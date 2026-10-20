package edu.pb.dcls;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static edu.pb.dcls.Domain.*;

/**
 * Keeps the last committed data snapshot available to JavaFX renderers. The delegate is used
 * for every write and is only accessed from the app's I/O executor.
 */
final class CachedRepository implements Repository {
    private final Repository delegate;
    private volatile Snapshot snapshot = Snapshot.empty();

    CachedRepository(Repository delegate) { this.delegate = delegate; }

    synchronized void refresh() {
        snapshot = new Snapshot(delegate.users(), delegate.vehicles(), delegate.shipments(), delegate.routes(),
                delegate.maintenanceRecords(), delegate.fuelRecords(), delegate.trackingHistory());
    }

    private record Snapshot(List<User> users, List<Vehicle> vehicles, List<Shipment> shipments,
                            List<RoutePlan> routes, List<MaintenanceRecord> maintenance,
                            List<FuelRecord> fuel, List<TrackingPoint> tracking) {
        private Snapshot {
            users = List.copyOf(users); vehicles = List.copyOf(vehicles); shipments = List.copyOf(shipments);
            routes = List.copyOf(routes); maintenance = List.copyOf(maintenance); fuel = List.copyOf(fuel);
            tracking = List.copyOf(tracking);
        }
        static Snapshot empty() { return new Snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()); }
    }

    private synchronized <T> void replace(Function<Snapshot, List<T>> select, Function<List<T>, Snapshot> update, T value, Function<T, String> id) {
        List<T> values = new ArrayList<>(select.apply(snapshot));
        values.removeIf(item -> id.apply(item).equals(id.apply(value)));
        values.add(value);
        snapshot = update.apply(values);
    }

    @Override public List<User> users() { return new ArrayList<>(snapshot.users()); }
    @Override public Optional<User> userByEmail(String email) {
        return snapshot.users().stream().filter(user -> user.email().equalsIgnoreCase(email)).findFirst();
    }
    @Override public void saveUser(User user) {
        delegate.saveUser(user);
        replace(Snapshot::users, values -> new Snapshot(values, snapshot.vehicles(), snapshot.shipments(), snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()), user, User::id);
    }
    @Override public void deleteUser(String id) { delegate.deleteUser(id); synchronized (this) { snapshot = new Snapshot(snapshot.users().stream().filter(user -> !user.id().equals(id)).toList(), snapshot.vehicles(), snapshot.shipments(), snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()); } }

    @Override public List<Vehicle> vehicles() { return new ArrayList<>(snapshot.vehicles()); }
    @Override public Optional<Vehicle> vehicle(String id) { return snapshot.vehicles().stream().filter(vehicle -> vehicle.id().equals(id)).findFirst(); }
    @Override public void saveVehicle(Vehicle vehicle) {
        delegate.saveVehicle(vehicle);
        replace(Snapshot::vehicles, values -> new Snapshot(snapshot.users(), values, snapshot.shipments(), snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()), vehicle, Vehicle::id);
    }
    @Override public void deleteVehicle(String id) { delegate.deleteVehicle(id); synchronized (this) { snapshot = new Snapshot(snapshot.users(), snapshot.vehicles().stream().filter(vehicle -> !vehicle.id().equals(id)).toList(), snapshot.shipments(), snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()); } }

    @Override public List<Shipment> shipments() { return new ArrayList<>(snapshot.shipments()); }
    @Override public Optional<Shipment> shipment(String id) { return snapshot.shipments().stream().filter(shipment -> shipment.id().equals(id)).findFirst(); }
    @Override public void saveShipment(Shipment shipment) {
        delegate.saveShipment(shipment);
        replace(Snapshot::shipments, values -> new Snapshot(snapshot.users(), snapshot.vehicles(), values, snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()), shipment, Shipment::id);
    }
    @Override public void saveAssignment(Shipment shipment, Vehicle vehicle) {
        delegate.saveAssignment(shipment, vehicle);
        replace(Snapshot::shipments, values -> new Snapshot(snapshot.users(), snapshot.vehicles(), values, snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()), shipment, Shipment::id);
        replace(Snapshot::vehicles, values -> new Snapshot(snapshot.users(), values, snapshot.shipments(), snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()), vehicle, Vehicle::id);
    }
    @Override public void saveShipmentAndVehicle(Shipment shipment, Vehicle vehicle) {
        delegate.saveShipmentAndVehicle(shipment, vehicle);
        replace(Snapshot::shipments, values -> new Snapshot(snapshot.users(), snapshot.vehicles(), values, snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()), shipment, Shipment::id);
        replace(Snapshot::vehicles, values -> new Snapshot(snapshot.users(), values, snapshot.shipments(), snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()), vehicle, Vehicle::id);
    }
    @Override public void deleteShipment(String id) { delegate.deleteShipment(id); synchronized (this) { snapshot = new Snapshot(snapshot.users(), snapshot.vehicles(), snapshot.shipments().stream().filter(shipment -> !shipment.id().equals(id)).toList(), snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking().stream().filter(point -> !id.equals(point.shipmentId())).toList()); } }

    @Override public List<RoutePlan> routes() { return new ArrayList<>(snapshot.routes()); }
    @Override public void saveRoute(RoutePlan route) {
        delegate.saveRoute(route);
        replace(Snapshot::routes, values -> new Snapshot(snapshot.users(), snapshot.vehicles(), snapshot.shipments(), values, snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()), route, RoutePlan::id);
    }
    @Override public void deleteRoute(String id) { delegate.deleteRoute(id); synchronized (this) { snapshot = new Snapshot(snapshot.users(), snapshot.vehicles(), snapshot.shipments(), snapshot.routes().stream().filter(route -> !route.id().equals(id)).toList(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()); } }

    @Override public List<MaintenanceRecord> maintenanceRecords() { return new ArrayList<>(snapshot.maintenance()); }
    @Override public void saveMaintenance(MaintenanceRecord record) {
        delegate.saveMaintenance(record);
        replace(Snapshot::maintenance, values -> new Snapshot(snapshot.users(), snapshot.vehicles(), snapshot.shipments(), snapshot.routes(), values, snapshot.fuel(), snapshot.tracking()), record, MaintenanceRecord::id);
    }
    @Override public void saveMaintenanceAndVehicle(MaintenanceRecord record, Vehicle vehicle) {
        delegate.saveMaintenanceAndVehicle(record, vehicle);
        replace(Snapshot::maintenance, values -> new Snapshot(snapshot.users(), snapshot.vehicles(), snapshot.shipments(), snapshot.routes(), values, snapshot.fuel(), snapshot.tracking()), record, MaintenanceRecord::id);
        replace(Snapshot::vehicles, values -> new Snapshot(snapshot.users(), values, snapshot.shipments(), snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()), vehicle, Vehicle::id);
    }
    @Override public void deleteMaintenance(String id) { delegate.deleteMaintenance(id); synchronized (this) { snapshot = new Snapshot(snapshot.users(), snapshot.vehicles(), snapshot.shipments(), snapshot.routes(), snapshot.maintenance().stream().filter(record -> !record.id().equals(id)).toList(), snapshot.fuel(), snapshot.tracking()); } }

    @Override public List<FuelRecord> fuelRecords() { return new ArrayList<>(snapshot.fuel()); }
    @Override public void saveFuel(FuelRecord record) {
        delegate.saveFuel(record);
        replace(Snapshot::fuel, values -> new Snapshot(snapshot.users(), snapshot.vehicles(), snapshot.shipments(), snapshot.routes(), snapshot.maintenance(), values, snapshot.tracking()), record, FuelRecord::id);
    }
    @Override public void saveFuelAndVehicle(FuelRecord record, Vehicle vehicle) {
        delegate.saveFuelAndVehicle(record, vehicle);
        replace(Snapshot::fuel, values -> new Snapshot(snapshot.users(), snapshot.vehicles(), snapshot.shipments(), snapshot.routes(), snapshot.maintenance(), values, snapshot.tracking()), record, FuelRecord::id);
        replace(Snapshot::vehicles, values -> new Snapshot(snapshot.users(), values, snapshot.shipments(), snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), snapshot.tracking()), vehicle, Vehicle::id);
    }
    @Override public void deleteFuel(String id) { delegate.deleteFuel(id); synchronized (this) { snapshot = new Snapshot(snapshot.users(), snapshot.vehicles(), snapshot.shipments(), snapshot.routes(), snapshot.maintenance(), snapshot.fuel().stream().filter(record -> !record.id().equals(id)).toList(), snapshot.tracking()); } }

    @Override public void deleteTrackingForVehicle(String vehicleId) {
        delegate.deleteTrackingForVehicle(vehicleId);
        synchronized (this) {
            snapshot = new Snapshot(snapshot.users(), snapshot.vehicles(), snapshot.shipments(), snapshot.routes(),
                    snapshot.maintenance(), snapshot.fuel(),
                    snapshot.tracking().stream().filter(p -> !vehicleId.equals(p.vehicleId())).toList());
        }
    }

    @Override public List<TrackingPoint> trackingHistory() { return new ArrayList<>(snapshot.tracking()); }
    @Override public void saveTrackingPoint(TrackingPoint point) {
        delegate.saveTrackingPoint(point);
        synchronized (this) {
            // Same 2000-fix cap as JdbcRepository/MockRepository; readers sort by
            // recordedAt explicitly, so insertion order is kept here.
            List<TrackingPoint> points = new ArrayList<>(snapshot.tracking());
            points.removeIf(p -> p.id() != null && p.id().equals(point.id()));
            points.add(point);
            while (points.size() > 2000) points.remove(0);
            snapshot = new Snapshot(snapshot.users(), snapshot.vehicles(), snapshot.shipments(), snapshot.routes(), snapshot.maintenance(), snapshot.fuel(), points);
        }
    }
    @Override public boolean persistent() { return delegate.persistent(); }
    @Override public void close() { delegate.close(); }
}
