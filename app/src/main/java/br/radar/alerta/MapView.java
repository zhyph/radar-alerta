package br.radar.alerta;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.animation.ValueAnimator;
import android.graphics.Camera;
import android.graphics.Matrix;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small slippy map: OpenStreetMap raster tiles darkened with a color filter, with camera
 * markers, the user's position and the alert range drawn on top. No map SDK needed.
 *
 * Tile policy (operations.osmfoundation.org/policies/tiles): only tiles for the visible
 * view are requested (no prefetching or offline areas), tiles are cached on disk for at
 * least 7 days, requests carry an identifying User-Agent, and attribution is always shown.
 */
final class MapView extends View {
    interface CameraProvider {
        List<CameraStore.Camera> cameras();
    }

    interface Listener {
        /** The user finished panning/zooming: the visible area, so data can be loaded for it. */
        void onViewportSettled(double south, double west, double north, double east, double zoom);

        /** Following the user's position was switched on or off by a gesture. */
        void onFollowChanged(boolean following);
    }

    private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final String USER_AGENT = "RadarAlerta/0.1 (personal Android project)";
    private static final long TILE_MIN_CACHE_MS = 7L * 24 * 3600 * 1000;
    private static final int MIN_ZOOM = 3, MAX_ZOOM = 18, MAX_TILE_ZOOM = 19;

    private final LruCache<String, Bitmap> memory = new LruCache<>(140);
    private final Set<String> loading = new HashSet<>();
    // Newest request first: while moving, tiles asked for a moment ago are often off screen already.
    private final ExecutorService pool = new java.util.concurrent.ThreadPoolExecutor(3, 3, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingDeque<Runnable>() {
                @Override
                public boolean offer(Runnable r) { return offerFirst(r); }
            });
    private final File cacheDir;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint userFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    private final Path arrow = new Path();
    private final RectF rect = new RectF();

    private final GestureDetector gestures;
    private final ScaleGestureDetector scaler;

    private CameraProvider cameras = () -> java.util.Collections.emptyList();
    private Listener listener;
    private final Runnable settled = this::fireSettled;

    private double centerLat = 20, centerLon = 0, zoom = 2;
    private boolean following;
    private boolean hasUser;
    private double userLat, userLon;
    private float userBearing = Float.NaN;
    private double alertRangeM;

    // Navigation view: heading-up, tilted, zoomed by speed. navBlend 0 = flat north-up, 1 = full nav.
    private static final float NAV_TILT_DEG = 52f;
    private static final float NAV_PIVOT_Y = 0.68f;     // user sits this far down the screen
    private static final float START_MS = 3f, STOP_MS = 1f;
    private float navBlend, heading;
    private boolean navOn;
    private ValueAnimator anim;
    private final Matrix navM = new Matrix();
    private final Camera camera = new Camera();
    /** log2(screen density): tiles are drawn at dp size so labels stay readable on dense screens. */
    private final double zOffset;

    MapView(Context context) {
        super(context);
        zOffset = Math.log(context.getResources().getDisplayMetrics().density) / Math.log(2);
        cacheDir = new File(context.getCacheDir(), "maptiles");
        cacheDir.mkdirs();

        // Dark look from the standard (light) tiles: invert, rotate hue back so water and
        // greens keep their character, then dim slightly to sit on the app's dark surface.
        ColorMatrix m = new ColorMatrix(new float[] {
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0});
        m.postConcat(rotateHue(180));
        ColorMatrix dim = new ColorMatrix();
        dim.setScale(0.82f, 0.86f, 0.95f, 1f);
        m.postConcat(dim);
        tilePaint.setColorFilter(new ColorMatrixColorFilter(m));

        outline.setStyle(Paint.Style.STROKE);
        outline.setColor(Ui.BG);
        outline.setStrokeWidth(Ui.dp(context, 1.5f));
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Ui.AMBER);
        ring.setAlpha(170);
        ring.setStrokeWidth(Ui.dp(context, 2));
        ring.setPathEffect(new DashPathEffect(new float[] {Ui.dp(context, 8), Ui.dp(context, 6)}, 0));
        userFill.setColor(Ui.TEXT);
        // Dark fade under the status bar so the clock stays legible over map content.
        scrim.setShader(new android.graphics.LinearGradient(0, 0, 0, Ui.dp(context, 96),
                0xCC0E1116, 0x000E1116, android.graphics.Shader.TileMode.CLAMP));
        label.setTextSize(Ui.dp(context, 11));
        label.setColor(Ui.TEXT_2);
        pill.setColor(0xCC0E1116);

        gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                panByPixels(dx, dy);
                setFollowing(false);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                setZoom(zoom + 1);
                scheduleSettled();
                return true;
            }
        });
        scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                setZoom(zoom + Math.log(d.getScaleFactor()) / Math.log(2));
                return true;
            }
        });
    }

    /** 4x5 hue rotation matrix (degrees), as used by CSS hue-rotate. */
    private static ColorMatrix rotateHue(float degrees) {
        double r = Math.toRadians(degrees), c = Math.cos(r), s = Math.sin(r);
        float[] a = {
                (float) (0.213 + c * 0.787 - s * 0.213), (float) (0.715 - c * 0.715 - s * 0.715), (float) (0.072 - c * 0.072 + s * 0.928), 0, 0,
                (float) (0.213 - c * 0.213 + s * 0.143), (float) (0.715 + c * 0.285 + s * 0.140), (float) (0.072 - c * 0.072 - s * 0.283), 0, 0,
                (float) (0.213 - c * 0.213 - s * 0.787), (float) (0.715 - c * 0.715 + s * 0.715), (float) (0.072 + c * 0.928 + s * 0.072), 0, 0,
                0, 0, 0, 1, 0};
        return new ColorMatrix(a);
    }

    // ---- Public API ---------------------------------------------------------------------

    void setCameraProvider(CameraProvider provider) { cameras = provider; }

    void setListener(Listener l) { listener = l; }

    boolean isFollowing() { return following; }

    void setFollowing(boolean value) {
        if (following == value) return;
        following = value;
        if (value && hasUser) {
            centerLat = userLat;
            centerLon = userLon;
            invalidate();
        }
        if (!value && navBlend > 0) glideTo(centerLat, centerLon, false, heading, zoom);
        if (listener != null) listener.onFollowChanged(value);
    }

    void centerOn(double lat, double lon, double newZoom) {
        centerLat = lat;
        centerLon = lon;
        zoom = clampZoom(newZoom);
        invalidate();
        scheduleSettled();
    }

    boolean hasUser() { return hasUser; }

    /** Updates the user marker; when following, glides the view and enters/leaves navigation mode. */
    void setUser(double lat, double lon, float bearingDeg, float speedMs) {
        boolean first = !hasUser;
        hasUser = true;
        userLat = lat;
        userLon = lon;
        userBearing = bearingDeg;
        // Once navigating, a missing bearing (slow creep) keeps the last heading instead of dropping out.
        boolean wantNav = following && (navOn ? speedMs >= STOP_MS : !Float.isNaN(bearingDeg) && speedMs >= START_MS);
        float headingTo = Float.isNaN(bearingDeg) ? heading : bearingDeg;
        if (following) glideTo(lat, lon, wantNav, wantNav ? headingTo : heading, wantNav ? navZoomFor(speedMs) : zoom);
        else if (navBlend > 0) glideTo(centerLat, centerLon, false, heading, zoom);
        navOn = wantNav;
        invalidate();
        if (first) scheduleSettled();
    }

    /** Closer when slow, wider when fast, so the next few hundred meters stay on screen. */
    private double navZoomFor(float speedMs) {
        final float[] edges = {8, 16, 25};
        final double[] zooms = {17.2, 16.7, 16.1, 15.5};
        // 1.5 m/s of slack either side so speed jitter near an edge does not flip the zoom.
        while (navLevel < 3 && speedMs > edges[navLevel] + 1.5f) navLevel++;
        while (navLevel > 0 && speedMs < edges[navLevel - 1] - 1.5f) navLevel--;
        return zooms[navLevel];
    }
    private int navLevel;

    private void glideTo(double lat, double lon, boolean nav, float headingTo, double zoomTo) {
        if (anim != null) anim.cancel();
        final double lat0 = centerLat, lon0 = centerLon, zoom0 = zoom;
        final float blend0 = navBlend, blend1 = nav ? 1f : 0f;
        final float h0 = blend0 == 0f && nav ? headingTo : heading;
        float dh = ((headingTo - h0 + 540f) % 360f) - 180f;   // shortest way round
        final float dHeading = nav ? dh : 0f;
        final boolean move = following;
        heading = h0;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(950);
        anim.setInterpolator(new android.view.animation.LinearInterpolator());
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            navBlend = blend0 + (blend1 - blend0) * Math.min(1f, t * 1.4f);
            heading = h0 + dHeading * t;
            zoom = zoom0 + (zoomTo - zoom0) * t;
            if (move) {
                centerLat = lat0 + (lat - lat0) * t;
                centerLon = lon0 + (lon - lon0) * t;
            }
            invalidate();
        });
        anim.start();
    }

    void clearUser() {
        hasUser = false;
        invalidate();
    }

    /** Draws a dashed ring of this radius (meters) around the user; 0 hides it. */
    void setAlertRange(double meters) {
        alertRangeM = meters;
        invalidate();
    }

    void refresh() { invalidate(); }

    // ---- Projection (Web Mercator, 256 px tiles) ---------------------------------------

    private static double worldSize(double z) { return 256.0 * Math.pow(2, z); }

    private static double lonToX(double lon, double z) { return (lon + 180) / 360 * worldSize(z); }

    private static double latToY(double lat, double z) {
        double s = Math.sin(Math.toRadians(Math.max(-85.05, Math.min(85.05, lat))));
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * worldSize(z);
    }

    private static double xToLon(double x, double z) { return x / worldSize(z) * 360 - 180; }

    private static double yToLat(double y, double z) {
        double n = Math.PI - 2 * Math.PI * y / worldSize(z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private float screenX(double lon) {
        return (float) (getWidth() / 2.0 + lonToX(lon, z()) - lonToX(centerLon, z()));
    }

    private float screenY(double lat) {
        return (float) (getHeight() / 2.0 + latToY(lat, z()) - latToY(centerLat, z()));
    }

    /** Effective tile zoom: user zoom plus the density offset. */
    private double z() { return zoom + zOffset; }

    private static double clampZoom(double z) { return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z)); }

    private void setZoom(double z) {
        zoom = clampZoom(z);
        invalidate();
    }

    private void panByPixels(float dx, float dy) {
        double x = lonToX(centerLon, z()) + dx;
        double y = latToY(centerLat, z()) + dy;
        centerLon = xToLon(x, z());
        centerLat = yToLat(y, z());
        invalidate();
    }

    // ---- Gestures and viewport ---------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) gestures.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            scheduleSettled();
        }
        if (e.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) setFollowing(false);
        getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private void scheduleSettled() {
        removeCallbacks(settled);
        postDelayed(settled, 600);
    }

    private void fireSettled() {
        if (listener == null || getWidth() == 0) return;
        double halfW = Math.max(getWidth() / 2.0, visibleRadius()), halfH = Math.max(getHeight() / 2.0, visibleRadius());
        double cx = lonToX(centerLon, z()), cy = latToY(centerLat, z());
        listener.onViewportSettled(yToLat(cy + halfH, z()), xToLon(cx - halfW, z()),
                yToLat(cy - halfH, z()), xToLon(cx + halfW, z()), zoom);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        scheduleSettled();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        removeCallbacks(settled);
        pool.shutdownNow();
        super.onDetachedFromWindow();
    }

    // ---- Drawing -----------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Ui.BG);
        updateNavMatrix();
        canvas.save();
        canvas.concat(navM);
        drawTiles(canvas);
        drawCameras(canvas);
        drawUser(canvas);
        canvas.restore();
        canvas.drawRect(0, 0, getWidth(), Ui.dp(getContext(), 96), scrim);
        drawAttribution(canvas);
    }

    /** Heading-up rotation about the user, tilt with perspective, user moved down the screen. */
    private void updateNavMatrix() {
        navM.reset();
        if (navBlend <= 0f) return;
        float w = getWidth(), h = getHeight();
        camera.save();
        camera.setLocation(0, 0, -h / 72f * 1.1f);   // Camera units are inches at 72 px/in
        camera.rotateX(NAV_TILT_DEG * navBlend);
        camera.getMatrix(navM);
        camera.restore();
        navM.preRotate(-heading * navBlend);
        navM.preTranslate(-w / 2, -h / 2);
        navM.postTranslate(w / 2, h / 2 + (NAV_PIVOT_Y - 0.5f) * h * navBlend);
    }

    private static final int PARENT_DZ = 2;
    private int tileZ = -1;
    private final android.graphics.Rect src = new android.graphics.Rect();
    private final float[] pts = new float[8];

    private boolean onScreen(float l, float t, float r, float b) {
        if (navBlend <= 0f) return true;
        pts[0] = l; pts[1] = t; pts[2] = r; pts[3] = t; pts[4] = r; pts[5] = b; pts[6] = l; pts[7] = b;
        navM.mapPoints(pts);
        float minX = Math.min(Math.min(pts[0], pts[2]), Math.min(pts[4], pts[6]));
        float maxX = Math.max(Math.max(pts[0], pts[2]), Math.max(pts[4], pts[6]));
        float minY = Math.min(Math.min(pts[1], pts[3]), Math.min(pts[5], pts[7]));
        float maxY = Math.max(Math.max(pts[1], pts[3]), Math.max(pts[5], pts[7]));
        return maxX >= 0 && minX <= getWidth() && maxY >= 0 && minY <= getHeight();
    }

    /** Half-size (px) of the map area that can be visible; larger when rotated and tilted. */
    private double visibleRadius() {
        return navBlend > 0f ? getHeight() * 1.5 : 0;
    }

    private void drawTiles(Canvas canvas) {
        // Sticky tile zoom: only switch levels once the view has clearly moved past the halfway point.
        if (tileZ < 0 || Math.abs(z() - tileZ) > 0.8) tileZ = (int) Math.round(z());
        tileZ = Math.max(MIN_ZOOM, Math.min(MAX_TILE_ZOOM, tileZ));
        int tz = tileZ;
        float scale = (float) Math.pow(2, z() - tz);
        double cx = lonToX(centerLon, tz), cy = latToY(centerLat, tz);
        double halfW = Math.max(getWidth() / 2.0, visibleRadius()) / scale;
        double halfH = Math.max(getHeight() / 2.0, visibleRadius()) / scale;
        int n = 1 << tz;
        int x0 = (int) Math.floor((cx - halfW) / 256), x1 = (int) Math.floor((cx + halfW) / 256);
        int y0 = (int) Math.floor((cy - halfH) / 256), y1 = (int) Math.floor((cy + halfH) / 256);
        // Farthest first: requests run newest-first, so the tiles nearest the user load first.
        java.util.ArrayList<int[]> wanted = new java.util.ArrayList<>();
        for (int ty = Math.max(0, y0); ty <= Math.min(n - 1, y1); ty++) {
            for (int tx = x0; tx <= x1; tx++) {
                float left = (float) (getWidth() / 2.0 + (tx * 256 - cx) * scale);
                float top = (float) (getHeight() / 2.0 + (ty * 256 - cy) * scale);
                if (!onScreen(left, top, left + 256 * scale, top + 256 * scale)) continue;
                double dx = tx * 256 + 128 - cx, dy = ty * 256 + 128 - cy;
                wanted.add(new int[] {tx, ty, (int) Math.min(Integer.MAX_VALUE, dx * dx + dy * dy)});
            }
        }
        wanted.sort((p, q) -> Integer.compare(q[2], p[2]));
        for (int[] t : wanted) {
            int tx = t[0], ty = t[1], wrapped = ((tx % n) + n) % n;
            float left = (float) (getWidth() / 2.0 + (tx * 256 - cx) * scale);
            float top = (float) (getHeight() / 2.0 + (ty * 256 - cy) * scale);
            rect.set(left, top, left + 256 * scale + 1, top + 256 * scale + 1);
            Bitmap b = tile(tz, wrapped, ty);
            if (b != null) {
                canvas.drawBitmap(b, null, rect, tilePaint);
            } else if (tz - PARENT_DZ >= MIN_ZOOM) {
                // Blurry stand-in from a coarser tile (one request covers 16 tiles) until this one arrives.
                Bitmap parent = tile(tz - PARENT_DZ, wrapped >> PARENT_DZ, ty >> PARENT_DZ);
                if (parent != null) {
                    int size = 256 >> PARENT_DZ, mask = (1 << PARENT_DZ) - 1;
                    src.set((wrapped & mask) * size, (ty & mask) * size, (wrapped & mask) * size + size, (ty & mask) * size + size);
                    canvas.drawBitmap(parent, src, rect, tilePaint);
                }
            }
        }
    }

    private void drawCameras(Canvas canvas) {
        float r = Ui.dp(getContext(), zoom >= 13 ? 5 : zoom >= 11 ? 4 : 3);
        int w = getWidth(), h = getHeight();
        for (CameraStore.Camera c : cameras.cameras()) {
            float x = screenX(c.lon), y = screenY(c.lat);
            if (navBlend > 0f) {
                if (Math.hypot(x - w / 2f, y - h / 2f) > visibleRadius()) continue;
            } else if (x < -r || y < -r || x > w + r || y > h + r) continue;
            dot.setColor(colorOf(c.kind));
            canvas.drawCircle(x, y, r, dot);
            canvas.drawCircle(x, y, r, outline);
        }
    }

    static int colorOf(CameraStore.Kind kind) {
        switch (kind) {
            case RED_LIGHT: return Ui.FAIL;
            case AVERAGE: return 0xFF7DD3FC;
            default: return Ui.AMBER;
        }
    }

    private void drawUser(Canvas canvas) {
        if (!hasUser) return;
        float x = screenX(userLon), y = screenY(userLat);
        if (alertRangeM > 0) {
            double metersPerPx = 156543.03392 * Math.cos(Math.toRadians(userLat)) / Math.pow(2, z());
            canvas.drawCircle(x, y, (float) (alertRangeM / metersPerPx), ring);
        }
        float r = Ui.dp(getContext(), 8);
        if (!Float.isNaN(userBearing)) {
            canvas.save();
            canvas.rotate(userBearing, x, y);
            arrow.reset();
            arrow.moveTo(x, y - r * 2.4f);
            arrow.lineTo(x - r * 0.9f, y - r * 0.6f);
            arrow.lineTo(x + r * 0.9f, y - r * 0.6f);
            arrow.close();
            userFill.setColor(Ui.TEXT);
            canvas.drawPath(arrow, userFill);
            canvas.restore();
        }
        userFill.setColor(Ui.TEXT);
        canvas.drawCircle(x, y, r, userFill);
        dot.setColor(Ui.AMBER);
        canvas.drawCircle(x, y, r * 0.55f, dot);
    }

    private void drawAttribution(Canvas canvas) {
        String text = "© OpenStreetMap contributors";
        float pad = Ui.dp(getContext(), 6);
        float w = label.measureText(text);
        float right = getWidth() - Ui.dp(getContext(), 8);
        float bottom = getHeight() - Ui.dp(getContext(), 8);
        rect.set(right - w - pad * 2, bottom - label.getTextSize() - pad, right, bottom + pad / 2);
        canvas.drawRoundRect(rect, pad, pad, pill);
        canvas.drawText(text, rect.left + pad, bottom - pad / 2 - 1, label);
    }

    // ---- Tiles: memory, disk cache, network --------------------------------------------

    private Bitmap tile(int z, int x, int y) {
        String key = z + "/" + x + "/" + y;
        Bitmap b = memory.get(key);
        if (b != null) return b;
        synchronized (loading) {
            if (!loading.add(key)) return null;
        }
        pool.execute(() -> {
            try {
                Bitmap loaded = loadTile(z, x, y);
                if (loaded != null) {
                    memory.put(key, loaded);
                    postInvalidate();
                }
            } finally {
                synchronized (loading) {
                    loading.remove(key);
                }
            }
        });
        return null;
    }

    private Bitmap loadTile(int z, int x, int y) {
        File f = new File(cacheDir, z + "_" + x + "_" + y + ".png");
        boolean fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < TILE_MIN_CACHE_MS;
        if (!fresh) {
            try {
                byte[] data = download(String.format(java.util.Locale.US, TILE_URL, z, x, y));
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(data);
                }
            } catch (Exception e) {
                if (!f.exists()) return null; // offline and never seen: leave the tile blank
            }
        }
        return BitmapFactory.decodeFile(f.getPath());
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            if (conn.getResponseCode() != 200) throw new IllegalStateException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                return buf.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }
}
