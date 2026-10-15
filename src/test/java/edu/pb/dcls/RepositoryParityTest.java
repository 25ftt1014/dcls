package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static edu.pb.dcls.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

/** Demo/MySQL parity guards that fail without a database: shared stop ids,
 *  empty-route validation, and tracking-point validation must behave alike. */
class RepositoryParityTest {
    private static Stop stop(String id, String name) {
        // Inside the Brunei service area so requireServiceArea passes.
        return new Stop(id, name, 114.9280, 4.9080);
    }

    @Test
    void sharedStopIdsAcrossRoutes() {
        MockRepository repository = new MockRepository();
        RoutePlan first = new RoutePlan("r-a", "Route A", null,
                List.of(stop("gadong", "Gadong"), stop("kiulap", "Kiulap")), 3.0, 6, false, LocalDateTime.now());
        RoutePlan second = new RoutePlan("r-b", "Route B", null,
                List.of(stop("gadong", "Gadong"), stop("berakas", "Berakas")), 4.0, 8, false, LocalDateTime.now());
        assertDoesNotThrow(() -> repository.saveRoute(first));
        // Same district stop id on a second route: allowed in demo, and since the
        // schema fix also in MySQL via PRIMARY KEY (route_id, stop_order).
        assertDoesNotThrow(() -> repository.saveRoute(second));
        assertEquals(2, repository.routes().stream()
                .filter(r -> r.id().equals("r-a") || r.id().equals("r-b")).count());
    }

    @Test
    void emptyRouteRejected() {
        MockRepository repository = new MockRepository();
        RoutePlan empty = new RoutePlan("r-empty", "Empty", null, List.of(), 0, 0, false, LocalDateTime.now());
        assertThrows(AppServices.AppException.class, () -> repository.saveRoute(empty));
        RoutePlan single = new RoutePlan("r-one", "One", null,
                List.of(stop("gadong", "Gadong")), 0, 0, false, LocalDateTime.now());
        assertThrows(AppServices.AppException.class, () -> repository.saveRoute(single));
    }

    @Test
    void trackingPointRequiresVehicle() {
        MockRepository repository = new MockRepository();
        TrackingPoint noVehicle = new TrackingPoint("t-x", null, null, 114.9280, 4.9080, 5, LocalDateTime.now());
        assertThrows(AppServices.AppException.class, () -> repository.saveTrackingPoint(noVehicle));
    }
}
