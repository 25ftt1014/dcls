package edu.pb.dcls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("basemap no-key default")
    class BasemapDefault {
        @Test
        void noKeyNeverRequestsCarto() {
            if (!MapRenderer.CARTO_KEY.isEmpty()) return; // keyed env: covered manually
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
