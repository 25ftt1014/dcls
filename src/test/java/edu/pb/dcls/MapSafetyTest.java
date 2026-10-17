package edu.pb.dcls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline lock for map safety guards: invalid points never break framing or markers. */
class MapSafetyTest {

    @Nested
    @DisplayName("fitBounds")
    class Fit {
        @Test
        void ignoresNonFinitePoints() {
            var bounds = MapRenderer.fitBounds(List.of(
                    new double[] {Double.NaN, Double.NaN},
                    new double[] {114.94, 4.89},
                    new double[] {Double.POSITIVE_INFINITY, 4.89}), 1.3);
            assertTrue(bounds.lonMin() < 114.94 && bounds.lonMax() > 114.94);
            assertTrue(bounds.latMin() < 4.89 && bounds.latMax() > 4.89);
        }

        @Test
        void allInvalidFallsBackToFullMap() {
            var bounds = MapRenderer.fitBounds(List.of(
                    new double[] {Double.NaN, Double.NaN}), 1.3);
            assertEquals(MapRenderer.FULL_MAP, bounds);
        }
    }

    @Nested
    @DisplayName("basemap")
    class BasemapOnly {
        @Test
        void tileUrlsAreOsmStandard() {
            assertEquals("https://a.tile.openstreetmap.org/14/12950/8260.png",
                    MapRenderer.tileUrl(14, 12950, 8260));
        }

        @Test
        void osmUrlShape() {
            String url = MapRenderer.osmUrl(14, 12950, 8260, 0);
            assertTrue(url.startsWith("https://"));
            assertTrue(url.endsWith(".png"));
        }

        @Test
        void scopeIsTheOsmCacheDir() {
            assertEquals("osm-v2-keyless-v2", MapRenderer.currentScopeName());
        }

        @Test
        void removedSatelliteScopeIsNeverCurrent() {
            assertNotEquals("esri-v1", MapRenderer.currentScopeName());
        }
    }

    @Nested
    @DisplayName("osm subdomains")
    class OsmSubdomains {
        @Test
        void adjacentTilesSpreadAcrossHosts() {
            // Pacing is per host, so consecutive tiles must actually reach every host
            // or the parallel fetches would serialize on one subdomain again.
            int first = MapRenderer.osmHostIndex(100, 200, 0);
            int second = MapRenderer.osmHostIndex(101, 200, 0);
            int third = MapRenderer.osmHostIndex(102, 200, 0);
            assertEquals(3, java.util.Set.of(first, second, third).size(),
                    "three consecutive tiles must hit all three subdomains");
            assertEquals(0, MapRenderer.osmHostIndex(100, 200, 0));
        }

        @Test
        void hostShiftMovesToTheNextHost() {
            assertEquals(1, MapRenderer.osmHostIndex(100, 200, 1));
        }

        @Test
        void hostIndexNeverGoesNegative() {
            // x+y can be negative near the antimeridian; floorMod must keep it in range.
            for (int shift = -4; shift <= 4; shift++) {
                int index = MapRenderer.osmHostIndex(-5, -5, shift);
                assertTrue(index >= 0 && index < 3, "out of range host index " + index);
            }
        }
    }

    @Nested
    @DisplayName("zoom level stability")
    class ZoomStability {
        @Test
        void holdsCurrentLevelWithinHysteresisBand() {
            // A gesture that hovers on a threshold must not flip-flop: holding the
            // level keeps the already-cached tiles instead of re-fetching the view.
            assertEquals(13, MapRenderer.stableFetchZoom(12, 13, 3, 20, MapRenderer.ZOOM_HYSTERESIS));
            assertEquals(13, MapRenderer.stableFetchZoom(14, 13, 3, 20, MapRenderer.ZOOM_HYSTERESIS));
            assertEquals(13, MapRenderer.stableFetchZoom(13, 13, 3, 20, MapRenderer.ZOOM_HYSTERESIS));
        }

        @Test
        void switchesWhenClearlyDifferent() {
            // Outside the band the ideal wins outright, so real zoom moves still track.
            assertEquals(16, MapRenderer.stableFetchZoom(16, 13, 3, 20, MapRenderer.ZOOM_HYSTERESIS));
            assertEquals(10, MapRenderer.stableFetchZoom(10, 13, 3, 20, MapRenderer.ZOOM_HYSTERESIS));
        }

        @Test
        void adoptsIdealWhenNoLevelYet() {
            assertEquals(14, MapRenderer.stableFetchZoom(14, -1, 3, 20, MapRenderer.ZOOM_HYSTERESIS));
            assertEquals(14, MapRenderer.stableFetchZoom(14, 99, 3, 20, MapRenderer.ZOOM_HYSTERESIS));
        }

        @Test
        void neverLeavesTheLegalRange() {
            assertEquals(20, MapRenderer.stableFetchZoom(99, 13, 3, 20, MapRenderer.ZOOM_HYSTERESIS));
            assertEquals(3, MapRenderer.stableFetchZoom(-5, 13, 3, 20, MapRenderer.ZOOM_HYSTERESIS));
        }

        @Test
        void smallMapDoesNotPinTheWideOneToItsCoarseLevel() {
            // Regression: the sticky level used to be one static shared by every map,
            // so the 430x210 dashboard settled on z11 and then held the route-planning
            // map (ideal z12) down at z11 — it opened visibly blocky.
            javafx.scene.canvas.Canvas small = new javafx.scene.canvas.Canvas(430, 210);
            javafx.scene.canvas.Canvas wide = new javafx.scene.canvas.Canvas(750, 480);
            int dashboard = MapRenderer.tileZoomFor(small, MapRenderer.FULL_MAP, 430 - MapRenderer.MAP_PAD * 2);
            int routes = MapRenderer.tileZoomFor(wide, MapRenderer.FULL_MAP, 750 - MapRenderer.MAP_PAD * 2);
            assertEquals(11, dashboard, "small dashboard map should sit at z11");
            assertEquals(12, routes, "route map must not inherit the dashboard's level");
        }

        @Test
        void eachCanvasTracksItsOwnLevelAcrossFrames() {
            javafx.scene.canvas.Canvas canvas = new javafx.scene.canvas.Canvas(750, 480);
            double px = 750 - MapRenderer.MAP_PAD * 2;
            int first = MapRenderer.tileZoomFor(canvas, MapRenderer.FULL_MAP, px);
            assertEquals(first, MapRenderer.tileZoomFor(canvas, MapRenderer.FULL_MAP, px),
                    "a steady view must not wobble between frames");
        }
    }

    @Nested
    @DisplayName("fetch zoom")
    class FetchZoom {
        @Test
        void overzoomsWhenFitsBudget() {
            // Narrow view: deeper tiles fit the budget -> one level deeper for crisper imagery.
            var view = new MapRenderer.ViewBounds(114.925, 114.928, 4.905, 4.908);
            int display = MapRenderer.zoomFor(0.003, 500);
            assertEquals(display + 1, MapRenderer.fetchZoom(display, view));
        }

        @Test
        void wideViewStaysAtDisplayZoom() {
            // Whole country at z14: deeper tiles exceed the fetch budget -> stays.
            assertEquals(14, MapRenderer.fetchZoom(14, MapRenderer.FULL_MAP));
        }

        @Test
        void countryViewOverzoomsForDetail() {
            // Full-country framing at z12 deepens to z13 (~192 tiles, within budget):
            // crisp coast instead of coarse overviews.
            assertEquals(13, MapRenderer.fetchZoom(12, MapRenderer.FULL_MAP));
        }

        @Test
        void deepZoomCapsAtLiveMax() {
            // Beyond z20 the tiles 404, so the request must never go deeper.
            var view = new MapRenderer.ViewBounds(114.925, 114.928, 4.905, 4.908);
            assertEquals(20, MapRenderer.fetchZoom(20, view));
            assertTrue(MapRenderer.zoomFor(1e-9, 100_000) <= 20);
        }
    }

    @Nested
    @DisplayName("projectIfVisible")
    class Visible {
        private final MapRenderer.ViewBounds view =
                new MapRenderer.ViewBounds(114.8, 115.0, 4.8, 5.0);

        @Test
        void insidePointProjects() {
            double[] p = MapRenderer.projectIfVisible(114.9, 4.9, 440, 340, view);
            assertNotNull(p);
            assertTrue(p[0] >= 14 && p[0] <= 426);
        }

        @Test
        void outsidePointIsHidden() {
            assertNull(MapRenderer.projectIfVisible(114.0, 4.0, 440, 340, view));
            assertNull(MapRenderer.projectIfVisible(Double.NaN, 4.9, 440, 340, view));
        }

        @Test
        void degenerateViewCenters() {
            var flat = new MapRenderer.ViewBounds(114.9, 114.9, 4.9, 4.9);
            double[] p = MapRenderer.project(114.9, 4.9, 440, 340, flat);
            assertEquals(220.0, p[0], 1e-9);
            assertEquals(170.0, p[1], 1e-9);
        }
    }
}
