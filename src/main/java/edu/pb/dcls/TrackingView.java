package edu.pb.dcls;

import javafx.collections.FXCollections;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;

import static edu.pb.dcls.Domain.OrderStatus;
import static edu.pb.dcls.Domain.Shipment;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.panel;
import static edu.pb.dcls.UiFactory.textCol;
import static javafx.scene.layout.Priority.ALWAYS;

/**
 * Live tracking screen: real GPS (NMEA / mobile / GPX) over WiFi.
 * No simulation — markers move only when a real fix is ingested.
 */
final class TrackingView {
    private final DclsApp app;

    TrackingView(DclsApp app) { this.app = app; }

    VBox page() {
        VBox page = pageHeader("Real-time tracking", "Live GPS via NMEA, driver mobile feed, or GPX import. Markers freeze when the fix is lost.");
        app.trackingMaps = app.new MapController(470, 350);
        app.trackingMaps.content = () -> app.trackingFrame;
        app.trackingMaps.painter = (g, v) -> app.paintTracking(g, app.trackingMaps.canvas.getWidth(), app.trackingMaps.canvas.getHeight(), v);
        app.trackingCanvas = app.trackingMaps.canvas;
        VBox map = panel(new Label("Fleet map  ·  Brunei Darussalam — scroll to zoom, drag to pan")); ((Label) map.getChildren().get(0)).getStyleClass().add("section-title");
        app.trackingLegend = new VBox(3);
        app.focusChipBox = new VBox(2);
        // Basemap + search row (free providers only).
        ComboBox<String> basemap = new ComboBox<>(FXCollections.observableArrayList(
                "OSM Standard", "Esri Imagery"));
        basemap.setValue(MapRenderer.basemap == MapRenderer.Basemap.ESRI_IMAGERY ? "Esri Imagery" : "OSM Standard");
        basemap.setMaxWidth(Double.MAX_VALUE);
        basemap.setOnAction(e -> {
            MapRenderer.basemap = "Esri Imagery".equals(basemap.getValue())
                    ? MapRenderer.Basemap.ESRI_IMAGERY : MapRenderer.Basemap.OSM_STANDARD;
            app.trackingMaps.redraw();
        });
        javafx.scene.control.TextField search = new javafx.scene.control.TextField();
        search.setPromptText("Search place in Brunei…");
        search.setOnAction(e -> {
            String query = search.getText();
            if (query == null || query.isBlank()) return;
            search.setDisable(true);
            search.setPromptText("Searching…");
            Thread thread = new Thread(() -> {
                List<MapRenderer.PlaceHit> hits = MapRenderer.searchPlaces(query);
                javafx.application.Platform.runLater(() -> {
                    search.setDisable(false);
                    search.setPromptText("Search place in Brunei…");
                    if (hits.isEmpty()) { app.toast("No results in Brunei for: " + query); return; }
                    MapRenderer.PlaceHit h = hits.get(0);
                    if (app.trackingMaps != null) app.trackingMaps.zoomTo(h.lon(), h.lat());
                    app.toast(h.name());
                });
            });
            thread.setDaemon(true);
            thread.start();
        });
        javafx.scene.control.CheckBox follow = new javafx.scene.control.CheckBox("Follow selected");
        follow.setSelected(app.followSelected);
        follow.setOnAction(e -> app.followSelected = follow.isSelected());
        HBox mapTools = new HBox(8, basemap, search, follow);
        map.getChildren().addAll(app.trackingCanvas, app.trackingMaps.zoomBar, mapTools, app.focusChipBox, app.legendBox(), app.trackingLegend);
        VBox active = panel(new javafx.scene.layout.HBox());
        javafx.scene.layout.HBox activeHeader = (javafx.scene.layout.HBox) active.getChildren().get(0);
        Label activeTitle = new Label("Active deliveries");
        activeTitle.getStyleClass().add("section-title");
        javafx.scene.layout.Region activeGap = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(activeGap, ALWAYS);
        javafx.scene.control.Button collapseBtn = new javafx.scene.control.Button(app.trackingPanelCollapsed ? "«" : "»");
        collapseBtn.getStyleClass().add("link-button");
        activeHeader.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        activeHeader.getChildren().addAll(activeTitle, activeGap, collapseBtn);
        app.trackingEta = new Label("Select a delivery to view its latest estimate."); app.trackingEta.getStyleClass().add("muted"); app.trackingEta.setWrapText(true);
        TableView<Shipment> table = new TableView<>(); table.setPlaceholder(new Label("No active deliveries — assign an order first."));
        table.getColumns().add(textCol("Order", Shipment::id, 90));
        table.getColumns().add(textCol("Vehicle", s -> app.vehiclePlate(s.vehicleId()), 90));
        table.getColumns().add(textCol("Fix", s -> s.vehicleId() == null ? "—" : app.tracking.gpsStatus(s.vehicleId()), 130));
        table.getColumns().add(textCol("Destination", Shipment::address, 150));
        table.getColumns().add(textCol("Status", s -> s.status().label(), 80));
        List<Shipment> activeShipments = app.visibleShipments().stream()
                .filter(s -> s.status() == OrderStatus.IN_TRANSIT || s.status() == OrderStatus.ASSIGNED).toList();
        table.setItems(FXCollections.observableArrayList(activeShipments)); table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY); table.setPrefHeight(220);
        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> app.focusShipment(selected));
        // Real-feed controls.
        ComboBox<String> feed = new ComboBox<>(FXCollections.observableArrayList("nmea", "mobile", "gpx"));
        feed.setValue(app.tracking.feedSource()); feed.setMaxWidth(Double.MAX_VALUE);
        feed.setOnAction(e -> { app.tracking.feedSource(feed.getValue()); app.setFeedStatus("Feed: " + feed.getValue()); });
        Button importGpx = new Button("Import GPX track…");
        importGpx.setMaxWidth(Double.MAX_VALUE);
        importGpx.setOnAction(event -> importGpx(table.getSelectionModel().getSelectedItem()));
        Button importNmea = new Button("Import NMEA file…");
        importNmea.setMaxWidth(Double.MAX_VALUE);
        importNmea.setOnAction(event -> importNmea(table.getSelectionModel().getSelectedItem()));
        Button pollMobile = new Button("Poll mobile feed now");
        pollMobile.setMaxWidth(Double.MAX_VALUE);
        pollMobile.setOnAction(event -> { app.pollMobileFeed(); table.refresh(); app.drawTracking(); });
        Button gpxExport = new Button("Export track (GPX/CSV/KML)");
        gpxExport.setMaxWidth(Double.MAX_VALUE);
        gpxExport.setOnAction(event -> app.exportTrack(table.getSelectionModel().getSelectedItem()));
        Button clearHistory = new Button("Clear vehicle history…");
        clearHistory.setMaxWidth(Double.MAX_VALUE);
        clearHistory.setOnAction(event -> {
            Shipment selected = table.getSelectionModel().getSelectedItem();
            if (selected != null && selected.vehicleId() != null) {
                app.clearVehicleHistory(selected.vehicleId());
                table.refresh();
            } else app.toast("Select a delivery with a vehicle first.");
        });
        Button viewTour = new Button("View vehicle tour (fastest)");
        viewTour.setMaxWidth(Double.MAX_VALUE);
        viewTour.setOnAction(event -> {
            Shipment selected = table.getSelectionModel().getSelectedItem();
            if (selected != null && selected.vehicleId() != null) {
                app.focusVehicleTour(selected.vehicleId(), selected.id());
            } else app.toast("Select a delivery with a vehicle first.");
        });
        app.feedStatusLabel = new Label(app.feedStatusText()); app.feedStatusLabel.getStyleClass().add("muted"); app.feedStatusLabel.setWrapText(true);
        active.getChildren().addAll(table, viewTour, new Label("GPS feed"), feed, importGpx, importNmea, pollMobile, gpxExport, clearHistory, app.feedStatusLabel, app.trackingEta);
        map.setMinWidth(280);
        active.setMinWidth(235);
        // Map canvas follows the panel width so the divider actually resizes the map.
        app.trackingCanvas.widthProperty().bind(map.widthProperty().subtract(24));
        map.widthProperty().addListener((o, a, b) -> { if (app.trackingMaps != null) app.trackingMaps.redraw(); });
        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(map, active);
        split.setDividerPositions(app.trackingPanelCollapsed ? 1.0 : app.trackingDividerPos);
        javafx.scene.control.SplitPane.setResizableWithParent(map, Boolean.TRUE);
        javafx.scene.control.SplitPane.setResizableWithParent(active, Boolean.TRUE);
        split.getDividers().get(0).positionProperty().addListener((o, old, pos) -> {
            double p = pos.doubleValue();
            if (p < 1.0) {
                app.trackingDividerPos = p;
                app.trackingPanelCollapsed = false;
                collapseBtn.setText("»");
            }
            if (app.trackingMaps != null) app.trackingMaps.redraw();
        });
        collapseBtn.setOnAction(e -> {
            if (app.trackingPanelCollapsed) {
                app.trackingPanelCollapsed = false;
                split.setDividerPositions(app.trackingDividerPos);
                collapseBtn.setText("»");
            } else {
                app.trackingDividerPos = split.getDividerPositions()[0];
                app.trackingPanelCollapsed = true;
                split.setDividerPositions(1.0);
                collapseBtn.setText("«");
            }
            if (app.trackingMaps != null) app.trackingMaps.redraw();
        });
        VBox content = new VBox(split);
        VBox.setVgrow(split, ALWAYS);
        page.getChildren().add(content);
        if (app.focusedVehicleId != null) {
            table.getItems().stream().filter(s -> app.focusedVehicleId.equals(s.vehicleId())).findFirst()
                    .ifPresent(s -> table.getSelectionModel().select(s));
        } else if (app.focusedShipmentId != null) {
            table.getItems().stream().filter(s -> app.focusedShipmentId.equals(s.id())).findFirst()
                    .ifPresent(s -> table.getSelectionModel().select(s));
        }
        app.refreshFocusChip();
        app.drawTracking();
        return page;
    }

    private void importGpx(Shipment selected) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import GPX track");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("GPX files", "*.gpx"));
        File file = chooser.showOpenDialog(app.stage);
        if (file == null) return;
        try {
            String xml = Files.readString(file.toPath());
            String vehicleId = selected != null && selected.vehicleId() != null ? selected.vehicleId()
                    : app.repository.vehicles().stream().findFirst().map(v -> v.id()).orElse(null);
            if (vehicleId == null) { app.alert(Alert.AlertType.WARNING, "No vehicle", "Add a vehicle first."); return; }
            List<GpsFeed.GpsFix> fixes = GpsFeed.parseGpx(xml, vehicleId, "gpx");
            if (fixes.isEmpty()) { app.alert(Alert.AlertType.WARNING, "Empty GPX", "No track points found."); return; }
            int n = 0, skipped = 0;
            String firstSkip = null;
            for (GpsFeed.GpsFix f : fixes) {
                try { app.tracking.ingest(f, selected == null ? null : selected.id()); n++; }
                catch (RuntimeException ex) { skipped++; if (firstSkip == null) firstSkip = ex.getMessage(); }
            }
            app.setFeedStatus("GPX: " + file.getName() + " · " + n + " fixes");
            if (n == 0) {
                app.alert(Alert.AlertType.WARNING, "No fixes imported",
                        "All " + fixes.size() + " track points were skipped."
                                + (firstSkip == null ? "" : " Reason: " + firstSkip)
                                + " Only fixes inside the Brunei service area can be stored.");
                return;
            }
            app.toast("GPX imported: " + n + " fixes → " + app.vehiclePlate(vehicleId)
                    + (skipped == 0 ? "" : " · skipped " + skipped + ": " + firstSkip));
            app.drawTracking();
            if (selected != null) app.showEta(selected);
        } catch (Exception ex) {
            app.alert(Alert.AlertType.ERROR, "Import failed", ex.getMessage());
        }
    }

    private void importNmea(Shipment selected) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import NMEA log");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("NMEA", "*.nmea;*.txt;*.log"));
        File file = chooser.showOpenDialog(app.stage);
        if (file == null) return;
        try {
            String vehicleId = selected != null && selected.vehicleId() != null ? selected.vehicleId()
                    : app.repository.vehicles().stream().findFirst().map(v -> v.id()).orElse(null);
            if (vehicleId == null) { app.alert(Alert.AlertType.WARNING, "No vehicle", "Add a vehicle first."); return; }
            List<GpsFeed.GpsFix> fixes = GpsFeed.readNmeaFile(file.toPath(), vehicleId);
            int n = 0, skipped = 0;
            String firstSkip = null;
            for (GpsFeed.GpsFix f : fixes) {
                try { app.tracking.ingest(f, selected == null ? null : selected.id()); n++; }
                catch (RuntimeException ex) { skipped++; if (firstSkip == null) firstSkip = ex.getMessage(); }
            }
            app.setFeedStatus("NMEA: " + file.getName() + " · " + n + " fixes");
            if (n == 0 && !fixes.isEmpty()) {
                app.alert(Alert.AlertType.WARNING, "No fixes imported",
                        "All " + fixes.size() + " sentences were skipped."
                                + (firstSkip == null ? "" : " Reason: " + firstSkip)
                                + " Only fixes inside the Brunei service area can be stored.");
                return;
            }
            app.toast("NMEA imported: " + n + " fixes"
                    + (skipped == 0 ? "" : " · skipped " + skipped + ": " + firstSkip));
            app.drawTracking();
            if (n == 0) app.alert(Alert.AlertType.WARNING, "No fixes", "No valid $GPGGA/$GPRMC sentences found.");
        } catch (Exception ex) {
            app.alert(Alert.AlertType.ERROR, "Import failed", ex.getMessage());
        }
    }
}
