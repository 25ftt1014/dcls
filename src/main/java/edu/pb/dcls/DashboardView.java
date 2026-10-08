package edu.pb.dcls;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Comparator;
import java.util.List;

import static edu.pb.dcls.Domain.OrderStatus;
import static edu.pb.dcls.Domain.Role;
import static edu.pb.dcls.Domain.Shipment;
import static edu.pb.dcls.Domain.TrackingPoint;
import static edu.pb.dcls.Domain.Vehicle;
import static edu.pb.dcls.MapRenderer.activityChart;
import static edu.pb.dcls.MapRenderer.drawDashboardRoads;
import static edu.pb.dcls.MapRenderer.drawRoute;
import static edu.pb.dcls.MapRenderer.mapCanvas;
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
        VBox page = pageHeader("", ""); page.setSpacing(18); page.setPadding(new Insets(18, 20, 20, 58));
        HBox metrics = new HBox(18,
                metric("Vehicles", String.valueOf(vehicleRows.size()), ""),
                metric("Active deliveries", String.valueOf(active), ""),
                metric("Pending orders", String.valueOf(pending), ""),
                metric("Completed today", String.valueOf(completed), ""));
        for (Node node : metrics.getChildren()) HBox.setHgrow(node, ALWAYS);
        VBox mapPanel = panel(new Label("Operations map")); ((Label) mapPanel.getChildren().get(0)).getStyleClass().add("section-title");
        Canvas map = mapCanvas(430, 210);
        if (!app.repository.routes().isEmpty()) drawRoute(map, app.repository.routes().getFirst().stops(), null); else drawDashboardRoads(map);
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

    private String etaFor(Shipment shipment) {
        TrackingPoint point = app.tracking.latestFor(shipment.id());
        return point == null ? "—" : point.etaMinutes() + " min";
    }
}
