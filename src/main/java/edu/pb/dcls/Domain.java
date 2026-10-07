package edu.pb.dcls;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Domain types shared by the JavaFX screens and both repository implementations. */
public final class Domain {
    private Domain() { }

    public enum Role {
        ADMIN("Admin"), MANAGER("Manager"), DISPATCHER("Dispatcher"), DRIVER("Driver");
        private final String label;
        Role(String label) { this.label = label; }
        public String label() { return label; }
        @Override public String toString() { return label; }
    }

    public enum VehicleStatus {
        AVAILABLE("Available"), IN_DELIVERY("In Delivery"), UNDER_MAINTENANCE("Under Maintenance"), UNAVAILABLE("Unavailable");
        private final String label;
        VehicleStatus(String label) { this.label = label; }
        public String label() { return label; }
        @Override public String toString() { return label; }
    }

    public enum OrderStatus {
        PENDING("Pending"), ASSIGNED("Assigned"), IN_TRANSIT("In Transit"), DELIVERED("Delivered"), CANCELLED("Cancelled");
        private final String label;
        OrderStatus(String label) { this.label = label; }
        public String label() { return label; }
        @Override public String toString() { return label; }
    }

    public enum Priority {
        LOW("Low"), NORMAL("Normal"), HIGH("High"), URGENT("Urgent");
        private final String label;
        Priority(String label) { this.label = label; }
        public String label() { return label; }
        @Override public String toString() { return label; }
    }

    public static final class User {
        private final String id;
        private String name;
        private String email;
        private String phone;
        private String passwordHash;
        private String passwordSalt;
        private Role role;
        private boolean active;

        public User(String id, String name, String email, String phone, String passwordHash, String passwordSalt, Role role, boolean active) {
            this.id = id; this.name = name; this.email = email; this.phone = phone;
            this.passwordHash = passwordHash; this.passwordSalt = passwordSalt; this.role = role; this.active = active;
        }
        public String id() { return id; }
        public String name() { return name; }
        public String email() { return email; }
        public String phone() { return phone; }
        public String passwordHash() { return passwordHash; }
        public String passwordSalt() { return passwordSalt; }
        public Role role() { return role; }
        public boolean active() { return active; }
        public void updateProfile(String name, String email, String phone) { this.name = name; this.email = email; this.phone = phone; }
        public void setPassword(String hash, String salt) { passwordHash = hash; passwordSalt = salt; }
        public void setRole(Role role) { this.role = role; }
        public void setActive(boolean active) { this.active = active; }
    }

    public static final class Vehicle {
        private final String id;
        private String plate;
        private String model;
        private String type;
        private double capacityKg;
        private double mileageKm;
        private VehicleStatus status;
        private String driverId;
        private double nextServiceKm;

        public Vehicle(String id, String plate, String model, String type, double capacityKg, double mileageKm,
                       VehicleStatus status, String driverId, double nextServiceKm) {
            this.id = id; this.plate = plate; this.model = model; this.type = type; this.capacityKg = capacityKg;
            this.mileageKm = mileageKm; this.status = status; this.driverId = driverId; this.nextServiceKm = nextServiceKm;
        }
        public String id() { return id; }
        public String plate() { return plate; }
        public String model() { return model; }
        public String type() { return type; }
        public double capacityKg() { return capacityKg; }
        public double mileageKm() { return mileageKm; }
        public VehicleStatus status() { return status; }
        public String driverId() { return driverId; }
        public double nextServiceKm() { return nextServiceKm; }
        public void update(String plate, String model, String type, double capacityKg, double mileageKm, VehicleStatus status, String driverId, double nextServiceKm) {
            this.plate = plate; this.model = model; this.type = type; this.capacityKg = capacityKg; this.mileageKm = mileageKm;
            this.status = status; this.driverId = driverId; this.nextServiceKm = nextServiceKm;
        }
        public void setStatus(VehicleStatus status) { this.status = status; }
        public void setDriverId(String driverId) { this.driverId = driverId; }
    }

    public static final class Shipment {
        private final String id;
        private String customerName;
        private String customerContact;
        private String address;
        private String packageDescription;
        private int quantity;
        private double weightKg;
        private Priority priority;
        private OrderStatus status;
        private String driverId;
        private String vehicleId;
        private String routeId;
        private LocalDateTime createdAt;
        private LocalDateTime deliveredAt;
        private int deliveryMinutes;
        private int customerSatisfaction;

        public Shipment(String id, String customerName, String customerContact, String address, String packageDescription,
                        int quantity, double weightKg, Priority priority, OrderStatus status, String driverId,
                        String vehicleId, String routeId, LocalDateTime createdAt, LocalDateTime deliveredAt) {
            this.id = id; this.customerName = customerName; this.customerContact = customerContact; this.address = address;
            this.packageDescription = packageDescription; this.quantity = quantity; this.weightKg = weightKg; this.priority = priority;
            this.status = status; this.driverId = driverId; this.vehicleId = vehicleId; this.routeId = routeId;
            this.createdAt = createdAt; this.deliveredAt = deliveredAt;
        }
        public String id() { return id; }
        public String customerName() { return customerName; }
        public String customerContact() { return customerContact; }
        public String address() { return address; }
        public String packageDescription() { return packageDescription; }
        public int quantity() { return quantity; }
        public double weightKg() { return weightKg; }
        public Priority priority() { return priority; }
        public OrderStatus status() { return status; }
        public String driverId() { return driverId; }
        public String vehicleId() { return vehicleId; }
        public String routeId() { return routeId; }
        public LocalDateTime createdAt() { return createdAt; }
        public LocalDateTime deliveredAt() { return deliveredAt; }
        public int deliveryMinutes() { return deliveryMinutes; }
        public int customerSatisfaction() { return customerSatisfaction; }
        public void setCustomerSatisfaction(int rating) { customerSatisfaction = Math.max(0, Math.min(5, rating)); }
        public void setDeliveryMetrics(int minutes, int rating) { deliveryMinutes = Math.max(0, minutes); setCustomerSatisfaction(rating); }
        public void update(String customerName, String customerContact, String address, String packageDescription,
                           int quantity, double weightKg, Priority priority, String driverId, String vehicleId, String routeId) {
            this.customerName = customerName; this.customerContact = customerContact; this.address = address;
            this.packageDescription = packageDescription; this.quantity = quantity; this.weightKg = weightKg;
            this.priority = priority; this.driverId = driverId; this.vehicleId = vehicleId; this.routeId = routeId;
        }
        public void setStatus(OrderStatus status) {
            this.status = status;
            if (status == OrderStatus.DELIVERED) {
                deliveredAt = LocalDateTime.now();
                deliveryMinutes = Math.max(1, (int) java.time.Duration.between(createdAt, deliveredAt).toMinutes());
            }
        }
        public void setAssignments(String driverId, String vehicleId, String routeId) {
            this.driverId = driverId; this.vehicleId = vehicleId; this.routeId = routeId;
        }
    }

    public record Stop(String id, String name, double x, double y) { }
    public record RoutePlan(String id, String name, String driverId, List<Stop> stops, double distanceKm,
                            int estimatedMinutes, boolean completed, LocalDateTime createdAt) {
        public RoutePlan { stops = List.copyOf(stops); }
    }
    public record MaintenanceRecord(String id, String vehicleId, LocalDate date, double mileageKm, String description, double cost) { }
    public record FuelRecord(String id, String vehicleId, LocalDate date, double mileageKm, double litres, double cost) { }
    public record TrackingPoint(String id, String vehicleId, String shipmentId, double x, double y, int etaMinutes, LocalDateTime recordedAt) { }

    public interface Repository extends AutoCloseable {
        List<User> users();
        Optional<User> userByEmail(String email);
        void saveUser(User user);
        void deleteUser(String id);
        List<Vehicle> vehicles();
        Optional<Vehicle> vehicle(String id);
        void saveVehicle(Vehicle vehicle);
        void deleteVehicle(String id);
        List<Shipment> shipments();
        Optional<Shipment> shipment(String id);
        void saveShipment(Shipment shipment);
        void saveAssignment(Shipment shipment, Vehicle vehicle);
        void saveShipmentAndVehicle(Shipment shipment, Vehicle vehicle);
        void deleteShipment(String id);
        List<RoutePlan> routes();
        void saveRoute(RoutePlan route);
        List<MaintenanceRecord> maintenanceRecords();
        void saveMaintenance(MaintenanceRecord record);
        List<FuelRecord> fuelRecords();
        void saveFuel(FuelRecord record);
        List<TrackingPoint> trackingHistory();
        void saveTrackingPoint(TrackingPoint point);
        boolean persistent();
        @Override default void close() { }
    }
}
