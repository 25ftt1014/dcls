package edu.pb.dcls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static edu.pb.dcls.TrackingView.Panel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full-bleed map page contract: the map owns the page and everything else floats on
 * top of it.
 *
 * <p>Only the pure geometry and state rules are covered. There is no FX toolkit under
 * plain JUnit, so the scene-graph wiring itself is verified by inspection and by
 * running the app.
 */
class TrackingLayoutTest {

    @Nested
    @DisplayName("one panel at a time")
    class Panels {
        @Test
        void clickingTheOpenPanelClosesIt() {
            assertEquals(Panel.NONE, TrackingView.togglePanel(Panel.LEGEND, Panel.LEGEND));
            assertEquals(Panel.NONE, TrackingView.togglePanel(Panel.DELIVERIES, Panel.DELIVERIES));
        }

        @Test
        void clickingAnotherPanelReplacesTheOpenOne() {
            // This single rule is what guarantees two panels can never share the map.
            assertEquals(Panel.LAYERS, TrackingView.togglePanel(Panel.LEGEND, Panel.LAYERS));
            assertEquals(Panel.DELIVERIES, TrackingView.togglePanel(Panel.HELP, Panel.DELIVERIES));
        }

        @Test
        void nothingIsOpenByDefault() {
            assertEquals(Panel.NONE, TrackingView.togglePanel(Panel.NONE, Panel.NONE));
            assertEquals(Panel.HELP, TrackingView.togglePanel(Panel.NONE, Panel.HELP));
        }

        @Test
        void aMissingTargetCloses() {
            assertEquals(Panel.NONE, TrackingView.togglePanel(Panel.HELP, null));
            assertEquals(Panel.NONE, TrackingView.togglePanel(Panel.HELP, Panel.NONE));
        }
    }

    @Nested
    @DisplayName("outside click")
    class OutsideClick {
        private final double x = 100, y = 100, w = 240, h = 180;

        @Test
        void aPressInsideThePanelIsNotAnOutsideClick() {
            assertFalse(TrackingView.pressOutsidePanel(150, 150, x, y, w, h));
        }

        @Test
        void aPressOnTheEdgeCountsAsInside() {
            assertFalse(TrackingView.pressOutsidePanel(x, y, x, y, w, h));
            assertFalse(TrackingView.pressOutsidePanel(x + w, y + h, x, y, w, h));
        }

        @Test
        void aPressBeyondAnyEdgeIsOutside() {
            assertTrue(TrackingView.pressOutsidePanel(x - 1, 150, x, y, w, h));
            assertTrue(TrackingView.pressOutsidePanel(x + w + 1, 150, x, y, w, h));
            assertTrue(TrackingView.pressOutsidePanel(150, y - 1, x, y, w, h));
            assertTrue(TrackingView.pressOutsidePanel(150, y + h + 1, x, y, w, h));
        }
    }

    @Nested
    @DisplayName("tour card")
    class TourCard {
        @Test
        void readsAsPlateStopsAndDistance() {
            assertEquals("Tour BQ 8817 · 1 stops · 12.1 km left",
                    TrackingView.tourCardText("BQ 8817", 1, 12.1));
        }

        @Test
        void keepsOneDecimalPlaceWhateverTheDistance() {
            assertEquals("Tour BKL 1934 · 4 stops · 0.0 km left",
                    TrackingView.tourCardText("BKL 1934", 4, 0));
        }

        @Test
        void fallsBackToThePlateAlone() {
            assertEquals("Tour BQ 8817", TrackingView.tourCardText("BQ 8817"));
            assertEquals("Tour —", TrackingView.tourCardText(null));
        }

        @Test
        void neverPrintsTheLongId() {
            // The vehicle id is a UUID: it belongs in the tooltip and the copy action,
            // never on the card itself.
            String card = TrackingView.tourCardText("BQ 8817", 3, 4.25);
            assertFalse(card.matches(".*[0-9a-f]{8}-[0-9a-f]{4}.*"), card);
        }
    }

    @Nested
    @DisplayName("floating controls clear the map's own overlays")
    class Clearances {
        @Test
        void theTourCardSitsAboveTheScaleBar() {
            // MapRenderer.drawScaleBar paints MAP_PAD in from the bottom-left corner.
            // The tour card shares that corner, so it has to start below that band.
            assertTrue(TrackingView.TOUR_CARD_INSET > MapRenderer.MAP_PAD + 20,
                    "tour card bottom inset " + TrackingView.TOUR_CARD_INSET
                            + " overlaps the scale bar drawn at " + MapRenderer.MAP_PAD);
        }

        @Test
        void theZoomStackSitsAboveTheAttribution() {
            // prepareMap writes the OpenStreetMap credit along the bottom-right edge.
            assertTrue(TrackingView.ZOOM_INSET > 8,
                    "zoom stack bottom inset " + TrackingView.ZOOM_INSET
                            + " would cover the attribution line");
        }
    }

    @Nested
    @DisplayName("map help")
    class Help {
        @Test
        void carriesTheMouseGesturesRemovedFromThePageHeading() {
            assertEquals("Scroll to zoom, drag to pan", TrackingView.mapUsageHelpLine());
        }

        @Test
        void documentsEscAndThePanelShortcuts() {
            assertTrue(TrackingView.shortcutHelpLines().stream().anyMatch(line -> line.startsWith("Esc")));
        }
    }

    @Nested
    @DisplayName("layers scope line")
    class Scope {
        @Test
        void countsWhatIsActuallyShown() {
            assertEquals("Showing all 3 vehicles.", TrackingView.scopeText(3, 3, null));
            assertEquals("Showing 2 of 3 vehicles.", TrackingView.scopeText(2, 3, null));
            assertEquals("Showing all 1 vehicle.", TrackingView.scopeText(1, 1, null));
        }

        @Test
        void noTicksIsACleanMapNotAnError() {
            assertEquals("No vehicles shown — the map is showing layers only.",
                    TrackingView.scopeText(0, 3, null));
        }

        @Test
        void anEmptyFleetSaysSo() {
            assertEquals("No vehicles on an active delivery.", TrackingView.scopeText(0, 0, null));
        }

        @Test
        void soloFocusExplainsItself() {
            assertEquals("Following one vehicle · untick to show more.",
                    TrackingView.scopeText(1, 3, "v-b"));
        }
    }

    @Nested
    @DisplayName("search prompt encoding")
    class Encoding {
        @Test
        void thePromptIsARealEllipsisNotMojibake() {
            // Regression guard for the "â€¦" bug: a source file decoded with anything
            // but UTF-8 turns U+2026 into three Latin-1 characters. The placeholder must
            // never contain them, so the build has to keep reading sources as UTF-8.
            String prompt = TrackingView.searchPrompt();
            assertEquals("Search place in Brunei…", prompt);
            assertTrue(prompt.indexOf('\u2026') >= 0, "prompt must use U+2026");
            assertFalse(prompt.contains("\u00e2"), "prompt must not contain a mojibake sequence");
        }
    }
}