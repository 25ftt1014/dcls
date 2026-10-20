CREATE DATABASE IF NOT EXISTS dcls CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE dcls;

CREATE TABLE IF NOT EXISTS roles (
    role_code VARCHAR(24) PRIMARY KEY,
    display_name VARCHAR(48) NOT NULL
);

CREATE TABLE IF NOT EXISTS permissions (
    permission_code VARCHAR(40) PRIMARY KEY,
    display_name VARCHAR(80) NOT NULL
);

CREATE TABLE IF NOT EXISTS role_permissions (
    role_code VARCHAR(24) NOT NULL,
    permission_code VARCHAR(40) NOT NULL,
    PRIMARY KEY (role_code, permission_code),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_code) REFERENCES roles(role_code),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_code) REFERENCES permissions(permission_code)
);

CREATE TABLE IF NOT EXISTS users (
    id VARCHAR(36) PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    email VARCHAR(160) NOT NULL UNIQUE,
    phone VARCHAR(32),
    password_hash VARCHAR(256) NOT NULL,
    password_salt VARCHAR(64) NOT NULL,
    role_code VARCHAR(24) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    pending BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_role FOREIGN KEY (role_code) REFERENCES roles(role_code)
);

CREATE TABLE IF NOT EXISTS vehicles (
    id VARCHAR(36) PRIMARY KEY,
    plate_number VARCHAR(24) NOT NULL UNIQUE,
    model VARCHAR(80) NOT NULL,
    vehicle_type VARCHAR(40) NOT NULL,
    capacity_kg DECIMAL(10,2) NOT NULL,
    mileage_km DECIMAL(12,1) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    driver_id VARCHAR(36) NULL,
    next_service_km DECIMAL(12,1) NOT NULL DEFAULT 10000,
    CONSTRAINT fk_vehicle_driver FOREIGN KEY (driver_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS maintenance_records (
    id VARCHAR(36) PRIMARY KEY,
    vehicle_id VARCHAR(36) NOT NULL,
    service_date DATE NOT NULL,
    mileage_km DECIMAL(12,1) NOT NULL,
    description VARCHAR(300) NOT NULL,
    cost DECIMAL(10,2) NOT NULL DEFAULT 0,
    CONSTRAINT fk_maintenance_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
);

CREATE TABLE IF NOT EXISTS fuel_records (
    id VARCHAR(36) PRIMARY KEY,
    vehicle_id VARCHAR(36) NOT NULL,
    refuel_date DATE NOT NULL,
    mileage_km DECIMAL(12,1) NOT NULL,
    litres DECIMAL(10,2) NOT NULL,
    cost DECIMAL(10,2) NOT NULL,
    CONSTRAINT fk_fuel_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id)
);

CREATE TABLE IF NOT EXISTS routes (
    id VARCHAR(36) PRIMARY KEY,
    route_name VARCHAR(100) NOT NULL,
    driver_id VARCHAR(36),
    distance_km DECIMAL(10,2) NOT NULL DEFAULT 0,
    estimated_minutes INT NOT NULL DEFAULT 0,
    completed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_route_driver FOREIGN KEY (driver_id) REFERENCES users(id)
);

-- Stop/track coordinates are WGS84 degrees: x_coord = longitude, y_coord = latitude (Brunei Darussalam).
-- Stop ids carry prefixes (custom-/order-/stop-/geo- + UUID), so 48 chars, not 36.
-- Stop ids are district/pin identifiers (e.g. 'gadong', 'order-<shipment>') shared across
-- routes, so the primary key is (route_id, stop_order): the same stop may appear on many routes.
CREATE TABLE IF NOT EXISTS route_stops (
    id VARCHAR(48) NOT NULL,
    route_id VARCHAR(36) NOT NULL,
    stop_order INT NOT NULL,
    stop_name VARCHAR(160) NOT NULL,
    x_coord DECIMAL(8,5) NOT NULL,
    y_coord DECIMAL(8,5) NOT NULL,
    PRIMARY KEY (route_id, stop_order),
    KEY idx_route_stops_id (id),
    CONSTRAINT fk_stop_route FOREIGN KEY (route_id) REFERENCES routes(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS shipments (
    id VARCHAR(36) PRIMARY KEY,
    customer_name VARCHAR(120) NOT NULL,
    customer_contact VARCHAR(64),
    delivery_address VARCHAR(240) NOT NULL,
    package_description VARCHAR(240) NOT NULL,
    quantity INT NOT NULL DEFAULT 1,
    weight_kg DECIMAL(10,2) NOT NULL DEFAULT 0,
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    driver_id VARCHAR(36),
    vehicle_id VARCHAR(36),
    route_id VARCHAR(36),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP NULL,
    delivery_minutes INT NULL,
    customer_satisfaction TINYINT NULL,
    CONSTRAINT fk_shipment_driver FOREIGN KEY (driver_id) REFERENCES users(id),
    CONSTRAINT fk_shipment_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT fk_shipment_route FOREIGN KEY (route_id) REFERENCES routes(id)
);

CREATE TABLE IF NOT EXISTS tracking_history (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    client_id VARCHAR(48) NULL,
    vehicle_id VARCHAR(36) NOT NULL,
    shipment_id VARCHAR(36),
    x_coord DECIMAL(8,5) NOT NULL,
    y_coord DECIMAL(8,5) NOT NULL,
    eta_minutes INT NOT NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    speed_kmh DECIMAL(6,2) NOT NULL DEFAULT 0,
    heading_deg DECIMAL(5,1) NULL,
    accuracy_m DECIMAL(6,1) NULL,
    fix_type VARCHAR(8) NOT NULL DEFAULT 'GPS',
    source VARCHAR(16) NOT NULL DEFAULT 'nmea',
    CONSTRAINT fk_tracking_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id) ON DELETE CASCADE,
    CONSTRAINT fk_tracking_shipment FOREIGN KEY (shipment_id) REFERENCES shipments(id) ON DELETE CASCADE
);

-- MySQL has no ADD COLUMN IF NOT EXISTS (MariaDB-only): guarded procedure instead.
DELIMITER $$
DROP PROCEDURE IF EXISTS dcls_add_tracking_col$$
CREATE PROCEDURE dcls_add_tracking_col(col_def VARCHAR(200), col_name VARCHAR(32))
BEGIN
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tracking_history' AND COLUMN_NAME = col_name) THEN
        SET @ddl = CONCAT('ALTER TABLE tracking_history ADD COLUMN ', col_def);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;
CALL dcls_add_tracking_col('speed_kmh DECIMAL(6,2) NOT NULL DEFAULT 0', 'speed_kmh');
CALL dcls_add_tracking_col('heading_deg DECIMAL(5,1) NULL', 'heading_deg');
CALL dcls_add_tracking_col('accuracy_m DECIMAL(6,1) NULL', 'accuracy_m');
CALL dcls_add_tracking_col('fix_type VARCHAR(8) NOT NULL DEFAULT \'GPS\'', 'fix_type');
CALL dcls_add_tracking_col('source VARCHAR(16) NOT NULL DEFAULT \'nmea\'', 'source');
CALL dcls_add_tracking_col('client_id VARCHAR(48) NULL', 'client_id');
DROP PROCEDURE IF EXISTS dcls_add_tracking_col;
-- Existing databases created before the widening still have VARCHAR(36): upgrade in place.
ALTER TABLE route_stops MODIFY COLUMN id VARCHAR(48);
-- Older databases used PRIMARY KEY (id), which rejects the same district/pin stop on two
-- routes (e.g. two routes via 'gadong'). Migrate to PRIMARY KEY (route_id, stop_order).
-- Guarded procedure: MySQL has no IF NOT EXISTS for key changes.
DELIMITER $$
DROP PROCEDURE IF EXISTS dcls_migrate_route_stops_pk$$
CREATE PROCEDURE dcls_migrate_route_stops_pk()
BEGIN
    DECLARE pk_cols TEXT DEFAULT NULL;
    SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) INTO pk_cols
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'route_stops' AND INDEX_NAME = 'PRIMARY';
    IF pk_cols = 'id' THEN
        ALTER TABLE route_stops DROP PRIMARY KEY, ADD PRIMARY KEY (route_id, stop_order);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM INFORMATION_SCHEMA.STATISTICS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'route_stops' AND INDEX_NAME = 'idx_route_stops_id') THEN
        ALTER TABLE route_stops ADD KEY idx_route_stops_id (id);
    END IF;
END$$
DELIMITER ;
CALL dcls_migrate_route_stops_pk;
DROP PROCEDURE IF EXISTS dcls_migrate_route_stops_pk;

INSERT IGNORE INTO roles(role_code, display_name) VALUES
('ADMIN', 'Administrator'), ('MANAGER', 'Manager'), ('DISPATCHER', 'Dispatcher'), ('DRIVER', 'Driver');

INSERT IGNORE INTO permissions(permission_code, display_name) VALUES
('VIEW_DASHBOARD', 'View dashboard'), ('MANAGE_USERS', 'Manage users and roles'),
('MANAGE_FLEET', 'Manage vehicles and service records'), ('MANAGE_ORDERS', 'Create and manage shipments'),
('DISPATCH', 'Assign deliveries and routes'), ('PLAN_ROUTES', 'Plan and optimize routes'),
('VIEW_TRACKING', 'View live tracking'), ('UPDATE_ASSIGNED_DELIVERY', 'Update assigned delivery status'),
('EDIT_PROFILE', 'Edit own profile and password');

INSERT IGNORE INTO role_permissions(role_code, permission_code) VALUES
('ADMIN','VIEW_DASHBOARD'), ('ADMIN','MANAGE_USERS'), ('ADMIN','MANAGE_FLEET'), ('ADMIN','MANAGE_ORDERS'), ('ADMIN','DISPATCH'), ('ADMIN','PLAN_ROUTES'), ('ADMIN','VIEW_TRACKING'), ('ADMIN','UPDATE_ASSIGNED_DELIVERY'), ('ADMIN','EDIT_PROFILE'),
('MANAGER','VIEW_DASHBOARD'), ('MANAGER','MANAGE_FLEET'), ('MANAGER','MANAGE_ORDERS'), ('MANAGER','DISPATCH'), ('MANAGER','PLAN_ROUTES'), ('MANAGER','VIEW_TRACKING'), ('MANAGER','UPDATE_ASSIGNED_DELIVERY'), ('MANAGER','EDIT_PROFILE'),
('DISPATCHER','VIEW_DASHBOARD'), ('DISPATCHER','MANAGE_ORDERS'), ('DISPATCHER','DISPATCH'), ('DISPATCHER','PLAN_ROUTES'), ('DISPATCHER','VIEW_TRACKING'), ('DISPATCHER','UPDATE_ASSIGNED_DELIVERY'), ('DISPATCHER','EDIT_PROFILE'),
('DRIVER','VIEW_DASHBOARD'), ('DRIVER','VIEW_TRACKING'), ('DRIVER','UPDATE_ASSIGNED_DELIVERY'), ('DRIVER','EDIT_PROFILE');
