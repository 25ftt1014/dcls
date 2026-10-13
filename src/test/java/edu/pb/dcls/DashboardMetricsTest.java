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

    private static Vehicle vehicle(String id, VehicleStatus status) {
        return new Vehicle(id, "V" + id, "Model", "Van", 1000, 5000, status, "u-driver-01", 10000);
    }

    @Test
    void completedTodayCountsOnlyToday() {
        List<Shipment> rows = List.of(
                shipment("D-1", OrderStatus.DELIVERED, 1),      // today
                shipment("D-2", OrderStatus.DELIVERED, 20),     // yesterday
                shipment("D-3", OrderStatus.DELIVERED, 48),     // two days ago
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
    void nullCollectionsAreTolerated() {
        assertEquals(0, DashboardView.completedToday(null));
        assertEquals(0, DashboardView.onRoad(null));
        assertEquals(0, DashboardView.statusCounts(null).get(OrderStatus.PENDING));
    }
}