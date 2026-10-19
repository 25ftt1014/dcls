package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static edu.pb.dcls.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

/** Dashboard derivations that must hold without a JavaFX toolkit: today's completed
 *  count, the on-road figure, the status breakdown, and the queue ordering. */
class DashboardMetricsTest {

    private static Shipment shipment(String id, OrderStatus status, Integer deliveredHoursAgo) {
        return new Shipment(id, "Acme", "+673 000 0000", "Bandar Seri Begawan", "Boxes", 1, 10,
                Priority.NORMAL, status, "u-driver-01", "v-1934", null,
                LocalDateTime.now().minusDays(2),
                deliveredHoursAgo == null ? null : LocalDateTime.now().minusHours(deliveredHoursAgo));
    }

    /**
     * A delivery at a given hour today. The "completed today" metric counts from
     * midnight, so a test that says "20 hours ago" is really saying "yesterday" only
     * when it runs before 4am — this test failed every evening. Anchoring to midnight
     * makes the intent ("one hour into today", "one hour into yesterday") hold at any
     * hour of any day.
     */
    private static LocalDateTime hoursInto(int daysAgo, int hour) {
        return LocalDate.now().minusDays(daysAgo).atStartOfDay().plusHours(hour);
    }

    private static Shipment deliveredAt(String id, LocalDateTime when) {
        return new Shipment(id, "Acme", "+673 000 0000", "Bandar Seri Begawan", "Boxes", 1, 10,
                Priority.NORMAL, OrderStatus.DELIVERED, "u-driver-01", "v-1934", null,
                LocalDateTime.now().minusDays(2), when);
    }

    private static Vehicle vehicle(String id, VehicleStatus status) {
        return new Vehicle(id, "V" + id, "Model", "Van", 1000, 5000, status, "u-driver-01", 10000);
    }

    @Test
    void completedTodayCountsOnlyToday() {
        List<Shipment> rows = List.of(
                deliveredAt("D-1", hoursInto(0, 1)),   // an hour into today
                deliveredAt("D-2", hoursInto(1, 1)),   // an hour into yesterday
                deliveredAt("D-3", hoursInto(2, 1)),   // two days ago
                shipment("D-4", OrderStatus.IN_TRANSIT, null)); // not delivered at all
        assertEquals(1, DashboardView.completedToday(rows));
    }

    @Test
    void completedTodayIgnoresDeliveredWithoutTimestamp() {
        // Status says delivered but the stamp is missing: it cannot be attributed to today.
        List<Shipment> rows = List.of(shipment("D-1", OrderStatus.DELIVERED, null));
        assertEquals(0, DashboardView.completedToday(rows));
    }

    @Test
    void completedTodayDoesNotCountEarlierTodayFromYesterday() {
        // Guards the boundary: 23:00 yesterday must not leak into today's total.
        LocalDateTime lastNight = LocalDate.now().minusDays(1).atTime(23, 0);
        Shipment edge = new Shipment("D-1", "Acme", "+673", "BSB", "Boxes", 1, 10,
                Priority.NORMAL, OrderStatus.DELIVERED, "u-1", "v-1", null,
                LocalDateTime.now().minusDays(3), lastNight);
        assertEquals(0, DashboardView.completedToday(List.of(edge)));
    }

    @Test
    void onRoadCountsOnlyVehiclesOnADelivery() {
        List<Vehicle> fleet = List.of(
                vehicle("v-1", VehicleStatus.IN_DELIVERY),
                vehicle("v-2", VehicleStatus.IN_DELIVERY),
                vehicle("v-3", VehicleStatus.AVAILABLE),
                vehicle("v-4", VehicleStatus.UNDER_MAINTENANCE),
                vehicle("v-5", VehicleStatus.UNAVAILABLE));
        assertEquals(2, DashboardView.onRoad(fleet));
    }

    @Test
    void statusCountsBucketsChartedStatusesAndDropsCancelled() {
        List<Shipment> rows = List.of(
                shipment("D-1", OrderStatus.PENDING, null),
                shipment("D-2", OrderStatus.PENDING, null),
                shipment("D-3", OrderStatus.ASSIGNED, null),
                shipment("D-4", OrderStatus.IN_TRANSIT, null),
                shipment("D-5", OrderStatus.DELIVERED, 2),
                shipment("D-6", OrderStatus.CANCELLED, null));
        Map<OrderStatus, Integer> counts = DashboardView.statusCounts(rows);
        assertEquals(2, counts.get(OrderStatus.PENDING));
        assertEquals(1, counts.get(OrderStatus.ASSIGNED));
        assertEquals(1, counts.get(OrderStatus.IN_TRANSIT));
        assertEquals(1, counts.get(OrderStatus.DELIVERED));
        assertEquals(5, counts.values().stream().mapToInt(Integer::intValue).sum(),
                "all six shipments minus the CANCELLED one");
        assertFalse(counts.containsKey(OrderStatus.CANCELLED));
    }

    @Test
    void statusCountsAlwaysReportsEveryChartedStatus() {
        // An empty or all-cancelled queue must still yield a bar row per status.
        Map<OrderStatus, Integer> counts = DashboardView.statusCounts(List.of());
        assertEquals(4, counts.size());
        assertTrue(counts.values().stream().allMatch(zero -> zero == 0));
    }

    @Test
    void queueLeadsWithSoonestEtaAndSinksTheOnesWithNoFix() {
        Map<String, Integer> etas = Map.of("D-1", 30, "D-2", 5, "D-3", Integer.MAX_VALUE);
        List<Shipment> queue = List.of(
                        shipment("D-1", OrderStatus.IN_TRANSIT, null),
                        shipment("D-2", OrderStatus.IN_TRANSIT, null),
                        shipment("D-3", OrderStatus.ASSIGNED, null))
                .stream().sorted(DashboardView.byEtaThenAge(etas::get)).toList();
        assertEquals(List.of("D-2", "D-1", "D-3"), queue.stream().map(Shipment::id).toList(),
                "soonest ETA first, no-fix shipment last");
    }

    @Test
    void isActiveCoversAssignedAndInTransitOnly() {
        assertTrue(DashboardView.isActive(shipment("D-1", OrderStatus.ASSIGNED, null)));
        assertTrue(DashboardView.isActive(shipment("D-2", OrderStatus.IN_TRANSIT, null)));
        assertFalse(DashboardView.isActive(shipment("D-3", OrderStatus.PENDING, null)));
        assertFalse(DashboardView.isActive(shipment("D-4", OrderStatus.DELIVERED, 1)));
        assertFalse(DashboardView.isActive(shipment("D-5", OrderStatus.CANCELLED, null)));
    }

    @Test
    void trackingIssueCountIncludesMissingAndStaleActiveFixesOnly() {
        LocalDateTime now = LocalDateTime.now();
        TrackingPoint fresh = new TrackingPoint("T-1", "v-fresh", "D-1", 114.9, 4.9,
                8, now.minusSeconds(15));
        TrackingPoint stale = new TrackingPoint("T-2", "v-stale", "D-2", 114.9, 4.9,
                18, now.minusSeconds(16));
        AppServices.TrackingService.TrackingSnapshot snapshot =
                new AppServices.TrackingService.TrackingSnapshot(
                        Map.of("v-fresh", fresh, "v-stale", stale), Map.of());
        Shipment noVehicle = new Shipment("D-3", "Acme", "+673", "BSB", "Boxes", 1, 10,
                Priority.NORMAL, OrderStatus.IN_TRANSIT, "u-driver-01", null, null,
                LocalDateTime.now().minusDays(1), null);
        Shipment freshDelivery = new Shipment("D-1", "Acme", "+673", "BSB", "Boxes", 1, 10,
                Priority.NORMAL, OrderStatus.IN_TRANSIT, "u-driver-01", "v-fresh", null,
                LocalDateTime.now().minusDays(1), null);

        assertEquals(2, DashboardView.trackingIssueCount(List.of(
                freshDelivery,
                new Shipment("D-2", "Acme", "+673", "BSB", "Boxes", 1, 10,
                        Priority.NORMAL, OrderStatus.IN_TRANSIT, "u-driver-01", "v-stale", null,
                        LocalDateTime.now().minusDays(1), null),
                noVehicle,
                new Shipment("D-5", "Acme", "+673", "BSB", "Boxes", 1, 10,
                        Priority.NORMAL, OrderStatus.ASSIGNED, "u-driver-01", "v-no-fix", null,
                        LocalDateTime.now().minusDays(1), null),
                shipment("D-4", OrderStatus.PENDING, null)), snapshot, now));
    }

    @Test
    void dashboardDestinationsFollowExistingRoleAccess() {
        AppServices.AccessService access = new AppServices.AccessService();
        for (Role role : List.of(Role.ADMIN, Role.MANAGER, Role.DISPATCHER, Role.DRIVER)) {
            assertTrue(access.canOpen(role, "dashboard"));
            assertTrue(access.canOpen(role, "orders"));
            assertTrue(access.canOpen(role, "tracking"));
            assertEquals(role == Role.ADMIN || role == Role.MANAGER, access.canOpen(role, "fleet"));
        }
    }

    @Test
    void nullCollectionsAreTolerated() {
        assertEquals(0, DashboardView.completedToday(null));
        assertEquals(0, DashboardView.onRoad(null));
        assertEquals(0, DashboardView.statusCounts(null).get(OrderStatus.PENDING));
    }
}
