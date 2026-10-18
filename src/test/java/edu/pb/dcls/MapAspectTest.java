package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Zoom-out squash regression: px-per-km must match on both axes at any zoom. */
class MapAspectTest {

    private static double ratio(MapRenderer.ViewBounds view, double w, double h) {
        double[] a = MapRenderer.projectRaw(114.94, 4.90, w, h, view);
        double[] b = MapRenderer.projectRaw(114.95, 4.90, w, h, view);
        double[] c = MapRenderer.projectRaw(114.94, 4.91, w, h, view);
        double kx = 0.01 * Math.PI / 180 * 6378137 * Math.cos(Math.toRadians(4.9)) / 1000;
        double ky = (MapRenderer.mercator(4.91) - MapRenderer.mercator(4.90)) * 6378137 / 1000;
        return (Math.abs(b[0] - a[0]) / kx) / (Math.abs(c[1] - a[1]) / ky);
    }

    @Test
    void wideClampedViewIsUndistorted() {
        // Request wider than the map: old code pinned lon to full range without
        // rebalancing lat (measured X/Y = 1.38, visible squash).
        var view = MapRenderer.fitAspect(
                new MapRenderer.ViewBounds(114.55, 115.05, 4.72, 5.02),
                MapRenderer.drawableAspect(1000, 330));
        assertEquals(1.0, ratio(view, 1000, 330), 0.03);
    }

    @Test
    void zoomedViewIsUndistorted() {
        var view = MapRenderer.fitAspect(MapRenderer.fitBounds(
                List.of(new double[] {114.94, 4.89}, new double[] {114.95, 4.94}),
                MapRenderer.drawableAspect(1000, 330)),
                MapRenderer.drawableAspect(1000, 330));
        assertEquals(1.0, ratio(view, 1000, 330), 0.03);
    }

    @Test
    void tallCanvasIsUndistorted() {
        var view = MapRenderer.fitAspect(
                new MapRenderer.ViewBounds(114.90, 114.98, 4.85, 4.95),
                MapRenderer.drawableAspect(330, 1000));
        assertEquals(1.0, ratio(view, 330, 1000), 0.03);
    }

    @Test
    void drawableAspectAccountsForPad() {
        // Full-canvas w/h would be ~7% off; the drawable area is what matters.
        assertEquals((1000 - 28) / (330.0 - 28), MapRenderer.drawableAspect(1000, 330), 1e-9);
    }

    @Test
    void degenerateCanvasSizeNeverSquashesAspect() {
        // Pre-layout frames (zero/negative canvas width) must yield a positive
        // aspect so the stored view can never be squashed by a bad first paint.
        assertTrue(MapRenderer.drawableAspect(0, 480) > 0);
        assertTrue(MapRenderer.drawableAspect(-24, 480) > 0);
        assertTrue(MapRenderer.drawableAspect(640, 0) > 0);
    }

    @Test
    void zoomForRespectsLiveFetchMax() {
        // Beyond z20 the tiles 404, so a very deep view must never request them.
        assertTrue(MapRenderer.zoomFor(1e-9, 100_000) <= 20);
    }

    @Test
    void edgePushedViewRefitsWithoutSquash() {
        // Panning past the map border shifts the view out of range; clamping back
        // plus aspect re-fit (the panBy path) must stay undistorted.
        var pushed = new MapRenderer.ViewBounds(114.0, 114.2, 4.7, 4.9);
        double aspect = MapRenderer.drawableAspect(900, 480);
        var refit = MapRenderer.fitAspect(pushed, aspect);
        assertEquals(1.0, ratio(refit, 900, 480), 0.03);
    }

    @Test
    void zoomedViewIsUndistortedAndCentred() {
        // The post-Clear view: explicit zoom on the depot. Uniform scale on both axes
        // and the requested centre preserved.
        double w = 750, h = 480;
        double viewPx = w - MapRenderer.MAP_PAD * 2;
        double aspect = MapRenderer.drawableAspect(w, h);
        var view = MapRenderer.zoomedView(114.94, 4.89, MapRenderer.DEFAULT_ZOOM, viewPx, aspect);

        assertEquals(1.0, ratio(view, w, h), 0.03, "explicit-zoom view must not squash");
        double[] centre = MapRenderer.projectRaw(114.94, 4.89, w, h, view);
        assertEquals(w / 2, centre[0], 1.5);
        assertEquals(h / 2, centre[1], 1.5);
    }

    @Test
    void zoomedViewStaysInsideTheMap() {
        double aspect = MapRenderer.drawableAspect(750, 480);
        var view = MapRenderer.zoomedView(114.94, 4.89, MapRenderer.DEFAULT_ZOOM,
                750 - MapRenderer.MAP_PAD * 2, aspect);
        assertTrue(view.lonMin() >= MapRenderer.MAP_LON_MIN - 1e-9);
        assertTrue(view.lonMax() <= MapRenderer.MAP_LON_MAX + 1e-9);
        assertTrue(view.latMin() >= MapRenderer.MAP_LAT_MIN - 1e-9);
        assertTrue(view.latMax() <= MapRenderer.MAP_LAT_MAX + 1e-9);
    }

    @Test
    void zoomedViewIsTighterThanTheWholeCountry() {
        // Regression guard for the Clear bug: the home view must be a neighbourhood,
        // never the all-of-Brunei extent fitBounds falls back to.
        double aspect = MapRenderer.drawableAspect(750, 480);
        var view = MapRenderer.zoomedView(114.94, 4.89, MapRenderer.DEFAULT_ZOOM,
                750 - MapRenderer.MAP_PAD * 2, aspect);
        double homeLon = view.lonMax() - view.lonMin();
        double countryLon = MapRenderer.FULL_MAP.lonMax() - MapRenderer.FULL_MAP.lonMin();
        assertTrue(homeLon < countryLon / 5,
                "home view must be far tighter than the country, got " + homeLon + " vs " + countryLon);
    }

    @Test
    void zoomedViewClampsZoomIntoTheFetchableRange() {
        double aspect = MapRenderer.drawableAspect(750, 480);
        double px = 750 - MapRenderer.MAP_PAD * 2;
        for (int z : new int[] {-5, 0, 99}) {
            var view = MapRenderer.zoomedView(114.94, 4.89, z, px, aspect);
            assertTrue(view.lonMax() > view.lonMin() && view.latMax() > view.latMin(),
                    "zoom " + z + " must still yield a usable view");
        }
    }
}
