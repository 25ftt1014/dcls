package edu.pb.dcls;

import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;

import static edu.pb.dcls.Domain.OrderStatus;
import static edu.pb.dcls.Domain.Shipment;
import static edu.pb.dcls.MapRenderer.mapCanvas;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.panel;
import static edu.pb.dcls.UiFactory.statusCol;
import static edu.pb.dcls.UiFactory.textCol;
import static javafx.scene.layout.Priority.ALWAYS;

/**
 * Live tracking screen. Builds the map and active-delivery table; the per-second position
 * simulation ({@code startTracking}/{@code drawTracking}) stays in {@link DclsApp} because it
 * is tied to the app's navigation lifecycle.
 */
final class TrackingView {
    private final DclsApp app;

    TrackingView(DclsApp app) { this.app = app; }

    VBox page() {
        VBox page = pageHeader("Real-time tracking", "Live positions are simulated along assigned routes. No GPS data is used.");
        app.trackingMaps = app.new MapController(470, 350);
        app.trackingMaps.content = () -> app.trackingFrame;
        app.trackingMaps.painter = (g, v) -> app.paintTracking(g, app.trackingMaps.canvas.getWidth(), app.trackingMaps.canvas.getHeight(), v);
        app.trackingCanvas = app.trackingMaps.canvas;
        VBox map = panel(new Label("Fleet map  ·  Brunei Darussalam — scroll to zoom, drag to pan")); ((Label) map.getChildren().get(0)).getStyleClass().add("section-title");
        app.trackingLegend = new VBox(3);
        map.getChildren().addAll(app.trackingCanvas, app.trackingMaps.zoomBar, app.legendBox(), app.trackingLegend);
        VBox active = panel(new Label("Active deliveries")); ((Label) active.getChildren().get(0)).getStyleClass().add("section-title");
        app.trackingEta = new Label("Select a delivery to view its latest estimate."); app.trackingEta.getStyleClass().add("muted"); app.trackingEta.setWrapText(true);
        TableView<Shipment> table = new TableView<>(); table.setPlaceholder(new Label("No active deliveries."));
        table.getColumns().add(textCol("Order", Shipment::id, 90));
        table.getColumns().add(textCol("Vehicle", s -> app.vehiclePlate(s.vehicleId()), 90));
        table.getColumns().add(textCol("Driver", s -> app.driverName(s.driverId()), 110));
        table.getColumns().add(textCol("GPS", s -> s.vehicleId() == null ? "—" : app.tracking.gpsStatus(s.vehicleId()), 110));
        table.getColumns().add(textCol("Destination", Shipment::address, 150));
        table.getColumns().add(textCol("Status", s -> s.status().label(), 80));
        List<Shipment> activeShipments = app.visibleShipments().stream().filter(s -> s.status() == OrderStatus.IN_TRANSIT).toList();
        table.setItems(FXCollections.observableArrayList(activeShipments)); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY); table.setPrefHeight(240);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> app.showEta(selected));
        javafx.scene.control.Button gpsToggle = new javafx.scene.control.Button("Simulate GPS fix lost / restore");
        gpsToggle.setMaxWidth(Double.MAX_VALUE);
        gpsToggle.setOnAction(event -> {
            Shipment selected = table.getSelectionModel().getSelectedItem();
            if (selected == null || selected.vehicleId() == null) {
                app.alert(javafx.scene.control.Alert.AlertType.INFORMATION, "No vehicle selected", "Select a delivery first, then toggle its simulated phone-GPS fix.");
                return;
            }
            boolean lost = !app.tracking.gps().isFixLost(selected.vehicleId());
            app.tracking.gps().setFixLost(selected.vehicleId(), lost);
            app.toast(app.vehiclePlate(selected.vehicleId()) + ": simulated GPS fix " + (lost ? "LOST — marker frozen." : "restored."));
            table.refresh();
            app.drawTracking();
            app.showEta(selected);
        });
        javafx.scene.control.Button gpxExport = new javafx.scene.control.Button("Export GPX track");
        gpxExport.setMaxWidth(Double.MAX_VALUE);
        gpxExport.setOnAction(event -> app.exportGpx(table.getSelectionModel().getSelectedItem()));
        active.getChildren().addAll(table, gpsToggle, gpxExport, app.trackingEta);
        HBox content = new HBox(12, map, active); HBox.setHgrow(map, ALWAYS); active.setPrefWidth(230); active.setMinWidth(220);
        page.getChildren().add(content);
        app.drawTracking();
        return page;
    }
}
