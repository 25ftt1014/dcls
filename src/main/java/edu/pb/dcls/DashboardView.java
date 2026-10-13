package edu.pb.dcls;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final VBox dotList = new VBox(3);
    private final VBox statusBars = new VBox(7);
    private final VBox deliveryRows = new VBox(0);
    private final Label updatedAt = new Label();
    private final Label activeSummary = new Label();

    DashboardView(DclsApp app) {
        this.app = app;
        this.map = app.new MapController(430, 240);
        this.vehiclesCard = metric("Vehicles", "0", "");
        this.activeCard = metric("Active deliveries", "0", "");
        this.pendingCard = metric("Pending orders", "0", "");
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
        VBox page = pageHeader("", "");
        page.setSpacing(18);
        page.setPadding(new Insets(18, 24, 20, 28));
        page.getChildren().add(0, headerRow());

        HBox metrics = new HBox(18,
                vehiclesCard.card(), activeCard.card(), pendingCard.card(), completedCard.card());
        for (Node node : metrics.getChildren()) HBox.setHgrow(node, ALWAYS);

        map.content = () -> plots.values().stream()
                .map(plot -> new double[] {plot.lon(), plot.lat()}).toList();
        map.painter = (g, view) -> MapRenderer.paintVehicleDots(
                g, map.canvas.getWidth(), map.canvas.getHeight(), view, List.copyOf(plots.values()));
        installHover();

        VBox mapPanel = panel(new Label("Operations map"));
        ((Label) mapPanel.getChildren().get(0)).getStyleClass().add("section-title");
        mapPanel.getChildren().addAll(map.zoomBar, map.canvas, dotList, app.legendBox());

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
        page.getChildren().addAll(metrics, content);
        refresh();
        return page;
    }

    private HBox headerRow() {
        Label subtitle = new Label("Live fleet and delivery status across Brunei-Muara.");
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

        long active = shipmentRows.stream().filter(DashboardView::isActive).count();
        long assigned = shipmentRows.stream().filter(s -> s.status() == OrderStatus.ASSIGNED).count();
        long pending = shipmentRows.stream().filter(s -> s.status() == OrderStatus.PENDING).count();
        long road = onRoad(vehicleRows);

        vehiclesCard.value().setText(String.valueOf(vehicleRows.size()));
        vehiclesCard.caption().setText(road + " on the road");
        activeCard.value().setText(String.valueOf(active));
        activeCard.caption().setText(assigned + " assigned");
        pendingCard.value().setText(String.valueOf(pending));
        pendingCard.caption().setText("not yet assigned");
        completedCard.value().setText(String.valueOf(completedToday(shipmentRows)));
        completedCard.caption().setText("since midnight");
        updatedAt.setText("Updated " + LocalDateTime.now().format(CLOCK));
        activeSummary.setText(String.valueOf(active));

        paintStatusBars(statusCounts(shipmentRows));
        refreshPlots(shipmentRows);
        refreshVehicleList();
        refreshDeliveries(shipmentRows);
    }

    private List<Vehicle> fleetRows() {
        List<Vehicle> all = app.repository.vehicles();
        if (app.currentUser.role() != Role.DRIVER) return all;
        return all.stream().filter(v -> app.currentUser.id().equals(v.driverId())).toList();
    }

    /** One marker per vehicle on an active delivery; no-fix vehicles sit at the depot. */
    private void refreshPlots(List<Shipment> shipmentRows) {
        activeByVehicle.clear();
        for (Shipment shipment : shipmentRows)
            if (isActive(shipment) && shipment.vehicleId() != null)
                activeByVehicle.putIfAbsent(shipment.vehicleId(), shipment);

        plots.clear();
        for (Map.Entry<String, Shipment> entry : activeByVehicle.entrySet()) {
            Optional<Vehicle> vehicle = app.repository.vehicle(entry.getKey());
            if (vehicle.isEmpty()) continue;
            TrackingPoint fix = app.tracking.latestForVehicle(entry.getKey());
            plots.put(entry.getKey(), new VehiclePlot(
                    fix != null ? fix.x() : DEPOT.x(),
                    fix != null ? fix.y() : DEPOT.y(),
                    vehicle.get().plate() + " · " + app.driverName(vehicle.get().driverId()),
                    fix != null ? app.vehicleColor(entry.getKey()) : "#8a94a6",
                    app.tracking.gps().isStale(entry.getKey())));
        }
        map.redraw();
    }

    private void refreshVehicleList() {
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
            TrackingPoint fix = app.tracking.latestForVehicle(vehicleId);
            dotList.getChildren().add(app.legendSwatch(entry.getValue().color(),
                    entry.getValue().label() + " · " + duty.status().label()
                            + " · " + app.tracking.gpsStatus(vehicleId)
                            + " · ETA " + (fix != null ? fix.etaMinutes() + " min" : "—")));
        }
    }

    private void refreshDeliveries(List<Shipment> shipmentRows) {
        List<Shipment> queue = shipmentRows.stream()
                .filter(DashboardView::isActive)
                .sorted(byEtaThenAge(id -> {
                    TrackingPoint point = app.tracking.latestFor(id);
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
        Label pill = new Label(shipment.status().label());
        pill.getStyleClass().addAll("status-pill", pillClassFor(shipment.status().label()));
        Label eta = new Label(etaFor(shipment));
        eta.getStyleClass().add("detail-value");
        Region spacer = new Region();
        HBox.setHgrow(spacer, ALWAYS);
        HBox row = new HBox(8, id, pill, spacer, eta);
        row.getStyleClass().add("pill-row");
        return row;
    }

    private String describe(String vehicleId) {
        Optional<Vehicle> vehicle = app.repository.vehicle(vehicleId);
        Shipment duty = activeByVehicle.get(vehicleId);
        TrackingPoint fix = app.tracking.latestForVehicle(vehicleId);
        StringBuilder details = new StringBuilder(app.vehiclePlate(vehicleId));
        if (vehicle.isPresent()) details.append(" · ").append(vehicle.get().model());
        details.append("\nDriver: ").append(vehicle.isPresent() ? app.driverName(vehicle.get().driverId()) : "—");
        if (duty != null) details.append("\nStatus: ").append(duty.status().label()).append(" · ").append(duty.address());
        details.append("\nGPS: ").append(app.tracking.gpsStatus(vehicleId));
        if (fix != null) details.append("\nETA about ").append(fix.etaMinutes()).append(" min · updated ")
                .append(fix.recordedAt().format(DclsApp.DATE_TIME));
        else details.append("\nNo live fix yet — shown at depot.");
        return details.toString();
    }

    private String etaFor(Shipment shipment) {
        TrackingPoint point = app.tracking.latestFor(shipment.id());
        return point == null ? "—" : point.etaMinutes() + " min";
    }
}