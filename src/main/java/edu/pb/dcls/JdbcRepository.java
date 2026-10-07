package edu.pb.dcls;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static edu.pb.dcls.Domain.*;

/** JDBC-backed repository. Configure it with DCLS_DB_URL, DCLS_DB_USER and DCLS_DB_PASSWORD. */
public final class JdbcRepository implements Repository {
    private final String url;
    private final String username;
    private final String password;

    public JdbcRepository(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
        read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1")) { statement.executeQuery(); }
            return null;
        });
        if (users().isEmpty()) seedDemoData();
    }

    private void seedDemoData() {
        MockRepository demo = new MockRepository();
        demo.users().forEach(this::saveUser);
        demo.vehicles().forEach(this::saveVehicle);
        demo.routes().forEach(this::saveRoute);
        demo.shipments().forEach(this::saveShipment);
        demo.maintenanceRecords().forEach(this::saveMaintenance);
        demo.fuelRecords().forEach(this::saveFuel);
        demo.trackingHistory().forEach(this::saveTrackingPoint);
    }

    private Connection connection() throws SQLException { return DriverManager.getConnection(url, username, password); }

    private <T> T read(SqlWork<T> work) {
        try (Connection connection = connection()) { return work.run(connection); }
        catch (SQLException exception) { throw friendly(exception); }
    }

    private void write(SqlWork<Void> work) {
        read(connection -> { work.run(connection); return null; });
    }

    private AppServices.AppException friendly(SQLException exception) {
        return new AppServices.AppException("MySQL request failed. Check the database connection and configuration, then try again.", exception);
    }

    @Override public List<User> users() {
        return read(connection -> {
            List<User> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM users ORDER BY full_name"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new User(rows.getString("id"), rows.getString("full_name"), rows.getString("email"),
                        rows.getString("phone"), rows.getString("password_hash"), rows.getString("password_salt"),
                        Role.valueOf(rows.getString("role_code")), rows.getBoolean("active")));
            }
            return result;
        });
    }

    @Override public Optional<User> userByEmail(String email) {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM users WHERE LOWER(email)=LOWER(?) LIMIT 1")) {
                statement.setString(1, email);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    return Optional.of(new User(rows.getString("id"), rows.getString("full_name"), rows.getString("email"),
                            rows.getString("phone"), rows.getString("password_hash"), rows.getString("password_salt"),
                            Role.valueOf(rows.getString("role_code")), rows.getBoolean("active")));
                }
            }
        });
    }

    @Override public void saveUser(User user) {
        write(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO users(id, full_name, email, phone, password_hash, password_salt, role_code, active)
                    VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE full_name=VALUES(full_name), email=VALUES(email),
                    phone=VALUES(phone), password_hash=VALUES(password_hash), password_salt=VALUES(password_salt),
                    role_code=VALUES(role_code), active=VALUES(active)
                    """)) {
                statement.setString(1, user.id()); statement.setString(2, user.name()); statement.setString(3, user.email());
                statement.setString(4, user.phone()); statement.setString(5, user.passwordHash()); statement.setString(6, user.passwordSalt());
                statement.setString(7, user.role().name()); statement.setBoolean(8, user.active()); statement.executeUpdate();
            }
            return null;
        });
    }

    @Override public void deleteUser(String id) { write(connection -> { execute(connection, "DELETE FROM users WHERE id=?", id); return null; }); }

    @Override public List<Vehicle> vehicles() {
        return read(connection -> {
            List<Vehicle> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM vehicles ORDER BY plate_number"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(vehicleFrom(rows));
            }
            return result;
        });
    }

    @Override public Optional<Vehicle> vehicle(String id) {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM vehicles WHERE id=?")) {
                statement.setString(1, id);
                try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(vehicleFrom(rows)) : Optional.empty(); }
            }
        });
    }

    private Vehicle vehicleFrom(ResultSet rows) throws SQLException {
        return new Vehicle(rows.getString("id"), rows.getString("plate_number"), rows.getString("model"), rows.getString("vehicle_type"),
                rows.getDouble("capacity_kg"), rows.getDouble("mileage_km"), VehicleStatus.valueOf(rows.getString("status")),
                rows.getString("driver_id"), rows.getDouble("next_service_km"));
    }

    @Override public void saveVehicle(Vehicle vehicle) { write(connection -> { saveVehicle(connection, vehicle); return null; }); }
    private void saveVehicle(Connection connection, Vehicle vehicle) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO vehicles(id, plate_number, model, vehicle_type, capacity_kg, mileage_km, status, driver_id, next_service_km)
                VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE plate_number=VALUES(plate_number), model=VALUES(model),
                vehicle_type=VALUES(vehicle_type), capacity_kg=VALUES(capacity_kg), mileage_km=VALUES(mileage_km),
                status=VALUES(status), driver_id=VALUES(driver_id), next_service_km=VALUES(next_service_km)
                """)) {
            statement.setString(1, vehicle.id()); statement.setString(2, vehicle.plate()); statement.setString(3, vehicle.model());
            statement.setString(4, vehicle.type()); statement.setDouble(5, vehicle.capacityKg()); statement.setDouble(6, vehicle.mileageKm());
            statement.setString(7, vehicle.status().name()); statement.setString(8, vehicle.driverId()); statement.setDouble(9, vehicle.nextServiceKm());
            statement.executeUpdate();
        }
    }
    @Override public void deleteVehicle(String id) { write(connection -> { execute(connection, "DELETE FROM vehicles WHERE id=?", id); return null; }); }

    @Override public List<Shipment> shipments() {
        return read(connection -> {
            List<Shipment> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM shipments ORDER BY created_at DESC"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(shipmentFrom(rows));
            }
            return result;
        });
    }
    @Override public Optional<Shipment> shipment(String id) {
        return read(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM shipments WHERE id=?")) {
                statement.setString(1, id);
                try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(shipmentFrom(rows)) : Optional.empty(); }
            }
        });
    }
    private Shipment shipmentFrom(ResultSet rows) throws SQLException {
        Timestamp created = rows.getTimestamp("created_at"), delivered = rows.getTimestamp("delivered_at");
        Shipment shipment = new Shipment(rows.getString("id"), rows.getString("customer_name"), rows.getString("customer_contact"),
                rows.getString("delivery_address"), rows.getString("package_description"), rows.getInt("quantity"),
                rows.getDouble("weight_kg"), Priority.valueOf(rows.getString("priority")), OrderStatus.valueOf(rows.getString("status")),
                rows.getString("driver_id"), rows.getString("vehicle_id"), rows.getString("route_id"),
                created == null ? LocalDateTime.now() : created.toLocalDateTime(), delivered == null ? null : delivered.toLocalDateTime());
        shipment.setDeliveryMetrics(rows.getInt("delivery_minutes"), rows.getInt("customer_satisfaction"));
        return shipment;
    }
    @Override public void saveShipment(Shipment shipment) { write(connection -> { saveShipment(connection, shipment); return null; }); }
    private void saveShipment(Connection connection, Shipment shipment) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO shipments(id, customer_name, customer_contact, delivery_address, package_description, quantity, weight_kg,
                priority, status, driver_id, vehicle_id, route_id, created_at, delivered_at, delivery_minutes, customer_satisfaction)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE customer_name=VALUES(customer_name),
                customer_contact=VALUES(customer_contact), delivery_address=VALUES(delivery_address), package_description=VALUES(package_description),
                quantity=VALUES(quantity), weight_kg=VALUES(weight_kg), priority=VALUES(priority), status=VALUES(status), driver_id=VALUES(driver_id),
                vehicle_id=VALUES(vehicle_id), route_id=VALUES(route_id), delivered_at=VALUES(delivered_at),
                delivery_minutes=VALUES(delivery_minutes), customer_satisfaction=VALUES(customer_satisfaction)
                """)) {
            statement.setString(1, shipment.id()); statement.setString(2, shipment.customerName()); statement.setString(3, shipment.customerContact());
            statement.setString(4, shipment.address()); statement.setString(5, shipment.packageDescription()); statement.setInt(6, shipment.quantity());
            statement.setDouble(7, shipment.weightKg()); statement.setString(8, shipment.priority().name()); statement.setString(9, shipment.status().name());
            statement.setString(10, shipment.driverId()); statement.setString(11, shipment.vehicleId()); statement.setString(12, shipment.routeId());
            statement.setTimestamp(13, Timestamp.valueOf(shipment.createdAt()));
            if (shipment.deliveredAt() == null) statement.setNull(14, java.sql.Types.TIMESTAMP); else statement.setTimestamp(14, Timestamp.valueOf(shipment.deliveredAt()));
            if (shipment.deliveryMinutes() <= 0) statement.setNull(15, java.sql.Types.INTEGER); else statement.setInt(15, shipment.deliveryMinutes());
            if (shipment.customerSatisfaction() == 0) statement.setNull(16, java.sql.Types.TINYINT); else statement.setInt(16, shipment.customerSatisfaction());
            statement.executeUpdate();
        }
    }
    @Override public void saveAssignment(Shipment shipment, Vehicle vehicle) { transaction(connection -> { saveShipment(connection, shipment); saveVehicle(connection, vehicle); return null; }); }
    @Override public void saveShipmentAndVehicle(Shipment shipment, Vehicle vehicle) { transaction(connection -> { saveShipment(connection, shipment); saveVehicle(connection, vehicle); return null; }); }
    @Override public void deleteShipment(String id) { write(connection -> { execute(connection, "DELETE FROM shipments WHERE id=?", id); return null; }); }

    @Override public List<RoutePlan> routes() {
        return read(connection -> {
            List<RoutePlan> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM routes ORDER BY created_at DESC"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    List<Stop> stops = new ArrayList<>();
                    try (PreparedStatement stopQuery = connection.prepareStatement("SELECT * FROM route_stops WHERE route_id=? ORDER BY stop_order")) {
                        stopQuery.setString(1, rows.getString("id"));
                        try (ResultSet stopRows = stopQuery.executeQuery()) {
                            while (stopRows.next()) stops.add(new Stop(stopRows.getString("id"), stopRows.getString("stop_name"), stopRows.getDouble("x_coord"), stopRows.getDouble("y_coord")));
                        }
                    }
                    Timestamp created = rows.getTimestamp("created_at");
                    result.add(new RoutePlan(rows.getString("id"), rows.getString("route_name"), rows.getString("driver_id"), stops,
                            rows.getDouble("distance_km"), rows.getInt("estimated_minutes"), rows.getBoolean("completed"),
                            created == null ? LocalDateTime.now() : created.toLocalDateTime()));
                }
            }
            return result;
        });
    }
    @Override public void saveRoute(RoutePlan route) {
        transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO routes(id, route_name, driver_id, distance_km, estimated_minutes, completed, created_at)
                    VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE route_name=VALUES(route_name), driver_id=VALUES(driver_id),
                    distance_km=VALUES(distance_km), estimated_minutes=VALUES(estimated_minutes), completed=VALUES(completed)
                    """)) {
                statement.setString(1, route.id()); statement.setString(2, route.name()); statement.setString(3, route.driverId());
                statement.setDouble(4, route.distanceKm()); statement.setInt(5, route.estimatedMinutes()); statement.setBoolean(6, route.completed());
                statement.setTimestamp(7, Timestamp.valueOf(route.createdAt())); statement.executeUpdate();
            }
            execute(connection, "DELETE FROM route_stops WHERE route_id=?", route.id());
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO route_stops(id, route_id, stop_order, stop_name, x_coord, y_coord) VALUES(?,?,?,?,?,?)")) {
                int order = 0;
                for (Stop stop : route.stops()) {
                    statement.setString(1, stop.id()); statement.setString(2, route.id()); statement.setInt(3, order++);
                    statement.setString(4, stop.name()); statement.setDouble(5, stop.x()); statement.setDouble(6, stop.y()); statement.addBatch();
                }
                statement.executeBatch();
            }
            return null;
        });
    }

    @Override public List<MaintenanceRecord> maintenanceRecords() {
        return read(connection -> {
            List<MaintenanceRecord> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM maintenance_records ORDER BY service_date DESC"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new MaintenanceRecord(rows.getString("id"), rows.getString("vehicle_id"), rows.getDate("service_date").toLocalDate(),
                        rows.getDouble("mileage_km"), rows.getString("description"), rows.getDouble("cost")));
            }
            return result;
        });
    }
    @Override public void saveMaintenance(MaintenanceRecord record) { write(connection -> {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO maintenance_records(id,vehicle_id,service_date,mileage_km,description,cost) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE service_date=VALUES(service_date), mileage_km=VALUES(mileage_km), description=VALUES(description), cost=VALUES(cost)")) {
            statement.setString(1, record.id()); statement.setString(2, record.vehicleId()); statement.setDate(3, Date.valueOf(record.date())); statement.setDouble(4, record.mileageKm()); statement.setString(5, record.description()); statement.setDouble(6, record.cost()); statement.executeUpdate();
        } return null;
    }); }
    @Override public List<FuelRecord> fuelRecords() {
        return read(connection -> {
            List<FuelRecord> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM fuel_records ORDER BY refuel_date DESC"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new FuelRecord(rows.getString("id"), rows.getString("vehicle_id"), rows.getDate("refuel_date").toLocalDate(),
                        rows.getDouble("mileage_km"), rows.getDouble("litres"), rows.getDouble("cost")));
            }
            return result;
        });
    }
    @Override public void saveFuel(FuelRecord record) { write(connection -> {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO fuel_records(id,vehicle_id,refuel_date,mileage_km,litres,cost) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE refuel_date=VALUES(refuel_date), mileage_km=VALUES(mileage_km), litres=VALUES(litres), cost=VALUES(cost)")) {
            statement.setString(1, record.id()); statement.setString(2, record.vehicleId()); statement.setDate(3, Date.valueOf(record.date())); statement.setDouble(4, record.mileageKm()); statement.setDouble(5, record.litres()); statement.setDouble(6, record.cost()); statement.executeUpdate();
        } return null;
    }); }
    @Override public List<TrackingPoint> trackingHistory() {
        return read(connection -> {
            List<TrackingPoint> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM tracking_history ORDER BY recorded_at DESC LIMIT 500"); ResultSet rows = statement.executeQuery()) {
                while (rows.next()) { Timestamp recorded = rows.getTimestamp("recorded_at");
                    result.add(new TrackingPoint(rows.getString("id"), rows.getString("vehicle_id"), rows.getString("shipment_id"), rows.getDouble("x_coord"), rows.getDouble("y_coord"), rows.getInt("eta_minutes"), recorded.toLocalDateTime())); }
            }
            return result;
        });
    }
    @Override public void saveTrackingPoint(TrackingPoint point) { write(connection -> {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO tracking_history(vehicle_id,shipment_id,x_coord,y_coord,eta_minutes,recorded_at) VALUES(?,?,?,?,?,?)")) {
            statement.setString(1, point.vehicleId()); statement.setString(2, point.shipmentId()); statement.setDouble(3, point.x()); statement.setDouble(4, point.y()); statement.setInt(5, point.etaMinutes()); statement.setTimestamp(6, Timestamp.valueOf(point.recordedAt())); statement.executeUpdate();
        } return null;
    }); }

    private void transaction(SqlWork<Void> work) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try { work.run(connection); connection.commit(); }
            catch (SQLException exception) { connection.rollback(); throw exception; }
        } catch (SQLException exception) { throw friendly(exception); }
    }

    private void execute(Connection connection, String sql, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) { statement.setString(1, value); statement.executeUpdate(); }
    }

    @Override public boolean persistent() { return true; }
    @FunctionalInterface private interface SqlWork<T> { T run(Connection connection) throws SQLException; }
}
