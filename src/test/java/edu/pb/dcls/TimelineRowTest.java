package edu.pb.dcls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static edu.pb.dcls.Domain.Stop;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Route timeline pairing contract. The panel used to render a leg list and a separate
 * ETA breakdown that printed the same numbers twice; they are now one arrival-ordered
 * list. legKm holds one fewer entry than stops, so legKm.get(i - 1) arrives AT stop i.
 * That off-by-one is the only thing worth pinning down.
 */
class TimelineRowTest {

    private static List<Stop> stops(String... names) {
        return java.util.Arrays.stream(names)
                .map(n -> new Stop(n.toLowerCase(java.util.Locale.ROOT), n, 114.9, 4.9))
                .toList();
    }

    @Test
    void numbersStopsFromOne() {
        var rows = RoutesView.timelineRows(stops("DCLS Depot", "A", "B"),
                List.of(4.9, 2.4), List.of(0d, 8d, 13d));
        assertEquals(3, rows.size());
        assertEquals(1, rows.get(0).number());
        assertEquals(2, rows.get(1).number());
        assertEquals(3, rows.get(2).number());
    }

    @Test
    void depotHasNoIncomingLeg() {
        var rows = RoutesView.timelineRows(stops("DCLS Depot", "A"),
                List.of(4.9), List.of(0d, 8d));
        assertNull(rows.get(0).legKm(), "the first stop has no leg arriving at it");
    }

    @Test
    void legDistanceAttachesToTheStopItArrivesAt() {
        // The bug this guards: legKm.get(0) is depot->A, so it belongs on row 2, not row 1.
        var rows = RoutesView.timelineRows(stops("DCLS Depot", "A", "B"),
                List.of(4.9, 2.4), List.of(0d, 8d, 13d));
        assertEquals(4.9, rows.get(1).legKm(), 1e-9);
        assertEquals(2.4, rows.get(2).legKm(), 1e-9);
    }

    @Test
    void arrivalTimesPassThroughUnchanged() {
        var rows = RoutesView.timelineRows(stops("DCLS Depot", "A", "B"),
                List.of(4.9, 2.4), List.of(0d, 8d, 13d));
        assertEquals(0, rows.get(0).arrivalMin());
        assertEquals(8, rows.get(1).arrivalMin());
        assertEquals(13, rows.get(2).arrivalMin());
    }

    @Test
    void stopNamesComeThrough() {
        var rows = RoutesView.timelineRows(stops("DCLS Depot", "Custom pin 1"),
                List.of(4.9), List.of(0d, 8d));
        assertEquals("DCLS Depot", rows.get(0).name());
        assertEquals("Custom pin 1", rows.get(1).name());
    }

    @Test
    void singleStopYieldsOneRowWithNoLeg() {
        var rows = RoutesView.timelineRows(stops("DCLS Depot"), List.of(), List.of(0d));
        assertEquals(1, rows.size());
        assertNull(rows.get(0).legKm());
    }

    @Test
    void emptyInputYieldsNoRows() {
        assertTrue(RoutesView.timelineRows(List.of(), List.of(), List.of()).isEmpty());
        assertTrue(RoutesView.timelineRows(null, null, null).isEmpty());
    }

    @Test
    void missingLegDataDoesNotMisalignTheRest() {
        // A short leg list must not shift distances onto the wrong stops.
        var rows = RoutesView.timelineRows(stops("DCLS Depot", "A", "B"),
                List.of(4.9), List.of(0d, 8d, 13d));
        assertEquals(4.9, rows.get(1).legKm(), 1e-9);
        assertNull(rows.get(2).legKm(), "a missing leg is blank, never borrowed from a neighbour");
    }
}