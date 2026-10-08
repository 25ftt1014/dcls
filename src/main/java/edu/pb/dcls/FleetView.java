package edu.pb.dcls;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static edu.pb.dcls.Domain.FuelRecord;
import static edu.pb.dcls.Domain.MaintenanceRecord;
import static edu.pb.dcls.Domain.Role;
import static edu.pb.dcls.Domain.User;
import static edu.pb.dcls.Domain.Vehicle;
import static edu.pb.dcls.Domain.VehicleStatus;
import static edu.pb.dcls.UiFactory.addField;
import static edu.pb.dcls.UiFactory.button;
import static edu.pb.dcls.UiFactory.formGrid;
import static edu.pb.dcls.UiFactory.labeled;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.panel;
import static edu.pb.dcls.UiFactory.textCol;
import static javafx.scene.layout.Priority.ALWAYS;

/** Fleet inventory plus maintenance and fuel record screens, with their edit dialogs. */
final class FleetView {
    private final DclsApp app;

    FleetView(DclsApp app) { this.app = app; }

    VBox page() {
        VBox page = pageHeader("Fleet", "Manage vehicle availability, assignments, and service records.");
        HBox recordLinks = new HBox(8); recordLinks.setAlignment(Pos.CENTER_RIGHT);
        Button maintenance = button("Maintenance records", "secondary-button"); maintenance.setOnAction(event -> { page.getChildren().setAll(recordLinks, maintenanceTab()); });
        Button fuelRecords = button("Fuel records", "secondary-button"); fuelRecords.setOnAction(event -> { page.getChildren().setAll(recordLinks, fuelTab()); });
        Button vehicles = button("‹  Fleet inventory", "secondary-button"); vehicles.setOnAction(event -> { app.fleetRecordsView="vehicles"; app.showPage("fleet"); });
        recordLinks.getChildren().addAll(vehicles, maintenance, fuelRecords);
        if (app.fleetRecordsView.equals("maintenance")) page.getChildren().addAll(recordLinks, maintenanceTab());
        else if (app.fleetRecordsView.equals("fuel")) page.getChildren().addAll(recordLinks, fuelTab());
        else page.getChildren().add(vehiclesTab());
        return page;
    }

    private Node vehiclesTab() {
        VBox content = new VBox(12); content.setPadding(new Insets(12, 0, 0, 0));
        HBox tools = new HBox(9); tools.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField(); search.setPromptText("Search plate, model, type, or driver"); search.setAccessibleText("Search fleet"); search.setPrefWidth(300);
        ChoiceBox<VehicleStatus> filter = new ChoiceBox<>(FXCollections.observableArrayList(VehicleStatus.values()));
        filter.getItems().add(0, null); filter.setValue(null); filter.setConverter(new StringConverter<>() { public String toString(VehicleStatus value) { return value == null ? "All statuses" : value.label(); } public VehicleStatus fromString(String value) { return null; } });
        Region gap = new Region(); HBox.setHgrow(gap, ALWAYS);
        Button add = button("Add vehicle", "primary-button"); add.setDisable(!app.access.canManageFleet(app.currentUser.role())); add.setOnAction(event -> vehicleDialog(null));
        MenuButton records = new MenuButton("Records");
        records.getItems().addAll(app.menu("Maintenance", () -> { app.fleetRecordsView="maintenance"; app.showPage("fleet"); }), app.menu("Fuel records", () -> { app.fleetRecordsView="fuel"; app.showPage("fleet"); }));
        MenuButton filters = new MenuButton("Filter");
        MenuItem allStatuses = new MenuItem("All statuses"); allStatuses.setOnAction(event -> filter.setValue(null));
        filters.getItems().add(allStatuses);
        for (VehicleStatus value : VehicleStatus.values()) filters.getItems().add(app.menu(value.label(), () -> { filter.setValue(value); filters.setText(value.label()); }));
        allStatuses.setOnAction(event -> { filter.setValue(null); filters.setText("Filter"); });
        tools.getChildren().addAll(search, gap, filters, records, add);
        TableView<Vehicle> table = vehicleTable();
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
        remove.setOnAction(event -> { Vehicle selected = table.getSelectionModel().getSelectedItem(); if (selected != null && app.confirm("Delete vehicle?", "This action is available only for vehicles without shipment history.")) { try { app.repository.deleteVehicle(selected.id()); refresh.run(); } catch (RuntimeException ex) { app.alert(Alert.AlertType.WARNING, "Vehicle is in use", "This vehicle is linked to an order. Set it to Unavailable instead."); } } });
        Label reminders = new Label(app.fleet.maintenanceDue().size() + " service reminder(s) based on current mileage"); reminders.getStyleClass().add("muted");
        actions.getChildren().addAll(edit, status, remove, new Region(), reminders);
        VBox inventory = panel(new Label("Fleet inventory")); ((Label)inventory.getChildren().get(0)).getStyleClass().add("section-title");
        inventory.getChildren().addAll(table, actions); VBox.setVgrow(table, ALWAYS);
        content.getChildren().addAll(tools, inventory); VBox.setVgrow(inventory, ALWAYS);
        return content;
    }

    private TableView<Vehicle> vehicleTable() {
        TableView<Vehicle> table = new TableView<>(); table.setPlaceholder(new Label("No vehicles match this search."));
        table.getColumns().add(textCol("Plate", Vehicle::plate, 110));
        table.getColumns().add(textCol("Vehicle", Vehicle::model, 180));
        table.getColumns().add(textCol("Driver", v -> app.driverName(v.driverId()), 150));
        table.getColumns().add(textCol("Status", v -> v.status().label(), 140));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        return table;
    }

    private Node maintenanceTab() {
        VBox content = new VBox(12); content.setPadding(new Insets(12, 0, 0, 0));
        HBox tools = new HBox(8); tools.setAlignment(Pos.CENTER_LEFT);
        Button add = button("Record service", "primary-button"); add.setDisable(!app.access.canManageFleet(app.currentUser.role())); add.setOnAction(event -> maintenanceDialog());
        Label due = new Label(app.fleet.maintenanceDue().size() + " vehicle(s) are approaching service"); due.getStyleClass().add("muted"); tools.getChildren().addAll(add, due);
        TableView<MaintenanceRecord> table = new TableView<>(); table.setPlaceholder(new Label("No maintenance records yet."));
        table.getColumns().add(textCol("Vehicle", m -> app.vehiclePlate(m.vehicleId()), 110));
        table.getColumns().add(textCol("Date", m -> m.date().toString(), 100));
        table.getColumns().add(textCol("Mileage", m -> String.format("%,.0f km", m.mileageKm()), 110));
        table.getColumns().add(textCol("Service", MaintenanceRecord::description, 300));
        table.getColumns().add(textCol("Cost", m -> String.format("$%.2f", m.cost()), 110));
        table.setItems(FXCollections.observableArrayList(app.repository.maintenanceRecords())); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        content.getChildren().addAll(tools, table); VBox.setVgrow(table, ALWAYS); return content;
    }

    private Node fuelTab() {
        VBox content = new VBox(12); content.setPadding(new Insets(12, 0, 0, 0));
        Button add = button("Record refuel", "primary-button"); add.setDisable(!app.access.canManageFleet(app.currentUser.role())); add.setOnAction(event -> fuelDialog());
        TableView<FuelRecord> table = new TableView<>(); table.setPlaceholder(new Label("No fuel records yet."));
        table.getColumns().add(textCol("Vehicle", f -> app.vehiclePlate(f.vehicleId()), 110));
        table.getColumns().add(textCol("Date", f -> f.date().toString(), 100));
        table.getColumns().add(textCol("Mileage", f -> String.format("%,.0f km", f.mileageKm()), 110));
        table.getColumns().add(textCol("Amount", f -> String.format("%.1f L", f.litres()), 110));
        table.getColumns().add(textCol("Cost", f -> String.format("$%.2f", f.cost()), 110));
        table.setItems(FXCollections.observableArrayList(app.repository.fuelRecords())); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        content.getChildren().addAll(add, table); VBox.setVgrow(table, ALWAYS); return content;
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
        Optional<ButtonType> result = app.confirmDialog(existing == null ? "Add vehicle" : "Edit vehicle", form, existing == null ? "Add vehicle" : "Save changes");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            try {
                Vehicle vehicle = existing == null ? new Vehicle(AppServices.id(), plate.getText(), model.getText(), type.getText(), app.number(capacity.getText()), app.number(mileage.getText()), status.getValue(),
                        driver.getValue() == null ? null : driver.getValue().id(), app.number(nextService.getText())) : existing;
                if (existing != null) vehicle.update(plate.getText(), model.getText(), type.getText(), app.number(capacity.getText()), app.number(mileage.getText()), status.getValue(),
                        driver.getValue() == null ? null : driver.getValue().id(), app.number(nextService.getText()));
                app.fleet.save(vehicle); app.showPage("fleet");
            } catch (RuntimeException exception) { app.alert(Alert.AlertType.WARNING, "Vehicle not saved", app.readable(exception)); }
        }
    }

    private void vehicleStatusDialog(Vehicle vehicle) {
        ComboBox<VehicleStatus> status = new ComboBox<>(FXCollections.observableArrayList(VehicleStatus.values())); status.setValue(vehicle.status());
        Optional<ButtonType> result = app.confirmDialog("Change status · " + vehicle.plate(), status, "Save status");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) { app.runAction(() -> app.fleet.setStatus(vehicle, status.getValue()), "Vehicle status updated."); app.showPage("fleet"); }
    }

    private void maintenanceDialog() {
        ComboBox<Vehicle> vehicle = app.vehicleCombo(); DatePicker date = new DatePicker(LocalDate.now());
        TextField mileage = new TextField("0"), details = new TextField(), cost = new TextField("0");
        VBox form = new VBox(10, labeled("Vehicle", vehicle), labeled("Date", date), labeled("Mileage (km)", mileage), labeled("Service details", details), labeled("Cost ($)", cost));
        Optional<ButtonType> result = app.confirmDialog("Record maintenance", form, "Save record");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            try { app.fleet.logMaintenance(vehicle.getValue(), date.getValue(), app.number(mileage.getText()), details.getText(), app.number(cost.getText())); app.showPage("fleet"); }
            catch (RuntimeException ex) { app.alert(Alert.AlertType.WARNING, "Service record not saved", app.readable(ex)); }
        }
    }

    private void fuelDialog() {
        ComboBox<Vehicle> vehicle = app.vehicleCombo(); DatePicker date = new DatePicker(LocalDate.now());
        TextField mileage = new TextField("0"), litres = new TextField(), cost = new TextField();
        VBox form = new VBox(10, labeled("Vehicle", vehicle), labeled("Date", date), labeled("Mileage (km)", mileage), labeled("Litres", litres), labeled("Cost ($)", cost));
        Optional<ButtonType> result = app.confirmDialog("Record refuel", form, "Save record");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            try { app.fleet.logFuel(vehicle.getValue(), date.getValue(), app.number(mileage.getText()), app.number(litres.getText()), app.number(cost.getText())); app.showPage("fleet"); }
            catch (RuntimeException ex) { app.alert(Alert.AlertType.WARNING, "Fuel record not saved", app.readable(ex)); }
        }
    }
}
