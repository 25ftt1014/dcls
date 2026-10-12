package edu.pb.dcls;

import javafx.animation.KeyFrame;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static edu.pb.dcls.AppServices.*;
import static edu.pb.dcls.Domain.*;
import static edu.pb.dcls.MapRenderer.*;
import static edu.pb.dcls.UiFactory.*;
import static javafx.scene.layout.Priority.ALWAYS;

/** Desktop client for the Delivery Logistics Simulator. */
public final class DclsApp extends Application {
    static final String DEMO_EMAIL = "admin@dcls.local";
    static final String DEMO_PASSWORD = "demo1234";
    static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM, HH:mm");

    Stage stage;
    Repository repository;
    AuthService auth;
    AccessService access;
    FleetService fleet;
    OrderService orders;
    RouteService routeService;
    TrackingService tracking;
    User currentUser;
    private BorderPane shell;
    private VBox nav;
    private Label pageHeading;
    Label dataModeLabel;
    String activePage = "dashboard";
    String fleetRecordsView = "vehicles";
    private Timeline trackingTimer;
    Canvas trackingCanvas;
    Label trackingEta;
    final ObservableList<String> routeStopsDraft = FXCollections.observableArrayList();
    private Timeline signInRipple;
    private Runnable pendingSignIn;

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
        HBox frame = new HBox(0);
        frame.setAlignment(Pos.CENTER);
        frame.setMaxSize(742, 432);
        frame.setPrefSize(742, 432);
        VBox introduction = new VBox(0); introduction.setPrefWidth(404); introduction.setMinWidth(404); introduction.setPadding(new Insets(24, 50, 24, 50));
        introduction.getStyleClass().add("login-brand-panel");
        Label brand = new Label("Trackify"); brand.setStyle("-fx-font-size: 21px;-fx-font-weight:800;-fx-text-fill:white;");
        Label subtitle = new Label("Delivery Logistics Simulator"); subtitle.setStyle("-fx-font-size:11px;-fx-text-fill:#d1d9eb;");
        VBox brandLine = new VBox(2, brand, subtitle);
        Region brandGap = new Region(); VBox.setVgrow(brandGap, ALWAYS);
        VBox statement = new VBox(3); statement.setAlignment(Pos.CENTER);
        Label title = new Label("MOVE SMARTER"); title.setStyle("-fx-font-size:22px;-fx-font-weight:800;-fx-text-fill:white;");
        Label body = new Label("Plan, dispatch, and monitor every delivery."); body.setStyle("-fx-font-size:10px;-fx-text-fill:#d1d9eb;");
        statement.getChildren().addAll(title, body);
        Region brandGap2 = new Region(); VBox.setVgrow(brandGap2, ALWAYS);
        introduction.getChildren().addAll(brandLine, brandGap, statement, brandGap2);

        VBox card = new VBox(13);
        card.setPrefWidth(338); card.setMinWidth(338);
        card.setPadding(new Insets(35, 30, 28, 30));
        card.getStyleClass().add("login-card");
        Label formTitle = new Label("Welcome back"); formTitle.setStyle("-fx-font-size:20px;-fx-font-weight:700;-fx-text-fill:#172033;");
        Label hint = new Label("Sign in to continue"); hint.getStyleClass().add("muted");
        TextField email = new TextField(DEMO_EMAIL); email.setPromptText("Email address"); email.setAccessibleText("Email address");
        PasswordField password = new PasswordField(); password.setText(DEMO_PASSWORD); password.setPromptText("Password"); password.setAccessibleText("Password");
        CheckBox remember = new CheckBox("Remember me"); remember.getStyleClass().add("muted");
        Button forgot = new Button("Forgot password?"); forgot.getStyleClass().add("link-button");
        HBox loginOptions = new HBox(8, remember, new Region(), forgot); loginOptions.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(loginOptions.getChildren().get(1), ALWAYS);
        Label error = new Label(); error.setWrapText(true); error.setStyle("-fx-text-fill: #9b2c24;");
        Button submit = button("Sign in", "primary-button"); submit.setMaxWidth(Double.MAX_VALUE);
        Label demoInfo = new Label("Demo: admin@dcls.local  ·  demo1234"); demoInfo.getStyleClass().add("muted"); demoInfo.setStyle("-fx-font-size:10px;");
        Button register = new Button("Create a new account"); register.getStyleClass().add("link-button"); register.setMaxWidth(Double.MAX_VALUE);
        Runnable signIn = () -> {
            pendingSignIn = null;
            submit.setDisable(true);
            submit.setText("Signing in…");
            try {
                currentUser = auth.login(email.getText(), password.getText().toCharArray());
            } catch (AppException exception) {
                error.setText(exception.getMessage());
                submit.setDisable(false);
                submit.setText("Sign in");
                return;
            } catch (RuntimeException exception) {
                exception.printStackTrace();
                error.setText("Sign in is temporarily unavailable. Please try again.");
                submit.setDisable(false);
                submit.setText("Sign in");
                return;
            }
            try {
                showShell("dashboard");
            } catch (RuntimeException exception) {
                exception.printStackTrace();
                currentUser = null;
                error.setText("Your account was verified, but the dashboard could not be opened. Please restart the app.");
                submit.setDisable(false);
                submit.setText("Sign in");
            }
        };
        submit.setOnAction(event -> {
            if (submit.isDisabled()) return;
            pendingSignIn = signIn;
            playRipple(submit);
        });
        password.setOnAction(event -> submit.fire());
        register.setOnAction(event -> registerDialog());
        forgot.setOnAction(event -> alert(Alert.AlertType.INFORMATION, "Password reset", "For this local demo, ask an administrator to reset your account password."));
        card.getChildren().addAll(formTitle, hint, new Region(), labeled("EMAIL", email), labeled("PASSWORD", password), loginOptions, error, demoInfo, submit, register);
        frame.getChildren().addAll(introduction, card);
        page.setCenter(frame);
        Scene scene = new Scene(page);
        scene.getStylesheets().add(getClass().getResource("/edu/pb/dcls/styles.css").toExternalForm());
        stage.setScene(scene);
    }

    private void registerDialog() {
        TextField name = new TextField(); name.setPromptText("Full name");
        TextField email = new TextField(); email.setPromptText("name@example.com");
        TextField phone = new TextField(); phone.setPromptText("Phone number");
        PasswordField password = new PasswordField(); password.setPromptText("At least 8 characters");
        Label heading = new Label("Create your account"); heading.setStyle("-fx-font-size:20px;-fx-font-weight:700;-fx-text-fill:#172033;");
        Label description = new Label("Join Trackify to manage your deliveries."); description.getStyleClass().add("muted");
        Label error = new Label(); error.setWrapText(true); error.setStyle("-fx-text-fill:#9b2c24;");
        Button create = button("Create account", "primary-button"); create.setMaxWidth(Double.MAX_VALUE);
        Button back = new Button("Already have an account? Sign in"); back.getStyleClass().add("link-button"); back.setMaxWidth(Double.MAX_VALUE);
        VBox form = new VBox(12, heading, description, new Region(), labeled("FULL NAME", name), labeled("EMAIL", email), labeled("PHONE NUMBER", phone), labeled("PASSWORD", password), error, create, back);
        form.setPadding(new Insets(24)); form.setPrefWidth(338); form.setMinWidth(338); form.getStyleClass().add("login-card");
        Stage registration = new Stage(); registration.initOwner(stage); registration.initModality(Modality.WINDOW_MODAL); registration.setTitle("Create your account");
        HBox frame = new HBox(0); frame.setAlignment(Pos.CENTER); frame.setMaxSize(742, 432); frame.setPrefSize(742, 432);
        VBox introduction = new VBox(0); introduction.setPrefWidth(404); introduction.setMinWidth(404); introduction.setPadding(new Insets(24, 32, 24, 32)); introduction.getStyleClass().add("login-brand-panel");
        Label brand = new Label("Trackify"); brand.setStyle("-fx-font-size:21px;-fx-font-weight:800;-fx-text-fill:white;");
        Label subtitle = new Label("Delivery Logistics Simulator"); subtitle.setStyle("-fx-font-size:11px;-fx-text-fill:#d1d9eb;");
        Label slogan = new Label("MOVE SMARTER"); slogan.setStyle("-fx-font-size:22px;-fx-font-weight:800;-fx-text-fill:white;");
        Label sloganText = new Label("Plan, dispatch, and monitor every delivery."); sloganText.setWrapText(true); sloganText.setStyle("-fx-font-size:10px;-fx-text-fill:#d1d9eb;");
        Region topGap = new Region(); VBox.setVgrow(topGap, ALWAYS); Region bottomGap = new Region(); VBox.setVgrow(bottomGap, ALWAYS);
        VBox message = new VBox(4, slogan, sloganText); message.setAlignment(Pos.CENTER);
        introduction.getChildren().addAll(new VBox(2, brand, subtitle), topGap, message, bottomGap);
        frame.getChildren().addAll(introduction, form);
        BorderPane page = new BorderPane(); page.getStyleClass().add("login-background"); page.setCenter(frame);
        Scene scene = new Scene(page, 900, 560); scene.getStylesheets().add(getClass().getResource("/edu/pb/dcls/styles.css").toExternalForm()); registration.setScene(scene);
        Runnable submit = () -> {
            try {
                currentUser = auth.register(name.getText(), email.getText(), phone.getText(), password.getText().toCharArray());
                registration.close(); showShell("dashboard");
            } catch (AppException exception) { error.setText(exception.getMessage()); }
        };
        create.setOnAction(event -> submit.run()); password.setOnAction(event -> submit.run());
        back.setOnAction(event -> registration.close());
        registration.showAndWait();
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

    // Monochrome icon silhouettes (Material Symbols, 24px viewBox) used in the nav rail.
    private static final String IC_DASHBOARD = "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z";
    private static final String IC_USERS = "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z";
    private static final String IC_FLEET = "M20 8h-3V4H3c-1.1 0-2 .9-2 2v11h2c0 1.66 1.34 3 3 3s3-1.34 3-3h6c0 1.66 1.34 3 3 3s3-1.34 3-3h2v-5l-3-4zM6 18.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm13.5-9l1.96 2.5H17V9.5h2.5zm-1.5 9c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z";
    private static final String IC_ORDERS = "M20 2H4c-1.1 0-2 .9-2 2v3.01c0 .72.43 1.34 1 1.69V20c0 1.1 1.1 2 2 2h14c.9 0 2-.9 2-2V8.7c.57-.35 1-.97 1-1.69V4c0-1.1-.9-2-2-2zm-5 12H9v-2h6v2zm5-7H4V4h16v3z";
    private static final String IC_ROUTES = "M20.5 3l-.16.03L15 5.1 9 3 3.36 4.9c-.21.07-.36.25-.36.48V20.5c0 .28.22.5.5.5l.16-.03L9 18.9l6 2.1 5.64-1.9c.21-.07.36-.25.36-.48V3.5c0-.28-.22-.5-.5-.5zM15 19l-6-2.11V5l6 2.11V19z";
    private static final String IC_TRACKING = "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z";

    private VBox buildNav() {
        VBox sidebar = new VBox(4);
        sidebar.setPrefWidth(204); sidebar.setMinWidth(204); sidebar.setPadding(new Insets(16, 12, 12, 12));
        sidebar.getStyleClass().add("sidebar");

        // Brand: logo + wordmark
        HBox brand = new HBox(10); brand.setAlignment(Pos.CENTER_LEFT); brand.setPadding(new Insets(2, 4, 14, 6));
        InputStream logo = getClass().getResourceAsStream("/edu/pb/dcls/Logo.svg");
        if (logo != null) {
            ImageView mark = new ImageView(new Image(logo));
            mark.setFitWidth(30); mark.setFitHeight(30); mark.setPreserveRatio(true);
            brand.getChildren().add(mark);
        }
        Label wordmark = new Label("Trackify"); wordmark.getStyleClass().add("brand-word");
        brand.getChildren().add(wordmark);
        sidebar.getChildren().add(brand);

        Label menuHeader = new Label("MENU"); menuHeader.getStyleClass().add("nav-section");
        sidebar.getChildren().add(menuHeader);

        addNav(sidebar, "dashboard", IC_DASHBOARD, "Dashboard");
        addNav(sidebar, "users", IC_USERS, "Users & roles");
        addNav(sidebar, "fleet", IC_FLEET, "Fleet");
        addNav(sidebar, "orders", IC_ORDERS, "Orders");
        addNav(sidebar, "routes", IC_ROUTES, "Route planning");
        addNav(sidebar, "tracking", IC_TRACKING, "Live tracking");

        Region spacer = new Region(); VBox.setVgrow(spacer, ALWAYS); sidebar.getChildren().add(spacer);

        // Signed-in user identity
        Label who = new Label(currentUser.name()); who.getStyleClass().add("nav-user-name");
        Label role = new Label(currentUser.role().label()); role.getStyleClass().add("nav-user-role");
        VBox identity = new VBox(1, who, role); identity.setPadding(new Insets(0, 0, 8, 8));
        sidebar.getChildren().add(identity);

        MenuButton settings = new MenuButton("Settings"); settings.setMaxWidth(Double.MAX_VALUE); settings.setAccessibleText("Settings and profile"); settings.getStyleClass().add("settings-button");
        settings.getItems().addAll(menu("My profile", this::profileDialog), menu("Change password", this::passwordDialog), menu("Sign out", () -> { stopTracking(); currentUser = null; showLogin(); }));
        sidebar.getChildren().add(settings);
        return sidebar;
    }

    private void addNav(VBox sidebar, String page, String svgIcon, String label) {
        if (!access.canOpen(currentUser.role(), page)) return;
        Button button = new Button(label, navIcon(svgIcon));
        button.setGraphicTextGap(12); button.setMaxWidth(Double.MAX_VALUE); button.setAlignment(Pos.CENTER_LEFT);
        button.setAccessibleText(label); button.setFocusTraversable(true); button.getStyleClass().add("nav-button");
        if (activePage.equals(page)) button.getStyleClass().add("active");
        button.setOnAction(event -> { stopTracking(); showPage(page); });
        sidebar.getChildren().add(button);
    }

    /** A crisp monochrome vector icon that recolours with the nav state via CSS (.nav-icon). */
    private Region navIcon(String svg) {
        SVGPath path = new SVGPath(); path.setContent(svg);
        Region icon = new Region();
        icon.setShape(path);
        icon.setMinSize(18, 18); icon.setPrefSize(18, 18); icon.setMaxSize(18, 18);
        icon.getStyleClass().add("nav-icon");
        return icon;
    }

    private Node buildTopbar() {
        HBox bar = new HBox(10); bar.setAlignment(Pos.CENTER_LEFT); bar.setPadding(new Insets(9, 24, 9, 24)); bar.getStyleClass().add("topbar");
        Label title = new Label(activePage.equals("dashboard") ? "Dashboard" : pageLabel(activePage)); title.setStyle("-fx-font-size: 16px; -fx-font-weight: 700;");
        // Status chip on the right: shows the data mode, and briefly flashes action feedback (toasts).
        dataModeLabel = new Label(repository.persistent() ? "MySQL connected" : "Demo data");
        dataModeLabel.getStyleClass().add("mode-chip");
        Region spacer = new Region(); HBox.setHgrow(spacer, ALWAYS);
        bar.getChildren().addAll(title, spacer, dataModeLabel);
        return bar;
    }

    MenuItem menu(String title, Runnable action) { MenuItem item = new MenuItem(title); item.setOnAction(event -> action.run()); return item; }

    void showPage(String page) {
        if (!access.canOpen(currentUser.role(), page)) page = "dashboard";
        activePage = page;
        if (shell == null) return;
        if (nav != null) { shell.setLeft(buildNav()); nav = (VBox) shell.getLeft(); }
        BorderPane content = (BorderPane) shell.getCenter();
        content.setTop(buildTopbar());
        Node view = switch (page) {
            case "users" -> new UsersView(this).page();
            case "fleet" -> new FleetView(this).page();
            case "orders" -> new OrdersView(this).page();
            case "routes" -> new RoutesView(this).page();
            case "tracking" -> new TrackingView(this).page();
            default -> new DashboardView(this).page();
        };
        ScrollPane scroll = new ScrollPane(view); scroll.setFitToWidth(true); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setStyle("-fx-background: #f6f8fb; -fx-background-color: #f6f8fb;");
        content.setCenter(scroll);
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

    void drawTracking() {
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

    void showEta(Shipment shipment) {
        if (shipment == null || trackingEta == null) return;
        TrackingPoint point = tracking.latestFor(shipment.id());
        trackingEta.setText(point == null ? "" : "ETA about " + point.etaMinutes() + " minutes  ·  last updated " + point.recordedAt().format(DATE_TIME));
    }

    void startTracking() {
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

    void stopTracking() { if (trackingTimer != null) { trackingTimer.stop(); trackingTimer = null; } }

    ComboBox<User> driverCombo() {
        ComboBox<User> combo = new ComboBox<>(FXCollections.observableArrayList(repository.users().stream().filter(u -> u.role() == Role.DRIVER && u.active()).toList()));
        combo.setConverter(new StringConverter<>() { public String toString(User user) { return user == null ? "" : user.name() + " · " + user.email(); } public User fromString(String value) { return null; } });
        combo.setMaxWidth(Double.MAX_VALUE); return combo;
    }

    ComboBox<Vehicle> vehicleCombo() {
        ComboBox<Vehicle> combo = new ComboBox<>(FXCollections.observableArrayList(repository.vehicles()));
        combo.setConverter(new StringConverter<>() { public String toString(Vehicle vehicle) { return vehicle == null ? "" : vehicle.plate() + " · " + vehicle.model(); } public Vehicle fromString(String value) { return null; } });
        combo.setMaxWidth(Double.MAX_VALUE); if (!combo.getItems().isEmpty()) combo.setValue(combo.getItems().getFirst()); return combo;
    }

    private void playRipple(Button target) {
        if (signInRipple != null) signInRipple.stop();
        ScaleTransition press = new ScaleTransition(Duration.millis(90), target);
        press.setFromX(1); press.setFromY(1); press.setToX(.975); press.setToY(.94);
        ScaleTransition release = new ScaleTransition(Duration.millis(150), target);
        release.setFromX(.975); release.setFromY(.94); release.setToX(1); release.setToY(1);
        signInRipple = new Timeline(new KeyFrame(Duration.ZERO), new KeyFrame(Duration.millis(90)));
        signInRipple.setOnFinished(event -> {
            release.playFromStart();
            Runnable action = pendingSignIn;
            if (action != null) action.run();
        });
        press.playFromStart();
        signInRipple.playFromStart();
    }
    Optional<ButtonType> confirmDialog(String title, Node content, String action) {
        Dialog<ButtonType> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.initModality(Modality.WINDOW_MODAL); dialog.setTitle(title);
        dialog.getDialogPane().getButtonTypes().addAll(new ButtonType(action, ButtonType.OK.getButtonData()), ButtonType.CANCEL);
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().setPrefWidth(480);
        Scene scene = dialog.getDialogPane().getScene(); if (scene != null) scene.getStylesheets().add(getClass().getResource("/edu/pb/dcls/styles.css").toExternalForm());
        return dialog.showAndWait();
    }

    boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO); alert.initOwner(stage); alert.setTitle(title); alert.setHeaderText(null);
        return alert.showAndWait().filter(ButtonType.YES::equals).isPresent();
    }

    void runAction(Runnable action, String success) {
        try { action.run(); if (success != null) toast(success); }
        catch (RuntimeException exception) { showError(readable(exception)); }
    }

    String readable(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "Something went wrong. Check the information and try again.";
        return message;
    }

    void showError(String message) { alert(Alert.AlertType.ERROR, "Action could not be completed", message); }
    void toast(String message) {
        if (dataModeLabel == null) return;
        dataModeLabel.setText(message);
        if (!dataModeLabel.getStyleClass().contains("mode-chip-success")) dataModeLabel.getStyleClass().add("mode-chip-success");
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(Duration.seconds(3));
        pause.setOnFinished(event -> {
            if (dataModeLabel == null) return;
            dataModeLabel.getStyleClass().remove("mode-chip-success");
            dataModeLabel.setText(repository.persistent() ? "MySQL connected" : "Demo data");
        });
        pause.play();
    }
    void alert(Alert.AlertType type, String title, String message) { Alert alert = new Alert(type, message, ButtonType.OK); alert.initOwner(stage); alert.setTitle(title); alert.setHeaderText(null); alert.showAndWait(); }
    private void showStartupError(String message) { Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK); alert.setTitle("DCLS could not start"); alert.setHeaderText("Check the MySQL configuration"); alert.showAndWait(); }

    String driverName(String id) { return id == null ? "Unassigned" : repository.users().stream().filter(u -> u.id().equals(id)).map(User::name).findFirst().orElse("Unknown driver"); }
    List<Shipment> visibleShipments() {
        return repository.shipments().stream().filter(shipment -> currentUser.role() != Role.DRIVER || currentUser.id().equals(shipment.driverId())).toList();
    }
    String vehiclePlate(String id) { return id == null ? "Unassigned" : repository.vehicle(id).map(Vehicle::plate).orElse("Unknown vehicle"); }
    private String pageLabel(String page) { return switch (page) { case "users" -> "Users & roles"; case "fleet" -> "Fleet"; case "orders" -> "Orders"; case "routes" -> "Route planning"; case "tracking" -> "Live tracking"; default -> "Overview"; }; }
    private String nullToEmpty(String value) { return value == null ? "" : value; }
    int integer(String text) { try { return Integer.parseInt(text.trim()); } catch (Exception exception) { throw new AppException("Enter a whole number."); } }
    double number(String text) { try { double value = Double.parseDouble(text.trim()); if (value < 0) throw new NumberFormatException(); return value; } catch (Exception exception) { throw new AppException("Enter a number zero or greater."); } }

    @Override public void stop() { stopTracking(); if (repository != null) repository.close(); }

    public static void main(String[] args) { launch(args); }
}
