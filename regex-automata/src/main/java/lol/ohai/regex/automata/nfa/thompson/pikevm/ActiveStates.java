package lol.ohai.regex.automata.nfa.thompson.pikevm;

import lol.ohai.regex.automata.util.SparseSet;

/**
 * Tracks the set of active NFA states along with their capture slot values.
 *
 * <p>Each active state has a row in the slot table that records the current
 * capture group offsets for that particular "thread" of execution.</p>
 */
final class ActiveStates {
    /** The sparse set of active state IDs, in priority order. */
    final SparseSet set;

    /**
     * Slot table: a 2D array flattened to 1D.
     * Row i corresponds to state ID {@code set.get(i)} (but indexed by state ID, not insertion order).
     * Each row has {@code slotCount} entries.
     */
    final int[] slotTable;

    /** Number of slots per state (groupCount * 2). */
    final int slotCount;

    /** Number of states (capacity of the sparse set). */
    final int stateCount;

    ActiveStates(int stateCount, int slotCount) {
        this.set = new SparseSet(stateCount);
        this.slotCount = slotCount;
        this.stateCount = stateCount;
        this.slotTable = new int[stateCount * slotCount];
    }

    /**
     * Returns the slot sub-array for the given state ID.
     * The returned offset is the start index into {@code slotTable};
     * the length is {@code slotCount}.
     */
    int slotOffset(int stateId) {
        return stateId * slotCount;
    }

    /**
     * Copies slots from the source array into the slot table row for the given state.
     */
    void copySlots(int stateId, int[] src, int srcOffset) {
        int offset = stateId * slotCount;
        System.arraycopy(src, srcOffset, slotTable, offset, slotCount);
    }

    /**
     * Copies slots from this state's row to the destination array.
     */
    void readSlots(int stateId, int[] dst, int dstOffset) {
        int offset = stateId * slotCount;
        System.arraycopy(slotTable, offset, dst, dstOffset, slotCount);
    }

    /**
     * Clears the set (O(1)) but does not clear the slot table.
     */
    void clear() {
        set.clear();
    }
}
