package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.syntax.hir.LookKind;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LookSetTest {

    @Test void emptySetContainsNothing() {
        for (LookKind k : LookKind.values()) {
            assertFalse(LookSet.EMPTY.contains(k));
        }
        assertTrue(LookSet.EMPTY.isEmpty());
    }

    @Test void insertAndContains() {
        LookSet s = LookSet.EMPTY.insert(LookKind.START_TEXT);
        assertTrue(s.contains(LookKind.START_TEXT));
        assertFalse(s.contains(LookKind.END_TEXT));
        assertFalse(s.isEmpty());
    }

    @Test void insertMultiple() {
        LookSet s = LookSet.EMPTY
                .insert(LookKind.START_TEXT)
                .insert(LookKind.END_TEXT)
                .insert(LookKind.WORD_BOUNDARY_ASCII);
        assertTrue(s.contains(LookKind.START_TEXT));
        assertTrue(s.contains(LookKind.END_TEXT));
        assertTrue(s.contains(LookKind.WORD_BOUNDARY_ASCII));
        assertFalse(s.contains(LookKind.START_LINE));
    }

    @Test void union() {
        LookSet a = LookSet.of(LookKind.START_TEXT);
        LookSet b = LookSet.of(LookKind.END_TEXT);
        LookSet u = a.union(b);
        assertTrue(u.contains(LookKind.START_TEXT));
        assertTrue(u.contains(LookKind.END_TEXT));
    }

    @Test void intersect() {
        LookSet a = LookSet.EMPTY.insert(LookKind.START_TEXT).insert(LookKind.END_TEXT);
        LookSet b = LookSet.EMPTY.insert(LookKind.END_TEXT).insert(LookKind.START_LINE);
        LookSet i = a.intersect(b);
        assertFalse(i.contains(LookKind.START_TEXT));
        assertTrue(i.contains(LookKind.END_TEXT));
        assertFalse(i.contains(LookKind.START_LINE));
    }

    @Test void subtract() {
        LookSet a = LookSet.EMPTY.insert(LookKind.START_TEXT).insert(LookKind.END_TEXT);
        LookSet b = LookSet.of(LookKind.END_TEXT);
        LookSet s = a.subtract(b);
        assertTrue(s.contains(LookKind.START_TEXT));
        assertFalse(s.contains(LookKind.END_TEXT));
    }

    @Test void allOrdinalsDistinct() {
        int combined = 0;
        for (LookKind k : LookKind.values()) {
            int bit = k.asBit();
            assertEquals(0, combined & bit, "Duplicate bit for " + k);
            combined |= bit;
        }
        assertEquals(18, LookKind.values().length);
    }

    @Test void containsWord() {
        assertFalse(LookSet.EMPTY.containsWord());
        assertTrue(LookSet.of(LookKind.WORD_BOUNDARY_ASCII).containsWord());
        assertTrue(LookSet.of(LookKind.WORD_START_UNICODE).containsWord());
        assertFalse(LookSet.of(LookKind.START_TEXT).containsWord());
    }

    @Test void containsCrlf() {
        assertFalse(LookSet.EMPTY.containsCrlf());
        assertTrue(LookSet.of(LookKind.START_LINE_CRLF).containsCrlf());
        assertTrue(LookSet.of(LookKind.END_LINE_CRLF).containsCrlf());
        assertFalse(LookSet.of(LookKind.START_LINE).containsCrlf());
    }
}
