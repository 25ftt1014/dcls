package edu.pb.dcls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static edu.pb.dcls.Domain.Stop;

/** Keyword-first address resolution for buyer/customer delivery addresses. */
final class AddressGeocoder {
    private AddressGeocoder() { }

    record GeocodedStop(Stop stop, double confidence, List<Stop> candidates, String method, String note) { }

    static final double CONFIRM_THRESHOLD = 0.7;

    /** Single normalization path: word-boundary alias fixes live in {@link AddressNormalizer}.
     *  (The old substring table here corrupted names containing alias fragments.) */
    static String normalize(String address) {
        return AddressNormalizer.baseClean(address);
    }

    /** Search-first resolution over normalized buyer text (typo-tolerant):
     *  progressive L0..L4 live search wins; district keyword centroid is the last
     *  resort (low confidence, forces map confirm). */
    static GeocodedStop geocode(String address, Map<String, Stop> places) {
        Map<String, Stop> safe = places == null ? AppServices.BruneiPlaces.places() : places;
        if (address == null || address.isBlank()) {
            return new GeocodedStop(safe.get("Gadong"), 0, List.of(safe.get("Gadong")), "FALLBACK", "Empty address — confirm on map.");
        }
        // 1. Live place search over progressive levels (rooftop if possible).
        try {
            AddressNormalizer.Levels lv = AddressNormalizer.levels(address);
            MapRenderer.LevelHits lh = MapRenderer.searchLevels(lv.inOrder());
            if (!lh.hits().isEmpty()) {
                MapRenderer.PlaceHit h = lh.hits().get(0);
                double conf = confidenceFor(h);
                Stop geo = new Stop("geo-" + Math.abs((h.displayName() + h.lon()).hashCode()),
                        h.name(), h.lon(), h.lat());
                List<Stop> cands = new ArrayList<>();
                cands.add(geo);
                for (int i = 1; i < Math.min(3, lh.hits().size()); i++) {
                    MapRenderer.PlaceHit c = lh.hits().get(i);
                    cands.add(new Stop("geo-" + Math.abs((c.displayName() + c.lon()).hashCode()),
                            c.name(), c.lon(), c.lat()));
                }
                Stop keyword = keywordMatch(address, safe);
                if (keyword != null) {
                    double drift = AppServices.BruneiPlaces.haversineKm(keyword.x(), keyword.y(), h.lon(), h.lat());
                    if (drift > 0.5) cands.add(keyword);
                }
                String levelTag = lh.level() <= 1 ? "exact" : lh.level() == 2 ? "detail-stripped"
                        : lh.level() == 3 ? "area" : "street-only";
                return new GeocodedStop(geo, conf, cands, "NOMINATIM",
                        h.displayName() + String.format(" (%.6f, %.6f · %s L%d)", h.lon(), h.lat(), levelTag, lh.level()));
            }
        } catch (Exception ignored) { }
        // 2. Keyword district fallback (centroid — approximate, must confirm).
        KeywordScore ks = scoreDistricts(address, safe);
        Stop fallback = ks.bestKey() == null ? safe.get("Gadong") : safe.get(ks.bestKey());
        List<Stop> cands = ks.top().stream().map(safe::get).filter(s -> s != null).toList();
        if (cands.isEmpty()) cands = List.of(fallback);
        return new GeocodedStop(fallback, Math.min(ks.bestScore(), 0.6), cands,
                "KEYWORD", ks.bestKey() == null
                        ? "No district match — centroid fallback, confirm on map."
                        : "District '" + ks.bestKey() + "' centroid — approximate, confirm on map.");
    }

    private record KeywordScore(String bestKey, double bestScore, List<String> top) { }

    private static KeywordScore scoreDistricts(String address, Map<String, Stop> safe) {
        String norm = normalize(address);
        String[] tokens = norm.split(" ");
        String bestKey = null;
        double bestScore = 0;
        Map<String, Double> scores = new LinkedHashMap<>();
        for (var entry : safe.entrySet()) {
            if (entry.getKey().equals("DCLS Depot")) continue;
            String district = entry.getKey().toLowerCase(Locale.ROOT);
            double score = 0;
            if (norm.contains(district)) score = 0.9;
            else {
                for (String tok : tokens) {
                    if (tok.length() >= 3 && (district.startsWith(tok) || tok.startsWith(district))) {
                        score = Math.max(score, 0.65);
                    }
                }
            }
            scores.put(entry.getKey(), score);
            if (score > bestScore) { bestScore = score; bestKey = entry.getKey(); }
        }
        List<String> top = scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(3).map(Map.Entry::getKey).toList();
        return new KeywordScore(bestKey, bestScore, top);
    }

    private static Stop keywordMatch(String address, Map<String, Stop> safe) {
        KeywordScore ks = scoreDistricts(address, safe);
        return ks.bestKey() == null ? null : safe.get(ks.bestKey());
    }

    private static double confidenceFor(MapRenderer.PlaceHit h) {
        String t = h.type() == null ? "" : h.type().toLowerCase(Locale.ROOT);
        // Rooftop categories auto-pass; street/road NEVER auto-passes: a point on a
        // multi-km highway can be km off, so the dispatcher must drag-confirm.
        if (t.contains("house") || t.contains("building") || t.contains("amenity") || t.contains("shop")) {
            return 0.9;
        }
        if (t.contains("road") || t.contains("street") || t.contains("residential")
                || t.contains("tertiary") || t.contains("trunk") || t.contains("highway")) {
            return 0.65;
        }
        double c = 0.55;
        if (h.placeRank() >= 26) c = 0.65;
        else if (h.placeRank() >= 20) c = 0.7;
        else if (h.placeRank() >= 16) c = 0.68;
        else if (h.placeRank() > 0) c = 0.6;
        else c = 0.55 + Math.min(0.1, h.importance());
        return Math.min(0.68, c);
    }

    /** Nearby pending orders within radiusKm of a point (for batch suggest). */
    static List<Domain.Shipment> nearbyPending(List<Domain.Shipment> all, double lon, double lat, double radiusKm, int limit) {
        List<Domain.Shipment> out = new ArrayList<>();
        for (Domain.Shipment s : all) {
            if (s.status() != Domain.OrderStatus.PENDING && s.status() != Domain.OrderStatus.ASSIGNED) continue;
            Stop g = AppServices.BruneiPlaces.geocodeAddress(s.address(), AppServices.BruneiPlaces.places());
            double d = AppServices.BruneiPlaces.haversineKm(lon, lat, g.x(), g.y());
            if (d <= radiusKm) out.add(s);
            if (out.size() >= limit) break;
        }
        return out;
    }
}
