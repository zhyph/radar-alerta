package br.radar.alerta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GeoTest {
    @Test
    public void distanceOneDegreeLatitudeIsAbout111km() {
        assertEquals(111_195, Geo.distanceM(0, 0, 1, 0), 200);
    }

    @Test
    public void bearingToCardinalDirections() {
        assertEquals(0, Geo.bearingDeg(0, 0, 1, 0), 0.5);   // north
        assertEquals(90, Geo.bearingDeg(0, 0, 0, 1), 0.5);  // east
        assertEquals(180, Geo.bearingDeg(0, 0, -1, 0), 0.5); // south
        assertEquals(270, Geo.bearingDeg(0, 0, 0, -1), 0.5); // west
    }

    @Test
    public void angleDiffWrapsAroundNorth() {
        assertEquals(20, Geo.angleDiff(350, 10), 1e-9);
        assertEquals(180, Geo.angleDiff(0, 180), 1e-9);
    }

    @Test
    public void cameraAheadVersusBehind() {
        // Driving east; one camera 200 m east, one 200 m west of the car.
        double lat = -23.5, lon = -46.6, dLon = 0.002; // ~205 m at this latitude
        assertTrue(Geo.isAhead(lat, lon, 90, lat, lon + dLon, 50));
        assertFalse(Geo.isAhead(lat, lon, 90, lat, lon - dLon, 50));
    }

    @Test
    public void alertDistanceIsClamped() {
        assertEquals(250, Geo.alertDistanceM(5, 10), 1e-9);    // slow: floor
        assertEquals(700, Geo.alertDistanceM(80, 10), 1e-9);   // fast: ceiling
        assertEquals(333, Geo.alertDistanceM(33.3, 10), 0.5);  // in between
        assertEquals(500, Geo.alertDistanceM(33.3, 15), 1);    // early setting warns sooner
        assertEquals(250, Geo.alertDistanceM(33.3, 6), 1e-9);  // late setting hits the floor
    }

    @Test
    public void lateralOffsetSeparatesSameRoadFromParallelStreet() {
        double lat = -23.5, lon = -46.6;
        // Driving east: a camera 250 m straight ahead is on our road, one 250 m ahead and
        // ~120 m to the side is on a parallel street.
        double onRoad = Geo.lateralOffsetM(lat, lon, 90, lat, lon + 250 / 102_000.0);
        double parallel = Geo.lateralOffsetM(lat, lon, 90, lat - 120 / 111_000.0, lon + 250 / 102_000.0);
        assertTrue("on road " + onRoad, onRoad < Geo.lateralToleranceM(250));
        assertTrue("parallel " + parallel, parallel > Geo.lateralToleranceM(250));
    }

    @Test
    public void lateralToleranceGrowsWithDistance() {
        assertEquals(25, Geo.lateralToleranceM(0), 1e-9);
        assertTrue(Geo.lateralToleranceM(500) > Geo.lateralToleranceM(100));
    }

    @Test
    public void overLimitRespectsUnitAndTolerance() {
        // 50 km/h limit: 13 m/s = 46.8 km/h is within the 5 km/h tolerance, 10 m/s = 36 km/h is not.
        assertTrue(Geo.nearOrOverLimit(13, 50, false, 5));
        assertFalse(Geo.nearOrOverLimit(10, 50, false, 5));
        // Same speed against a 30 mph limit: 13 m/s = 29.1 mph is near it.
        assertTrue(Geo.nearOrOverLimit(13, 30, true, 5));
        assertFalse(Geo.nearOrOverLimit(8, 30, true, 5)); // 17.9 mph
    }

    @Test
    public void tileIndexFloorsTowardNegativeInfinity() {
        assertEquals(-236, Geo.tileIndex(-23.56, 0.1));
        assertEquals(-467, Geo.tileIndex(-46.68, 0.1));
        assertEquals(525, Geo.tileIndex(52.52, 0.1));
        assertEquals(-1, Geo.tileIndex(-0.01, 0.1));
    }

    @Test
    public void projectMovesInTheTravelDirection() {
        double[] north = Geo.project(-23.5, -46.6, 0, 1000);
        assertEquals(-23.5 + 1000 / 111_320.0, north[0], 1e-6);
        assertEquals(-46.6, north[1], 1e-6);
        double[] east = Geo.project(-23.5, -46.6, 90, 1000);
        assertEquals(1000, Geo.distanceM(-23.5, -46.6, east[0], east[1]), 15);
        assertTrue(east[1] > -46.6);
    }

    @Test
    public void facingParsesOnlyPlainCardinalValues() {
        assertEquals(45, Geo.parseFacing("NE"));
        assertEquals(90, Geo.parseFacing(" 90 "));
        assertEquals(-1, Geo.parseFacing("both"));
        assertEquals(-1, Geo.parseFacing("forward"));
        assertEquals(-1, Geo.parseFacing("90-180"));
        assertEquals(-1, Geo.parseFacing(null));
    }

    @Test
    public void cameraWatchesTrafficComingTowardIt() {
        assertTrue(Geo.watchesTravel(270, 90));    // faces east, sees westbound traffic
        assertFalse(Geo.watchesTravel(90, 90));    // eastbound traffic drives away from it
        assertTrue(Geo.watchesTravel(90, -1));     // unknown direction never excludes
    }
}
