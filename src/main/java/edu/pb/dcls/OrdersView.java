package edu.pb.dcls;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static edu.pb.dcls.Domain.OrderStatus;
import static edu.pb.dcls.Domain.Priority;
import static edu.pb.dcls.Domain.Role;
import static edu.pb.dcls.Domain.RoutePlan;
import static edu.pb.dcls.Domain.Shipment;
import static edu.pb.dcls.Domain.User;
import static edu.pb.dcls.Domain.Vehicle;
import static edu.pb.dcls.Domain.VehicleStatus;
import static edu.pb.dcls.UiFactory.addField;
import static edu.pb.dcls.UiFactory.button;
import static edu.pb.dcls.UiFactory.detailField;
import static edu.pb.dcls.UiFactory.enumFilter;
import static edu.pb.dcls.UiFactory.formGrid;
import static edu.pb.dcls.UiFactory.labeled;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.panel;
import static edu.pb.dcls.UiFactory.textCol;
import static javafx.scene.layout.Priority.ALWAYS;

/** Orders and shipments screen: list, detail panel, and the create/assign/advance dialogs. */
final class OrdersView {
    private final DclsApp app;

    OrdersView(DclsApp app) { this.app = app; }

    VBox page() {
        VBox page = pageHeader("Orders & shipments", "Follow each delivery from intake through customer confirmation.");
        HBox tools = new HBox(9); tools.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField(); search.setPromptText("Search order or customer..."); search.setPrefWidth(310); search.setAccessibleText("Search orders");
        ChoiceBox<OrderStatus> statusFilter = enumFilter(OrderStatus.values(), "All statuses");
        ChoiceBox<Priority> priorityFilter = enumFilter(Priority.values(), "All priorities");
        Region gap = new Region(); HBox.setHgrow(gap, ALWAYS);
        Button create = button("Create order", "primary-button"); create.setDisable(!app.access.canDispatch(app.currentUser.role())); create.setOnAction(event -> shipmentDialog(null));
        tools.getChildren().addAll(search, statusFilter, priorityFilter, gap, create);
        TableView<Shipment> table = shipmentTable();
        Runnable refresh = () -> {
            String q = search.getText() == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
            OrderStatus s = statusFilter.getValue(); Priority p = priorityFilter.getValue();
            List<Shipment> rows = app.repository.shipments().stream().filter(item -> app.currentUser.role() != Role.DRIVER || app.currentUser.id().equals(item.driverId()))
                    .filter(item -> s == null || item.status() == s).filter(item -> p == null || item.priority() == p)
                    .filter(item -> q.isEmpty() || (item.id() + " " + item.customerName() + " " + item.customerContact() + " " + item.address()).toLowerCase(Locale.ROOT).contains(q))
                    .sorted(Comparator.comparing(Shipment::createdAt).reversed()).toList();
            table.setItems(FXCollections.observableArrayList(rows));
        };
        search.textProperty().addListener((observable, oldValue, newValue) -> refresh.run());
        statusFilter.valueProperty().addListener((observable, oldValue, newValue) -> refresh.run());
        priorityFilter.valueProperty().addListener((observable, oldValue, newValue) -> refresh.run()); refresh.run(); table.setPrefHeight(340);
        HBox actions = new HBox(8);
        Button edit = button("Edit details", "secondary-button"), assign = button("Assign driver / vehicle", "secondary-button"), advance = button("Advance status", "primary-button"), cancel = button("Cancel order", "danger-button");
        boolean canDispatch = app.access.canDispatch(app.currentUser.role());
        edit.setDisable(!canDispatch); assign.setDisable(!canDispatch); advance.setDisable(!app.access.canUpdateDelivery(app.currentUser.role())); cancel.setDisable(!canDispatch);
        edit.setOnAction(event -> { Shipment selected = table.getSelectionModel().getSelectedItem(); if (selected != null) shipmentDialog(selected); });
        assign.setOnAction(event -> { Shipment selected = table.getSelectionModel().getSelectedItem(); if (selected != null) assignmentDialog(selected); });
        advance.setOnAction(event -> { Shipment selected = table.getSelectionModel().getSelectedItem(); if (selected != null) advanceShipment(selected, refresh); });
        cancel.setOnAction(event -> { Shipment selected = table.getSelectionModel().getSelectedItem(); if (selected != null && app.confirm("Cancel order", "Cancel " + selected.id() + "?")) { app.runAction(() -> app.orders.transition(selected, OrderStatus.CANCELLED, app.currentUser), "Order cancelled."); refresh.run(); } });
        actions.getChildren().addAll(edit, assign, advance, cancel);
        VBox list = panel(new Label("Orders")); ((Label)list.getChildren().get(0)).getStyleClass().add("section-title");
        table.setPrefWidth(475); table.setPrefHeight(335); list.getChildren().addAll(table, actions); VBox.setVgrow(table, ALWAYS);
        VBox detail = panel(new Label("Order details")); ((Label)detail.getChildren().get(0)).getStyleClass().add("section-title"); detail.setPrefWidth(230); detail.setMinWidth(215);
        Shipment initial = table.getItems().isEmpty() ? null : table.getItems().getFirst();
        showShipmentDetails(detail, initial);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> showShipmentDetails(detail, selected));
        HBox body = new HBox(14, list, detail); HBox.setHgrow(list, ALWAYS);
        page.getChildren().addAll(tools, body); VBox.setVgrow(body, ALWAYS); return page;
    }

    private void showShipmentDetails(VBox panel, Shipment shipment) {
        while (panel.getChildren().size() > 1) panel.getChildren().removeLast();
        if (shipment == null) { panel.getChildren().add(new Label("Select an order to see its details.")); return; }
        Label orderId=new Label(shipment.id()); orderId.setStyle("-fx-font-size:16px;-fx-font-weight:700;");
        panel.getChildren().addAll(orderId, detailField("Customer", shipment.customerName()), detailField("Phone", shipment.customerContact()),
                detailField("Driver / vehicle", app.driverName(shipment.driverId())+" · "+app.vehiclePlate(shipment.vehicleId())),
                detailField("Shipment", shipment.quantity()+" packages · "+shipment.weightKg()+" kg"), detailField("Status", shipment.status().label()));
        Button trackingButton=button("Open tracking", "primary-button"); trackingButton.setMaxWidth(Double.MAX_VALUE);
        trackingButton.setOnAction(event -> app.showPage("tracking")); panel.getChildren().add(trackingButton);
    }

    private TableView<Shipment> shipmentTable() {
        TableView<Shipment> table = new TableView<>(); table.setPlaceholder(new Label("No orders match this search."));
        table.getColumns().add(textCol("Order", Shipment::id, 115));
        table.getColumns().add(textCol("Customer", Shipment::customerName, 190));
        table.getColumns().add(textCol("Status", s -> s.status().label(), 110));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        return table;
    }

    private void shipmentDialog(Shipment existing) {
        TextField customer = new TextField(existing == null ? "" : existing.customerName());
        TextField contact = new TextField(existing == null ? "" : existing.customerContact());
        TextField address = new TextField(existing == null ? "" : existing.address());
        TextField packageText = new TextField(existing == null ? "" : existing.packageDescription());
        TextField quantity = new TextField(existing == null ? "1" : String.valueOf(existing.quantity()));
        TextField weight = new TextField(existing == null ? "0" : String.valueOf(existing.weightKg()));
        ComboBox<Priority> priority = new ComboBox<>(FXCollections.observableArrayList(Priority.values())); priority.setValue(existing == null ? Priority.NORMAL : existing.priority());
        GridPane form = formGrid(); addField(form, 0, "Customer", customer); addField(form, 1, "Contact", contact); addField(form, 2, "Delivery address", address);
        addField(form, 3, "Package details", packageText); addField(form, 4, "Quantity", quantity); addField(form, 5, "Weight (kg)", weight); addField(form, 6, "Priority", priority);
        Optional<ButtonType> result = app.confirmDialog(existing == null ? "Create order" : "Edit order details", form, existing == null ? "Create order" : "Save changes");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            try {
                Shipment shipment = existing == null ? new Shipment("DCLS-" + (2400 + app.repository.shipments().size() + 1), customer.getText().trim(), contact.getText().trim(), address.getText().trim(), packageText.getText().trim(),
                        app.integer(quantity.getText()), app.number(weight.getText()), priority.getValue(), OrderStatus.PENDING, null, null, null, LocalDateTime.now(), null) : existing;
                if (existing != null) shipment.update(customer.getText().trim(), contact.getText().trim(), address.getText().trim(), packageText.getText().trim(), app.integer(quantity.getText()), app.number(weight.getText()), priority.getValue(), shipment.driverId(), shipment.vehicleId(), shipment.routeId());
                app.orders.save(shipment); app.showPage("orders");
            } catch (RuntimeException exception) { app.alert(Alert.AlertType.WARNING, "Order not saved", app.readable(exception)); }
        }
    }

    private void assignmentDialog(Shipment shipment) {
        ComboBox<User> driver = app.driverCombo();
        app.repository.users().stream().filter(user -> user.id().equals(shipment.driverId())).findFirst().ifPresent(driver::setValue);
        ComboBox<Vehicle> vehicle = new ComboBox<>(FXCollections.observableArrayList(app.repository.vehicles().stream().filter(v -> v.status() == VehicleStatus.AVAILABLE || v.id().equals(shipment.vehicleId())).toList()));
        vehicle.setConverter(new StringConverter<>() { public String toString(Vehicle v) { return v == null ? "" : v.plate() + " · " + v.model() + " · " + v.status().label(); } public Vehicle fromString(String value) { return null; } });
        app.repository.vehicle(shipment.vehicleId()).ifPresent(vehicle::setValue);
        ComboBox<RoutePlan> route = new ComboBox<>(FXCollections.observableArrayList(app.repository.routes())); route.setPromptText("No route assigned");
        route.setConverter(new StringConverter<>() { public String toString(RoutePlan r) { return r == null ? "" : r.name() + " · " + String.format("%.1f km", r.distanceKm()); } public RoutePlan fromString(String value) { return null; } });
        app.repository.routes().stream().filter(r -> r.id().equals(shipment.routeId())).findFirst().ifPresent(route::setValue);
        VBox form = new VBox(10, labeled("Driver", driver), labeled("Available vehicle", vehicle), labeled("Route", route));
        Optional<ButtonType> result = app.confirmDialog("Assign delivery · " + shipment.id(), form, "Assign");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            app.runAction(() -> app.orders.assign(shipment, driver.getValue(), vehicle.getValue(), route.getValue() == null ? null : route.getValue().id()), "Driver and vehicle assigned."); app.showPage("orders");
        }
    }

    private void advanceShipment(Shipment shipment, Runnable refresh) {
        OrderStatus next = switch (shipment.status()) {
            case ASSIGNED -> OrderStatus.IN_TRANSIT;
            case IN_TRANSIT -> OrderStatus.DELIVERED;
            case PENDING -> OrderStatus.ASSIGNED;
            default -> null;
        };
        if (next == null) { app.alert(Alert.AlertType.INFORMATION, "Order closed", "This order is already complete or cancelled."); return; }
        int ratingValue = 0;
        if (next == OrderStatus.DELIVERED) {
            ChoiceBox<Integer> rating = new ChoiceBox<>(FXCollections.observableArrayList(1, 2, 3, 4, 5)); rating.setValue(5);
            VBox form = new VBox(8, new Label("Confirm this delivery and record customer satisfaction."), labeled("Customer rating (1–5)", rating));
            Optional<ButtonType> result = app.confirmDialog("Delivery confirmation", form, "Confirm delivery");
            if (result.isEmpty() || result.get().getButtonData() != ButtonType.OK.getButtonData()) return;
            ratingValue = rating.getValue();
        }
        if (next == OrderStatus.ASSIGNED && shipment.driverId() == null) { assignmentDialog(shipment); return; }
        OrderStatus finalNext = next;
        int finalRating = ratingValue;
        app.runAction(() -> { if (finalRating > 0) shipment.setCustomerSatisfaction(finalRating); app.orders.transition(shipment, finalNext, app.currentUser); }, "Order moved to " + finalNext.label() + ".");
        refresh.run();
        if (app.activePage.equals("dashboard")) app.showPage("dashboard");
    }
}
