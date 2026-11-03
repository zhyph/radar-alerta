package br.radar.alerta;

/** Pure-Java geometry so it can be unit-tested on the JVM. */
final class Geo {
    private static final double EARTH_M = 6_371_000;

    private Geo() { }

    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_M * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** Initial bearing in degrees [0, 360). */
    static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    /** Smallest absolute angle between two bearings, in [0, 180]. */
    static double angleDiff(double a, double b) {
        return Math.abs(((a - b) + 540) % 360 - 180);
    }

    /** True if the camera lies within maxDiffDeg of the travel bearing. */
    static boolean isAhead(double lat, double lon, double travelBearing,
                           double camLat, double camLon, double maxDiffDeg) {
        return angleDiff(bearingDeg(lat, lon, camLat, camLon), travelBearing) <= maxDiffDeg;
    }

    /** Index of the tile of the given size (degrees) that contains this coordinate. */
    static int tileIndex(double degrees, double tileDeg) {
        return (int) Math.floor(degrees / tileDeg);
    }

    /** Point at the given distance and bearing from (lat, lon); flat-earth, fine for a few km. */
    static double[] project(double lat, double lon, double bearingDeg, double meters) {
        double dLat = Math.cos(Math.toRadians(bearingDeg)) * meters / 111_320.0;
        double dLon = Math.sin(Math.toRadians(bearingDeg)) * meters
                / (111_320.0 * Math.max(0.01, Math.cos(Math.toRadians(lat))));
        return new double[] {lat + dLat, lon + dLon};
    }

    /** Sideways distance (m) from the straight line through the user along the travel bearing. */
    static double lateralOffsetM(double lat, double lon, double travelBearing, double camLat, double camLon) {
        double dist = distanceM(lat, lon, camLat, camLon);
        double diff = Math.toRadians(angleDiff(bearingDeg(lat, lon, camLat, camLon), travelBearing));
        return Math.abs(Math.sin(diff)) * dist;
    }

    /**
     * How far to the side a camera may be and still count as being on our road. It grows with
     * distance because a road that curves ahead puts its own cameras off the straight line.
     */
    static double lateralToleranceM(double distM) {
        return 25 + 0.25 * distM;
    }

    /**
     * True if the speed is within toleranceUnits below the limit, or above it. The speed is
     * compared in the limit's own unit (km/h, or mph when the limit is in mph).
     */
    static boolean nearOrOverLimit(double speedMs, int limit, boolean mph, double toleranceUnits) {
        double speed = speedMs * (mph ? 2.236936 : 3.6);
        return speed > limit - toleranceUnits;
    }

    /** Warn about leadSeconds ahead, clamped to a sensible range (meters). */
    static double alertDistanceM(double speedMs, int leadSeconds) {
        return Math.max(250, Math.min(700, speedMs * leadSeconds));
    }
}
