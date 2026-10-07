package edu.pb.dcls;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static edu.pb.dcls.AppServices.*;
import static edu.pb.dcls.Domain.*;
import static javafx.scene.layout.Priority.ALWAYS;

/** Desktop client for the Delivery Logistics Simulator. */
public final class DclsApp extends Application {
    private static final String DEMO_EMAIL = "admin@dcls.local";
    private static final String DEMO_PASSWORD = "demo1234";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM, HH:mm");

    private Stage stage;
    private Repository repository;
    private AuthService auth;
    private AccessService access;
    private FleetService fleet;
    private OrderService orders;
    private RouteService routeService;
    private TrackingService tracking;
    private User currentUser;
    private BorderPane shell;
    private VBox nav;
    private Label pageHeading;
    private Label dataModeLabel;
    private String activePage = "dashboard";
    private Timeline trackingTimer;
    private Canvas trackingCanvas;
    private Label trackingEta;
    private String routeNameInput = "";
    private final ObservableList<String> routeStopsDraft = FXCollections.observableArrayList();

    @Override public void start(Stage stage) {
        this.stage = stage;
        try {
            repository = configuredRepository();
        } catch (RuntimeException exception) {
            showStartupError(exception.getMessage());
            Platform.exit();
            return;
        }
        auth = new AuthService(repository);
        access = new AccessService();
        fleet = new FleetService(repository);
        orders = new OrderService(repository);
        routeService = new RouteService(repository);
        tracking = new TrackingService(repository);
        stage.setTitle("DCLS — Delivery Logistics Simulator");
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.setWidth(900);
        stage.setHeight(560);
        showLogin();
        stage.show();
    }

    private Repository configuredRepository() {
        String url = System.getenv("DCLS_DB_URL");
        if (url == null || url.isBlank()) return new MockRepository();
        return new JdbcRepository(url, nullToEmpty(System.getenv("DCLS_DB_USER")), nullToEmpty(System.getenv("DCLS_DB_PASSWORD")));
    }

    private void showLogin() {
        stopTracking();
        BorderPane page = new BorderPane();
        page.getStyleClass().add("login-background");
        HBox frame = new HBox(44);
        frame.setAlignment(Pos.CENTER);
        frame.setPadding(new Insets(40));

        VBox introduction = new VBox(20);
        introduction.setMaxWidth(410);
        Label brand = new Label("DCLS");
        brand.setStyle("-fx-font-size: 16px; -fx-font-weight: 800; -fx-text-fill: #1457c5;");
        Label title = new Label("Keep every delivery\nmoving with purpose.");
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: 700; -fx-text-fill: #172033;");
        Label body = new Label("A clear view of your fleet, routes, and orders — from dispatch to delivery.");
        body.setWrapText(true);
        body.getStyleClass().add("muted");
        body.setStyle("-fx-font-size: 15px;");
        VBox preview = new VBox(13,
                previewRow("Fleet readiness", "12 vehicles available", "#e8f5ef", "#17613f"),
                previewRow("Delivery in progress", "08 active shipments", "#eaf1ff", "#174c9a"),
                previewRow("Needs attention", "02 service reminders", "#fff4df", "#7a5100"));
        preview.setPadding(new Insets(17));
        preview.getStyleClass().add("panel");
        introduction.getChildren().addAll(brand, title, body, preview);

        VBox card = new VBox(15);
        card.setMaxWidth(370);
        card.setPadding(new Insets(30));
        card.getStyleClass().add("login-card");
        Label formTitle = new Label("Sign in to your workspace");
        formTitle.setStyle("-fx-font-size: 20px; -fx-font-weight: 700;");
        Label hint = new Label("Use your DCLS account to continue."); hint.getStyleClass().add("muted");
        TextField email = new TextField(DEMO_EMAIL); email.setPromptText("Email address"); email.setAccessibleText("Email address");
        PasswordField password = new PasswordField(); password.setText(DEMO_PASSWORD); password.setPromptText("Password"); password.setAccessibleText("Password");
        Label error = new Label(); error.setWrapText(true); error.setStyle("-fx-text-fill: #9b2c24;");
        Button submit = button("Sign in", "primary-button"); submit.setMaxWidth(Double.MAX_VALUE);
        Button useDemo = button("Use demo account", "secondary-button"); useDemo.setMaxWidth(Double.MAX_VALUE);
        Label demoInfo = new Label("Demo: admin@dcls.local  ·  demo1234"); demoInfo.getStyleClass().add("muted");
        Button register = button("Create a driver account", "secondary-button"); register.setMaxWidth(Double.MAX_VALUE);
        Runnable signIn = () -> {
            try {
                currentUser = auth.login(email.getText(), password.getText().toCharArray());
                showShell("dashboard");
            } catch (AppException exception) { error.setText(exception.getMessage()); }
        };
        submit.setOnAction(event -> signIn.run());
        password.setOnAction(event -> signIn.run());
        useDemo.setOnAction(event -> { email.setText(DEMO_EMAIL); password.setText(DEMO_PASSWORD); signIn.run(); });
        register.setOnAction(event -> registerDialog());
        card.getChildren().addAll(formTitle, hint, labeled("Email", email), labeled("Password", password), error, submit, useDemo,
                new Separator(), demoInfo, register);
        frame.getChildren().addAll(introduction, card);
        page.setCenter(frame);
        Scene scene = new Scene(page);
        scene.getStylesheets().add(getClass().getResource("/edu/pb/dcls/styles.css").toExternalForm());
        stage.setScene(scene);
    }

    private Node previewRow(String heading, String value, String fill, String color) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        Label dot = new Label("●"); dot.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 12px;");
        VBox text = new VBox(2, new Label(heading), new Label(value));
        text.getChildren().get(0).setStyle("-fx-font-weight: 700;");
        text.getChildren().get(1).getStyleClass().add("muted");
        Region spacer = new Region(); HBox.setHgrow(spacer, ALWAYS);
        Label status = new Label("Live"); status.setStyle("-fx-background-color: " + fill + "; -fx-text-fill: " + color + "; -fx-padding: 4 8; -fx-background-radius: 10; -fx-font-size: 11px;");
        row.getChildren().addAll(dot, text, spacer, status);
        return row;
    }

    private void registerDialog() {
        TextField name = new TextField(); name.setPromptText("Full name");
        TextField email = new TextField(); email.setPromptText("name@example.com");
        TextField phone = new TextField(); phone.setPromptText("Phone number");
        PasswordField password = new PasswordField(); password.setPromptText("At least 8 characters");
        VBox form = new VBox(10, labeled("Full name", name), labeled("Email", email), labeled("Phone", phone), labeled("Password", password));
        Optional<ButtonType> result = confirmDialog("Create driver account", form, "Create account");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            try {
                currentUser = auth.register(name.getText(), email.getText(), phone.getText(), password.getText().toCharArray());
                showShell("dashboard");
            } catch (AppException exception) { alert(Alert.AlertType.WARNING, "Account not created", exception.getMessage()); }
        }
    }

    private void showShell(String page) {
        if (currentUser == null) { showLogin(); return; }
        activePage = page;
        shell = new BorderPane(); shell.getStyleClass().add("workspace");
        nav = buildNav();
        shell.setLeft(nav);
        BorderPane content = new BorderPane();
        content.setTop(buildTopbar());
        pageHeading = new Label(); pageHeading.getStyleClass().add("page-title");
        shell.setCenter(content);
        showPage(page);
        Scene scene = new Scene(shell);
        scene.getStylesheets().add(getClass().getResource("/edu/pb/dcls/styles.css").toExternalForm());
        stage.setScene(scene);
        if (page.equals("tracking")) startTracking();
    }

    private VBox buildNav() {
        VBox sidebar = new VBox(7);
        sidebar.setPrefWidth(72); sidebar.setMinWidth(72); sidebar.setPadding(new Insets(15, 8, 8, 8));
        sidebar.getStyleClass().add("sidebar");
        Label mark = new Label("◇");
        mark.setMinSize(36, 36); mark.setAlignment(Pos.CENTER);
        mark.setStyle("-fx-background-color: #176df6; -fx-background-radius: 9; -fx-text-fill: white; -fx-font-size: 21px; -fx-font-weight: 800;");
        VBox markWrap = new VBox(mark); markWrap.setAlignment(Pos.CENTER); markWrap.setPadding(new Insets(0, 0, 17, 0));
        sidebar.getChildren().add(markWrap);
        addNav(sidebar, "dashboard", "▦", "Dashboard");
        addNav(sidebar, "users", "♙", "Users and roles");
        addNav(sidebar, "fleet", "▤", "Fleet");
        addNav(sidebar, "orders", "▣", "Orders and shipments");
        addNav(sidebar, "routes", "⌁", "Route planning");
        addNav(sidebar, "tracking", "◉", "Live tracking");
        Region spacer = new Region(); VBox.setVgrow(spacer, ALWAYS); sidebar.getChildren().add(spacer);
        Button settings = button("Setting  ›", "settings-button"); settings.setMaxWidth(Double.MAX_VALUE); settings.setAccessibleText("Settings and profile");
        settings.setOnAction(event -> profileDialog()); sidebar.getChildren().add(settings);
        return sidebar;
    }

    private void addNav(VBox sidebar, String page, String glyph, String accessibleName) {
        if (!access.canOpen(currentUser.role(), page)) return;
        Button button = new Button(glyph);
        button.setMinSize(44, 42); button.setMaxWidth(Double.MAX_VALUE); button.setTooltip(new Tooltip(accessibleName));
        button.setAccessibleText(accessibleName); button.setFocusTraversable(true); button.getStyleClass().add("nav-button");
        if (activePage.equals(page)) button.getStyleClass().add("active");
        button.setOnAction(event -> { stopTracking(); showPage(page); });
        sidebar.getChildren().add(button);
    }

    private Node buildTopbar() {
        HBox bar = new HBox(10); bar.setAlignment(Pos.CENTER_LEFT); bar.setPadding(new Insets(9, 24, 9, 24)); bar.getStyleClass().add("topbar");
        Label back = new Label("‹"); back.setStyle("-fx-font-size: 21px; -fx-text-fill: #172033;");
        Label title = new Label(activePage.equals("dashboard") ? "Dashboard" : pageLabel(activePage)); title.setStyle("-fx-font-size: 16px; -fx-font-weight: 700;");
        dataModeLabel = new Label(repository.persistent() ? "MySQL" : "Demo data");
        dataModeLabel.setStyle("-fx-text-fill: #7a8698; -fx-font-size: 11px;");
        Region spacer = new Region(); HBox.setHgrow(spacer, ALWAYS);
        MenuButton account = new MenuButton(currentUser.name() + "  ·  " + currentUser.role().label());
        account.getItems().addAll(menu("My profile", this::profileDialog), menu("Change password", this::passwordDialog), menu("Sign out", () -> { stopTracking(); currentUser = null; showLogin(); }));
        bar.getChildren().addAll(back, title, spacer, dataModeLabel, account);
        return bar;
    }

    private MenuItem menu(String title, Runnable action) { MenuItem item = new MenuItem(title); item.setOnAction(event -> action.run()); return item; }

    private void showPage(String page) {
        if (!access.canOpen(currentUser.role(), page)) page = "dashboard";
        activePage = page;
        if (shell == null) return;
        if (nav != null) { shell.setLeft(buildNav()); nav = (VBox) shell.getLeft(); }
        BorderPane content = (BorderPane) shell.getCenter();
        content.setTop(buildTopbar());
        Node view = switch (page) {
            case "users" -> usersPage();
            case "fleet" -> fleetPage();
            case "orders" -> ordersPage();
            case "routes" -> routesPage();
            case "tracking" -> trackingPage();
            default -> dashboardPage();
        };
        ScrollPane scroll = new ScrollPane(view); scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: #f6f8fb; -fx-background-color: #f6f8fb;");
        content.setCenter(scroll);
    }

    private VBox dashboardPage() {
        List<Shipment> shipmentRows = visibleShipments();
        List<Vehicle> vehicleRows = currentUser.role() == Role.DRIVER
                ? repository.vehicles().stream().filter(v -> currentUser.id().equals(v.driverId())).toList()
                : repository.vehicles();
        long active = shipmentRows.stream().filter(s -> s.status() == OrderStatus.IN_TRANSIT).count();
        long pending = shipmentRows.stream().filter(s -> s.status() == OrderStatus.PENDING).count();
        long completed = shipmentRows.stream().filter(s -> s.status() == OrderStatus.DELIVERED).count();
        VBox page = pageHeader("", ""); page.setSpacing(18); page.setPadding(new Insets(18, 20, 20, 58));
        HBox metrics = new HBox(18,
                metric("Vehicles", String.valueOf(vehicleRows.size()), ""),
                metric("Active deliveries", String.valueOf(active), ""),
                metric("Pending orders", String.valueOf(pending), ""),
                metric("Completed today", String.valueOf(completed), ""));
        for (Node node : metrics.getChildren()) HBox.setHgrow(node, ALWAYS);
        VBox mapPanel = panel(new Label("Operations map")); ((Label) mapPanel.getChildren().get(0)).getStyleClass().add("section-title");
        Canvas map = mapCanvas(455, 210);
        if (!repository.routes().isEmpty()) drawRoute(map, repository.routes().getFirst().stops(), null); else drawDashboardRoads(map);
        mapPanel.getChildren().add(map);
        VBox activity = panel(new Label("Active deliveries")); ((Label) activity.getChildren().get(0)).getStyleClass().add("section-title");
        HBox summary = new HBox(8, new Label("+4 vs yesterday"), new Region(), new Label(String.valueOf(active)));
        summary.getStyleClass().add("activity-summary"); HBox.setHgrow(summary.getChildren().get(1), ALWAYS); activity.getChildren().add(summary);
        activity.getChildren().add(activityChart());
        List<Shipment> latest = shipmentRows.stream().filter(s -> s.status() == OrderStatus.ASSIGNED || s.status() == OrderStatus.IN_TRANSIT)
                .sorted(Comparator.comparing(Shipment::createdAt).reversed()).limit(4).toList();
        for (Shipment shipment : latest) { HBox row = new HBox(8, new Label(shipment.id()), new Region(), new Label(etaFor(shipment))); row.getStyleClass().add("delivery-line"); HBox.setHgrow(row.getChildren().get(1), ALWAYS); activity.getChildren().add(row); }
        if (latest.isEmpty()) activity.getChildren().add(new Label("No active deliveries."));
        HBox content = new HBox(14, mapPanel, activity); HBox.setHgrow(mapPanel, ALWAYS); activity.setPrefWidth(264);
        page.getChildren().addAll(metrics, content);
        return page;
    }

    private void drawDashboardRoads(Canvas canvas) {
        GraphicsContext g = prepareMap(canvas); double w=canvas.getWidth(), h=canvas.getHeight();
        g.setStroke(javafx.scene.paint.Color.web("#d6e0e8")); g.setLineWidth(17);
        g.beginPath(); g.moveTo(w*.04,h*.25); g.bezierCurveTo(w*.30,h*.02,w*.50,h*.58,w*.70,h*.25); g.bezierCurveTo(w*.82,h*.12,w*.91,h*.32,w*.98,h*.50); g.stroke();
        g.setStroke(javafx.scene.paint.Color.WHITE); g.setLineWidth(6);
        g.beginPath(); g.moveTo(w*.05,h*.62); g.bezierCurveTo(w*.32,h*.37,w*.50,h*.76,w*.75,h*.46); g.bezierCurveTo(w*.88,h*.34,w*.92,h*.54,w*.98,h*.70); g.stroke();
        g.setStroke(javafx.scene.paint.Color.web("#dce9dc")); g.setLineWidth(18);
        g.beginPath(); g.moveTo(w*.02,h*.78); g.bezierCurveTo(w*.25,h*.52,w*.55,h*.95,w*.75,h*.68); g.bezierCurveTo(w*.83,h*.58,w*.92,h*.75,w*.98,h*.89); g.stroke();
        g.setStroke(javafx.scene.paint.Color.web("#176df6")); g.setLineWidth(3);
        g.beginPath(); g.moveTo(w*.08,h*.82); g.bezierCurveTo(w*.28,h*.71,w*.37,h*.48,w*.55,h*.40); g.bezierCurveTo(w*.73,h*.32,w*.81,h*.24,w*.94,h*.16); g.stroke();
        for(double[] p:new double[][]{{.08,.82},{.55,.40},{.94,.16}}){g.setFill(javafx.scene.paint.Color.web("#176df6"));g.fillOval(p[0]*w-6,p[1]*h-6,12,12);g.setStroke(javafx.scene.paint.Color.WHITE);g.setLineWidth(2);g.strokeOval(p[0]*w-6,p[1]*h-6,12,12);}
    }

    private Canvas activityChart() {
        Canvas canvas=new Canvas(224,74); GraphicsContext g=canvas.getGraphicsContext2D(); double[] values={.34,.43,.39,.55,.74,.86,.69,.92,.84,.94,.82,1};
        for(int i=0;i<values.length;i++){double x=4+i*18,bar=values[i]*56;g.setFill(i==values.length-1?javafx.scene.paint.Color.web("#315cf5"):javafx.scene.paint.Color.web("#e5ebff"));g.fillRoundRect(x,58-bar,14,bar,4,4);} return canvas;
    }

    private String etaFor(Shipment shipment) { TrackingPoint point=tracking.latestFor(shipment.id()); return point==null?"—":point.etaMinutes()+" min"; }

    private void addStatusSummary(VBox target, String label, long count, String color) {
        HBox row = new HBox(10); row.setAlignment(Pos.CENTER_LEFT);
        Label indicator = new Label("●"); indicator.setStyle("-fx-text-fill: " + color + ";");
        Label name = new Label(label); name.getStyleClass().add("muted");
        Region spacer = new Region(); HBox.setHgrow(spacer, ALWAYS);
        Label value = new Label(String.valueOf(count)); value.setStyle("-fx-font-weight: 700;");
        row.getChildren().addAll(indicator, name, spacer, value); target.getChildren().add(row);
    }

    private VBox fleetPage() {
        VBox page = pageHeader("Fleet & vehicles", "Manage vehicle availability, assignments, and service records.");
        TabPane tabs = new TabPane(); tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("Vehicles", vehiclesTab()));
        tabs.getTabs().add(new Tab("Maintenance", maintenanceTab()));
        tabs.getTabs().add(new Tab("Fuel records", fuelTab()));
        page.getChildren().add(tabs); VBox.setVgrow(tabs, ALWAYS);
        return page;
    }

    private Node vehiclesTab() {
        VBox content = new VBox(12); content.setPadding(new Insets(12, 0, 0, 0));
        HBox tools = new HBox(9); tools.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField(); search.setPromptText("Search plate, model, type, or driver"); search.setAccessibleText("Search fleet"); search.setPrefWidth(300);
        ChoiceBox<VehicleStatus> filter = new ChoiceBox<>(FXCollections.observableArrayList(VehicleStatus.values()));
        filter.getItems().add(0, null); filter.setValue(null); filter.setConverter(new StringConverter<>() { public String toString(VehicleStatus value) { return value == null ? "All statuses" : value.label(); } public VehicleStatus fromString(String value) { return null; } });
        Region gap = new Region(); HBox.setHgrow(gap, ALWAYS);
        Button add = button("Add vehicle", "primary-button"); add.setDisable(!access.canManageFleet(currentUser.role())); add.setOnAction(event -> vehicleDialog(null));
        tools.getChildren().addAll(search, filter, gap, add);
        TableView<Vehicle> table = vehicleTable();
        Runnable refresh = () -> {
            String q = search.getText() == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
            VehicleStatus status = filter.getValue();
            List<Vehicle> rows = repository.vehicles().stream().filter(v -> status == null || v.status() == status)
                    .filter(v -> q.isEmpty() || (v.plate() + " " + v.model() + " " + v.type() + " " + driverName(v.driverId())).toLowerCase(Locale.ROOT).contains(q))
                    .toList(); table.setItems(FXCollections.observableArrayList(rows));
        };
        search.textProperty().addListener((observable, oldValue, newValue) -> refresh.run()); filter.valueProperty().addListener((observable, oldValue, newValue) -> refresh.run());
        refresh.run(); table.setPrefHeight(360);
        HBox actions = new HBox(8);
        Button edit = button("Edit", "secondary-button"), status = button("Change status", "secondary-button"), remove = button("Delete", "danger-button");
        edit.setDisable(!access.canManageFleet(currentUser.role())); status.setDisable(!access.canManageFleet(currentUser.role())); remove.setDisable(currentUser.role() != Role.ADMIN);
        edit.setOnAction(event -> { if (table.getSelectionModel().getSelectedItem() != null) vehicleDialog(table.getSelectionModel().getSelectedItem()); });
        status.setOnAction(event -> { if (table.getSelectionModel().getSelectedItem() != null) vehicleStatusDialog(table.getSelectionModel().getSelectedItem()); });
        remove.setOnAction(event -> { Vehicle selected = table.getSelectionModel().getSelectedItem(); if (selected != null && confirm("Delete vehicle?", "This action is available only for vehicles without shipment history.")) { try { repository.deleteVehicle(selected.id()); refresh.run(); } catch (RuntimeException ex) { alert(Alert.AlertType.WARNING, "Vehicle is in use", "This vehicle is linked to an order. Set it to Unavailable instead."); } } });
        Label reminders = new Label(fleet.maintenanceDue().size() + " service reminder(s) based on current mileage"); reminders.getStyleClass().add("muted");
        actions.getChildren().addAll(edit, status, remove, new Region(), reminders);
        content.getChildren().addAll(tools, table, actions); VBox.setVgrow(table, ALWAYS);
        return content;
    }

    private TableView<Vehicle> vehicleTable() {
        TableView<Vehicle> table = new TableView<>(); table.setPlaceholder(new Label("No vehicles match this search."));
        table.getColumns().add(textCol("Plate", Vehicle::plate, 100));
        table.getColumns().add(textCol("Model", Vehicle::model, 160));
        table.getColumns().add(textCol("Type", Vehicle::type, 110));
        table.getColumns().add(textCol("Capacity", v -> String.format("%.0f kg", v.capacityKg()), 90));
        table.getColumns().add(textCol("Driver", v -> driverName(v.driverId()), 140));
        table.getColumns().add(textCol("Mileage", v -> String.format("%,.0f km", v.mileageKm()), 100));
        table.getColumns().add(textCol("Status", v -> v.status().label(), 140));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        return table;
    }

    private Node maintenanceTab() {
        VBox content = new VBox(12); content.setPadding(new Insets(12, 0, 0, 0));
        HBox tools = new HBox(8); tools.setAlignment(Pos.CENTER_LEFT);
        Button add = button("Record service", "primary-button"); add.setDisable(!access.canManageFleet(currentUser.role())); add.setOnAction(event -> maintenanceDialog());
        Label due = new Label(fleet.maintenanceDue().size() + " vehicle(s) are approaching service"); due.getStyleClass().add("muted"); tools.getChildren().addAll(add, due);
        TableView<MaintenanceRecord> table = new TableView<>(); table.setPlaceholder(new Label("No maintenance records yet."));
        table.getColumns().add(textCol("Vehicle", m -> vehiclePlate(m.vehicleId()), 110));
        table.getColumns().add(textCol("Date", m -> m.date().toString(), 100));
        table.getColumns().add(textCol("Mileage", m -> String.format("%,.0f km", m.mileageKm()), 110));
        table.getColumns().add(textCol("Service", MaintenanceRecord::description, 300));
        table.getColumns().add(textCol("Cost", m -> String.format("$%.2f", m.cost()), 110));
        table.setItems(FXCollections.observableArrayList(repository.maintenanceRecords())); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        content.getChildren().addAll(tools, table); VBox.setVgrow(table, ALWAYS); return content;
    }

    private Node fuelTab() {
        VBox content = new VBox(12); content.setPadding(new Insets(12, 0, 0, 0));
        Button add = button("Record refuel", "primary-button"); add.setDisable(!access.canManageFleet(currentUser.role())); add.setOnAction(event -> fuelDialog());
        TableView<FuelRecord> table = new TableView<>(); table.setPlaceholder(new Label("No fuel records yet."));
        table.getColumns().add(textCol("Vehicle", f -> vehiclePlate(f.vehicleId()), 110));
        table.getColumns().add(textCol("Date", f -> f.date().toString(), 100));
        table.getColumns().add(textCol("Mileage", f -> String.format("%,.0f km", f.mileageKm()), 110));
        table.getColumns().add(textCol("Amount", f -> String.format("%.1f L", f.litres()), 110));
        table.getColumns().add(textCol("Cost", f -> String.format("$%.2f", f.cost()), 110));
        table.setItems(FXCollections.observableArrayList(repository.fuelRecords())); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        content.getChildren().addAll(add, table); VBox.setVgrow(table, ALWAYS); return content;
    }

    private VBox ordersPage() {
        VBox page = pageHeader("Orders & shipments", "Follow each delivery from intake through customer confirmation.");
        HBox tools = new HBox(9); tools.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField(); search.setPromptText("Order ID, customer, phone, or address"); search.setPrefWidth(310); search.setAccessibleText("Search orders");
        ChoiceBox<OrderStatus> statusFilter = enumFilter(OrderStatus.values(), "All statuses");
        ChoiceBox<Priority> priorityFilter = enumFilter(Priority.values(), "All priorities");
        Region gap = new Region(); HBox.setHgrow(gap, ALWAYS);
        Button create = button("Create order", "primary-button"); create.setDisable(!access.canDispatch(currentUser.role())); create.setOnAction(event -> shipmentDialog(null));
        tools.getChildren().addAll(search, statusFilter, priorityFilter, gap, create);
        TableView<Shipment> table = shipmentTable();
        Runnable refresh = () -> {
            String q = search.getText() == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
            OrderStatus s = statusFilter.getValue(); Priority p = priorityFilter.getValue();
            List<Shipment> rows = repository.shipments().stream().filter(item -> currentUser.role() != Role.DRIVER || currentUser.id().equals(item.driverId()))
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
        boolean canDispatch = access.canDispatch(currentUser.role());
        edit.setDisable(!canDispatch); assign.setDisable(!canDispatch); advance.setDisable(!access.canUpdateDelivery(currentUser.role())); cancel.setDisable(!canDispatch);
        edit.setOnAction(event -> { Shipment selected = table.getSelectionModel().getSelectedItem(); if (selected != null) shipmentDialog(selected); });
        assign.setOnAction(event -> { Shipment selected = table.getSelectionModel().getSelectedItem(); if (selected != null) assignmentDialog(selected); });
        advance.setOnAction(event -> { Shipment selected = table.getSelectionModel().getSelectedItem(); if (selected != null) advanceShipment(selected, refresh); });
        cancel.setOnAction(event -> { Shipment selected = table.getSelectionModel().getSelectedItem(); if (selected != null && confirm("Cancel order", "Cancel " + selected.id() + "?")) { runAction(() -> orders.transition(selected, OrderStatus.CANCELLED, currentUser), "Order cancelled."); refresh.run(); } });
        actions.getChildren().addAll(edit, assign, advance, cancel);
        page.getChildren().addAll(tools, table, actions); VBox.setVgrow(table, ALWAYS); return page;
    }

    private TableView<Shipment> shipmentTable() {
        TableView<Shipment> table = new TableView<>(); table.setPlaceholder(new Label("No orders match this search."));
        table.getColumns().add(textCol("Order", Shipment::id, 105));
        table.getColumns().add(textCol("Customer", Shipment::customerName, 140));
        table.getColumns().add(textCol("Destination", Shipment::address, 210));
        table.getColumns().add(textCol("Priority", s -> s.priority().label(), 85));
        table.getColumns().add(textCol("Driver", s -> driverName(s.driverId()), 120));
        table.getColumns().add(textCol("Vehicle", s -> vehiclePlate(s.vehicleId()), 100));
        table.getColumns().add(textCol("Status", s -> s.status().label(), 100));
        table.getColumns().add(textCol("Received", s -> s.createdAt().format(DATE_TIME), 120));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        return table;
    }

    private VBox routesPage() {
        VBox page = pageHeader("Route planning", "Build a multi-stop route from local map points and estimate travel time.");
        HBox builder = new HBox(12);
        VBox controls = panel(new Label("Create a route"));
        ((Label) controls.getChildren().get(0)).getStyleClass().add("section-title");
        TextField name = new TextField(); name.setPromptText("Route name");
        ComboBox<String> place = new ComboBox<>(FXCollections.observableArrayList(routeService.placeNames())); place.setPromptText("Choose stop"); place.setMaxWidth(Double.MAX_VALUE);
        Button addStop = button("Add stop", "secondary-button");
        ListViewWithRemove draft = new ListViewWithRemove(routeStopsDraft);
        addStop.setOnAction(event -> { if (place.getValue() != null && !routeStopsDraft.contains(place.getValue())) routeStopsDraft.add(place.getValue()); });
        if (routeStopsDraft.isEmpty()) routeStopsDraft.add("DCLS Depot");
        ComboBox<User> driver = driverCombo(); driver.setPromptText("Assign driver (optional)");
        Button optimize = button("Optimize route", "primary-button");
        Label routeResult = new Label("Add at least one delivery stop after the depot."); routeResult.getStyleClass().add("muted"); routeResult.setWrapText(true);
        Canvas preview = mapCanvas(620, 330);
        optimize.setOnAction(event -> {
            try {
                RoutePlan plan = routeService.optimize(name.getText(), new ArrayList<>(routeStopsDraft), driver.getValue());
                routeResult.setText(String.format("%s  ·  %.1f km  ·  about %d minutes", plan.name(), plan.distanceKm(), plan.estimatedMinutes()));
                drawRoute(preview, plan.stops(), null);
                showPage("routes");
            } catch (AppException exception) { alert(Alert.AlertType.WARNING, "Route not created", exception.getMessage()); }
        });
        controls.getChildren().addAll(new Label("Name"), name, new Label("Add a depot and stops"), place, addStop, draft, driver, optimize, routeResult);
        controls.setPrefWidth(285); VBox.setVgrow(draft, ALWAYS);
        VBox mapPanel = panel(new Label("Route preview")); ((Label) mapPanel.getChildren().get(0)).getStyleClass().add("section-title"); mapPanel.getChildren().add(preview);
        builder.getChildren().addAll(controls, mapPanel); HBox.setHgrow(mapPanel, ALWAYS);
        VBox history = panel(new Label("Route history")); ((Label) history.getChildren().get(0)).getStyleClass().add("section-title");
        TableView<RoutePlan> table = new TableView<>(); table.setPlaceholder(new Label("No routes have been planned yet."));
        table.getColumns().add(textCol("Route", RoutePlan::name, 180));
        table.getColumns().add(textCol("Stops", r -> String.valueOf(r.stops().size()), 60));
        table.getColumns().add(textCol("Distance", r -> String.format("%.1f km", r.distanceKm()), 95));
        table.getColumns().add(textCol("Estimate", r -> r.estimatedMinutes() + " min", 95));
        table.getColumns().add(textCol("Driver", r -> driverName(r.driverId()), 140));
        table.getColumns().add(textCol("Created", r -> r.createdAt().format(DATE_TIME), 130));
        table.setItems(FXCollections.observableArrayList(repository.routes())); table.setPrefHeight(180); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> { if (selected != null) drawRoute(preview, selected.stops(), null); });
        repository.routes().stream().max(Comparator.comparing(RoutePlan::createdAt)).ifPresent(route -> drawRoute(preview, route.stops(), null));
        history.getChildren().add(table); page.getChildren().addAll(builder, history); return page;
    }

    private VBox trackingPage() {
        VBox page = pageHeader("Real-time tracking", "Live positions are simulated along assigned routes. No GPS data is used.");
        trackingCanvas = mapCanvas(700, 430);
        VBox map = panel(new Label("Fleet map  ·  Brunei-Muara")); ((Label) map.getChildren().get(0)).getStyleClass().add("section-title"); map.getChildren().add(trackingCanvas);
        VBox active = panel(new Label("Active deliveries")); ((Label) active.getChildren().get(0)).getStyleClass().add("section-title");
        trackingEta = new Label("Select a delivery to view its latest estimate."); trackingEta.getStyleClass().add("muted"); trackingEta.setWrapText(true);
        TableView<Shipment> table = new TableView<>(); table.setPlaceholder(new Label("No active deliveries."));
        table.getColumns().add(textCol("Order", Shipment::id, 100));
        table.getColumns().add(textCol("Driver", s -> driverName(s.driverId()), 130));
        table.getColumns().add(textCol("Destination", Shipment::address, 180));
        table.getColumns().add(textCol("Status", s -> s.status().label(), 100));
        List<Shipment> activeShipments = visibleShipments().stream().filter(s -> s.status() == OrderStatus.IN_TRANSIT).toList();
        table.setItems(FXCollections.observableArrayList(activeShipments)); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY); table.setPrefHeight(300);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> showEta(selected));
        active.getChildren().addAll(table, trackingEta);
        HBox content = new HBox(12, map, active); HBox.setHgrow(map, ALWAYS); active.setPrefWidth(410);
        page.getChildren().add(content);
        drawTracking();
        return page;
    }

    private VBox usersPage() {
        VBox page = pageHeader("Account & role management", "Manage access, roles, and account status.");
        HBox controls = new HBox(8); controls.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField(); search.setPromptText("Search name, email, or phone"); search.setPrefWidth(320);
        Button add = button("Register user", "primary-button"); add.setOnAction(event -> userDialog(null));
        controls.getChildren().addAll(search, add);
        TableView<User> table = new TableView<>(); table.setPlaceholder(new Label("No accounts match this search."));
        table.getColumns().add(textCol("Name", User::name, 170));
        table.getColumns().add(textCol("Email", User::email, 210));
        table.getColumns().add(textCol("Phone", User::phone, 130));
        table.getColumns().add(textCol("Role", u -> u.role().label(), 110));
        table.getColumns().add(textCol("Status", u -> u.active() ? "Active" : "Inactive", 100));
        Runnable refresh = () -> { String q = search.getText().toLowerCase(Locale.ROOT).trim(); table.setItems(FXCollections.observableArrayList(repository.users().stream().filter(u -> (u.name()+" "+u.email()+" "+u.phone()).toLowerCase(Locale.ROOT).contains(q)).toList())); };
        search.textProperty().addListener((o, old, value) -> refresh.run()); refresh.run(); table.setPrefHeight(390); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        HBox actions = new HBox(8); Button edit = button("Edit account", "secondary-button"), toggle = button("Activate / deactivate", "secondary-button");
        edit.setOnAction(event -> { User selected = table.getSelectionModel().getSelectedItem(); if (selected != null) userDialog(selected); });
        toggle.setOnAction(event -> { User selected = table.getSelectionModel().getSelectedItem(); if (selected != null && !selected.id().equals(currentUser.id())) { selected.setActive(!selected.active()); repository.saveUser(selected); refresh.run(); } });
        actions.getChildren().addAll(edit, toggle); page.getChildren().addAll(controls, table, actions); VBox.setVgrow(table, ALWAYS); return page;
    }

    private void profileDialog() {
        TextField name = new TextField(currentUser.name()); TextField email = new TextField(currentUser.email()); TextField phone = new TextField(currentUser.phone());
        VBox form = new VBox(10, labeled("Full name", name), labeled("Email", email), labeled("Phone", phone),
                new Label("Role: " + currentUser.role().label()));
        Optional<ButtonType> result = confirmDialog("My profile", form, "Save profile");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            if (name.getText().isBlank() || !email.getText().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) { alert(Alert.AlertType.WARNING, "Profile not saved", "Enter a name and valid email address."); return; }
            currentUser.updateProfile(name.getText().trim(), email.getText().trim(), phone.getText().trim()); repository.saveUser(currentUser); showPage(activePage);
        }
    }

    private void passwordDialog() {
        PasswordField current = new PasswordField(), next = new PasswordField(), confirm = new PasswordField();
        VBox form = new VBox(10, labeled("Current password", current), labeled("New password", next), labeled("Confirm new password", confirm));
        Optional<ButtonType> result = confirmDialog("Change password", form, "Update password");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            if (!next.getText().equals(confirm.getText())) { alert(Alert.AlertType.WARNING, "Password not changed", "The new passwords do not match."); return; }
            runAction(() -> auth.changePassword(currentUser, current.getText().toCharArray(), next.getText().toCharArray()), "Password updated.");
        }
    }

    private void userDialog(User existing) {
        TextField name = new TextField(existing == null ? "" : existing.name());
        TextField email = new TextField(existing == null ? "" : existing.email());
        TextField phone = new TextField(existing == null ? "" : existing.phone());
        ComboBox<Role> role = new ComboBox<>(FXCollections.observableArrayList(Role.values())); role.setValue(existing == null ? Role.DRIVER : existing.role());
        PasswordField password = new PasswordField(); password.setPromptText("At least 8 characters");
        VBox form = new VBox(10, labeled("Full name", name), labeled("Email", email), labeled("Phone", phone), labeled("Role", role));
        if (existing == null) form.getChildren().add(labeled("Temporary password", password));
        Optional<ButtonType> result = confirmDialog(existing == null ? "Register user" : "Edit user", form, existing == null ? "Create" : "Save");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            try {
                if (name.getText().isBlank() || !email.getText().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) throw new AppException("Enter a name and valid email address.");
                if (existing == null) {
                    if (password.getText().length() < 8) throw new AppException("Use a temporary password with at least 8 characters.");
                    Security.Credentials c = Security.credentials(password.getText().toCharArray());
                    repository.saveUser(new User(AppServices.id(), name.getText().trim(), email.getText().trim(), phone.getText().trim(), c.hash(), c.salt(), role.getValue(), true));
                } else {
                    existing.updateProfile(name.getText().trim(), email.getText().trim(), phone.getText().trim()); existing.setRole(role.getValue()); repository.saveUser(existing);
                }
                showPage("users");
            } catch (AppException exception) { alert(Alert.AlertType.WARNING, "User not saved", exception.getMessage()); }
        }
    }

    private void vehicleDialog(Vehicle existing) {
        TextField plate = new TextField(existing == null ? "" : existing.plate());
        TextField model = new TextField(existing == null ? "" : existing.model());
        TextField type = new TextField(existing == null ? "Light truck" : existing.type());
        TextField capacity = new TextField(existing == null ? "" : String.valueOf(existing.capacityKg()));
        TextField mileage = new TextField(existing == null ? "0" : String.valueOf(existing.mileageKm()));
        TextField nextService = new TextField(existing == null ? "5000" : String.valueOf(existing.nextServiceKm()));
        ComboBox<VehicleStatus> status = new ComboBox<>(FXCollections.observableArrayList(VehicleStatus.values())); status.setValue(existing == null ? VehicleStatus.AVAILABLE : existing.status());
        ComboBox<User> driver = driverCombo(); driver.setPromptText("Unassigned");
        if (existing != null) repository.users().stream().filter(u -> u.id().equals(existing.driverId())).findFirst().ifPresent(driver::setValue);
        GridPane form = formGrid(); addField(form, 0, "Plate number", plate); addField(form, 1, "Model", model); addField(form, 2, "Vehicle type", type);
        addField(form, 3, "Capacity (kg)", capacity); addField(form, 4, "Mileage (km)", mileage); addField(form, 5, "Next service (km)", nextService);
        addField(form, 6, "Status", status); addField(form, 7, "Assigned driver", driver);
        Optional<ButtonType> result = confirmDialog(existing == null ? "Add vehicle" : "Edit vehicle", form, existing == null ? "Add vehicle" : "Save changes");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            try {
                Vehicle vehicle = existing == null ? new Vehicle(AppServices.id(), plate.getText(), model.getText(), type.getText(), number(capacity.getText()), number(mileage.getText()), status.getValue(),
                        driver.getValue() == null ? null : driver.getValue().id(), number(nextService.getText())) : existing;
                if (existing != null) vehicle.update(plate.getText(), model.getText(), type.getText(), number(capacity.getText()), number(mileage.getText()), status.getValue(),
                        driver.getValue() == null ? null : driver.getValue().id(), number(nextService.getText()));
                fleet.save(vehicle); showPage("fleet");
            } catch (RuntimeException exception) { alert(Alert.AlertType.WARNING, "Vehicle not saved", readable(exception)); }
        }
    }

    private void vehicleStatusDialog(Vehicle vehicle) {
        ComboBox<VehicleStatus> status = new ComboBox<>(FXCollections.observableArrayList(VehicleStatus.values())); status.setValue(vehicle.status());
        Optional<ButtonType> result = confirmDialog("Change status · " + vehicle.plate(), status, "Save status");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) { runAction(() -> fleet.setStatus(vehicle, status.getValue()), "Vehicle status updated."); showPage("fleet"); }
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
        Optional<ButtonType> result = confirmDialog(existing == null ? "Create order" : "Edit order details", form, existing == null ? "Create order" : "Save changes");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            try {
                Shipment shipment = existing == null ? new Shipment("DCLS-" + (2400 + repository.shipments().size() + 1), customer.getText().trim(), contact.getText().trim(), address.getText().trim(), packageText.getText().trim(),
                        integer(quantity.getText()), number(weight.getText()), priority.getValue(), OrderStatus.PENDING, null, null, null, LocalDateTime.now(), null) : existing;
                if (existing != null) shipment.update(customer.getText().trim(), contact.getText().trim(), address.getText().trim(), packageText.getText().trim(), integer(quantity.getText()), number(weight.getText()), priority.getValue(), shipment.driverId(), shipment.vehicleId(), shipment.routeId());
                orders.save(shipment); showPage("orders");
            } catch (RuntimeException exception) { alert(Alert.AlertType.WARNING, "Order not saved", readable(exception)); }
        }
    }

    private void assignmentDialog(Shipment shipment) {
        ComboBox<User> driver = driverCombo();
        repository.users().stream().filter(user -> user.id().equals(shipment.driverId())).findFirst().ifPresent(driver::setValue);
        ComboBox<Vehicle> vehicle = new ComboBox<>(FXCollections.observableArrayList(repository.vehicles().stream().filter(v -> v.status() == VehicleStatus.AVAILABLE || v.id().equals(shipment.vehicleId())).toList()));
        vehicle.setConverter(new StringConverter<>() { public String toString(Vehicle v) { return v == null ? "" : v.plate() + " · " + v.model() + " · " + v.status().label(); } public Vehicle fromString(String value) { return null; } });
        repository.vehicle(shipment.vehicleId()).ifPresent(vehicle::setValue);
        ComboBox<RoutePlan> route = new ComboBox<>(FXCollections.observableArrayList(repository.routes())); route.setPromptText("No route assigned");
        route.setConverter(new StringConverter<>() { public String toString(RoutePlan r) { return r == null ? "" : r.name() + " · " + String.format("%.1f km", r.distanceKm()); } public RoutePlan fromString(String value) { return null; } });
        repository.routes().stream().filter(r -> r.id().equals(shipment.routeId())).findFirst().ifPresent(route::setValue);
        VBox form = new VBox(10, labeled("Driver", driver), labeled("Available vehicle", vehicle), labeled("Route", route));
        Optional<ButtonType> result = confirmDialog("Assign delivery · " + shipment.id(), form, "Assign");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            runAction(() -> orders.assign(shipment, driver.getValue(), vehicle.getValue(), route.getValue() == null ? null : route.getValue().id()), "Driver and vehicle assigned."); showPage("orders");
        }
    }

    private void advanceShipment(Shipment shipment, Runnable refresh) {
        OrderStatus next = switch (shipment.status()) {
            case ASSIGNED -> OrderStatus.IN_TRANSIT;
            case IN_TRANSIT -> OrderStatus.DELIVERED;
            case PENDING -> OrderStatus.ASSIGNED;
            default -> null;
        };
        if (next == null) { alert(Alert.AlertType.INFORMATION, "Order closed", "This order is already complete or cancelled."); return; }
        int ratingValue = 0;
        if (next == OrderStatus.DELIVERED) {
            ChoiceBox<Integer> rating = new ChoiceBox<>(FXCollections.observableArrayList(1, 2, 3, 4, 5)); rating.setValue(5);
            VBox form = new VBox(8, new Label("Confirm this delivery and record customer satisfaction."), labeled("Customer rating (1–5)", rating));
            Optional<ButtonType> result = confirmDialog("Delivery confirmation", form, "Confirm delivery");
            if (result.isEmpty() || result.get().getButtonData() != ButtonType.OK.getButtonData()) return;
            ratingValue = rating.getValue();
        }
        if (next == OrderStatus.ASSIGNED && shipment.driverId() == null) { assignmentDialog(shipment); return; }
        OrderStatus finalNext = next;
        int finalRating = ratingValue;
        runAction(() -> { if (finalRating > 0) shipment.setCustomerSatisfaction(finalRating); orders.transition(shipment, finalNext, currentUser); }, "Order moved to " + finalNext.label() + ".");
        refresh.run();
        if (activePage.equals("dashboard")) showPage("dashboard");
    }

    private void maintenanceDialog() {
        ComboBox<Vehicle> vehicle = vehicleCombo(); DatePicker date = new DatePicker(LocalDate.now());
        TextField mileage = new TextField("0"), details = new TextField(), cost = new TextField("0");
        VBox form = new VBox(10, labeled("Vehicle", vehicle), labeled("Date", date), labeled("Mileage (km)", mileage), labeled("Service details", details), labeled("Cost ($)", cost));
        Optional<ButtonType> result = confirmDialog("Record maintenance", form, "Save record");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            try { fleet.logMaintenance(vehicle.getValue(), date.getValue(), number(mileage.getText()), details.getText(), number(cost.getText())); showPage("fleet"); }
            catch (RuntimeException ex) { alert(Alert.AlertType.WARNING, "Service record not saved", readable(ex)); }
        }
    }

    private void fuelDialog() {
        ComboBox<Vehicle> vehicle = vehicleCombo(); DatePicker date = new DatePicker(LocalDate.now());
        TextField mileage = new TextField("0"), litres = new TextField(), cost = new TextField();
        VBox form = new VBox(10, labeled("Vehicle", vehicle), labeled("Date", date), labeled("Mileage (km)", mileage), labeled("Litres", litres), labeled("Cost ($)", cost));
        Optional<ButtonType> result = confirmDialog("Record refuel", form, "Save record");
        if (result.isPresent() && result.get().getButtonData() == ButtonType.OK.getButtonData()) {
            try { fleet.logFuel(vehicle.getValue(), date.getValue(), number(mileage.getText()), number(litres.getText()), number(cost.getText())); showPage("fleet"); }
            catch (RuntimeException ex) { alert(Alert.AlertType.WARNING, "Fuel record not saved", readable(ex)); }
        }
    }

    private void drawTracking() {
        if (trackingCanvas == null) return;
        GraphicsContext g = prepareMap(trackingCanvas);
        List<RoutePlan> activeRoutes = visibleShipments().stream().filter(s -> s.status() == OrderStatus.IN_TRANSIT && s.routeId() != null)
                .map(s -> repository.routes().stream().filter(r -> r.id().equals(s.routeId())).findFirst().orElse(null)).filter(java.util.Objects::nonNull).toList();
        if (!activeRoutes.isEmpty()) drawRouteOn(g, trackingCanvas.getWidth(), trackingCanvas.getHeight(), activeRoutes.get(0).stops(), null);
        List<Shipment> active = visibleShipments().stream().filter(s -> s.status() == OrderStatus.IN_TRANSIT).toList();
        for (Shipment shipment : active) {
            TrackingPoint point = tracking.latestFor(shipment.id());
            if (point == null && !activeRoutes.isEmpty()) {
                Stop depot = activeRoutes.get(0).stops().get(0); point = new TrackingPoint("current", shipment.vehicleId(), shipment.id(), depot.x(), depot.y(), activeRoutes.get(0).estimatedMinutes(), LocalDateTime.now());
            }
            if (point != null) drawVehicleMarker(g, trackingCanvas.getWidth(), trackingCanvas.getHeight(), point.x(), point.y(), vehiclePlate(shipment.vehicleId()));
        }
    }

    private void showEta(Shipment shipment) {
        if (shipment == null || trackingEta == null) return;
        TrackingPoint point = tracking.latestFor(shipment.id());
        trackingEta.setText(point == null ? "" : "ETA about " + point.etaMinutes() + " minutes  ·  last updated " + point.recordedAt().format(DATE_TIME));
    }

    private void startTracking() {
        if (trackingTimer != null) trackingTimer.stop();
        trackingTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
            for (Shipment shipment : visibleShipments()) if (shipment.status() == OrderStatus.IN_TRANSIT) {
                try { tracking.advance(shipment); } catch (RuntimeException exception) { stopTracking(); showError(exception.getMessage()); break; }
            }
            drawTracking();
            if (activePage.equals("dashboard")) showPage("dashboard");
        }));
        trackingTimer.setCycleCount(Timeline.INDEFINITE); trackingTimer.play();
    }

    private void stopTracking() { if (trackingTimer != null) { trackingTimer.stop(); trackingTimer = null; } }

    private Canvas mapCanvas(double width, double height) {
        Canvas canvas = new Canvas(width, height); canvas.getStyleClass().add("map-canvas"); prepareMap(canvas); return canvas;
    }

    private GraphicsContext prepareMap(Canvas canvas) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        g.setFill(javafx.scene.paint.Color.web("#eef3f6")); g.fillRect(0, 0, w, h);
        g.setFill(javafx.scene.paint.Color.web("#e3eee3")); g.fillOval(w * .70, h * .08, w * .25, h * .2); g.fillOval(w * .02, h * .66, w * .18, h * .22);
        g.setStroke(javafx.scene.paint.Color.web("#d7e1e5")); g.setLineWidth(18);
        g.strokeLine(w * .03, h * .82, w * .94, h * .14); g.strokeLine(w * .08, h * .28, w * .9, h * .77);
        g.strokeLine(w * .36, h * .98, w * .56, h * .02); g.strokeLine(w * .04, h * .52, w * .95, h * .48);
        g.setStroke(javafx.scene.paint.Color.web("#ffffff")); g.setLineWidth(2.5);
        g.strokeLine(w * .03, h * .82, w * .94, h * .14); g.strokeLine(w * .08, h * .28, w * .9, h * .77);
        g.strokeLine(w * .36, h * .98, w * .56, h * .02); g.strokeLine(w * .04, h * .52, w * .95, h * .48);
        g.setFill(javafx.scene.paint.Color.web("#657487")); g.setFont(javafx.scene.text.Font.font("Segoe UI", 11));
        g.fillText("Brunei-Muara  ·  simulated route map", 14, 20);
        return g;
    }

    private void drawRoute(Canvas canvas, List<Stop> stops, TrackingPoint moving) {
        GraphicsContext g = prepareMap(canvas); drawRouteOn(g, canvas.getWidth(), canvas.getHeight(), stops, moving);
    }

    private void drawRouteOn(GraphicsContext g, double width, double height, List<Stop> stops, TrackingPoint moving) {
        if (stops == null || stops.isEmpty()) return;
        g.setStroke(javafx.scene.paint.Color.web("#176df6")); g.setLineWidth(4);
        for (int i = 1; i < stops.size(); i++) {
            Stop a = stops.get(i - 1), b = stops.get(i);
            g.strokeLine(a.x() * width, a.y() * height, b.x() * width, b.y() * height);
        }
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i); double x = stop.x() * width, y = stop.y() * height;
            g.setFill(i == 0 ? javafx.scene.paint.Color.web("#17613f") : javafx.scene.paint.Color.web("#ffffff"));
            g.fillOval(x - 7, y - 7, 14, 14); g.setStroke(javafx.scene.paint.Color.web(i == 0 ? "#17613f" : "#176df6")); g.setLineWidth(3); g.strokeOval(x - 7, y - 7, 14, 14);
            g.setFill(javafx.scene.paint.Color.web("#172033")); g.fillText(stop.name(), x + 9, y - 8);
        }
        if (moving != null) drawVehicleMarker(g, width, height, moving.x(), moving.y(), "Vehicle");
    }

    private void drawVehicleMarker(GraphicsContext g, double width, double height, double x, double y, String label) {
        double px = x * width, py = y * height;
        g.setFill(javafx.scene.paint.Color.web("#1457c5")); g.fillOval(px - 9, py - 9, 18, 18);
        g.setStroke(javafx.scene.paint.Color.WHITE); g.setLineWidth(2); g.strokeOval(px - 9, py - 9, 18, 18);
        g.setFill(javafx.scene.paint.Color.web("#172033")); g.fillText(label, px + 12, py + 4);
    }

    private void registerRouteInOrder(Shipment shipment, RoutePlan route) { shipment.setAssignments(shipment.driverId(), shipment.vehicleId(), route.id()); repository.saveShipment(shipment); }

    private ComboBox<User> driverCombo() {
        ComboBox<User> combo = new ComboBox<>(FXCollections.observableArrayList(repository.users().stream().filter(u -> u.role() == Role.DRIVER && u.active()).toList()));
        combo.setConverter(new StringConverter<>() { public String toString(User user) { return user == null ? "" : user.name() + " · " + user.email(); } public User fromString(String value) { return null; } });
        combo.setMaxWidth(Double.MAX_VALUE); return combo;
    }

    private ComboBox<Vehicle> vehicleCombo() {
        ComboBox<Vehicle> combo = new ComboBox<>(FXCollections.observableArrayList(repository.vehicles()));
        combo.setConverter(new StringConverter<>() { public String toString(Vehicle vehicle) { return vehicle == null ? "" : vehicle.plate() + " · " + vehicle.model(); } public Vehicle fromString(String value) { return null; } });
        combo.setMaxWidth(Double.MAX_VALUE); if (!combo.getItems().isEmpty()) combo.setValue(combo.getItems().getFirst()); return combo;
    }

    private <E extends Enum<E>> ChoiceBox<E> enumFilter(E[] values, String allLabel) {
        ChoiceBox<E> choice = new ChoiceBox<>(FXCollections.observableArrayList(values)); choice.getItems().add(0, null); choice.setValue(null);
        choice.setConverter(new StringConverter<>() { public String toString(E value) { return value == null ? allLabel : value.toString(); } public E fromString(String value) { return null; } });
        return choice;
    }

    private <T> TableColumn<T, String> textCol(String heading, Function<T, String> value, double width) {
        TableColumn<T, String> column = new TableColumn<>(heading); column.setPrefWidth(width);
        column.setCellValueFactory(cell -> new SimpleStringProperty(Optional.ofNullable(value.apply(cell.getValue())).orElse("—")));
        return column;
    }

    private VBox pageHeader(String title, String subtitle) {
        VBox page = new VBox(17); page.setPadding(new Insets(26, 28, 28, 28)); page.getStyleClass().add("workspace");
        Label heading = new Label(title); heading.getStyleClass().add("page-title");
        Label description = new Label(subtitle); description.getStyleClass().add("muted");
        page.getChildren().addAll(new VBox(4, heading, description)); return page;
    }

    private Node metric(String title, String value, String caption) {
        VBox card = new VBox(8); card.setPadding(new Insets(16)); card.setMinWidth(140); card.getStyleClass().add("panel-tight");
        Label t = new Label(title); t.getStyleClass().add("metric-caption");
        Label v = new Label(value); v.getStyleClass().add("metric-value");
        Label c = new Label(caption); c.getStyleClass().add("muted"); c.setStyle("-fx-font-size: 11px;");
        card.getChildren().addAll(t, v, c); return card;
    }

    private VBox panel(Node heading) { VBox box = new VBox(13); box.setPadding(new Insets(16)); box.getStyleClass().add("panel"); box.getChildren().add(heading); return box; }
    private HBox labeled(String label, Node control) { VBox item = new VBox(5); Label text = new Label(label); text.getStyleClass().add("eyebrow"); item.getChildren().addAll(text, control); return new HBox(item); }
    private Button button(String text, String style) { Button button = new Button(text); button.getStyleClass().add(style); return button; }
    private GridPane formGrid() { GridPane grid = new GridPane(); grid.setHgap(12); grid.setVgap(10); ColumnConstraints left = new ColumnConstraints(145), right = new ColumnConstraints(280); grid.getColumnConstraints().addAll(left, right); return grid; }
    private void addField(GridPane grid, int row, String label, Node field) { grid.add(new Label(label), 0, row); grid.add(field, 1, row); if (field instanceof Region region) region.setMaxWidth(Double.MAX_VALUE); }

    private Optional<ButtonType> confirmDialog(String title, Node content, String action) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.initModality(Modality.WINDOW_MODAL); dialog.setTitle(title);
        dialog.getDialogPane().getButtonTypes().addAll(new ButtonType(action, ButtonType.OK.getButtonData()), ButtonType.CANCEL);
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().setPrefWidth(480);
        Scene scene = dialog.getDialogPane().getScene(); if (scene != null) scene.getStylesheets().add(getClass().getResource("/edu/pb/dcls/styles.css").toExternalForm());
        return dialog.showAndWait();
    }

    private boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO); alert.initOwner(stage); alert.setTitle(title); alert.setHeaderText(null);
        return alert.showAndWait().filter(ButtonType.YES::equals).isPresent();
    }

    private void runAction(Runnable action, String success) {
        try { action.run(); if (success != null) toast(success); }
        catch (RuntimeException exception) { showError(readable(exception)); }
    }

    private String readable(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "Something went wrong. Check the information and try again.";
        return message;
    }

    private void showError(String message) { alert(Alert.AlertType.ERROR, "Action could not be completed", message); }
    private void toast(String message) { if (dataModeLabel != null) { String before = dataModeLabel.getText(); dataModeLabel.setText(message); dataModeLabel.setStyle("-fx-text-fill: #17613f; -fx-font-size: 12px;"); javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(Duration.seconds(3)); pause.setOnFinished(event -> { if (dataModeLabel != null) dataModeLabel.setText(repository.persistent() ? "MySQL connected" : "Demo data"); }); pause.play(); } }
    private void alert(Alert.AlertType type, String title, String message) { Alert alert = new Alert(type, message, ButtonType.OK); alert.initOwner(stage); alert.setTitle(title); alert.setHeaderText(null); alert.showAndWait(); }
    private void showStartupError(String message) { Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK); alert.setTitle("DCLS could not start"); alert.setHeaderText("Check the MySQL configuration"); alert.showAndWait(); }

    private String driverName(String id) { return id == null ? "Unassigned" : repository.users().stream().filter(u -> u.id().equals(id)).map(User::name).findFirst().orElse("Unknown driver"); }
    private List<Shipment> visibleShipments() {
        return repository.shipments().stream().filter(shipment -> currentUser.role() != Role.DRIVER || currentUser.id().equals(shipment.driverId())).toList();
    }
    private String vehiclePlate(String id) { return id == null ? "Unassigned" : repository.vehicle(id).map(Vehicle::plate).orElse("Unknown vehicle"); }
    private String pageLabel(String page) { return switch (page) { case "users" -> "Users & roles"; case "fleet" -> "Fleet"; case "orders" -> "Orders"; case "routes" -> "Route planning"; case "tracking" -> "Live tracking"; default -> "Overview"; }; }
    private String greeting() { int hour = LocalDateTime.now().getHour(); return hour < 12 ? "morning" : hour < 18 ? "afternoon" : "evening"; }
    private String firstName(String name) { return name == null || name.isBlank() ? "there" : name.trim().split("\\s+")[0]; }
    private String nullToEmpty(String value) { return value == null ? "" : value; }
    private int integer(String text) { try { return Integer.parseInt(text.trim()); } catch (Exception exception) { throw new AppException("Enter a whole number."); } }
    private double number(String text) { try { double value = Double.parseDouble(text.trim()); if (value < 0) throw new NumberFormatException(); return value; } catch (Exception exception) { throw new AppException("Enter a number zero or greater."); } }

    @Override public void stop() { stopTracking(); if (repository != null) repository.close(); }

    private static final class ListViewWithRemove extends VBox {
        ListViewWithRemove(ObservableList<String> items) {
            javafx.scene.control.ListView<String> list = new javafx.scene.control.ListView<>(items); list.setPrefHeight(120);
            Button remove = new Button("Remove selected stop"); remove.getStyleClass().add("secondary-button");
            remove.setOnAction(event -> { String selected = list.getSelectionModel().getSelectedItem(); if (selected != null && !selected.equals("DCLS Depot")) items.remove(selected); });
            getChildren().addAll(list, remove); setSpacing(6);
        }
    }

    public static void main(String[] args) { launch(args); }
}
