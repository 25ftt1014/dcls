package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GpsFeedTest {

    @Test
    void nmeaRmcParsesFix() {
        // $GPRMC,123519,A,0454.000,N,11456.400,E,022.4,084.4,230394,003.1,W*6A pattern (checksum optional)
        String rmc = "$GPRMC,123519,A,0454.000,N,11456.400,E,22.4,84.4,230394,,,A";
        GpsFeed.GpsFix fix = GpsFeed.parseNmea(rmc, "v-1934");
        assertNotNull(fix);
        assertEquals("v-1934", fix.vehicleId());
        assertEquals(114.94, fix.lon(), 0.01);
        assertEquals(4.9, fix.lat(), 0.01);
        assertTrue(fix.speedKmh() > 30);
    }

    @Test
    void nmeaBadChecksumRejected() {
        String bad = "$GPRMC,123519,A,0454.000,N,11456.400,E,22.4,84.4,230394,,,A*00";
        assertNull(GpsFeed.parseNmea(bad, "v-1"));
    }

    @Test
    void gpxRoundTrip() {
        String gpx = "<?xml version=\"1.0\"?><gpx version=\"1.1\"><trk><name>T</name><trkseg>"
                + "<trkpt lat=\"4.9010\" lon=\"114.9180\"><time>2026-10-13T02:00:00Z</time></trkpt>"
                + "<trkpt lat=\"4.9020\" lon=\"114.9200\"><time>2026-10-13T02:01:00Z</time></trkpt>"
                + "</trkseg></trk></gpx>";
        List<GpsFeed.GpsFix> fixes = GpsFeed.parseGpx(gpx, "v-1934", "gpx");
        assertEquals(2, fixes.size());
        assertEquals(114.9180, fixes.get(0).lon(), 1e-6);
    }

    @Test
    void mobileJsonParses() {
        String json = "[{\"vehicleId\":\"v-1934\",\"lon\":114.94,\"lat\":4.89,\"speed\":42,\"heading\":90,\"accuracy\":8,\"ts\":\"2026-10-13T02:00:00Z\"}]";
        List<GpsFeed.GpsFix> fixes = GpsFeed.parseMobileJson(json);
        assertEquals(1, fixes.size());
        assertEquals(42, fixes.get(0).speedKmh(), 1e-6);
        assertEquals(Domain.FixType.GPS, fixes.get(0).fixType());
    }

    @Test
    void trackingIngestPersistsRealFix() {
        MockRepository repo = new MockRepository();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        int before = repo.trackingHistory().size();
        var point = tracking.ingest("v-1934", "DCLS-2401", 114.9180, 4.9010,
                40, 90.0, 8.0, Domain.FixType.GPS, "nmea", LocalDateTime.now());
        assertNotNull(point);
        assertEquals(before + 1, repo.trackingHistory().size());
        assertEquals(40, point.speedKmh(), 1e-6);
        assertTrue(point.etaMinutes() >= 0);
    }

    @Test
    void bulkSameTimestampFixesAllStored() {
        // NMEA sentences carry no date and MySQL TIMESTAMP truncates to whole seconds,
        // so bulk imports stamp many fixes with the same instant — all must be kept.
        MockRepository repo = new MockRepository();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        LocalDateTime ts = LocalDateTime.now();
        int before = repo.trackingHistory().size();
        for (int i = 0; i < 5; i++) {
            tracking.ingest("v-8817", null, 114.9000 + i * 0.001, 4.9000 + i * 0.001,
                    30, null, null, Domain.FixType.GPS, "nmea", ts);
        }
        assertEquals(before + 5, repo.trackingHistory().size());
    }

    @Test
    void olderFixIgnoredButExactRedeliveryIgnored() {
        MockRepository repo = new MockRepository();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        LocalDateTime base = LocalDateTime.now();
        var first = tracking.ingest("v-8817", null, 114.9000, 4.9000,
                30, null, null, Domain.FixType.GPS, "nmea", base);
        assertNotNull(first);
        int size = repo.trackingHistory().size();
        // Strictly older fix: ignored.
        assertSame(first, tracking.ingest("v-8817", null, 114.9010, 4.9010,
                30, null, null, Domain.FixType.GPS, "nmea", base.minusMinutes(1)));
        assertEquals(size, repo.trackingHistory().size());
        // Exact redelivery (same instant + position, e.g. repeated mobile poll): ignored.
        tracking.ingest("v-8817", null, 114.9000, 4.9000,
                30, null, null, Domain.FixType.GPS, "nmea", base);
        assertEquals(size, repo.trackingHistory().size());
    }

    @Test
    void outOfBoundsFixRejected() {
        MockRepository repo = new MockRepository();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        assertThrows(AppServices.AppException.class, () ->
                tracking.ingest("v-1934", null, 0, 0, 0, null, null, Domain.FixType.GPS, "nmea", LocalDateTime.now()));
    }
}
