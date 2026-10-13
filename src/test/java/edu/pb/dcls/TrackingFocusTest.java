package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Show-all tracking contract: focus highlights, never hides other vehicles. */
class TrackingFocusTest {

    @Test
    void spotlightStaysClearlyVisible() {
        assertTrue(DclsApp.TRACK_DIM_ALPHA >= 0.8,
                "non-focused tours must stay clearly visible, got " + DclsApp.TRACK_DIM_ALPHA);
        assertTrue(DclsApp.TRACK_DIM_WIDTH >= 3.0);
        assertTrue(DclsApp.TRACK_FOCUS_WIDTH >= DclsApp.TRACK_DIM_WIDTH);
    }

    @Test
    void followDefaultsOff() {
        assertFalse(new DclsApp().followSelected,
                "map must not chase the selection unless the user opts into Follow");
    }

    @Test
    void offscreenSkipsFocusedVehicle() {
        var view = new MapRenderer.ViewBounds(114.8, 115.0, 4.8, 5.0);
        Map<String, double[]> pos = new LinkedHashMap<>();
        pos.put("v-focus", new double[] {114.0, 4.0}); // off-screen but focused -> excluded
        pos.put("v-near", new double[] {114.9, 4.9}); // in view
        pos.put("v-far", new double[] {114.0, 4.0}); // off-screen
        List<String> hidden = DclsApp.offscreenIds(view, 440, 340, pos, "v-focus");
        assertEquals(List.of("v-far"), hidden);
    }

    @Test
    void offscreenEmptyWhenAllVisible() {
        var view = new MapRenderer.ViewBounds(114.8, 115.0, 4.8, 5.0);
        Map<String, double[]> pos = new LinkedHashMap<>();
        pos.put("v-a", new double[] {114.9, 4.9});
        pos.put("v-b", new double[] {114.85, 4.95});
        assertTrue(DclsApp.offscreenIds(view, 440, 340, pos, "v-a").isEmpty());
    }

    @Test
    void offscreenHandlesNulls() {
        assertTrue(DclsApp.offscreenIds(null, 440, 340, Map.of(), null).isEmpty());
        assertTrue(DclsApp.offscreenIds(
                new MapRenderer.ViewBounds(114.8, 115.0, 4.8, 5.0), 440, 340, null, null).isEmpty());
    }
}
