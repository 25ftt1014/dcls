package edu.pb.dcls;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static edu.pb.dcls.AppServices.BruneiPlaces.DEPOT;
import static edu.pb.dcls.Domain.OrderStatus;
import static edu.pb.dcls.Domain.Role;
import static edu.pb.dcls.Domain.Shipment;
import static edu.pb.dcls.Domain.TrackingPoint;
import static edu.pb.dcls.Domain.Vehicle;
import static edu.pb.dcls.Domain.VehicleStatus;
import static edu.pb.dcls.MapRenderer.VehiclePlot;
import static edu.pb.dcls.UiFactory.metric;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.panel;
import static edu.pb.dcls.UiFactory.pillClassFor;
import static javafx.scene.layout.Priority.ALWAYS;

/**
 * Landing dashboard: headline metrics, operations map, live order-status breakdown,
 * and the active deliveries queue.
 *
 * <p>Built once and ticked in place. The tracking poll calls {@link #refresh()} every
 * few seconds, which mutates labels and repaints the existing canvas — it does not
 * rebuild the scene graph, so hover state, tooltip and manual map zoom survive a tick.
 */
final class DashboardView {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Charted statuses; CANCELLED is deliberately excluded from the breakdown. */
    private static final OrderStatus[] CHARTED = {
            OrderStatus.PENDING, OrderStatus.ASSIGNED, OrderStatus.IN_TRANSIT, OrderStatus.DELIVERED};

    private final DclsApp app;
    private final DclsApp.MapController map;
    /** vehicleId → marker. Single source for the map paint, the legend and the hover. */
    private final Map<String, VehiclePlot> plots = new LinkedHashMap<>();
    /** vehicleId → the active shipment that put it on the road. */
    private final Map<String, Shipment> activeByVehicle = new LinkedHashMap<>();

    private final UiFactory.Metric vehiclesCard, activeCard, pendingCard, completedCard;
    /** Vehicle the operator just clicked on the ops map; drawn with a pulse and panned to. */
    private String highlightedVehicle;
    /** Whether the initial auto-fit has happened; refreshes never re-fit after this. */
    private boolean framed;
    /** Popup shown when a vehicle marker is clicked. */
    private javafx.stage.Popup mapPopup;
    /** Notice shown over the map when no vehicle has a fix. */
    private final Label noFixNotice = new Label("No live GPS from any vehicle");
    private final VBox dotList = new VBox(3);
    private final VBox statusBars = new VBox(7);
    private final VBox deliveryRows = new VBox(0);
    private final HBox attentionCards = new HBox(10);
    private final Label updatedAt = new Label();
    private final Label activeSummary = new Label();
    private final Label attentionEmpty = new Label();
    private AppServices.TrackingService.TrackingSnapshot currentTrackingSnapshot =
            new AppServices.TrackingService.TrackingSnapshot(Map.of(), Map.of());
    private Button ordersAttention, trackingAttention, serviceAttention;

    DashboardView(DclsApp app) {
        this.app = app;
        this.map = app.new MapController(430, 240);
        Role role = app.currentUser.role();
        if (role == Role.DRIVER) {
            this.vehiclesCard = metric("My deliveries", "0", "active now");
            this.activeCard = metric("Assigned", "0", "ready to start");
            this.pendingCard = metric("In transit", "0", "on route");
        } else if (role == Role.DISPATCHER) {
            this.vehiclesCard = metric("Orders to dispatch", "0", "awaiting dispatch");
            this.activeCard = metric("Active deliveries", "0", "");
            this.pendingCard = metric("GPS alerts", "0", "active deliveries");
        } else {
            this.vehiclesCard = metric("Vehicles", "0", "");
            this.activeCard = metric("Active deliveries", "0", "");
            this.pendingCard = metric("Orders to dispatch", "0", "awaiting dispatch");
        }
        this.completedCard = metric("Completed today", "0", "");
    }

    // ---- pure, headless-testable derivations -------------------------------------

    /** A shipment occupying a vehicle right now: assigned to one, or moving on it. */
    static boolean isActive(Shipment shipment) {
        return shipment.status() == OrderStatus.ASSIGNED || shipment.status() == OrderStatus.IN_TRANSIT;
    }

    /** Delivered since midnight. An all-time DELIVERED count would not be "today". */
    static long completedToday(List<Shipment> rows) {
        if (rows == null) return 0;
        LocalDate today = LocalDate.now();
        return rows.stream()
                .filter(s -> s.status() == OrderStatus.DELIVERED)
                .filter(s -> s.deliveredAt() != null && s.deliveredAt().toLocalDate().equals(today))
                .count();
    }

    /** Vehicles actually out on a delivery, as opposed to the whole fleet. */
    static long onRoad(List<Vehicle> vehicles) {
        if (vehicles == null) return 0;
        return vehicles.stream().filter(v -> v.status() == VehicleStatus.IN_DELIVERY).count();
    }

    /** In-transit deliveries without a position fix or with a fix older than the live GPS threshold. */
    static long trackingIssueCount(List<Shipment> rows,
                                   AppServices.TrackingService.TrackingSnapshot tracking,
                                   LocalDateTime now) {
        if (rows == null || tracking == null || now == null) return 0;
        return rows.stream().filter(shipment -> shipment.status() == OrderStatus.IN_TRANSIT).filter(shipment -> {
            if (shipment.vehicleId() == null) return true;
            TrackingPoint point = tracking.byVehicle().get(shipment.vehicleId());
            return point == null || java.time.Duration.between(point.recordedAt(), now).getSeconds()
                    > AppServices.LiveGpsState.STALE_AFTER_SECONDS;
        }).count();
    }

    /** Shipment count per charted status; statuses outside {@link #CHARTED} are dropped. */
    static Map<OrderStatus, Integer> statusCounts(List<Shipment> rows) {
        Map<OrderStatus, Integer> counts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus status : CHARTED) counts.put(status, 0);
        if (rows != null) for (Shipment shipment : rows)
            if (counts.containsKey(shipment.status())) counts.merge(shipment.status(), 1, Integer::sum);
        return counts;
    }

    /**
     * Soonest ETA first so the delivery that needs attention leads the list; shipments
     * with no live fix sort last, then oldest first. {@code etaFor} returns
     * {@link Integer#MAX_VALUE} when a shipment has no fix.
     */
    static Comparator<Shipment> byEtaThenAge(ToIntFunction<String> etaFor) {
        return Comparator.<Shipment, Integer>comparing(s -> etaFor.applyAsInt(s.id()))
                .thenComparing(Shipment::createdAt);
    }

    // ---- scene graph (built once) ------------------------------------------------

    VBox page() {
        // pageHeader for its workspace class and padding; the title/subtitle args stay
        // empty because headerRow() supplies its own title line with the live timestamp.
        VBox page = pageHeader("");
        page.setSpacing(18);
        page.setPadding(new Insets(18, 24, 20, 28));
        HBox dashboardHeader = headerRow();
        page.getChildren().add(0, dashboardHeader);

        HBox metrics = new HBox(12,
                vehiclesCard.card(), activeCard.card(), pendingCard.card(), completedCard.card());
        for (Node node : metrics.getChildren()) HBox.setHgrow(node, ALWAYS);

        VBox attentionPanel = panel(new Label("Needs attention"));
        ((Label) attentionPanel.getChildren().get(0)).getStyleClass().add("section-title");
        attentionEmpty.getStyleClass().add("dashboard-empty");
        attentionEmpty.setWrapText(true);
        attentionCards.setAlignment(Pos.CENTER_LEFT);
        createAttentionActions();
        attentionPanel.getChildren().addAll(attentionEmpty, attentionCards);

        map.content = () -> plots.values().stream()
                .map(plot -> new double[] {plot.lon(), plot.lat()}).toList();
        map.painter = (g, view) -> MapRenderer.paintVehicleDots(
                g, map.canvas.getWidth(), map.canvas.getHeight(), view, List.copyOf(plots.values()));
        // Read-only map: clicks open a popup, they never place pins or edit routes.
        map.clickAction = point -> openMarkerPopup(point);
        installHover();

        Label mapTitle = new Label("Operations map");
        mapTitle.getStyleClass().add("section-title");
        Button fullScreen = new Button("Full screen map");
        fullScreen.getStyleClass().addAll("secondary-button", "dashboard-fullscreen");
        fullScreen.setAccessibleText("Open the operations map full screen");
        Button resetView = new Button("Reset view");
        resetView.getStyleClass().add("secondary-button");
        resetView.setAccessibleText("Reset the map view");
        resetView.setOnAction(e -> {
            map.manual = null;
            framed = false;
            map.redraw();
            framed = true;
            map.manual = map.currentView();
            noFixNotice.setVisible(!anyHasFix());
        });
        HBox mapHeader = new HBox(10, mapTitle, new Region(), resetView, fullScreen);
        mapHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(mapHeader.getChildren().get(1), ALWAYS);
        VBox mapPanel = panel(mapHeader);

        StackPane mapViewport = new StackPane(map.canvas);
        mapViewport.getStyleClass().add("dashboard-map-viewport");
        mapViewport.setMinHeight(420);
        mapViewport.setPrefHeight(520);
        noFixNotice.getStyleClass().addAll("dashboard-map-notice", "muted");
        noFixNotice.setManaged(false);
        noFixNotice.setVisible(false);
        noFixNotice.setMouseTransparent(true);
        mapViewport.getChildren().add(noFixNotice);
        StackPane.setAlignment(noFixNotice, Pos.CENTER);
        // Canvas is resizable but reports its current size as its preferred size.
        // Keep it out of the viewport's layout measurement to prevent a sizing loop.
        map.canvas.setManaged(false);
        Runnable resizeMap = () -> {
            double width = mapViewport.getWidth();
            double height = mapViewport.getHeight();
            if (width > 0 && Math.abs(map.canvas.getWidth() - width) > 0.5) {
                map.canvas.setWidth(width);
            }
            if (height > 0 && Math.abs(map.canvas.getHeight() - height) > 0.5) {
                map.canvas.setHeight(height);
            }
            map.canvas.relocate(0, 0);
        };
        mapViewport.widthProperty().addListener((observable, oldWidth, newWidth) -> resizeMap.run());
        mapViewport.heightProperty().addListener((observable, oldHeight, newHeight) -> resizeMap.run());
        map.canvas.widthProperty().addListener((observable, oldWidth, newWidth) -> map.requestRedraw());
        map.canvas.heightProperty().addListener((observable, oldHeight, newHeight) -> map.requestRedraw());
        VBox.setVgrow(mapViewport, ALWAYS);

        // Zoom controls as a floating overlay in the corner, plus a compact chip row
        // and a single-line legend popover instead of three text lines under the map.
        StackPane zoomHost = new StackPane(map.zoomStack);
        zoomHost.setPickOnBounds(false);
        zoomHost.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        mapViewport.getChildren().add(zoomHost);
        StackPane.setAlignment(zoomHost, Pos.TOP_RIGHT);
        zoomHost.setPadding(new Insets(10, 10, 0, 0));

        HBox chipRow = new HBox(8);
        chipRow.getStyleClass().add("dashboard-chip-row");
        chipRow.setAlignment(Pos.CENTER_LEFT);
        chipRow.setPickOnBounds(false);
        mapPanel.getChildren().addAll(mapViewport, chipRow);

        Button legendButton = new Button("Legend");
        legendButton.getStyleClass().addAll("secondary-button", "dashboard-legend-toggle");
        legendButton.setAccessibleText("Show map legend");
        VBox legendContent = new VBox(4, app.legendBox());
        legendContent.setPadding(new Insets(8));
        legendContent.getStyleClass().add("legend-popover");
        javafx.stage.Popup legendPopover = new javafx.stage.Popup();
        legendPopover.getContent().add(legendContent);
        legendButton.setOnAction(e -> {
            if (legendPopover.isShowing()) legendPopover.hide();
            else legendPopover.show(legendButton, 0, legendButton.getHeight());
        });
        chipRow.getChildren().add(legendButton);

        VBox statusPanel = panel(new Label("Order status"));
        ((Label) statusPanel.getChildren().get(0)).getStyleClass().add("section-title");
        statusPanel.getChildren().add(statusBars);

        VBox listPanel = panel(new Label("Active deliveries"));
        ((Label) listPanel.getChildren().get(0)).getStyleClass().add("section-title");
        HBox summary = new HBox(8, new Label("Currently active"), new Region(), activeSummary);
        summary.getStyleClass().add("activity-summary");
        HBox.setHgrow(summary.getChildren().get(1), ALWAYS);
        listPanel.getChildren().addAll(summary, deliveryRows);

        VBox rightColumn = new VBox(14, statusPanel, listPanel);
        rightColumn.setPrefWidth(264);
        rightColumn.setMinWidth(264);

        HBox content = new HBox(14, mapPanel, rightColumn);
        HBox.setHgrow(mapPanel, ALWAYS);
        VBox.setVgrow(content, ALWAYS);
        page.getChildren().addAll(metrics, attentionPanel, content);

        boolean[] fullScreenMode = {false};
        Runnable toggleFullScreen = () -> {
            fullScreenMode[0] = !fullScreenMode[0];
            boolean expanded = fullScreenMode[0];
            dashboardHeader.setVisible(!expanded);
            dashboardHeader.setManaged(!expanded);
            metrics.setVisible(!expanded);
            metrics.setManaged(!expanded);
            attentionPanel.setVisible(!expanded);
            attentionPanel.setManaged(!expanded);
            rightColumn.setVisible(!expanded);
            rightColumn.setManaged(!expanded);
            if (expanded) {
                mapViewport.prefHeightProperty().bind(app.stage.heightProperty().subtract(120));
                fullScreen.setText("Exit full screen  ·  Esc");
                fullScreen.setAccessibleText("Exit full-screen map");
            } else {
                mapViewport.prefHeightProperty().unbind();
                mapViewport.setPrefHeight(520);
                fullScreen.setText("Full screen map");
                fullScreen.setAccessibleText("Open the operations map full screen");
            }
            app.setDashboardMapFullscreen(expanded);
            page.requestLayout();
            map.requestRedraw();
        };
        fullScreen.setOnAction(event -> toggleFullScreen.run());
        page.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (fullScreenMode[0] && event.getCode() == KeyCode.ESCAPE) {
                toggleFullScreen.run();
                event.consume();
            }
        });

        refresh();
        return page;
    }

    private void createAttentionActions() {
        ordersAttention = attentionButton("Review orders", () -> app.showPage("orders"));
        trackingAttention = attentionButton("Open live tracking", () -> app.showPage("tracking"));
        serviceAttention = attentionButton("Review maintenance", () -> {
            app.fleetRecordsView = "maintenance";
            app.showPage("fleet");
        });
        Role role = app.currentUser.role();
        if (role == Role.DRIVER) attentionCards.getChildren().addAll(trackingAttention, ordersAttention);
        else if (role == Role.DISPATCHER) attentionCards.getChildren().addAll(ordersAttention, trackingAttention);
        else attentionCards.getChildren().addAll(ordersAttention, serviceAttention, trackingAttention);
        for (Node node : attentionCards.getChildren()) HBox.setHgrow(node, ALWAYS);
    }

    private static Button attentionButton(String accessibleText, Runnable action) {
        Button button = new Button(accessibleText);
        button.getStyleClass().add("dashboard-attention");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMinHeight(58);
        button.setWrapText(true);
        button.setAccessibleText(accessibleText);
        button.setOnAction(event -> action.run());
        return button;
    }

    private HBox headerRow() {
        String subtitleText = switch (app.currentUser.role()) {
            case DRIVER -> "Your assigned deliveries and live GPS status.";
            case DISPATCHER -> "Orders waiting for dispatch and live delivery exceptions.";
            default -> "Fleet readiness, service needs, and delivery exceptions.";
        };
        Label subtitle = new Label(subtitleText);
        subtitle.getStyleClass().add("page-subtitle");
        updatedAt.getStyleClass().add("muted");
        updatedAt.setStyle("-fx-font-size: 11px;");
        HBox row = new HBox(12, subtitle, updatedAt);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(subtitle, ALWAYS);
        return row;
    }

    /** Hover detail on the ops map. Hit-testing reads the live view, not a captured one,
     *  so it stays correct after a zoom or pan. */
    /** True when at least one vehicle on the map has a live fix. */
    private boolean anyHasFix() {
        for (VehiclePlot plot : plots.values()) if (!plot.stale()) return true;
        return false;
    }

    /** Opens the click popup for the vehicle nearest to the click, if any. */
    private void openMarkerPopup(double[] point) {
        if (mapPopup != null && mapPopup.isShowing()) mapPopup.hide();
        String hit = nearestVehicle(point);
        if (hit == null) return;
        highlightVehicle(hit);
        Shipment duty = activeByVehicle.get(hit);
        Vehicle vehicle = app.repository.vehicle(hit).orElse(null);
        TrackingPoint fix = currentTrackingSnapshot.byVehicle().get(hit);
        VBox content = new VBox(6);
        content.setPadding(new Insets(10));
        content.getStyleClass().add("legend-popover");
        Label title = new Label(app.vehiclePlate(hit));
        title.getStyleClass().add("section-title");
        Label driver = new Label("Driver: " + (vehicle == null ? "—" : app.driverName(vehicle.driverId())));
        driver.getStyleClass().add("muted");
        Label order = new Label("Order: " + (duty == null ? "—" : duty.id()));
        order.getStyleClass().add("muted");
        Label status = new Label("Status: " + (duty == null ? "—" : duty.status().label()));
        status.getStyleClass().add("muted");
        Label eta = new Label("ETA: " + (fix == null ? "—" : fix.etaMinutes() + " min"));
        eta.getStyleClass().add("muted");
        Label lastFix = new Label("Last fix: " + (fix == null ? "no fix" : fix.recordedAt().format(DclsApp.DATE_TIME)));
        lastFix.getStyleClass().add("muted");
        Button openTracking = new Button("Open in Live tracking");
        openTracking.getStyleClass().add("primary-button");
        openTracking.setMaxWidth(Double.MAX_VALUE);
        openTracking.setOnAction(e -> {
            if (mapPopup != null) mapPopup.hide();
            app.focusVehicleTour(hit, duty == null ? null : duty.id());
        });
        content.getChildren().addAll(title, driver, order, status, eta, lastFix, openTracking);
        mapPopup = new javafx.stage.Popup();
        mapPopup.getContent().add(content);
        mapPopup.setAutoHide(true);
        mapPopup.show(map.canvas, 0, 0);
        mapPopup.setX(map.canvas.localToScreen(map.canvas.getWidth() / 2, 0).getX());
    }

    /** Pans and highlights a vehicle on the map. Used by marker clicks, delivery rows and attention items. */
    void highlightVehicle(String vehicleId) {
        highlightedVehicle = vehicleId;
        Shipment duty = vehicleId == null ? null : activeByVehicle.get(vehicleId);
        TrackingPoint fix = vehicleId == null ? null : currentTrackingSnapshot.byVehicle().get(vehicleId);
        if (fix != null && map != null) map.zoomTo(fix.x(), fix.y());
        map.requestRedraw();
        if (duty != null) app.refreshFocusChip();
    }

    private String nearestVehicle(double[] point) {
        if (point == null || plots.isEmpty()) return null;
        MapRenderer.ViewBounds view = map.currentView();
        double w = map.canvas.getWidth(), h = map.canvas.getHeight();
        String hit = null;
        double best = 16 * 16;
        for (Map.Entry<String, VehiclePlot> entry : plots.entrySet()) {
            double[] p = MapRenderer.projectIfVisible(entry.getValue().lon(), entry.getValue().lat(), w, h, view);
            if (p == null) continue;
            double dx = p[0] - point[0];
            double dy = p[1] - point[1];
            double d = dx * dx + dy * dy;
            if (d < best) { best = d; hit = entry.getKey(); }
        }
        return hit;
    }

    private void installHover() {
        Tooltip tip = new Tooltip();
        map.canvas.setOnMouseMoved(event -> {
            MapRenderer.ViewBounds view = map.currentView();
            double w = map.canvas.getWidth(), h = map.canvas.getHeight();
            String hit = null;
            double best = 14 * 14;
            for (Map.Entry<String, VehiclePlot> entry : plots.entrySet()) {
                VehiclePlot plot = entry.getValue();
                double[] p = MapRenderer.projectIfVisible(plot.lon(), plot.lat(), w, h, view);
                if (p == null) continue;
                double dx = p[0] - event.getX(), dy = p[1] - event.getY();
                double distance = dx * dx + dy * dy;
                if (distance <= best) { best = distance; hit = entry.getKey(); }
            }
            if (hit == null) { tip.hide(); return; }
            tip.setText(describe(hit));
            if (!tip.isShowing()) tip.show(map.canvas, event.getScreenX() + 12, event.getScreenY() + 12);
        });
        map.canvas.setOnMouseExited(event -> tip.hide());
    }

    // ---- tick --------------------------------------------------------------------

    /** Recompute every live value in place. Safe to call as often as the poll likes. */
    void refresh() {
        List<Shipment> shipmentRows = app.visibleShipments();
        List<Vehicle> vehicleRows = fleetRows();
        Map<String, Vehicle> vehiclesById = new LinkedHashMap<>();
        for (Vehicle vehicle : vehicleRows) vehiclesById.put(vehicle.id(), vehicle);
        currentTrackingSnapshot = app.tracking.latestSnapshot();

        long active = shipmentRows.stream().filter(DashboardView::isActive).count();
        long assigned = shipmentRows.stream().filter(s -> s.status() == OrderStatus.ASSIGNED).count();
        long inTransit = shipmentRows.stream().filter(s -> s.status() == OrderStatus.IN_TRANSIT).count();
        long pending = shipmentRows.stream().filter(s -> s.status() == OrderStatus.PENDING).count();
        long road = onRoad(vehicleRows);
        long trackingIssues = trackingIssueCount(shipmentRows, currentTrackingSnapshot, LocalDateTime.now());

        if (app.currentUser.role() == Role.DRIVER) {
            vehiclesCard.value().setText(String.valueOf(active));
            activeCard.value().setText(String.valueOf(assigned));
            pendingCard.value().setText(String.valueOf(inTransit));
        } else if (app.currentUser.role() == Role.DISPATCHER) {
            vehiclesCard.value().setText(String.valueOf(pending));
            vehiclesCard.caption().setText("awaiting dispatch");
            activeCard.value().setText(String.valueOf(active));
            activeCard.caption().setText(assigned + " assigned");
            pendingCard.value().setText(String.valueOf(trackingIssues));
            pendingCard.caption().setText("need a live GPS fix");
        } else {
            vehiclesCard.value().setText(String.valueOf(vehicleRows.size()));
            vehiclesCard.caption().setText(road + " on the road");
            activeCard.value().setText(String.valueOf(active));
            activeCard.caption().setText(assigned + " assigned");
            pendingCard.value().setText(String.valueOf(pending));
            pendingCard.caption().setText("awaiting dispatch");
        }
        completedCard.value().setText(String.valueOf(completedToday(shipmentRows)));
        completedCard.caption().setText("since midnight");
        updatedAt.setText("Updated " + LocalDateTime.now().format(CLOCK));
        activeSummary.setText(String.valueOf(active));

        refreshAttention(vehicleRows, pending, trackingIssues, active);
        paintStatusBars(statusCounts(shipmentRows));
        refreshPlots(shipmentRows, vehiclesById, currentTrackingSnapshot);
        refreshVehicleList(currentTrackingSnapshot);
        refreshDeliveries(shipmentRows, currentTrackingSnapshot);
        // Auto-fit only on the first load. Every later refresh redraws with the sticky
        // manual view, so an operator who zoomed or panned keeps their view.
        if (!framed && map.manual == null) {
            framed = true;
            map.manual = map.currentView();
        }
        noFixNotice.setVisible(!anyHasFix());
    }

    private void refreshAttention(List<Vehicle> vehicleRows, long pending, long trackingIssues, long active) {
        long serviceDue = 0;
        if (app.currentUser.role() == Role.DRIVER) {
            ordersAttention.setText("My deliveries  ·  " + active + " active\nOpen My Orders");
            ordersAttention.setAccessibleText("My deliveries: " + active + " active. Open My Orders.");
        } else {
            ordersAttention.setText("Orders to dispatch  ·  " + pending + "\nOpen Orders");
            ordersAttention.setAccessibleText("Orders to dispatch: " + pending + " pending orders. Open Orders.");
        }
        trackingAttention.setText("Tracking needs attention  ·  " + trackingIssues + "\nOpen Live Tracking");
        trackingAttention.setAccessibleText("Tracking needs attention for " + trackingIssues + " in-transit deliveries. Open Live Tracking.");
        if (serviceAttention != null) {
            long overdue = 0, dueSoon = 0;
            for (Vehicle vehicle : vehicleRows) {
                AppServices.FleetService.MaintenanceStatus status = app.fleet.maintenanceStatus(vehicle);
                if (status == AppServices.FleetService.MaintenanceStatus.OVERDUE) overdue++;
                else if (status == AppServices.FleetService.MaintenanceStatus.DUE_SOON) dueSoon++;
            }
            serviceDue = overdue + dueSoon;
            serviceAttention.setText("Vehicle service  ·  " + (overdue + dueSoon) + " need attention\n"
                    + overdue + " overdue, " + dueSoon + " due soon");
            serviceAttention.setAccessibleText("Vehicle service: " + overdue + " overdue and "
                    + dueSoon + " due soon. Review maintenance.");
        }
        boolean clear = pending == 0 && trackingIssues == 0 && serviceDue == 0;
        attentionEmpty.setText(clear ? "No urgent work needs attention right now." : "Select an item to open its workspace.");
        attentionEmpty.setVisible(true);
        attentionEmpty.setManaged(true);
    }

    private List<Vehicle> fleetRows() {
        List<Vehicle> all = app.repository.vehicles();
        if (app.currentUser.role() != Role.DRIVER) return all;
        return all.stream().filter(v -> app.currentUser.id().equals(v.driverId())).toList();
    }

    /** One marker per vehicle on an active delivery; no-fix vehicles sit at the depot. */
    private void refreshPlots(List<Shipment> shipmentRows, Map<String, Vehicle> vehiclesById,
                              AppServices.TrackingService.TrackingSnapshot tracking) {
        activeByVehicle.clear();
        for (Shipment shipment : shipmentRows)
            if (isActive(shipment) && shipment.vehicleId() != null)
                activeByVehicle.putIfAbsent(shipment.vehicleId(), shipment);

        plots.clear();
        for (Map.Entry<String, Shipment> entry : activeByVehicle.entrySet()) {
            Vehicle vehicle = vehiclesById.get(entry.getKey());
            if (vehicle == null) continue;
            TrackingPoint fix = tracking.byVehicle().get(entry.getKey());
            plots.put(entry.getKey(), new VehiclePlot(
                    fix != null ? fix.x() : DEPOT.x(),
                    fix != null ? fix.y() : DEPOT.y(),
                    vehicle.plate() + " · " + app.driverName(vehicle.driverId()),
                    fix != null ? app.vehicleColor(entry.getKey()) : "#8a94a6",
                    fix == null || java.time.Duration.between(fix.recordedAt(), LocalDateTime.now()).getSeconds()
                            > AppServices.LiveGpsState.STALE_AFTER_SECONDS));
        }
        map.redraw();
    }

    private void refreshVehicleList(AppServices.TrackingService.TrackingSnapshot tracking) {
        dotList.getChildren().clear();
        if (plots.isEmpty()) {
            Label none = new Label("No active vehicles on the road.");
            none.getStyleClass().add("muted");
            dotList.getChildren().add(none);
            return;
        }
        for (Map.Entry<String, VehiclePlot> entry : plots.entrySet()) {
            String vehicleId = entry.getKey();
            Shipment duty = activeByVehicle.get(vehicleId);
            TrackingPoint fix = tracking.byVehicle().get(vehicleId);
            dotList.getChildren().add(app.legendSwatch(entry.getValue().color(),
                    entry.getValue().label() + " · " + duty.status().label()
                            + " · " + gpsStatus(fix)
                            + " · ETA " + (fix != null ? fix.etaMinutes() + " min" : "—")));
        }
    }

    private void refreshDeliveries(List<Shipment> shipmentRows,
                                   AppServices.TrackingService.TrackingSnapshot tracking) {
        List<Shipment> queue = shipmentRows.stream()
                .filter(DashboardView::isActive)
                .sorted(byEtaThenAge(id -> {
                    TrackingPoint point = tracking.byShipment().get(id);
                    return point == null ? Integer.MAX_VALUE : point.etaMinutes();
                }))
                .limit(6)
                .toList();
        deliveryRows.getChildren().clear();
        if (queue.isEmpty()) {
            Label none = new Label("No active deliveries.");
            none.getStyleClass().add("muted");
            deliveryRows.getChildren().add(none);
            return;
        }
        for (Shipment shipment : queue) deliveryRows.getChildren().add(deliveryRow(shipment));
    }

    private void paintStatusBars(Map<OrderStatus, Integer> counts) {
        int max = 1;
        for (int count : counts.values()) max = Math.max(max, count);
        statusBars.getChildren().clear();
        for (OrderStatus status : CHARTED) {
            int count = counts.getOrDefault(status, 0);
            Label name = new Label(status.label());
            name.getStyleClass().add("muted");
            name.setMinWidth(62);
            ProgressBar bar = new ProgressBar(count / (double) max);
            bar.getStyleClass().add("status-bar");
            Label value = new Label(String.valueOf(count));
            value.getStyleClass().add("metric-caption");
            HBox row = new HBox(8, name, bar, value);
            row.getStyleClass().add("status-row");
            row.setAlignment(Pos.CENTER_LEFT);
            statusBars.getChildren().add(row);
        }
    }

    private HBox deliveryRow(Shipment shipment) {
        Label id = new Label(shipment.id());
        id.getStyleClass().add("delivery-id");
        Label vehicle = new Label(app.vehiclePlate(shipment.vehicleId()));
        vehicle.getStyleClass().add("muted");
        Label destination = new Label(shipment.address());
        destination.getStyleClass().add("muted");
        destination.setWrapText(true);
        destination.setMaxWidth(180);
        destination.setTooltip(new Tooltip(shipment.address()));
        Label eta = new Label(etaFor(shipment));
        eta.getStyleClass().add("detail-value");
        Region spacer = new Region();
        HBox.setHgrow(spacer, ALWAYS);
        HBox row = new HBox(8, id, vehicle, destination, spacer, eta);
        row.getStyleClass().add("pill-row");
        row.setOnMouseClicked(e -> {
            if (shipment.vehicleId() != null) highlightVehicle(shipment.vehicleId());
        });
        return row;
    }

    private String describe(String vehicleId) {
        Vehicle vehicle = app.repository.vehicle(vehicleId).orElse(null);
        Shipment duty = activeByVehicle.get(vehicleId);
        TrackingPoint fix = currentTrackingSnapshot.byVehicle().get(vehicleId);
        StringBuilder details = new StringBuilder(app.vehiclePlate(vehicleId));
        if (vehicle != null) details.append(" · ").append(vehicle.model());
        details.append("\nDriver: ").append(vehicle != null ? app.driverName(vehicle.driverId()) : "—");
        if (duty != null) details.append("\nStatus: ").append(duty.status().label()).append(" · ").append(duty.address());
        details.append("\nGPS: ").append(gpsStatus(fix));
        if (fix != null) details.append("\nETA about ").append(fix.etaMinutes()).append(" min · updated ")
                .append(fix.recordedAt().format(DclsApp.DATE_TIME));
        else details.append("\nNo live fix yet — shown at depot.");
        return details.toString();
    }

    private String etaFor(Shipment shipment) {
        TrackingPoint point = currentTrackingSnapshot.byShipment().get(shipment.id());
        return point == null ? "—" : point.etaMinutes() + " min";
    }

    private static String gpsStatus(TrackingPoint point) {
        if (point == null) return "NO FIX";
        long age = Math.max(0, java.time.Duration.between(point.recordedAt(), LocalDateTime.now()).getSeconds());
        if (age > AppServices.LiveGpsState.STALE_AFTER_SECONDS) return "LOST · " + age + "s ago";
        String accuracy = point.accuracyM() == null ? "" : "·±" + Math.round(point.accuracyM()) + "m";
        String speed = point.speedKmh() > 0.5 ? "·" + Math.round(point.speedKmh()) + "km/h" : "";
        return (point.fixType() == null ? "GPS" : point.fixType().label()) + accuracy + speed;
    }
}
