package edu.pb.dcls;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Optional;

import static edu.pb.dcls.AppServices.AppException;
import static edu.pb.dcls.Domain.FuelRecord;
import static edu.pb.dcls.Domain.MaintenanceRecord;
import static edu.pb.dcls.Domain.Role;
import static edu.pb.dcls.Domain.User;
import static edu.pb.dcls.Domain.Vehicle;
import static edu.pb.dcls.Domain.VehicleStatus;
import static edu.pb.dcls.UiFactory.addField;
import static edu.pb.dcls.UiFactory.button;
import static edu.pb.dcls.UiFactory.enumFilter;
import static edu.pb.dcls.UiFactory.formGrid;
import static edu.pb.dcls.UiFactory.labeled;
import static edu.pb.dcls.UiFactory.onRowDoubleClick;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.panel;
import static edu.pb.dcls.UiFactory.statusCol;
import static edu.pb.dcls.UiFactory.textCol;
import static javafx.scene.layout.Priority.ALWAYS;

/** Fleet inventory plus maintenance and fuel record screens, with their edit dialogs. */
final class FleetView {
    private final DclsApp app;

    FleetView(DclsApp app) { this.app = app; }

    VBox page() {
        VBox page = pageHeader("Fleet", "Manage vehicle availability, assignments, and service records.");
        HBox recordLinks = new HBox(8); recordLinks.setAlignment(Pos.CENTER_RIGHT);
        Button maintenance = button("Maintenance records", "secondary-button"); maintenance.setOnAction(event -> { app.fleetRecordsView = "maintenance"; app.showPage("fleet"); });
        Button fuelRecords = button("Fuel records", "secondary-button"); fuelRecords.setOnAction(event -> { app.fleetRecordsView = "fuel"; app.showPage("fleet"); });
        Button vehicles = button("‹  Fleet inventory", "secondary-button"); vehicles.setOnAction(event -> { app.fleetRecordsView = "vehicles"; app.showPage("fleet"); });
        recordLinks.getChildren().addAll(vehicles, maintenance, fuelRecords);
        Node body;
        if (app.fleetRecordsView.equals("maintenance")) body = maintenanceTab();
        else if (app.fleetRecordsView.equals("fuel")) body = fuelTab();
        else body = vehiclesTab();
        page.getChildren().addAll(recordLinks, body);
        return page;
    }

    private Node vehiclesTab() {
        VBox content = new VBox(12); content.setPadding(new Insets(12, 0, 0, 0));
        HBox tools = new HBox(9); tools.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField(); search.setPromptText("Search plate, model, type, or driver"); search.setAccessibleText("Search fleet"); search.setPrefWidth(300);
        javafx.scene.control.ChoiceBox<VehicleStatus> filter = enumFilter(VehicleStatus.values(), "All statuses");
        Region gap = new Region(); HBox.setHgrow(gap, ALWAYS);
        Button add = button("Add vehicle", "primary-button"); add.setDisable(!app.access.canManageFleet(app.currentUser.role())); add.setOnAction(event -> vehicleDialog(null));
        MenuButton records = new MenuButton("Records");
        records.getItems().addAll(app.menu("Maintenance", () -> { app.fleetRecordsView = "maintenance"; app.showPage("fleet"); }), app.menu("Fuel records", () -> { app.fleetRecordsView = "fuel"; app.showPage("fleet"); }));
        tools.getChildren().addAll(search, filter, gap, records, add);
        TableView<Vehicle> table = vehicleTable();
        onRowDoubleClick(table, v -> { if (app.access.canManageFleet(app.currentUser.role())) vehicleDialog(v); });
        Runnable refresh = () -> {
            String q = search.getText() == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
            VehicleStatus status = filter.getValue();
            List<Vehicle> rows = app.repository.vehicles().stream().filter(v -> status == null || v.status() == status)
                    .filter(v -> q.isEmpty() || (v.plate() + " " + v.model() + " " + v.type() + " " + app.driverName(v.driverId())).toLowerCase(Locale.ROOT).contains(q))
                    .toList(); table.setItems(FXCollections.observableArrayList(rows));
        };
        search.textProperty().addListener((observable, oldValue, newValue) -> refresh.run()); filter.valueProperty().addListener((observable, oldValue, newValue) -> refresh.run());
        refresh.run(); table.setPrefHeight(330);
        HBox actions = new HBox(8);
        Button edit = button("Edit", "secondary-button"), status = button("Change status", "secondary-button"), remove = button("Delete", "danger-button");
        edit.setDisable(!app.access.canManageFleet(app.currentUser.role())); status.setDisable(!app.access.canManageFleet(app.currentUser.role())); remove.setDisable(app.currentUser.role() != Role.ADMIN);
        edit.setOnAction(event -> { if (table.getSelectionModel().getSelectedItem() != null) vehicleDialog(table.getSelectionModel().getSelectedItem()); });
        status.setOnAction(event -> { if (table.getSelectionModel().getSelectedItem() != null) vehicleStatusDialog(table.getSelectionModel().getSelectedItem()); });
        remove.setOnAction(event -> {
            Vehicle selected = table.getSelectionModel().getSelectedItem();
            if (selected != null && app.confirm("Delete vehicle?", "Delete " + selected.plate() + "? Vehicles linked to orders or service records cannot be removed.")) {
                app.runAction(() -> app.fleet.deleteVehicle(selected.id()), "Vehicle deleted.", refresh);
            }
        });
        Label reminders = new Label(app.fleet.maintenanceDue().size() + " service reminder(s) based on current mileage"); reminders.getStyleClass().add("muted");
        Region spacer = new Region(); HBox.setHgrow(spacer, ALWAYS);
        actions.getChildren().addAll(edit, status, remove, spacer, reminders);
        VBox inventory = panel(new Label("Fleet inventory")); ((Label)inventory.getChildren().get(0)).getStyleClass().add("section-title");
        inventory.getChildren().addAll(table, actions); VBox.setVgrow(table, ALWAYS);
        content.getChildren().addAll(tools, inventory); VBox.setVgrow(inventory, ALWAYS);
        return content;
    }

    private TableView<Vehicle> vehicleTable() {
        TableView<Vehicle> table = new TableView<>(); table.setPlaceholder(new Label("No vehicles match this search."));
        Set<String> due = new HashSet<>();
        app.fleet.maintenanceDue().forEach(v -> due.add(v.id()));
        table.getColumns().add(textCol("Plate", Vehicle::plate, 90));
        table.getColumns().add(textCol("Vehicle", Vehicle::model, 130));
        table.getColumns().add(textCol("Type", Vehicle::type, 90));
        table.getColumns().add(textCol("Capacity", v -> String.format("%,.0f kg", v.capacityKg()), 85));
        table.getColumns().add(textCol("Mileage", v -> String.format("%,.0f km", v.mileageKm()), 85));
        table.getColumns().add(textCol("Next service", v -> String.format("%,.0f km", v.nextServiceKm()), 95));
        table.getColumns().add(textCol("Driver", v -> app.driverName(v.driverId()), 110));
        table.getColumns().add(statusCol("Status", v -> v.status().label(), 110));
        table.getColumns().add(textCol("Due", v -> due.contains(v.id()) ? "Due" : "", 55));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        return table;
    }

    private Node maintenanceTab() {
        VBox content = new VBox(12); content.setPadding(new Insets(12, 0, 0, 0));
        HBox tools = new HBox(8); tools.setAlignment(Pos.CENTER_LEFT);
        Button add = button("Record service", "primary-button"); add.setDisable(!app.access.canManageFleet(app.currentUser.role())); add.setOnAction(event -> maintenanceDialog());
        ComboBox<Vehicle> vehicleFilter = vehicleFilter();
        Label due = new Label(app.fleet.maintenanceDue().size() + " vehicle(s) are approaching service"); due.getStyleClass().add("muted"); tools.getChildren().addAll(add, vehicleFilter, due);
        TableView<MaintenanceRecord> table = new TableView<>(); table.setPlaceholder(new Label("No maintenance records yet."));
        table.getColumns().add(textCol("Vehicle", m -> app.vehiclePlate(m.vehicleId()), 110));
        table.getColumns().add(textCol("Date", m -> m.date().toString(), 100));
        table.getColumns().add(textCol("Mileage", m -> String.format("%,.0f km", m.mileageKm()), 110));
        table.getColumns().add(textCol("Service", MaintenanceRecord::description, 300));
        table.getColumns().add(textCol("Cost", m -> String.format("$%.2f", m.cost()), 110));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Runnable refresh = () -> {
            Vehicle selected = vehicleFilter.getValue();
            List<MaintenanceRecord> rows = app.repository.maintenanceRecords().stream()
                    .filter(m -> selected == null || selected.id().equals(m.vehicleId()))
                    .sorted(Comparator.comparing(MaintenanceRecord::date).reversed())
                    .toList();
            table.setItems(FXCollections.observableArrayList(rows));
        };
        vehicleFilter.valueProperty().addListener((observable, oldValue, newValue) -> refresh.run());
        refresh.run();
        Button edit = button("Edit", "secondary-button"), remove = button("Delete", "danger-button");
        boolean canManage = app.access.canManageFleet(app.currentUser.role()); edit.setDisable(!canManage); remove.setDisable(!canManage);
        edit.setOnAction(event -> { MaintenanceRecord selected = table.getSelectionModel().getSelectedItem(); if (selected != null) maintenanceDialog(selected); });
        remove.setOnAction(event -> { MaintenanceRecord selected = table.getSelectionModel().getSelectedItem(); if (selected != null && app.confirm("Delete service record?", "Delete this maintenance record? The vehicle's current odometer will be kept.")) app.runAction(() -> app.fleet.deleteMaintenance(selected.id()), "Maintenance record deleted.", () -> app.showPage("fleet")); });
        content.getChildren().addAll(tools, table, new HBox(8, edit, remove)); VBox.setVgrow(table, ALWAYS); return content;
    }

    private Node fuelTab() {
        VBox content = new VBox(12); content.setPadding(new Insets(12, 0, 0, 0));
        HBox tools = new HBox(8); tools.setAlignment(Pos.CENTER_LEFT);
        Button add = button("Record refuel", "primary-button"); add.setDisable(!app.access.canManageFleet(app.currentUser.role())); add.setOnAction(event -> fuelDialog());
        ComboBox<Vehicle> vehicleFilter = vehicleFilter();
        tools.getChildren().addAll(add, vehicleFilter);
        TableView<FuelRecord> table = new TableView<>(); table.setPlaceholder(new Label("No fuel records yet."));
        table.getColumns().add(textCol("Vehicle", f -> app.vehiclePlate(f.vehicleId()), 110));
        table.getColumns().add(textCol("Date", f -> f.date().toString(), 100));
        table.getColumns().add(textCol("Mileage", f -> String.format("%,.0f km", f.mileageKm()), 110));
        table.getColumns().add(textCol("Amount", f -> String.format("%.1f L", f.litres()), 110));
        table.getColumns().add(textCol("Cost", f -> String.format("$%.2f", f.cost()), 110));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Runnable refresh = () -> {
            Vehicle selected = vehicleFilter.getValue();
            List<FuelRecord> rows = app.repository.fuelRecords().stream()
                    .filter(f -> selected == null || selected.id().equals(f.vehicleId()))
                    .sorted(Comparator.comparing(FuelRecord::date).reversed())
                    .toList();
            table.setItems(FXCollections.observableArrayList(rows));
        };
        vehicleFilter.valueProperty().addListener((observable, oldValue, newValue) -> refresh.run());
        refresh.run();
        Button edit = button("Edit", "secondary-button"), remove = button("Delete", "danger-button");
        boolean canManage = app.access.canManageFleet(app.currentUser.role()); edit.setDisable(!canManage); remove.setDisable(!canManage);
        edit.setOnAction(event -> { FuelRecord selected = table.getSelectionModel().getSelectedItem(); if (selected != null) fuelDialog(selected); });
        remove.setOnAction(event -> { FuelRecord selected = table.getSelectionModel().getSelectedItem(); if (selected != null && app.confirm("Delete fuel record?", "Delete this refuel record? The vehicle's current odometer will be kept.")) app.runAction(() -> app.fleet.deleteFuel(selected.id()), "Fuel record deleted.", () -> app.showPage("fleet")); });
        content.getChildren().addAll(tools, table, new HBox(8, edit, remove)); VBox.setVgrow(table, ALWAYS); return content;
    }

    private ComboBox<Vehicle> vehicleFilter() {
        ComboBox<Vehicle> filter = new ComboBox<>(FXCollections.observableArrayList(app.repository.vehicles()));
        filter.getItems().add(0, null);
        filter.setValue(null);
        filter.setPromptText("All vehicles");
        filter.setConverter(new StringConverter<>() {
            public String toString(Vehicle vehicle) { return vehicle == null ? "All vehicles" : vehicle.plate() + " · " + vehicle.model(); }
            public Vehicle fromString(String value) { return null; }
        });
        filter.setPrefWidth(220);
        return filter;
    }

    private void vehicleDialog(Vehicle existing) {
        TextField plate = new TextField(existing == null ? "" : existing.plate());
        TextField model = new TextField(existing == null ? "" : existing.model());
        TextField type = new TextField(existing == null ? "Light truck" : existing.type());
        TextField capacity = new TextField(existing == null ? "" : String.valueOf(existing.capacityKg()));
        TextField mileage = new TextField(existing == null ? "0" : String.valueOf(existing.mileageKm()));
        TextField nextService = new TextField(existing == null ? "5000" : String.valueOf(existing.nextServiceKm()));
        ComboBox<VehicleStatus> status = new ComboBox<>(FXCollections.observableArrayList(VehicleStatus.values())); status.setValue(existing == null ? VehicleStatus.AVAILABLE : existing.status());
        ComboBox<User> driver = app.driverCombo(); driver.setPromptText("Unassigned");
        if (existing != null) app.repository.users().stream().filter(u -> u.id().equals(existing.driverId())).findFirst().ifPresent(driver::setValue);
        GridPane form = formGrid(); addField(form, 0, "Plate number", plate); addField(form, 1, "Model", model); addField(form, 2, "Vehicle type", type);
        addField(form, 3, "Capacity (kg)", capacity); addField(form, 4, "Mileage (km)", mileage); addField(form, 5, "Next service (km)", nextService);
        addField(form, 6, "Status", status); addField(form, 7, "Assigned driver", driver);
        app.confirmForm(existing == null ? "Add vehicle" : "Edit vehicle", form, existing == null ? "Add vehicle" : "Save changes", complete -> {
            try {
                double capacityKg = number(capacity.getText(), "Capacity");
                double mileageKm = number(mileage.getText(), "Mileage");
                double nextServiceKm = number(nextService.getText(), "Next service");
                Vehicle vehicle = new Vehicle(existing == null ? AppServices.id() : existing.id(), plate.getText().trim(), model.getText().trim(), type.getText().trim(),
                        capacityKg, mileageKm, status.getValue(),
                        driver.getValue() == null ? null : driver.getValue().id(), nextServiceKm);
                app.runAsync(() -> app.fleet.save(vehicle), () -> { complete.accept(null); app.showPage("fleet"); }, complete);
            } catch (RuntimeException exception) { complete.accept(exception); }
        });
    }

    private void vehicleStatusDialog(Vehicle vehicle) {
        ComboBox<VehicleStatus> status = new ComboBox<>(FXCollections.observableArrayList(
                Arrays.stream(VehicleStatus.values()).filter(s -> s != VehicleStatus.IN_DELIVERY).toList()));
        status.setValue(vehicle.status());
        Optional<ButtonType> result = app.confirmDialog("Change status · " + vehicle.plate(), status, "Save status");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) app.runAction(() -> app.fleet.setStatus(vehicle, status.getValue()), "Vehicle status updated.", () -> app.showPage("fleet"));
    }

    private double number(String text, String field) {
        try {
            return app.number(text);
        } catch (AppException exception) {
            throw new AppException(field + " must be a number zero or greater.");
        }
    }

    private void maintenanceDialog() { maintenanceDialog(null); }

    private void maintenanceDialog(MaintenanceRecord existing) {
        ComboBox<Vehicle> vehicle = app.vehicleCombo();
        if (existing != null) app.repository.vehicle(existing.vehicleId()).ifPresent(vehicle::setValue);
        vehicle.setDisable(existing != null);
        DatePicker date = new DatePicker(existing == null ? LocalDate.now() : existing.date());
        TextField mileage = new TextField(existing == null ? "0" : String.valueOf(existing.mileageKm()));
        TextField details = new TextField(existing == null ? "" : existing.description());
        TextField cost = new TextField(existing == null ? "0" : String.valueOf(existing.cost()));
        VBox form = new VBox(10, labeled("Vehicle", vehicle), labeled("Date", date), labeled("Mileage (km)", mileage), labeled("Service details", details), labeled("Cost ($)", cost));
        app.confirmForm(existing == null ? "Record maintenance" : "Edit maintenance record", form, "Save record", complete -> {
            try {
                Vehicle selectedVehicle = vehicle.getValue();
                LocalDate selectedDate = date.getValue(); double selectedMileage = app.number(mileage.getText());
                String selectedDetails = details.getText(); double selectedCost = app.number(cost.getText());
                app.runAsync(() -> {
                    if (existing == null) app.fleet.logMaintenance(selectedVehicle, selectedDate, selectedMileage, selectedDetails, selectedCost);
                    else app.fleet.updateMaintenance(existing, selectedDate, selectedMileage, selectedDetails, selectedCost);
                }, () -> { complete.accept(null); app.showPage("fleet"); }, complete);
            } catch (RuntimeException exception) { complete.accept(exception); }
        });
    }

    private void fuelDialog() { fuelDialog(null); }

    private void fuelDialog(FuelRecord existing) {
        ComboBox<Vehicle> vehicle = app.vehicleCombo();
        if (existing != null) app.repository.vehicle(existing.vehicleId()).ifPresent(vehicle::setValue);
        vehicle.setDisable(existing != null);
        DatePicker date = new DatePicker(existing == null ? LocalDate.now() : existing.date());
        TextField mileage = new TextField(existing == null ? "0" : String.valueOf(existing.mileageKm()));
        TextField litres = new TextField(existing == null ? "" : String.valueOf(existing.litres()));
        TextField cost = new TextField(existing == null ? "" : String.valueOf(existing.cost()));
        VBox form = new VBox(10, labeled("Vehicle", vehicle), labeled("Date", date), labeled("Mileage (km)", mileage), labeled("Litres", litres), labeled("Cost ($)", cost));
        app.confirmForm(existing == null ? "Record refuel" : "Edit fuel record", form, "Save record", complete -> {
            try {
                Vehicle selectedVehicle = vehicle.getValue(); LocalDate selectedDate = date.getValue();
                double selectedMileage = app.number(mileage.getText()), selectedLitres = app.number(litres.getText()), selectedCost = app.number(cost.getText());
                app.runAsync(() -> {
                    if (existing == null) app.fleet.logFuel(selectedVehicle, selectedDate, selectedMileage, selectedLitres, selectedCost);
                    else app.fleet.updateFuel(existing, selectedDate, selectedMileage, selectedLitres, selectedCost);
                }, () -> { complete.accept(null); app.showPage("fleet"); }, complete);
            } catch (RuntimeException exception) { complete.accept(exception); }
        });
    }
}
