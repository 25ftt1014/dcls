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
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
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
        card.getChildren().addAll(formTitle, hint, new Region(), labeled("EMAIL", email), labeled("PASSWORD", UiFactory.passwordInput(password)), loginOptions, error, demoInfo, submit, register);
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
        VBox form = new VBox(12, heading, description, new Region(), labeled("FULL NAME", name), labeled("EMAIL", email), labeled("PHONE NUMBER", phone), labeled("PASSWORD", UiFactory.passwordInput(password)), error, create, back);
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

    private VBox buildNav() {
        VBox sidebar = new VBox(7);
        sidebar.setPrefWidth(72); sidebar.setMinWidth(72); sidebar.setPadding(new Insets(15, 8, 8, 8));
        sidebar.getStyleClass().add("sidebar");
        InputStream logo = getClass().getResourceAsStream("/edu/pb/dcls/Logo.svg");
        if (logo != null) {
            ImageView mark = new ImageView(new Image(logo));
            mark.setFitWidth(36); mark.setFitHeight(36); mark.setPreserveRatio(true);
            VBox markWrap = new VBox(mark); markWrap.setAlignment(Pos.CENTER); markWrap.setPadding(new Insets(0, 0, 17, 0));
            sidebar.getChildren().add(markWrap);
        }
        addNav(sidebar, "dashboard", "▦", "Dashboard");
        addNav(sidebar, "users", "♙", "Users and roles");
        addNav(sidebar, "fleet", "▤", "Fleet");
        addNav(sidebar, "orders", "▣", "Orders and shipments");
        addNav(sidebar, "routes", "⌁", "Route planning");
        addNav(sidebar, "tracking", "◉", "Live tracking");
        Region spacer = new Region(); VBox.setVgrow(spacer, ALWAYS); sidebar.getChildren().add(spacer);
        MenuButton settings = new MenuButton("Setting  ›"); settings.setMaxWidth(Double.MAX_VALUE); settings.setAccessibleText("Settings and profile"); settings.getStyleClass().add("settings-button");
        settings.getItems().addAll(menu("My profile", this::profileDialog), menu("Change password", this::passwordDialog), menu("Sign out", () -> { stopTracking(); currentUser = null; showLogin(); }));
        sidebar.getChildren().add(settings);
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
        Label title = new Label(activePage.equals("dashboard") ? "Dashboard" : pageLabel(activePage)); title.setStyle("-fx-font-size: 16px; -fx-font-weight: 700;");
        dataModeLabel = new Label("");
        Region spacer = new Region(); HBox.setHgrow(spacer, ALWAYS);
        bar.getChildren().addAll(title, spacer);
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
        VBox form = new VBox(10, labeled("Current password", UiFactory.passwordInput(current)), labeled("New password", UiFactory.passwordInput(next)), labeled("Confirm new password", UiFactory.passwordInput(confirm)));
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
    void toast(String message) { if (dataModeLabel != null) { dataModeLabel.setText(message); dataModeLabel.setStyle("-fx-text-fill: #17613f; -fx-font-size: 12px;"); javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(Duration.seconds(3)); pause.setOnFinished(event -> { if (dataModeLabel != null) dataModeLabel.setText(repository.persistent() ? "MySQL connected" : "Demo data"); }); pause.play(); } }
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
