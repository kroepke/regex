package lol.ohai.regex.automata.dfa.onepass;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransitionTableTest {

    @Test
    void encodeDecodeRoundtrip() {
        int stateId = 42;
        boolean matchWins = true;
        long eps = Epsilons.withLook(Epsilons.withSlot(Epsilons.EMPTY, 3), 5);

        long trans = TransitionTable.encode(stateId, matchWins, eps);

        assertEquals(stateId, TransitionTable.stateId(trans));
        assertTrue(TransitionTable.matchWins(trans));
        assertEquals(eps, TransitionTable.epsilons(trans));
    }

    @Test
    void encodeDecodeNoMatchWins() {
        long trans = TransitionTable.encode(100, false, Epsilons.EMPTY);

        assertEquals(100, TransitionTable.stateId(trans));
        assertFalse(TransitionTable.matchWins(trans));
        assertEquals(Epsilons.EMPTY, TransitionTable.epsilons(trans));
    }

    @Test
    void deadStateIsZero() {
        assertEquals(0, TransitionTable.DEAD);
        // A zero transition decodes to dead state, no match, no epsilons
        long trans = 0L;
        assertEquals(0, TransitionTable.stateId(trans));
        assertFalse(TransitionTable.matchWins(trans));
        assertEquals(Epsilons.EMPTY, TransitionTable.epsilons(trans));
    }

    @Test
    void unsignedShiftForHighStateIds() {
        // Use the maximum 21-bit state ID (all bits set)
        int maxId = TransitionTable.MAX_STATE_ID; // 2097151
        long trans = TransitionTable.encode(maxId, true, Epsilons.EMPTY);

        // This would fail with >> (signed shift) because bit 63 is set
        // (maxId = 0x1FFFFF, shifted left 43 = bits 63-43 all set)
        assertEquals(maxId, TransitionTable.stateId(trans));
        assertTrue(TransitionTable.matchWins(trans));
    }

    @Test
    void patternEpsilonsRoundtrip() {
        int patId = 7;
        long eps = Epsilons.withSlot(Epsilons.EMPTY, 0);
        long patEps = TransitionTable.encodePatternEpsilons(patId, eps);

        assertEquals(patId, TransitionTable.patternId(patEps));
        assertTrue(TransitionTable.hasPattern(patEps));
        assertEquals(eps, TransitionTable.epsilons(patEps));
    }

    @Test
    void noPatternSentinel() {
        long patEps = TransitionTable.encodePatternEpsilons(
                TransitionTable.NO_PATTERN, Epsilons.EMPTY);
        assertFalse(TransitionTable.hasPattern(patEps));
        assertEquals(TransitionTable.NO_PATTERN, TransitionTable.patternId(patEps));
    }

    @Test
    void addStateAndGetSet() {
        TransitionTable tt = new TransitionTable(4);
        // Stride should be next power of 2 >= 5 → 8
        assertEquals(8, tt.stride());
        assertEquals(4, tt.alphabetLen());

        int s0 = tt.addState();
        int s1 = tt.addState();
        assertEquals(0, s0);
        assertEquals(8, s1);
        assertEquals(2, tt.stateCount());

        // All transitions should initially be dead (0)
        for (int c = 0; c < 4; c++) {
            assertEquals(0, tt.get(s0, c));
            assertEquals(0, tt.get(s1, c));
        }

        // Set and get a transition
        long trans = TransitionTable.encode(s1, false, Epsilons.EMPTY);
        tt.set(s0, 2, trans);
        assertEquals(trans, tt.get(s0, 2));
        assertEquals(s1, TransitionTable.stateId(tt.get(s0, 2)));
    }

    @Test
    void patternEpsilonsInTable() {
        TransitionTable tt = new TransitionTable(4);
        int s0 = tt.addState();

        // Initially no pattern
        assertFalse(TransitionTable.hasPattern(tt.getPatternEpsilons(s0)));

        // Set a pattern
        long patEps = TransitionTable.encodePatternEpsilons(3, Epsilons.withSlot(Epsilons.EMPTY, 1));
        tt.setPatternEpsilons(s0, patEps);
        assertEquals(patEps, tt.getPatternEpsilons(s0));
        assertEquals(3, TransitionTable.patternId(tt.getPatternEpsilons(s0)));
    }

    @Test
    void swapStates() {
        TransitionTable tt = new TransitionTable(2);
        int s0 = tt.addState();
        int s1 = tt.addState();

        long t0 = TransitionTable.encode(s1, true, Epsilons.withLook(Epsilons.EMPTY, 0));
        long t1 = TransitionTable.encode(s0, false, Epsilons.withSlot(Epsilons.EMPTY, 2));
        tt.set(s0, 0, t0);
        tt.set(s1, 0, t1);

        long pat0 = TransitionTable.encodePatternEpsilons(1, Epsilons.EMPTY);
        long pat1 = TransitionTable.encodePatternEpsilons(TransitionTable.NO_PATTERN, Epsilons.EMPTY);
        tt.setPatternEpsilons(s0, pat0);
        tt.setPatternEpsilons(s1, pat1);

        tt.swapStates(s0, s1);

        assertEquals(t1, tt.get(s0, 0));
        assertEquals(t0, tt.get(s1, 0));
        assertEquals(pat1, tt.getPatternEpsilons(s0));
        assertEquals(pat0, tt.getPatternEpsilons(s1));
    }

    @Test
    void remapState() {
        TransitionTable tt = new TransitionTable(2);
        int s0 = tt.addState();
        int s1 = tt.addState();
        int s2 = tt.addState();

        // s0 --class0--> s1, s1 --class0--> s1, s2 --class1--> s1
        tt.set(s0, 0, TransitionTable.encode(s1, false, Epsilons.EMPTY));
        tt.set(s1, 0, TransitionTable.encode(s1, false, Epsilons.EMPTY));
        tt.set(s2, 1, TransitionTable.encode(s1, true, Epsilons.withLook(Epsilons.EMPTY, 3)));

        // Remap s1 -> s2
        tt.remapState(s1, s2);

        assertEquals(s2, TransitionTable.stateId(tt.get(s0, 0)));
        assertEquals(s2, TransitionTable.stateId(tt.get(s1, 0)));
        assertEquals(s2, TransitionTable.stateId(tt.get(s2, 1)));
        // match_wins and epsilons should be preserved
        assertTrue(TransitionTable.matchWins(tt.get(s2, 1)));
    }

    @Test
    void shrinkReducesArray() {
        TransitionTable tt = new TransitionTable(2);
        tt.addState();
        tt.addState();
        int expectedLen = 2 * tt.stride();
        tt.shrink();
        assertEquals(expectedLen, tt.rawTable().length);
    }

    @Test
    void strideIsPowerOfTwo() {
        // alphabetLen=1 → need stride >= 2 → stride=2
        assertEquals(2, new TransitionTable(1).stride());
        // alphabetLen=2 → need stride >= 3 → stride=4
        assertEquals(4, new TransitionTable(2).stride());
        // alphabetLen=3 → need stride >= 4 → stride=4
        assertEquals(4, new TransitionTable(3).stride());
        // alphabetLen=4 → need stride >= 5 → stride=8
        assertEquals(8, new TransitionTable(4).stride());
        // alphabetLen=7 → need stride >= 8 → stride=8
        assertEquals(8, new TransitionTable(7).stride());
        // alphabetLen=8 → need stride >= 9 → stride=16
        assertEquals(16, new TransitionTable(8).stride());
    }
}
