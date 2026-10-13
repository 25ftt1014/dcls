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
    void outOfBoundsFixRejected() {
        MockRepository repo = new MockRepository();
        AppServices.TrackingService tracking = new AppServices.TrackingService(repo);
        assertThrows(AppServices.AppException.class, () ->
                tracking.ingest("v-1934", null, 0, 0, 0, null, null, Domain.FixType.GPS, "nmea", LocalDateTime.now()));
    }
}
