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

CREATE TABLE IF NOT EXISTS route_stops (
    id VARCHAR(36) PRIMARY KEY,
    route_id VARCHAR(36) NOT NULL,
    stop_order INT NOT NULL,
    stop_name VARCHAR(160) NOT NULL,
    x_coord DECIMAL(8,5) NOT NULL,
    y_coord DECIMAL(8,5) NOT NULL,
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
    vehicle_id VARCHAR(36) NOT NULL,
    shipment_id VARCHAR(36),
    x_coord DECIMAL(8,5) NOT NULL,
    y_coord DECIMAL(8,5) NOT NULL,
    eta_minutes INT NOT NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_tracking_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicles(id),
    CONSTRAINT fk_tracking_shipment FOREIGN KEY (shipment_id) REFERENCES shipments(id)
);

INSERT IGNORE INTO roles(role_code, display_name) VALUES
('ADMIN', 'Administrator'), ('MANAGER', 'Manager'), ('DISPATCHER', 'Dispatcher'), ('DRIVER', 'Driver');

INSERT IGNORE INTO permissions(permission_code, display_name) VALUES
('VIEW_DASHBOARD', 'View dashboard'), ('MANAGE_USERS', 'Manage users and roles'),
('MANAGE_FLEET', 'Manage vehicles and service records'), ('MANAGE_ORDERS', 'Create and manage shipments'),
('DISPATCH', 'Assign deliveries and routes'), ('PLAN_ROUTES', 'Plan and optimize routes'),
('VIEW_TRACKING', 'View simulated tracking'), ('UPDATE_ASSIGNED_DELIVERY', 'Update assigned delivery status'),
('EDIT_PROFILE', 'Edit own profile and password');

INSERT IGNORE INTO role_permissions(role_code, permission_code) VALUES
('ADMIN','VIEW_DASHBOARD'), ('ADMIN','MANAGE_USERS'), ('ADMIN','MANAGE_FLEET'), ('ADMIN','MANAGE_ORDERS'), ('ADMIN','DISPATCH'), ('ADMIN','PLAN_ROUTES'), ('ADMIN','VIEW_TRACKING'), ('ADMIN','UPDATE_ASSIGNED_DELIVERY'), ('ADMIN','EDIT_PROFILE'),
('MANAGER','VIEW_DASHBOARD'), ('MANAGER','MANAGE_FLEET'), ('MANAGER','MANAGE_ORDERS'), ('MANAGER','DISPATCH'), ('MANAGER','PLAN_ROUTES'), ('MANAGER','VIEW_TRACKING'), ('MANAGER','UPDATE_ASSIGNED_DELIVERY'), ('MANAGER','EDIT_PROFILE'),
('DISPATCHER','VIEW_DASHBOARD'), ('DISPATCHER','MANAGE_ORDERS'), ('DISPATCHER','DISPATCH'), ('DISPATCHER','PLAN_ROUTES'), ('DISPATCHER','VIEW_TRACKING'), ('DISPATCHER','UPDATE_ASSIGNED_DELIVERY'), ('DISPATCHER','EDIT_PROFILE'),
('DRIVER','VIEW_DASHBOARD'), ('DRIVER','VIEW_TRACKING'), ('DRIVER','UPDATE_ASSIGNED_DELIVERY'), ('DRIVER','EDIT_PROFILE');
