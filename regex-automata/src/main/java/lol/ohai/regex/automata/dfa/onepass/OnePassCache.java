package lol.ohai.regex.automata.dfa.onepass;

import java.util.Arrays;

/**
 * Scratch space for one-pass DFA search: holds the explicit capture slot
 * positions during a search pass.
 *
 * <p>Instances are reusable across searches via {@link #setup(int)}.
 */
public final class OnePassCache {
    private final int[] explicitSlots;
    private int activeLen;

    /**
     * Creates a new cache with capacity for the given number of explicit slots,
     * capped at {@link Epsilons#MAX_SLOTS}.
     *
     * @param maxExplicitSlots the desired number of slots
     */
    public OnePassCache(int maxExplicitSlots) {
        this.explicitSlots = new int[Math.min(maxExplicitSlots, Epsilons.MAX_SLOTS)];
    }

    /**
     * Resets the cache for a new search, activating {@code len} slots
     * (capped at capacity) and filling them with {@code -1}.
     *
     * @param len the number of active slots for this search
     */
    public void setup(int len) {
        this.activeLen = Math.min(len, explicitSlots.length);
        Arrays.fill(explicitSlots, 0, activeLen, -1);
    }

    /** Returns the backing slot array. */
    public int[] explicitSlots() { return explicitSlots; }

    /** Returns the number of currently active slots. */
    public int activeLen() { return activeLen; }

    /** Returns the total capacity of the slot array. */
    public int capacity() { return explicitSlots.length; }
}
