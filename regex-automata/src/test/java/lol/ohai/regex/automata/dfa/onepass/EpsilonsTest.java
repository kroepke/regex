package lol.ohai.regex.automata.dfa.onepass;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EpsilonsTest {

    @Test
    void emptyHasNoLooksOrSlots() {
        long eps = Epsilons.EMPTY;
        assertEquals(0, Epsilons.looks(eps));
        assertEquals(0, Epsilons.slots(eps));
        assertTrue(Epsilons.looksEmpty(eps));
    }

    @Test
    void withLookSetsSingleBit() {
        long eps = Epsilons.withLook(Epsilons.EMPTY, 0);
        assertEquals(1, Epsilons.looks(eps));
        assertFalse(Epsilons.looksEmpty(eps));
        assertEquals(0, Epsilons.slots(eps));
    }

    @Test
    void withLookSetsHighBit() {
        long eps = Epsilons.withLook(Epsilons.EMPTY, 17);
        assertEquals(1 << 17, Epsilons.looks(eps));
        assertFalse(Epsilons.looksEmpty(eps));
    }

    @Test
    void withLookMultipleBits() {
        long eps = Epsilons.EMPTY;
        eps = Epsilons.withLook(eps, 0);
        eps = Epsilons.withLook(eps, 5);
        eps = Epsilons.withLook(eps, 17);
        assertEquals((1 << 0) | (1 << 5) | (1 << 17), Epsilons.looks(eps));
        assertEquals(0, Epsilons.slots(eps));
    }

    @Test
    void withSlotSetsSingleBit() {
        long eps = Epsilons.withSlot(Epsilons.EMPTY, 0);
        assertEquals(1, Epsilons.slots(eps));
        assertEquals(0, Epsilons.looks(eps));
        assertTrue(Epsilons.looksEmpty(eps));
    }

    @Test
    void withSlotSetsHighBit() {
        long eps = Epsilons.withSlot(Epsilons.EMPTY, 23);
        assertEquals(1 << 23, Epsilons.slots(eps));
    }

    @Test
    void withSlotMultipleBits() {
        long eps = Epsilons.EMPTY;
        eps = Epsilons.withSlot(eps, 0);
        eps = Epsilons.withSlot(eps, 3);
        eps = Epsilons.withSlot(eps, 23);
        assertEquals((1 << 0) | (1 << 3) | (1 << 23), Epsilons.slots(eps));
        assertEquals(0, Epsilons.looks(eps));
    }

    @Test
    void combinedLooksAndSlots() {
        long eps = Epsilons.EMPTY;
        eps = Epsilons.withLook(eps, 2);
        eps = Epsilons.withLook(eps, 10);
        eps = Epsilons.withSlot(eps, 1);
        eps = Epsilons.withSlot(eps, 5);

        assertEquals((1 << 2) | (1 << 10), Epsilons.looks(eps));
        assertEquals((1 << 1) | (1 << 5), Epsilons.slots(eps));
        assertFalse(Epsilons.looksEmpty(eps));
    }

    @Test
    void applySlotsWritesPositions() {
        long eps = Epsilons.EMPTY;
        eps = Epsilons.withSlot(eps, 0);
        eps = Epsilons.withSlot(eps, 2);
        eps = Epsilons.withSlot(eps, 4);

        int[] slots = new int[]{-1, -1, -1, -1, -1};
        Epsilons.applySlots(eps, 42, slots, slots.length);

        assertEquals(42, slots[0]);
        assertEquals(-1, slots[1]);
        assertEquals(42, slots[2]);
        assertEquals(-1, slots[3]);
        assertEquals(42, slots[4]);
    }

    @Test
    void applySlotsRespectsLen() {
        long eps = Epsilons.EMPTY;
        eps = Epsilons.withSlot(eps, 0);
        eps = Epsilons.withSlot(eps, 3);

        int[] slots = new int[]{-1, -1, -1, -1};
        // Only active len 2, so slot 3 should not be written
        Epsilons.applySlots(eps, 99, slots, 2);

        assertEquals(99, slots[0]);
        assertEquals(-1, slots[1]);
        assertEquals(-1, slots[2]);
        assertEquals(-1, slots[3]);
    }

    @Test
    void slotsDoNotOverlapWithLooks() {
        // Set all 18 look bits
        long eps = Epsilons.EMPTY;
        for (int i = 0; i < 18; i++) {
            eps = Epsilons.withLook(eps, i);
        }
        assertEquals(0, Epsilons.slots(eps));
        assertEquals((1 << 18) - 1, Epsilons.looks(eps));

        // Set all 24 slot bits
        long eps2 = Epsilons.EMPTY;
        for (int i = 0; i < 24; i++) {
            eps2 = Epsilons.withSlot(eps2, i);
        }
        assertEquals(0, Epsilons.looks(eps2));
        assertEquals((1 << 24) - 1, Epsilons.slots(eps2));
    }
}
