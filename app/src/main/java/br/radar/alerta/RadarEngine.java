package br.radar.alerta;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Looper;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/** Watches GPS and speaks a warning when a known camera is ahead. */
final class RadarEngine implements LocationListener {
    private static final String TAG = "RadarAlerta";

    private static final double MAX_BEARING_DIFF_DEG = 50;
    private static final float MIN_SPEED_MS = 3f;
    /** "Only warn when over the limit" still warns when within this many km/h (or mph) below it. */
    private static final double OVER_LIMIT_TOLERANCE = 5;
    private static final long REALERT_MS = 3 * 60 * 1000L;
    // OSM often maps one physical camera spot as several nodes (one per lane/direction), so an
    // alert silences every camera within this distance of the one that triggered it.
    private static final double CLUSTER_M = 250;

    private static RadarEngine instance;

    static synchronized RadarEngine start(Context context) {
        if (instance == null) {
            instance = new RadarEngine(context.getApplicationContext());
            instance.begin();
        }
        return instance;
    }

    static synchronized void stop() {
        if (instance != null) {
            instance.end();
            instance = null;
        }
    }

    static synchronized RadarEngine running() { return instance; }

    private final Context context;
    private final CameraStore store;
    private final Settings settings;
    private volatile long lastAlertAt;
    private volatile CameraStore.Camera lastAlertCamera;
    private final Map<Long, Long> lastAlert = new HashMap<>();
    private LocationManager locationManager;
    private AlertSound sound;
    private Location previous;
    private volatile Location lastFix;
    private volatile float lastBearing = Float.NaN;
    private volatile float lastSpeed;
    private volatile double lastRangeM;
    private volatile CameraStore.Camera nextCamera;
    private volatile double nextDistM;
    private static final double NEXT_RANGE_M = 1_500;

    private RadarEngine(Context context) {
        this.context = context;
        this.store = new CameraStore(context);
        this.settings = new Settings(context);
    }

    CameraStore store() { return store; }

    int cameraCount() { return store.cameras().size(); }

    /** Latest GPS fix (null until the first one) and the travel bearing if known. */
    Location lastFix() { return lastFix; }

    float lastBearing() { return lastBearing; }

    float lastSpeed() { return lastSpeed; }

    /** Nearest enabled camera ahead within 1.5 km (null if none), for the limit display. */
    CameraStore.Camera nextCamera() { return nextCamera; }

    double nextDistM() { return nextDistM; }

    /** Distance (m) at which a camera ahead triggers the siren at the current speed. */
    double alertRangeM() { return lastRangeM; }

    long lastAlertAt() { return lastAlertAt; }

    CameraStore.Camera lastAlertCamera() { return lastAlertCamera; }

    String osmBase() { return store.osmBase(); }

    private void begin() {
        sound = new AlertSound(context);
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, this,
                    Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "location permission missing", e);
        }
    }

    private void end() {
        SpeedWidget.push(context, null, true);
        try {
            locationManager.removeUpdates(this);
        } catch (Throwable ignored) { }
        if (sound != null) {
            sound.release();
            sound = null;
        }
    }

    @Override
    public void onLocationChanged(Location loc) {
        try {
            handle(loc);
        } catch (Throwable t) {
            Log.e(TAG, "location handling failed", t);
        }
    }

    private void handle(Location loc) {
        // Providers may omit bearing/speed (GPS drops bearing at low speed), so derive both
        // from movement since the last distinct position when they are missing.
        float bearing = loc.hasBearing() ? loc.getBearing() : Float.NaN;
        float speed = loc.hasSpeed() ? loc.getSpeed() : Float.NaN;
        if (previous != null) {
            double moved = Geo.distanceM(previous.getLatitude(), previous.getLongitude(),
                    loc.getLatitude(), loc.getLongitude());
            double dt = (loc.getTime() - previous.getTime()) / 1000.0;
            if (Float.isNaN(speed) && dt > 0) speed = (float) (moved / dt);
            if (Float.isNaN(bearing) && moved > 3) {
                bearing = (float) Geo.bearingDeg(previous.getLatitude(), previous.getLongitude(),
                        loc.getLatitude(), loc.getLongitude());
            }
        }
        if (previous == null || Geo.distanceM(previous.getLatitude(), previous.getLongitude(),
                loc.getLatitude(), loc.getLongitude()) > 3) {
            previous = loc;
        }
        lastFix = loc;
        lastBearing = bearing;
        lastSpeed = Float.isNaN(speed) ? 0f : speed;
        SpeedWidget.push(context, this, false);
        store.ensureCoverage(loc.getLatitude(), loc.getLongitude(), bearing);
        if (Float.isNaN(bearing) || Float.isNaN(speed) || speed < MIN_SPEED_MS) return;

        double range = Geo.alertDistanceM(speed, settings.leadSeconds());
        lastRangeM = range;
        long now = System.currentTimeMillis();
        CameraStore.Camera nearest = null;
        double nearestDist = NEXT_RANGE_M;
        boolean directionFilter = settings.directionFilter();
        CameraStore.Camera toAnnounce = null;
        double announceDist = 0;
        for (CameraStore.Camera c : store.cameras()) {
            if (!settings.kindEnabled(c.kind)) continue;
            double dist = Geo.distanceM(loc.getLatitude(), loc.getLongitude(), c.lat, c.lon);
            if (dist > NEXT_RANGE_M) continue;
            if (!Geo.isAhead(loc.getLatitude(), loc.getLongitude(), bearing, c.lat, c.lon,
                    MAX_BEARING_DIFF_DEG)) continue;
            // Skip cameras on parallel streets: they are ahead but too far to the side of our road.
            if (Geo.lateralOffsetM(loc.getLatitude(), loc.getLongitude(), bearing, c.lat, c.lon)
                    > Geo.lateralToleranceM(dist)) continue;
            if (directionFilter && !Geo.watchesTravel(bearing, c.facing)) continue;
            if (dist < nearestDist) {
                nearest = c;
                nearestDist = dist;
            }
            if (dist > range) continue;
            if (settings.onlyWhenOver() && c.maxSpeed > 0
                    && !Geo.nearOrOverLimit(speed, c.maxSpeed, c.mph, OVER_LIMIT_TOLERANCE)) continue;
            Long last = lastAlert.get(c.id);
            if (last != null && now - last < REALERT_MS) continue;
            if (toAnnounce == null || dist < announceDist) {
                toAnnounce = c;
                announceDist = dist;
            }
        }
        if (toAnnounce != null) {
            // Silence its neighbours too, so a cluster of cameras gives one alert, not several.
            for (CameraStore.Camera other : store.cameras()) {
                if (other == toAnnounce || Geo.distanceM(toAnnounce.lat, toAnnounce.lon, other.lat, other.lon) <= CLUSTER_M) {
                    lastAlert.put(other.id, now);
                }
            }
            announce(toAnnounce, announceDist, speed);
        }
        nextCamera = nearest;
        nextDistM = nearestDist;
    }

    private void announce(CameraStore.Camera c, double dist, float speedMs) {
        Log.i(TAG, "ALERT camera " + c.id + " in " + (int) dist + " m, limit " + c.maxSpeed
                + (c.mph ? " mph" : " km/h"));
        lastAlertAt = System.currentTimeMillis();
        lastAlertCamera = c;
        History.add(context, new History.Entry(lastAlertAt, c.id, c.kind, (int) dist,
                (int) Math.round(speedMs * 3.6), c.maxSpeed, c.mph));
        if (sound != null) sound.play(settings.soundId(), c.kind);
    }
}
