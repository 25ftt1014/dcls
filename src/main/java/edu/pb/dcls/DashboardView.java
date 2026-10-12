package edu.pb.dcls;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static edu.pb.dcls.AppServices.BruneiPlaces.DEPOT;
import static edu.pb.dcls.Domain.OrderStatus;
import static edu.pb.dcls.Domain.Role;
import static edu.pb.dcls.Domain.Shipment;
import static edu.pb.dcls.Domain.TrackingPoint;
import static edu.pb.dcls.Domain.Vehicle;
import static edu.pb.dcls.MapRenderer.activityChart;
import static edu.pb.dcls.MapRenderer.addTileListener;
import static edu.pb.dcls.MapRenderer.drawVehicles;
import static edu.pb.dcls.MapRenderer.mapCanvas;
import static edu.pb.dcls.MapRenderer.ViewBounds;
import static edu.pb.dcls.MapRenderer.VehiclePlot;
import static edu.pb.dcls.UiFactory.metric;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.panel;
import static javafx.scene.layout.Priority.ALWAYS;

/** Landing dashboard: headline metrics, operations map, and recent active deliveries. */
final class DashboardView {
    private final DclsApp app;

    DashboardView(DclsApp app) { this.app = app; }

    VBox page() {
        List<Shipment> shipmentRows = app.visibleShipments();
        List<Vehicle> vehicleRows = app.currentUser.role() == Role.DRIVER
                ? app.repository.vehicles().stream().filter(v -> app.currentUser.id().equals(v.driverId())).toList()
                : app.repository.vehicles();
        long active = shipmentRows.stream().filter(s -> s.status() == OrderStatus.ASSIGNED || s.status() == OrderStatus.IN_TRANSIT).count();
        long pending = shipmentRows.stream().filter(s -> s.status() == OrderStatus.PENDING).count();
        long completed = shipmentRows.stream().filter(s -> s.status() == OrderStatus.DELIVERED).count();
        VBox page = pageHeader("", ""); page.setSpacing(18); page.setPadding(new Insets(18, 24, 20, 28));
        HBox metrics = new HBox(18,
                metric("Vehicles", String.valueOf(vehicleRows.size()), ""),
                metric("Active deliveries", String.valueOf(active), ""),
                metric("Pending orders", String.valueOf(pending), ""),
                metric("Completed today", String.valueOf(completed), ""));
        for (Node node : metrics.getChildren()) HBox.setHgrow(node, ALWAYS);
        VBox mapPanel = panel(new Label("Operations map")); ((Label) mapPanel.getChildren().get(0)).getStyleClass().add("section-title");
        Canvas map = mapCanvas(430, 210);
        // Operations map tracks active vehicles only: one dot per vehicle on an
        // assigned or in-transit delivery. No routes, no stop pins on this map.
        Map<String, Shipment> activeByVehicle = new LinkedHashMap<>();
        for (Shipment shipment : shipmentRows) {
            if ((shipment.status() == OrderStatus.ASSIGNED || shipment.status() == OrderStatus.IN_TRANSIT)
                    && shipment.vehicleId() != null && !activeByVehicle.containsKey(shipment.vehicleId()))
                activeByVehicle.put(shipment.vehicleId(), shipment);
        }
        List<VehiclePlot> plots = new ArrayList<>();
        List<String> plotVehicles = new ArrayList<>();
        for (Map.Entry<String, Shipment> entry : activeByVehicle.entrySet()) {
            Optional<Vehicle> vehicle = app.repository.vehicle(entry.getKey());
            if (vehicle.isEmpty()) continue;
            TrackingPoint fix = app.tracking.latestForVehicle(entry.getKey());
            boolean stale = app.tracking.gps().isFixLost(entry.getKey());
            plots.add(new VehiclePlot(
                    fix != null ? fix.x() : DEPOT.x(),
                    fix != null ? fix.y() : DEPOT.y(),
                    vehicle.get().plate() + " · " + app.driverName(vehicle.get().driverId()),
                    fix != null ? app.vehicleColor(entry.getKey()) : "#8a94a6",
                    stale));
            plotVehicles.add(entry.getKey());
        }
        ViewBounds opsView = drawVehicles(map, plots);
        mapPanel.getChildren().add(map);
        Runnable opsRefresh = () -> drawVehicles(map, plots);
        map.getProperties().put("dcls-tile-refresh", opsRefresh);
        addTileListener(opsRefresh);
        VBox dotList = new VBox(3);
        if (plots.isEmpty()) {
            Label none = new Label("No active vehicles on the road."); none.getStyleClass().add("muted");
            dotList.getChildren().add(none);
        } else {
            for (int i = 0; i < plots.size(); i++) {
                String vehicleId = plotVehicles.get(i);
                Shipment duty = activeByVehicle.get(vehicleId);
                TrackingPoint fix = app.tracking.latestForVehicle(vehicleId);
                dotList.getChildren().add(app.legendSwatch(plots.get(i).color(),
                        plots.get(i).label() + " · " + duty.status().label()
                                + " · " + app.tracking.gpsStatus(vehicleId)
                                + " · ETA " + (fix != null ? fix.etaMinutes() + " min" : "—")));
            }
        }
        mapPanel.getChildren().add(dotList);
        Tooltip opsTip = new Tooltip();
        Tooltip.install(map, opsTip);
        map.setOnMouseMoved(event -> {
            double w = map.getWidth(), h = map.getHeight();
            int hit = -1; double best = 14 * 14;
            for (int i = 0; i < plots.size(); i++) {
                double[] p = edu.pb.dcls.MapRenderer.project(plots.get(i).lon(), plots.get(i).lat(), w, h, opsView);
                double d = (p[0] - event.getX()) * (p[0] - event.getX()) + (p[1] - event.getY()) * (p[1] - event.getY());
                if (d <= best) { best = d; hit = i; }
            }
            if (hit < 0) { opsTip.hide(); return; }
            String vehicleId = plotVehicles.get(hit);
            Optional<Vehicle> vehicle = app.repository.vehicle(vehicleId);
            Shipment duty = activeByVehicle.get(vehicleId);
            TrackingPoint fix = app.tracking.latestForVehicle(vehicleId);
            StringBuilder details = new StringBuilder(app.vehiclePlate(vehicleId));
            if (vehicle.isPresent()) details.append(" · ").append(vehicle.get().model());
            details.append("\nDriver: ").append(vehicle.isPresent() ? app.driverName(vehicle.get().driverId()) : "—");
            if (duty != null) details.append("\nStatus: ").append(duty.status().label()).append(" · ").append(duty.address());
            details.append("\nGPS: ").append(app.tracking.gpsStatus(vehicleId));
            if (fix != null) details.append("\nETA about ").append(fix.etaMinutes()).append(" min · updated ").append(fix.recordedAt().format(DclsApp.DATE_TIME));
            else details.append("\nNo simulated fix yet — shown at depot.");
            opsTip.setText(details.toString());
            if (!opsTip.isShowing()) opsTip.show(map, event.getScreenX() + 12, event.getScreenY() + 12);
        });
        map.setOnMouseExited(event -> opsTip.hide());
        VBox activity = panel(new Label("Active deliveries")); ((Label) activity.getChildren().get(0)).getStyleClass().add("section-title");
        HBox summary = new HBox(8, new Label("Currently active"), new Region(), new Label(String.valueOf(active)));
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

    private String etaFor(Shipment shipment) {
        TrackingPoint point = app.tracking.latestFor(shipment.id());
        return point == null ? "—" : point.etaMinutes() + " min";
    }
}
