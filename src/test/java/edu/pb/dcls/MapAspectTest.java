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
}
