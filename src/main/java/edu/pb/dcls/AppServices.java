package edu.pb.dcls;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static edu.pb.dcls.Domain.*;

final class AppServices {
    private AppServices() { }

    static final class AppException extends RuntimeException {
        AppException(String message) { super(message); }
        AppException(String message, Throwable cause) { super(message, cause); }
    }

    static final class AuthService {
        private final Repository repository;
        AuthService(Repository repository) { this.repository = repository; }

        User login(String email, char[] password) {
            if (email == null || email.isBlank() || password == null || password.length == 0) {
                throw new AppException("Enter your email and password.");
            }
            User user = repository.userByEmail(email.trim()).orElseThrow(() -> new AppException("Email or password is incorrect."));
            if (!user.active()) throw new AppException("This account is inactive. Ask an administrator for help.");
            if (!Security.matches(password, user.passwordHash(), user.passwordSalt())) {
                throw new AppException("Email or password is incorrect.");
            }
            return user;
        }

        User register(String name, String email, String phone, char[] password) {
            if (name == null || name.isBlank() || email == null || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw new AppException("Enter your name and a valid email address.");
            }
            if (password == null || password.length < 8) throw new AppException("Use a password with at least 8 characters.");
            if (repository.userByEmail(email.trim()).isPresent()) throw new AppException("An account with that email already exists.");
            Security.Credentials credentials = Security.credentials(password);
            User user = new User(id(), name.trim(), email.trim(), phone == null ? "" : phone.trim(),
                    credentials.hash(), credentials.salt(), Role.DRIVER, true);
            repository.saveUser(user);
            return user;
        }

        void changePassword(User user, char[] current, char[] next) {
            if (!Security.matches(current, user.passwordHash(), user.passwordSalt())) throw new AppException("Current password is incorrect.");
            if (next == null || next.length < 8) throw new AppException("Use a password with at least 8 characters.");
            Security.Credentials credentials = Security.credentials(next);
            user.setPassword(credentials.hash(), credentials.salt());
            repository.saveUser(user);
        }
    }

    static final class AccessService {
        boolean canOpen(Role role, String page) {
            return switch (role) {
                case ADMIN -> true;
                case MANAGER -> !page.equals("users");
                case DISPATCHER -> List.of("dashboard", "orders", "routes", "tracking", "profile").contains(page);
                case DRIVER -> List.of("dashboard", "orders", "tracking", "profile").contains(page);
            };
        }
        boolean canManageUsers(Role role) { return role == Role.ADMIN; }
        boolean canManageFleet(Role role) { return role == Role.ADMIN || role == Role.MANAGER; }
        boolean canDispatch(Role role) { return role == Role.ADMIN || role == Role.MANAGER || role == Role.DISPATCHER; }
        boolean canUpdateDelivery(Role role) { return role == Role.ADMIN || role == Role.MANAGER || role == Role.DISPATCHER || role == Role.DRIVER; }
    }

    static final class FleetService {
        private final Repository repository;
        FleetService(Repository repository) { this.repository = repository; }
        void save(Vehicle vehicle) {
            if (vehicle.plate().isBlank() || vehicle.model().isBlank() || vehicle.capacityKg() <= 0) {
                throw new AppException("Plate, model, and a positive capacity are required.");
            }
            boolean duplicate = repository.vehicles().stream().anyMatch(other -> !other.id().equals(vehicle.id()) && other.plate().equalsIgnoreCase(vehicle.plate().trim()));
            if (duplicate) throw new AppException("That plate number is already registered.");
            vehicle.update(vehicle.plate().trim().toUpperCase(), vehicle.model().trim(), vehicle.type().trim(),
                    vehicle.capacityKg(), vehicle.mileageKm(), vehicle.status(), vehicle.driverId(), vehicle.nextServiceKm());
            repository.saveVehicle(vehicle);
        }
        void setStatus(Vehicle vehicle, VehicleStatus status) {
            if (status == null) throw new AppException("Choose a vehicle status.");
            vehicle.setStatus(status);
            repository.saveVehicle(vehicle);
        }
        List<Vehicle> maintenanceDue() {
            return repository.vehicles().stream().filter(vehicle -> vehicle.mileageKm() >= vehicle.nextServiceKm() - 500).toList();
        }
        void logMaintenance(Vehicle vehicle, LocalDate date, double mileage, String details, double cost) {
            if (details.isBlank() || mileage < vehicle.mileageKm() || cost < 0) throw new AppException("Check the service details, mileage, and cost.");
            repository.saveMaintenance(new MaintenanceRecord(id(), vehicle.id(), date, mileage, details.trim(), cost));
            vehicle.update(vehicle.plate(), vehicle.model(), vehicle.type(), vehicle.capacityKg(), mileage,
                    VehicleStatus.AVAILABLE, vehicle.driverId(), Math.max(vehicle.nextServiceKm(), mileage + 5000));
            repository.saveVehicle(vehicle);
        }
        void logFuel(Vehicle vehicle, LocalDate date, double mileage, double litres, double cost) {
            if (mileage < vehicle.mileageKm() || litres <= 0 || cost < 0) throw new AppException("Check the fuel amount, mileage, and cost.");
            repository.saveFuel(new FuelRecord(id(), vehicle.id(), date, mileage, litres, cost));
            vehicle.update(vehicle.plate(), vehicle.model(), vehicle.type(), vehicle.capacityKg(), mileage,
                    vehicle.status(), vehicle.driverId(), vehicle.nextServiceKm());
            repository.saveVehicle(vehicle);
        }
    }

    static final class OrderService {
        private final Repository repository;
        OrderService(Repository repository) { this.repository = repository; }
        void save(Shipment shipment) {
            if (shipment.customerName().isBlank() || shipment.address().isBlank() || shipment.packageDescription().isBlank()) {
                throw new AppException("Customer, delivery address, and package details are required.");
            }
            if (shipment.quantity() < 1 || shipment.weightKg() < 0) throw new AppException("Quantity must be at least one and weight cannot be negative.");
            repository.saveShipment(shipment);
        }
        void assign(Shipment shipment, User driver, Vehicle vehicle, String routeId) {
            if (driver == null || driver.role() != Role.DRIVER || !driver.active()) throw new AppException("Choose an active driver account.");
            if (vehicle == null || vehicle.status() != VehicleStatus.AVAILABLE) throw new AppException("Choose an available vehicle.");
            if (shipment.weightKg() > vehicle.capacityKg()) throw new AppException("The shipment exceeds this vehicle's capacity.");
            if (shipment.status() == OrderStatus.DELIVERED || shipment.status() == OrderStatus.CANCELLED) throw new AppException("Completed or cancelled orders cannot be assigned.");
            shipment.setAssignments(driver.id(), vehicle.id(), routeId);
            shipment.setStatus(OrderStatus.ASSIGNED);
            vehicle.setStatus(VehicleStatus.IN_DELIVERY);
            repository.saveAssignment(shipment, vehicle);
        }
        void transition(Shipment shipment, OrderStatus next, User actor) {
            if (next == OrderStatus.CANCELLED) {
                if (shipment.status() == OrderStatus.DELIVERED) throw new AppException("A delivered order cannot be cancelled.");
            } else if (next == OrderStatus.ASSIGNED) {
                throw new AppException("Assign a driver and vehicle before marking this order assigned.");
            } else if (shipment.status() == OrderStatus.PENDING && next != OrderStatus.PENDING) {
                throw new AppException("Assign a driver and vehicle before dispatching this order.");
            } else if (shipment.status() == OrderStatus.ASSIGNED && next != OrderStatus.IN_TRANSIT) {
                throw new AppException("Assigned orders can move to In Transit.");
            } else if (shipment.status() == OrderStatus.IN_TRANSIT && next != OrderStatus.DELIVERED) {
                throw new AppException("In-transit orders can be confirmed as Delivered.");
            } else if (shipment.status() == OrderStatus.DELIVERED || shipment.status() == OrderStatus.CANCELLED) {
                throw new AppException("This order is already closed.");
            }
            if (actor.role() == Role.DRIVER && !actor.id().equals(shipment.driverId())) throw new AppException("Drivers can update only their assigned deliveries.");
            shipment.setStatus(next);
            if (shipment.vehicleId() != null && (next == OrderStatus.DELIVERED || next == OrderStatus.CANCELLED)) {
                repository.vehicle(shipment.vehicleId()).ifPresent(vehicle -> {
                    vehicle.setStatus(VehicleStatus.AVAILABLE);
                    repository.saveShipmentAndVehicle(shipment, vehicle);
                });
            } else repository.saveShipment(shipment);
        }
    }

    static final class RouteService {
        private static final Map<String, Stop> PLACES = Map.of(
                "DCLS Depot", new Stop("depot", "DCLS Depot", .08, .76),
                "Kiulap", new Stop("kiulap", "Kiulap", .40, .52),
                "Gadong", new Stop("gadong", "Gadong", .67, .35),
                "Berakas", new Stop("berakas", "Berakas", .88, .18),
                "Jerudong", new Stop("jerudong", "Jerudong", .14, .22),
                "Manggis", new Stop("manggis", "Manggis", .78, .72),
                "Tutong", new Stop("tutong", "Tutong", .36, .12));
        private final Repository repository;
        RouteService(Repository repository) { this.repository = repository; }
        List<String> placeNames() { return PLACES.keySet().stream().sorted().toList(); }

        RoutePlan optimize(String routeName, List<String> names, User driver) {
            if (names == null || names.size() < 2) throw new AppException("Add a depot and at least one stop.");
            List<Stop> stops = new ArrayList<>();
            for (String name : names) {
                Stop stop = PLACES.get(name);
                if (stop == null) throw new AppException("Choose a location from the list.");
                stops.add(stop);
            }
            List<Stop> ordered = new ArrayList<>();
            Stop current = stops.removeFirst();
            ordered.add(current);
            while (!stops.isEmpty()) {
                Stop from = current;
                current = stops.stream().min(Comparator.comparingDouble(stop -> distance(from, stop))).orElseThrow();
                stops.remove(current);
                ordered.add(current);
            }
            double km = 0;
            for (int i = 1; i < ordered.size(); i++) km += distance(ordered.get(i - 1), ordered.get(i));
            int minutes = Math.max(1, (int) Math.ceil(km / 35d * 60));
            RoutePlan route = new RoutePlan(id(), routeName == null || routeName.isBlank() ? "Delivery route" : routeName.trim(),
                    driver == null ? null : driver.id(), ordered, round(km), minutes, false, LocalDateTime.now());
            repository.saveRoute(route);
            return route;
        }
        private double distance(Stop first, Stop second) {
            double dx = first.x() - second.x();
            double dy = first.y() - second.y();
            return Math.sqrt(dx * dx + dy * dy) * 42;
        }
    }

    static final class TrackingService {
        private final Repository repository;
        private final Map<String, Double> progress = new HashMap<>();
        TrackingService(Repository repository) { this.repository = repository; }
        TrackingPoint advance(Shipment shipment) {
            if (shipment.routeId() == null) return null;
            Optional<RoutePlan> route = repository.routes().stream().filter(item -> item.id().equals(shipment.routeId())).findFirst();
            if (route.isEmpty() || route.get().stops().size() < 2) return null;
            double next = (progress.getOrDefault(shipment.id(), .28) + .018) % .86;
            progress.put(shipment.id(), next);
            List<Stop> stops = route.get().stops();
            double scaled = next * (stops.size() - 1);
            int segment = Math.min((int) scaled, stops.size() - 2);
            double local = scaled - segment;
            Stop a = stops.get(segment), b = stops.get(segment + 1);
            int eta = Math.max(1, route.get().estimatedMinutes() - (int) (next * route.get().estimatedMinutes()));
            TrackingPoint point = new TrackingPoint(id(), shipment.vehicleId(), shipment.id(),
                    a.x() + (b.x() - a.x()) * local, a.y() + (b.y() - a.y()) * local, eta, LocalDateTime.now());
            repository.saveTrackingPoint(point);
            return point;
        }
        TrackingPoint latestFor(String shipmentId) {
            return repository.trackingHistory().stream().filter(point -> shipmentId.equals(point.shipmentId()))
                    .max(Comparator.comparing(TrackingPoint::recordedAt)).orElse(null);
        }
    }

    static String id() { return UUID.randomUUID().toString(); }
    private static double round(double value) { return Math.round(value * 10d) / 10d; }
}
