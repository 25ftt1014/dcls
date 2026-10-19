package edu.pb.dcls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

    /**
     * The Layers panel unticks vehicles one at a time, and the map obeys the same set.
     * These lock the rule the panel depends on: ticks subtract, solo focus wins while it
     * is still ticked, and unticking everything leaves a clean map.
     */
    @Nested
    @DisplayName("layers visibility")
    class Layers {
        private final Set<String> fleet = new LinkedHashSet<>(List.of("v-a", "v-b", "v-c"));

        @Test
        void nothingHiddenShowsTheWholeFleet() {
            assertEquals(List.of("v-a", "v-b", "v-c"),
                    DclsApp.visibleVehicles(null, fleet, Set.of()));
        }

        @Test
        void untickedVehiclesAreDropped() {
            assertEquals(List.of("v-a", "v-c"),
                    DclsApp.visibleVehicles(null, fleet, Set.of("v-b")));
        }

        @Test
        void severalTickedVehiclesAllDraw() {
            // Ticking is additive: two unticked leaves two routes on the map, not one.
            assertEquals(List.of("v-a", "v-c"),
                    DclsApp.visibleVehicles(null, fleet, Set.of("v-b")));
            assertEquals(List.of("v-b"), DclsApp.visibleVehicles(null, fleet, Set.of("v-a", "v-c")));
        }

        @Test
        void untickingEveryVehicleLeavesACleanMap() {
            assertTrue(DclsApp.visibleVehicles(null, fleet, fleet).isEmpty(),
                    "no ticked vehicle means nothing is drawn");
        }

        @Test
        void soloFocusWinsWhileItIsStillTicked() {
            assertEquals(List.of("v-b"),
                    DclsApp.visibleVehicles("v-b", fleet, Set.of("v-a", "v-c")));
        }

        @Test
        void anUntickedFocusFallsBackToTheRest() {
            assertEquals(List.of("v-a", "v-c"),
                    DclsApp.visibleVehicles("v-b", fleet, Set.of("v-b")),
                    "a focused vehicle the operator unticked must not be drawn");
        }

        @Test
        void hiddenSetHandlesNulls() {
            assertEquals(List.of("v-a", "v-b", "v-c"),
                    DclsApp.visibleVehicles(null, fleet, null));
            assertTrue(DclsApp.visibleVehicles(null, null, Set.of()).isEmpty());
        }

        @Test
        void theTwoArgumentFormStillMeansSoloFocus() {
            assertEquals(List.of("v-b"), DclsApp.visibleVehicles("v-b", fleet));
            assertEquals(List.of("v-a", "v-b", "v-c"), DclsApp.visibleVehicles(null, fleet));
        }
    }
}
