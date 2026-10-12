package edu.pb.dcls;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static edu.pb.dcls.Domain.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses only an explicitly configured disposable database; never falls back to DCLS_DB_URL. */
class JdbcRepositoryIntegrationTest {
    @Test
    void crudPersistsAcrossRepositoryRestartAndRelatedWritesAreAtomic() {
        String url = System.getenv("DCLS_TEST_DB_URL");
        String username = System.getenv("DCLS_TEST_DB_USER");
        String password = System.getenv("DCLS_TEST_DB_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.isBlank() && username != null && !username.isBlank()
                        && password != null && !password.isBlank(),
                "Set DCLS_TEST_DB_URL, DCLS_TEST_DB_USER, and DCLS_TEST_DB_PASSWORD for the disposable MySQL test database.");

        String suffix = UUID.randomUUID().toString();
        String userId = suffix, vehicleId = UUID.randomUUID().toString(), routeId = UUID.randomUUID().toString();
        String shipmentId = UUID.randomUUID().toString(), maintenanceId = UUID.randomUUID().toString(), fuelId = UUID.randomUUID().toString();
        String stopA = UUID.randomUUID().toString(), stopB = UUID.randomUUID().toString();
        try (JdbcRepository repository = new JdbcRepository(url, username, password)) {
            User user = new User(userId, "Integration User", "integration-" + suffix + "@dcls.test", "+673 800 0000",
                    "not-used", "not-used", Role.DRIVER, true, false);
            Vehicle vehicle = new Vehicle(vehicleId, "IT-" + suffix.substring(0, 8), "Test vehicle", "Light truck", 1200,
                    100, VehicleStatus.AVAILABLE, userId, 5000);
            RoutePlan route = new RoutePlan(routeId, "Integration route", userId,
                    List.of(new Stop(stopA, "Depot", 114.94, 4.89), new Stop(stopB, "Kiulap", 114.916, 4.902)),
                    2.5, 5, false, LocalDateTime.now());
            Shipment shipment = new Shipment(shipmentId, "Integration customer", "+673 800 0001", "Kiulap", "Test package", 1,
                    20, Priority.NORMAL, OrderStatus.PENDING, userId, vehicleId, routeId, LocalDateTime.now(), null);
            repository.saveUser(user);
            repository.saveVehicle(vehicle);
            repository.saveRoute(route);
            repository.saveShipment(shipment);

            MaintenanceRecord maintenance = new MaintenanceRecord(maintenanceId, vehicleId, LocalDate.now(), 125, "Service", 40);
            Vehicle serviced = new Vehicle(vehicle.id(), vehicle.plate(), vehicle.model(), vehicle.type(), vehicle.capacityKg(),
                    125, VehicleStatus.AVAILABLE, vehicle.driverId(), 5125);
            repository.saveMaintenanceAndVehicle(maintenance, serviced);
            FuelRecord fuel = new FuelRecord(fuelId, vehicleId, LocalDate.now(), 130, 42, 35);
            Vehicle refuelled = new Vehicle(vehicle.id(), vehicle.plate(), vehicle.model(), vehicle.type(), vehicle.capacityKg(),
                    130, VehicleStatus.AVAILABLE, vehicle.driverId(), 5125);
            repository.saveFuelAndVehicle(fuel, refuelled);

            assertThrows(AppServices.AppException.class, () -> repository.deleteRoute(routeId),
                    "a route linked to a shipment must be protected by the foreign key");
            assertThrows(AppServices.AppException.class, () -> repository.deleteVehicle(vehicleId),
                    "a vehicle linked to a shipment or records must be protected by the foreign key");

            assertThrows(AppServices.AppException.class, () -> repository.saveMaintenanceAndVehicle(
                    new MaintenanceRecord(UUID.randomUUID().toString(), "missing-vehicle", LocalDate.now(), 999, "Must roll back", 1),
                    new Vehicle(vehicleId, vehicle.plate(), vehicle.model(), vehicle.type(), vehicle.capacityKg(), 999,
                            VehicleStatus.AVAILABLE, userId, 6000)));

            RoutePlan editedRoute = new RoutePlan(routeId, "Updated integration route", userId, route.stops(), 3.1, 7, false, route.createdAt());
            repository.saveRoute(editedRoute);
            repository.close();

            try (JdbcRepository reopened = new JdbcRepository(url, username, password)) {
                assertTrue(reopened.userByEmail(user.email()).isPresent());
                assertEquals("Updated integration route", reopened.routes().stream().filter(item -> item.id().equals(routeId)).findFirst().orElseThrow().name());
                assertEquals(OrderStatus.PENDING, reopened.shipment(shipmentId).orElseThrow().status());
                assertEquals(130, reopened.vehicle(vehicleId).orElseThrow().mileageKm());
                assertEquals("Service", reopened.maintenanceRecords().stream().filter(item -> item.id().equals(maintenanceId)).findFirst().orElseThrow().description());
                assertEquals(42, reopened.fuelRecords().stream().filter(item -> item.id().equals(fuelId)).findFirst().orElseThrow().litres());

                Vehicle currentVehicle = reopened.vehicle(vehicleId).orElseThrow();
                reopened.saveMaintenanceAndVehicle(new MaintenanceRecord(maintenanceId, vehicleId, LocalDate.now().minusDays(1), 120,
                                "Updated service", 45), currentVehicle);
                reopened.saveFuelAndVehicle(new FuelRecord(fuelId, vehicleId, LocalDate.now().minusDays(1), 125, 45, 37), currentVehicle);
                assertEquals("Updated service", reopened.maintenanceRecords().stream().filter(item -> item.id().equals(maintenanceId)).findFirst().orElseThrow().description());
                assertEquals(45, reopened.fuelRecords().stream().filter(item -> item.id().equals(fuelId)).findFirst().orElseThrow().litres());

                Shipment edited = new Shipment(shipment.id(), "Updated customer", shipment.customerContact(), shipment.address(),
                        shipment.packageDescription(), shipment.quantity(), shipment.weightKg(), shipment.priority(), shipment.status(),
                        shipment.driverId(), shipment.vehicleId(), shipment.routeId(), shipment.createdAt(), null);
                reopened.saveShipment(edited);
                assertEquals("Updated customer", reopened.shipment(shipmentId).orElseThrow().customerName());

                reopened.deleteShipment(shipmentId);
                reopened.deleteRoute(routeId);
                reopened.deleteMaintenance(maintenanceId);
                reopened.deleteFuel(fuelId);
                reopened.deleteVehicle(vehicleId);
                reopened.deleteUser(userId);
                assertFalse(reopened.userByEmail(user.email()).isPresent());
            }
        }
    }
}
