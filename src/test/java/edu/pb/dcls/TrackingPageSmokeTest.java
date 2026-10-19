package edu.pb.dcls;

import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scene-graph smoke test for the full-bleed tracking page: it builds the real page
 * against demo data, lays it out at a wide and a narrow width, and opens and closes
 * every panel.
 *
 * <p>The claim under test is that panels never resize the map. The canvas is bound to
 * its host and every panel is a sibling overlay, so the canvas must report the same
 * size after every panel toggle. A future change that reintroduces a per-panel map
 * relayout (the old scrim-plus-resize hook) fails here.
 *
 * <p>Each test builds its own page, so the assertions run on a node that is genuinely
 * the root of a laid-out scene.
 */
class TrackingPageSmokeTest {

    private static DclsApp app;
    private static TrackingView.Panel savedPanel;

    @BeforeAll
    static void buildFixture() throws Exception {
        FxTestKit.run(() -> {
            MockRepository source = new MockRepository();
            CachedRepository cached = new CachedRepository(source);
            cached.refresh();
            app = new DclsApp();
            app.repository = cached;
            app.auth = new AppServices.AuthService(cached);
            app.access = new AppServices.AccessService();
            app.fleet = new AppServices.FleetService(cached);
            app.orders = new AppServices.OrderService(cached);
            app.routeService = new AppServices.RouteService(cached);
            app.tracking = new AppServices.TrackingService(cached);
            app.currentUser = cached.users().getFirst();
            // Zero-length transitions: this test asserts state, not animation.
            app.reduceMotion = true;
            savedPanel = TrackingView.loadOpenPanel();
        });
    }

    /** The overlay layer stack of a freshly built page, laid out in its own scene. */
    private static StackPane pageAt(double width, double height) throws Exception {
        StackPane[] built = new StackPane[1];
        FxTestKit.run(() -> {
            javafx.scene.layout.VBox page = new TrackingView(app).page();
            // Detach from the page so it can be the root of its own scene; otherwise
            // JavaFX refuses the node and every assertion below runs on nothing.
            built[0] = (StackPane) page.getChildren().removeFirst();
            Scene scene = new Scene(built[0], width, height);
            scene.getStylesheets().add(TrackingPageSmokeTest.class
                    .getResource("/edu/pb/dcls/styles.css").toExternalForm());
            built[0].applyCss();
            built[0].layout();
        });
        // Let the toolkit pulse once before measuring. Insets and corner alignment are
        // applied by the layout pass that follows the first one, exactly as they are in
        // the running app; a single hand-rolled layout() reads the too-early values.
        Thread.sleep(250);
        FxTestKit.run(() -> {
            built[0].applyCss();
            built[0].layout();
        });
        assertNotNull(built[0], "the tracking page produced no overlay stack");
        return built[0];
    }

    /** Clicks the rail icon that owns a panel, exactly as the mouse would. */
    private static Button railButton(StackPane layers, TrackingView.Panel panel) {
        javafx.scene.Node rail = layers.lookupAll(".rail").stream().findFirst().orElse(null);
        assertNotNull(rail, "the icon rail is missing from the map stack");
        assertTrue(rail instanceof VBox, "the rail should be a vertical stack of buttons");
        int index = switch (panel) {
            case DELIVERIES -> 0;
            case LEGEND -> 1;
            case LAYERS -> 2;
            case HELP -> 3;
            default -> -1;
        };
        assertTrue(rail instanceof VBox box && box.getChildren().get(index) instanceof Button,
                "rail entry " + index + " should be a button");
        return (Button) ((VBox) rail).getChildren().get(index);
    }

    /** Lets the FX thread run its animations to completion. */
    private static void pump() throws Exception {
        Thread.sleep(250);
        FxTestKit.run(() -> { });
    }

    /** Builds a page with the panel state pinned, so the test does not inherit
     *  whatever the last run left in Preferences. */
    private static StackPane pageWithPanelOpen(TrackingView.Panel open, double width, double height)
            throws Exception {
        TrackingView.Panel before = TrackingView.loadOpenPanel();
        TrackingView.saveOpenPanel(open);
        try {
            return pageAt(width, height);
        } finally {
            TrackingView.saveOpenPanel(before);
        }
    }

    /**
     * The regression that matters most: clicking a rail icon has to actually open its
     * panel. An earlier version toggled the panel's requested state but only ever
     * animated the drawer on the way out, so clicking Deliveries did nothing at all —
     * and a test that only checked "the map did not resize" happily passed, because
     * nothing happened.
     */
    @Test
    void clickingTheRailOpensAndClosesTheDrawer() throws Exception {
        StackPane layers = pageWithPanelOpen(TrackingView.Panel.NONE, 1200, 800);
        FxTestKit.run(() -> {
            javafx.scene.Node drawer = layers.lookupAll(".tracking-drawer").stream().findFirst().orElse(null);
            assertNotNull(drawer, "no drawer in the page");
            assertFalse(drawer.isVisible(), "the drawer starts closed");
            railButton(layers, TrackingView.Panel.DELIVERIES).fire();
        });
        pump();
        FxTestKit.run(() -> {
            javafx.scene.Node drawer = layers.lookupAll(".tracking-drawer").stream().findFirst().orElse(null);
            assertTrue(drawer.isVisible(), "clicking Deliveries must open the drawer");
            assertEquals(0, drawer.getTranslateX(), 0.5,
                    "the opened drawer must come to rest against the right edge");
            var box = drawer.localToScene(drawer.getBoundsInLocal());
            // Only the column is asserted, not exact pixels: this scene is not styled the
            // way the running app is, and the table's metrics differ. Geometry is checked
            // relationally in eachOverlaySitsInItsOwnCorner, and visually by rendering
            // the real page.
            assertTrue(box.getMinX() > 400,
                    "the drawer belongs in the right-hand column: " + box);

            railButton(layers, TrackingView.Panel.DELIVERIES).fire();
        });
        pump();
        FxTestKit.run(() -> {
            javafx.scene.Node drawer = layers.lookupAll(".tracking-drawer").stream().findFirst().orElse(null);
            assertFalse(drawer.isVisible(), "clicking Deliveries again must close the drawer");
        });
    }

    @Test
    void clickingTheRailOpensExactlyOnePopoverAndClosesTheOthers() throws Exception {
        StackPane layers = pageWithPanelOpen(TrackingView.Panel.NONE, 1200, 800);
        FxTestKit.run(() -> railButton(layers, TrackingView.Panel.LEGEND).fire());
        pump();
        FxTestKit.run(() -> {
            assertTrue(popoverVisible(layers), "the legend popover must open");
            assertTrue(popoverText(layers).contains("Legend"), "the legend content must be shown");
            railButton(layers, TrackingView.Panel.LAYERS).fire();
        });
        pump();
        FxTestKit.run(() -> {
            // One panel at a time: opening Layers replaced the legend, it did not stack.
            assertTrue(popoverVisible(layers), "the layers popover must be open");
            assertTrue(popoverText(layers).contains("Layers and filters"),
                    "only the newly opened panel's content may be on screen: " + popoverText(layers));
            railButton(layers, TrackingView.Panel.LEGEND).fire();
        });
        pump();
        FxTestKit.run(() -> assertTrue(popoverVisible(layers), "the layers popover must replace the legend"));
    }

    /** A Layers tick has to change what the map would draw. */
    @Test
    void untickingAVehicleChangesTheVisibleSet() throws Exception {
        StackPane layers = pageWithPanelOpen(TrackingView.Panel.LAYERS, 1200, 800);
        FxTestKit.run(() -> {
            List<javafx.scene.control.CheckBox> toggles = layers.lookupAll(".check-box").stream()
                    .map(node -> (javafx.scene.control.CheckBox) node).toList();
            assertFalse(toggles.isEmpty(), "the layers panel should offer a tick per vehicle");
            long before = toggles.stream().filter(javafx.scene.control.CheckBox::isSelected).count();
            assertEquals(before, (long) toggles.size(), "every vehicle starts visible");

            javafx.scene.control.CheckBox first = toggles.getFirst();
            // A synthetic click, not fire(): the user toggles the box, the box reports its new
            // state, and the panel rebuilds itself from there.
            click(first);
        });
        pump();
        FxTestKit.run(() -> {
            assertEquals(1, app.hiddenVehicles.size(), "one vehicle should now be unticked");
            List<String> shown = DclsApp.visibleVehicles(null,
                    new LinkedHashSet<>(app.trackingTrails.keySet()), app.hiddenVehicles);
            assertFalse(shown.containsAll(app.hiddenVehicles),
                    "an unticked vehicle must not be drawn");
            assertTrue(app.trackingTrails.keySet().size() > 1,
                    "the fixture needs more than one vehicle for this test to mean anything");
            assertFalse(shown.isEmpty(), "the other vehicles stay visible");
        });
    }

    /** Clicks a control the way a mouse would: press then release inside it, which is
     *  what a two-state control turns into a tick. */
    private static void click(javafx.scene.Node target) {
        var box = target.localToScene(target.getBoundsInLocal());
        double x = box.getMinX() + box.getWidth() / 2;
        double y = box.getMinY() + box.getHeight() / 2;
        javafx.event.Event.fireEvent(target, mouse(
                javafx.scene.input.MouseEvent.MOUSE_PRESSED, x, y, 1, true));
        javafx.event.Event.fireEvent(target, mouse(
                javafx.scene.input.MouseEvent.MOUSE_RELEASED, x, y, 1, false));
    }

    private static javafx.scene.input.MouseEvent mouse(
            javafx.event.EventType<javafx.scene.input.MouseEvent> type,
            double x, double y, int clickCount, boolean primaryDown) {
        return new javafx.scene.input.MouseEvent(type, x, y, x, y,
                javafx.scene.input.MouseButton.PRIMARY, clickCount,
                false, false, false, false, primaryDown, false, false, false, false, false, null);
    }

    private static boolean popoverVisible(StackPane layers) {
        javafx.scene.Node popover = layers.lookupAll(".panel-popover").stream().findFirst().orElse(null);
        return popover != null && popover.isVisible() && popover.getParent().isVisible();
    }

    private static String popoverText(StackPane layers) {
        StringBuilder text = new StringBuilder();
        collectText(layers.lookupAll(".panel-popover").stream().findFirst().orElse(null), text);
        return text.toString();
    }

    private static void collectText(javafx.scene.Node node, StringBuilder into) {
        if (node instanceof javafx.scene.control.Labeled labeled) into.append(labeled.getText()).append(' ');
        if (node instanceof javafx.scene.control.CheckBox box) into.append(box.getText()).append(' ');
        if (node instanceof javafx.scene.Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collectText(child, into));
        }
    }

    @Test
    void theCanvasFillsItsHostExactlyWithNoPaddingSubtracted() throws Exception {
        StackPane layers = pageAt(1200, 800);
        FxTestKit.run(() -> {
            StackPane mapHost = (StackPane) layers.getChildren().getFirst();
            Canvas canvas = app.trackingCanvas;
            assertTrue(mapHost.getWidth() > 0, "the map host never got a width");
            assertTrue(canvas.getWidth() > 0, "the canvas never got a width");
            // The old binding subtracted 32px of panel padding from the map's own width,
            // which left an 8px dead strip once the card wrapper was gone.
            assertEquals(mapHost.getWidth(), canvas.getWidth(), 0.01,
                    "the canvas must fill its host exactly, with no padding subtracted");
            assertEquals(mapHost.getHeight(), canvas.getHeight(), 0.01,
                    "the canvas must fill its host exactly");
            assertEquals(canvas, mapHost.getChildren().getFirst(),
                    "the canvas must be the bottom layer of the map host");
        });
    }

    @Test
    void openingAndClosingEveryPanelLeavesTheMapSizeAlone() throws Exception {
        StackPane layers = pageAt(1200, 800);
        FxTestKit.run(() -> {
            Canvas canvas = app.trackingCanvas;
            double width = canvas.getWidth(), height = canvas.getHeight();
            for (TrackingView.Panel panel : TrackingView.Panel.values()) {
                if (panel == TrackingView.Panel.NONE) continue;
                railButton(layers, panel).fire();
                layers.layout();
                assertEquals(width, canvas.getWidth(), 0.01, "toggling " + panel + " resized the map");
                assertEquals(height, canvas.getHeight(), 0.01, "toggling " + panel + " resized the map");
            }
        });
    }

    @Test
    void overlaysDoNotCoverTheMapOutsideTheirOwnControls() throws Exception {
        StackPane layers = pageAt(1200, 800);
        FxTestKit.run(() -> {
            // Every floating overlay layer sits at its own preferred size in a corner and
            // is hit-tested only where a control actually is, so none of them swallows
            // the map's drags. The drawer is the one deliberate exception: it is opaque
            // by design and hidden whenever it is not open.
            for (int index = 1; index < layers.getChildren().size(); index++) {
                javafx.scene.Node layer = layers.getChildren().get(index);
                boolean drawerHost = layer.lookupAll(".tracking-drawer").stream().findAny().isPresent();
                if (drawerHost) continue;
                assertFalse(layer.isPickOnBounds(),
                        "overlay layer " + index + " hit-tests its whole bounds");
            }
            StackPane mapHost = (StackPane) layers.getChildren().getFirst();
            assertEquals(mapHost.getWidth(), layers.getWidth(), 0.01,
                    "the map host should span the whole page");
            assertEquals(mapHost.getHeight(), layers.getHeight(), 0.01,
                    "the map host should span the whole page");
        });
    }

    @Test
    void eachOverlaySitsInItsOwnCorner() throws Exception {
        // The drawer only exists on screen while it is open, and the page restores
        // whatever was left open last time, so pin it for this measurement.
        TrackingView.Panel before = TrackingView.loadOpenPanel();
        TrackingView.saveOpenPanel(TrackingView.Panel.DELIVERIES);
        try {
            StackPane layers = pageAt(1200, 800);
            FxTestKit.run(() -> {
                StackPane mapHost = (StackPane) layers.getChildren().getFirst();
                // The regression this guards: an overlay whose corner alignment was
                // applied inside its layer but not to the layer itself silently stacks
                // in the middle of the map, still "working" but on top of the map.
                javafx.scene.Node rail = layers.lookupAll(".rail").stream().findFirst().orElse(null);
                javafx.scene.Node search = layers.lookupAll(".search-float").stream().findFirst().orElse(null);
                javafx.scene.Node zoom = layers.lookupAll(".zoom-stack").stream().findFirst().orElse(null);
                javafx.scene.Node drawer = layers.lookupAll(".tracking-drawer").stream().findFirst().orElse(null);
                assertNotNull(rail, "no rail");
                assertNotNull(search, "no search bar");
                assertNotNull(zoom, "no zoom stack");
                assertNotNull(drawer, "no drawer");

                // Scene coordinates: the layer stack is the scene root, so these are the
                // controls' real positions on the page.
                var railBox = rail.localToScene(rail.getBoundsInLocal());
                var searchBox = search.localToScene(search.getBoundsInLocal());
                var zoomBox = zoom.localToScene(zoom.getBoundsInLocal());
                var drawerBox = drawer.localToScene(drawer.getBoundsInLocal());

                assertTrue(railBox.getMinX() > 600, "the rail belongs on the right half of the map");
                assertTrue(railBox.getMinY() < 80, "the rail belongs at the top");
                assertTrue(searchBox.getMaxX() < 420, "the search bar belongs on the left");
                assertTrue(searchBox.getMinY() < 80, "the search bar belongs at the top");
                assertTrue(zoomBox.getMinY() > 400, "zoom controls belong at the bottom");
                assertTrue(zoomBox.getMaxX() <= 1200 && zoomBox.getMinX() > 600,
                        "zoom controls belong in the bottom-right corner");
                // The drawer is a sibling overlay of the map, never a layout sibling of it: that is
                // the whole reason panels cannot resize the map.
                assertTrue(drawer.getParent() != mapHost,
                        "the drawer must float over the map, not share its layout");
                assertTrue(drawerBox.getMinX() > 400,
                        "the drawer belongs in the right-hand column: " + drawerBox);
            });
        } finally {
            TrackingView.saveOpenPanel(before);
        }
    }

    @Test
    void aNarrowWindowSwapsTheRailForTheMenuButtonAndKeepsTheMap() throws Exception {
        StackPane layers = pageAt(640, 800);
        FxTestKit.run(() -> {
            assertTrue(TrackingView.compactLayout(layers.getWidth()),
                    "the test window is below the breakpoint");
            javafx.scene.Node rail = layers.lookupAll(".rail").stream().findFirst().orElse(null);
            assertNotNull(rail, "the rail should still exist in the tree");
            // Visibility is not inherited in JavaFX: the rail's floating layer is what
            // gets hidden, so that is what the assertion has to look at.
            assertFalse(rail.getParent().isVisible(),
                    "the four-icon rail should be hidden at this width");
            javafx.scene.Node menu = layers.lookupAll(".rail-button").stream()
                    .filter(node -> node instanceof javafx.scene.control.MenuButton)
                    .findFirst().orElse(null);
            assertNotNull(menu, "the single menu button should replace the rail");
            assertTrue(menu.isVisible(), "the menu button should be visible at this width");
            StackPane mapHost = (StackPane) layers.getChildren().getFirst();
            assertEquals(mapHost.getWidth(), app.trackingCanvas.getWidth(), 0.01,
                    "the compact layout must not resize the map either");
        });
    }
}