package edu.pb.dcls;

import javafx.collections.FXCollections;
import javafx.animation.Interpolator;
import javafx.geometry.Insets;
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

    /** Fixed drawer width in px. Sized so all five columns fit the content area
     *  without clipping while still leaving the map the majority of the screen. */
    static final double PANEL_WIDTH = 440;
    /** Slide durations: opening eases in slightly longer than closing, which reads as
     *  responsive rather than sluggish on the way back. */
    static final double OPEN_MS = 280, CLOSE_MS = 220;
    /** Below this map-container width the drawer floats over the map instead of pushing
     *  it. Measured on the container, not the stage: the nav sidebar already eats ~200px,
     *  so a stage-based threshold would switch far too late. */
    static final double PUSH_MIN_WIDTH = 1100;
    /** Delay between the slide finishing and the map adopting its new width, so the
     *  resize lands just after the motion rather than on its last frame. */
    private static final Duration RESIZE_SETTLE = Duration.millis(60);
    /** Panel padding, so the anchored button lines up with the drawer header row. */
    private static final double PANEL_INSET = 16;

    TrackingView(DclsApp app) { this.app = app; }

    // ---- pure, headlessly testable helpers ---------------------------

    /** Overlay below the breakpoint (drawer floats, scrim visible), push at or above it. */
    static boolean drawerOverlay(double containerWidth) {
        return containerWidth < PUSH_MIN_WIDTH;
    }

    /** Honours reduced motion by collapsing every duration to zero. */
    static Duration motionDuration(boolean reduced, double millis) {
        return reduced ? Duration.ZERO : Duration.millis(millis);
    }

    /** Honours the app's reduced-motion setting. */
    private Duration motion(double millis) {
        return motionDuration(app.reduceMotion, millis);
    }

    /** Count badge on the toggle. Zero means no badge at all. */
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

    /** Accessible name for the toggle. The button is an icon, so this is the only label it
     *  has; it must always offer the opposite of the current state. */
    static String panelToggleAccessibleText(boolean collapsed) {
        return collapsed ? "Show active deliveries" : "Hide active deliveries";
    }

    /** Restored drawer state, default shown. Read lazily — never from a constructor —
     *  so building a DclsApp stays free of Preferences side effects. */
    static boolean loadDrawerOpen() {
        try { return prefs().getBoolean(PREF_OPEN, false); }
        catch (RuntimeException unavailable) { return false; }
    }

    static void saveDrawerOpen(boolean open) {
        try { prefs().putBoolean(PREF_OPEN, open); } catch (RuntimeException ignored) { }
    }

    private static final String PREF_OPEN = "trackingDrawerOpen";

    private static java.util.prefs.Preferences prefs() {
        return java.util.prefs.Preferences.userNodeForPackage(TrackingView.class);
    }

    /** Pin a node to one width: min == pref == max so no layout can move it. */
    private static void fixWidth(javafx.scene.layout.Region node, double width) {
        node.setMinWidth(width);
        node.setPrefWidth(width);
        node.setMaxWidth(width);
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
        // "Follow selected" and the NO FIX warning sit ON the map, top-left, rather than in a
        // row below it, so the map gets the full panel height.
        javafx.scene.control.CheckBox follow = new javafx.scene.control.CheckBox("Follow selected");
        follow.setSelected(app.followSelected);
        follow.setOnAction(e -> app.followSelected = follow.isSelected());
        Label noFix = new Label("NO FIX");
        noFix.getStyleClass().addAll("status-pill", "status-bad");
        HBox mapOverlay = new HBox(8, follow, noFix);
        mapOverlay.getStyleClass().add("map-overlay");

        // The toggle is an icon: glyph plus the live count and a red dot when any
        // vehicle has lost its fix. Both indicators are visible whether the drawer is
        // open or closed, which is exactly when a lost signal matters most.
        Label toggleGlyph = new Label("☰");
        toggleGlyph.getStyleClass().add("glyph");
        Label toggleBadge = new Label();
        toggleBadge.getStyleClass().add("count-badge");
        Region toggleDot = new Region();
        toggleDot.getStyleClass().add("warning-dot");
        StackPane panelToggle = new StackPane(toggleGlyph, toggleBadge, toggleDot);
        panelToggle.getStyleClass().add("tracking-toggle");
        panelToggle.setPadding(new Insets(6, 10, 6, 8));
        StackPane.setAlignment(toggleBadge, Pos.CENTER_RIGHT);
        StackPane.setAlignment(toggleDot, Pos.TOP_RIGHT);
        panelToggle.setFocusTraversable(true);
        panelToggle.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);

        List<Shipment> activeShipments = app.visibleShipments().stream()
                .filter(s -> s.status() == OrderStatus.IN_TRANSIT || s.status() == OrderStatus.ASSIGNED).toList();

        // ---- drawer header: title, live count, close ----
        Label activeTitle = new Label("Active deliveries");
        activeTitle.getStyleClass().add("section-title");
        Label countBadge = new Label();
        countBadge.getStyleClass().add("count-badge");
        Button closeDrawer = new Button("✕");
        closeDrawer.getStyleClass().add("drawer-close");
        closeDrawer.setAccessibleText("Close active deliveries");
        Region headerGap = new Region();
        HBox.setHgrow(headerGap, ALWAYS);
        HBox activeHeader = new HBox(8, activeTitle, countBadge, headerGap, closeDrawer);
        activeHeader.setAlignment(Pos.CENTER_LEFT);

        VBox active = panel(activeHeader);
        active.getStyleClass().add("tracking-drawer");

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
        // Widths sum to exactly the drawer's content area (440 - 2x16 = 408), so
        // CONSTRAINED resize never has to squeeze a column and the cells wrap instead.
        // Status is back as a column: at 440px it fits without costing Destination.
        table.getColumns().add(wrapTextCol("Order", Shipment::id, 68));
        table.getColumns().add(wrapTextCol("Vehicle", s -> app.vehiclePlate(s.vehicleId()), 76));
        table.getColumns().add(chipCol("Fix",
                s -> s.vehicleId() == null ? "—" : app.tracking.gpsStatus(s.vehicleId()),
                (s, text) -> text.startsWith("NO FIX") ? "status-bad"
                        : text.startsWith("LOST") ? "status-warn" : "status-good", 88));
        table.getColumns().add(wrapTextCol("Destination", Shipment::address, 108));
        table.getColumns().add(chipCol("Status", s -> s.status().label(),
                (s, text) -> s.status() == OrderStatus.IN_TRANSIT ? "status-info" : "status-good", 68));
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

        // Scrim covers the map only, and only in overlay mode. Hidden and
        // mouse-transparent otherwise so it can never eat a map drag.
        Region scrim = new Region();
        scrim.getStyleClass().add("drawer-scrim");
        scrim.setVisible(false);
        scrim.setMouseTransparent(true);
        scrim.setOpacity(0);

        // Layering, back to front: map, map overlay (top-left), scrim over the map only,
        // toggle (top-right), drawer. The scrim sits under the drawer so it dims the map
        // without swallowing clicks meant for the drawer, and under the toggle so the
        // button stays reachable in overlay mode.
        StackPane content = new StackPane(map);
        content.getChildren().addAll(mapOverlay, scrim, panelToggle, active);

        // ---- live count, NO FIX, and the toggle state --------------------
        Runnable refreshToggle = () ->
                panelToggle.setAccessibleText(panelToggleAccessibleText(app.trackingPanelCollapsed));
        Runnable refreshBadge = () -> {
            int count = activeDeliveryBadge(table.getItems());
            countBadge.setText(String.valueOf(count));
            countBadge.setVisible(count > 0);
            toggleBadge.setText(String.valueOf(count));
            toggleBadge.setVisible(count > 0);
            boolean lost = hasNoFixWarning(table.getItems(), id -> app.tracking.gpsStatus(id));
            noFix.setVisible(lost);
            noFix.setManaged(lost);
            toggleDot.setVisible(lost);
            refreshToggle.run();
        };
        app.addTrackingBadgeRefresh(refreshBadge);
        refreshBadge.run();

        // ---- slide the drawer out / in -----------------------------------
        // Layout: overlay below the breakpoint (drawer floats, scrim shown), push above
        // it. Push is expressed by narrowing the map, not by reflowing a sibling, so the
        // drawer never has to be re-parented between modes.
        final boolean[] overlayMode = {true};
        final javafx.animation.Transition[] running = {null};
        final javafx.animation.PauseTransition[] settle = {null};

        // The map adopts its new width exactly once, a beat after the slide ends, so the
        // canvas-width binding never fires mid-animation and no tiles are re-fetched
        // while the drawer is moving.
        Runnable applyMapWidth = () -> {
            double reserved = overlayMode[0] || app.trackingPanelCollapsed ? 0 : PANEL_WIDTH;
            map.setMaxWidth(Math.max(240, content.getWidth() - reserved));
        };
        Runnable settleResize = () -> {
            if (settle[0] != null) settle[0].stop();
            settle[0] = new javafx.animation.PauseTransition(motion(CLOSE_MS));
            settle[0].setOnFinished(e -> applyMapWidth.run());
            settle[0].play();
        };

        // Interruptible: stop whatever is in flight and reverse from wherever the drawer
        // currently is, so a fast double-toggle never jumps or runs two transitions at
        // once on the same node.
        Runnable applyCollapsed = () -> {
            boolean closing = app.trackingPanelCollapsed;
            refreshToggle.run();
            if (running[0] != null) { running[0].stop(); running[0] = null; }
            if (settle[0] != null) { settle[0].stop(); settle[0] = null; }

            // Cache the drawer for the duration only: a cached subtree repaints from a
            // snapshot, so the slide costs no re-layout. Cleared afterwards so the text
            // stays sharp when the drawer is at rest.
            active.setCache(true);
            active.setCacheHint(javafx.scene.CacheHint.SPEED);

            double from = active.getTranslateX();
            double to = closing ? PANEL_WIDTH : 0;
            Duration duration = motion(closing ? CLOSE_MS : OPEN_MS);
            boolean showScrim = !closing && overlayMode[0];

            active.setVisible(true);
            javafx.animation.TranslateTransition slide =
                    new javafx.animation.TranslateTransition(duration, active);
            slide.setInterpolator(Interpolator.EASE_BOTH);
            slide.setFromX(from);
            slide.setToX(to);

            scrim.setVisible(showScrim || scrim.getOpacity() > 0);
            scrim.setMouseTransparent(!showScrim);
            javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(duration, scrim);
            fade.setInterpolator(Interpolator.EASE_BOTH);
            fade.setFromValue(scrim.getOpacity());
            fade.setToValue(showScrim ? 0.34 : 0);

            javafx.animation.ParallelTransition group = new javafx.animation.ParallelTransition(slide, fade);
            group.setOnFinished(e -> {
                active.setCache(false);
                active.setMouseTransparent(closing);
                if (closing) { active.setVisible(false); scrim.setOpacity(0); scrim.setVisible(false); }
                if (app.trackingPanelCollapsed == closing) settleResize.run();
            });
            running[0] = group;
            group.play();
        };

        panelToggle.setOnMouseClicked(event -> {
            app.trackingPanelCollapsed = !app.trackingPanelCollapsed;
            saveDrawerOpen(!app.trackingPanelCollapsed);
            applyCollapsed.run();
        });
        panelToggle.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER
                    || event.getCode() == javafx.scene.input.KeyCode.SPACE) {
                app.trackingPanelCollapsed = !app.trackingPanelCollapsed;
                saveDrawerOpen(!app.trackingPanelCollapsed);
                applyCollapsed.run();
                event.consume();
            }
        });
        closeDrawer.setOnAction(event -> {
            app.trackingPanelCollapsed = true;
            saveDrawerOpen(false);
            applyCollapsed.run();
        });
        scrim.setOnMousePressed(event -> {
            app.trackingPanelCollapsed = true;
            saveDrawerOpen(false);
            applyCollapsed.run();
        });
        // Esc closes the drawer, but only while it owns focus, so it never steals the
        // key from a dialog or the filter field.
        active.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE && !app.trackingPanelCollapsed) {
                app.trackingPanelCollapsed = true;
                saveDrawerOpen(false);
                applyCollapsed.run();
                event.consume();
            }
        });

        // Restore the stored state without playing the animation: place the drawer at
        // its resting position directly, so opening the page never animates.
        app.trackingPanelCollapsed = !loadDrawerOpen();
        refreshToggle.run();
        if (app.trackingPanelCollapsed) {
            active.setTranslateX(PANEL_WIDTH);
            active.setVisible(false);
            active.setMouseTransparent(true);
        }
        scrim.setOpacity(0);
        scrim.setVisible(false);
        scrim.setMouseTransparent(true);
        overlayMode[0] = drawerOverlay(900);
        applyMapWidth.run();

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
        map.getChildren().addAll(app.trackingCanvas, app.trackingMaps.zoomBar, search,
                app.focusChipBox, app.legendBox(), app.trackingLegend);
        HBox.setHgrow(search, ALWAYS);
        // Map canvas follows the panel width so the drawer really does give the space back.
        app.trackingCanvas.widthProperty().bind(map.widthProperty().subtract(PANEL_PADDING_H));
        map.widthProperty().addListener((o, a, b) -> { if (app.trackingMaps != null) app.trackingMaps.requestRedraw(); });
        map.heightProperty().addListener((o, a, b) -> { if (app.trackingMaps != null) app.trackingMaps.requestRedraw(); });

        fixWidth(active, PANEL_WIDTH);

        StackPane.setAlignment(mapOverlay, Pos.TOP_LEFT);
        mapOverlay.setPadding(new Insets(PANEL_INSET - 2, 0, 0, PANEL_INSET));
        StackPane.setAlignment(scrim, Pos.TOP_LEFT);
        StackPane.setAlignment(panelToggle, Pos.TOP_RIGHT);
        panelToggle.setTranslateX(-PANEL_INSET);
        panelToggle.setTranslateY(PANEL_INSET);
        StackPane.setAlignment(active, Pos.TOP_RIGHT);

        // The breakpoint is measured on this container, and re-evaluated as the window
        // resizes. Only the map width changes — never the drawer's.
        content.widthProperty().addListener((o, a, b) -> {
            boolean now = drawerOverlay(b.doubleValue());
            if (now != overlayMode[0]) {
                overlayMode[0] = now;
                applyMapWidth.run();
                if (!app.trackingPanelCollapsed) { scrim.setVisible(now); scrim.setMouseTransparent(!now); }
            } else {
                applyMapWidth.run();
            }
        });
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
