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
        app.trackingCanvas = mapCanvas(470, 350);
        VBox map = panel(new Label("Fleet map  ·  Brunei-Muara")); ((Label) map.getChildren().get(0)).getStyleClass().add("section-title"); map.getChildren().add(app.trackingCanvas);
        VBox active = panel(new Label("Active deliveries")); ((Label) active.getChildren().get(0)).getStyleClass().add("section-title");
        app.trackingEta = new Label("Select a delivery to view its latest estimate."); app.trackingEta.getStyleClass().add("muted"); app.trackingEta.setWrapText(true);
        TableView<Shipment> table = new TableView<>(); table.setPlaceholder(new Label("No active deliveries."));
        table.getColumns().add(textCol("Order", Shipment::id, 100));
        table.getColumns().add(textCol("Driver", s -> app.driverName(s.driverId()), 130));
        table.getColumns().add(textCol("Destination", Shipment::address, 180));
        table.getColumns().add(textCol("Status", s -> s.status().label(), 100));
        List<Shipment> activeShipments = app.visibleShipments().stream().filter(s -> s.status() == OrderStatus.IN_TRANSIT).toList();
        table.setItems(FXCollections.observableArrayList(activeShipments)); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY); table.setPrefHeight(300);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> app.showEta(selected));
        active.getChildren().addAll(table, app.trackingEta);
        HBox content = new HBox(12, map, active); HBox.setHgrow(map, ALWAYS); active.setPrefWidth(230); active.setMinWidth(220);
        page.getChildren().add(content);
        app.drawTracking();
        return page;
    }
}
