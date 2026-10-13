package edu.pb.dcls;

import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
import static edu.pb.dcls.UiFactory.enumFilter;
import static edu.pb.dcls.UiFactory.formGrid;
import static edu.pb.dcls.UiFactory.labeled;
import static edu.pb.dcls.UiFactory.onRowDoubleClick;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.panel;
import static edu.pb.dcls.UiFactory.statusCol;
import static edu.pb.dcls.UiFactory.textCol;
import static javafx.scene.layout.Priority.ALWAYS;

/** Orders and shipments screen: list, detail panel, and the create/assign/advance dialogs. */
final class OrdersView {
    private final DclsApp app;
    /** The delivery every panel on this screen is showing: picked from either table. */
    private Shipment selected;

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
        TableView<Shipment> assignedTable = assignedTable();
        TableView<PackageField> packageTable = packageTable();
        onRowDoubleClick(table, s -> { if (app.access.canDispatch(app.currentUser.role())) shipmentDialog(s); });
        onRowDoubleClick(assignedTable, s -> { if (app.access.canDispatch(app.currentUser.role())) shipmentDialog(s); });
        Runnable refresh = () -> {
            String q = search.getText() == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
            OrderStatus s = statusFilter.getValue(); Priority p = priorityFilter.getValue();
            List<Shipment> rows = app.repository.shipments().stream().filter(item -> app.currentUser.role() != Role.DRIVER || app.currentUser.id().equals(item.driverId()))
                    .filter(item -> s == null || item.status() == s).filter(item -> p == null || item.priority() == p)
                    .filter(item -> q.isEmpty() || (item.id() + " " + item.customerName() + " " + item.customerContact() + " " + item.address()).toLowerCase(Locale.ROOT).contains(q))
                    .sorted(Comparator.comparing(Shipment::createdAt).reversed()).toList();
            table.setItems(FXCollections.observableArrayList(rows));
            assignedTable.setItems(FXCollections.observableArrayList(rows.stream().filter(OrdersView::isAssigned).toList()));
            keepSelected(table, assignedTable, rows, packageTable);
        };
        search.textProperty().addListener((observable, oldValue, newValue) -> refresh.run());
        statusFilter.valueProperty().addListener((observable, oldValue, newValue) -> refresh.run());
        priorityFilter.valueProperty().addListener((observable, oldValue, newValue) -> refresh.run()); refresh.run();
        HBox actions = new HBox(8);
        Button edit = button("Edit details", "secondary-button"), assign = button("Assign driver / vehicle", "secondary-button"), advance = button("Advance status", "primary-button"), cancel = button("Cancel order", "danger-button"), delete = button("Delete", "danger-button");
        boolean canDispatch = app.access.canDispatch(app.currentUser.role());
        edit.setDisable(true); assign.setDisable(true); advance.setDisable(true); cancel.setDisable(true); delete.setDisable(true);
        edit.setOnAction(event -> { if (selected != null) shipmentDialog(selected); });
        assign.setOnAction(event -> { if (selected != null) assignmentDialog(selected); });
        advance.setOnAction(event -> { if (selected != null) advanceShipment(selected, refresh); });
        cancel.setOnAction(event -> { Shipment row = selected; if (row != null && app.confirm("Cancel order", "Cancel " + row.id() + "? This cannot be undone.")) app.runAction(() -> app.orders.transition(row, OrderStatus.CANCELLED, app.currentUser), "Order cancelled.", refresh); });
        delete.setOnAction(event -> { Shipment row = selected; if (row != null && app.confirm("Delete order", "Delete " + row.id() + "? Delivered and active deliveries are retained.")) app.runAction(() -> app.orders.delete(row), "Order deleted.", refresh); });
        Runnable updateActions = () -> {
            OrderStatus status = selected == null ? null : selected.status();
            boolean closed = status == OrderStatus.DELIVERED || status == OrderStatus.CANCELLED;
            edit.setDisable(!canDispatch || selected == null || closed);
            assign.setDisable(!canDispatch || selected == null || closed);
            advance.setDisable(!app.access.canUpdateDelivery(app.currentUser.role()) || selected == null || closed);
            cancel.setDisable(!canDispatch || selected == null || status != OrderStatus.PENDING);
            delete.setDisable(!canDispatch || selected == null || (status != OrderStatus.PENDING && status != OrderStatus.CANCELLED));
        };
        table.getSelectionModel().selectedItemProperty().addListener((o, old, row) -> {
            selected = row;
            if (row != null) selectIn(assignedTable, row);
            packageTable.setItems(FXCollections.observableArrayList(packageFields(row)));
            updateActions.run();
        });
        assignedTable.getSelectionModel().selectedItemProperty().addListener((o, old, row) -> {
            if (row == null) return;
            Shipment inOrders = byId(table.getItems(), row.id());
            if (inOrders != null) table.getSelectionModel().select(inOrders);
            else { selected = row; packageTable.setItems(FXCollections.observableArrayList(packageFields(row))); updateActions.run(); }
        });
        updateActions.run();
        actions.getChildren().addAll(edit, assign, advance, cancel, delete);
        VBox list = section("Orders");
        list.getChildren().addAll(table, actions); table.setMaxWidth(Double.MAX_VALUE); table.setPrefHeight(215);
        VBox assignedPanel = section("Assigned deliveries");
        assignedPanel.getChildren().addAll(assignedTable, hint("Only deliveries that already have a driver and vehicle."));
        assignedTable.setMaxWidth(Double.MAX_VALUE); assignedTable.setPrefHeight(150);
        VBox details = section("Package details");
        details.getChildren().add(packageTable); packageTable.setMaxWidth(Double.MAX_VALUE); packageTable.setPrefHeight(270);
        VBox body = new VBox(14, list, assignedPanel, details);
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true); scroll.setStyle("-fx-background-color: transparent;");
        page.getChildren().addAll(tools, scroll); VBox.setVgrow(scroll, ALWAYS); return page;
    }

<<<<<<< HEAD
    private static boolean isAssigned(Shipment shipment) { return shipment.status() == OrderStatus.ASSIGNED || shipment.status() == OrderStatus.IN_TRANSIT; }

    private static Shipment byId(List<Shipment> rows, String id) { return rows.stream().filter(row -> row.id().equals(id)).findFirst().orElse(null); }

    /** Mirrors the current selection across both tables, so a status change never blanks the screen. */
    private void keepSelected(TableView<Shipment> table, TableView<Shipment> assignedTable, List<Shipment> rows, TableView<PackageField> packageTable) {
        String id = selected == null ? null : selected.id();
        Shipment kept = id == null ? null : byId(rows, id);
        if (kept == null) { selected = null; packageTable.setItems(FXCollections.observableArrayList(List.of())); table.getSelectionModel().clearSelection(); return; }
        selectIn(table, kept);
        selectIn(assignedTable, byId(assignedTable.getItems(), id));
        if (selected != kept) { selected = kept; packageTable.setItems(FXCollections.observableArrayList(packageFields(kept))); }
    }

    private static void selectIn(TableView<Shipment> table, Shipment row) {
        if (row == null) table.getSelectionModel().clearSelection();
        else if (table.getSelectionModel().getSelectedItem() != row) table.getSelectionModel().select(row);
    }

    private VBox section(String heading) {
        Label title = new Label(heading); title.getStyleClass().add("section-title");
        VBox box = panel(title); box.setPrefWidth(560); box.setMaxWidth(Double.MAX_VALUE); return box;
    }

    private Label hint(String text) {
        Label label = new Label(text); label.getStyleClass().add("muted"); return label;
    }

    /** One row of the package details table. */
    private record PackageField(String field, String value) { }

    private List<PackageField> packageFields(Shipment shipment) {
        if (shipment == null) return List.of();
        String route = shipment.routeId() == null ? "—" : app.repository.routes().stream().filter(r -> r.id().equals(shipment.routeId()))
                .map(RoutePlan::name).findFirst().orElse("Unknown route");
        String created = shipment.createdAt() == null ? "—" : shipment.createdAt().format(DclsApp.DATE_TIME);
        String delivered = shipment.deliveredAt() == null ? "—" : shipment.deliveredAt().format(DclsApp.DATE_TIME);
        List<PackageField> fields = new ArrayList<>();
        Collections.addAll(fields,
                new PackageField("Order", shipment.id()),
                new PackageField("Status", shipment.status().label()),
                new PackageField("Priority", shipment.priority().label()),
                new PackageField("Customer", shipment.customerName()),
                new PackageField("Phone", shipment.customerContact()),
                new PackageField("Delivery address", shipment.address()),
                new PackageField("Package details", shipment.packageDescription()),
                new PackageField("Packages", shipment.quantity() + " package(s)"),
                new PackageField("Total weight", String.format("%.1f kg", shipment.weightKg())),
                new PackageField("Driver", app.driverName(shipment.driverId())),
                new PackageField("Vehicle", app.vehiclePlate(shipment.vehicleId())),
                new PackageField("Route", route),
                new PackageField("Created", created),
                new PackageField("Delivered", delivered),
                new PackageField("Delivery time", shipment.deliveryMinutes() > 0 ? shipment.deliveryMinutes() + " min" : "—"),
                new PackageField("Customer rating", shipment.customerSatisfaction() > 0 ? shipment.customerSatisfaction() + " / 5" : "Not rated"));
        return fields;
    }

    private TableView<PackageField> packageTable() {
        TableView<PackageField> table = new TableView<>();
        table.setPlaceholder(hint("Select an assigned delivery to see its full package details."));
        TableColumn<PackageField, String> values = textCol("Value", PackageField::value, 520);
        values.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setWrapText(true);
                setText(empty || item == null ? null : item);
            }
        });
        table.getColumns().add(textCol("Detail", PackageField::field, 165));
        table.getColumns().add(values);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        return table;
    }

    private TableView<Shipment> assignedTable() {
        TableView<Shipment> table = new TableView<>(); table.setPlaceholder(hint("No deliveries are assigned yet."));
        table.getColumns().add(textCol("Order", Shipment::id, 115));
        table.getColumns().add(statusCol("Status", s -> s.status().label(), 110));
        table.getColumns().add(textCol("Driver", s -> app.driverName(s.driverId()), 165));
        table.getColumns().add(textCol("Vehicle", s -> app.vehiclePlate(s.vehicleId()), 130));
        table.getColumns().add(textCol("Destination", Shipment::address, 260));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        return table;
=======
    private void showShipmentDetails(VBox panel, Shipment shipment) {
        while (panel.getChildren().size() > 1) panel.getChildren().removeLast();
        if (shipment == null) { panel.getChildren().add(new Label("Select an order to see its details.")); return; }
        Label orderId=new Label(shipment.id()); orderId.setStyle("-fx-font-size:16px;-fx-font-weight:700;");
        panel.getChildren().addAll(orderId, detailField("Customer", shipment.customerName()), detailField("Phone", shipment.customerContact()),
                detailField("Driver / vehicle", app.driverName(shipment.driverId())+" · "+app.vehiclePlate(shipment.vehicleId())),
                detailField("Shipment", shipment.quantity()+" packages · "+shipment.weightKg()+" kg"), detailField("Status", shipment.status().label()));
        Button trackingButton=button("Open tracking", "primary-button"); trackingButton.setMaxWidth(Double.MAX_VALUE);
        boolean trackable = (shipment.status() == OrderStatus.ASSIGNED || shipment.status() == OrderStatus.IN_TRANSIT)
                && shipment.vehicleId() != null;
        trackingButton.setDisable(!trackable);
        if (!trackable) trackingButton.setTooltip(new javafx.scene.control.Tooltip("Assign a driver and vehicle first"));
        trackingButton.setOnAction(event -> { app.focusedShipmentId = shipment.id(); app.showPage("tracking"); }); panel.getChildren().add(trackingButton);
>>>>>>> origin/Sam-part
    }

    private TableView<Shipment> shipmentTable() {
        TableView<Shipment> table = new TableView<>(); table.setPlaceholder(hint("No orders match this search."));
        table.getColumns().add(textCol("Order", Shipment::id, 115));
        table.getColumns().add(textCol("Customer", Shipment::customerName, 190));
        table.getColumns().add(statusCol("Status", s -> s.status().label(), 110));
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
        app.confirmForm(existing == null ? "Create order" : "Edit order details", form, existing == null ? "Create order" : "Save changes", complete -> {
            try {
                String id = existing == null ? AppServices.id() : existing.id();
                Shipment shipment = new Shipment(id, customer.getText().trim(), contact.getText().trim(), address.getText().trim(), packageText.getText().trim(),
                        app.integer(quantity.getText()), app.number(weight.getText()), priority.getValue(), existing == null ? OrderStatus.PENDING : existing.status(),
                        existing == null ? null : existing.driverId(), existing == null ? null : existing.vehicleId(), existing == null ? null : existing.routeId(),
                        existing == null ? LocalDateTime.now() : existing.createdAt(), existing == null ? null : existing.deliveredAt());
                if (existing != null) shipment.setDeliveryMetrics(existing.deliveryMinutes(), existing.customerSatisfaction());
                app.runAsync(() -> app.orders.save(shipment), () -> { complete.accept(null); app.showPage("orders"); }, complete);
            } catch (RuntimeException exception) { complete.accept(exception); }
        });
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
            User selectedDriver = driver.getValue(); Vehicle selectedVehicle = vehicle.getValue(); String selectedRoute = route.getValue() == null ? null : route.getValue().id();
            app.runAction(() -> app.orders.assign(shipment, selectedDriver, selectedVehicle, selectedRoute), "Driver and vehicle assigned.", () -> app.showPage("orders"));
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
        app.runAction(() -> app.orders.transition(shipment, finalNext, app.currentUser, finalRating), "Order moved to " + finalNext.label() + ".", refresh);
    }
}
