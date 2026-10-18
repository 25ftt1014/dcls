package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Solo-trail focus contract: one focused delivery shows only its own trail. */
class TrackingFocusTest {

    @Test
    void focusTrailWidthSane() {
        assertTrue(DclsApp.TRACK_FOCUS_WIDTH >= 3.0);
    }

    @Test
    void followDefaultsOff() {
        assertFalse(new DclsApp().followSelected,
                "map must not chase the selection unless the user opts into Follow");
    }

    @Test
    void panelDefaultsExpanded() {
        // Replaces dividerDefaultsSane: the panel is a fixed-width sibling of the map
        // now, so there is no draggable divider position to keep sane — it is either
        // shown or hidden, and it starts shown.
        DclsApp app = new DclsApp();
        assertFalse(app.trackingPanelCollapsed);
    }

    @Test
    void noFocusShowsWholeFleet() {
        Set<String> ids = new LinkedHashSet<>(List.of("v-a", "v-b"));
        assertEquals(List.of("v-a", "v-b"), DclsApp.visibleVehicles(null, ids));
    }

    @Test
    void focusShowsOnlyItsTrail() {
        Set<String> ids = new LinkedHashSet<>(List.of("v-a", "v-b", "v-c"));
        assertEquals(List.of("v-b"), DclsApp.visibleVehicles("v-b", ids));
    }

    @Test
    void unknownFocusFallsBackToShowAll() {
        Set<String> ids = new LinkedHashSet<>(List.of("v-a", "v-b"));
        assertEquals(List.of("v-a", "v-b"), DclsApp.visibleVehicles("v-gone", ids));
    }

    @Test
    void visibleVehiclesHandlesNulls() {
        assertTrue(DclsApp.visibleVehicles("v-a", null).isEmpty());
        assertTrue(DclsApp.visibleVehicles(null, null).isEmpty());
    }
}
