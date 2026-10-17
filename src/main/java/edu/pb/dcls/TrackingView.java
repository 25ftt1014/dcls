package edu.pb.dcls;

import javafx.collections.FXCollections;
import javafx.animation.Interpolator;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static edu.pb.dcls.Domain.OrderStatus;
import static edu.pb.dcls.Domain.Shipment;
import static edu.pb.dcls.UiFactory.PANEL_PADDING_H;
import static edu.pb.dcls.UiFactory.chipCol;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.panel;
import static edu.pb.dcls.UiFactory.wrapTextCol;
import static javafx.scene.layout.Priority.ALWAYS;

/**
 * Live tracking screen: real GPS (NMEA / mobile / GPX) over WiFi.
 * No simulation — markers move only when a real fix is ingested.
 *
 * <p>Active deliveries sit in a fixed-width panel beside the map. This was a
 * user-draggable SplitPane, which let the panel be squeezed to any width and
 * truncated every table column; fixing the width removes that whole class of
 * problem, so there is nothing to drag and nothing to resize.
 */
final class TrackingView {
    private final DclsApp app;

    /** Fixed panel width in px: wide enough for the five columns to wrap rather than
     *  clip, narrow enough to leave the map the majority of the screen. */
    static final double PANEL_WIDTH = 420;
    /** How long the panel takes to slide out and back. */
    private static final Duration PANEL_ANIMATION = Duration.millis(250);
    /** Panel padding, so the anchored button lines up with the header row. */
    private static final double PANEL_INSET = 16;

    TrackingView(DclsApp app) { this.app = app; }

    // ---- pure, headlessly testable helpers ---------------------------

    /** Count badge in the panel header. Zero means no badge at all. */
    static int activeDeliveryBadge(List<Shipment> rows) {
        return rows == null ? 0 : rows.size();
    }

    /** True when any vehicle on screen has lost its fix entirely. */
    static boolean hasNoFixWarning(List<Shipment> rows, Function<String, String> gpsStatus) {
        if (rows == null || gpsStatus == null) return false;
        for (Shipment row : rows) {
            String vehicleId = row.vehicleId();
            if (vehicleId == null) continue;
            String status = gpsStatus.apply(vehicleId);
            if (status != null && status.startsWith("NO FIX")) return true;
        }
        return false;
    }

    /** Case-insensitive substring match over order id, plate, address and status.
     *  A blank query matches everything. */
    static boolean matchesFilter(Shipment row, String query, String plate) {
        if (query == null || query.isBlank()) return true;
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return contains(row.id(), needle)
                || contains(plate, needle)
                || contains(row.address(), needle)
                || contains(row.status().label(), needle);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Label for the show/hide toggle. Pure, so the mapping is testable without a
     *  toolkit: the button must always offer the opposite of the current state. */
    static String panelToggleLabel(boolean collapsed) {
        return collapsed ? "Show deliveries" : "Hide deliveries";
    }

    /** Width of the panel column. min, pref and max move together so the column stays
     *  rigid at any width; nothing can squeeze or stretch it. */
    private static void setPanelWidth(javafx.scene.layout.Region panel, double width) {
        panel.setMinWidth(width);
        panel.setPrefWidth(width);
        panel.setMaxWidth(width);
    }

    VBox page() {
        VBox page = pageHeader("Real-time tracking", "Live GPS via NMEA, driver mobile feed, or GPX import. Markers freeze when the fix is lost.");
        app.trackingMaps = app.new MapController(470, 350);
        app.trackingMaps.content = () -> app.trackingFrame;
        app.trackingMaps.painter = (g, v) -> app.paintTracking(g, app.trackingMaps.canvas.getWidth(), app.trackingMaps.canvas.getHeight(), v);
        app.trackingCanvas = app.trackingMaps.canvas;
        VBox map = panel(new Label("Fleet map  ·  Brunei Darussalam — scroll to zoom, drag to pan")); ((Label) map.getChildren().get(0)).getStyleClass().add("section-title");
        app.trackingLegend = new VBox(3);
        app.focusChipBox = new VBox(2);
        // Search + follow row (OSM Standard is the only basemap).
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

        // The show/hide toggle and the NO FIX warning live on the map toolbar, never
        // inside the panel. When the panel is hidden its own header is hidden with it,
        // so a toggle placed there could never be pressed to bring it back. Both also
        // stay readable while the panel is collapsed, which is exactly when you want
        // to know a vehicle has lost its fix.
        // The show/hide button is NOT here. It is anchored to the page container at the
        // coordinates the panel header's right side occupies, so it sits opposite the
        // title when the panel is open and does not move a pixel when the panel closes.
        Button panelToggle = new Button();
        panelToggle.getStyleClass().add("secondary-button");
        Label noFix = new Label("NO FIX");
        noFix.getStyleClass().addAll("status-pill", "status-bad");
        HBox mapTools = new HBox(8, search, follow, noFix);
        HBox.setHgrow(search, ALWAYS);

        List<Shipment> activeShipments = app.visibleShipments().stream()
                .filter(s -> s.status() == OrderStatus.IN_TRANSIT || s.status() == OrderStatus.ASSIGNED).toList();

        // ---- header: title and live count badge ----
        Label activeTitle = new Label("Active deliveries");
        activeTitle.getStyleClass().add("section-title");
        Label countBadge = new Label();
        countBadge.getStyleClass().add("count-badge");
        HBox activeHeader = new HBox(8, activeTitle, countBadge);
        activeHeader.setAlignment(Pos.CENTER_LEFT);

        VBox active = panel(activeHeader);

        TextField filter = new TextField();
        filter.setPromptText("Filter orders, vehicles, destinations…");
        filter.setAccessibleText("Filter active deliveries");

        app.trackingEta = new Label("Select a delivery to view its latest estimate.");
        app.trackingEta.getStyleClass().add("muted");
        app.trackingEta.setWrapText(true);
        app.feedStatusLabel = new Label(app.feedStatusText());
        app.feedStatusLabel.getStyleClass().add("muted");
        app.feedStatusLabel.setWrapText(true);

        TableView<Shipment> table = new TableView<>();
        // Releases the global 40px row height so a wrapped cell can grow instead of
        // clipping its text; see .wrap-rows in styles.css.
        table.getStyleClass().add("wrap-rows");
        table.setPlaceholder(new Label("No active deliveries — assign an order first."));
        // Widths sum to exactly the panel's content area. Status is deliberately not a
        // column: every row here is either ASSIGNED or IN_TRANSIT, so it carries little
        // and cost the Destination column enough width to truncate again. It is shown
        // as a chip in the detail pane instead.
        table.getColumns().add(wrapTextCol("Order", Shipment::id, 72));
        table.getColumns().add(wrapTextCol("Vehicle", s -> app.vehiclePlate(s.vehicleId()), 80));
        table.getColumns().add(chipCol("Fix",
                s -> s.vehicleId() == null ? "—" : app.tracking.gpsStatus(s.vehicleId()),
                (s, text) -> text.startsWith("NO FIX") ? "status-bad"
                        : text.startsWith("LOST") ? "status-warn" : "status-good", 96));
        table.getColumns().add(wrapTextCol("Destination", Shipment::address, 140));
        table.setItems(FXCollections.observableArrayList(activeShipments));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        // A fixed cell size is what forces one line per row, and therefore the ellipsis.
        // -1 is TableView's "let each row size itself" value.
        table.setFixedCellSize(-1d);

        // ---- selected-row detail -------------------------------------
        ComboBox<String> feed = new ComboBox<>(FXCollections.observableArrayList("nmea", "mobile", "gpx"));
        feed.setValue(app.tracking.feedSource()); feed.setMaxWidth(Double.MAX_VALUE);
        feed.setOnAction(e -> { app.tracking.feedSource(feed.getValue()); app.setFeedStatus("Feed: " + feed.getValue()); });
        Button viewTour = new Button("View vehicle tour (fastest)");
        viewTour.setMaxWidth(Double.MAX_VALUE);
        viewTour.setOnAction(event -> {
            Shipment selected = table.getSelectionModel().getSelectedItem();
            if (selected != null && selected.vehicleId() != null) {
                app.focusVehicleTour(selected.vehicleId(), selected.id());
            } else app.toast("Select a delivery with a vehicle first.");
        });

        ContextMenu actions = new ContextMenu();
        MenuItem actGpx = new MenuItem("Import GPX track…");
        actGpx.setOnAction(event -> importGpx(table.getSelectionModel().getSelectedItem()));
        MenuItem actNmea = new MenuItem("Import NMEA file…");
        actNmea.setOnAction(event -> importNmea(table.getSelectionModel().getSelectedItem()));
        MenuItem actPoll = new MenuItem("Poll mobile feed now");
        actPoll.setOnAction(event -> { app.pollMobileFeed(); table.refresh(); app.drawTracking(); });
        MenuItem actExport = new MenuItem("Export track (GPX/CSV/KML)");
        actExport.setOnAction(event -> app.exportTrack(table.getSelectionModel().getSelectedItem()));
        MenuItem actClear = new MenuItem("Clear vehicle history…");
        // clearVehicleHistory already shows its own confirmation dialog.
        actClear.setOnAction(event -> {
            Shipment selected = table.getSelectionModel().getSelectedItem();
            if (selected != null && selected.vehicleId() != null) {
                app.clearVehicleHistory(selected.vehicleId());
                table.refresh();
            } else app.toast("Select a delivery with a vehicle first.");
        });
        actions.getItems().addAll(actGpx, actNmea, actPoll, new SeparatorMenuItem(), actExport, actClear);
        Button actionsBtn = new Button("Actions ▾");
        actionsBtn.setMaxWidth(Double.MAX_VALUE);
        actionsBtn.setOnAction(event -> actions.show(actionsBtn, javafx.geometry.Side.BOTTOM, 0, 0));

        Label statusChip = new Label();
        statusChip.getStyleClass().addAll("status-pill", "status-info");
        VBox detailBox = new VBox(8, statusChip, viewTour, feed, actionsBtn, app.trackingEta, app.feedStatusLabel);
        TitledPane detail = new TitledPane();
        detail.setText("Delivery detail");
        detail.setCollapsible(true);
        detail.setExpanded(false);
        detail.setContent(detailBox);
        detail.getStyleClass().add("tracking-detail");

        // The table scrolls; the filter and detail stay put, so the panel is a VBox
        // with the table taking the slack rather than one tall scroll pane.
        table.setPrefHeight(240);
        VBox.setVgrow(table, ALWAYS);
        active.getChildren().addAll(filter, table, detail);

        // ---- live count, NO FIX, and the show/hide toggle ---------------
        // refreshToggle must stay callable before `table` exists (the toolbar button
        // is created above it), so it only reads the app flag, not the rows.
        Runnable refreshToggle = () -> {
            panelToggle.setText(panelToggleLabel(app.trackingPanelCollapsed));
            panelToggle.setAccessibleText(app.trackingPanelCollapsed
                    ? "Show active deliveries" : "Hide active deliveries");
        };
        Runnable refreshBadge = () -> {
            int count = activeDeliveryBadge(table.getItems());
            countBadge.setText(String.valueOf(count));
            countBadge.setVisible(count > 0);
            noFix.setVisible(hasNoFixWarning(table.getItems(), id -> app.tracking.gpsStatus(id)));
            noFix.setManaged(noFix.isVisible());
            refreshToggle.run();
        };
        app.addTrackingBadgeRefresh(refreshBadge);
        refreshBadge.run();

        // ---- slide the panel out / in ------------------------------------
        // min, pref and max are animated together: the panel is deliberately rigid
        // (min == pref == max), and driving only maxWidth would put max below min and
        // leave HBox to resolve a contradictory clamp.
        javafx.animation.Timeline widthRamp = new javafx.animation.Timeline();
        for (int step = 1; step <= 10; step++) {
            final double frac = step / 10.0;
            widthRamp.getKeyFrames().add(new javafx.animation.KeyFrame(
                    Duration.millis(PANEL_ANIMATION.toMillis() * step / 10),
                    e -> setPanelWidth(active, PANEL_WIDTH * (1 - frac))));
        }
        Runnable applyCollapsed = () -> {
            if (app.trackingPanelCollapsed) {
                panelToggle.setText(panelToggleLabel(true));
                panelToggle.setAccessibleText("Show active deliveries");
                active.setVisible(true);
                active.setMouseTransparent(true);
                javafx.animation.TranslateTransition out = new javafx.animation.TranslateTransition(
                        PANEL_ANIMATION, active);
                out.setInterpolator(Interpolator.EASE_BOTH);
                out.setToX(PANEL_WIDTH);
                out.setOnFinished(e -> { if (app.trackingPanelCollapsed) active.setVisible(false); });
                out.play();
                widthRamp.playFromStart();
            } else {
                panelToggle.setText(panelToggleLabel(false));
                panelToggle.setAccessibleText("Hide active deliveries");
                active.setVisible(true);
                active.setTranslateX(PANEL_WIDTH);
                javafx.animation.TranslateTransition in = new javafx.animation.TranslateTransition(
                        PANEL_ANIMATION, active);
                in.setInterpolator(Interpolator.EASE_BOTH);
                in.setToX(0);
                in.play();
                setPanelWidth(active, PANEL_WIDTH);
                widthRamp.stop();
            }
            if (app.trackingMaps != null) app.trackingMaps.requestRedraw();
        };
        panelToggle.setOnAction(event -> {
            app.trackingPanelCollapsed = !app.trackingPanelCollapsed;
            applyCollapsed.run();
        });
        applyCollapsed.run();
        if (app.trackingPanelCollapsed) {
            active.setVisible(false);
            setPanelWidth(active, 0);
        }

        Runnable refreshFilter = () -> table.setItems(FXCollections.observableArrayList(
                activeShipments.stream()
                        .filter(s -> matchesFilter(s, filter.getText(), app.vehiclePlate(s.vehicleId())))
                        .toList()));
        filter.textProperty().addListener((o, a, b) -> {
            refreshFilter.run();
            refreshBadge.run();
        });
        refreshFilter.run();

        table.getSelectionModel().selectedItemProperty().addListener((o, old, selected) -> {
            app.focusShipment(selected);
            if (selected == null) {
                statusChip.setVisible(false);
                statusChip.setManaged(false);
                return;
            }
            statusChip.setText(selected.status().label());
            statusChip.getStyleClass().setAll("status-pill",
                    selected.status() == OrderStatus.IN_TRANSIT ? "status-info" : "status-good");
            statusChip.setVisible(true);
            statusChip.setManaged(true);
            if (selected.vehicleId() == null) return;
            // Pan to the vehicle when it actually has a fix to pan to.
            var point = app.tracking.latestPlausibleForVehicle(selected.vehicleId());
            if (point != null && app.trackingMaps != null) app.trackingMaps.zoomTo(point.x(), point.y());
        });
        statusChip.setVisible(false);
        statusChip.setManaged(false);

        map.setMinWidth(280);
        map.getChildren().addAll(app.trackingCanvas, app.trackingMaps.zoomBar, mapTools,
                app.focusChipBox, app.legendBox(), app.trackingLegend);
        // Map canvas follows the panel width so collapsing really does give the space back.
        app.trackingCanvas.widthProperty().bind(map.widthProperty().subtract(PANEL_PADDING_H));
        map.widthProperty().addListener((o, a, b) -> { if (app.trackingMaps != null) app.trackingMaps.requestRedraw(); });
        map.heightProperty().addListener((o, a, b) -> { if (app.trackingMaps != null) app.trackingMaps.requestRedraw(); });

        // Fixed width on all three properties is what makes the panel immovable:
        // min == pref == max, so no layout can squeeze or stretch it. The collapse
        // animation moves all three together rather than just maxWidth.
        setPanelWidth(active, PANEL_WIDTH);
        HBox row = new HBox(12, map, active);
        HBox.setHgrow(map, ALWAYS);

        // The toggle is anchored to the container, not to the panel, so collapsing the
        // panel cannot move it. Offsets place it opposite the header title: one panel
        // padding in from the right edge, and nudged down to sit on the header row.
        StackPane content = new StackPane(row);
        content.getChildren().add(panelToggle);
        StackPane.setAlignment(panelToggle, Pos.TOP_RIGHT);
        panelToggle.setTranslateX(-PANEL_INSET);
        panelToggle.setTranslateY(PANEL_INSET - 4);
        VBox.setVgrow(content, ALWAYS);
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
