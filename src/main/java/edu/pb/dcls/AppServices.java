package edu.pb.dcls;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
            boolean firstAccount = repository.users().isEmpty();
            User user = new User(id(), name.trim(), email.trim(), phone == null ? "" : phone.trim(),
                    credentials.hash(), credentials.salt(), firstAccount ? Role.ADMIN : Role.DRIVER, firstAccount, !firstAccount);
            repository.saveUser(user);
            return user;
        }

        void changePassword(User user, char[] current, char[] next) {
            if (!Security.matches(current, user.passwordHash(), user.passwordSalt())) throw new AppException("Current password is incorrect.");
            if (next == null || next.length < 8) throw new AppException("Use a password with at least 8 characters.");
            Security.Credentials credentials = Security.credentials(next);
            User updated = new User(user.id(), user.name(), user.email(), user.phone(), user.passwordHash(), user.passwordSalt(),
                    user.role(), user.active(), user.pending());
            updated.setPassword(credentials.hash(), credentials.salt());
            repository.saveUser(updated);
            user.setPassword(credentials.hash(), credentials.salt());
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
        private static final int SERVICE_WARNING_KM = 500;
        private final Repository repository;
        FleetService(Repository repository) { this.repository = repository; }
        void save(Vehicle vehicle) {
            if (vehicle == null) throw new AppException("Vehicle details are required.");
            if (vehicle.plate() == null || vehicle.plate().isBlank() || vehicle.model() == null || vehicle.model().isBlank() || vehicle.capacityKg() <= 0) {
                throw new AppException("Plate, model, and a positive capacity are required.");
            }
            if (vehicle.mileageKm() < 0 || vehicle.nextServiceKm() <= 0) {
                throw new AppException("Mileage cannot be negative and next service must be positive.");
            }
            if (vehicle.status() == null) throw new AppException("Choose a vehicle status.");
            boolean duplicate = repository.vehicles().stream().anyMatch(other -> !other.id().equals(vehicle.id()) && other.plate().equalsIgnoreCase(vehicle.plate().trim()));
            if (duplicate) throw new AppException("That plate number is already registered.");
            String type = vehicle.type() == null ? "" : vehicle.type().trim();
            Vehicle updated = copy(vehicle);
            updated.update(vehicle.plate().trim().toUpperCase(), vehicle.model().trim(), type,
                    vehicle.capacityKg(), vehicle.mileageKm(), vehicle.status(), vehicle.driverId(), vehicle.nextServiceKm());
            repository.saveVehicle(updated);
        }
        void setStatus(Vehicle vehicle, VehicleStatus status) {
            if (vehicle == null) throw new AppException("Choose a vehicle first.");
            if (status == null) throw new AppException("Choose a vehicle status.");
            if (status == VehicleStatus.IN_DELIVERY) throw new AppException("Vehicles enter In Delivery only when assigned to an order.");
            Vehicle updated = copy(vehicle); updated.setStatus(status);
            repository.saveVehicle(updated);
        }
        List<Vehicle> maintenanceDue() {
            return repository.vehicles().stream().filter(vehicle -> maintenanceStatus(vehicle) != MaintenanceStatus.OK).toList();
        }
        /** Service urgency for one vehicle: past its service point, inside the warning window, or fine. */
        MaintenanceStatus maintenanceStatus(Vehicle vehicle) {
            if (vehicle == null) throw new AppException("Choose a vehicle first.");
            if (vehicle.mileageKm() >= vehicle.nextServiceKm()) return MaintenanceStatus.OVERDUE;
            if (vehicle.mileageKm() >= vehicle.nextServiceKm() - SERVICE_WARNING_KM) return MaintenanceStatus.DUE_SOON;
            return MaintenanceStatus.OK;
        }
        /** Kilometres per litre across a vehicle's fuel records; empty when there is too little data. */
        OptionalDouble fuelEfficiencyKmPerL(String vehicleId) {
            List<FuelRecord> records = repository.fuelRecords().stream().filter(record -> vehicleId.equals(record.vehicleId())).toList();
            double litres = records.stream().mapToDouble(FuelRecord::litres).sum();
            double first = records.stream().mapToDouble(FuelRecord::mileageKm).min().orElse(0);
            double last = records.stream().mapToDouble(FuelRecord::mileageKm).max().orElse(0);
            if (records.size() < 2 || litres <= 0 || last <= first) return OptionalDouble.empty();
            return OptionalDouble.of((last - first) / litres);
        }
        enum MaintenanceStatus { OK, DUE_SOON, OVERDUE }
        void logMaintenance(Vehicle vehicle, LocalDate date, double mileage, String details, double cost) {
            if (vehicle == null) throw new AppException("Choose a vehicle first.");
            if (date == null) throw new AppException("Choose a service date.");
            if (details == null || details.isBlank() || mileage < vehicle.mileageKm() || cost < 0) throw new AppException("Check the service details, mileage, and cost.");
            MaintenanceRecord record = new MaintenanceRecord(id(), vehicle.id(), date, mileage, details.trim(), cost);
            Vehicle updated = copy(vehicle);
            updated.update(vehicle.plate(), vehicle.model(), vehicle.type(), vehicle.capacityKg(), mileage,
                    VehicleStatus.AVAILABLE, vehicle.driverId(), Math.max(vehicle.nextServiceKm(), mileage + 5000));
            repository.saveMaintenanceAndVehicle(record, updated);
        }
        void logFuel(Vehicle vehicle, LocalDate date, double mileage, double litres, double cost) {
            if (vehicle == null) throw new AppException("Choose a vehicle first.");
            if (date == null) throw new AppException("Choose a refuel date.");
            if (mileage < vehicle.mileageKm() || litres <= 0 || cost < 0) throw new AppException("Check the fuel amount, mileage, and cost.");
            FuelRecord record = new FuelRecord(id(), vehicle.id(), date, mileage, litres, cost);
            Vehicle updated = copy(vehicle);
            updated.update(vehicle.plate(), vehicle.model(), vehicle.type(), vehicle.capacityKg(), mileage,
                    vehicle.status(), vehicle.driverId(), vehicle.nextServiceKm());
            repository.saveFuelAndVehicle(record, updated);
        }
        void deleteVehicle(String id) {
            boolean usedByShipment = repository.shipments().stream().anyMatch(shipment -> id.equals(shipment.vehicleId()));
            boolean hasMaintenance = repository.maintenanceRecords().stream().anyMatch(record -> id.equals(record.vehicleId()));
            boolean hasFuel = repository.fuelRecords().stream().anyMatch(record -> id.equals(record.vehicleId()));
            if (usedByShipment || hasMaintenance || hasFuel) {
                throw new AppException("This vehicle is linked to orders or service history. Remove those records first, then try again.");
            }
            repository.deleteVehicle(id);
        }
        void updateMaintenance(MaintenanceRecord existing, LocalDate date, double mileage, String details, double cost) {
            if (existing == null) throw new AppException("Choose a maintenance record first.");
            if (date == null) throw new AppException("Choose a service date.");
            if (details == null || details.isBlank() || mileage < 0 || cost < 0) throw new AppException("Check the service details, mileage, and cost.");
            Vehicle vehicle = repository.vehicle(existing.vehicleId()).orElseThrow(() -> new AppException("The vehicle for this record no longer exists."));
            Vehicle updated = copy(vehicle);
            double currentMileage = Math.max(vehicle.mileageKm(), mileage);
            updated.update(vehicle.plate(), vehicle.model(), vehicle.type(), vehicle.capacityKg(), currentMileage,
                    vehicle.status(), vehicle.driverId(), Math.max(vehicle.nextServiceKm(), mileage + 5000));
            repository.saveMaintenanceAndVehicle(new MaintenanceRecord(existing.id(), existing.vehicleId(), date, mileage, details.trim(), cost), updated);
        }
        void deleteMaintenance(String id) { repository.deleteMaintenance(id); }
        void updateFuel(FuelRecord existing, LocalDate date, double mileage, double litres, double cost) {
            if (existing == null) throw new AppException("Choose a fuel record first.");
            if (date == null) throw new AppException("Choose a refuel date.");
            if (mileage < 0 || litres <= 0 || cost < 0) throw new AppException("Check the fuel amount, mileage, and cost.");
            Vehicle vehicle = repository.vehicle(existing.vehicleId()).orElseThrow(() -> new AppException("The vehicle for this record no longer exists."));
            Vehicle updated = copy(vehicle);
            updated.update(vehicle.plate(), vehicle.model(), vehicle.type(), vehicle.capacityKg(), Math.max(vehicle.mileageKm(), mileage),
                    vehicle.status(), vehicle.driverId(), vehicle.nextServiceKm());
            repository.saveFuelAndVehicle(new FuelRecord(existing.id(), existing.vehicleId(), date, mileage, litres, cost), updated);
        }
        void deleteFuel(String id) { repository.deleteFuel(id); }
        private Vehicle copy(Vehicle vehicle) {
            return new Vehicle(vehicle.id(), vehicle.plate(), vehicle.model(), vehicle.type(), vehicle.capacityKg(),
                    vehicle.mileageKm(), vehicle.status(), vehicle.driverId(), vehicle.nextServiceKm());
        }
    }

    static final class OrderService {
        private final Repository repository;
        OrderService(Repository repository) { this.repository = repository; }
        void delete(Shipment shipment) {
            if (shipment == null) return;
            if (shipment.status() == OrderStatus.ASSIGNED || shipment.status() == OrderStatus.IN_TRANSIT) {
                throw new AppException("Cancel or complete this delivery before deleting it.");
            }
            if (shipment.status() == OrderStatus.DELIVERED) {
                throw new AppException("Delivered orders are retained as delivery history.");
            }
            repository.deleteShipment(shipment.id());
        }
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
            Shipment updatedShipment = copy(shipment);
            Vehicle updatedVehicle = copy(vehicle);
            updatedShipment.setAssignments(driver.id(), vehicle.id(), routeId);
            updatedShipment.setStatus(OrderStatus.ASSIGNED);
            updatedVehicle.setStatus(VehicleStatus.IN_DELIVERY);
            repository.saveAssignment(updatedShipment, updatedVehicle);
        }
        void transition(Shipment shipment, OrderStatus next, User actor) {
            transition(shipment, next, actor, 0);
        }
        void transition(Shipment shipment, OrderStatus next, User actor, int rating) {
            if (next == OrderStatus.CANCELLED) {
                if (shipment.status() != OrderStatus.PENDING) throw new AppException("Only a pending order can be cancelled.");
            } else if (shipment.status() == OrderStatus.PENDING) {
                // PENDING → ASSIGNED is legal once a driver and vehicle are set (Advance path);
                // anything else must go through assignment first.
                if (next != OrderStatus.ASSIGNED) throw new AppException("Assign a driver and vehicle before dispatching this order.");
                if (shipment.driverId() == null || shipment.vehicleId() == null) throw new AppException("Assign a driver and vehicle before marking this order assigned.");
            } else if (shipment.status() == OrderStatus.ASSIGNED && next != OrderStatus.IN_TRANSIT) {
                throw new AppException("Assigned orders can move to In Transit.");
            } else if (shipment.status() == OrderStatus.IN_TRANSIT && next != OrderStatus.DELIVERED) {
                throw new AppException("In-transit orders can be confirmed as Delivered.");
            } else if (shipment.status() == OrderStatus.DELIVERED || shipment.status() == OrderStatus.CANCELLED) {
                throw new AppException("This order is already closed.");
            }
            if (actor.role() == Role.DRIVER && !actor.id().equals(shipment.driverId())) throw new AppException("Drivers can update only their assigned deliveries.");
            Shipment updatedShipment = copy(shipment);
            if (rating > 0) updatedShipment.setCustomerSatisfaction(rating);
            updatedShipment.setStatus(next);
            Optional<Vehicle> assignedVehicle = shipment.vehicleId() == null ? Optional.empty() : repository.vehicle(shipment.vehicleId());
            if (next == OrderStatus.DELIVERED && assignedVehicle.isPresent()) {
                Vehicle freed = copy(assignedVehicle.get());
                freed.setStatus(VehicleStatus.AVAILABLE);
                repository.saveShipmentAndVehicle(updatedShipment, freed);
            } else repository.saveShipment(updatedShipment);
        }
        private Shipment copy(Shipment shipment) {
            Shipment copied = new Shipment(shipment.id(), shipment.customerName(), shipment.customerContact(), shipment.address(),
                    shipment.packageDescription(), shipment.quantity(), shipment.weightKg(), shipment.priority(), shipment.status(),
                    shipment.driverId(), shipment.vehicleId(), shipment.routeId(), shipment.createdAt(), shipment.deliveredAt());
            copied.setDeliveryMetrics(shipment.deliveryMinutes(), shipment.customerSatisfaction());
            return copied;
        }
        private Vehicle copy(Vehicle vehicle) {
            return new Vehicle(vehicle.id(), vehicle.plate(), vehicle.model(), vehicle.type(), vehicle.capacityKg(),
                    vehicle.mileageKm(), vehicle.status(), vehicle.driverId(), vehicle.nextServiceKm());
        }
    }

    /** Shared Brunei Darussalam coordinate base (WGS84 degrees, sensible Bandar defaults).
     *  Stop.x() carries longitude, Stop.y() carries latitude so existing storage keeps working. */
    static final class BruneiPlaces {
        /** Reference driving speed for ETA estimates when a fix carries no speed. */
        static final double AVG_SPEED_KMH = 35d;
        static final Stop DEPOT = new Stop("depot", "DCLS Depot", 114.9400, 4.8900);

        static Map<String, Stop> places() {
            Map<String, Stop> places = new LinkedHashMap<>();
            places.put("DCLS Depot", DEPOT);
            places.put("Kiulap", new Stop("kiulap", "Kiulap", 114.9160, 4.9020));
            places.put("Gadong", new Stop("gadong", "Gadong", 114.9280, 4.9080));
            places.put("Berakas", new Stop("berakas", "Berakas", 114.9500, 4.9400));
            places.put("Jerudong", new Stop("jerudong", "Jerudong", 114.8400, 4.9500));
            places.put("Manggis", new Stop("manggis", "Manggis", 114.9800, 4.9700));
            places.put("Tutong", new Stop("tutong", "Tutong", 114.6600, 4.8100));
            places.put("Bandar", new Stop("bandar", "Bandar", 114.9420, 4.8900));
            places.put("Mentiri", new Stop("mentiri", "Mentiri", 114.9900, 4.9600));
            places.put("Sengkurong", new Stop("sengkurong", "Sengkurong", 114.8800, 4.9300));
            return places;
        }

        /** Great-circle distance in km between two lon/lat stops. */
        static double haversineKm(double lonA, double latA, double lonB, double latB) {
            double dLat = Math.toRadians(latB - latA);
            double dLon = Math.toRadians(lonB - lonA);
            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(Math.toRadians(latA)) * Math.cos(Math.toRadians(latB))
                    * Math.sin(dLon / 2) * Math.sin(dLon / 2);
            return 6371d * 2 * Math.asin(Math.sqrt(a));
        }

        static double haversineKm(Stop first, Stop second) {
            return haversineKm(first.x(), first.y(), second.x(), second.y());
        }

        /** Resolve a free-text delivery address to the nearest known Brunei stop. */
        static Stop geocodeAddress(String address, Map<String, Stop> places) {
            if (address != null && !address.isBlank()) {
                String text = address.toLowerCase(Locale.ROOT);
                for (Map.Entry<String, Stop> entry : places.entrySet()) {
                    if (!entry.getKey().equals("DCLS Depot") && text.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                        return entry.getValue();
                    }
                }
            }
            return places.get("Gadong");
        }
    }

    /** Real road network bundled offline (one-time OSRM fetch): coord-keyed pairs
     *  {@code "lonA,latA>lonB,latB"} to road km plus the full road polyline.
     *  Missing pairs fall back to straight haversine so the app never breaks. */
    static final class RoadNetwork {
        private static volatile boolean loaded;
        private static final Map<String, Double> KM = new HashMap<>();
        private static final Map<String, List<double[]>> PATHS = new HashMap<>();

        static Double roadKm(double lonA, double latA, double lonB, double latB) {
            ensureLoaded();
            return KM.get(key(lonA, latA, lonB, latB));
        }

        static List<double[]> roadPath(double lonA, double latA, double lonB, double latB) {
            ensureLoaded();
            List<double[]> path = PATHS.get(key(lonA, latA, lonB, latB));
            if (path == null) return List.of(new double[] {lonA, latA}, new double[] {lonB, latB});
            return path;
        }

        static String key(double lonA, double latA, double lonB, double latB) {
            return r5(lonA) + "," + r5(latA) + ">" + r5(lonB) + "," + r5(latB);
        }

        private static double r5(double value) { return Math.round(value * 1e5) / 1e5; }

        private static void ensureLoaded() {
            if (loaded) return;
            synchronized (RoadNetwork.class) {
                if (loaded) return;
                loaded = true;
                try (InputStream in = AppServices.class.getResourceAsStream("/edu/pb/dcls/road-network.json")) {
                    if (in == null) return;
                    parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception ignored) { }
            }
        }

        /** Minimal parser for the known bundle shape (no JSON dependency).
         *  Entries look like {@code "lonA,latA>lonB,latB":{"path":[[..]],"km":x,"minutes":y}}
         *  with {@code >} possibly unicode-escaped by the exporting tool. */
        private static void parse(String json) {
            int cursor = 0;
            java.util.regex.Matcher entry = java.util.regex.Pattern
                    .compile("\"([^\"]+)\":\\{\"path\":\\[").matcher(json);
            java.util.regex.Matcher numbers = java.util.regex.Pattern.compile("-?[\\d.]+").matcher("");
            java.util.regex.Matcher kmValue = java.util.regex.Pattern.compile("\"km\":([\\d.]+)").matcher("");
            while (entry.find(cursor)) {
                String key = entry.group(1).replace("\\u003e", ">");
                int arrStart = entry.end() - 1;
                int depth = 0, end = arrStart;
                for (; end < json.length(); end++) {
                    char c = json.charAt(end);
                    if (c == '[') depth++;
                    else if (c == ']') { depth--; if (depth == 0) break; }
                }
                double km = -1;
                kmValue.reset(json);
                if (kmValue.find(end) && kmValue.start() < end + 400) {
                    try { km = Double.parseDouble(kmValue.group(1)); } catch (NumberFormatException ignored) { }
                }
                numbers.reset(json.substring(arrStart, Math.min(end + 1, json.length())));
                List<Double> values = new ArrayList<>();
                while (numbers.find()) {
                    try { values.add(Double.parseDouble(numbers.group())); } catch (NumberFormatException ignored) { }
                }
                List<double[]> path = new ArrayList<>();
                for (int i = 0; i + 1 < values.size(); i += 2) {
                    path.add(new double[] {values.get(i), values.get(i + 1)});
                }
                if (key.contains(">") && km >= 0 && path.size() >= 2) {
                    KM.put(key, km);
                    PATHS.put(key, path);
                }
                cursor = end + 1;
            }
        }
    }

    /** Live road routing via OSRM (demo server, no key): true road-following legs for
     *  arbitrary points. Memory + disk cache, paced serial fetches; layered fallbacks
     *  (bundled pair, district-snap, straight) keep routing working offline. */
    static final class OsrmRouter {
        private OsrmRouter() { }

        record LegResult(List<double[]> path, double km, boolean roadQuality) { }

        private static final String OSRM_BASE = System.getenv()
                .getOrDefault("DCLS_OSRM_URL", "https://router.project-osrm.org").trim().replaceAll("/$", "");
        private static final Map<String, Stop> DISTRICTS = BruneiPlaces.places();
        private static final Map<String, LegResult> OMEM = Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, LegResult> entry) { return size() > 200; }
        });
        private static final Map<String, double[]> NEAR = Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, double[]> entry) { return size() > 200; }
        });
        private static final Set<String> OSM_IN_FLIGHT = Collections.synchronizedSet(new java.util.HashSet<>());
        private static final java.net.http.HttpClient OSRM_HTTP = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).proxy(MapRenderer.tileProxy()).build();
        private static final AtomicLong NEXT_OSRM_AT = new AtomicLong();

        static String osrmUrl(double lonA, double latA, double lonB, double latB) {
            return OSRM_BASE + "/route/v1/driving/" + f6(lonA) + "," + f6(latA) + ";" + f6(lonB) + "," + f6(latB)
                    + "?overview=full&geometries=geojson";
        }

        private static String f6(double value) { return String.format(Locale.US, "%.6f", value); }

        private static String okey(double lonA, double latA, double lonB, double latB) {
            return "osrm:" + r4(lonA) + "," + r4(latA) + ">" + r4(lonB) + "," + r4(latB);
        }

        private static double r4(double value) { return Math.round(value * 1e4) / 1e4; }

        private static Path osrmDiskPath(String okey) {
            return Path.of(System.getProperty("user.home"), ".dcls", "osrm", Integer.toHexString(okey.hashCode()) + ".json");
        }

        /** Cached leg (bundled pair or prior OSRM result), or null when a fetch is needed. */
        static LegResult leg(double lonA, double latA, double lonB, double latB) {
            List<double[]> exact = RoadNetwork.PATHS.get(RoadNetwork.key(lonA, latA, lonB, latB));
            Double exactKm = RoadNetwork.KM.get(RoadNetwork.key(lonA, latA, lonB, latB));
            if (exact != null && exactKm != null) return new LegResult(exact, exactKm, true);
            String okey = okey(lonA, latA, lonB, latB);
            LegResult hit = OMEM.get(okey);
            if (hit != null) return hit;
            try {
                Path disk = osrmDiskPath(okey);
                if (Files.isRegularFile(disk)) {
                    LegResult parsed = parseOsrm(new String(Files.readAllBytes(disk), StandardCharsets.UTF_8));
                    if (parsed != null) { OMEM.put(okey, parsed); return parsed; }
                }
            } catch (Exception ignored) { }
            return null;
        }

        /** Cached OSRM km without fetching (for instant ordering); null when unknown. */
        static Double cachedKm(double lonA, double latA, double lonB, double latB) {
            LegResult cached = leg(lonA, latA, lonB, latB);
            return cached == null ? null : cached.km();
        }

        /** Full resolution: cache, then one network fetch, then district-snap fallback. */
        static LegResult resolve(double lonA, double latA, double lonB, double latB) {
            LegResult cached = leg(lonA, latA, lonB, latB);
            if (cached != null) return cached;
            LegResult fetched = fetchLeg(lonA, latA, lonB, latB);
            if (fetched != null) return fetched;
            return snapLeg(lonA, latA, lonB, latB);
        }

        static LegResult fetchLeg(double lonA, double latA, double lonB, double latB) {
            String okey = okey(lonA, latA, lonB, latB);
            if (!OSM_IN_FLIGHT.add(okey)) return null;
            try {
                paceOsrm();
                HttpRequest request = HttpRequest.newBuilder(URI.create(osrmUrl(lonA, latA, lonB, latB)))
                        .timeout(Duration.ofSeconds(12))
                        .header("User-Agent", "DCLS classroom demo")
                        .GET().build();
                HttpResponse<String> response = OSRM_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    LegResult parsed = parseOsrm(response.body());
                    if (parsed != null) {
                        OMEM.put(okey, parsed);
                        try {
                            Path disk = osrmDiskPath(okey);
                            Files.createDirectories(disk.getParent());
                            Files.writeString(disk, response.body(), StandardCharsets.UTF_8);
                        } catch (Exception ignored) { }
                        return parsed;
                    }
                    System.out.println("[dcls-osrm] " + shortKey(lonA, latA, lonB, latB) + " -> unparseable");
                } else {
                    System.out.println("[dcls-osrm] " + shortKey(lonA, latA, lonB, latB) + " -> HTTP " + response.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.out.println("[dcls-osrm] " + shortKey(lonA, latA, lonB, latB) + " -> " + e.getClass().getSimpleName());
            } finally {
                OSM_IN_FLIGHT.remove(okey);
            }
            return null;
        }

        /** Snap a point to the nearest road via OSRM /nearest (r6 coords), or null. */
        static double[] snapToRoad(double lon, double lat) {
            String key = "nr:" + r4(lon) + "," + r4(lat);
            double[] hit = NEAR.get(key);
            if (hit != null) return hit;
            Path disk = Path.of(System.getProperty("user.home"), ".dcls", "osrm",
                    "nearest-" + Integer.toHexString(key.hashCode()) + ".txt");
            try {
                if (Files.isRegularFile(disk)) {
                    String[] parts = Files.readString(disk, StandardCharsets.UTF_8).trim().split(",");
                    if (parts.length == 2) {
                        double[] coords = {Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
                        NEAR.put(key, coords);
                        return coords;
                    }
                }
            } catch (Exception ignored) { }
            paceOsrm();
            try {
                String url = OSRM_BASE + "/nearest/v1/driving/" + f6(lon) + "," + f6(lat) + ".json?number=1";
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(12))
                        .header("User-Agent", "DCLS classroom demo")
                        .GET().build();
                HttpResponse<String> response = OSRM_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    java.util.regex.Matcher location = java.util.regex.Pattern
                            .compile("\"location\":\\[(-?[\\d.]+),(-?[\\d.]+)\\]").matcher(response.body());
                    if (location.find()) {
                        double[] coords = {Double.parseDouble(location.group(1)), Double.parseDouble(location.group(2))};
                        NEAR.put(key, coords);
                        try {
                            Files.createDirectories(disk.getParent());
                            Files.writeString(disk, coords[0] + "," + coords[1], StandardCharsets.UTF_8);
                        } catch (Exception ignored) { }
                        return coords;
                    }
                } else {
                    System.out.println("[dcls-osrm] nearest " + shortKey(lon, lat, lon, lat) + " -> HTTP " + response.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.out.println("[dcls-osrm] nearest " + shortKey(lon, lat, lon, lat) + " -> " + e.getClass().getSimpleName());
            }
            return null;
        }

        /** One-shot distance matrix (km) via OSRM /table, or null offline. Depot-agnostic. */
        static double[][] tableKm(List<Stop> stops) {
            if (stops == null || stops.size() < 2 || stops.size() > 100) return null;
            StringBuilder coords = new StringBuilder();
            for (Stop stop : stops) {
                if (coords.length() > 0) coords.append(';');
                coords.append(f6(stop.x())).append(',').append(f6(stop.y()));
            }
            paceOsrm();
            try {
                String url = OSRM_BASE + "/table/v1/driving/" + coords + "?annotations=distance";
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "DCLS classroom demo")
                        .GET().build();
                HttpResponse<String> response = OSRM_HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    double[][] matrix = parseTable(response.body());
                    if (matrix != null && matrix.length == stops.size()) return matrix;
                    System.out.println("[dcls-osrm] table -> unparseable");
                } else {
                    System.out.println("[dcls-osrm] table -> HTTP " + response.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.out.println("[dcls-osrm] table -> " + e.getClass().getSimpleName());
            }
            return null;
        }

        /** Parse an OSRM /table response into a square km matrix, or null. */
        static double[][] parseTable(String json) {
            int d = json.indexOf("\"distances\"");
            if (d < 0) return null;
            int start = json.indexOf('[', d + 12);
            if (start < 0) return null;
            List<List<Double>> rows = new ArrayList<>();
            List<Double> current = null;
            int depth = 0;
            StringBuilder num = new StringBuilder();
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[') {
                    depth++;
                    if (depth == 2) current = new ArrayList<>();
                } else if (c == ']') {
                    flushNumber(num, current);
                    if (depth == 2 && current != null) { rows.add(current); current = null; }
                    depth--;
                    if (depth == 0) break;
                } else if (current != null && (Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E')) {
                    num.append(c);
                } else {
                    flushNumber(num, current);
                }
            }
            if (rows.size() < 2) return null;
            for (List<Double> row : rows) if (row.size() != rows.size()) return null;
            double[][] matrix = new double[rows.size()][rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).contains(null)) return null;
                for (int j = 0; j < rows.size(); j++) matrix[i][j] = rows.get(i).get(j) / 1000d;
            }
            return matrix;
        }

        private static void flushNumber(StringBuilder num, List<Double> current) {
            if (num.length() == 0 || current == null) { num.setLength(0); return; }
            try {
                current.add(Double.valueOf(num.toString()));
            } catch (NumberFormatException ignored) {
                current.add(null);
            }
            num.setLength(0);
        }

        /** Fastest visiting order over a km matrix, first stop anchored.
         *  Exact brute force up to 8 movable stops, greedy + 2-opt beyond. */
        static List<Stop> orderByMatrix(List<Stop> stops, double[][] matrix) {
            int n = stops.size();
            if (n <= 2 || matrix == null || matrix.length != n) return new ArrayList<>(stops);
            int m = n - 1;
            int[] best;
            if (m <= 8) {
                int[] perm = new int[m];
                for (int i = 0; i < m; i++) perm[i] = i + 1;
                best = perm.clone();
                double bestCost = tourCost(perm, matrix);
                while (nextPermutation(perm)) {
                    double cost = tourCost(perm, matrix);
                    if (cost < bestCost) { bestCost = cost; best = perm.clone(); }
                }
            } else {
                best = twoOptIndices(matrix);
            }
            List<Stop> ordered = new ArrayList<>(n);
            ordered.add(stops.get(0));
            for (int idx : best) ordered.add(stops.get(idx));
            return ordered;
        }

        private static double tourCost(int[] perm, double[][] matrix) {
            double cost = matrix[0][perm[0]];
            for (int i = 1; i < perm.length; i++) cost += matrix[perm[i - 1]][perm[i]];
            return cost;
        }

        private static boolean nextPermutation(int[] perm) {
            int i = perm.length - 2;
            while (i >= 0 && perm[i] >= perm[i + 1]) i--;
            if (i < 0) return false;
            int j = perm.length - 1;
            while (perm[j] <= perm[i]) j--;
            int swap = perm[i]; perm[i] = perm[j]; perm[j] = swap;
            for (int a = i + 1, b = perm.length - 1; a < b; a++, b--) {
                swap = perm[a]; perm[a] = perm[b]; perm[b] = swap;
            }
            return true;
        }

        private static int[] twoOptIndices(double[][] matrix) {
            int n = matrix.length;
            boolean[] used = new boolean[n];
            int[] order = new int[n - 1];
            used[0] = true;
            int current = 0;
            for (int k = 0; k < n - 1; k++) {
                int next = -1; double best = Double.MAX_VALUE;
                for (int j = 1; j < n; j++) {
                    if (!used[j] && matrix[current][j] < best) { best = matrix[current][j]; next = j; }
                }
                order[k] = next; used[next] = true; current = next;
            }
            boolean improved = true;
            while (improved) {
                improved = false;
                for (int i = 0; i < order.length - 1; i++) {
                    for (int j = i + 1; j < order.length; j++) {
                        int a = i == 0 ? 0 : order[i - 1];
                        int b = order[i], c = order[j];
                        double before = matrix[a][b] + (j + 1 < order.length ? matrix[c][order[j + 1]] : 0);
                        double after = matrix[a][c] + (j + 1 < order.length ? matrix[order[i]][order[j + 1]] : 0);
                        if (after < before - 1e-9) {
                            for (int x = i, y = j; x < y; x++, y--) {
                                int swap = order[x]; order[x] = order[y]; order[y] = swap;
                            }
                            improved = true;
                        }
                    }
                }
            }
            return order;
        }

        private static void paceOsrm() {
            long waitUntil = NEXT_OSRM_AT.get();
            long now = System.currentTimeMillis();
            if (waitUntil > now) {
                try {
                    Thread.sleep(waitUntil - now);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            NEXT_OSRM_AT.set(Math.max(System.currentTimeMillis(), waitUntil) + 200);
        }

        private static String shortKey(double lonA, double latA, double lonB, double latB) {
            return f6(lonA) + "," + f6(latA) + ">" + f6(lonB) + "," + f6(latB);
        }

        /** Parse an OSRM route response (GeoJSON geometry): road path + km, or null. */
        static LegResult parseOsrm(String json) {
            int routes = json.indexOf("\"routes\"");
            if (routes < 0) return null;
            double km = -1;
            java.util.regex.Matcher distance = java.util.regex.Pattern.compile("\"distance\":([\\d.]+)").matcher(json);
            if (distance.find(routes)) {
                try { km = Double.parseDouble(distance.group(1)) / 1000d; } catch (NumberFormatException ignored) { }
            }
            int coords = json.indexOf("\"coordinates\"", routes);
            if (coords < 0 || km < 0) return null;
            int arrStart = json.indexOf('[', coords + 14);
            if (arrStart < 0) return null;
            int depth = 0, end = arrStart;
            for (; end < json.length(); end++) {
                char c = json.charAt(end);
                if (c == '[') depth++;
                else if (c == ']') { depth--; if (depth == 0) break; }
            }
            java.util.regex.Matcher numbers = java.util.regex.Pattern.compile("-?[\\d.]+")
                    .matcher(json.substring(arrStart, Math.min(end + 1, json.length())));
            List<double[]> path = new ArrayList<>();
            List<Double> values = new ArrayList<>();
            while (numbers.find()) {
                try { values.add(Double.parseDouble(numbers.group())); } catch (NumberFormatException ignored) { }
            }
            for (int i = 0; i + 1 < values.size(); i += 2) {
                path.add(new double[] {values.get(i), values.get(i + 1)});
            }
            if (path.size() < 2) return null;
            return new LegResult(List.copyOf(path), km, true);
        }

        private static Stop nearestPlace(double lon, double lat) {
            Stop best = BruneiPlaces.DEPOT;
            double bestKm = Double.MAX_VALUE;
            for (Stop stop : DISTRICTS.values()) {
                double d = BruneiPlaces.haversineKm(lon, lat, stop.x(), stop.y());
                if (d < bestKm) { bestKm = d; best = stop; }
            }
            return best;
        }

        /** Road distance estimate without network: stored pair km or district-snap math. */
        static double snapKm(double lonA, double latA, double lonB, double latB) {
            Stop snapA = nearestPlace(lonA, latA), snapB = nearestPlace(lonB, latB);
            Double midKm = RoadNetwork.KM.get(RoadNetwork.key(snapA.x(), snapA.y(), snapB.x(), snapB.y()));
            if (midKm == null) return BruneiPlaces.haversineKm(lonA, latA, lonB, latB);
            return BruneiPlaces.haversineKm(lonA, latA, snapA.x(), snapA.y())
                    + midKm + BruneiPlaces.haversineKm(snapB.x(), snapB.y(), lonB, latB);
        }

        /** Offline-approximate leg: stub to nearest districts, stored road path between. */
        static LegResult snapLeg(double lonA, double latA, double lonB, double latB) {
            Stop snapA = nearestPlace(lonA, latA), snapB = nearestPlace(lonB, latB);
            String pairKey = RoadNetwork.key(snapA.x(), snapA.y(), snapB.x(), snapB.y());
            List<double[]> mid = RoadNetwork.PATHS.get(pairKey);
            Double midKm = RoadNetwork.KM.get(pairKey);
            if (mid == null || midKm == null || mid.size() < 2) {
                double km = BruneiPlaces.haversineKm(lonA, latA, lonB, latB);
                return new LegResult(List.of(new double[] {lonA, latA}, new double[] {lonB, latB}), km, false);
            }
            List<double[]> path = new ArrayList<>();
            path.add(new double[] {lonA, latA});
            path.addAll(mid);
            path.add(new double[] {lonB, latB});
            double km = BruneiPlaces.haversineKm(lonA, latA, snapA.x(), snapA.y())
                    + midKm + BruneiPlaces.haversineKm(snapB.x(), snapB.y(), lonB, latB);
            return new LegResult(List.copyOf(path), km, false);
        }
    }

    static final class RouteService {
        private final Map<String, Stop> places = BruneiPlaces.places();
        private final Repository repository;
        RouteService(Repository repository) { this.repository = repository; }
        List<String> placeNames() { return places.keySet().stream().sorted().toList(); }
        Stop place(String name) { return places.get(name); }

        RoutePlan optimize(String routeName, List<String> names, User driver) {
            if (names == null) throw new AppException("Add a depot and at least one stop.");
            List<Stop> stops = new ArrayList<>();
            for (String name : names) {
                Stop stop = places.get(name);
                if (stop == null) throw new AppException("Choose a location from the list.");
                stops.add(stop);
            }
            return optimizeStops(routeName, stops, driver);
        }

        /** Preview-only calculation: visiting order + metrics without persisting.
         *  Safe for debounced auto-calc; only Save & assign writes history. */
        RoutePlan computeRoute(String routeName, List<Stop> stops) {
            if (stops == null || stops.size() < 2) throw new AppException("Add a depot and at least one stop.");
            List<Stop> ordered = nearestNeighbor(new ArrayList<>(stops));
            twoOpt(ordered);
            double km = totalKm(ordered);
            int minutes = Math.max(1, (int) Math.ceil(km / BruneiPlaces.AVG_SPEED_KMH * 60));
            return new RoutePlan(id(), routeName == null || routeName.isBlank() ? "Delivery route" : routeName.trim(),
                    null, ordered, round(km), minutes, false, LocalDateTime.now());
}

        void delete(RoutePlan route) {
            if (route == null) return;
            if (repository.shipments().stream().anyMatch(shipment -> route.id().equals(shipment.routeId()))) {
                throw new AppException("This route is assigned to an order. Reassign the order before deleting the route.");
            }
            repository.deleteRoute(route.id());
        }

        /** Optimize a mixed list of district and map-clicked custom stops (persists). */
        RoutePlan optimizeStops(String routeName, List<Stop> stops, User driver) {
            RoutePlan preview = computeRoute(routeName, stops);
            RoutePlan route = new RoutePlan(preview.id(), preview.name(),
                    driver == null ? null : driver.id(), preview.stops(),
                    preview.distanceKm(), preview.estimatedMinutes(), preview.completed(), preview.createdAt());
            repository.saveRoute(route);
            return route;
        }

        /** Cumulative arrival minutes per stop along the given order (road km @ demo speed). */
        List<Double> cumulativeMinutes(List<Stop> ordered) {
            List<Double> result = new ArrayList<>();
            double km = 0;
            result.add(0d);
            for (int i = 1; i < ordered.size(); i++) {
                km += distance(ordered.get(i - 1), ordered.get(i));
                result.add(round(km / BruneiPlaces.AVG_SPEED_KMH * 60));
            }
            return result;
        }

        /** Nearest-neighbor seed starting at the first stop (depot), then 2-opt refined by callers. */
        private List<Stop> nearestNeighbor(List<Stop> stops) {
            List<Stop> remaining = new ArrayList<>(stops);
            List<Stop> ordered = new ArrayList<>();
            Stop current = remaining.removeFirst();
            ordered.add(current);
            while (!remaining.isEmpty()) {
                Stop from = current;
                current = remaining.stream().min(Comparator.comparingDouble(stop -> distance(from, stop))).orElseThrow();
                remaining.remove(current);
                ordered.add(current);
            }
            return ordered;
        }

        /** 2-opt improvement pass over the visiting order (depot stays first). */
        private void twoOpt(List<Stop> ordered) {
            boolean improved = true;
            while (improved) {
                improved = false;
                for (int i = 1; i < ordered.size() - 2; i++) {
                    for (int j = i + 1; j < ordered.size() - 1; j++) {
                        double before = distance(ordered.get(i - 1), ordered.get(i)) + distance(ordered.get(j), ordered.get(j + 1));
                        double after = distance(ordered.get(i - 1), ordered.get(j)) + distance(ordered.get(i), ordered.get(j + 1));
                        if (after < before - 1e-9) {
                            java.util.Collections.reverse(ordered.subList(i, j + 1));
                            improved = true;
                        }
                    }
                }
            }
        }

        private double totalKm(List<Stop> ordered) {
            double km = 0;
            for (int i = 1; i < ordered.size(); i++) km += distance(ordered.get(i - 1), ordered.get(i));
            return km;
        }

        /** Order the given stops fastest-first from a start point, depot-anchored via 2-opt. */
        List<Stop> orderFrom(Stop start, List<Stop> destinations) {
            List<Stop> seed = new ArrayList<>();
            seed.add(start);
            seed.addAll(destinations);
            List<Stop> ordered = nearestNeighbor(seed);
            twoOpt(ordered);
            return ordered;
        }

        double routeKm(List<Stop> ordered) { return totalKm(ordered); }

        int etaMinutes(double km) {
            return Math.max(1, (int) Math.ceil(km / BruneiPlaces.AVG_SPEED_KMH * 60));
        }

        /** Single geocoding path: delegates to {@link AddressGeocoder} so every
         *  caller resolves an address to the same Stop. */
        Stop geocode(String address) {
            try {
                return AddressGeocoder.geocode(address, places).stop();
            } catch (RuntimeException e) {
                return BruneiPlaces.geocodeAddress(address, places);
            }
        }

        Stop depot() { return BruneiPlaces.DEPOT; }

        private double distance(Stop first, Stop second) {
            Double road = RoadNetwork.roadKm(first.x(), first.y(), second.x(), second.y());
            if (road != null) return road;
            Double cached = OsrmRouter.cachedKm(first.x(), first.y(), second.x(), second.y());
            if (cached != null) return cached;
            return OsrmRouter.snapKm(first.x(), first.y(), second.x(), second.y());
        }

        /** Road-following polyline between two stops (OSRM cache, district-snap,
         *  then straight fallback when unknown). */
        static List<double[]> legPath(Stop first, Stop second) {
            OsrmRouter.LegResult cached = OsrmRouter.leg(first.x(), first.y(), second.x(), second.y());
            if (cached != null) return cached.path();
            return OsrmRouter.snapLeg(first.x(), first.y(), second.x(), second.y()).path();
        }
    }

    /** Live GPS state per vehicle: last real fix, no simulation. A vehicle is STALE
     *  when no fix has arrived for {@link #STALE_AFTER_SECONDS}. */
    static final class LiveGpsState {
        static final long STALE_AFTER_SECONDS = 15;
        // Concurrent: the mobile-feed worker writes via note(), the FX tick reads.
        private final Map<String, TrackingPoint> lastByVehicle = new java.util.concurrent.ConcurrentHashMap<>();

        void note(TrackingPoint p) { lastByVehicle.put(p.vehicleId(), p); }
        void reset(String vehicleId) { lastByVehicle.remove(vehicleId); }

        boolean isStale(String vehicleId) { return ageSeconds(vehicleId) > STALE_AFTER_SECONDS; }

        long ageSeconds(String vehicleId) {
            TrackingPoint p = lastByVehicle.get(vehicleId);
            if (p == null) return Long.MAX_VALUE;
            return Math.max(0, java.time.Duration.between(p.recordedAt(), LocalDateTime.now()).getSeconds());
        }
    }

    static final class TrackingService {
        private final Repository repository;
        private final LiveGpsState gps = new LiveGpsState();
        private String feedSource = "nmea";
        TrackingService(Repository repository) { this.repository = repository; }

        /** Ingest one real fix (NMEA / mobile / GPX). Validates, computes remaining
         *  distance along the vehicle's active route, persists, returns the point. */
        TrackingPoint ingest(String vehicleId, String shipmentId, double lon, double lat,
                             double speedKmh, Double headingDeg, Double accuracyM,
                             Domain.FixType fixType, String source, LocalDateTime at) {
            if (vehicleId == null) throw new AppException("Choose a vehicle for this GPS fix.");
            if (!Double.isFinite(lon) || !Double.isFinite(lat)) throw new AppException("Invalid GPS coordinates.");
            if (lon < MapRenderer.MAP_LON_MIN || lon > MapRenderer.MAP_LON_MAX
                    || lat < MapRenderer.MAP_LAT_MIN || lat > MapRenderer.MAP_LAT_MAX) {
                throw new AppException("Fix is outside the Brunei service area — ignored.");
            }
            if (fixType == Domain.FixType.NONE) return null;
            // Deduplicate: ignore fixes older than the latest stored fix.
            TrackingPoint latest = latestForVehicle(vehicleId);
            LocalDateTime ts = at == null ? LocalDateTime.now() : at;
            if (latest != null && !ts.isAfter(latest.recordedAt())) return latest;
            String ship = shipmentId;
            if (ship == null) {
                ship = repository.shipments().stream()
                        .filter(s -> vehicleId.equals(s.vehicleId())
                                && (s.status() == OrderStatus.IN_TRANSIT || s.status() == OrderStatus.ASSIGNED))
                        .map(Domain.Shipment::id).findFirst().orElse(null);
            }
            double remainingKm = remainingAlongRoute(vehicleId, ship, lon, lat);
            double refSpeed = speedKmh > 1 ? speedKmh : BruneiPlaces.AVG_SPEED_KMH;
            int eta = remainingKm <= 0.05 ? 0 : Math.max(1, (int) Math.ceil(remainingKm / refSpeed * 60));
            TrackingPoint point = new TrackingPoint(id(), vehicleId, ship, lon, lat, eta, ts,
                    Math.max(0, speedKmh), headingDeg, accuracyM,
                    fixType == null ? Domain.FixType.GPS : fixType,
                    source == null ? feedSource : source);
            repository.saveTrackingPoint(point);
            gps.note(point);
            return point;
        }

        TrackingPoint ingest(GpsFeed.GpsFix fix, String shipmentId) {
            return ingest(fix.vehicleId(), shipmentId, fix.lon(), fix.lat(), fix.speedKmh(),
                    fix.headingDeg(), fix.accuracyM(), fix.fixType(), fix.source(),
                    GpsFeed.toLocal(fix.time()));
        }

        private double remainingAlongRoute(String vehicleId, String shipmentId, double lon, double lat) {
            Optional<RoutePlan> route = Optional.empty();
            if (shipmentId != null) {
                Optional<Shipment> s = repository.shipment(shipmentId);
                if (s.isPresent() && s.get().routeId() != null) {
                    route = repository.routes().stream().filter(r -> r.id().equals(s.get().routeId())).findFirst();
                }
            }
            if (route.isEmpty()) {
                route = repository.routes().stream()
                        .filter(r -> vehicleId.equals(r.driverId()) || r.name().contains(vehicleId))
                        .max(Comparator.comparing(RoutePlan::createdAt));
            }
            if (route.isEmpty()) {
                route = repository.shipments().stream().filter(s -> vehicleId.equals(s.vehicleId()))
                        .map(Shipment::routeId).filter(java.util.Objects::nonNull)
                        .flatMap(rid -> repository.routes().stream().filter(r -> r.id().equals(rid)))
                        .findFirst();
            }
            if (route.isEmpty() || route.get().stops().size() < 2) {
                // No route: straight-line distance from the fix to the geocoded
                // delivery address, so ETA degrades gracefully instead of reading 0.
                if (shipmentId != null) {
                    Optional<Shipment> s = repository.shipment(shipmentId);
                    if (s.isPresent() && s.get().address() != null && !s.get().address().isBlank()) {
                        try {
                            Stop dest = new RouteService(repository).geocode(s.get().address());
                            return BruneiPlaces.haversineKm(lon, lat, dest.x(), dest.y());
                        } catch (RuntimeException ignored) { }
                    }
                }
                return 0;
            }
            List<Stop> stops = route.get().stops();
            // Find nearest leg index, sum remaining legs + partial.
            double best = Double.MAX_VALUE;
            int bestLeg = 0;
            double bestAlong = 0;
            for (int i = 1; i < stops.size(); i++) {
                Stop a = stops.get(i - 1), b = stops.get(i);
                List<double[]> path = RouteService.legPath(a, b);
                for (int j = 1; j < path.size(); j++) {
                    double[] p = path.get(j - 1), q = path.get(j);
                    double[] proj = projectOnto(p, q, lon, lat);
                    double d = BruneiPlaces.haversineKm(proj[0], proj[1], lon, lat);
                    if (d < best) { best = d; bestLeg = i; bestAlong = proj[2]; }
                }
            }
            double remaining = 0;
            for (int i = bestLeg + 1; i < stops.size(); i++) {
                remaining += legKm(stops.get(i - 1), stops.get(i));
            }
            // Partial remainder of current leg.
            Stop a = stops.get(bestLeg - 1), b = stops.get(bestLeg);
            double legTotal = legKm(a, b);
            remaining += legTotal * (1 - Math.min(1, Math.max(0, bestAlong)));
            return remaining;
        }

        private double legKm(Stop a, Stop b) {
            Double road = RoadNetwork.roadKm(a.x(), a.y(), b.x(), b.y());
            if (road != null) return road;
            Double cached = OsrmRouter.cachedKm(a.x(), a.y(), b.x(), b.y());
            if (cached != null) return cached;
            return BruneiPlaces.haversineKm(a, b);
        }

        private double[] projectOnto(double[] p, double[] q, double lon, double lat) {
            double dx = q[0] - p[0], dy = q[1] - p[1];
            double len2 = dx * dx + dy * dy;
            double t = len2 < 1e-12 ? 0 : ((lon - p[0]) * dx + (lat - p[1]) * dy) / len2;
            t = Math.min(1, Math.max(0, t));
            return new double[] {p[0] + t * dx, p[1] + t * dy, t};
        }

        /** Distance of a fix from its active route (km) — for off-route alerts. */
        public double deviationKm(String vehicleId, double lon, double lat) {
            TrackingPoint latest = latestForVehicle(vehicleId);
            String ship = latest == null ? null : latest.shipmentId();
            Optional<RoutePlan> route = repository.shipments().stream()
                    .filter(s -> vehicleId.equals(s.vehicleId())).map(Shipment::routeId)
                    .filter(java.util.Objects::nonNull)
                    .flatMap(rid -> repository.routes().stream().filter(r -> r.id().equals(rid))).findFirst();
            if (route.isEmpty()) return 0;
            double best = Double.MAX_VALUE;
            for (int i = 1; i < route.get().stops().size(); i++) {
                for (double[] pt : RouteService.legPath(route.get().stops().get(i - 1), route.get().stops().get(i))) {
                    best = Math.min(best, BruneiPlaces.haversineKm(pt[0], pt[1], lon, lat));
                }
            }
            return best == Double.MAX_VALUE ? 0 : best;
        }

        /** True when the vehicle is within arrivalRadiusKm of the final stop. */
        public boolean arrivedAtDestination(String vehicleId, double arrivalRadiusKm) {
            TrackingPoint latest = latestForVehicle(vehicleId);
            if (latest == null) return false;
            Optional<RoutePlan> route = repository.shipments().stream()
                    .filter(s -> vehicleId.equals(s.vehicleId())).map(Shipment::routeId)
                    .filter(java.util.Objects::nonNull)
                    .flatMap(rid -> repository.routes().stream().filter(r -> r.id().equals(rid))).findFirst();
            if (route.isEmpty() || route.get().stops().isEmpty()) return false;
            Stop dest = route.get().stops().get(route.get().stops().size() - 1);
            return BruneiPlaces.haversineKm(dest.x(), dest.y(), latest.x(), latest.y()) <= arrivalRadiusKm;
        }

        TrackingPoint latestFor(String shipmentId) {
            if (shipmentId == null) return null;
            return repository.trackingHistory().stream().filter(point -> shipmentId.equals(point.shipmentId()))
                    .max(Comparator.comparing(TrackingPoint::recordedAt)).orElse(null);
        }
        TrackingPoint latestForVehicle(String vehicleId) {
            if (vehicleId == null) return null;
            TrackingPoint mem = gps.lastByVehicle.get(vehicleId);
            TrackingPoint stored = repository.trackingHistory().stream().filter(point -> vehicleId.equals(point.vehicleId()))
                    .max(Comparator.comparing(TrackingPoint::recordedAt)).orElse(null);
            if (mem == null) return stored;
            if (stored == null) return mem;
            return stored.recordedAt().isAfter(mem.recordedAt()) ? stored : mem;
        }
        String gpsStatus(String vehicleId) {
            TrackingPoint p = latestForVehicle(vehicleId);
            if (p == null) return "NO FIX";
            long age = Math.max(0, java.time.Duration.between(p.recordedAt(), LocalDateTime.now()).getSeconds());
            if (age > LiveGpsState.STALE_AFTER_SECONDS) return "LOST · " + age + "s ago";
            String acc = p.accuracyM() == null ? "" : "·±" + Math.round(p.accuracyM()) + "m";
            String spd = p.speedKmh() > 0.5 ? "·" + Math.round(p.speedKmh()) + "km/h" : "";
            return (p.fixType() == null ? "GPS" : p.fixType().label()) + acc + spd;
        }
        LiveGpsState gps() { return gps; }
        String feedSource() { return feedSource; }
        void feedSource(String s) { feedSource = s; }

        /** One optimized multi-stop tour for a vehicle: live fix → remaining destinations. */
        public record VehicleTour(List<Stop> ordered, int progressIndex, double remainingKm) { }
        
        private final Map<String, CachedTour> tourCache = new java.util.concurrent.ConcurrentHashMap<>();
        private record CachedTour(VehicleTour tour, String signature) { }

        public VehicleTour tourForVehicle(String vehicleId) {
            List<Shipment> group = repository.shipments().stream()
                    .filter(s -> vehicleId.equals(s.vehicleId()))
                    .filter(s -> s.status() == OrderStatus.IN_TRANSIT || s.status() == OrderStatus.ASSIGNED)
                    .toList();
            if (group.isEmpty()) { tourCache.remove(vehicleId); return new VehicleTour(List.of(), 0, 0); }
            TrackingPoint live = latestForVehicle(vehicleId);
            // Reuse the cached tour unless the order set or the live fix changed —
            // geocoding every address on every 1-second tick is what froze the UI.
            String sig = tourSignature(group, live);
            CachedTour cached = tourCache.get(vehicleId);
            if (cached != null && cached.signature().equals(sig)) return cached.tour();
            VehicleTour tour = buildTour(vehicleId, group, live);
            tourCache.put(vehicleId, new CachedTour(tour, sig));
            return tour;
        }

        private String tourSignature(List<Shipment> group, TrackingPoint live) {
            // Ordering also depends on priority and route links — include them so edits
            // invalidate the cache instead of reusing a stale visiting order.
            StringBuilder sb = new StringBuilder();
            group.stream().map(s -> s.id() + ":" + s.status() + ":" + s.address() + ":"
                            + s.priority() + ":" + s.routeId()).sorted()
                    .forEach(sb::append);
            sb.append('|').append(live == null ? "nofix" : live.recordedAt().toString());
            return sb.toString();
        }

        private VehicleTour buildTour(String vehicleId, List<Shipment> group, TrackingPoint live) {
            RouteService rs = new RouteService(repository);
            Stop start = live == null ? rs.depot()
                    : new Stop("live-" + vehicleId, "Live position", live.x(), live.y());
            List<Shipment> prioritized = new ArrayList<>(group);
            prioritized.sort(Comparator.comparingInt(s -> switch (s.priority()) {
                case URGENT -> 0; case HIGH -> 1; case NORMAL -> 2; case LOW -> 3; }));
            List<Stop> dests = new ArrayList<>();
            for (Shipment s : prioritized) {
                Stop geo = rs.geocode(s.address());
                dests.add(new Stop("stop-" + s.id(), geo.name() + " · " + s.id(), geo.x(), geo.y()));
            }
            // Fastest order without network in the render path (cached road km only).
            // Full OSRM table ordering happens in Route Planning / AutoRouteService.
            List<Stop> ordered;
            try {
                ordered = rs.orderFrom(start, dests);
            } catch (Exception e) {
                ordered = new ArrayList<>();
                ordered.add(start);
                ordered.addAll(dests);
            }
            // Progress: nearest ordered stop to live fix.
            int progress = 0;
            double remaining = 0;
            if (live != null && ordered.size() >= 2) {
                double best = Double.MAX_VALUE;
                for (int i = 1; i < ordered.size(); i++) {
                    double d = BruneiPlaces.haversineKm(ordered.get(i).x(), ordered.get(i).y(), live.x(), live.y());
                    if (d < best) { best = d; progress = Math.max(0, i - 1); }
                }
                for (int i = progress + 1; i < ordered.size(); i++) {
                    Stop a = ordered.get(i - 1), b = ordered.get(i);
                    Double road = RoadNetwork.roadKm(a.x(), a.y(), b.x(), b.y());
                    remaining += road != null ? road : BruneiPlaces.haversineKm(a, b);
                }
            } else {
                for (int i = 1; i < ordered.size(); i++) {
                    remaining += BruneiPlaces.haversineKm(ordered.get(i - 1), ordered.get(i));
                }
            }
            return new VehicleTour(List.copyOf(ordered), progress, remaining);
        }
    }

    /** Builds one fastest route per vehicle across all of its ready orders.
     *  Fired when an order goes In-Transit; urgent orders are biased earlier. */
    static final class AutoRouteService {
        private final Repository repository;
        private final RouteService routes;
        private final TrackingService tracking;
        AutoRouteService(Repository repository, RouteService routes, TrackingService tracking) {
            this.repository = repository; this.routes = routes; this.tracking = tracking;
        }

        RoutePlan buildForVehicle(String vehicleId) {
            Vehicle vehicle = repository.vehicle(vehicleId)
                    .orElseThrow(() -> new AppException("Vehicle is no longer available."));
            List<Shipment> group = repository.shipments().stream()
                    .filter(s -> vehicleId.equals(s.vehicleId()))
                    .filter(s -> s.status() == OrderStatus.ASSIGNED || s.status() == OrderStatus.IN_TRANSIT)
                    .toList();
            if (group.isEmpty()) throw new AppException("No assigned orders found for this vehicle.");
            double totalWeight = group.stream().mapToDouble(Shipment::weightKg).sum();
            if (totalWeight > vehicle.capacityKg()) {
                throw new AppException("Combined order weight (" + totalWeight + " kg) exceeds "
                        + vehicle.plate() + " capacity (" + vehicle.capacityKg() + " kg).");
            }
            TrackingPoint live = tracking.latestForVehicle(vehicleId);
            Stop start = live == null ? routes.depot()
                    : new Stop("live-" + vehicleId, "Live position", live.x(), live.y());
            List<Shipment> prioritized = new ArrayList<>(group);
            prioritized.sort(Comparator.comparingInt(s -> priorityRank(s.priority())));
            List<Stop> destinations = new ArrayList<>();
            Map<String, String> stopToShipment = new LinkedHashMap<>();
            for (Shipment s : prioritized) {
                Stop geo = routes.geocode(s.address());
                Stop stop = new Stop("stop-" + s.id(), geo.name() + " · " + s.id(), geo.x(), geo.y());
                destinations.add(stop);
                stopToShipment.put(stop.id(), s.id());
            }
            List<Stop> ordered = routes.orderFrom(start, destinations);
            double km = routes.routeKm(ordered);
            RoutePlan plan = new RoutePlan(id(), "Auto · " + vehicle.plate() + " · " + group.size() + " stops",
                    vehicle.driverId(), ordered, round(km), routes.etaMinutes(km), false, LocalDateTime.now());
            repository.saveRoute(plan);
            for (Shipment s : group) {
                s.setAssignments(s.driverId(), s.vehicleId(), plan.id());
                repository.saveShipment(s);
            }
            tracking.gps().reset(vehicleId);
            return plan;
        }

        private int priorityRank(Priority priority) {
            if (priority == null) return 2;
            return switch (priority) {
                case URGENT -> 0;
                case HIGH -> 1;
                case NORMAL -> 2;
                case LOW -> 3;
            };
        }
    }

    static String id() { return UUID.randomUUID().toString(); }
    private static double round(double value) { return Math.round(value * 10d) / 10d; }
}
