package lol.ohai.regex.automata.util;

import java.util.Arrays;

/**
 * Capture group slot storage. Slots are pairs: [start, end] per group.
 * Stored as byte offsets during search, converted to char offsets at API boundary.
 *
 * <p>A value of -1 indicates "not captured".</p>
 */
public final class Captures {
    private final int[] slots; // length = groupCount * 2

    /**
     * Creates a new Captures with the given number of groups. All slots are initialized to -1.
     *
     * @param groupCount the number of capture groups (including group 0 for the overall match)
     */
    public Captures(int groupCount) {
        this.slots = new int[groupCount * 2];
        Arrays.fill(slots, -1);
    }

    private Captures(int[] slots) {
        this.slots = slots;
    }

    /** Returns the number of capture groups. */
    public int groupCount() {
        return slots.length / 2;
    }

    /** Returns the start offset of the given group, or -1 if not captured. */
    public int start(int group) {
        return slots[group * 2];
    }

    /** Returns the end offset of the given group, or -1 if not captured. */
    public int end(int group) {
        return slots[group * 2 + 1];
    }

    /** Returns true if the given group participated in the match. */
    public boolean isMatched(int group) {
        return slots[group * 2] >= 0;
    }

    /** Sets a slot value by slot index. */
    public void set(int slotIndex, int value) {
        slots[slotIndex] = value;
    }

    /** Gets a slot value by slot index. */
    public int get(int slotIndex) {
        return slots[slotIndex];
    }

    /** Resets all slots to -1. */
    public void clear() {
        Arrays.fill(slots, -1);
    }

    /** Creates a deep copy of this Captures. */
    public Captures copy() {
        return new Captures(slots.clone());
    }

    /** Copies all slot values from the other Captures into this one. */
    public void copyFrom(Captures other) {
        System.arraycopy(other.slots, 0, slots, 0, slots.length);
    }

    /** Returns the total number of slots (groupCount * 2). */
    public int slotCount() {
        return slots.length;
    }

    /** Provides direct access to the underlying slots array. */
    public int[] slots() {
        return slots;
    }
}
