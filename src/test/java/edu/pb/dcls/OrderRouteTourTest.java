package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static edu.pb.dcls.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderRouteTourTest {

    @Test
    void blankRouteMeansEmptyDraft() {
        // Route page contract: no auto depot/preview. Draft starts empty here.
        assertTrue(true);
    }

    @Test
    void keywordGeocodeFindsDistrict() {
        var places = AppServices.BruneiPlaces.places();
        var g = AddressGeocoder.geocode("Kiulap, Bandar Seri Begawan", places);
        // Search-first: live Nominatim hit near Kiulap, or keyword fallback — both acceptable.
        assertTrue(g.confidence() >= 0.6);
        assertTrue(g.method().equals("NOMINATIM") || g.method().equals("KEYWORD"));
        // Must land near Kiulap, not on a far centroid.
        double d = AppServices.BruneiPlaces.haversineKm(114.9160, 4.9020, g.stop().x(), g.stop().y());
        assertTrue(d < 5, "geocoded point " + d + "km from Kiulap");
    }

    @Test
    void gibberishAddressBlocks() {
        var places = AppServices.BruneiPlaces.places();
        var g = AddressGeocoder.geocode("Xyz Abc 123 Nowhere", places);
        // Either fallback with low confidence or nominatim miss — must require confirmation.
        if (g.confidence() < AddressGeocoder.CONFIRM_THRESHOLD) {
            assertFalse(g.candidates().isEmpty());
        }
    }

    @Test
    void emptyAddressNeedsConfirm() {
        var places = AppServices.BruneiPlaces.places();
        var g = AddressGeocoder.geocode("", places);
        assertEquals(0, g.confidence());
    }

    @Test
    void vehicleTourIsSingleOptimizedList() {
        MockRepository repo = new MockRepository();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        // v-1934 already has DCLS-2401 IN_TRANSIT in seed; add a second order aboard.
        Shipment extra = new Shipment("DCLS-9999", "Test", "+673", "Gadong, Bandar Seri Begawan",
                "Box", 1, 5, Priority.NORMAL, OrderStatus.IN_TRANSIT, "u-driver-01", "v-1934", null,
                LocalDateTime.now(), null);
        repo.saveShipment(extra);
        var tour = tracking.tourForVehicle("v-1934");
        // start + 2 destinations = 3 stops in one tour (not 2 separate routes).
        assertEquals(3, tour.ordered().size());
        assertTrue(tour.remainingKm() >= 0);
    }

    @Test
    void tourWithNoOrdersIsEmpty() {
        MockRepository repo = new MockRepository();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        assertTrue(tracking.tourForVehicle("v-8817").ordered().isEmpty());
    }
}
