package br.radar.alerta;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Camera data from OpenStreetMap, loaded on demand per map tile and cached on disk.
 *
 * The world is split into TILE_DEG-sized tiles. Only the tile the user is in and the tiles
 * a few kilometers ahead along the direction of travel are fetched, each with one small
 * request, and every tile is cached. A repeated trip therefore costs (almost) no API calls,
 * which keeps the public servers' fair-use limits.
 *
 * Several sources can serve a tile. They are ranked automatically (healthiest and fastest
 * first) or the user can prefer one; the rest remain as fallbacks. If every source fails,
 * previously cached tiles keep working even when stale.
 *
 * Tagging per the OSM wiki: speed cameras are nodes with highway=speed_camera; enforcement
 * relations (type=enforcement, enforcement=maxspeed|traffic_signals|average_speed) reference
 * their cameras as "device" members and carry the maxspeed.
 */
final class CameraStore {
    private static final String TAG = "RadarAlerta";
    private static final String TILE_DIR = "tiles";

    static final double TILE_DEG = 0.1;
    private static final int MAX_AREA_TILES = 12;
    /** How far ahead (meters, along the heading) to make sure data is loaded. */
    private static final double[] LOOKAHEAD_M = {0, 3_000, 8_000};
    private static final long TILE_TTL_MS = 3L * 24 * 3600 * 1000;
    private static final long TILE_PURGE_MS = 30L * 24 * 3600 * 1000;
    // Overpass asks clients to wait 30 s after HTTP 429/406; never retry a tile in a tight loop.
    private static final long RETRY_MS = 60_000;
    /** A source that failed this many times in a row is tried last for a while. */
    private static final int COOLDOWN_FAILS = 2;
    private static final long COOLDOWN_MS = 5 * 60_000;
    private static final long UNKNOWN_LATENCY_MS = 8_000;

    enum Kind { SPEED, RED_LIGHT, AVERAGE }

    static final class Camera {
        final long id;
        final double lat;
        final double lon;
        final int maxSpeed; // 0 = unknown
        final boolean mph;
        final Kind kind;

        Camera(long id, double lat, double lon, int maxSpeed, boolean mph, Kind kind) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
            this.maxSpeed = maxSpeed;
            this.mph = mph;
            this.kind = kind;
        }
    }

    /** A place tiles can be fetched from, with live health stats for ranking and the UI. */
    static final class Source {
        final String id;   // shown to the user (host name)
        final String url;
        final boolean mirror;      // static tile mirror (GET json) instead of an Overpass API (POST query)
        volatile long manifestAt;  // mirror only: when the manifest was last read OK
        volatile String manifestOsm = "";
        volatile long lastOkMs;
        volatile long lastFailMs;
        volatile int consecutiveFails;
        volatile long latencyMs;   // moving average of successful requests, 0 = unknown
        volatile String lastError = "";

        Source(String id, String url, boolean mirror) {
            this.id = id;
            this.url = url;
            this.mirror = mirror;
        }

        boolean coolingDown(long now) {
            return consecutiveFails >= COOLDOWN_FAILS && now - lastFailMs < COOLDOWN_MS;
        }
    }

    private static final class Tile {
        final long fetchedAt;
        final String osmBase;
        final List<Camera> cameras;

        Tile(long fetchedAt, String osmBase, List<Camera> cameras) {
            this.fetchedAt = fetchedAt;
            this.osmBase = osmBase;
            this.cameras = cameras;
        }
    }

    private final Context context;
    private final Settings settings;
    private final File dir;
    // Public instances (global, no API key; see the OSM wiki Overpass_API page). Order is the
    // tie-break when no stats exist yet.
    private final List<Source> sources = new ArrayList<>();
    private final Map<String, Tile> tiles = new HashMap<>();
    private final Map<String, Long> nextTry = new HashMap<>();
    private final Set<String> queue = new LinkedHashSet<>();
    private boolean worker;
    private volatile List<Camera> active = new ArrayList<>();
    private volatile String osmBase = "";
    private String currentKey = "";

    CameraStore(Context context) {
        this.context = context;
        this.settings = new Settings(context);
        dir = new File(context.getFilesDir(), TILE_DIR);
        dir.mkdirs();
        sources.addAll(newSources());
        purgeOld();
    }

    /** Fresh source list. Order is the tie-break when no stats exist yet. */
    static List<Source> newSources() {
        List<Source> list = new ArrayList<>();
        // Weekly static mirror first (fast, no rate limits); the live Overpass servers follow.
        list.add(new Source("zhyph.github.io", "https://zhyph.github.io/radar-tiles/", true));
        list.add(new Source("overpass-api.de", "https://overpass-api.de/api/interpreter", false));
        list.add(new Source("overpass.private.coffee", "https://overpass.private.coffee/api/interpreter", false));
        return list;
    }

    /** Cameras from all loaded tiles (deduplicated); safe to iterate from any thread. */
    List<Camera> cameras() { return active; }

    /** OSM timestamp of the oldest data in use, so the user sees how current the data is. */
    String osmBase() { return osmBase; }

    List<Source> sources() { return sources; }

    /**
     * True when the area the user is in has no data at all (nothing cached and the last fetch
     * failed), so no warnings are possible there. Stale data does not count: it still warns.
     */
    synchronized boolean currentAreaMissing() {
        return !currentKey.isEmpty() && !tiles.containsKey(currentKey) && nextTry.containsKey(currentKey);
    }

    synchronized int pending() { return queue.size() + (worker ? 1 : 0); }

    /**
     * Makes sure tiles for the current position and a few km ahead (if the heading is known)
     * are loaded, queuing a background fetch for any that are missing or older than the TTL.
     */
    synchronized void ensureCoverage(double lat, double lon, double bearingDeg) {
        Set<String> needed = new LinkedHashSet<>();
        for (double meters : LOOKAHEAD_M) {
            if (meters > 0 && Double.isNaN(bearingDeg)) break;
            double[] p = meters == 0 ? new double[] {lat, lon} : Geo.project(lat, lon, bearingDeg, meters);
            needed.add(key(Geo.tileIndex(p[0], TILE_DEG), Geo.tileIndex(p[1], TILE_DEG)));
        }
        requestTiles(needed);
    }

    /**
     * Loads cameras for the area shown on the map. Capped so zooming far out never triggers
     * a flood of requests; only tiles that are missing or stale are fetched.
     */
    synchronized void ensureArea(double south, double west, double north, double east) {
        int i0 = Geo.tileIndex(south, TILE_DEG), i1 = Geo.tileIndex(north, TILE_DEG);
        int j0 = Geo.tileIndex(west, TILE_DEG), j1 = Geo.tileIndex(east, TILE_DEG);
        if ((long) (i1 - i0 + 1) * (j1 - j0 + 1) > MAX_AREA_TILES) return;
        Set<String> needed = new LinkedHashSet<>();
        for (int i = i0; i <= i1; i++) {
            for (int j = j0; j <= j1; j++) needed.add(key(i, j));
        }
        requestTiles(needed);
    }

    /** Loads cached tiles from disk and queues fetches for missing or stale ones. */
    private void requestTiles(Set<String> needed) {
        currentKey = needed.iterator().next();
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (String k : needed) {
            Tile t = tiles.get(k);
            if (t == null) {
                t = loadFromDisk(k);
                if (t != null) {
                    tiles.put(k, t);
                    changed = true;
                }
            }
            boolean stale = t == null || now - t.fetchedAt > TILE_TTL_MS;
            Long retryAt = nextTry.get(k);
            if (stale && (retryAt == null || now >= retryAt)) queue.add(k);
        }
        if (changed) rebuildActive();
        startWorker();
    }

    /**
     * Downloads the 1 degree mirror squares around a position (radius in degrees, so 1 gives a
     * 3x3 block, roughly 330 km across) and caches them for offline use. Mirror only: it is a
     * static file host, unlike the public Overpass servers, which must not be bulk-queried.
     * Returns how many squares were fetched. Blocking: call it from a background thread.
     */
    int downloadArea(double lat, double lon, int radiusDeg) throws Exception {
        Source mirror = null;
        for (Source s : sources) if (s.mirror) mirror = s;
        if (mirror == null) throw new IllegalStateException("no mirror source");
        // Same manifest rule as normal fetches: a reachable manifest makes 404 mean "empty".
        if (get(mirror.url + "manifest.json") == null) throw new IllegalStateException("manifest missing");
        int la = (int) Math.floor(lat), lo = (int) Math.floor(lon), count = 0;
        for (int i = la - radiusDeg; i <= la + radiusDeg; i++) {
            for (int j = lo - radiusDeg; j <= lo + radiusDeg; j++) {
                Map<String, Tile> got = mirrorSquare(mirror, i, j, System.currentTimeMillis());
                for (Map.Entry<String, Tile> e : got.entrySet()) saveToDisk(e.getKey(), e.getValue());
                synchronized (this) {
                    tiles.putAll(got);
                    rebuildActive();
                }
                count++;
            }
        }
        return count;
    }

    /** Deletes every cached tile (disk and memory). */
    synchronized void clear() {
        tiles.clear();
        nextTry.clear();
        queue.clear();
        deleteTiles(context);
        rebuildActive();
    }

    static void deleteTiles(Context context) {
        File[] files = new File(context.getFilesDir(), TILE_DIR).listFiles();
        if (files == null) return;
        for (File f : files) f.delete();
    }

    /** {number of cached tiles, total bytes}; works whether or not the service is running. */
    static long[] cacheStats(Context context) {
        File[] files = new File(context.getFilesDir(), TILE_DIR).listFiles();
        long bytes = 0;
        if (files == null) return new long[] {0, 0};
        for (File f : files) bytes += f.length();
        return new long[] {files.length, bytes};
    }

    private void startWorker() {
        if (worker || queue.isEmpty()) return;
        worker = true;
        new Thread(() -> {
            while (true) {
                String k;
                synchronized (CameraStore.this) {
                    if (queue.isEmpty()) {
                        worker = false;
                        return;
                    }
                    k = queue.iterator().next();
                    queue.remove(k);
                }
                try {
                    synchronized (CameraStore.this) {
                        Tile have = tiles.get(k);
                        if (have != null && System.currentTimeMillis() - have.fetchedAt <= TILE_TTL_MS) continue;
                    }
                    int[] ij = parseKey(k);
                    Map<String, Tile> got = fetch(ij[0], ij[1]);
                    for (Map.Entry<String, Tile> e : got.entrySet()) saveToDisk(e.getKey(), e.getValue());
                    synchronized (CameraStore.this) {
                        tiles.putAll(got);
                        for (String key : got.keySet()) nextTry.remove(key);
                        rebuildActive();
                    }
                    Tile main = got.get(k);
                    Log.i(TAG, "tile " + k + ": " + (main == null ? 0 : main.cameras.size()) + " cameras ("
                            + got.size() + " tiles stored), OSM data from " + (main == null ? "" : main.osmBase));
                } catch (Throwable e) {
                    Log.w(TAG, "tile " + k + " fetch failed: " + e);
                    synchronized (CameraStore.this) {
                        nextTry.put(k, System.currentTimeMillis() + RETRY_MS);
                    }
                }
            }
        }, "radar-fetch").start();
    }

    /** Rebuilds the merged camera list from all loaded tiles. Caller holds the lock. */
    private void rebuildActive() {
        Map<Long, Camera> merged = new HashMap<>();
        String oldest = null;
        for (Tile t : tiles.values()) {
            for (Camera c : t.cameras) merged.putIfAbsent(c.id, c);
            if (!t.osmBase.isEmpty() && (oldest == null || t.osmBase.compareTo(oldest) < 0)) oldest = t.osmBase;
        }
        active = new ArrayList<>(merged.values());
        osmBase = oldest == null ? "" : oldest;
    }

    // ---- Tiles --------------------------------------------------------------------------

    private static String key(int iLat, int iLon) {
        return iLat + "_" + iLon;
    }

    private static int[] parseKey(String k) {
        String[] p = k.split("_");
        return new int[] {Integer.parseInt(p[0]), Integer.parseInt(p[1])};
    }

    // ---- Sources ------------------------------------------------------------------------

    /**
     * Order in which to try sources: healthy before cooling-down, then fastest first. A
     * source the user prefers goes first regardless; the others stay as fallbacks.
     */
    List<Source> ranked(String preferredId, long now) {
        List<Source> order = new ArrayList<>(sources);
        Collections.sort(order, (a, b) -> {
            boolean ca = a.coolingDown(now), cb = b.coolingDown(now);
            if (ca != cb) return ca ? 1 : -1;
            long la = a.latencyMs == 0 ? UNKNOWN_LATENCY_MS : a.latencyMs;
            long lb = b.latencyMs == 0 ? UNKNOWN_LATENCY_MS : b.latencyMs;
            return Long.compare(la, lb); // stable sort keeps list order on ties
        });
        if (!Settings.SOURCE_AUTO.equals(preferredId)) {
            for (int i = 0; i < order.size(); i++) {
                if (order.get(i).id.equals(preferredId)) {
                    order.add(0, order.remove(i));
                    break;
                }
            }
        }
        return order;
    }

    /** Fetches the tile (and any others the same request covers) from the best working source. */
    private Map<String, Tile> fetch(int iLat, int iLon) throws Exception {
        Exception last = null;
        for (Source src : ranked(settings.source(), System.currentTimeMillis())) {
            long start = System.currentTimeMillis();
            try {
                Map<String, Tile> result = src.mirror ? fetchMirror(src, iLat, iLon) : fetchOverpass(src, iLat, iLon);
                long took = System.currentTimeMillis() - start;
                src.latencyMs = src.latencyMs == 0 ? took : (src.latencyMs + took) / 2;
                src.lastOkMs = System.currentTimeMillis();
                src.consecutiveFails = 0;
                src.lastError = "";
                return result;
            } catch (Exception e) {
                Log.w(TAG, "source " + src.id + " failed: " + e);
                src.lastFailMs = System.currentTimeMillis();
                src.consecutiveFails++;
                src.lastError = String.valueOf(e.getMessage());
                last = e;
            }
        }
        throw last;
    }

    private Map<String, Tile> fetchOverpass(Source src, int iLat, int iLon) throws Exception {
        double s = iLat * TILE_DEG, w = iLon * TILE_DEG;
        String bbox = s + "," + w + "," + (s + TILE_DEG) + "," + (w + TILE_DEG);
        String query = "[out:json][timeout:25][bbox:" + bbox + "];("
                + "node[\"highway\"=\"speed_camera\"];"
                + "rel[\"type\"=\"enforcement\"][\"enforcement\"~\"^(maxspeed|traffic_signals|average_speed)$\"];"
                + ");out geom;";
        Parsed p = parse(post(src.url, query));
        Map<String, Tile> out = new HashMap<>();
        out.put(key(iLat, iLon), new Tile(System.currentTimeMillis(), p.osmBase, p.cameras));
        return out;
    }

    /**
     * Static mirror: one JSON file per 1 degree square (t/LAT_LON.json, named by its south-west
     * corner). A missing file (404) means no cameras there, but only when the manifest was
     * reachable, so a down server is never mistaken for an empty area. The 1 degree file is
     * split into the 100 tiles of the app's grid so one request covers about 110 km.
     */
    private Map<String, Tile> fetchMirror(Source src, int iLat, int iLon) throws Exception {
        long now = System.currentTimeMillis();
        if (now - src.manifestAt > 10 * 60_000) {
            String manifest = get(src.url + "manifest.json");
            if (manifest == null) throw new IllegalStateException("manifest missing");
            src.manifestOsm = new JSONObject(manifest).optString("osm", "");
            src.manifestAt = now;
        }
        return mirrorSquare(src, Math.floorDiv(iLat, 10), Math.floorDiv(iLon, 10), now);
    }

    /** The 100 tiles of one 1 degree mirror square, named by its south-west corner. */
    private Map<String, Tile> mirrorSquare(Source src, int latDeg, int lonDeg, long now) throws Exception {
        String body = get(src.url + "t/" + latDeg + "_" + lonDeg + ".json");
        List<Camera> all = new ArrayList<>();
        if (body != null) {
            JSONArray arr = new JSONObject(body).getJSONArray("cameras");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Kind kind;
                try {
                    kind = Kind.valueOf(o.optString("kind", "SPEED"));
                } catch (IllegalArgumentException ex) {
                    kind = Kind.SPEED;
                }
                all.add(new Camera(o.getLong("id"), o.getDouble("lat"), o.getDouble("lon"),
                        o.getInt("max"), o.optBoolean("mph"), kind));
            }
        }
        Map<String, List<Camera>> buckets = new HashMap<>();
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) buckets.put(key(latDeg * 10 + i, lonDeg * 10 + j), new ArrayList<>());
        }
        for (Camera c : all) {
            List<Camera> b = buckets.get(key(Geo.tileIndex(c.lat, TILE_DEG), Geo.tileIndex(c.lon, TILE_DEG)));
            if (b != null) b.add(c);
        }
        Map<String, Tile> out = new HashMap<>();
        for (Map.Entry<String, List<Camera>> e : buckets.entrySet()) {
            out.put(e.getKey(), new Tile(now, src.manifestOsm, e.getValue()));
        }
        return out;
    }

    /** Anonymous OSM map note (POST /api/0.6/notes, no login needed). */
    static boolean postNote(double lat, double lon, String text) {
        try {
            String q = String.format(java.util.Locale.US, "lat=%.6f&lon=%.6f&text=%s", lat, lon,
                    java.net.URLEncoder.encode(text, "UTF-8"));
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.openstreetmap.org/api/0.6/notes").openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(20_000);
                conn.setDoOutput(true);
                conn.setRequestProperty("User-Agent", "radar-alerta/0.1 (personal use)");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.getOutputStream().write(q.getBytes("UTF-8"));
                return conn.getResponseCode() == 200;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** GET returning the body, or null for HTTP 404; any other failure throws. */
    private static String get(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setRequestProperty("User-Agent", "radar-alerta/0.1 (personal use)");
            int code = conn.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static String post(String url, String query) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(60_000); // busy public servers can take 30+ s to answer
            conn.setDoOutput(true);
            // Overpass rejects anonymous clients (HTTP 406); identify the app.
            conn.setRequestProperty("User-Agent", "radar-alerta/0.1 (personal use)");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(("data=" + URLEncoder.encode(query, "UTF-8")).getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    private static final class Parsed {
        List<Camera> cameras = new ArrayList<>();
        String osmBase = "";
    }

    private static Parsed parse(String json) throws Exception {
        Parsed out = new Parsed();
        JSONObject root = new JSONObject(json);
        JSONObject meta = root.optJSONObject("osm3s");
        if (meta != null) out.osmBase = meta.optString("timestamp_osm_base", "");
        Set<Long> seen = new HashSet<>();
        JSONArray elements = root.getJSONArray("elements");
        for (int i = 0; i < elements.length(); i++) {
            JSONObject e = elements.getJSONObject(i);
            JSONObject tags = e.optJSONObject("tags");
            String type = e.optString("type");
            if (type.equals("node")) {
                add(out.cameras, seen, e.getLong("id"), e.getDouble("lat"), e.getDouble("lon"), tags, Kind.SPEED);
            } else if (type.equals("relation")) {
                JSONArray members = e.optJSONArray("members");
                if (members == null) continue;
                Kind kind = kindOf(tags);
                for (int j = 0; j < members.length(); j++) {
                    JSONObject m = members.getJSONObject(j);
                    if (!"device".equals(m.optString("role")) || !"node".equals(m.optString("type"))) continue;
                    if (!m.has("lat")) continue; // member outside the queried area has no geometry
                    add(out.cameras, seen, m.getLong("ref"), m.getDouble("lat"), m.getDouble("lon"), tags, kind);
                }
            }
        }
        return out;
    }

    private static Kind kindOf(JSONObject relationTags) {
        String e = relationTags == null ? "" : relationTags.optString("enforcement", "");
        if (e.equals("traffic_signals")) return Kind.RED_LIGHT;
        if (e.equals("average_speed")) return Kind.AVERAGE;
        return Kind.SPEED;
    }

    private static void add(List<Camera> list, Set<Long> seen, long id, double lat, double lon,
                            JSONObject tags, Kind kind) {
        if (!seen.add(id)) return;
        int speed = 0;
        boolean mph = false;
        if (tags != null) {
            // maxspeed is "50", "50 mph", "signals", "none"...; only plain numbers are usable.
            String v = tags.optString("maxspeed", "").trim().toLowerCase();
            mph = v.endsWith("mph");
            String digits = v.replace("mph", "").replace("km/h", "").trim();
            try {
                speed = Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                speed = 0;
            }
        }
        list.add(new Camera(id, lat, lon, speed, mph, kind));
    }

    // ---- Disk cache -----------------------------------------------------------------------

    private File file(String k) {
        return new File(dir, "t_" + k + ".json");
    }

    private void saveToDisk(String k, Tile t) {
        try {
            JSONArray arr = new JSONArray();
            for (Camera c : t.cameras) {
                arr.put(new JSONObject().put("id", c.id).put("lat", c.lat).put("lon", c.lon)
                        .put("max", c.maxSpeed).put("mph", c.mph).put("kind", c.kind.name()));
            }
            JSONObject root = new JSONObject().put("time", t.fetchedAt).put("osm", t.osmBase).put("cameras", arr);
            try (FileOutputStream out = new FileOutputStream(file(k))) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable e) {
            Log.w(TAG, "tile save failed: " + e);
        }
    }

    private Tile loadFromDisk(String k) {
        File f = file(k);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject root = new JSONObject(readAll(in));
            JSONArray arr = root.getJSONArray("cameras");
            List<Camera> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Kind kind;
                try {
                    kind = Kind.valueOf(o.optString("kind", "SPEED"));
                } catch (IllegalArgumentException ex) {
                    kind = Kind.SPEED;
                }
                list.add(new Camera(o.getLong("id"), o.getDouble("lat"), o.getDouble("lon"),
                        o.getInt("max"), o.optBoolean("mph"), kind));
            }
            return new Tile(root.getLong("time"), root.optString("osm", ""), list);
        } catch (Throwable e) {
            Log.w(TAG, "tile load failed: " + e);
            return null;
        }
    }

    /** Deletes tiles not refreshed for a long time so the cache cannot grow without bound. */
    private void purgeOld() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - TILE_PURGE_MS;
        for (File f : files) {
            if (f.lastModified() < cutoff) f.delete();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        return buf.toString("UTF-8");
    }
}
