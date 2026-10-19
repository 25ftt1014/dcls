package edu.pb.dcls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static edu.pb.dcls.Domain.OrderStatus;
import static edu.pb.dcls.Domain.Shipment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Active-deliveries panel contract. The panel replaced a user-draggable SplitPane,
 * so these lock the behaviour that was previously implicit: a fixed width that
 * layout cannot change, a live count and NO FIX indicator, and a usable filter.
 *
 * <p>Only the pure logic is covered. There is no FX toolkit under plain JUnit, so
 * the buttons and cell factories are verified by inspection.
 */
class TrackingPanelTest {

    private static Shipment shipment(String id, String address, OrderStatus status, String vehicleId) {
        return new Shipment(id, "Customer " + id, "8551234", address, "Parcel",
                1, 1.0, Domain.Priority.NORMAL, status, null, vehicleId, null,
                LocalDateTime.now(), null);
    }

@Nested
    @DisplayName("fixed width")
    class FixedWidth {
/** Columns that hold their width in TrackingView. Destination is not listed: it is
         *  the flexible one, taking whatever these leave. They must fit the content
         *  width *including* what the vertical scrollbar claims, because hitting the
         *  content width exactly is what made CONSTRAINED_RESIZE_POLICY shrink every
         *  column and ellipsis-clip the Status chips. */
        private final double columns = 72 + 68 + 76 + 96;

        /** What TableView's vertical scrollbar claims from the content width. */
        private final double scrollbar = 16;

        @Test
        void widthIsFixed() {
            assertEquals(480, TrackingView.PANEL_WIDTH);
    }

        @Test
    void columnsFitInsideThePanel() {
            double content = TrackingView.PANEL_WIDTH - UiFactory.PANEL_PADDING_H;
        assertTrue(columns <= content,
      "columns " + columns + " exceed panel content width " + content);
    }

    @Test
    void columnsLeaveRoomForTheScrollbar() {
            double content = TrackingView.PANEL_WIDTH - UiFactory.PANEL_PADDING_H;
            assertTrue(columns + scrollbar <= content,
       "columns " + columns + " plus a " + scrollbar
                        + "px scrollbar do not fit inside " + content);
        }

        @Test
    void destinationKeepsAUsableShare() {
            // The flexible column must still be wide enough to wrap a town and a street
            // rather than showing one word per line.
            double content = TrackingView.PANEL_WIDTH - UiFactory.PANEL_PADDING_H;
            double destination = content - columns - scrollbar;
            assertTrue(destination >= 100,
                    "Destination would be left with " + destination + "px");
        }

@Test
    void overlayBelowTheBreakpointAndPushAbove() {
        // The drawer never pushes the map any more: it is an overlay at every width.
        // What the breakpoint decides instead is whether the layout is compact — the
        // rail collapses into one menu button and the panels become bottom sheets.
        // Measured on the map container, not the stage, because the nav sidebar already
        // consumes ~200px.
        assertTrue(TrackingView.compactLayout(0));
        assertTrue(TrackingView.compactLayout(799));
        assertTrue(TrackingView.compactLayout(TrackingView.COMPACT_MIN_WIDTH - 0.1));
        assertFalse(TrackingView.compactLayout(TrackingView.COMPACT_MIN_WIDTH));
        assertFalse(TrackingView.compactLayout(1600));
    }

    @Test
    void closeIsQuickerThanOpen() {
        assertTrue(TrackingView.CLOSE_MS < TrackingView.OPEN_MS,
                "closing should feel snappier than opening");
    }

    @Test
    void reducedMotionCollapsesEveryDuration() {
        assertEquals(javafx.util.Duration.ZERO, TrackingView.motionDuration(true, 280));
        assertEquals(javafx.util.Duration.ZERO, TrackingView.motionDuration(true, 220));
        assertEquals(javafx.util.Duration.millis(280), TrackingView.motionDuration(false, 280));
    }

    @Test
    void panelPaddingIsNotTheTileInset() {
            // The canvas binding and the column budget both use the 16px-per-side panel
            // padding, never MapRenderer.MAP_PAD, which is the separate 14px inset the
            // projection maps tiles onto.
            assertEquals(32, UiFactory.PANEL_PADDING_H, 1e-9);
            assertFalse(MapRenderer.MAP_PAD * 2 == UiFactory.PANEL_PADDING_H,
                    "panel padding and tile inset must stay distinct values");
        }
    }

    @Nested
    @DisplayName("count badge")
    class Badge {
        @Test
        void countsActiveDeliveries() {
            List<Shipment> rows = List.of(
                    shipment("S-1", "Kiulap", OrderStatus.IN_TRANSIT, "v-1"),
                    shipment("S-2", "Gadong", OrderStatus.ASSIGNED, "v-2"),
                    shipment("S-3", "Batu Bersurat", OrderStatus.IN_TRANSIT, "v-3"));
            assertEquals(3, TrackingView.activeDeliveryBadge(rows));
        }

        @Test
        void zeroHidesTheBadge() {
            assertEquals(0, TrackingView.activeDeliveryBadge(List.of()));
            assertEquals(0, TrackingView.activeDeliveryBadge(null));
        }
    }

    @Nested
    @DisplayName("NO FIX indicator")
    class NoFix {
        private final List<Shipment> rows = List.of(
                shipment("S-1", "Kiulap", OrderStatus.IN_TRANSIT, "v-1"),
                shipment("S-2", "Gadong", OrderStatus.IN_TRANSIT, "v-2"));

        @Test
        void shownWhenAnyVehicleHasNoFix() {
            Map<String, String> status = Map.of("v-1", "GPS·±8m", "v-2", "NO FIX");
            assertTrue(TrackingView.hasNoFixWarning(rows, status::get));
        }

        @Test
        void hiddenWhenEveryVehicleHasAFix() {
            Map<String, String> status = Map.of("v-1", "GPS·±8m", "v-2", "LOST · 40s ago");
            assertFalse(TrackingView.hasNoFixWarning(rows, status::get),
                    "LOST is stale, not a lost signal — only NO FIX lights the indicator");
        }

        @Test
        void handlesRowsWithoutVehicles() {
            List<Shipment> noVehicle = List.of(shipment("S-9", "Somewhere", OrderStatus.ASSIGNED, null));
            assertFalse(TrackingView.hasNoFixWarning(noVehicle, id -> "NO FIX"));
            assertFalse(TrackingView.hasNoFixWarning(List.of(), id -> "NO FIX"));
            assertFalse(TrackingView.hasNoFixWarning(null, id -> "NO FIX"));
        }
    }

    @Nested
    @DisplayName("show / hide toggle")
    class Toggle {
        @Test
        void offersTheOppositeOfTheCurrentState() {
            // The toggle is an icon button, so its accessible name is the only label it
            // has and must always offer the opposite of the current state.
            assertEquals("Hide active deliveries", TrackingView.panelToggleAccessibleText(false));
            assertEquals("Show active deliveries", TrackingView.panelToggleAccessibleText(true));
        }

        @Test
        void startsVisibleAndOffersToHide() {
            DclsApp app = new DclsApp();
            assertFalse(app.trackingPanelCollapsed, "panel starts shown");
            assertEquals("Hide active deliveries",
                    TrackingView.panelToggleAccessibleText(app.trackingPanelCollapsed));
        }
    }

    @Nested
    @DisplayName("panel filter")
    class Filter {
        private final Shipment row = shipment("S-1042", "Kiulap, Brunei", OrderStatus.IN_TRANSIT, "v-1");

        @Test
        void blankMatchesEverything() {
            assertTrue(TrackingView.matchesFilter(row, null, "BKL 1934"));
            assertTrue(TrackingView.matchesFilter(row, "", "BKL 1934"));
            assertTrue(TrackingView.matchesFilter(row, "   ", "BKL 1934"));
        }

        @Test
        void matchesIdPlateAddressAndStatus() {
            assertTrue(TrackingView.matchesFilter(row, "1042", "BKL 1934"));
            assertTrue(TrackingView.matchesFilter(row, "bkl", "BKL 1934"));
            assertTrue(TrackingView.matchesFilter(row, "kiulap", "BKL 1934"));
            assertTrue(TrackingView.matchesFilter(row, "transit", "BKL 1934"));
        }

        @Test
   void isCaseInsensitiveAndTrims() {
  assertTrue(TrackingView.matchesFilter(row, "  KIULAP  ", "BKL 1934"));
          assertFalse(TrackingView.matchesFilter(row, "nonexistent", "BKL 1934"));
        }
    }

    @Nested
    @DisplayName("lost-signal ordering")
    class Ordering {
        private final Shipment lost = shipment("S-2", "Kiulap", OrderStatus.IN_TRANSIT, "v-lost");
        private final Shipment healthy = shipment("S-1", "Jerudong", OrderStatus.ASSIGNED, "v-ok");
        private final Shipment unassigned = shipment("S-3", "Bukit", OrderStatus.ASSIGNED, null);

        private java.util.function.Function<String, String> status(String... lostIds) {
            java.util.Set<String> lostSet = java.util.Set.of(lostIds);
            return id -> lostSet.contains(id) ? "NO FIX 40s" : "LIVE 2s";
        }

        @Test
        void putsLostFixesFirst() {
            List<Shipment> sorted = TrackingView.lostFixFirst(
                    List.of(healthy, lost, unassigned), status("v-lost"));
            assertEquals(List.of(lost, healthy, unassigned), sorted);
        }

        @Test
        void treatsLostSignalsAsLostToo() {
            List<Shipment> sorted = TrackingView.lostFixFirst(
                    List.of(healthy, lost), id -> id.equals("v-lost") ? "LOST 90s" : "LIVE 2s");
            assertEquals(lost, sorted.get(0));
        }

        @Test
        void keepsCallerOrderWhenEveryRowIsHealthy() {
            assertEquals(List.of(healthy, lost, unassigned),
                    TrackingView.lostFixFirst(List.of(healthy, lost, unassigned), status()));
        }

        @Test
        void handlesEmptyAndMissingInput() {
            assertTrue(TrackingView.lostFixFirst(null, status()).isEmpty());
            assertTrue(TrackingView.lostFixFirst(List.of(), status()).isEmpty());
            assertEquals(2, TrackingView.lostFixFirst(List.of(healthy, lost), null).size());
        }
    }

    @Nested
    @DisplayName("map and shortcut announcements")
    class Announcements {
        @Test
        void narratesTheMapForScreenReaders() {
            assertEquals("Fleet map. 3 vehicles shown. 3 have no live fix. "
                            + "Use arrow keys to pan, plus and minus to zoom, R to reset.",
                    TrackingView.mapAccessibleSummary(3, 3));
        }

        @Test
        void usesSingularsForOne() {
            assertTrue(TrackingView.mapAccessibleSummary(1, 1).contains("1 vehicle shown."));
            assertTrue(TrackingView.mapAccessibleSummary(1, 1).contains("1 has no live fix."));
        }

        @Test
        void staysQuietWhenEveryFixIsGood() {
            assertFalse(TrackingView.mapAccessibleSummary(2, 0).contains("no live fix"));
        }

        @Test
        void documentsTheBoundShortcuts() {
            List<String> lines = TrackingView.shortcutHelpLines();
            assertFalse(lines.isEmpty());
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("Arrow keys")));
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("R ")));
            assertTrue(lines.stream().anyMatch(line -> line.startsWith("Esc")));
        }
    }
}
