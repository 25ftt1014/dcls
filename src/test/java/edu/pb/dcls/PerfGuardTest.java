package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static edu.pb.dcls.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

/** Performance guards: no repeat network for known queries, bounded memory, cached tours. */
class PerfGuardTest {

    @Test
    void negativeMissesAreCachedWithoutNetwork() {
        String q = "xyznonexistent-place-" + System.nanoTime();
        assertFalse(MapRenderer.isGeoMissCached(q));
        MapRenderer.noteGeoMissForTest(q);
        assertTrue(MapRenderer.isGeoMissCached(q));
        long start = System.currentTimeMillis();
        assertTrue(MapRenderer.fetchNominatim(q).isEmpty());
        assertTrue(System.currentTimeMillis() - start < 2000,
                "cached miss must short-circuit without network");
    }

    @Test
    void missLevelsShortCircuitWithoutNetwork() {
        String q = "qqq-noplace-" + System.nanoTime();
        MapRenderer.noteGeoMissForTest(q);
        long start = System.currentTimeMillis();
        var lh = MapRenderer.searchLevels(List.of(q));
        assertTrue(lh.hits().isEmpty());
        assertTrue(System.currentTimeMillis() - start < 2000,
                "cached miss level must short-circuit without network");
    }

    @Test
    void mockTrackingHistoryIsCapped() {
        MockRepository repo = new MockRepository();
        for (int i = 0; i < MockRepository.TRACKING_CAP + 500; i++) {
            repo.saveTrackingPoint(new TrackingPoint("t-cap-" + i, "v-1934", "DCLS-2401",
                    114.9, 4.9, 1, LocalDateTime.now()));
        }
        assertTrue(repo.trackingHistory().size() <= MockRepository.TRACKING_CAP);
    }

    @Test
    void tourReusedAcrossTicksWithoutNewData() {
        MockRepository repo = new MockRepository();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        var first = tracking.tourForVehicle("v-1934");
        var second = tracking.tourForVehicle("v-1934");
        assertSame(first, second, "identical tick state must reuse the cached tour (no re-geocode)");
    }

    @Test
    void tourRebuiltOnNewFix() {
        MockRepository repo = new MockRepository();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        var first = tracking.tourForVehicle("v-1934");
        tracking.ingest("v-1934", "DCLS-2401", 114.9180, 4.9010, 40, 90.0, 8.0,
                FixType.GPS, "nmea", LocalDateTime.now().plusSeconds(5));
        var second = tracking.tourForVehicle("v-1934");
        assertNotSame(first, second, "new fix must invalidate the cached tour");
    }

    @Test
    void currentScopeNameMatchesDiskLayout() {
        String scope = MapRenderer.currentScopeName();
        assertFalse(scope.isBlank());
        assertFalse(scope.equals("keyless"), "old poisoned scope must never be current");
        assertFalse(scope.matches("\\d+"), "abandoned numeric scopes must never be current");
    }
}
