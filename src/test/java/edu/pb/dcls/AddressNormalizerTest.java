package edu.pb.dcls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AddressNormalizerTest {

    private static final String REAL =
            "Unit No F2, First Floor, Block A, Spg 396-26-1, Leburaya Tungku-Jerudong, Kg, Bandar Seri Begawan BG1321";

    @Test
    void buyerTypoFixed() {
        String base = AddressNormalizer.baseClean(REAL);
        assertTrue(base.contains("lebuhraya"), "leburaya typo must become lebuhraya: " + base);
        assertFalse(base.contains("leburaya"));
    }

    @Test
    void danglingKgDropped() {
        String base = AddressNormalizer.baseClean(REAL);
        // ", kg ," with no kampong name must not survive as a query token.
        assertFalse(base.matches("(?i).*[,\\s]kampong\\s*,.*"));
    }

    @Test
    void levelsStripDetailsProgressively() {
        AddressNormalizer.Levels lv = AddressNormalizer.levels(REAL);
        assertFalse(lv.l0().isBlank());
        // L1 must drop unit/floor/block; L2 must drop the spg ref.
        assertFalse(lv.l1().toLowerCase().contains("unit"), lv.l1());
        assertFalse(lv.l1().toLowerCase().contains("floor"), lv.l1());
        assertFalse(lv.l2().toLowerCase().contains("spg"), lv.l2());
        // Street survives to the last levels.
        assertTrue(lv.l4().contains("lebuhraya"), lv.l4());
        assertTrue(lv.l4().contains("tungku"), lv.l4());
    }

    @Test
    void levelsAreOrderedAndDeduped() {
        var order = AddressNormalizer.levels(REAL).inOrder();
        assertFalse(order.isEmpty());
        assertEquals(order.size(), order.stream().distinct().count());
    }

    @Test
    void emptyAddressGivesBlankLevels() {
        var lv = AddressNormalizer.levels("  ");
        assertTrue(lv.inOrder().isEmpty());
    }
}
