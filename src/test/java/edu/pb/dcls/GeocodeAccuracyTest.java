package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Offline lock for search-first geocoding: parser handles real Nominatim shapes. */
class GeocodeAccuracyTest {

    private static final String SAMPLE =
            "[{\"place_id\":1,\"lat\":\"4.9032\",\"lon\":\"114.9210\",\"display_name\":\"Jalan Gadong, Gadong, Brunei\","
            + "\"type\":\"residential\",\"importance\":0.5,\"place_rank\":26},"
            + "{\"place_id\":2,\"lat\":4.9080,\"lon\":114.9280,\"display_name\":\"Gadong, Brunei-Muara, Brunei\","
            + "\"type\":\"suburb\",\"importance\":0.4,\"place_rank\":14}]";

    @Test
    void parserPrefersRooftopOverSuburb() {
        List<MapRenderer.PlaceHit> hits = MapRenderer.parseNominatim(SAMPLE, "q");
        assertEquals(2, hits.size());
        // House/road rank 26 first, suburb second.
        assertEquals(114.9210, hits.get(0).lon(), 1e-6);
        assertTrue(hits.get(0).displayName().contains("Jalan Gadong"));
        assertEquals(26, hits.get(0).placeRank());
    }

    @Test
    void parserHandlesNumericLatLon() {
        String json = "[{\"lat\":4.89,\"lon\":114.94,\"display_name\":\"Bandar, Brunei\",\"importance\":0.6,\"place_rank\":14}]";
        List<MapRenderer.PlaceHit> hits = MapRenderer.parseNominatim(json, "q");
        assertEquals(1, hits.size());
        assertEquals(114.94, hits.get(0).lon(), 1e-6);
    }

    @Test
    void parserKeepsDisplayName() {
        List<MapRenderer.PlaceHit> hits = MapRenderer.parseNominatim(SAMPLE, "fallback");
        assertFalse(hits.get(0).displayName().isBlank());
        assertFalse(hits.get(0).displayName().equals("fallback"));
    }

    @Test
    void emptyAndGarbageGiveNoHits() {
        assertTrue(MapRenderer.parseNominatim("", "q").isEmpty());
        assertTrue(MapRenderer.parseNominatim("not json", "q").isEmpty());
        assertTrue(MapRenderer.parseNominatim("[{\"foo\":1}]", "q").isEmpty());
    }

    @Test
    void keywordFallbackStaysApproximate() {
        var places = AppServices.BruneiPlaces.places();
        // Pure district text with no network: use scoring path via empty address cache miss is
        // network-dependent, so assert the fallback contract instead.
        var g = AddressGeocoder.geocode("", places);
        assertEquals(0, g.confidence());
        assertEquals("FALLBACK", g.method());
    }
}
