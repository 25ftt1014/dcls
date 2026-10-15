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
    @DisplayName("basemap default")
    class BasemapDefault {
        @Test
        void defaultIsAlwaysOsmStandard() {
            assertEquals(MapRenderer.Basemap.OSM_STANDARD, MapRenderer.basemap);
            String url = MapRenderer.tileUrl(14, 12950, 8260);
            assertTrue(url.contains("tile.openstreetmap.org"));
        }

        @Test
        void osmUrlShape() {
            String url = MapRenderer.osmUrl(14, 12950, 8260, 0);
            assertTrue(url.startsWith("https://"));
            assertTrue(url.endsWith(".png"));
        }
    }

    @Nested
    @DisplayName("esri imagery")
    class EsriImagery {
        @Test
        void esriUrlAxisOrder() {
            // Esri serves {z}/{y}/{x}: row before column, unlike OSM-style {z}/{x}/{y}.
            assertEquals("https://server.arcgisonline.com/ArcGIS/rest/services"
                    + "/World_Imagery/MapServer/tile/14/8260/12950",
                    MapRenderer.esriImageryUrl(14, 12950, 8260));
        }

        @Test
        void scopesSeparatePerProvider() {
            MapRenderer.Basemap prev = MapRenderer.basemap;
            try {
                MapRenderer.basemap = MapRenderer.Basemap.OSM_STANDARD;
                String osm = MapRenderer.currentScopeName();
                MapRenderer.basemap = MapRenderer.Basemap.ESRI_IMAGERY;
                assertEquals("esri-v1", MapRenderer.currentScopeName());
                assertNotEquals(osm, MapRenderer.currentScopeName());
            } finally {
                MapRenderer.basemap = prev;
            }
        }

        @Test
        void memKeyPrefixesSeparateProviders() {
            assertEquals("o/14/1/2", MapRenderer.memKey(MapRenderer.Basemap.OSM_STANDARD, "14/1/2"));
            assertEquals("e/14/1/2", MapRenderer.memKey(MapRenderer.Basemap.ESRI_IMAGERY, "14/1/2"));
        }

        @Test
        void esriTileUrlSelected() {
            MapRenderer.Basemap prev = MapRenderer.basemap;
            try {
                MapRenderer.basemap = MapRenderer.Basemap.ESRI_IMAGERY;
                assertTrue(MapRenderer.tileUrl(14, 12950, 8260).contains("server.arcgisonline.com"));
            } finally {
                MapRenderer.basemap = prev;
            }
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
            assertEquals(display + 1, MapRenderer.fetchZoom(display, view, MapRenderer.Basemap.OSM_STANDARD));
            assertEquals(display + 1, MapRenderer.fetchZoom(display, view, MapRenderer.Basemap.ESRI_IMAGERY));
        }

        @Test
        void wideViewStaysAtDisplayZoom() {
            // Whole country at z14: deeper tiles exceed the fetch budget -> stays.
            assertEquals(14, MapRenderer.fetchZoom(14, MapRenderer.FULL_MAP, MapRenderer.Basemap.OSM_STANDARD));
        }

        @Test
        void countryViewOverzoomsForDetail() {
            // Full-country framing at z12 deepens to z13 (~192 tiles, within budget):
            // crisp coast instead of coarse overviews.
            assertEquals(13, MapRenderer.fetchZoom(12, MapRenderer.FULL_MAP, MapRenderer.Basemap.OSM_STANDARD));
            assertEquals(13, MapRenderer.fetchZoom(12, MapRenderer.FULL_MAP, MapRenderer.Basemap.ESRI_IMAGERY));
        }

        @Test
        void esriCapsAt19() {
            var view = new MapRenderer.ViewBounds(114.925, 114.928, 4.905, 4.908);
            assertEquals(19, MapRenderer.maxFetchZoom(MapRenderer.Basemap.ESRI_IMAGERY));
            assertEquals(20, MapRenderer.maxFetchZoom(MapRenderer.Basemap.OSM_STANDARD));
            assertEquals(19, MapRenderer.fetchZoom(19, view, MapRenderer.Basemap.ESRI_IMAGERY));
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
