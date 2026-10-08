package edu.pb.dcls;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static edu.pb.dcls.AppServices.AccessService;
import static edu.pb.dcls.AppServices.AppException;
import static edu.pb.dcls.AppServices.AuthService;
import static edu.pb.dcls.AppServices.FleetService;
import static edu.pb.dcls.AppServices.OrderService;
import static edu.pb.dcls.AppServices.RouteService;
import static edu.pb.dcls.Domain.OrderStatus;
import static edu.pb.dcls.Domain.Priority;
import static edu.pb.dcls.Domain.Repository;
import static edu.pb.dcls.Domain.Role;
import static edu.pb.dcls.Domain.RoutePlan;
import static edu.pb.dcls.Domain.Shipment;
import static edu.pb.dcls.Domain.User;
import static edu.pb.dcls.Domain.Vehicle;
import static edu.pb.dcls.Domain.VehicleStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavior lock for the service layer before the UI restructure. Each test uses a fresh
 * seeded {@link MockRepository}, so the assertions double as documentation of the seeded demo state.
 */
class AppServicesTest {

    private Repository repo;

    @BeforeEach
    void setUp() {
        repo = new MockRepository();
    }

    private User admin() {
        return repo.userByEmail("admin@dcls.local").orElseThrow();
    }

    private User driver01() {
        return repo.userByEmail("driver@dcls.local").orElseThrow();
    }

    @Nested
    @DisplayName("AuthService")
    class Auth {
        private AuthService auth;

        @BeforeEach
        void init() {
            auth = new AuthService(repo);
        }

        @Test
        void loginWithValidCredentials() {
            User user = auth.login("admin@dcls.local", "demo1234".toCharArray());
            assertEquals(Role.ADMIN, user.role());
        }

        @Test
        void loginIsCaseInsensitiveOnEmailAndTrims() {
            User user = auth.login("  ADMIN@dcls.local  ", "demo1234".toCharArray());
            assertEquals("admin@dcls.local", user.email());
        }

        @Test
        void loginWrongPasswordThrows() {
            assertThrows(AppException.class, () -> auth.login("admin@dcls.local", "nope".toCharArray()));
        }

        @Test
        void loginBlankInputThrows() {
            assertThrows(AppException.class, () -> auth.login("", "demo1234".toCharArray()));
        }

        @Test
        void loginInactiveAccountThrows() {
            User user = admin();
            user.setActive(false);
            repo.saveUser(user);
            assertThrows(AppException.class, () -> auth.login("admin@dcls.local", "demo1234".toCharArray()));
        }

        @Test
        void registerCreatesDriverAccount() {
            User user = auth.register("New Person", "new@dcls.local", "+673 000", "password1".toCharArray());
            assertEquals(Role.DRIVER, user.role());
            assertTrue(repo.userByEmail("new@dcls.local").isPresent());
        }

        @Test
        void registerDuplicateEmailThrows() {
            assertThrows(AppException.class,
                    () -> auth.register("Dup", "admin@dcls.local", "+673", "password1".toCharArray()));
        }

        @Test
        void registerShortPasswordThrows() {
            assertThrows(AppException.class,
                    () -> auth.register("Shorty", "shorty@dcls.local", "+673", "123".toCharArray()));
        }

        @Test
        void registerInvalidEmailThrows() {
            assertThrows(AppException.class,
                    () -> auth.register("Bad", "not-an-email", "+673", "password1".toCharArray()));
        }

        @Test
        void changePasswordRequiresCorrectCurrent() {
            assertThrows(AppException.class,
                    () -> auth.changePassword(admin(), "wrong".toCharArray(), "password1".toCharArray()));
        }

        @Test
        void changePasswordUpdatesCredential() {
            auth.changePassword(admin(), "demo1234".toCharArray(), "password1".toCharArray());
            assertNotNull(auth.login("admin@dcls.local", "password1".toCharArray()));
            assertThrows(AppException.class, () -> auth.login("admin@dcls.local", "demo1234".toCharArray()));
        }
    }

    @Nested
    @DisplayName("AccessService")
    class Access {
        private final AccessService access = new AccessService();

        @Test
        void adminOpensEverything() {
            assertTrue(access.canOpen(Role.ADMIN, "users"));
            assertTrue(access.canOpen(Role.ADMIN, "fleet"));
        }

        @Test
        void managerCannotOpenUsers() {
            assertFalse(access.canOpen(Role.MANAGER, "users"));
            assertTrue(access.canOpen(Role.MANAGER, "fleet"));
        }

        @Test
        void dispatcherHasNoFleet() {
            assertFalse(access.canOpen(Role.DISPATCHER, "fleet"));
            assertTrue(access.canOpen(Role.DISPATCHER, "orders"));
        }

        @Test
        void driverIsLimited() {
            assertFalse(access.canOpen(Role.DRIVER, "routes"));
            assertTrue(access.canOpen(Role.DRIVER, "orders"));
        }

        @Test
        void capabilityGates() {
            assertTrue(access.canManageUsers(Role.ADMIN));
            assertFalse(access.canManageUsers(Role.MANAGER));
            assertTrue(access.canManageFleet(Role.MANAGER));
            assertFalse(access.canManageFleet(Role.DISPATCHER));
            assertTrue(access.canDispatch(Role.DISPATCHER));
            assertFalse(access.canDispatch(Role.DRIVER));
            assertTrue(access.canUpdateDelivery(Role.DRIVER));
        }
    }

    @Nested
    @DisplayName("FleetService")
    class Fleet {
        private FleetService fleet;

        @BeforeEach
        void init() {
            fleet = new FleetService(repo);
        }

        @Test
        void saveRejectsBlankPlate() {
            Vehicle v = new Vehicle(AppServices.id(), "", "Model", "Light truck", 1000, 0, VehicleStatus.AVAILABLE, null, 5000);
            assertThrows(AppException.class, () -> fleet.save(v));
        }

        @Test
        void saveRejectsDuplicatePlate() {
            Vehicle v = new Vehicle(AppServices.id(), "BQ 1934", "Model", "Light truck", 1000, 0, VehicleStatus.AVAILABLE, null, 5000);
            assertThrows(AppException.class, () -> fleet.save(v));
        }

        @Test
        void saveNormalisesPlateToUpperCase() {
            Vehicle v = new Vehicle(AppServices.id(), "bq 9999", "Model", "Light truck", 1000, 0, VehicleStatus.AVAILABLE, null, 5000);
            fleet.save(v);
            assertEquals("BQ 9999", v.plate());
        }

        @Test
        void setStatusRejectsNull() {
            Vehicle v = repo.vehicle("v-3310").orElseThrow();
            assertThrows(AppException.class, () -> fleet.setStatus(v, null));
        }

        @Test
        void maintenanceDueFlagsVehiclesNearService() {
            // Seeded: only v-5201 (52870 km, next 53000) is within the 500 km window.
            List<Vehicle> due = fleet.maintenanceDue();
            assertEquals(1, due.size());
            assertEquals("v-5201", due.get(0).id());
        }

        @Test
        void logMaintenanceRejectsMileageBelowCurrent() {
            Vehicle v = repo.vehicle("v-3310").orElseThrow(); // 31840 km
            assertThrows(AppException.class,
                    () -> fleet.logMaintenance(v, LocalDate.now(), 100, "Oil", 50));
        }

        @Test
        void logMaintenanceRecordsAndReturnsVehicleToService() {
            Vehicle v = repo.vehicle("v-5201").orElseThrow(); // UNDER_MAINTENANCE, 52870 km
            int before = repo.maintenanceRecords().size();
            fleet.logMaintenance(v, LocalDate.now(), 53000, "Full service", 400);
            assertEquals(before + 1, repo.maintenanceRecords().size());
            assertEquals(VehicleStatus.AVAILABLE, v.status());
        }
    }

    @Nested
    @DisplayName("OrderService")
    class Orders {
        private OrderService orders;

        @BeforeEach
        void init() {
            orders = new OrderService(repo);
        }

        @Test
        void saveRejectsMissingFields() {
            Shipment s = new Shipment(AppServices.id(), "", "+673", "", "", 1, 0,
                    Priority.NORMAL, OrderStatus.PENDING, null, null, null, java.time.LocalDateTime.now(), null);
            assertThrows(AppException.class, () -> orders.save(s));
        }

        @Test
        void assignSetsOrderAndVehicleStatuses() {
            Shipment pending = repo.shipment("DCLS-2403").orElseThrow(); // 64 kg, PENDING
            Vehicle available = repo.vehicle("v-8817").orElseThrow();     // AVAILABLE, 1600 kg
            orders.assign(pending, driver01(), available, null);
            assertEquals(OrderStatus.ASSIGNED, pending.status());
            assertEquals(VehicleStatus.IN_DELIVERY, available.status());
        }

        @Test
        void assignRejectsOverweightShipment() {
            Shipment heavy = new Shipment(AppServices.id(), "Big", "+673", "Somewhere", "Steel", 1, 5000,
                    Priority.HIGH, OrderStatus.PENDING, null, null, null, java.time.LocalDateTime.now(), null);
            repo.saveShipment(heavy);
            Vehicle small = repo.vehicle("v-8817").orElseThrow(); // 1600 kg
            assertThrows(AppException.class, () -> orders.assign(heavy, driver01(), small, null));
        }

        @Test
        void assignRejectsUnavailableVehicle() {
            Shipment pending = repo.shipment("DCLS-2403").orElseThrow();
            Vehicle inMaintenance = repo.vehicle("v-5201").orElseThrow(); // UNDER_MAINTENANCE
            assertThrows(AppException.class, () -> orders.assign(pending, driver01(), inMaintenance, null));
        }

        @Test
        void transitionPendingWithoutAssignmentThrows() {
            Shipment pending = repo.shipment("DCLS-2403").orElseThrow();
            assertThrows(AppException.class, () -> orders.transition(pending, OrderStatus.IN_TRANSIT, admin()));
        }

        @Test
        void transitionAssignedToInTransit() {
            Shipment assigned = repo.shipment("DCLS-2402").orElseThrow(); // ASSIGNED
            orders.transition(assigned, OrderStatus.IN_TRANSIT, admin());
            assertEquals(OrderStatus.IN_TRANSIT, assigned.status());
        }

        @Test
        void deliveringFreesTheVehicle() {
            Shipment transit = repo.shipment("DCLS-2401").orElseThrow(); // IN_TRANSIT, vehicle v-1934
            orders.transition(transit, OrderStatus.DELIVERED, admin());
            assertEquals(OrderStatus.DELIVERED, transit.status());
            assertEquals(VehicleStatus.AVAILABLE, repo.vehicle("v-1934").orElseThrow().status());
        }

        @Test
        void driverCannotTouchOthersDeliveries() {
            Shipment others = repo.shipment("DCLS-2402").orElseThrow(); // driver u-driver-02
            assertThrows(AppException.class, () -> orders.transition(others, OrderStatus.IN_TRANSIT, driver01()));
        }

        @Test
        void deliveredOrderCannotBeCancelled() {
            Shipment delivered = repo.shipment("DCLS-2398").orElseThrow(); // DELIVERED
            assertThrows(AppException.class, () -> orders.transition(delivered, OrderStatus.CANCELLED, admin()));
        }
    }

    @Nested
    @DisplayName("RouteService")
    class Routes {
        private RouteService routes;

        @BeforeEach
        void init() {
            routes = new RouteService(repo);
        }

        @Test
        void optimizeRequiresAtLeastTwoStops() {
            assertThrows(AppException.class, () -> routes.optimize("Solo", List.of("DCLS Depot"), null));
        }

        @Test
        void optimizeRejectsUnknownPlace() {
            assertThrows(AppException.class, () -> routes.optimize("Bad", List.of("DCLS Depot", "Atlantis"), null));
        }

        @Test
        void optimizeBuildsAndPersistsRoute() {
            int before = repo.routes().size();
            RoutePlan plan = routes.optimize("Test run", List.of("DCLS Depot", "Berakas", "Kiulap"), null);
            assertEquals(3, plan.stops().size());
            assertTrue(plan.distanceKm() > 0);
            assertTrue(plan.estimatedMinutes() >= 1);
            assertEquals("DCLS Depot", plan.stops().get(0).name()); // starts from the first supplied stop
            assertEquals(before + 1, repo.routes().size());
        }
    }
}
