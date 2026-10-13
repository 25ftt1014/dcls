package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static edu.pb.dcls.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

/** Regression tests for the code-audit fixes (all headless-safe). */
class AuditFixTest {

    private MockRepository repo() { return new MockRepository(); }

    @Test
    void latestForVehicleNullIsNullNotNpe() {
        assertNull(new AppServices.TrackingService(repo()).latestForVehicle(null));
    }

    @Test
    void pendingWithAssignmentsCanAdvanceToAssigned() {
        MockRepository repo = repo();
        User admin = repo.userByEmail("admin@dcls.local").orElseThrow();
        User driver = repo.userByEmail("driver@dcls.local").orElseThrow();
        Vehicle vehicle = repo.vehicle("v-8817").orElseThrow();
        Shipment s = new Shipment("DCLS-AUD1", "C", "+673", "Gadong", "Box", 1, 5,
                Priority.NORMAL, OrderStatus.PENDING, driver.id(), vehicle.id(), null,
                LocalDateTime.now(), null);
        repo.saveShipment(s);
        AppServices.OrderService orders = new AppServices.OrderService(repo);
        assertDoesNotThrow(() -> orders.transition(s, OrderStatus.ASSIGNED, admin));
        // transition() persists an immutable copy — callers must re-read.
        assertEquals(OrderStatus.ASSIGNED, repo.shipment("DCLS-AUD1").orElseThrow().status());
    }

    @Test
    void pendingWithoutAssignmentsStillBlocked() {
        MockRepository repo = repo();
        User admin = repo.userByEmail("admin@dcls.local").orElseThrow();
        Shipment s = repo.shipment("DCLS-2403").orElseThrow(); // PENDING, unassigned
        AppServices.OrderService orders = new AppServices.OrderService(repo);
        assertThrows(AppServices.AppException.class,
                () -> orders.transition(s, OrderStatus.ASSIGNED, admin));
    }

    @Test
    void routelessFixGetsHonestEta() {
        MockRepository repo = repo();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        Shipment s = new Shipment("DCLS-AUD2", "C", "+673", "Kiulap, Bandar Seri Begawan",
                "Box", 1, 5, Priority.NORMAL, OrderStatus.IN_TRANSIT,
                "u-driver-01", "v-8817", null, LocalDateTime.now(), null);
        repo.saveShipment(s);
        // Fix far from the destination with no route: ETA must be positive, never fake 0.
        TrackingPoint p = tracking.ingest("v-8817", s.id(), 114.6600, 4.8100,
                30, null, null, FixType.GPS, "nmea", LocalDateTime.now());
        assertNotNull(p);
        assertTrue(p.etaMinutes() > 0, "routeless ETA must be honest, got 0");
    }

    @Test
    void gpxParsesLonFirstAndSelfClosing() {
        String xml = "<?xml version=\"1.0\"?><gpx version=\"1.1\"><trk><name>T</name><trkseg>"
                + "<trkpt lon=\"114.9180\" lat=\"4.9010\"><time>2026-10-13T02:00:00Z</time></trkpt>"
                + "<trkpt lat=\"4.9020\" lon=\"114.9200\"/>"
                + "</trkseg></trk></gpx>";
        List<GpsFeed.GpsFix> fixes = GpsFeed.parseGpx(xml, "v-1934", "gpx");
        assertEquals(2, fixes.size());
        assertEquals(114.9180, fixes.get(0).lon(), 1e-6);
        assertEquals(114.9200, fixes.get(1).lon(), 1e-6);
    }

    @Test
    void normalizeKeepsWordBoundaries() {
        // "bd" inside Abdullah and "kg" inside other words must survive intact.
        String norm = AddressGeocoder.normalize("Abdullah Shop, Bakong Road");
        assertTrue(norm.contains("abdullah"), "alias must not corrupt names: " + norm);
        assertTrue(norm.contains("bakong"), "alias must not corrupt names: " + norm);
        assertTrue(AddressNormalizer.baseClean("Jln Gadong").contains("jalan Gadong")
                || AddressNormalizer.baseClean("Jln Gadong").contains("jalan gadong"));
    }

    @Test
    void mockDeleteVehicleLinkedThrowsLikeJdbc() {
        MockRepository repo = repo(); // v-1934 linked to DCLS-2401 in seed
        assertThrows(AppServices.AppException.class, () -> repo.deleteVehicle("v-1934"));
    }

    @Test
    void mockDeleteRouteLinkedThrowsLikeJdbc() {
        MockRepository repo = repo(); // r-1001 linked to DCLS-2401 in seed
        assertThrows(AppServices.AppException.class, () -> repo.deleteRoute("r-1001"));
    }

    @Test
    void mockDeleteShipmentCascadesTracking() {
        MockRepository repo = repo(); // t-01 references DCLS-2401 in seed
        assertFalse(repo.trackingHistory().isEmpty());
        repo.deleteShipment("DCLS-2401");
        assertTrue(repo.trackingHistory().stream()
                .noneMatch(p -> "DCLS-2401".equals(p.shipmentId())));
    }

    @Test
    void tourInvalidatedByPriorityChange() {
        MockRepository repo = repo();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        var first = tracking.tourForVehicle("v-1934");
        Shipment extra = new Shipment("DCLS-AUD3", "C", "+673", "Gadong", "Box", 1, 5,
                Priority.LOW, OrderStatus.IN_TRANSIT, "u-driver-01", "v-1934", null,
                LocalDateTime.now(), null);
        repo.saveShipment(extra);
        var withExtra = tracking.tourForVehicle("v-1934");
        assertNotSame(first, withExtra);
        // Same state again -> cached.
        assertSame(withExtra, tracking.tourForVehicle("v-1934"));
    }
}
