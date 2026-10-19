package edu.pb.dcls;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.TranslateTransition;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import static edu.pb.dcls.Domain.OrderStatus;
import static edu.pb.dcls.Domain.Shipment;
import static edu.pb.dcls.UiFactory.button;
import static edu.pb.dcls.UiFactory.chipCol;
import static edu.pb.dcls.UiFactory.pageHeader;
import static edu.pb.dcls.UiFactory.panel;
import static edu.pb.dcls.UiFactory.wrapTextCol;
import static javafx.scene.layout.Priority.ALWAYS;

/**
 * Live tracking screen: real GPS (NMEA / mobile / GPX) over WiFi.
 * No simulation — markers move only when a real fix is ingested.
 *
 * <p>The map owns the whole page. Everything else — search, the icon rail, the
 * zoom controls, the tour card and every panel — floats on top of it in a single
 * {@link StackPane}, so opening or closing a panel never changes a single pixel of
 * map layout: the canvas is bound to its host and nothing else can resize it. That
 * is what keeps the map's centre and zoom steady while a panel animates, and why
 * there is no {@code invalidateSize}/{@code fitBounds} call anywhere near a panel
 * transition any more.
 *
 * <p>Exactly one panel is open at a time. The active deliveries panel is a
 * right-hand drawer, the other three are small popovers anchored beside the rail.
 *
 * <p>Overlays never swallow map input. Each floating host is laid out at its own
 * preferred size (never stretched across the map) and every container uses
 * {@code pickOnBounds(false)}, so the empty space inside and between the overlays
 * falls straight through to the canvas underneath — dragging and scrolling the map
 * still work everywhere except on a control that is actually under the pointer.
 */
final class TrackingView {
    private final DclsApp app;
    /** Rebuilds the open popover in place. The Layers panel calls this after every tick
     *  so its checkboxes and counts can never describe a state the map is not in. Wired
     *  to the page's own renderer once that exists. */
    private Runnable popoverRefresh = () -> { };

    /** Fixed drawer width in px. Sized so all five columns fit the content area
     *  without clipping while still leaving the map the majority of the screen. */
    static final double PANEL_WIDTH = 440;
    /** Slide durations: opening eases in slightly longer than closing, which reads as
     *  responsive rather than sluggish on the way back. */
    static final double OPEN_MS = 280, CLOSE_MS = 220;
    /** Below this map-container width the layout goes compact: the icon rail
     *  collapses into a single menu button and the panels become bottom sheets.
     *  Measured on the map container, not the stage — the nav sidebar already eats
     *  ~200px, so a stage-based threshold would switch far too late. */
    static final double COMPACT_MIN_WIDTH = 800;
    /** Gap between a floating control and the edge of the map. */
    static final double OVERLAY_INSET = 16;
    /** Width of the floating search bar. */
    static final double SEARCH_WIDTH = 340;
    /** Side length of the square icon buttons in the rail. */
    static final double RAIL_SIZE = 42;
    /** Horizontal gap between the rail and the popover anchored beside it. */
    static final double POPOVER_GAP = 10;
    /** Vertical band the drawer starts below, so the rail stays clickable above it. */
    static final double RAIL_BAND = OVERLAY_INSET + RAIL_SIZE + POPOVER_GAP;
    /** Spacing between the rail's stacked icons, and the rail's own padding. Mirrored
     *  by the VBox gap and the .rail CSS rule, so the rail's height is knowable here. */
    static final double RAIL_ITEM_GAP = 8;
    static final double RAIL_PADDING = 6;
    /** Total height of the four-icon rail. */
    static final double RAIL_HEIGHT = 4 * RAIL_SIZE + 3 * RAIL_ITEM_GAP + 2 * RAIL_PADDING;
    /** Top inset of the drawer: it hangs below the *whole* rail, so the drawer can
     *  never cover the icons — including the one that closes it. */
    static final double DRAWER_TOP = OVERLAY_INSET + RAIL_HEIGHT + POPOVER_GAP;
    /** Popover fade + slide, the same in both directions. */
    static final double POPOVER_MS = 140;
    /** Bottom inset of the tour card. MapRenderer paints the scale bar MAP_PAD (14px)
     *  in from the bottom-left corner, so the card clears that band instead of
     *  sitting on top of it. */
    static final double TOUR_CARD_INSET = 52;
    /** Bottom inset of the zoom stack. MapRenderer paints the OpenStreetMap
     *  attribution along the bottom-right edge, so the stack stays above it. */
    static final double ZOOM_INSET = 34;
    /** How long typing settles before the search dropdown queries Nominatim. */
    static final double SEARCH_DEBOUNCE_MS = 450;

    /** The one panel that may be open at a time. */
    enum Panel { NONE, DELIVERIES, LEGEND, LAYERS, HELP }

    TrackingView(DclsApp app) { this.app = app; }

    // ---- pure, headlessly testable helpers ---------------------------

    /** Honours reduced motion by collapsing every duration to zero. */
    static Duration motionDuration(boolean reduced, double millis) {
        return reduced ? Duration.ZERO : Duration.millis(millis);
    }

    /** Honours the app's reduced-motion setting. */
    private Duration motion(double millis) {
        return motionDuration(app.reduceMotion, millis);
    }

    /** Compact layout (rail as one menu button, panels as bottom sheets) below the
     *  breakpoint, floating rail and popovers at or above it. */
    static boolean compactLayout(double containerWidth) {
        return containerWidth < COMPACT_MIN_WIDTH;
    }

    /** Opening the already-open panel closes it; opening any other panel replaces it.
     *  This single rule is what guarantees only one panel is ever on screen. */
    static Panel togglePanel(Panel current, Panel target) {
        if (target == null || target == Panel.NONE) return Panel.NONE;
        return current == target ? Panel.NONE : target;
    }

    /** True when the press landed outside the popover, in the given (scene) bounds.
     *  Shared with the Esc/outside-click dismiss so both paths agree on "outside". */
    static boolean pressOutsidePanel(double pressX, double pressY,
                                     double panelX, double panelY, double panelW, double panelH) {
        return pressX < panelX || pressX > panelX + panelW
                || pressY < panelY || pressY > panelY + panelH;
    }

    /** The floating tour card's single line. The plate and the short summary go on the
     *  card; the long vehicle/tour id lives in the tooltip and the copy action instead
     *  of being rendered as an unreadable row of digits. */
    static String tourCardText(String plate, int stops, double kmLeft) {
        String name = plate == null || plate.isBlank() ? "—" : plate;
        return "Tour " + name + " · " + stops + " stops · "
                + String.format(Locale.ROOT, "%.1f km left", kmLeft);
    }

    /** Shortest-tour variant used when the tour cannot be computed at all. */
    static String tourCardText(String plate) {
        return "Tour " + (plate == null || plate.isBlank() ? "—" : plate);
    }

    /** The mouse gesture help that used to sit in the map's heading row. It moved into
     *  the Help panel with the rest of the map affordances. */
    static String mapUsageHelpLine() {
        return "Scroll to zoom, drag to pan";
    }

    /** Placeholder for the floating search bar. Kept in one place so the encoding
     *  contract (a real U+2026, read as UTF-8) is asserted against the same string the
     *  UI actually shows. */
    static String searchPrompt() {
        return "Search place in Brunei…";
    }

    /** Count badge on the rail's deliveries button. Zero means no badge at all. */
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

    /**
     * Active deliveries with a lost signal first. "NO FIX" is the row an operator has
     * to act on, so burying it under healthy rows hides the only thing worth doing.
     * Order within each group is the caller's, which keeps this stable and testable.
     */
    static List<Shipment> lostFixFirst(List<Shipment> rows, Function<String, String> gpsStatus) {
        if (rows == null || rows.isEmpty()) return List.of();
        if (gpsStatus == null) return List.copyOf(rows);
        return rows.stream()
                .sorted(Comparator.comparingInt(row -> noFixRank(row, gpsStatus)))
                .toList();
    }

    /** 0 when the row's vehicle has lost its fix, 1 otherwise. Unassigned rows rank 1. */
    private static int noFixRank(Shipment row, Function<String, String> gpsStatus) {
        String vehicleId = row.vehicleId();
        if (vehicleId == null) return 1;
        String status = gpsStatus.apply(vehicleId);
        return status != null && (status.contains("NO FIX") || status.contains("LOST")) ? 0 : 1;
    }

    /** Accessible name for the deliveries rail button. The button is an icon, so this
     *  is the only label it has; it must always offer the opposite of the current state. */
    static String panelToggleAccessibleText(boolean collapsed) {
        return collapsed ? "Show active deliveries" : "Hide active deliveries";
    }

    /**
     * Screen-reader text for the map canvas, which is otherwise a blank rectangle to a
     * non-visual user: "Fleet map. 3 vehicles shown. 3 with no live fix."
     */
    static String mapAccessibleSummary(int shown, int lost) {
        StringBuilder text = new StringBuilder("Fleet map. ");
        text.append(shown == 1 ? "1 vehicle shown." : shown + " vehicles shown.");
        if (lost > 0) {
            text.append(' ').append(lost == 1 ? "1 has" : lost + " have")
                    .append(" no live fix.");
        }
        text.append(" Use arrow keys to pan, plus and minus to zoom, R to reset.");
        return text.toString();
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

    /** The last panel the operator had open, so the page comes back the way it was
     *  left. The drawer keeps its own legacy key, which older installs already wrote. */
    static Panel loadOpenPanel() {
        try {
            if (prefs().getBoolean(PREF_OPEN, false)) return Panel.DELIVERIES;
            String stored = prefs().get(PREF_PANEL, Panel.NONE.name());
            return Panel.valueOf(stored);
        } catch (RuntimeException unavailable) { return Panel.NONE; }
    }

    static void saveOpenPanel(Panel open) {
        Panel panel = open == null ? Panel.NONE : open;
        try {
            prefs().putBoolean(PREF_OPEN, panel == Panel.DELIVERIES);
            prefs().put(PREF_PANEL, panel.name());
        } catch (RuntimeException ignored) { }
    }

    private static final String PREF_OPEN = "trackingDrawerOpen";
    private static final String PREF_PANEL = "trackingOpenPanel";

    /** Keyboard shortcuts, as shown in the Help panel and asserted by a test so the
     *  documented list cannot silently drift from the ones actually bound. */
    static List<String> shortcutHelpLines() {
        return List.of(
                "Arrow keys — pan the map",
                "+ / − — zoom in and out",
                "R — reset the map view",
                "D — show or hide active deliveries",
                "F — jump to the delivery filter",
                "Esc — close the open panel",
                "Enter / Space — activate the focused control");
    }

    private static java.util.prefs.Preferences prefs() {
        return java.util.prefs.Preferences.userNodeForPackage(TrackingView.class);
    }

    /**
     * Stop a StackPane stretching this node across the whole map. StackPane resizes
     * managed children to fill and then ignores their alignment, so a corner control
     * needs an explicit USE_PREF_SIZE ceiling to stay corner-sized.
     */
    private static void hug(Region node) {
        node.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
    }

    /** Pin a node to one width: min == pref == max so no layout can move it. */
    private static void fixWidth(Region node, double width) {
        node.setMinWidth(width);
        node.setPrefWidth(width);
        node.setMaxWidth(width);
    }

    /**
     * Adds a floating overlay layer to the map stack, pinned to one corner: laid out at
     * its own preferred size (never stretched across the map) and hit-tested only where
     * a control actually is, so everything else keeps scrolling and dragging the map
     * underneath.
     *
     * <p>The corner has to be applied twice — once for the control inside the layer and
     * once for the layer itself inside the map. Setting only the inner one silently
     * leaves every overlay stacked in the middle of the map.
     */
    private static StackPane floatLayer(StackPane parent, Node content, Pos corner) {
        StackPane layer = new StackPane(content);
        StackPane.setAlignment(content, corner);
        StackPane.setAlignment(layer, corner);
        layer.setPickOnBounds(false);
        hug(layer);
        parent.getChildren().add(layer);
        return layer;
    }

    VBox page() {
        // pageHeader supplies the .workspace page style; its padding is dropped because
        // the map now runs edge to edge under the top bar.
        VBox page = pageHeader("");
        page.setPadding(Insets.EMPTY);
        page.setSpacing(0);

        app.trackingMaps = app.new MapController(470, 350);
        app.trackingMaps.content = () -> app.trackingFrame;
        app.trackingMaps.painter = (g, v) -> app.paintTracking(g, app.trackingMaps.canvas.getWidth(), app.trackingMaps.canvas.getHeight(), v);
        app.trackingCanvas = app.trackingMaps.canvas;
        app.trackingLegend = new VBox(3);
        app.focusChipBox = new VBox(4);
        app.focusChipBox.getStyleClass().add("tour-card");

        // ---- the map: the only node in the stack that fills and resizes -----------
        // The map stack is built here and every overlay is added to it as it is
        // created, so the layer order below is the order of construction.
        StackPane layers = new StackPane();
        StackPane mapHost = new StackPane(app.trackingCanvas);
        layers.getChildren().add(mapHost);
        mapHost.getStyleClass().add("map-shell");
        // Canvas is resizable but reports its current size as its preferred size, so
        // keeping it out of the host's measurement avoids a sizing loop. It follows
        // the host exactly — no padding subtracted, which is what used to leave an 8px
        // gap between the drawn map and the map's own right-hand edge.
        app.trackingCanvas.setManaged(false);
        app.trackingCanvas.widthProperty().bind(mapHost.widthProperty());
        app.trackingCanvas.heightProperty().bind(mapHost.heightProperty());
        app.trackingCanvas.widthProperty().addListener((o, a, b) -> app.trackingMaps.requestRedraw());
        app.trackingCanvas.heightProperty().addListener((o, a, b) -> app.trackingMaps.requestRedraw());

        // A bare Canvas cannot paint a focus ring of its own, so the map borrows one
        // — but only for genuine keyboard use. The canvas is the page's first
        // focus-traversable node, so JavaFX hands it focus on its own whenever the
        // page is shown or the window regains focus with no owner; drawing the ring
        // then would flash a blue outline on every visit to this tab.
        final boolean[] keyboardMode = {false};
        Runnable focusRing = () -> {
            boolean on = app.trackingCanvas.isFocused() && keyboardMode[0];
            if (on) mapHost.getStyleClass().add("map-focused");
            else mapHost.getStyleClass().remove("map-focused");
        };
        app.trackingCanvas.focusedProperty().addListener((o, was, is) -> focusRing.run());
        // A keypress reaching the canvas is by definition a Tab away from keyboard use.
        app.trackingCanvas.addEventFilter(KeyEvent.KEY_PRESSED,
                event -> { keyboardMode[0] = true; focusRing.run(); });
        app.trackingCanvas.addEventFilter(MouseEvent.MOUSE_PRESSED,
                event -> { keyboardMode[0] = false; focusRing.run(); });

        // ---- floating search, top-left -------------------------------------------
        TextField search = new TextField();
        search.setPromptText(searchPrompt());
        search.setAccessibleText("Search for a place in Brunei");
        search.setPrefWidth(SEARCH_WIDTH - 34);
        search.setMaxWidth(SEARCH_WIDTH - 34);
        Button searchClear = button("✕", "search-clear");
        searchClear.setAccessibleText("Clear the place search");
        searchClear.setFocusTraversable(true);
        HBox searchBar = new HBox(4, search, searchClear);
        searchBar.getStyleClass().add("search-float");
        searchBar.setPickOnBounds(false);
        VBox searchResults = new VBox(4);
        searchResults.getStyleClass().add("search-results");
        searchResults.setPickOnBounds(false);
        searchResults.setVisible(false);
        searchResults.setManaged(false);
        VBox searchLayer = new VBox(6, searchBar, searchResults);
        searchLayer.setPickOnBounds(false);
        StackPane searchHost = floatLayer(layers, searchLayer, Pos.TOP_LEFT);
        searchHost.setPadding(new Insets(OVERLAY_INSET, 0, 0, OVERLAY_INSET));

        // Search runs on a worker thread exactly as before: the FX thread never waits
        // on the network. A keystroke is debounced, so typing a place name issues one
        // query instead of one per character.
        PauseTransition searchDebounce = new PauseTransition(Duration.millis(SEARCH_DEBOUNCE_MS));
        Runnable closeResults = () -> {
            searchResults.setVisible(false);
            searchResults.setManaged(false);
            searchResults.getChildren().clear();
        };
        Runnable runSearch = () -> {
            String query = search.getText();
            if (query == null || query.isBlank()) { closeResults.run(); return; }
            Thread thread = new Thread(() -> {
                List<MapRenderer.PlaceHit> hits = MapRenderer.searchPlaces(query);
                javafx.application.Platform.runLater(() -> {
                    searchResults.getChildren().clear();
                    if (hits.isEmpty()) {
                        searchResults.getChildren().add(resultRow("No results in Brunei for: " + query, null));
                        app.toast("No results in Brunei for: " + query);
                    } else {
                        for (MapRenderer.PlaceHit hit : hits) {
                            searchResults.getChildren().add(resultRow(hit.displayName(), hit));
                        }
                    }
                    searchResults.setVisible(true);
                    searchResults.setManaged(true);
                });
            });
            thread.setDaemon(true);
            thread.start();
        };
        search.setOnAction(event -> { searchDebounce.stop(); runSearch.run(); });
        search.textProperty().addListener((o, a, b) -> {
            searchClear.setVisible(!b.isBlank());
            searchDebounce.stop();
            searchDebounce.setOnFinished(event -> runSearch.run());
            searchDebounce.playFromStart();
        });
        searchClear.setOnAction(event -> { search.setText(""); closeResults.run(); });
        searchClear.setVisible(false);

        // ---- zoom controls, bottom-right ------------------------------------------
        StackPane zoomHost = floatLayer(layers, app.trackingMaps.zoomStack, Pos.BOTTOM_RIGHT);
        zoomHost.setPadding(new Insets(0, OVERLAY_INSET, ZOOM_INSET, OVERLAY_INSET));

        // ---- tour info card, bottom-left -----------------------------------------
        // The card stays managed so it occupies its own corner of the map; it is
        // hidden (and therefore not laid out at all) whenever no tour is focused.
        StackPane tourHost = floatLayer(layers, app.focusChipBox, Pos.BOTTOM_LEFT);
        tourHost.setPadding(new Insets(0, 0, TOUR_CARD_INSET, OVERLAY_INSET));
        app.focusChipBox.setVisible(false);

        // ---- the icon rail, top-right --------------------------------------------
        Button deliveriesBtn = railButton("Deliveries", IC_DELIVERIES);
        deliveriesBtn.setAccessibleText(panelToggleAccessibleText(false));
        deliveriesBtn.setTooltip(new Tooltip("Deliveries (D)"));
        Label badge = new Label();
        badge.getStyleClass().add("count-badge");
        Region warningDot = new Region();
        warningDot.getStyleClass().add("warning-dot");
        deliveriesBtn.setGraphic(deliveriesGlyph(badge, warningDot));
        deliveriesBtn.setText("");
        Button legendBtn = railButton("Legend", IC_LEGEND);
        legendBtn.setTooltip(new Tooltip("Legend"));
        Button layersBtn = railButton("Layers and filters", IC_LAYERS);
        layersBtn.setTooltip(new Tooltip("Layers and filters"));
        Button helpBtn = railButton("Help", IC_HELP);
        helpBtn.setTooltip(new Tooltip("Help and keyboard shortcuts"));
        VBox rail = new VBox(RAIL_ITEM_GAP, deliveriesBtn, legendBtn, layersBtn, helpBtn);
        rail.getStyleClass().add("rail");
        rail.setPickOnBounds(false);
        StackPane railHost = floatLayer(layers, rail, Pos.TOP_RIGHT);
        railHost.setPadding(new Insets(OVERLAY_INSET, OVERLAY_INSET, 0, 0));

        // Below the breakpoint the four icons become one menu button: the rail simply
        // has nowhere to go, and four stacked buttons would cover the map. The compact
        // button carries its own badge and dot — a node can only have one parent, so it
        // cannot share the rail's.
        Label compactBadge = new Label();
        compactBadge.getStyleClass().add("count-badge");
        Region compactDot = new Region();
        compactDot.getStyleClass().add("warning-dot");
        MenuPanelRail compactRail = MenuPanelRail.create(compactBadge, compactDot);
        StackPane compactRailHost = floatLayer(layers, compactRail.button(), Pos.TOP_RIGHT);
        compactRailHost.setPadding(new Insets(OVERLAY_INSET, OVERLAY_INSET, 0, 0));
        compactRailHost.setVisible(false);
        compactRailHost.setManaged(false);

        // ---- panels -----------------------------------------------------------------
        // One popover host, its content swapped per panel: the four panels are mutually
        // exclusive, so giving them one anchor keeps them from drifting apart and makes
        // "only one is open" a property of the scene graph rather than of bookkeeping.
        VBox popoverCard = new VBox(10);
        popoverCard.getStyleClass().add("panel-popover");
        popoverCard.setPickOnBounds(false);
        // Same grip as the drawer: visible only when the popover is a bottom sheet. It is a
        // second node rather than a shared one because a Node cannot have two parents,
        // and both grips live in the graph at all times.
        Region popoverHandle = new Region();
        popoverHandle.getStyleClass().add("sheet-handle");
        popoverHandle.setMaxWidth(72);
        VBox popoverGrip = new VBox(popoverHandle);
        popoverGrip.setAlignment(Pos.CENTER);
        popoverGrip.setPickOnBounds(false);
        StackPane popoverHost = floatLayer(layers, popoverCard, Pos.TOP_RIGHT);
        // Right padding pushes the card clear of the rail without any manual offsets.
        popoverHost.setPadding(new Insets(OVERLAY_INSET, RAIL_BAND, 0, 0));
        popoverHost.setVisible(false);
        popoverHost.setMouseTransparent(true);

        // ---- deliveries drawer -------------------------------------------------------
        List<Shipment> activeShipments = app.visibleShipments().stream()
                .filter(s -> s.status() == OrderStatus.IN_TRANSIT || s.status() == OrderStatus.ASSIGNED).toList();

        Label activeTitle = new Label("Active deliveries");
        activeTitle.getStyleClass().add("section-title");
        Label countBadge = new Label();
        countBadge.getStyleClass().add("count-badge");
        ToggleButton follow = new ToggleButton("Follow selected");
        follow.setSelected(app.followSelected);
        follow.setTooltip(new Tooltip("Keep the map centred on the selected delivery"));
        follow.setOnAction(e -> app.followSelected = follow.isSelected());
        Button closeDrawer = new Button("✕");
        closeDrawer.getStyleClass().add("drawer-close");
        closeDrawer.setAccessibleText("Close active deliveries");
        Region headerGap = new Region();
        HBox.setHgrow(headerGap, ALWAYS);
        HBox activeHeader = new HBox(8, activeTitle, countBadge, follow, headerGap, closeDrawer);
        activeHeader.setAlignment(Pos.CENTER_LEFT);

        VBox active = panel(activeHeader);
        active.getStyleClass().add("tracking-drawer");
        fixWidth(active, PANEL_WIDTH);

        // Bottom sheets get a grip at the top. On a floating panel it is invisible, so
        // it costs nothing and never appears unless the layout is actually compact.
        Region sheetHandle = new Region();
        sheetHandle.getStyleClass().add("sheet-handle");
        sheetHandle.setMaxWidth(72);
        VBox sheetGrip = new VBox(sheetHandle);
        sheetGrip.setAlignment(Pos.CENTER);
        sheetGrip.setPickOnBounds(false);
        sheetGrip.setVisible(false);
        sheetGrip.setManaged(false);
        active.getChildren().add(0, sheetGrip);

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
        table.setAccessibleText("Active deliveries");
        table.setPlaceholder(new Label("No active deliveries — assign an order first."));

        // Widths sum to 392, not the drawer's 408px content width: the vertical scrollbar
        // takes ~14px, and CONSTRAINED_RESIZE_POLICY responds to that shortfall by
        // shrinking every column, which ellipsis-clips all of them at once.
        table.getColumns().add(wrapTextCol("Order", Shipment::id, 88));
        table.getColumns().add(wrapTextCol("Vehicle", s -> app.vehiclePlate(s.vehicleId()), 76));
        table.getColumns().add(chipCol("Fix",
                s -> s.vehicleId() == null ? "—" : app.tracking.gpsStatus(s.vehicleId()),
                (s, text) -> text.startsWith("NO FIX") ? "status-bad"
                        : text.startsWith("LOST") ? "status-warn" : "status-good", 76));
        table.getColumns().add(wrapTextCol("Destination", Shipment::address, 84));
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

        // The drawer hangs below the rail rather than over it, and its host is added
        // before the rail's own layers so the rail always wins a click. The host keeps
        // the drawer's height free (it fills the map) while hugging its width, and its
        // top padding is the band the rail occupies.
        StackPane drawerHost = new StackPane(active);
        StackPane.setAlignment(active, Pos.TOP_RIGHT);
        StackPane.setAlignment(drawerHost, Pos.TOP_RIGHT);
        drawerHost.setPadding(new Insets(DRAWER_TOP, 0, 0, 0));
        drawerHost.setPickOnBounds(false);
        drawerHost.setMaxWidth(Region.USE_PREF_SIZE);
        layers.getChildren().add(5, drawerHost);

        // ---- panel state: exactly one open at a time --------------------------------
        final Panel[] openPanel = {Panel.NONE};
        final boolean[] compact = {false};
        /** Whether the drawer is actually on screen right now. Tracked separately from
         *  {@code app.trackingPanelCollapsed} (what was asked for) because those two
         *  drift apart mid-animation, and deciding the animation from the requested
         *  state alone left the open path doing nothing at all. */
        final boolean[] drawerShown = {false};
        final javafx.animation.Transition[] running = {null};
        final Map<Panel, Button> railButtons = Map.of(
                Panel.DELIVERIES, deliveriesBtn,
                Panel.LEGEND, legendBtn,
                Panel.LAYERS, layersBtn,
                Panel.HELP, helpBtn);

        // A bottom sheet owns the bottom of the map, so the controls that live there would be
        // buried under it. On a wide window the drawer leaves them visible.
        Runnable applyOverlayVisibility = () -> {
            boolean sheetCovers = compact[0] && openPanel[0] != Panel.NONE;
            zoomHost.setVisible(!sheetCovers);
            zoomHost.setManaged(!sheetCovers);
            tourHost.setVisible(!sheetCovers);
            tourHost.setManaged(!sheetCovers);
        };

        Runnable updateRailSelection = () -> {
            Panel open = openPanel[0];
            for (Map.Entry<Panel, Button> entry : railButtons.entrySet()) {
                boolean on = open == entry.getKey();
                if (on) entry.getValue().getStyleClass().add("active");
                else entry.getValue().getStyleClass().remove("active");
            }
            deliveriesBtn.setAccessibleText(panelToggleAccessibleText(open != Panel.DELIVERIES));
            compactRail.refresh(open);
        };

        Runnable renderPopover = () -> {
            popoverCard.getChildren().clear();
            if (compact[0]) popoverCard.getChildren().add(popoverGrip);
            switch (openPanel[0]) {
                case LEGEND -> popoverCard.getChildren().addAll(app.legendBox(), app.trackingLegend);
                case HELP -> popoverCard.getChildren().addAll(helpContent());
                case LAYERS -> popoverCard.getChildren().addAll(layersContent(activeShipments));
                default -> { }
            }
        };
        // The Layers panel rebuilds itself through this rather than through a captured
        // local, which a lambda cannot reference inside its own initializer.
        popoverRefresh = renderPopover;

        Runnable hidePopover = () -> {
            if (!popoverHost.isVisible()) return;
            double rest = popoverHost.getTranslateY();
            double away = compact[0] ? rest + 8 : rest - 8;
            Duration duration = motion(POPOVER_MS);
            FadeTransition fade = new FadeTransition(duration, popoverHost);
            fade.setInterpolator(Interpolator.EASE_BOTH);
            fade.setFromValue(popoverHost.getOpacity());
            fade.setToValue(0);
            TranslateTransition slide = new TranslateTransition(duration, popoverHost);
            slide.setInterpolator(Interpolator.EASE_BOTH);
            slide.setFromY(rest);
            slide.setToY(away);
            ParallelTransition group = new ParallelTransition(fade, slide);
            group.setOnFinished(event -> {
                popoverHost.setVisible(false);
                popoverHost.setMouseTransparent(true);
                popoverHost.setOpacity(1);
                popoverHost.setTranslateY(0);
            });
            group.play();
        };
        Runnable showPopover = () -> {
            double from = compact[0] ? 8 : -8;
            popoverHost.setVisible(true);
            popoverHost.setMouseTransparent(false);
            popoverHost.setOpacity(0);
            popoverHost.setTranslateY(from);
            Duration duration = motion(POPOVER_MS);
            FadeTransition fade = new FadeTransition(duration, popoverHost);
            fade.setInterpolator(Interpolator.EASE_BOTH);
            fade.setFromValue(0);
            fade.setToValue(1);
            TranslateTransition slide = new TranslateTransition(duration, popoverHost);
            slide.setInterpolator(Interpolator.EASE_BOTH);
            slide.setFromY(from);
            slide.setToY(0);
            new ParallelTransition(fade, slide).play();
        };

        // Hidden position of the drawer: off the right edge normally, below the map in
        // compact mode. Reading the live height keeps the sheet a full map-height slide
        // whatever the window size is.
        Runnable drawerHidden = () -> {
            double hidden = compact[0] ? Math.max(240, active.getHeight()) : PANEL_WIDTH;
            active.setTranslateX(compact[0] ? 0 : hidden);
            active.setTranslateY(compact[0] ? hidden : 0);
        };

        // Places the drawer at its resting position with no transition: used for the
        // restore-on-open path and whenever the breakpoint flips mid-animation.
        Runnable placeDrawer = () -> {
            if (running[0] != null) { running[0].stop(); running[0] = null; }
            boolean closed = app.trackingPanelCollapsed;
            active.setCache(false);
            if (closed) {
                drawerHidden.run();
                active.setVisible(false);
                active.setMouseTransparent(true);
            } else {
                active.setVisible(true);
                active.setMouseTransparent(false);
                active.setTranslateX(0);
                active.setTranslateY(0);
            }
            drawerShown[0] = !closed;
        };

Runnable applyDrawer = () -> {
            boolean closing = app.trackingPanelCollapsed;
            if (running[0] != null) { running[0].stop(); running[0] = null; }

            active.setVisible(true);
            double hiddenX = compact[0] ? 0 : PANEL_WIDTH;
            double hiddenY = compact[0] ? Math.max(240, active.getHeight()) : 0;
            Duration duration = motion(closing ? CLOSE_MS : OPEN_MS);

            // Reduced motion: place the panel at its resting position instead of playing
            // a transition. A zero-length TranslateTransition does not reliably apply
            // its end value, which left the drawer "open" but parked off-screen.
            if (duration.toMillis() <= 0) {
                active.setCache(false);
                active.setTranslateX(closing ? hiddenX : 0);
                active.setTranslateY(closing ? hiddenY : 0);
                active.setMouseTransparent(closing);
                if (closing) active.setVisible(false);
                drawerShown[0] = !closing;
                (closing ? deliveriesBtn : filter).requestFocus();
                return;
            }

            // Cache the drawer for the duration only: a cached subtree repaints from a
            // snapshot, so the slide costs no re-layout. Cleared afterwards so the text
            // stays sharp when the drawer is at rest.
            active.setCache(true);
            active.setCacheHint(javafx.scene.CacheHint.SPEED);

            javafx.animation.TranslateTransition slide = new javafx.animation.TranslateTransition(duration, active);
            slide.setInterpolator(Interpolator.EASE_BOTH);
            slide.setFromX(active.getTranslateX());
            slide.setToX(closing ? hiddenX : 0);
            slide.setFromY(active.getTranslateY());
            slide.setToY(closing ? hiddenY : 0);

            javafx.animation.Transition group = slide;
            group.setOnFinished(event -> {
                active.setCache(false);
                active.setMouseTransparent(closing);
                if (closing) active.setVisible(false);
            });
            running[0] = group;
            group.play();
            drawerShown[0] = !closing;
            // Hand focus across the boundary so the keyboard never gets stranded
            // behind a 440px panel: into the filter when the drawer opens, back to
            // the button that opened it when it closes.
            (closing ? deliveriesBtn : filter).requestFocus();
        };

        Runnable applyPanel = () -> {
            Panel open = openPanel[0];
            boolean drawerOpen = open == Panel.DELIVERIES;
            boolean popoverOpen = open == Panel.LEGEND || open == Panel.LAYERS || open == Panel.HELP;
            app.trackingPanelCollapsed = !drawerOpen;
            // Both panel kinds live in the same overlay layer as siblings of the map, so
            // switching between them is pure translation — no reflow of anything below.
            // The drawer is driven by whether it is *actually* on screen, so both the
            // open and the close path animate; deciding from the requested state alone
            // silently skipped the animation on the way in, which is exactly the bug
            // where clicking the Deliveries icon did nothing.
            if (drawerShown[0] != drawerOpen) applyDrawer.run();
            if (popoverOpen) {
                renderPopover.run();
                showPopover.run();
            } else {
                hidePopover.run();
            }
            updateRailSelection.run();
            applyOverlayVisibility.run();
            saveOpenPanel(open);
        };

        for (Map.Entry<Panel, Button> entry : railButtons.entrySet()) {
            Panel target = entry.getKey();
            entry.getValue().setOnAction(event -> {
                openPanel[0] = togglePanel(openPanel[0], target);
                applyPanel.run();
            });
        }
        compactRail.onSelect(panel -> { openPanel[0] = togglePanel(openPanel[0], panel); applyPanel.run(); });
        closeDrawer.setOnAction(event -> { openPanel[0] = Panel.NONE; applyPanel.run(); });
        sheetHandle.setOnMousePressed(event -> { openPanel[0] = Panel.NONE; applyPanel.run(); });

        // ---- live count and NO FIX -------------------------------------------------
        Runnable refreshBadge = () -> {
            int count = activeDeliveryBadge(table.getItems());
            countBadge.setText(String.valueOf(count));
            countBadge.setVisible(count > 0);
            boolean lost = hasNoFixWarning(table.getItems(), id -> app.tracking.gpsStatus(id));
            for (Label deliveryBadge : List.of(badge, compactBadge)) {
                deliveryBadge.setText(String.valueOf(count));
                deliveryBadge.setVisible(count > 0);
            }
            warningDot.setVisible(lost);
            compactDot.setVisible(lost);
        };
        app.addTrackingBadgeRefresh(refreshBadge);
        refreshBadge.run();

        Runnable refreshFilter = () -> table.setItems(FXCollections.observableArrayList(
                lostFixFirst(activeShipments.stream()
                        .filter(s -> matchesFilter(s, filter.getText(), app.vehiclePlate(s.vehicleId())))
                        .toList(), id -> app.tracking.gpsStatus(id))));
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

        // ---- responsive: rail vs menu button, drawer vs bottom sheet --------------
        Runnable applyCompact = () -> {
            // Width 0 means "not laid out yet". Reacting to it would put every window
            // through the compact branch for one frame before correcting itself.
            if (mapHost.getWidth() <= 0) return;
            boolean now = compactLayout(mapHost.getWidth());
            if (now == compact[0]) return;
            compact[0] = now;
            railHost.setVisible(!now);
            railHost.setManaged(!now);
            compactRailHost.setVisible(now);
            compactRailHost.setManaged(now);
            StackPane.setAlignment(active, now ? Pos.BOTTOM_CENTER : Pos.TOP_RIGHT);
            // On a wide window the drawer hangs below the rail; as a sheet it owns the
            // whole bottom edge instead.
            drawerHost.setPadding(now ? Insets.EMPTY : new Insets(DRAWER_TOP, 0, 0, 0));
            // The popover follows the rail: anchored beside it on a wide window, a
            // bottom sheet with a grip on a narrow one.
            StackPane.setAlignment(popoverCard, now ? Pos.BOTTOM_CENTER : Pos.TOP_RIGHT);
            popoverHost.setPadding(now ? Insets.EMPTY
                    : new Insets(OVERLAY_INSET, RAIL_BAND, 0, 0));
            popoverCard.getStyleClass().remove("sheet-popover");
            if (now) popoverCard.getStyleClass().add("sheet-popover");
            sheetGrip.setVisible(now);
            sheetGrip.setManaged(now);
            popoverHandle.setOnMousePressed(event -> { openPanel[0] = Panel.NONE; applyPanel.run(); });
            if (now) {
                double sheetHeight = Math.max(280, Math.min(520, mapHost.getHeight() * 0.72));
                active.getStyleClass().add("bottom-sheet");
                active.setMinWidth(0);
                active.setPrefWidth(Double.MAX_VALUE);
                active.setMaxWidth(Double.MAX_VALUE);
                // Capped, or a resizable sheet would stretch to the whole map and hide it.
                active.setPrefHeight(sheetHeight);
                active.setMaxHeight(sheetHeight);
                // The sheet is a different shape, so it is placed, not animated: a
                // half-offscreen sheet that then slides is worse than one that is
                // simply correct after the breakpoint flips.
                placeDrawer.run();
            } else {
                active.getStyleClass().remove("bottom-sheet");
                fixWidth(active, PANEL_WIDTH);
                active.setPrefHeight(Region.USE_COMPUTED_SIZE);
                active.setMaxHeight(Double.MAX_VALUE);
                placeDrawer.run();
            }
            applyOverlayVisibility.run();
            if (popoverHost.isVisible()) renderPopover.run();
        };

        // ---- Esc ----------------------------------------------------------------
        // Esc is handled on the page so it works wherever focus sits; the outside-click
        // press filter is installed on the map container further down, once the layer
        // stack exists.

        page.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isConsumed()) return;
            if (event.getCode() == KeyCode.ESCAPE && openPanel[0] != Panel.NONE) {
                openPanel[0] = Panel.NONE;
                applyPanel.run();
                event.consume();
                return;
            }
            if (event.getTarget() instanceof TextInputControl) return;
            if (event.getCode() == KeyCode.D) {
                openPanel[0] = togglePanel(openPanel[0], Panel.DELIVERIES);
                applyPanel.run();
                event.consume();
            } else if (event.getCode() == KeyCode.F) {
                if (openPanel[0] != Panel.DELIVERIES) {
                    openPanel[0] = Panel.DELIVERIES;
                    applyPanel.run();
                }
                filter.requestFocus();
                event.consume();
            }
        });
        app.trackingMaps.bindMapKeys(mapHost);

        // ---- restore the last panel, without animating ----------------------------
        // Placing the drawer and popover at their resting positions directly means
        // arriving on this page never plays a transition, and never touches the map.
        openPanel[0] = loadOpenPanel();
        app.trackingPanelCollapsed = openPanel[0] != Panel.DELIVERIES;
        placeDrawer.run();
        if (openPanel[0] == Panel.LEGEND || openPanel[0] == Panel.LAYERS || openPanel[0] == Panel.HELP) {
            renderPopover.run();
            popoverHost.setVisible(true);
            popoverHost.setMouseTransparent(false);
            popoverHost.setOpacity(1);
            popoverHost.setTranslateY(0);
        }
        updateRailSelection.run();

        // ---- layer order, back to front -------------------------------------------
        // The map went in first and fills; every overlay after it is corner-aligned at
        // its own preferred size. Everything is already in the stack by now, in the
        // order it was built: map, search, zoom, tour card, rail, drawer, menu button,
        // popover.
        layers.getStyleClass().add("map-shell");
        layers.setPickOnBounds(false);
        VBox.setVgrow(layers, ALWAYS);
        page.getChildren().add(layers);
        StackPane content = layers;

        // Outside-click dismiss for the popovers. The filter sits on the layer stack,
        // not on the canvas, so a press anywhere else in the map area — the search bar,
        // the zoom stack, empty map — counts as outside, while the rail itself is
        // excluded because pressing a rail button is that panel's own toggle.
        content.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (!popoverHost.isVisible()) return;
            if (event.getTarget() instanceof Node pressed
                    && (within(pressed, rail) || within(pressed, compactRail.button()))) return;
            Bounds bounds = popoverCard.localToScene(popoverCard.getBoundsInLocal());
            if (pressOutsidePanel(event.getSceneX(), event.getSceneY(),
                    bounds.getMinX(), bounds.getMinY(), bounds.getWidth(), bounds.getHeight())) {
                openPanel[0] = Panel.NONE;
                applyPanel.run();
            }
        });

        mapHost.widthProperty().addListener((o, a, b) -> applyCompact.run());
        mapHost.heightProperty().addListener((o, a, b) -> {
            // The sheet is sized from the map's height, so both its preferred and its
            // maximum have to follow: only the preferred one left the first, pre-layout
            // measurement in force and the sheet came out half its intended height.
            if (!compact[0]) return;
            double sheetHeight = Math.max(280, Math.min(520, b.doubleValue() * 0.72));
            active.setPrefHeight(sheetHeight);
            active.setMaxHeight(sheetHeight);
        });
        mapHost.setMinHeight(320);

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

    /** One result row in the search dropdown. A row with no hit is a plain hint. */
    private Button resultRow(String text, MapRenderer.PlaceHit hit) {
        Button row = button(text, "search-result");
        row.setMaxWidth(Double.MAX_VALUE);
        if (hit != null) {
            row.setAccessibleText("Zoom to " + hit.name());
            row.setOnAction(event -> {
                if (app.trackingMaps != null) app.trackingMaps.zoomTo(hit.lon(), hit.lat());
                app.toast(hit.name());
            });
        } else {
            row.setDisable(true);
        }
        return row;
    }

    /** Monochrome vector icons (Material Symbols, 24px viewBox), matching the nav rail's
     *  set. Glyph characters were the obvious choice here and the wrong one: several of
     *  them (◧ for instance) fall back to a different letter in the default font, so the
     *  rail ended up reading "E" instead of a legend icon. */
    private static final String IC_DELIVERIES =
            "M20 8h-3V4H3c-1.1 0-2 .9-2 2v11h2c0 1.66 1.34 3 3 3s3-1.34 3-3h6c0 1.66 1.34 3 3 3s3-1.34 3-3h2v-5l-3-4zM6 18.5c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm13.5-9l1.96 2.5H17V9.5h2.5zm-1.5 9c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z";
    private static final String IC_LEGEND =
            "M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z";
    private static final String IC_LAYERS =
            "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z";
    private static final String IC_HELP =
            "M11 18h2v-2h-2v2zm1-16C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8zm0-14c-2.21 0-4 1.79-4 4h2c0-1.1.9-2 2-2s2 .9 2 2c0 2-3 1.75-3 5h2c0-2.25 3-2.5 3-5 0-2.21-1.79-4-4-4z";

    /** A square, icon-only rail button. It is focusable and keyboard-activatable
     *  because the label it carries is only ever spoken, never shown. */
    private static Button railButton(String accessibleText, String svgPath) {
        Button railButton = button("", "rail-button");
        railButton.setGraphic(railIcon(svgPath));
        railButton.setAccessibleText(accessibleText);
        railButton.setFocusTraversable(true);
        railButton.setMinSize(RAIL_SIZE, RAIL_SIZE);
        railButton.setPrefSize(RAIL_SIZE, RAIL_SIZE);
        railButton.setMaxSize(RAIL_SIZE, RAIL_SIZE);
        return railButton;
    }

    /** A crisp monochrome vector icon that recolours with the rail state via CSS
     *  (.rail-button .rail-icon), the same technique the nav rail uses. */
    private static Region railIcon(String svg) {
        javafx.scene.shape.SVGPath path = new javafx.scene.shape.SVGPath();
        path.setContent(svg);
        Region icon = new Region();
        icon.setShape(path);
        icon.setMinSize(20, 20);
        icon.setPrefSize(20, 20);
        icon.setMaxSize(20, 20);
        icon.getStyleClass().add("rail-icon");
        return icon;
    }

    /** Deliveries icon plus the live count and a red dot when a vehicle has lost its fix.
     *  Both indicators stay visible whether the drawer is open or closed, which is
     *  exactly when a lost signal matters most. */
    private static StackPane deliveriesGlyph(Label countBadge, Region warningDot) {
        StackPane icon = new StackPane(railIcon(IC_DELIVERIES), countBadge, warningDot);
        StackPane.setAlignment(countBadge, Pos.CENTER_RIGHT);
        StackPane.setAlignment(warningDot, Pos.TOP_RIGHT);
        icon.setPickOnBounds(false);
        return icon;
    }

    /** The compact-window replacement for the four-icon rail: one menu button that
     *  carries the same count badge and NO FIX dot, plus one item per panel. The
     *  callback is attached after construction because the panel machinery it drives
     *  needs the button to exist first. */
    private static final class MenuPanelRail {
        private final MenuButton button;
        private final List<MenuItem> items = new ArrayList<>();
        private final List<Panel> panels = new ArrayList<>();
        private Consumer<Panel> onSelect = panel -> { };

        private MenuPanelRail(MenuButton button) { this.button = button; }

        static MenuPanelRail create(Label countBadge, Region warningDot) {
            MenuButton menu = new MenuButton();
            menu.getStyleClass().add("rail-button");
            menu.setAccessibleText("Map panels");
            menu.setTooltip(new Tooltip("Map panels"));
            menu.setMinSize(RAIL_SIZE, RAIL_SIZE);
            menu.setPrefSize(RAIL_SIZE, RAIL_SIZE);
            menu.setMaxSize(RAIL_SIZE, RAIL_SIZE);
            menu.setGraphic(deliveriesGlyph(countBadge, warningDot));
            MenuPanelRail rail = new MenuPanelRail(menu);
            for (Map.Entry<Panel, String> entry : Map.of(
                    Panel.DELIVERIES, "Deliveries",
                    Panel.LEGEND, "Legend",
                    Panel.LAYERS, "Layers and filters",
                    Panel.HELP, "Help").entrySet()) {
                MenuItem item = new MenuItem(entry.getValue());
                item.setOnAction(event -> rail.onSelect.accept(entry.getKey()));
                menu.getItems().add(item);
                rail.items.add(item);
                rail.panels.add(entry.getKey());
            }
            return rail;
        }

        MenuButton button() { return button; }

        void onSelect(Consumer<Panel> action) { this.onSelect = action; }

        /** Disables the item for the open panel, which reads as "this is already open". */
        void refresh(Panel open) {
            for (int i = 0; i < items.size(); i++) items.get(i).setDisable(open == panels.get(i));
        }
    }

    /** True when {@code node} is {@code ancestor} or sits somewhere below it. Used to tell
     *  a press on a rail button (which toggles) from a press on the map (which dismisses
     *  an open popover). */
    private static boolean within(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == ancestor) return true;
        }
        return false;
    }

    /** The Help popover: the map gestures that used to live in the page heading, plus
     *  the keyboard list. */
    private List<Node> helpContent() {
        Label title = new Label("Map help");
        title.getStyleClass().add("section-title");
        Label gestures = new Label(mapUsageHelpLine());
        gestures.getStyleClass().add("muted");
        VBox keys = new VBox(5);
        Label keyTitle = new Label("Keyboard");
        keyTitle.getStyleClass().add("eyebrow");
        keys.getChildren().add(keyTitle);
        shortcutHelpLines().forEach(line -> keys.getChildren().add(new Label(line)));
        return List.of(title, gestures, keys);
    }

    /** The Layers/Filters popover: one tick per vehicle, plus the basemap note.
     *
     *  <p>The ticks are the map's truth: {@code app.hiddenVehicles} is the same set
     *  {@code paintTracking} filters on, and the panel is re-rendered after every action
     *  so a checkbox can never show a state the map is not in. Ticking several vehicles
     *  shows several routes; unticking them all leaves a clean map.
     *
     *  <p>The panel rebuilds itself after every action (via {@link #popoverRefresh}), so
     *  a checkbox can never show a state the map is not in. */
    private List<Node> layersContent(List<Shipment> activeShipments) {
        Label title = new Label("Layers and filters");
        title.getStyleClass().add("section-title");

        List<String> vehicles = vehiclesOn(activeShipments);
        int total = vehicles.size();
        int shown = (int) vehicles.stream().filter(v -> !app.hiddenVehicles.contains(v)).count();
        // The scope line states exactly what is on the map, including the empty case:
        // unticking everything is a legitimate state, not an error to paper over.
        Label scope = new Label(scopeText(shown, total, app.focusedVehicleId));
        scope.getStyleClass().add("muted");
        scope.setMaxWidth(Double.MAX_VALUE);
        scope.setWrapText(true);
        Button showAll = button("Show all", "link-button");
        showAll.setDisable(total == 0);
        showAll.setOnAction(event -> {
            app.showAllVehicles();
            popoverRefresh.run();
        });
        HBox scopeRow = new HBox(8, scope, showAll);
        scopeRow.setAlignment(Pos.CENTER_LEFT);
        scopeRow.setPickOnBounds(false);

        Label vehiclesTitle = new Label("Vehicles");
        vehiclesTitle.getStyleClass().add("eyebrow");
        VBox toggles = new VBox(4);
        for (String vehicleId : vehicles) {
            CheckBox toggle = new CheckBox(app.vehiclePlate(vehicleId) + " · "
                    + (app.tracking.gpsStatus(vehicleId) == null ? "—" : app.tracking.gpsStatus(vehicleId)));
            toggle.setSelected(!app.hiddenVehicles.contains(vehicleId));
            toggle.setOnAction(event -> {
                app.setVehicleVisible(vehicleId, toggle.isSelected());
                popoverRefresh.run();
            });
            toggles.getChildren().add(toggle);
        }
        if (vehicles.isEmpty()) {
            Label none = new Label("No vehicles on an active delivery.");
            none.getStyleClass().add("muted");
            toggles.getChildren().add(none);
        }
        toggles.setPickOnBounds(false);
        Label options = new Label("Basemap: OpenStreetMap Standard");
        options.getStyleClass().add("muted");
        return List.of(title, scopeRow, vehiclesTitle, toggles, options);
    }

    /** What the Layers panel says the map is showing. Kept pure so the wording and the
     *  counts cannot drift apart, and so "no ticks" reads as a deliberate clean map. */
    static String scopeText(int shown, int total, String focusedVehicleId) {
        if (total == 0) return "No vehicles on an active delivery.";
        if (shown == 0) return "No vehicles shown — the map is showing layers only.";
        if (focusedVehicleId != null) return "Following one vehicle · untick to show more.";
        if (shown == total) return "Showing all " + total + " vehicle" + (total == 1 ? "" : "s") + ".";
        return "Showing " + shown + " of " + total + " vehicles.";
    }

    /** Distinct vehicles with an active delivery, in first-seen order. */
    private static List<String> vehiclesOn(List<Shipment> rows) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (rows != null) for (Shipment row : rows) {
            if (row.vehicleId() != null) ids.add(row.vehicleId());
        }
        return List.copyOf(ids);
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
            String xml = Files.readString(file.toPath());
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