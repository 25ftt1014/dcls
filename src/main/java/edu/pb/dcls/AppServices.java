package edu.pb.dcls;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /** Shared Brunei Darussalam coordinate base (WGS84 degrees, sensible Bandar defaults).
     *  Stop.x() carries longitude, Stop.y() carries latitude so existing storage keeps working. */
    static final class BruneiPlaces {
        /** Average simulated driving speed. Kept at 35 km/h per project decision. */
        static final double AVG_SPEED_KMH = 35d;
        /** How many simulated minutes pass per one-second live tick (demo time compression). */
        static final double SIM_MINUTES_PER_TICK = 1d;
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

    static final class RouteService {
        private final Map<String, Stop> places = BruneiPlaces.places();
        private final Repository repository;
        RouteService(Repository repository) { this.repository = repository; }
        List<String> placeNames() { return places.keySet().stream().sorted().toList(); }

        RoutePlan optimize(String routeName, List<String> names, User driver) {
            if (names == null || names.size() < 2) throw new AppException("Add a depot and at least one stop.");
            List<Stop> stops = new ArrayList<>();
            for (String name : names) {
                Stop stop = places.get(name);
                if (stop == null) throw new AppException("Choose a location from the list.");
                stops.add(stop);
            }
            List<Stop> ordered = nearestNeighbor(stops);
            twoOpt(ordered);
            double km = totalKm(ordered);
            int minutes = Math.max(1, (int) Math.ceil(km / BruneiPlaces.AVG_SPEED_KMH * 60));
            RoutePlan route = new RoutePlan(id(), routeName == null || routeName.isBlank() ? "Delivery route" : routeName.trim(),
                    driver == null ? null : driver.id(), ordered, round(km), minutes, false, LocalDateTime.now());
            repository.saveRoute(route);
            return route;
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

        Stop geocode(String address) { return BruneiPlaces.geocodeAddress(address, places); }

        Stop depot() { return BruneiPlaces.DEPOT; }

        private double distance(Stop first, Stop second) {
            return BruneiPlaces.haversineKm(first, second);
        }
    }

    /** Simulated driver's-phone GPS feed. Each vehicle carries a virtual tracker that
     *  advances along its active route at {@link BruneiPlaces#AVG_SPEED_KMH}, compressed so
     *  one live tick equals {@link BruneiPlaces#SIM_MINUTES_PER_TICK} of driving. */
    static final class PhoneGpsProvider {
        private final Map<String, GpsState> states = new HashMap<>();
        private final Map<String, Boolean> fixLost = new HashMap<>();

        record Fix(double lon, double lat, double remainingKm, boolean hasFix) { }

        private static final class GpsState {
            String routeId;
            int legIndex;
            double legTravelledKm;
        }

        Fix poll(String vehicleId, RoutePlan route, TrackingPoint lastKnown) {
            if (Boolean.TRUE.equals(fixLost.get(vehicleId)) && lastKnown != null) {
                return new Fix(lastKnown.x(), lastKnown.y(), routeDistanceLeft(route, lastKnown), false);
            }
            if (route == null || route.stops().size() < 2) return null;
            GpsState state = states.computeIfAbsent(vehicleId, key -> seed(route, lastKnown));
            if (!state.routeId.equals(route.id())) states.put(vehicleId, state = seed(route, lastKnown));
            double stepKm = BruneiPlaces.AVG_SPEED_KMH / 60d * BruneiPlaces.SIM_MINUTES_PER_TICK;
            List<Stop> stops = route.stops();
            while (stepKm > 0 && state.legIndex < stops.size() - 1) {
                double legKm = BruneiPlaces.haversineKm(stops.get(state.legIndex), stops.get(state.legIndex + 1));
                double left = Math.max(0, legKm - state.legTravelledKm);
                if (stepKm < left) { state.legTravelledKm += stepKm; stepKm = 0; }
                else { stepKm -= left; state.legIndex++; state.legTravelledKm = 0; }
            }
            if (state.legIndex >= stops.size() - 1) {
                Stop end = stops.get(stops.size() - 1);
                return new Fix(end.x(), end.y(), 0, true);
            }
            Stop a = stops.get(state.legIndex), b = stops.get(state.legIndex + 1);
            double legKm = Math.max(1e-9, BruneiPlaces.haversineKm(a, b));
            double local = Math.min(1, state.legTravelledKm / legKm);
            double lon = a.x() + (b.x() - a.x()) * local;
            double lat = a.y() + (b.y() - a.y()) * local;
            double remaining = (1 - local) * legKm;
            for (int i = state.legIndex + 1; i < stops.size() - 1; i++) {
                remaining += BruneiPlaces.haversineKm(stops.get(i), stops.get(i + 1));
            }
            return new Fix(lon, lat, remaining, true);
        }

        private GpsState seed(RoutePlan route, TrackingPoint lastKnown) {
            GpsState state = new GpsState();
            state.routeId = route.id();
            state.legIndex = 0;
            state.legTravelledKm = 0;
            if (lastKnown != null) {
                double best = Double.MAX_VALUE;
                for (int i = 0; i < route.stops().size() - 1; i++) {
                    double mid = BruneiPlaces.haversineKm(
                            route.stops().get(i).x(), route.stops().get(i).y(), lastKnown.x(), lastKnown.y());
                    if (mid < best) { best = mid; state.legIndex = i; }
                }
            }
            return state;
        }

        private double routeDistanceLeft(RoutePlan route, TrackingPoint point) {
            if (route == null) return 0;
            double remaining = 0;
            List<Stop> stops = route.stops();
            int nearest = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < stops.size(); i++) {
                double d = BruneiPlaces.haversineKm(stops.get(i).x(), stops.get(i).y(), point.x(), point.y());
                if (d < best) { best = d; nearest = i; }
            }
            for (int i = Math.max(0, nearest); i < stops.size() - 1; i++) {
                remaining += BruneiPlaces.haversineKm(stops.get(i), stops.get(i + 1));
            }
            return remaining;
        }

        void reset(String vehicleId) { states.remove(vehicleId); }
        void setFixLost(String vehicleId, boolean lost) { fixLost.put(vehicleId, lost); }
        boolean isFixLost(String vehicleId) { return Boolean.TRUE.equals(fixLost.get(vehicleId)); }
    }

    static final class TrackingService {
        private final Repository repository;
        private final PhoneGpsProvider gps = new PhoneGpsProvider();
        TrackingService(Repository repository) { this.repository = repository; }
        TrackingPoint advance(Shipment shipment) {
            if (shipment.vehicleId() == null) return null;
            Optional<RoutePlan> route = repository.routes().stream()
                    .filter(item -> item.id().equals(shipment.routeId())).findFirst();
            if (route.isEmpty() || route.get().stops().size() < 2) return null;
            TrackingPoint last = latestForVehicle(shipment.vehicleId());
            PhoneGpsProvider.Fix fix = gps.poll(shipment.vehicleId(), route.get(), last);
            if (fix == null) return null;
            int eta = Math.max(1, (int) Math.ceil(fix.remainingKm() / BruneiPlaces.AVG_SPEED_KMH * 60));
            if (fix.remainingKm() <= 1e-9) eta = 0;
            TrackingPoint point = new TrackingPoint(id(), shipment.vehicleId(), shipment.id(),
                    fix.lon(), fix.lat(), eta, LocalDateTime.now());
            repository.saveTrackingPoint(point);
            return point;
        }
        TrackingPoint latestFor(String shipmentId) {
            return repository.trackingHistory().stream().filter(point -> shipmentId.equals(point.shipmentId()))
                    .max(Comparator.comparing(TrackingPoint::recordedAt)).orElse(null);
        }
        TrackingPoint latestForVehicle(String vehicleId) {
            return repository.trackingHistory().stream().filter(point -> vehicleId.equals(point.vehicleId()))
                    .max(Comparator.comparing(TrackingPoint::recordedAt)).orElse(null);
        }
        String gpsStatus(String vehicleId) {
            return gps.isFixLost(vehicleId) ? "SIM FIX LOST" : "SIM FIX · phone";
        }
        PhoneGpsProvider gps() { return gps; }
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
