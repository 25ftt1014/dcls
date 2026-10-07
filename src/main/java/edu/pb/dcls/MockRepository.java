package edu.pb.dcls;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static edu.pb.dcls.Domain.*;

/** Seeded, in-memory data used for classroom demos and for development without a MySQL server. */
public final class MockRepository implements Repository {
    private final Map<String, User> users = new LinkedHashMap<>();
    private final Map<String, Vehicle> vehicles = new LinkedHashMap<>();
    private final Map<String, Shipment> shipments = new LinkedHashMap<>();
    private final Map<String, RoutePlan> routes = new LinkedHashMap<>();
    private final List<MaintenanceRecord> maintenance = new ArrayList<>();
    private final List<FuelRecord> fuel = new ArrayList<>();
    private final List<TrackingPoint> tracking = new ArrayList<>();

    public MockRepository() { seed(); }

    private void seed() {
        addUser("u-admin", "Aisha Rahman", "admin@dcls.local", "+673 812 4001", Role.ADMIN, "demo1234");
        addUser("u-manager", "Faris Iskandar", "manager@dcls.local", "+673 812 4002", Role.MANAGER, "demo1234");
        addUser("u-dispatcher", "Nadia Hassan", "dispatcher@dcls.local", "+673 812 4003", Role.DISPATCHER, "demo1234");
        addUser("u-driver-01", "Haziq Amin", "driver@dcls.local", "+673 812 4010", Role.DRIVER, "demo1234");
        addUser("u-driver-02", "Liyana Rahman", "liyana@dcls.local", "+673 812 4011", Role.DRIVER, "demo1234");

        vehicles.put("v-1934", new Vehicle("v-1934", "BQ 1934", "Isuzu N-Series", "Light truck", 1800, 24510,
                VehicleStatus.IN_DELIVERY, "u-driver-01", 26000));
        vehicles.put("v-3310", new Vehicle("v-3310", "BQ 3310", "Toyota Dyna", "Medium truck", 3500, 31840,
                VehicleStatus.AVAILABLE, "u-driver-02", 35000));
        vehicles.put("v-5201", new Vehicle("v-5201", "BQ 5201", "Mitsubishi Fuso", "Heavy truck", 6800, 52870,
                VehicleStatus.UNDER_MAINTENANCE, null, 53000));
        vehicles.put("v-8817", new Vehicle("v-8817", "BQ 8817", "Isuzu ELF", "Light truck", 1600, 18240,
                VehicleStatus.AVAILABLE, null, 25000));

        List<Stop> routeStops = List.of(
                new Stop("s-depot", "DCLS Depot", .08, .76),
                new Stop("s-kiulap", "Kiulap", .40, .52),
                new Stop("s-gadong", "Gadong Central", .67, .35),
                new Stop("s-berakas", "Berakas", .88, .18));
        RoutePlan route = new RoutePlan("r-1001", "North District Run", "u-driver-01", routeStops, 18.6, 42, false, LocalDateTime.now().minusMinutes(24));
        routes.put(route.id(), route);

        LocalDateTime now = LocalDateTime.now();
        shipments.put("DCLS-2401", new Shipment("DCLS-2401", "Hana Trading", "+673 223 1044", "Kiulap, Bandar Seri Begawan",
                "Office supplies", 4, 86, Priority.HIGH, OrderStatus.IN_TRANSIT, "u-driver-01", "v-1934", "r-1001", now.minusHours(2), null));
        shipments.put("DCLS-2402", new Shipment("DCLS-2402", "Rimba Mart", "+673 245 8810", "Gadong, Bandar Seri Begawan",
                "Dry goods", 12, 290, Priority.NORMAL, OrderStatus.ASSIGNED, "u-driver-02", "v-3310", null, now.minusHours(1), null));
        shipments.put("DCLS-2403", new Shipment("DCLS-2403", "Seri Kenangan Clinic", "+673 265 7700", "Berakas, Bandar Seri Begawan",
                "Medical supplies", 3, 64, Priority.URGENT, OrderStatus.PENDING, null, null, null, now.minusMinutes(34), null));
        shipments.put("DCLS-2398", new Shipment("DCLS-2398", "Kedai Mawar", "+673 233 2100", "Manggis, Bandar Seri Begawan",
                "Retail cartons", 6, 132, Priority.LOW, OrderStatus.DELIVERED, "u-driver-02", "v-3310", null, now.minusDays(1), now.minusHours(20)));
        shipments.put("DCLS-2404", new Shipment("DCLS-2404", "Jerudong Pharmacy", "+673 261 0500", "Jerudong, Brunei-Muara",
                "Pharmacy stock", 5, 108, Priority.HIGH, OrderStatus.PENDING, null, null, null, now.minusMinutes(9), null));

        maintenance.add(new MaintenanceRecord("m-01", "v-5201", LocalDate.now().minusDays(2), 52870, "Brake inspection and oil service", 245));
        maintenance.add(new MaintenanceRecord("m-02", "v-1934", LocalDate.now().minusDays(22), 23000, "Tyre replacement", 310));
        fuel.add(new FuelRecord("f-01", "v-1934", LocalDate.now().minusDays(1), 24420, 52, 39.50));
        fuel.add(new FuelRecord("f-02", "v-3310", LocalDate.now().minusDays(2), 31780, 66, 49.80));
        tracking.add(new TrackingPoint("t-01", "v-1934", "DCLS-2401", .42, .51, 18, now.minusMinutes(5)));
    }

    private void addUser(String id, String name, String email, String phone, Role role, String password) {
        Security.Credentials credentials = Security.credentials(password.toCharArray());
        users.put(id, new User(id, name, email, phone, credentials.hash(), credentials.salt(), role, true));
    }

    @Override public List<User> users() { return new ArrayList<>(users.values()); }
    @Override public Optional<User> userByEmail(String email) { return users.values().stream().filter(user -> user.email().equalsIgnoreCase(email)).findFirst(); }
    @Override public void saveUser(User user) { users.put(user.id(), user); }
    @Override public void deleteUser(String id) { users.remove(id); }
    @Override public List<Vehicle> vehicles() { return new ArrayList<>(vehicles.values()); }
    @Override public Optional<Vehicle> vehicle(String id) { return Optional.ofNullable(vehicles.get(id)); }
    @Override public void saveVehicle(Vehicle vehicle) { vehicles.put(vehicle.id(), vehicle); }
    @Override public void deleteVehicle(String id) { vehicles.remove(id); }
    @Override public List<Shipment> shipments() { return new ArrayList<>(shipments.values()); }
    @Override public Optional<Shipment> shipment(String id) { return Optional.ofNullable(shipments.get(id)); }
    @Override public void saveShipment(Shipment shipment) { shipments.put(shipment.id(), shipment); }
    @Override public void saveAssignment(Shipment shipment, Vehicle vehicle) { saveShipment(shipment); saveVehicle(vehicle); }
    @Override public void saveShipmentAndVehicle(Shipment shipment, Vehicle vehicle) { saveShipment(shipment); saveVehicle(vehicle); }
    @Override public void deleteShipment(String id) { shipments.remove(id); }
    @Override public List<RoutePlan> routes() { return new ArrayList<>(routes.values()); }
    @Override public void saveRoute(RoutePlan route) { routes.put(route.id(), route); }
    @Override public List<MaintenanceRecord> maintenanceRecords() { return new ArrayList<>(maintenance); }
    @Override public void saveMaintenance(MaintenanceRecord record) { maintenance.removeIf(item -> item.id().equals(record.id())); maintenance.add(record); }
    @Override public List<FuelRecord> fuelRecords() { return new ArrayList<>(fuel); }
    @Override public void saveFuel(FuelRecord record) { fuel.removeIf(item -> item.id().equals(record.id())); fuel.add(record); }
    @Override public List<TrackingPoint> trackingHistory() { return new ArrayList<>(tracking); }
    @Override public void saveTrackingPoint(TrackingPoint point) { tracking.add(point); }
    @Override public boolean persistent() { return false; }
}
