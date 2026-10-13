package edu.pb.dcls;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real GPS input abstraction (WiFi-first, no simulation).
 * Sources: live NMEA stream (TCP/file), driver mobile feed (HTTP JSON poll),
 * GPX file import / replay. All emit {@link GpsFix} which TrackingService ingests.
 */
public final class GpsFeed {
    private GpsFeed() { }

    public record GpsFix(String vehicleId, double lon, double lat,
                         double speedKmh, Double headingDeg, Double accuracyM,
                         Domain.FixType fixType, Instant time, String source) { }

    public interface Feed extends AutoCloseable {
        void start(Consumer<GpsFix> onFix);
        @Override default void close() { stop(); }
        void stop();
        String sourceName();
        default boolean isLive() { return false; }
    }

    // ---- NMEA parsing ($GPGGA / $GPRMC, checksum-validated) ----

    public static Double parseNmeaLat(String ddmm, String ns) {
        try {
            double v = Double.parseDouble(ddmm);
            double deg = Math.floor(v / 100), min = v - deg * 100;
            double dec = deg + min / 60d;
            return "S".equalsIgnoreCase(ns) ? -dec : dec;
        } catch (Exception e) { return null; }
    }

    public static Double parseNmeaLon(String dddmm, String ew) {
        try {
            double v = Double.parseDouble(dddmm);
            double deg = Math.floor(v / 100), min = v - deg * 100;
            double dec = deg + min / 60d;
            return "W".equalsIgnoreCase(ew) ? -dec : dec;
        } catch (Exception e) { return null; }
    }

    static boolean checksumOk(String sentence) {
        int star = sentence.lastIndexOf('*');
        if (star < 0 || !sentence.startsWith("$")) return false;
        int cs = 0;
        for (int i = 1; i < star; i++) cs ^= sentence.charAt(i);
        try {
            return Integer.parseInt(sentence.substring(star + 1).trim(), 16) == cs;
        } catch (NumberFormatException e) { return false; }
    }

    /** Parse one NMEA sentence for the given vehicle. Returns null when not a fix. */
    public static GpsFix parseNmea(String sentence, String vehicleId) {
        if (sentence == null) return null;
        sentence = sentence.trim();
        if (!sentence.startsWith("$")) return null;
        boolean hasChecksum = sentence.contains("*");
        if (hasChecksum && !checksumOk(sentence)) return null;
        String body = sentence.substring(0, sentence.contains("*") ? sentence.indexOf('*') : sentence.length());
        String[] f = body.split(",", -1);
        try {
            if (f[0].endsWith("GGA") && f.length >= 10) {
                int quality = f[6].isEmpty() ? 0 : Integer.parseInt(f[6]);
                if (quality == 0) return new GpsFix(vehicleId, 0, 0, 0, null, null, Domain.FixType.NONE, Instant.now(), "nmea");
                Double lat = parseNmeaLat(f[2], f[3]);
                Double lon = parseNmeaLon(f[4], f[5]);
                Double acc = f[8].isEmpty() ? null : Double.parseDouble(f[8]) * 5.0;
                if (lat == null || lon == null) return null;
                return new GpsFix(vehicleId, lon, lat, 0, null, acc, Domain.FixType.GPS, Instant.now(), "nmea");
            }
            if (f[0].endsWith("RMC") && f.length >= 9) {
                if (!"A".equals(f[2])) return new GpsFix(vehicleId, 0, 0, 0, null, null, Domain.FixType.NONE, Instant.now(), "nmea");
                Double lat = parseNmeaLat(f[3], f[4]);
                Double lon = parseNmeaLon(f[5], f[6]);
                double knots = f[7].isEmpty() ? 0 : Double.parseDouble(f[7]);
                Double hdg = f[8].isEmpty() ? null : Double.parseDouble(f[8]);
                if (lat == null || lon == null) return null;
                return new GpsFix(vehicleId, lon, lat, knots * 1.852, hdg, null, Domain.FixType.GPS, Instant.now(), "nmea");
            }
        } catch (Exception ignored) { }
        return null;
    }

    public static List<GpsFix> readNmeaFile(Path file, String vehicleId) throws IOException {
        List<GpsFix> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            GpsFix fix = parseNmea(line, vehicleId);
            if (fix != null && fix.fixType() != Domain.FixType.NONE) out.add(fix);
        }
        return out;
    }

    // ---- GPX import (GPX 1.1 trkpt) ----

    // Order-independent attributes; self-closing <trkpt .../> accepted.
    private static final Pattern TRKPT_TAG = Pattern.compile(
            "<trkpt\\b([^>]*?)(?:>(.*?)</trkpt>|/>)", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("(lat|lon)\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern TIME = Pattern.compile("<time>([^<]+)</time>");
    private static final Pattern SPEED = Pattern.compile("<speed>([^<]+)</speed>");

    public static List<GpsFix> parseGpx(String xml, String vehicleId, String source) {
        List<GpsFix> out = new ArrayList<>();
        Matcher m = TRKPT_TAG.matcher(xml);
        while (m.find()) {
            try {
                Double lat = null, lon = null;
                Matcher attrs = ATTR.matcher(m.group(1));
                while (attrs.find()) {
                    if ("lat".equals(attrs.group(1))) lat = Double.parseDouble(attrs.group(2));
                    else lon = Double.parseDouble(attrs.group(2));
                }
                if (lat == null || lon == null) continue;
                String inner = m.group(2) == null ? "" : m.group(2);
                Instant time = Instant.now();
                Matcher tm = TIME.matcher(inner);
                if (tm.find()) {
                    try { time = Instant.parse(tm.group(1).trim()); } catch (Exception ignored) { }
                }
                double speed = 0;
                Matcher sm = SPEED.matcher(inner);
                if (sm.find()) {
                    try { speed = Double.parseDouble(sm.group(1).trim()) * 3.6; } catch (NumberFormatException ignored) { }
                }
                out.add(new GpsFix(vehicleId, lon, lat, speed, null, null, Domain.FixType.GPS, time, source));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public static List<GpsFix> readGpxFile(Path file, String vehicleId) throws IOException {
        return parseGpx(Files.readString(file, StandardCharsets.UTF_8), vehicleId, "gpx");
    }

    public static String toGpx(String trackName, List<Domain.TrackingPoint> trail) {
        StringBuilder gpx = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<gpx version=\"1.1\" creator=\"DCLS\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
                + "  <trk><name>").append(escape(trackName)).append("</name><trkseg>\n");
        for (Domain.TrackingPoint p : trail) {
            String time = p.recordedAt().atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            gpx.append("    <trkpt lat=\"").append(p.y()).append("\" lon=\"").append(p.x()).append("\">")
               .append("<time>").append(time).append("</time>");
            if (p.speedKmh() > 0) gpx.append("<speed>").append(String.format(java.util.Locale.US, "%.2f", p.speedKmh() / 3.6)).append("</speed>");
            gpx.append("</trkpt>\n");
        }
        return gpx.append("  </trkseg></trk>\n</gpx>\n").toString();
    }

    private static String escape(String t) {
        return t == null ? "" : t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- Driver mobile feed (HTTP JSON poll, WiFi) ----
    // Expected JSON: [{"vehicleId":"v-1934","lon":114.9,"lat":4.9,"speed":42,"heading":90,"accuracy":8,"ts":"2026-10-13T10:00:00Z"}]

    public static List<GpsFix> parseMobileJson(String json) {
        List<GpsFix> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        Pattern obj = Pattern.compile("\\{([^}]+)\\}");
        Matcher om = obj.matcher(json);
        while (om.find()) {
            String o = om.group(1);
            try {
                String vid = strField(o, "vehicleId");
                Double lon = numField(o, "lon"), lat = numField(o, "lat");
                if (vid == null || lon == null || lat == null) continue;
                Double speed = numField(o, "speed"), hdg = numField(o, "heading"), acc = numField(o, "accuracy");
                Instant time = Instant.now();
                String ts = strField(o, "ts");
                if (ts != null) { try { time = Instant.parse(ts); } catch (Exception ignored) { } }
                Domain.FixType fix = acc != null && acc > 100 ? Domain.FixType.CELL : Domain.FixType.GPS;
                out.add(new GpsFix(vid, lon, lat, speed == null ? 0 : speed, hdg, acc, fix, time, "mobile"));
            } catch (Exception ignored) { }
        }
        return out;
    }

    private static String strField(String o, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(o);
        return m.find() ? m.group(1) : null;
    }

    private static Double numField(String o, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?[\\d.]+)").matcher(o);
        if (!m.find()) return null;
        try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException e) { return null; }
    }

    public static List<GpsFix> pollMobileFeed(String url) throws IOException, InterruptedException {
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("User-Agent", "DCLS tracker").GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
        return parseMobileJson(res.body());
    }

    public static LocalDateTime toLocal(Instant i) {
        return LocalDateTime.ofInstant(i, ZoneId.systemDefault());
    }
}
