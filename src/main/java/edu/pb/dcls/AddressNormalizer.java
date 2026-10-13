package edu.pb.dcls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Buyer-proof normalization for Brunei delivery addresses.
 * Buyer text routinely contains typos (leburaya), unit/floor/block/simpang
 * details absent from map data, and dangling abbreviations (Kg,). This turns
 * one raw address into progressive query levels L0 (full) .. L4 (street only).
 */
final class AddressNormalizer {
    private AddressNormalizer() { }

    /** L0 = full cleaned, L1 = minus unit/floor/block/lot, L2 = minus spg refs,
     *  L3 = street + kampong + postcode words only, L4 = street only. */
    record Levels(String l0, String l1, String l2, String l3, String l4) {
        List<String> inOrder() {
            List<String> out = new ArrayList<>();
            for (String s : List.of(l0, l1, l2, l3, l4)) {
                if (s != null && !s.isBlank() && (out.isEmpty() || !out.get(out.size() - 1).equalsIgnoreCase(s))) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    private static final Map<String, String> WORD_FIXES = new LinkedHashMap<>();
    static {
        WORD_FIXES.put("leburaya", "lebuhraya");
        WORD_FIXES.put("lebuh raya", "lebuhraya");
        WORD_FIXES.put("lebhuraya", "lebuhraya");
        WORD_FIXES.put("jln", "jalan");
        WORD_FIXES.put("kg", "kampong");
        WORD_FIXES.put("kampung", "kampong");
        WORD_FIXES.put("simpang", "spg");
        WORD_FIXES.put("bsb", "bandar seri begawan");
        WORD_FIXES.put("bd", "bandar");
        WORD_FIXES.put("town", "bandar");
        WORD_FIXES.put("gadong central", "gadong");
    }

    /** Known Brunei road stems used to detect the street-only level. */
    private static final List<String> ROAD_MARKERS = List.of(
            "lebuhraya", "jalan", "highway", "jln");

    static String baseClean(String address) {
        if (address == null) return "";
        String text = address.toLowerCase(Locale.ROOT)
                .replaceAll("[.;/\\-:]+", " ")
                .replaceAll(",", " , ")
                .replaceAll("\\s+", " ")
                .trim();
        for (var e : WORD_FIXES.entrySet()) {
            text = text.replaceAll("(?i)\\b" + java.util.regex.Pattern.quote(e.getKey()) + "\\b", e.getValue());
        }
        // Tungku-Jerudong Highway == Lebuhraya Tungku-Jerudong (OSM lists both).
        text = text.replaceAll("(?i)\\btungku\\s+jorudong\\b", "tungku jerudong");
        text = text.replaceAll("(?i)\\btungku\\s+jerudong\\s+highway\\b", "lebuhraya tungku jerudong");
        // Drop dangling abbreviations with no value (", kg ," / trailing "kg").
        text = text.replaceAll("(?i),\\s*kampong\\s*,", " , ")
                .replaceAll("(?i),\\s*kampong\\s*$", "")
                .replaceAll("(?i)\\s+kampong\\s*$", "");
        text = text.replaceAll("\\s*,\\s*,\\s*", " , ").replaceAll("\\s+", " ").trim();
        text = text.replaceAll("^,\\s*|\\s*,$", "").trim();
        return text.replaceAll("\\s+", " ");
    }

    private static String stripUnits(String text) {
        String s = text;
        s = s.replaceAll("(?i)\\bunit\\b[^,]*?(?=,|$)", " ");
        s = s.replaceAll("(?i)\\bno\\.?\\s*[a-z]?\\d*[\\w-]*", " ");
        s = s.replaceAll("(?i)\\b\\d+(st|nd|rd|th)\\s+floor\\b", " ");
        s = s.replaceAll("(?i)\\bfloor\\b[^,]*?(?=,|$)", " ");
        s = s.replaceAll("(?i)\\bblock\\s+[a-z0-9]+", " ");
        s = s.replaceAll("(?i)\\blot\\s+[\\w-]+", " ");
        return tidy(s);
    }

    private static String stripSpg(String text) {
        String s = text;
        s = s.replaceAll("(?i)\\bspg\\s*[\\d][\\w-]*", " ");
        return tidy(s);
    }

    private static String streetPlusArea(String text) {
        // Keep road phrase + kampong/postcode/district hint words only.
        String[] parts = text.split("\\s*,\\s*");
        List<String> keep = new ArrayList<>();
        for (String p : parts) {
            String q = p.trim();
            if (q.isEmpty()) continue;
            String low = q.toLowerCase(Locale.ROOT);
            boolean road = ROAD_MARKERS.stream().anyMatch(low::contains);
            boolean area = low.matches(".*\\b(bg\\d+|be\\d+|bn\\d+|kampong|bandar|gadong|jerudong|berakas|tutong|manggis|mentiri|sengkurong|kiulap)\\b.*");
            if (road || area) keep.add(q);
        }
        if (keep.isEmpty()) return "";
        return tidy(String.join(", ", keep));
    }

    private static String streetOnly(String text) {
        String[] parts = text.split("\\s*,\\s*");
        for (String p : parts) {
            String low = p.toLowerCase(Locale.ROOT);
            if (ROAD_MARKERS.stream().anyMatch(low::contains)) return tidy(p);
        }
        // Fallback: longest comma-part (often the road).
        String best = "";
        for (String p : parts) if (p.trim().length() > best.length()) best = p.trim();
        return tidy(best);
    }

    private static String tidy(String s) {
        return s.replaceAll("\\s*,\\s*,\\s*", " , ").replaceAll("\\s+", " ")
                .replaceAll("^,\\s*|\\s*,$", "").trim().replaceAll("\\s+", " ");
    }

    static Levels levels(String rawAddress) {
        String l0 = baseClean(rawAddress);
        if (l0.isBlank()) return new Levels("", "", "", "", "");
        String l1 = stripUnits(l0);
        String l2 = stripSpg(l1.isBlank() ? l0 : l1);
        String l3 = streetPlusArea(l2.isBlank() ? l0 : l2);
        String l4 = streetOnly(l2.isBlank() ? l0 : l2);
        return new Levels(l0, l1, l2, l3, l4);
    }
}
