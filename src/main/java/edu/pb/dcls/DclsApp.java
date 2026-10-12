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
<<<<<<< HEAD
import java.util.List;
import java.util.Optional;
=======
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
>>>>>>> Sam-part

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

<<<<<<< HEAD
    Stage stage;
    Repository repository;
    AuthService auth;
    AccessService access;
    FleetService fleet;
    OrderService orders;
    RouteService routeService;
    TrackingService tracking;
    User currentUser;
=======
    private Stage stage;
    private Repository repository;
    private AuthService auth;
    private AccessService access;
    private FleetService fleet;
    private OrderService orders;
    private RouteService routeService;
    private TrackingService tracking;
    private AutoRouteService autoRoutes;
    private User currentUser;
>>>>>>> Sam-part
    private BorderPane shell;
    private VBox nav;
    private Label pageHeading;
    Label dataModeLabel;
    String activePage = "dashboard";
    String fleetRecordsView = "vehicles";
    private Timeline trackingTimer;
<<<<<<< HEAD
    Canvas trackingCanvas;
    Label trackingEta;
    final ObservableList<String> routeStopsDraft = FXCollections.observableArrayList();
=======
    private Canvas trackingCanvas;
    private TileLevel baseLevel = new TileLevel(null, 11, 1675, 995, 4, 3);
    private TileLevel metroLevel = new TileLevel(null, 13, 6709, 3982, 5, 4);
    private TileLevel tutongLevel = new TileLevel(null, 13, 6704, 3985, 3, 2);
    private TileLevel muaraLevel = new TileLevel(null, 13, 6712, 3981, 4, 3);
    private Label trackingEta;
    private String routeNameInput = "";
    private final ObservableList<Stop> routeStopsDraft = FXCollections.observableArrayList();
    private int customPinCount = 0;
    private List<Stop> previewStops = List.of();
    private List<double[]> trackingFrame = List.of();
    private final Map<String, RoutePlan> trackingRoutes = new LinkedHashMap<>();
    private final Map<String, List<TrackingPoint>> trackingTrails = new LinkedHashMap<>();
    private MapController trackingMaps;
    private VBox trackingLegend;
>>>>>>> Sam-part
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
        baseLevel = loadLevel("/edu/pb/dcls/brunei-map.png", 11, 1675, 995, 4, 3);
        metroLevel = loadLevel("/edu/pb/dcls/brunei-map-z13.png", 13, 6709, 3982, 5, 4);
        tutongLevel = loadLevel("/edu/pb/dcls/brunei-map-z13-tutong.png", 13, 6704, 3985, 3, 2);
        muaraLevel = loadLevel("/edu/pb/dcls/brunei-map-z13-muara.png", 13, 6712, 3981, 4, 3);
        fleet = new FleetService(repository);
        orders = new OrderService(repository);
        routeService = new RouteService(repository);
        tracking = new TrackingService(repository);
        autoRoutes = new AutoRouteService(repository, routeService, tracking);
        stage.setTitle("DCLS — Delivery Logistics Simulator");
        stage.setMinWidth(900);
        stage.setMinHeight(560);
        stage.setWidth(900);
        stage.setHeight(560);
        showLogin();
        stage.show();
    }

    private TileLevel loadLevel(String resource, int z, int x0, int y0, int cols, int rows) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in != null) return new TileLevel(new Image(in), z, x0, y0, cols, rows);
        } catch (Exception ignored) { }
        return new TileLevel(null, z, x0, y0, cols, rows);
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

<<<<<<< HEAD
=======
    private VBox dashboardPage() {
        List<Shipment> shipmentRows = visibleShipments();
        List<Vehicle> vehicleRows = currentUser.role() == Role.DRIVER
                ? repository.vehicles().stream().filter(v -> currentUser.id().equals(v.driverId())).toList()
                : repository.vehicles();
        long active = shipmentRows.stream().filter(s -> s.status() == OrderStatus.ASSIGNED || s.status() == OrderStatus.IN_TRANSIT).count();
        long pending = shipmentRows.stream().filter(s -> s.status() == OrderStatus.PENDING).count();
        long completed = shipmentRows.stream().filter(s -> s.status() == OrderStatus.DELIVERED).count();
        VBox page = pageHeader("", ""); page.setSpacing(18); page.setPadding(new Insets(18, 20, 20, 58));
        HBox metrics = new HBox(18,
                metric("Vehicles", String.valueOf(vehicleRows.size()), ""),
                metric("Active deliveries", String.valueOf(active), ""),
                metric("Pending orders", String.valueOf(pending), ""),
                metric("Completed today", String.valueOf(completed), ""));
        for (Node node : metrics.getChildren()) HBox.setHgrow(node, ALWAYS);
        VBox mapPanel = panel(new Label("Operations map — scroll to zoom, drag to pan")); ((Label) mapPanel.getChildren().get(0)).getStyleClass().add("section-title");
        MapController opsMap = new MapController(430, 210);
        if (!repository.routes().isEmpty()) {
            List<Stop> firstStops = repository.routes().getFirst().stops();
            opsMap.content = () -> firstStops.stream().map(s -> ll(s.x(), s.y())).toList();
            opsMap.painter = (g, v) -> drawRouteOn(g, opsMap.canvas.getWidth(), opsMap.canvas.getHeight(), firstStops, null, "#176df6", v);
        } else {
            opsMap.content = this::dashboardPoints;
            opsMap.painter = (g, v) -> paintDashboardMarkers(g, opsMap.canvas.getWidth(), opsMap.canvas.getHeight(), v);
        }
        opsMap.redraw();
        mapPanel.getChildren().addAll(opsMap.canvas, opsMap.zoomBar);
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

    private List<double[]> dashboardPoints() {
        Stop depot = BruneiPlaces.DEPOT;
        List<double[]> frame = new ArrayList<>(List.of(ll(depot.x(), depot.y())));
        for (Vehicle vehicle : repository.vehicles()) {
            TrackingPoint last = tracking.latestForVehicle(vehicle.id());
            if (last != null) frame.add(ll(last.x(), last.y()));
        }
        return frame;
    }

    private void paintDashboardMarkers(GraphicsContext g, double w, double h, ViewBounds view) {
        Stop depot = BruneiPlaces.DEPOT;
        double[] d = project(depot, w, h, view);
        g.setFill(javafx.scene.paint.Color.web("#17613f")); g.fillOval(d[0] - 7, d[1] - 7, 14, 14);
        g.setStroke(javafx.scene.paint.Color.WHITE); g.setLineWidth(2.5); g.strokeOval(d[0] - 7, d[1] - 7, 14, 14);
        mapLabel(g, depot.name(), d[0] + 10, d[1] - 6);
        for (Vehicle vehicle : repository.vehicles()) {
            TrackingPoint last = tracking.latestForVehicle(vehicle.id());
            if (last == null) continue;
            drawVehicleMarker(g, w, h, last.x(), last.y(), vehiclePlate(last.vehicleId()), vehicleColor(last.vehicleId()),
                    tracking.gps().isFixLost(last.vehicleId()), view);
        }
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
        VBox page = pageHeader("Fleet", "Manage vehicle availability, assignments, and service records.");
        HBox recordLinks = new HBox(8); recordLinks.setAlignment(Pos.CENTER_RIGHT);
        Button maintenance = button("Maintenance records", "secondary-button"); maintenance.setOnAction(event -> { page.getChildren().setAll(recordLinks, maintenanceTab()); });
        Button fuelRecords = button("Fuel records", "secondary-button"); fuelRecords.setOnAction(event -> { page.getChildren().setAll(recordLinks, fuelTab()); });
        Button vehicles = button("‹  Fleet inventory", "secondary-button"); vehicles.setOnAction(event -> { fleetRecordsView="vehicles"; showPage("fleet"); });
        recordLinks.getChildren().addAll(vehicles, maintenance, fuelRecords);
        if (fleetRecordsView.equals("maintenance")) page.getChildren().addAll(recordLinks, maintenanceTab());
        else if (fleetRecordsView.equals("fuel")) page.getChildren().addAll(recordLinks, fuelTab());
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
        Button add = button("Add vehicle", "primary-button"); add.setDisable(!access.canManageFleet(currentUser.role())); add.setOnAction(event -> vehicleDialog(null));
        MenuButton records = new MenuButton("Records");
        records.getItems().addAll(menu("Maintenance", () -> { fleetRecordsView="maintenance"; showPage("fleet"); }), menu("Fuel records", () -> { fleetRecordsView="fuel"; showPage("fleet"); }));
        MenuButton filters = new MenuButton("Filter");
        MenuItem allStatuses = new MenuItem("All statuses"); allStatuses.setOnAction(event -> filter.setValue(null));
        filters.getItems().add(allStatuses);
        for (VehicleStatus value : VehicleStatus.values()) filters.getItems().add(menu(value.label(), () -> { filter.setValue(value); filters.setText(value.label()); }));
        allStatuses.setOnAction(event -> { filter.setValue(null); filters.setText("Filter"); });
        tools.getChildren().addAll(search, gap, filters, records, add);
        TableView<Vehicle> table = vehicleTable();
        Runnable refresh = () -> {
            String q = search.getText() == null ? "" : search.getText().toLowerCase(Locale.ROOT).trim();
            VehicleStatus status = filter.getValue();
            List<Vehicle> rows = repository.vehicles().stream().filter(v -> status == null || v.status() == status)
                    .filter(v -> q.isEmpty() || (v.plate() + " " + v.model() + " " + v.type() + " " + driverName(v.driverId())).toLowerCase(Locale.ROOT).contains(q))
                    .toList(); table.setItems(FXCollections.observableArrayList(rows));
        };
        search.textProperty().addListener((observable, oldValue, newValue) -> refresh.run()); filter.valueProperty().addListener((observable, oldValue, newValue) -> refresh.run());
        refresh.run(); table.setPrefHeight(330);
        HBox actions = new HBox(8);
        Button edit = button("Edit", "secondary-button"), status = button("Change status", "secondary-button"), remove = button("Delete", "danger-button");
        edit.setDisable(!access.canManageFleet(currentUser.role())); status.setDisable(!access.canManageFleet(currentUser.role())); remove.setDisable(currentUser.role() != Role.ADMIN);
        edit.setOnAction(event -> { if (table.getSelectionModel().getSelectedItem() != null) vehicleDialog(table.getSelectionModel().getSelectedItem()); });
        status.setOnAction(event -> { if (table.getSelectionModel().getSelectedItem() != null) vehicleStatusDialog(table.getSelectionModel().getSelectedItem()); });
        remove.setOnAction(event -> { Vehicle selected = table.getSelectionModel().getSelectedItem(); if (selected != null && confirm("Delete vehicle?", "This action is available only for vehicles without shipment history.")) { try { repository.deleteVehicle(selected.id()); refresh.run(); } catch (RuntimeException ex) { alert(Alert.AlertType.WARNING, "Vehicle is in use", "This vehicle is linked to an order. Set it to Unavailable instead."); } } });
        Label reminders = new Label(fleet.maintenanceDue().size() + " service reminder(s) based on current mileage"); reminders.getStyleClass().add("muted");
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
        table.getColumns().add(textCol("Driver", v -> driverName(v.driverId()), 150));
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
        TextField search = new TextField(); search.setPromptText("Search order or customer..."); search.setPrefWidth(310); search.setAccessibleText("Search orders");
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
                detailField("Driver / vehicle", driverName(shipment.driverId())+" · "+vehiclePlate(shipment.vehicleId())),
                detailField("Shipment", shipment.quantity()+" packages · "+shipment.weightKg()+" kg"), detailField("Status", shipment.status().label()));
        Button trackingButton=button("Open tracking", "primary-button"); trackingButton.setMaxWidth(Double.MAX_VALUE);
        trackingButton.setOnAction(event -> showPage("tracking")); panel.getChildren().add(trackingButton);
    }

    private Node detailField(String title, String value) {
        VBox box=new VBox(4); Label heading=new Label(title); heading.getStyleClass().add("eyebrow"); Label content=new Label(value==null||value.isBlank()?"—":value); content.setWrapText(true); content.getStyleClass().add("detail-value"); box.getChildren().addAll(heading,content); return box;
    }

    private TableView<Shipment> shipmentTable() {
        TableView<Shipment> table = new TableView<>(); table.setPlaceholder(new Label("No orders match this search."));
        table.getColumns().add(textCol("Order", Shipment::id, 115));
        table.getColumns().add(textCol("Customer", Shipment::customerName, 190));
        table.getColumns().add(textCol("Status", s -> s.status().label(), 110));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        return table;
    }

    private VBox routesPage() {
        VBox page = pageHeader("Route planning", "District stops or map clicks, fastest route @ 35 km/h. Marking an order In-Transit auto-builds one route per vehicle.");
        HBox builder = new HBox(12);
        VBox controls = panel(new Label("Create a route"));
        ((Label) controls.getChildren().get(0)).getStyleClass().add("section-title");
        TextField name = new TextField(); name.setPromptText("Route name");
        ComboBox<String> place = new ComboBox<>(FXCollections.observableArrayList(routeService.placeNames())); place.setPromptText("Choose stop"); place.setMaxWidth(Double.MAX_VALUE);
        Button addStop = button("Add stop", "secondary-button");
        ListViewWithRemove draft = new ListViewWithRemove(routeStopsDraft);
        addStop.setOnAction(event -> {
            if (place.getValue() == null) return;
            Stop stop = routeService.place(place.getValue());
            if (stop != null && routeStopsDraft.stream().noneMatch(s -> s.name().equals(stop.name()))) routeStopsDraft.add(stop);
        });
        if (routeStopsDraft.isEmpty()) routeStopsDraft.add(BruneiPlaces.DEPOT);
        ComboBox<User> driver = driverCombo(); driver.setPromptText("Assign driver (optional)");
        Button optimize = button("Optimize route", "secondary-button");
        Button saveAssign = button("Save & assign", "primary-button");
        Button clickToggle = button("Map clicks: add stops ON", "secondary-button");
        Label routeResult = new Label("Add at least one delivery stop after the depot."); routeResult.getStyleClass().add("muted"); routeResult.setWrapText(true);
        VBox etaBreakdown = new VBox(2);
        final Runnable[] optimizeRouteRef = new Runnable[1];
        final RoutePlan[] planned = {null};
        final boolean[] saved = {false};
        MapController previewMap = new MapController(430, 330);
        previewMap.content = () -> previewStops.stream().map(s -> ll(s.x(), s.y())).toList();
        previewMap.painter = (g, v) -> {
            if (!previewStops.isEmpty()) drawRouteOn(g, previewMap.canvas.getWidth(), previewMap.canvas.getHeight(), previewStops, null, "#176df6", v);
        };
        previewMap.clickAction = lonLat -> {
            Stop pin = new Stop("custom-" + AppServices.id(), "Custom pin " + (++customPinCount), lonLat[0], lonLat[1]);
            routeStopsDraft.add(pin);
            if (optimizeRouteRef[0] != null) optimizeRouteRef[0].run();
        };
        Runnable optimizeRoute = () -> {
            try {
                RoutePlan plan = routeService.optimizeStops(name.getText(), new ArrayList<>(routeStopsDraft), null);
                planned[0] = plan; saved[0] = false;
                previewStops = plan.stops();
                routeResult.setText(String.format("%s  ·  %.1f km  ·  about %d minutes", plan.name(), plan.distanceKm(), plan.estimatedMinutes()));
                showEtaBreakdown(etaBreakdown, plan);
                previewMap.redraw();
            } catch (AppException exception) { alert(Alert.AlertType.WARNING, "Route not created", exception.getMessage()); }
        };
        optimizeRouteRef[0] = optimizeRoute;
        optimize.setOnAction(event -> optimizeRoute.run());
        clickToggle.setOnAction(event -> {
            if (previewMap.clickAction != null) {
                previewMap.clickAction = null;
                clickToggle.setText("Map clicks: add stops OFF");
            } else {
                previewMap.clickAction = lonLat -> {
                    Stop pin = new Stop("custom-" + AppServices.id(), "Custom pin " + (++customPinCount), lonLat[0], lonLat[1]);
                    routeStopsDraft.add(pin);
                    optimizeRoute.run();
                };
                clickToggle.setText("Map clicks: add stops ON");
            }
        });
        saveAssign.setOnAction(event -> {
            if (planned[0] == null) { optimizeRoute.run(); return; }
            if (driver.getValue() == null) { alert(Alert.AlertType.INFORMATION, "Choose a driver", "Select a driver before saving and assigning this route."); return; }
            if (!saved[0]) {
                RoutePlan route = planned[0];
                planned[0] = new RoutePlan(route.id(), route.name(), driver.getValue().id(), route.stops(), route.distanceKm(), route.estimatedMinutes(), route.completed(), route.createdAt());
                repository.saveRoute(planned[0]); saved[0] = true;
            }
            alert(Alert.AlertType.INFORMATION, "Route assigned", "Route " + planned[0].name() + " has been saved and assigned to " + driver.getValue().name() + ".");
            showPage("routes");
        });
        controls.getChildren().addAll(new Label("Pickup location"), place, new Label("Delivery stops"), draft, addStop, driver, optimize, saveAssign, clickToggle, routeResult, etaBreakdown);
        controls.setPrefWidth(260); controls.setMinWidth(250); VBox.setVgrow(draft, ALWAYS);
        VBox mapPanel = panel(new Label("Route preview — scroll to zoom, drag to pan, click to add a stop")); ((Label) mapPanel.getChildren().get(0)).getStyleClass().add("section-title"); mapPanel.getChildren().addAll(previewMap.canvas, previewMap.zoomBar, legendBox());
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
        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> {
            if (selected != null) {
                previewStops = selected.stops();
                showEtaBreakdown(etaBreakdown, selected);
                previewMap.redraw();
            }
        });
        repository.routes().stream().max(Comparator.comparing(RoutePlan::createdAt)).ifPresent(route -> {
            previewStops = route.stops();
            showEtaBreakdown(etaBreakdown, route);
        });
        previewMap.redraw();
        history.getChildren().add(table); page.getChildren().addAll(builder, history); return page;
    }

    /** Per-stop cumulative arrival times for the currently previewed route. */
    private void showEtaBreakdown(VBox box, RoutePlan plan) {
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
    private VBox legendBox() {
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

    private VBox trackingPage() {
        VBox page = pageHeader("Real-time tracking", "Simulated driver's-phone GPS per vehicle @ 35 km/h · Brunei Darussalam.");
        trackingMaps = new MapController(470, 350);
        trackingMaps.content = () -> trackingFrame;
        trackingMaps.painter = (g, v) -> paintTracking(g, trackingMaps.canvas.getWidth(), trackingMaps.canvas.getHeight(), v);
        trackingCanvas = trackingMaps.canvas;
        VBox map = panel(new Label("Fleet map  ·  Brunei Darussalam — scroll to zoom, drag to pan")); ((Label) map.getChildren().get(0)).getStyleClass().add("section-title");
        trackingLegend = new VBox(3);
        map.getChildren().addAll(trackingCanvas, trackingMaps.zoomBar, legendBox(), trackingLegend);
        VBox active = panel(new Label("Active deliveries")); ((Label) active.getChildren().get(0)).getStyleClass().add("section-title");
        trackingEta = new Label("Select a delivery to view its latest estimate."); trackingEta.getStyleClass().add("muted"); trackingEta.setWrapText(true);
        TableView<Shipment> table = new TableView<>(); table.setPlaceholder(new Label("No active deliveries."));
        table.getColumns().add(textCol("Order", Shipment::id, 90));
        table.getColumns().add(textCol("Vehicle", s -> vehiclePlate(s.vehicleId()), 90));
        table.getColumns().add(textCol("Driver", s -> driverName(s.driverId()), 110));
        table.getColumns().add(textCol("GPS", s -> s.vehicleId() == null ? "—" : tracking.gpsStatus(s.vehicleId()), 110));
        table.getColumns().add(textCol("Destination", Shipment::address, 150));
        table.getColumns().add(textCol("Status", s -> s.status().label(), 80));
        List<Shipment> activeShipments = visibleShipments().stream().filter(s -> s.status() == OrderStatus.IN_TRANSIT).toList();
        table.setItems(FXCollections.observableArrayList(activeShipments)); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY); table.setPrefHeight(280);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> showEta(selected));
        Button gpsToggle = button("Simulate GPS fix lost / restore", "secondary-button");
        gpsToggle.setMaxWidth(Double.MAX_VALUE);
        gpsToggle.setOnAction(event -> {
            Shipment selected = table.getSelectionModel().getSelectedItem();
            if (selected == null || selected.vehicleId() == null) {
                alert(Alert.AlertType.INFORMATION, "No vehicle selected", "Select a delivery first, then toggle its simulated phone-GPS fix.");
                return;
            }
            boolean lost = !tracking.gps().isFixLost(selected.vehicleId());
            tracking.gps().setFixLost(selected.vehicleId(), lost);
            toast(vehiclePlate(selected.vehicleId()) + ": simulated GPS fix " + (lost ? "LOST — marker frozen." : "restored."));
            table.refresh();
            drawTracking();
            showEta(selected);
        });
        Button gpxExport = button("Export GPX track", "secondary-button");
        gpxExport.setMaxWidth(Double.MAX_VALUE);
        gpxExport.setOnAction(event -> exportGpx(table.getSelectionModel().getSelectedItem()));
        active.getChildren().addAll(table, gpsToggle, gpxExport, trackingEta);
        HBox content = new HBox(12, map, active); HBox.setHgrow(map, ALWAYS); active.setPrefWidth(230); active.setMinWidth(220);
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

>>>>>>> Sam-part
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

<<<<<<< HEAD
    void drawTracking() {
        if (trackingCanvas == null) return;
        GraphicsContext g = prepareMap(trackingCanvas);
        List<RoutePlan> activeRoutes = visibleShipments().stream().filter(s -> s.status() == OrderStatus.IN_TRANSIT && s.routeId() != null)
                .map(s -> repository.routes().stream().filter(r -> r.id().equals(s.routeId())).findFirst().orElse(null)).filter(java.util.Objects::nonNull).toList();
        if (!activeRoutes.isEmpty()) drawRouteOn(g, trackingCanvas.getWidth(), trackingCanvas.getHeight(), activeRoutes.get(0).stops(), null);
=======
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
        if (finalNext == OrderStatus.IN_TRANSIT && shipment.status() == OrderStatus.IN_TRANSIT && shipment.vehicleId() != null) {
            try {
                RoutePlan auto = autoRoutes.buildForVehicle(shipment.vehicleId());
                toast("Auto-route: " + auto.name() + " · " + String.format("%.1f km", auto.distanceKm()) + " · about " + auto.estimatedMinutes() + " min @ 35 km/h.");
            } catch (RuntimeException exception) {
                alert(Alert.AlertType.WARNING, "Auto-route not created", readable(exception));
            }
        }
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
        if (trackingMaps == null) return;
>>>>>>> Sam-part
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
        trackingMaps.redraw();
        updateTrackingLegend(byVehicle);
    }

    private void paintTracking(GraphicsContext g, double w, double h, ViewBounds view) {
        for (String vehicleId : trackingTrails.keySet()) {
            String color = vehicleColor(vehicleId);
            RoutePlan route = trackingRoutes.get(vehicleId);
            if (route != null) drawRouteOn(g, w, h, route.stops(), null, color, view);
            List<TrackingPoint> recent = trackingTrails.getOrDefault(vehicleId, List.of());
            g.setStroke(javafx.scene.paint.Color.web(color));
            g.setLineWidth(2.5);
            for (int i = 1; i < recent.size(); i++) {
                double[] a = project(recent.get(i - 1).x(), recent.get(i - 1).y(), w, h, view);
                double[] b = project(recent.get(i).x(), recent.get(i).y(), w, h, view);
                g.strokeLine(a[0], a[1], b[0], b[1]);
            }
            TrackingPoint latest = recent.isEmpty() ? tracking.latestForVehicle(vehicleId) : recent.get(recent.size() - 1);
            if (latest != null) {
                boolean stale = tracking.gps().isFixLost(vehicleId);
                drawVehicleMarker(g, w, h, latest.x(), latest.y(), vehiclePlate(vehicleId), stale ? "#8a94a6" : color, stale, view);
            } else if (route != null) {
                Stop depot = route.stops().get(0);
                drawVehicleMarker(g, w, h, depot.x(), depot.y(), vehiclePlate(vehicleId), color, false, view);
            }
        }
    }

    /** Live per-vehicle legend chips under the fleet map. */
    private void updateTrackingLegend(Map<String, List<Shipment>> byVehicle) {
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
    private String vehicleColor(String vehicleId) {
        String[] palette = {"#1457c5", "#c2410c", "#15803d", "#7c3aed", "#be123c", "#0e7490", "#a16207"};
        return palette[Math.abs(vehicleId.hashCode()) % palette.length];
    }

    /** Interactive map: auto-fit content with sticky manual zoom/pan (scroll, drag, buttons). */
    private final class MapController {
        final Canvas canvas;
        final HBox zoomBar;
        Supplier<List<double[]>> content = List::of;
        BiConsumer<GraphicsContext, ViewBounds> painter = (g, v) -> { };
        Consumer<double[]> clickAction;
        ViewBounds manual;
        ViewBounds lastAuto = FULL_MAP;
        private double pressX, pressY;
        private boolean dragging;

        MapController(double width, double height) {
            canvas = mapCanvas(width, height);
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
            ViewBounds auto = fitBounds(content.get(), canvas.getWidth() / canvas.getHeight());
            lastAuto = auto;
            ViewBounds view = manual != null ? manual : auto;
            GraphicsContext g = prepareMap(canvas, view);
            painter.accept(g, view);
        }

        void zoomAt(double factor, double px, double py) {
            ViewBounds base = currentView();
            double w = canvas.getWidth(), h = canvas.getHeight(), pad = 14;
            double[] cursor = unproject(px, py);
            double lonSpan = Math.min(Math.max((base.lonMax() - base.lonMin()) / factor, 0.03), MAP_LON_MAX - MAP_LON_MIN);
            double latSpan = Math.min(Math.max((base.latMax() - base.latMin()) / factor, 0.022), MAP_LAT_MAX - MAP_LAT_MIN);
            double lonMin = cursor[0] - (cursor[0] - base.lonMin()) / factor;
            double latTop = mercator(cursor[1]) + (mercator(base.latMax()) - mercator(cursor[1])) / factor;
            double latBottom = mercator(cursor[1]) - (mercator(cursor[1]) - mercator(base.latMin())) / factor;
            manual = clamp(new ViewBounds(lonMin, lonMin + lonSpan, mercToLat(latBottom), mercToLat(latTop)));
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
    private void exportGpx(Shipment selected) {
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

<<<<<<< HEAD
    ComboBox<User> driverCombo() {
=======
    private Canvas mapCanvas(double width, double height) {
        Canvas canvas = new Canvas(width, height); canvas.getStyleClass().add("map-canvas"); prepareMap(canvas); return canvas;
    }

    private GraphicsContext prepareMap(Canvas canvas) { return prepareMap(canvas, FULL_MAP); }

    private GraphicsContext prepareMap(Canvas canvas, ViewBounds view) {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = canvas.getWidth(), h = canvas.getHeight();
        TileLevel level = levelFor(view);
        if (level.image() != null) {
            // NOTE: Canvas drawImage with an explicit source rect renders blank on this
            // JavaFX version, so zoom by drawing the whole grid through a dest transform.
            double sx = level.lonToPixel(view.lonMin()), ex = level.lonToPixel(view.lonMax());
            double sy = level.latToPixel(view.latMax()), ey = level.latToPixel(view.latMin());
            double xScale = w / (ex - sx), yScale = h / (ey - sy);
            g.save();
            g.beginPath(); g.rect(0, 0, w, h); g.clip();
            g.drawImage(level.image(), -sx * xScale, -sy * yScale,
                    level.image().getWidth() * xScale, level.image().getHeight() * yScale);
            g.restore();
        } else {
            g.setFill(javafx.scene.paint.Color.web("#eef3f6")); g.fillRect(0, 0, w, h);
            g.setFill(javafx.scene.paint.Color.web("#e3eee3")); g.fillOval(w * .70, h * .08, w * .25, h * .2); g.fillOval(w * .02, h * .66, w * .18, h * .22);
            g.setStroke(javafx.scene.paint.Color.web("#d7e1e5")); g.setLineWidth(18);
            g.strokeLine(w * .03, h * .82, w * .94, h * .14); g.strokeLine(w * .08, h * .28, w * .9, h * .77);
            g.strokeLine(w * .36, h * .98, w * .56, h * .02); g.strokeLine(w * .04, h * .52, w * .95, h * .48);
            g.setStroke(javafx.scene.paint.Color.web("#ffffff")); g.setLineWidth(2.5);
            g.strokeLine(w * .03, h * .82, w * .94, h * .14); g.strokeLine(w * .08, h * .28, w * .9, h * .77);
            g.strokeLine(w * .36, h * .98, w * .56, h * .02); g.strokeLine(w * .04, h * .52, w * .95, h * .48);
        }
        g.setFill(javafx.scene.paint.Color.web("#657487")); g.setFont(javafx.scene.text.Font.font("Segoe UI", 11));
        g.fillText("Brunei Darussalam  ·  simulated phone GPS @ 35 km/h", 14, 20);
        g.setFont(javafx.scene.text.Font.font("Segoe UI", 9));
        g.fillText("© OpenStreetMap contributors", w - 178, h - 8);
        drawScaleBar(g, w, h, view);
        return g;
    }

    /** Scale bar from the live view span (111.32 km per lon-degree, adjusted for latitude). */
    private void drawScaleBar(GraphicsContext g, double w, double h, ViewBounds view) {
        double latMid = (view.latMin() + view.latMax()) / 2;
        double metersPerPixel = (view.lonMax() - view.lonMin()) * 111320 * Math.cos(Math.toRadians(latMid)) / w;
        int[] steps = {50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000};
        int choice = steps[0];
        for (int step : steps) if (step / metersPerPixel <= 130) choice = step;
        double bar = choice / metersPerPixel;
        double x = 14, y = h - 14;
        g.setStroke(javafx.scene.paint.Color.WHITE); g.setLineWidth(5);
        g.strokeLine(x, y, x + bar, y);
        g.setStroke(javafx.scene.paint.Color.web("#172033")); g.setLineWidth(2.5);
        g.strokeLine(x, y, x + bar, y);
        g.setFill(javafx.scene.paint.Color.web("#172033"));
        g.setFont(javafx.scene.text.Font.font("Segoe UI", 9));
        g.fillText(choice >= 1000 ? (choice / 1000) + " km" : choice + " m", x + bar + 6, y + 3);
    }

    /** Legend chip: colored dot + label. */
    private HBox legendSwatch(String color, String label) {
        Label dot = new Label("●"); dot.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 13px;");
        Label text = new Label(label); text.getStyleClass().add("muted");
        HBox row = new HBox(6, dot, text); row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void drawRouteOn(GraphicsContext g, double width, double height, List<Stop> stops, TrackingPoint moving, ViewBounds view) {
        drawRouteOn(g, width, height, stops, moving, "#176df6", view);
    }

    private void drawRouteOn(GraphicsContext g, double width, double height, List<Stop> stops, TrackingPoint moving, String color, ViewBounds view) {
        if (stops == null || stops.isEmpty()) return;
        g.setStroke(javafx.scene.paint.Color.web("#ffffff", 0.85)); g.setLineWidth(6);
        strokeRoadPath(g, width, height, stops, view);
        g.setStroke(javafx.scene.paint.Color.web(color)); g.setLineWidth(3.5);
        strokeRoadPath(g, width, height, stops, view);
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i); double[] p = project(stop, width, height, view); double x = p[0], y = p[1];
            if (stop.id().startsWith("custom-")) {
                g.setFill(javafx.scene.paint.Color.web("#ffffff"));
                g.fillPolygon(new double[] {x, x + 8, x, x - 8}, new double[] {y - 9, y, y + 9, y}, 4);
                g.setStroke(javafx.scene.paint.Color.web("#7c3aed")); g.setLineWidth(3);
                g.strokePolygon(new double[] {x, x + 8, x, x - 8}, new double[] {y - 9, y, y + 9, y}, 4);
            } else {
                g.setFill(i == 0 ? javafx.scene.paint.Color.web("#17613f") : javafx.scene.paint.Color.web("#ffffff"));
                g.fillOval(x - 7, y - 7, 14, 14); g.setStroke(javafx.scene.paint.Color.web(i == 0 ? "#17613f" : "#176df6")); g.setLineWidth(3); g.strokeOval(x - 7, y - 7, 14, 14);
            }
            mapLabel(g, stop.name(), x + 9, y - 8);
        }
        if (moving != null) drawVehicleMarker(g, width, height, moving.x(), moving.y(), "Vehicle", "#1457c5", false, view);
    }

    /** Stroke every leg along its real road polyline (straight fallback when unknown). */
    private void strokeRoadPath(GraphicsContext g, double width, double height, List<Stop> stops, ViewBounds view) {
        for (int i = 1; i < stops.size(); i++) {
            List<double[]> path = RouteService.legPath(stops.get(i - 1), stops.get(i));
            for (int j = 1; j < path.size(); j++) {
                double[] a = project(path.get(j - 1)[0], path.get(j - 1)[1], width, height, view);
                double[] b = project(path.get(j)[0], path.get(j)[1], width, height, view);
                g.strokeLine(a[0], a[1], b[0], b[1]);
            }
        }
    }

    /** Geo viewport into the bundled Brunei render (Web-Mercator tiles z11 x1675-1678 y995-997). */
    private record ViewBounds(double lonMin, double lonMax, double latMin, double latMax) { }
    private static final double MAP_LON_MIN = 114.43359375, MAP_LON_MAX = 115.13671875;
    private static final double MAP_LAT_MIN = 4.56547355071028, MAP_LAT_MAX = 5.09094417503339;
    private static final ViewBounds FULL_MAP = new ViewBounds(MAP_LON_MIN, MAP_LON_MAX, MAP_LAT_MIN, MAP_LAT_MAX);
    /** One bundled offline tile grid; the detail grid covers Bandar metro at higher resolution. */
    private record TileLevel(Image image, int z, int x0, int y0, int cols, int rows) {
        double lonMin() { return x0 / Math.pow(2, z) * 360 - 180; }
        double lonMax() { return (x0 + cols) / Math.pow(2, z) * 360 - 180; }
        double latAt(int y) { return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1 - 2.0 * y / Math.pow(2, z))))); }
        double latMax() { return latAt(y0); }
        double latMin() { return latAt(y0 + rows); }
        double lonToPixel(double lon) { return ((lon + 180) / 360 * Math.pow(2, z) - x0) * 256; }
        double latToPixel(double lat) {
            double rad = Math.toRadians(lat);
            return ((1 - Math.log(Math.tan(rad) + 1 / Math.cos(rad)) / Math.PI) / 2 * Math.pow(2, z) - y0) * 256;
        }
        boolean contains(ViewBounds view) {
            return view.lonMin() >= lonMin() && view.lonMax() <= lonMax()
                    && view.latMin() >= latMin() && view.latMax() <= latMax();
        }
    }

    /** Pick the sharpest bundled grid covering the view; street-level zooms use z13, wide views z11. */
    private TileLevel levelFor(ViewBounds view) {
        if (view.lonMax() - view.lonMin() < 0.24) {
            for (TileLevel level : List.of(metroLevel, tutongLevel, muaraLevel)) {
                if (level.image() != null && level.contains(view)) return level;
            }
        }
        return baseLevel;
    }

    private static double[] ll(double lon, double lat) { return new double[] {lon, lat}; }

    /** Zoom-to-content: bounds enclosing the given lon/lat points plus margin, matched to
     *  the canvas aspect, never tighter than a ~12 km window (tile detail limit). */
    private static ViewBounds fitBounds(List<double[]> points, double aspect) {
        if (points == null || points.isEmpty()) return FULL_MAP;
        double lonMin = 180, lonMax = -180, latMin = 90, latMax = -90;
        for (double[] p : points) {
            lonMin = Math.min(lonMin, p[0]); lonMax = Math.max(lonMax, p[0]);
            latMin = Math.min(latMin, p[1]); latMax = Math.max(latMax, p[1]);
        }
        double lonPad = Math.max((lonMax - lonMin) * 0.20, 0.07);
        double latPad = Math.max((latMax - latMin) * 0.20, 0.05);
        lonMin -= lonPad; lonMax += lonPad; latMin -= latPad; latMax += latPad;
        double lonSpan = Math.max(lonMax - lonMin, 0.14), latSpan = Math.max(latMax - latMin, 0.10);
        if (lonSpan / latSpan < aspect) lonSpan = latSpan * aspect;
        else latSpan = lonSpan / aspect;
        double lonMid = (lonMin + lonMax) / 2, latMid = (latMin + latMax) / 2;
        lonMin = lonMid - lonSpan / 2; lonMax = lonMid + lonSpan / 2;
        latMin = latMid - latSpan / 2; latMax = latMid + latSpan / 2;
        if (lonMax - lonMin >= MAP_LON_MAX - MAP_LON_MIN || latMax - latMin >= MAP_LAT_MAX - MAP_LAT_MIN) return FULL_MAP;
        if (lonMin < MAP_LON_MIN) { lonMax += MAP_LON_MIN - lonMin; lonMin = MAP_LON_MIN; }
        if (lonMax > MAP_LON_MAX) { lonMin -= lonMax - MAP_LON_MAX; lonMax = MAP_LON_MAX; }
        if (latMin < MAP_LAT_MIN) { latMax += MAP_LAT_MIN - latMin; latMin = MAP_LAT_MIN; }
        if (latMax > MAP_LAT_MAX) { latMin -= latMax - MAP_LAT_MAX; latMax = MAP_LAT_MAX; }
        return new ViewBounds(lonMin, lonMax, latMin, latMax);
    }

    private static double mercator(double lat) {
        double rad = Math.toRadians(lat);
        return Math.log(Math.tan(rad) + 1 / Math.cos(rad));
    }

    private double[] project(Stop stop, double width, double height, ViewBounds view) {
        return project(stop.x(), stop.y(), width, height, view);
    }

    private double[] project(double lon, double lat, double width, double height, ViewBounds view) {
        double pad = 14;
        double x = pad + (lon - view.lonMin()) / (view.lonMax() - view.lonMin()) * (width - pad * 2);
        double top = mercator(view.latMax()), bottom = mercator(view.latMin());
        double y = pad + (top - mercator(Math.min(Math.max(lat, view.latMin()), view.latMax()))) / (top - bottom) * (height - pad * 2);
        return new double[] {Math.min(Math.max(x, 10), width - 10), Math.min(Math.max(y, 22), height - 10)};
    }

    private void mapLabel(GraphicsContext g, String text, double x, double y) {
        g.setStroke(javafx.scene.paint.Color.WHITE); g.setLineWidth(3);
        g.strokeText(text, x, y);
        g.setFill(javafx.scene.paint.Color.web("#172033")); g.fillText(text, x, y);
    }

    private void drawVehicleMarker(GraphicsContext g, double width, double height, double x, double y, String label, String color, boolean stale, ViewBounds view) {
        double[] p = project(x, y, width, height, view); double px = p[0], py = p[1];
        g.setFill(javafx.scene.paint.Color.web(color)); g.fillOval(px - 9, py - 9, 18, 18);
        g.setStroke(javafx.scene.paint.Color.WHITE); g.setLineWidth(2); g.strokeOval(px - 9, py - 9, 18, 18);
        mapLabel(g, label + (stale ? " · STALE" : ""), px + 12, py + 4);
    }

    private void registerRouteInOrder(Shipment shipment, RoutePlan route) { shipment.setAssignments(shipment.driverId(), shipment.vehicleId(), route.id()); repository.saveShipment(shipment); }

    private ComboBox<User> driverCombo() {
>>>>>>> Sam-part
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

<<<<<<< HEAD
=======
    private static final class ListViewWithRemove extends VBox {
        ListViewWithRemove(ObservableList<Stop> items) {
            javafx.scene.control.ListView<Stop> list = new javafx.scene.control.ListView<>(items); list.setPrefHeight(120);
            list.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
                @Override protected void updateItem(Stop stop, boolean empty) {
                    super.updateItem(stop, empty);
                    if (empty || stop == null) { setText(null); return; }
                    setText(stop.id().startsWith("custom-")
                            ? stop.name() + String.format("  ·  %.4f, %.4f", stop.x(), stop.y())
                            : stop.name());
                }
            });
            Button remove = new Button("Remove selected stop"); remove.getStyleClass().add("secondary-button");
            remove.setOnAction(event -> {
                Stop selected = list.getSelectionModel().getSelectedItem();
                if (selected != null && !"DCLS Depot".equals(selected.name())) items.remove(selected);
            });
            getChildren().addAll(list, remove); setSpacing(6);
        }
    }

>>>>>>> Sam-part
    public static void main(String[] args) { launch(args); }
}
