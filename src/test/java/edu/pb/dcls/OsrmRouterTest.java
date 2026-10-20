package edu.pb.dcls;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static edu.pb.dcls.AppServices.OsrmRouter;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Offline lock for OSRM parsing and the routing fallback chain (no network in tests). */
class OsrmRouterTest {

    private static final String OSRM_SAMPLE = """
            {"code":"Ok","routes":[{"distance":4823.7,"duration":610.2,
            "geometry":{"coordinates":[[114.9400,4.8900],[114.9450,4.8950],[114.9500,4.9400]],"type":"LineString"}}],
            "waypoints":[{"location":[114.9400,4.8900]},{"location":[114.9500,4.9400]}]}""";

    @Nested
    @DisplayName("parseOsrm")
    class Parse {
        @Test
        void parsesPathAndKm() {
            OsrmRouter.LegResult leg = OsrmRouter.parseOsrm(OSRM_SAMPLE);
            assertNotNull(leg);
            assertEquals(3, leg.path().size());
            assertEquals(114.94, leg.path().get(0)[0], 1e-9);
            assertEquals(4.94, leg.path().get(2)[1], 1e-9);
            assertEquals(4.8237, leg.km(), 1e-4);
            assertTrue(leg.roadQuality());
        }

        @Test
        void rejectsGarbage() {
            assertNull(OsrmRouter.parseOsrm("{}"));
            assertNull(OsrmRouter.parseOsrm("not json"));
            assertNull(OsrmRouter.parseOsrm("{\"code\":\"NoRoute\",\"routes\":[]}"));
        }
    }

    @Nested
    @DisplayName("fallback chain")
    class Fallback {
        @Test
        void districtPairUsesBundledRoad() {
            // DCLS Depot -> Berakas is a bundled pair: road quality without network.
            OsrmRouter.LegResult leg = OsrmRouter.leg(114.94, 4.89, 114.95, 4.94);
            assertNotNull(leg);
            assertTrue(leg.roadQuality());
            assertTrue(leg.path().size() >= 2);
            assertTrue(leg.km() > 0);
        }

        @Test
        void unknownAreaFallsBackToSnapOrStraight() {
            // Mid-ocean coords match nothing: must still return a usable leg, flagged approximate.
            OsrmRouter.LegResult leg = OsrmRouter.snapLeg(114.0, 4.0, 114.1, 4.1);
            assertNotNull(leg);
            assertFalse(leg.roadQuality());
            assertEquals(2, leg.path().size());
            assertTrue(leg.km() > 0);
        }

        @Test
        void snapKmBeatsStraightLine() {
            // Tutong is far from the depot along real roads: snapped km must exceed haversine.
            double straight = AppServices.BruneiPlaces.haversineKm(114.94, 4.89, 114.66, 4.81);
            double snapped = OsrmRouter.snapKm(114.94, 4.89, 114.66, 4.81);
            assertTrue(snapped >= straight);
        }

    @Nested
    @DisplayName("table + ordering")
    class Table {
        private static final String TABLE_SAMPLE = """
                {"code":"Ok","distances":[[0,4823.7,9000.0],[4823.7,0,3500.0],[9000.0,3500.0,0]]}""";

        @Test
        void parsesMatrixKm() {
            double[][] matrix = OsrmRouter.parseTable(TABLE_SAMPLE);
            assertNotNull(matrix);
            assertEquals(3, matrix.length);
            assertEquals(4.8237, matrix[0][1], 1e-4);
            assertEquals(0.0, matrix[1][1], 1e-9);
        }

        @Test
        void rejectsBadTable() {
            assertNull(OsrmRouter.parseTable("{}"));
            assertNull(OsrmRouter.parseTable("{\"distances\":[[0,1],[2]]}"));
        }

        @Test
        void bruteForceFindsBest() {
            var stops = List.of(
                    new Domain.Stop("a", "A", 0, 0),
                    new Domain.Stop("b", "B", 0, 1),
                    new Domain.Stop("c", "C", 0, 2));
            // B->C cheap, A->C expensive: best is A,B,C.
            double[][] matrix = {{0, 1, 100}, {1, 0, 1}, {100, 1, 0}};
            var ordered = OsrmRouter.orderByMatrix(stops, matrix);
            assertEquals(List.of("A", "B", "C"), ordered.stream().map(Domain.Stop::name).toList());
            // Reverse the cheap edge: best is A,C,B.
            double[][] flipped = {{0, 100, 1}, {100, 0, 1}, {1, 1, 0}};
            var ordered2 = OsrmRouter.orderByMatrix(stops, flipped);
            assertEquals(List.of("A", "C", "B"), ordered2.stream().map(Domain.Stop::name).toList());
        }

        @Test
        void nearestParseShape() {
            String url = OsrmRouter.osrmUrl(114.94, 4.89, 114.95, 4.94);
            assertTrue(url.contains("/route/v1/driving/114.940000,4.890000;114.950000,4.940000"));
            assertTrue(url.contains("overview=full&geometries=geojson"));
        }
    }
    }

    @Nested
    @DisplayName("tracking warm-up + road detection")
    class Warmup {
        @Test
        void tourLegSnapsToBundledRoad() {
            // Depot -> nominatim-precise Gadong point (what tourForVehicle builds):
            // exact pair misses, district snap hits -> multi-point road path, not straight.
            var path = AppServices.RouteService.legPath(
                    AppServices.BruneiPlaces.DEPOT,
                    new Domain.Stop("stop-x", "Gadong Complex", 114.9189059, 4.9108598));
            assertTrue(path.size() > 2);
            assertTrue(AppServices.OsrmRouter.hasRoad(114.94, 4.89, 114.9189059, 4.9108598));
        }

        @Test
        void unknownPairHasNoRoad() {
            // Mid-sea coords snap to districts with no bundled pair: straight fallback.
            assertFalse(AppServices.OsrmRouter.hasRoad(114.0, 4.0, 114.1, 4.1));
        }

        @Test
        void warmLegSkipsCachedPairs() {
            // Depot -> Berakas district centers are bundled: nothing to fetch, no callback.
            assertFalse(AppServices.OsrmRouter.warmLeg(114.94, 4.89, 114.95, 4.94, () -> {
                throw new AssertionError("must not fetch cached legs");
            }));
        }

        @Test
        void resolveReadsDiskCacheWithoutNetwork() throws Exception {
            java.nio.file.Path home = java.nio.file.Files.createTempDirectory("dcls-osrm-home");
            String prev = System.getProperty("user.home");
            System.setProperty("user.home", home.toString());
            try {
                // Distinct coords from the other tests: resolve() memoizes into OMEM.
                double lonA = 113.0, latA = 3.0, lonB = 113.1, latB = 3.1;
                assertNull(AppServices.OsrmRouter.leg(lonA, latA, lonB, latB));
                // Canned OSRM response under the r4-rounded cache key (mirrors osrmDiskPath).
                String okey = "osrm:" + r4(lonA) + "," + r4(latA) + ">" + r4(lonB) + "," + r4(latB);
                java.nio.file.Path dir = home.resolve(".dcls/osrm");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.writeString(dir.resolve(Integer.toHexString(okey.hashCode()) + ".json"),
                        "{\"code\":\"Ok\",\"routes\":[{\"distance\":1500.0,\"duration\":120.0,"
                                + "\"geometry\":{\"coordinates\":[[113.0,3.0],[113.05,3.05],[113.1,3.1]],"
                                + "\"type\":\"LineString\"}}]}",
                        java.nio.charset.StandardCharsets.UTF_8);
                OsrmRouter.LegResult leg = AppServices.OsrmRouter.resolve(lonA, latA, lonB, latB);
                assertNotNull(leg);
                assertEquals(3, leg.path().size());
                assertEquals(1.5, leg.km(), 1e-9);
                assertTrue(leg.roadQuality());
                assertTrue(AppServices.OsrmRouter.hasRoad(lonA, latA, lonB, latB));
            } finally {
                System.setProperty("user.home", prev);
            }
        }

        private static double r4(double value) { return Math.round(value * 1e4) / 1e4; }
    }

    @Nested
    @DisplayName("preview compute")
    class Compute {
        @Test
        void computeRouteStaysUnsaved() {
            MockRepository repo = new MockRepository();
            int before = repo.routes().size();
            AppServices.RouteService routes = new AppServices.RouteService(repo);
            var plan = routes.computeRoute("Preview", List.of(
                    AppServices.BruneiPlaces.DEPOT,
                    new Domain.Stop("x", "Berakas", 114.95, 4.94)));
            assertNotNull(plan);
            assertEquals(before, repo.routes().size());
        }
    }
}
