package edu.pb.dcls;

import javafx.animation.KeyFrame;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
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
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.File;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

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
    AutoRouteService autoRoutes;
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
    String routeNameInput = "";
    final ObservableList<Stop> routeStopsDraft = FXCollections.observableArrayList();
    int routePageGen;
    int customPinCount = 0;
    List<Stop> previewStops = List.of();
    List<double[]> trackingFrame = List.of();
    final Map<String, RoutePlan> trackingRoutes = new LinkedHashMap<>();
    final Map<String, List<TrackingPoint>> trackingTrails = new LinkedHashMap<>();
    MapController trackingMaps;
    VBox trackingLegend;
    VBox focusChipBox;
    String focusedShipmentId;
    private Timeline signInRipple;
    private Runnable pendingSignIn;
    private String loginNotice;

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
        autoRoutes = new AutoRouteService(repository, routeService, tracking);
        stage.setTitle("DCLS — Delivery Logistics Simulator");
        stage.setMinWidth(1080);
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
        if (loginNotice != null && !loginNotice.isBlank()) {
            Label notice = new Label(loginNotice);
            notice.setWrapText(true);
            notice.setMaxWidth(Double.MAX_VALUE);
            notice.getStyleClass().add("notice");
            card.getChildren().add(card.getChildren().indexOf(submit), notice);
            loginNotice = null;
        }
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
        Label description = new Label("Create an account, then wait for an administrator to approve it and set your role."); description.setWrapText(true); description.getStyleClass().add("muted");
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
                auth.register(name.getText(), email.getText(), phone.getText(), password.getText().toCharArray());
                registration.close();
                loginNotice = "Account created. An administrator must approve it and set your role before you can sign in.";
                showLogin();
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
        VBox sidebar = new VBox(9);
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
        if (trackingMaps == null) return;
        List<Shipment> active = visibleShipments().stream().filter(s -> s.status() == OrderStatus.IN_TRANSIT).toList();
        Map<String, List<Shipment>> byVehicle = active.stream()
                .filter(s -> s.vehicleId() != null)
                .collect(Collectors.groupingBy(Shipment::vehicleId, LinkedHashMap::new, Collectors.toList()));
        List<double[]> frame = new ArrayList<>();
        trackingRoutes.clear();
        trackingTrails.clear();
        for (Map.Entry<String, List<Shipment>> entry : byVehicle.entrySet()) {
            entry.getValue().stream().map(Shipment::routeId).filter(java.util.Objects::nonNull).findFirst()
                    .flatMap(routeId -> repository.routes().stream().filter(r -> r.id().equals(routeId)).findFirst())
                    .ifPresent(route -> {
                        trackingRoutes.put(entry.getKey(), route);
                        for (Stop stop : route.stops()) frame.add(ll(stop.x(), stop.y()));
                    });
            List<TrackingPoint> trail = repository.trackingHistory().stream()
                    .filter(p -> entry.getKey().equals(p.vehicleId()))
                    .sorted(Comparator.comparing(TrackingPoint::recordedAt))
                    .toList();
            List<TrackingPoint> recent = trail.subList(Math.max(0, trail.size() - 40), trail.size());
            trackingTrails.put(entry.getKey(), recent);
            for (TrackingPoint p : recent) frame.add(ll(p.x(), p.y()));
        }
        trackingFrame = frame;
        if (focusedShipmentId != null && trackingMaps != null) {
            Shipment focused = focusedShipment();
            TrackingFocus focus = focused == null ? null : trackingFocusFor(focused);
            if (focus != null && !focus.points().isEmpty()) {
                double aspect = trackingMaps.canvas.getWidth() / trackingMaps.canvas.getHeight();
                trackingMaps.manual = fitAspect(fitBounds(focus.points(), aspect), aspect);
            }
        }
        trackingMaps.redraw();
        updateTrackingLegend(byVehicle);
    }

    /** Click-to-focus target: points to frame plus how they were found. */
    record TrackingFocus(List<double[]> points, String label) { }

    Shipment focusedShipment() {
        if (focusedShipmentId == null) return null;
        return visibleShipments().stream().filter(s -> focusedShipmentId.equals(s.id())).findFirst().orElse(null);
    }

    TrackingFocus trackingFocusFor(Shipment shipment) {
        if (shipment == null) return null;
        if (shipment.vehicleId() != null) {
            TrackingPoint fix = tracking.latestForVehicle(shipment.vehicleId());
            if (fix == null) fix = tracking.latestFor(shipment.id());
            if (fix != null) return new TrackingFocus(List.of(ll(fix.x(), fix.y())), "Live position");
            if (shipment.routeId() != null) {
                RoutePlan route = repository.routes().stream().filter(r -> r.id().equals(shipment.routeId())).findFirst().orElse(null);
                if (route != null && !route.stops().isEmpty()) {
                    List<double[]> points = new ArrayList<>();
                    for (Stop stop : route.stops()) points.add(ll(stop.x(), stop.y()));
                    return new TrackingFocus(points, "Assigned route");
                }
            }
        }
        Stop dest = routeService.geocode(shipment.address());
        return new TrackingFocus(List.of(ll(dest.x(), dest.y())), "Destination");
    }

    void focusShipment(Shipment shipment) {
        focusedShipmentId = shipment == null ? null : shipment.id();
        drawTracking();
        refreshFocusChip();
        showEta(shipment);
    }

    void clearTrackingFocus() {
        focusedShipmentId = null;
        if (trackingMaps != null) trackingMaps.manual = null;
        drawTracking();
        refreshFocusChip();
    }

    void refreshFocusChip() {
        if (focusChipBox == null) return;
        focusChipBox.getChildren().clear();
        Shipment focused = focusedShipment();
        if (focused == null) return;
        TrackingFocus focus = trackingFocusFor(focused);
        Label text = new Label("Following " + focused.id() + (focus == null ? "" : " · " + focus.label()));
        text.getStyleClass().add("muted");
        javafx.scene.control.Button clear = new javafx.scene.control.Button("Clear");
        clear.getStyleClass().add("link-button");
        clear.setOnAction(event -> clearTrackingFocus());
        HBox row = new HBox(8, text, new Region(), clear);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(row.getChildren().get(1), ALWAYS);
        focusChipBox.getChildren().add(row);
    }

    void paintTracking(GraphicsContext g, double w, double h, ViewBounds view) {
        Shipment focused = focusedShipment();
        String focusVehicle = focused == null ? null : focused.vehicleId();
        for (String vehicleId : trackingTrails.keySet()) {
            boolean dimmed = focusVehicle != null && !focusVehicle.equals(vehicleId);
            g.setGlobalAlpha(dimmed ? 0.45 : 1.0);
            String color = vehicleColor(vehicleId);
            RoutePlan route = trackingRoutes.get(vehicleId);
            if (route != null) drawRouteOn(g, w, h, route.stops(), null, color, view);
            List<TrackingPoint> recent = trackingTrails.getOrDefault(vehicleId, List.of());
            g.setStroke(javafx.scene.paint.Color.web(color));
            g.setLineWidth(dimmed ? 2.5 : 3.5);
            for (int i = 1; i < recent.size(); i++) {
                double[] a = projectRaw(recent.get(i - 1).x(), recent.get(i - 1).y(), w, h, view);
                double[] b = projectRaw(recent.get(i).x(), recent.get(i).y(), w, h, view);
                strokeClippedLine(g, a[0], a[1], b[0], b[1], w, h);
            }
            TrackingPoint latest = recent.isEmpty() ? tracking.latestForVehicle(vehicleId) : recent.get(recent.size() - 1);
            if (latest != null) {
                boolean stale = tracking.gps().isFixLost(vehicleId);
                drawVehicleMarker(g, w, h, latest.x(), latest.y(), vehiclePlate(vehicleId), stale ? "#8a94a6" : color, stale, view);
            } else if (route != null) {
                Stop depot = route.stops().get(0);
                drawVehicleMarker(g, w, h, depot.x(), depot.y(), vehiclePlate(vehicleId), color, false, view);
            }
            g.setGlobalAlpha(1.0);
        }
        // Focused order with no live fix yet: pin its preset destination.
        if (focused != null && (focused.vehicleId() == null
                || (tracking.latestForVehicle(focused.vehicleId()) == null && tracking.latestFor(focused.id()) == null))) {
            Stop dest = routeService.geocode(focused.address());
            drawVehicleMarker(g, w, h, dest.x(), dest.y(), "To: " + focused.address(), "#7c3aed", false, view);
        }
        g.setGlobalAlpha(1.0);
    }

    static double[] ll(double lon, double lat) { return new double[] {lon, lat}; }

    /** Per-stop cumulative arrival times for the currently previewed route. */
    void showEtaBreakdown(VBox box, RoutePlan plan) {
        box.getChildren().clear();
        List<Double> cum = routeService.cumulativeMinutes(plan.stops());
        for (int i = 0; i < plan.stops().size(); i++) {
            Label line = new Label((i + 1) + ". " + plan.stops().get(i).name() + " — about " + Math.round(cum.get(i)) + " min from start");
            line.getStyleClass().add("muted");
            line.setWrapText(true);
            box.getChildren().add(line);
        }
    }

    /** Static symbol legend shared by the map panels. */
    VBox legendBox() {
        VBox legend = new VBox(3,
                legendSwatch("#17613f", "Depot / route start"),
                legendSwatch("#176df6", "District stop · blue route"),
                legendSwatch("#7c3aed", "Custom map pin (click-added)"),
                legendSwatch("#8a94a6", "STALE — simulated GPS fix lost"));
        Label title = new Label("Legend"); title.getStyleClass().add("eyebrow");
        VBox box = new VBox(4, title, legend);
        box.setPadding(new Insets(4, 0, 0, 0));
        return box;
    }

    /** Legend chip: colored dot + label. */
    HBox legendSwatch(String color, String label) {
        Label dot = new Label("●"); dot.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 13px;");
        Label text = new Label(label); text.getStyleClass().add("muted");
        HBox row = new HBox(6, dot, text); row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Live per-vehicle legend chips under the fleet map. */
    void updateTrackingLegend(Map<String, List<Shipment>> byVehicle) {
        if (trackingLegend == null) return;
        trackingLegend.getChildren().clear();
        if (byVehicle.isEmpty()) {
            Label none = new Label("No vehicles on the road.");
            none.getStyleClass().add("muted");
            trackingLegend.getChildren().add(none);
            return;
        }
        Label title = new Label("On the road"); title.getStyleClass().add("eyebrow");
        trackingLegend.getChildren().add(title);
        for (Map.Entry<String, List<Shipment>> entry : byVehicle.entrySet()) {
            String vehicleId = entry.getKey();
            boolean stale = tracking.gps().isFixLost(vehicleId);
            trackingLegend.getChildren().add(legendSwatch(stale ? "#8a94a6" : vehicleColor(vehicleId),
                    vehiclePlate(vehicleId) + " · " + entry.getValue().size() + " order(s)" + (stale ? " · STALE" : "")));
        }
    }

    /** Stable distinct color per vehicle so trails and markers stay distinguishable. */
    String vehicleColor(String vehicleId) {
        String[] palette = {"#1457c5", "#c2410c", "#15803d", "#7c3aed", "#be123c", "#0e7490", "#a16207"};
        return palette[Math.abs(vehicleId.hashCode()) % palette.length];
    }

    /** Interactive map: auto-fit content with sticky manual zoom/pan (scroll, drag, buttons). */
    final class MapController {
        final Canvas canvas;
        final HBox zoomBar;
        Supplier<List<double[]>> content = List::of;
        BiConsumer<GraphicsContext, ViewBounds> painter = (g, v) -> { };
        Consumer<double[]> clickAction;
        ViewBounds manual;
        ViewBounds lastAuto = FULL_MAP;
        private double pressX, pressY;
        private boolean dragging;
        private final Runnable tileRefresh = this::redraw;

        MapController(double width, double height) {
            canvas = mapCanvas(width, height);
            addTileListener(tileRefresh);
            Button in = button("+", "secondary-button"), out = button("−", "secondary-button"), reset = button("Reset view", "secondary-button");
            in.setOnAction(event -> zoomAt(1.4, width / 2, height / 2));
            out.setOnAction(event -> zoomAt(1 / 1.4, width / 2, height / 2));
            reset.setOnAction(event -> { manual = null; redraw(); });
            zoomBar = new HBox(6, in, out, reset);
            canvas.setOnScroll(event -> { zoomAt(event.getDeltaY() > 0 ? 1.25 : 0.8, event.getX(), event.getY()); event.consume(); });
            canvas.setOnMousePressed(event -> { pressX = event.getX(); pressY = event.getY(); dragging = false; });
            canvas.setOnMouseDragged(event -> {
                if (Math.abs(event.getX() - pressX) + Math.abs(event.getY() - pressY) > 4) dragging = true;
                if (dragging) { panBy(pressX - event.getX(), pressY - event.getY()); pressX = event.getX(); pressY = event.getY(); }
            });
            canvas.setOnMouseReleased(event -> {
                if (!dragging && clickAction != null) clickAction.accept(unproject(event.getX(), event.getY()));
                dragging = false;
            });
        }

        ViewBounds currentView() { return manual != null ? manual : lastAuto; }

        void redraw() {
            double aspect = canvas.getWidth() / canvas.getHeight();
            ViewBounds auto = fitAspect(fitBounds(content.get(), aspect), aspect);
            lastAuto = auto;
            if (manual != null) manual = fitAspect(manual, aspect);
            ViewBounds view = manual != null ? manual : auto;
            GraphicsContext g = prepareMap(canvas, view);
            painter.accept(g, view);
        }

        void zoomAt(double factor, double px, double py) {
            ViewBounds base = currentView();
            double w = canvas.getWidth(), h = canvas.getHeight(), pad = 14;
            double[] cursor = unproject(px, py);
            double lonSpan = Math.min(Math.max((base.lonMax() - base.lonMin()) / factor, 0.0006), MAP_LON_MAX - MAP_LON_MIN);
            double latSpan = Math.min(Math.max((base.latMax() - base.latMin()) / factor, 0.00045), MAP_LAT_MAX - MAP_LAT_MIN);
            double lonMin = cursor[0] - (cursor[0] - base.lonMin()) / factor;
            double latTop = mercator(cursor[1]) + (mercator(base.latMax()) - mercator(cursor[1])) / factor;
            double latBottom = mercator(cursor[1]) - (mercator(cursor[1]) - mercator(base.latMin())) / factor;
            manual = clamp(fitAspect(new ViewBounds(lonMin, lonMin + lonSpan, mercToLat(latBottom), mercToLat(latTop)), w / h));
            redraw();
        }

        void panBy(double dxPx, double dyPx) {
            ViewBounds base = currentView();
            double w = canvas.getWidth(), h = canvas.getHeight(), pad = 14;
            double lonSpan = base.lonMax() - base.lonMin();
            double mercSpan = mercator(base.latMax()) - mercator(base.latMin());
            double dLon = dxPx / (w - pad * 2) * lonSpan;
            double dMerc = dyPx / (h - pad * 2) * mercSpan;
            double mercTop = mercator(base.latMax()) - dMerc, mercBottom = mercator(base.latMin()) - dMerc;
            manual = clamp(new ViewBounds(base.lonMin() + dLon, base.lonMax() + dLon, mercToLat(mercBottom), mercToLat(mercTop)));
            redraw();
        }

        double[] unproject(double px, double py) {
            ViewBounds view = currentView();
            double w = canvas.getWidth(), h = canvas.getHeight(), pad = 14;
            double lon = view.lonMin() + (px - pad) / (w - pad * 2) * (view.lonMax() - view.lonMin());
            double mercTop = mercator(view.latMax()), mercBottom = mercator(view.latMin());
            double lat = mercToLat(mercTop - (py - pad) / (h - pad * 2) * (mercTop - mercBottom));
            return new double[] {
                Math.min(Math.max(lon, MAP_LON_MIN), MAP_LON_MAX),
                Math.min(Math.max(lat, MAP_LAT_MIN), MAP_LAT_MAX)};
        }

        private ViewBounds clamp(ViewBounds view) {
            double lonSpan = Math.min(view.lonMax() - view.lonMin(), MAP_LON_MAX - MAP_LON_MIN);
            double latSpan = Math.min(view.latMax() - view.latMin(), MAP_LAT_MAX - MAP_LAT_MIN);
            double lonMin = Math.min(Math.max(view.lonMin(), MAP_LON_MIN), MAP_LON_MAX - lonSpan);
            double latMin = Math.min(Math.max(view.latMin(), MAP_LAT_MIN), MAP_LAT_MAX - latSpan);
            return new ViewBounds(lonMin, lonMin + lonSpan, latMin, latMin + latSpan);
        }
    }

    private static double mercToLat(double merc) {
        return Math.toDegrees(Math.atan(Math.sinh(merc)));
    }

    void showEta(Shipment shipment) {
        if (shipment == null || trackingEta == null) return;
        TrackingPoint point = tracking.latestFor(shipment.id());
        if (point == null) { trackingEta.setText("No simulated fix yet for " + vehiclePlate(shipment.vehicleId()) + "."); return; }
        long ordersOnVehicle = shipment.vehicleId() == null ? 0 : visibleShipments().stream()
                .filter(s -> shipment.vehicleId().equals(s.vehicleId()) && s.status() == OrderStatus.IN_TRANSIT).count();
        String gps = shipment.vehicleId() == null ? "—" : tracking.gpsStatus(shipment.vehicleId());
        trackingEta.setText(vehiclePlate(shipment.vehicleId()) + " · " + gps + " · " + ordersOnVehicle + " order(s) aboard"
                + "  ·  ETA about " + point.etaMinutes() + " min  ·  updated " + point.recordedAt().format(DATE_TIME));
    }

    /** Export simulated phone-GPS fixes as a GPX 1.1 track: the selected delivery's vehicle,
     *  or every active vehicle when nothing is selected. Opens in real tracker apps. */
    void exportGpx(Shipment selected) {
        Map<String, List<TrackingPoint>> trails = new LinkedHashMap<>();
        if (selected != null && selected.vehicleId() != null) {
            List<TrackingPoint> trail = repository.trackingHistory().stream()
                    .filter(p -> selected.vehicleId().equals(p.vehicleId()))
                    .sorted(Comparator.comparing(TrackingPoint::recordedAt)).toList();
            if (!trail.isEmpty()) trails.put(selected.vehicleId(), trail);
        } else {
            for (Shipment s : visibleShipments()) {
                if (s.status() != OrderStatus.IN_TRANSIT || s.vehicleId() == null || trails.containsKey(s.vehicleId())) continue;
                List<TrackingPoint> trail = repository.trackingHistory().stream()
                        .filter(p -> s.vehicleId().equals(p.vehicleId()))
                        .sorted(Comparator.comparing(TrackingPoint::recordedAt)).toList();
                if (!trail.isEmpty()) trails.put(s.vehicleId(), trail);
            }
        }
        if (trails.isEmpty()) {
            alert(Alert.AlertType.INFORMATION, "Nothing to export", "No simulated GPS fixes recorded yet. Open Live tracking and let a delivery move first.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export GPX track");
        chooser.setInitialFileName("dcls-track.gpx");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("GPX files", "*.gpx"));
        File file = chooser.showSaveDialog(stage);
        if (file == null) return;
        try {
            StringBuilder gpx = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<gpx version=\"1.1\" creator=\"DCLS\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
            for (Map.Entry<String, List<TrackingPoint>> entry : trails.entrySet()) {
                gpx.append("  <trk><name>").append(gpxEscape(vehiclePlate(entry.getKey()))).append("</name>")
                   .append("<desc>Simulated driver phone GPS @ 35 km/h · Brunei Darussalam</desc><trkseg>\n");
                for (TrackingPoint p : entry.getValue()) {
                    String time = p.recordedAt().atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    gpx.append("    <trkpt lat=\"").append(p.y()).append("\" lon=\"").append(p.x()).append("\">")
                       .append("<time>").append(time).append("</time></trkpt>\n");
                }
                gpx.append("  </trkseg></trk>\n");
            }
            gpx.append("</gpx>\n");
            java.nio.file.Files.writeString(file.toPath(), gpx.toString(), java.nio.charset.StandardCharsets.UTF_8);
            toast("GPX exported: " + file.getName() + " (" + trails.size() + " vehicle(s)).");
        } catch (Exception exception) {
            showError("Could not write the GPX file: " + readable(exception));
        }
    }

    private String gpxEscape(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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

    private void registerRouteInOrder(Shipment shipment, RoutePlan route) { shipment.setAssignments(shipment.driverId(), shipment.vehicleId(), route.id()); repository.saveShipment(shipment); }

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
    /** Shared dialog setup: owner, modality, title, and — importantly — our stylesheet on the
     *  DialogPane itself (the old code added it to a not-yet-created scene, so dialogs fell back
     *  to default Java styling). */
    private void initDialog(Dialog<?> dialog, String title) {
        dialog.initOwner(stage); dialog.initModality(Modality.WINDOW_MODAL); dialog.setTitle(title);
        dialog.getDialogPane().getStyleClass().add("app-dialog");
        dialog.getDialogPane().getStylesheets().add(getClass().getResource("/edu/pb/dcls/styles.css").toExternalForm());
    }

    Optional<ButtonType> confirmDialog(String title, Node content, String action) {
        Dialog<ButtonType> dialog = new Dialog<>(); initDialog(dialog, title);
        ButtonType actionType = new ButtonType(action, ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(actionType, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(content); dialog.getDialogPane().setPrefWidth(480);
        ((Button) dialog.getDialogPane().lookupButton(actionType)).setDefaultButton(true);
        return dialog.showAndWait();
    }

    /** Form dialog that stays open and keeps the user's input when {@code submit} throws,
     *  showing the message inline instead of closing and firing a separate alert.
     *  {@code submit} validates, persists, and navigates; Enter triggers it. */
    void confirmForm(String title, Node content, String action, Runnable submit) {
        Dialog<ButtonType> dialog = new Dialog<>(); initDialog(dialog, title);
        ButtonType actionType = new ButtonType(action, ButtonType.OK.getButtonData());
        dialog.getDialogPane().getButtonTypes().addAll(actionType, ButtonType.CANCEL);
        Label error = new Label(); error.getStyleClass().add("form-error"); error.setWrapText(true);
        error.setManaged(false); error.setVisible(false);
        VBox wrap = new VBox(12, error, content);
        dialog.getDialogPane().setContent(wrap); dialog.getDialogPane().setPrefWidth(480);
        Button ok = (Button) dialog.getDialogPane().lookupButton(actionType); ok.setDefaultButton(true);
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            try { submit.run(); }
            catch (RuntimeException exception) {
                error.setText(readable(exception)); error.setManaged(true); error.setVisible(true);
                event.consume();
            }
        });
        dialog.showAndWait();
    }

    boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO); alert.initOwner(stage); alert.setTitle(title); alert.setHeaderText(null);
        alert.getDialogPane().getStylesheets().add(getClass().getResource("/edu/pb/dcls/styles.css").toExternalForm());
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
    void alert(Alert.AlertType type, String title, String message) { Alert alert = new Alert(type, message, ButtonType.OK); alert.initOwner(stage); alert.setTitle(title); alert.setHeaderText(null); alert.getDialogPane().getStylesheets().add(getClass().getResource("/edu/pb/dcls/styles.css").toExternalForm()); alert.showAndWait(); }
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

    /** Draft stop list: numbered rows matching the map pins, with per-row
     *  move-up / move-down / remove. The depot stays pinned first. */
    static final class ListViewWithRemove extends VBox {
        ListViewWithRemove(ObservableList<Stop> items) {
            this(items, null);
        }
        ListViewWithRemove(ObservableList<Stop> items, Runnable onChange) {
            javafx.scene.control.ListView<Stop> list = new javafx.scene.control.ListView<>(items); list.setPrefHeight(172);
            list.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
                private final Label name = new Label();
                private final Button up = miniButton("↑", "Move stop up");
                private final Button down = miniButton("↓", "Move stop down");
                private final Button remove = miniButton("×", "Remove stop");
                private final HBox row = new HBox(4, name, new Region(), up, down, remove);
                {
                    HBox.setHgrow(row.getChildren().get(1), ALWAYS);
                    row.setAlignment(Pos.CENTER_LEFT);
                    up.setOnAction(event -> move(-1));
                    down.setOnAction(event -> move(1));
                    remove.setOnAction(event -> {
                        int i = getIndex();
                        Stop stop = i >= 0 && i < items.size() ? items.get(i) : null;
                        if (stop != null && !"DCLS Depot".equals(stop.name()) && items.remove(stop)
                                && onChange != null) onChange.run();
                    });
                    setOnDragDetected(event -> {
                        int i = getIndex();
                        if (isEmpty() || i <= 0 || i >= items.size()) return;
                        if ("DCLS Depot".equals(items.get(i).name())) return;
                        javafx.scene.input.Dragboard db = startDragAndDrop(javafx.scene.input.TransferMode.MOVE);
                        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                        content.putString(String.valueOf(i));
                        db.setContent(content);
                        event.consume();
                    });
                    setOnDragOver(event -> {
                        if (!isEmpty() && event.getGestureSource() != this && event.getDragboard().hasString()
                                && getIndex() > 0) {
                            event.acceptTransferModes(javafx.scene.input.TransferMode.MOVE);
                        }
                        event.consume();
                    });
                    setOnDragDropped(event -> {
                        boolean done = false;
                        try {
                            int from = Integer.parseInt(event.getDragboard().getString());
                            int to = getIndex();
                            if (!isEmpty() && from > 0 && to > 0 && from < items.size() && to < items.size() && from != to) {
                                Stop moving = items.remove(from);
                                items.add(to, moving);
                                list.getSelectionModel().select(to);
                                if (onChange != null) onChange.run();
                                done = true;
                            }
                        } catch (NumberFormatException ignored) { }
                        event.setDropCompleted(done);
                        event.consume();
                    });
                }
                private void move(int delta) {
                    int i = getIndex();
                    int j = i + delta;
                    if (i < 0 || i >= items.size() || j <= 0 || j >= items.size()) return;
                    if ("DCLS Depot".equals(items.get(i).name())) return;
                    java.util.Collections.swap(items, i, j);
                    list.getSelectionModel().select(j);
                    if (onChange != null) onChange.run();
                }
                @Override protected void updateItem(Stop stop, boolean empty) {
                    super.updateItem(stop, empty);
                    if (empty || stop == null) { setGraphic(null); setText(null); return; }
                    setText(null);
                    int i = getIndex();
                    name.setText((i + 1) + ". " + (stop.id().startsWith("custom-")
                            ? stop.name() + String.format("  ·  %.4f, %.4f", stop.x(), stop.y())
                            : stop.name()));
                    boolean depot = "DCLS Depot".equals(stop.name());
                    up.setDisable(depot || i <= 1);
                    down.setDisable(depot || i < 0 || i >= items.size() - 1);
                    remove.setDisable(depot);
                    setGraphic(row);
                }
            });
            getChildren().add(list);
        }
        private static Button miniButton(String text, String tip) {
            Button button = new Button(text);
            button.getStyleClass().add("mini-button");
            button.setTooltip(new javafx.scene.control.Tooltip(tip));
            button.setFocusTraversable(false);
            return button;
        }
    }

    public static void main(String[] args) { launch(args); }
}
