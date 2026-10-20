package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static edu.pb.dcls.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

/** Sea-outlier bug: one noisy fix must never steer tours, trails, or the viewport. */
class SeaOutlierTest {

    private static final double SEA_LON = 114.4400, SEA_LAT = 4.9000; // open sea, inside map window
    private static final double ROAD_LON = 114.9280, ROAD_LAT = 4.9080; // Gadong, on the route

    private MockRepository repo() { return new MockRepository(); }

    private TrackingPoint seaFix(AppServices.TrackingService tracking) {
        return tracking.ingest("v-1934", "DCLS-2401", SEA_LON, SEA_LAT,
                30, null, null, FixType.GPS, "nmea", LocalDateTime.now());
    }

    @Test
    void seaFixIsQuarantinedButStored() {
        MockRepository repo = repo();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        TrackingPoint p = seaFix(tracking);
        assertNotNull(p);
        assertTrue(tracking.isQuarantined(p), "sea fix must be quarantined");
        assertTrue(repo.trackingHistory().stream().anyMatch(q -> q.id().equals(p.id())),
                "quarantined fixes stay stored for audit");
        assertEquals(1, tracking.quarantinedCount("v-1934"));
    }

    @Test
    void tourStartsAtDepotNotSea() {
        MockRepository repo = repo();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        // Poison the history first, as on MySQL: newest point is the sea outlier.
        seaFix(tracking);
        var tour = tracking.tourForVehicle("v-1934");
        assertFalse(tour.ordered().isEmpty());
        TrackingPoint start = tracking.latestPlausibleForVehicle("v-1934");
        assertNotNull(start);
        assertNotEquals(SEA_LON, start.x(), 1e-9);
        // No tour stop may be the sea point.
        assertTrue(tour.ordered().stream()
                .noneMatch(s -> Math.abs(s.x() - SEA_LON) < 1e-9 && Math.abs(s.y() - SEA_LAT) < 1e-9));
    }

    @Test
    void goodFixAcceptedAndSteersTour() {
        MockRepository repo = repo();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        TrackingPoint p = tracking.ingest("v-1934", "DCLS-2401", ROAD_LON, ROAD_LAT,
                30, null, null, FixType.GPS, "nmea", LocalDateTime.now());
        assertFalse(tracking.isQuarantined(p));
        assertEquals(0, tracking.quarantinedCount("v-1934"));
        assertEquals(p.id(), tracking.latestPlausibleForVehicle("v-1934").id());
    }

    @Test
    void clearHistoryRemovesVehicleFixes() {
        MockRepository repo = repo();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        seaFix(tracking);
        assertFalse(repo.trackingHistory().isEmpty());
        repo.deleteTrackingForVehicle("v-1934");
        tracking.forgetVehicle("v-1934");
        assertTrue(repo.trackingHistory().stream().noneMatch(p -> "v-1934".equals(p.vehicleId())));
        assertNull(tracking.latestPlausibleForVehicle("v-1934"));
        assertEquals(0, tracking.quarantinedCount("v-1934"));
    }

    @Test
    void fitBoundsIgnoresFarOutlier() {
        List<double[]> cluster = List.of(new double[] {114.94, 4.89}, new double[] {114.95, 4.94},
                new double[] {114.928, 4.908});
        var without = MapRenderer.fitBounds(cluster, 1.3);
        var pts = new java.util.ArrayList<>(cluster);
        pts.add(new double[] {SEA_LON, SEA_LAT});
        var with = MapRenderer.fitBounds(pts, 1.3);
        assertEquals(without.lonMin(), with.lonMin(), 1e-9);
        assertEquals(without.lonMax(), with.lonMax(), 1e-9);
        assertEquals(without.latMin(), with.latMin(), 1e-9);
        assertEquals(without.latMax(), with.latMax(), 1e-9);
    }

    @Test
    void dropFarOutliersDropsOnlyTheOutlier() {
        var kept = MapRenderer.dropFarOutliers(
                List.of(new double[] {114.94, 4.89}, new double[] {114.95, 4.94},
                        new double[] {114.928, 4.908}, new double[] {SEA_LON, SEA_LAT}), 10.0);
        assertEquals(3, kept.size());
        assertTrue(kept.stream().noneMatch(p -> Math.abs(p[0] - SEA_LON) < 1e-9));
    }

    @Test
    void saveRouteRejectsOffAreaStops() {
        MockRepository repo = repo();
        Domain.RoutePlan bad = new Domain.RoutePlan("r-bad", "Bad", null,
                List.of(AppServices.BruneiPlaces.DEPOT,
                        new Domain.Stop("x", "Nowhere", 0.42, 0.51)),
                0, 0, false, LocalDateTime.now());
        assertThrows(AppServices.AppException.class, () -> repo.saveRoute(bad));
    }

    @Test
    void saveTrackingPointRejectsOffArea() {
        MockRepository repo = repo();
        assertThrows(AppServices.AppException.class, () -> repo.saveTrackingPoint(
                new TrackingPoint("t-bad", "v-1934", "DCLS-2401", 0.42, 0.51, 0, LocalDateTime.now())));
    }

    @Test
    void garbageLegsDoNotLegitimizeFixes() {
        // The poisoned legacy leg (sea -> depot, ~12,700 km) is insane; a real leg is not.
        assertFalse(AppServices.TrackingService.saneLeg(
                new Domain.Stop("a", "Sea", 0.42, 0.51),
                new Domain.Stop("b", "Depot", 114.94, 4.89)));
        assertTrue(AppServices.TrackingService.saneLeg(
                AppServices.BruneiPlaces.DEPOT,
                new Domain.Stop("k", "Kiulap", 114.916, 4.902)));
    }

    @Test
    void fitBoundsStillFramesGenuineSpread() {
        // Two distant vehicles: median filter may drop both, but fitBounds must
        // fall back to framing everything rather than returning an empty view.
        var view = MapRenderer.fitBounds(
                List.of(new double[] {114.66, 4.81}, new double[] {114.99, 4.96}), 1.3);
        assertNotNull(MapRenderer.projectIfVisible(114.66, 4.81, 440, 340, view));
        assertNotNull(MapRenderer.projectIfVisible(114.99, 4.96, 440, 340, view));
        assertTrue(MapRenderer.dropFarOutliers(List.of(), 10.0).isEmpty());
        assertTrue(MapRenderer.dropFarOutliers(null, 10.0).isEmpty());
    }
}
