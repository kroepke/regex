package lol.ohai.regex.automata.nfa.thompson;

import java.util.List;

/**
 * A compiled Thompson NFA.
 *
 * <p>An NFA is a directed graph of {@link State}s. Each state is identified
 * by an integer ID (its index in the states array). The NFA has two start
 * states: one for anchored searches (must match at the current position) and
 * one for unanchored searches (may match anywhere in the input).</p>
 */
public final class NFA {
    private final State[] states;
    private final int startAnchored;
    private final int startUnanchored;
    private final int captureSlotCount;
    private final int groupCount;
    private final List<String> groupNames;

    /**
     * Creates a new NFA. Use {@link Builder} to construct instances.
     *
     * @param states           the array of NFA states
     * @param startAnchored    the start state ID for anchored searches
     * @param startUnanchored  the start state ID for unanchored searches
     * @param captureSlotCount the total number of capture slots (groupCount * 2)
     * @param groupCount       the number of capture groups (including group 0)
     * @param groupNames       the capture group names (null entries for unnamed groups)
     */
    NFA(State[] states, int startAnchored, int startUnanchored,
        int captureSlotCount, int groupCount, List<String> groupNames) {
        this.states = states;
        this.startAnchored = startAnchored;
        this.startUnanchored = startUnanchored;
        this.captureSlotCount = captureSlotCount;
        this.groupCount = groupCount;
        this.groupNames = groupNames;
    }

    /**
     * Returns the total number of states in this NFA.
     */
    public int stateCount() {
        return states.length;
    }

    /**
     * Returns the state with the given ID.
     *
     * @param id the state ID
     * @return the state
     */
    public State state(int id) {
        return states[id];
    }

    /**
     * Returns the start state ID for anchored searches.
     */
    public int startAnchored() {
        return startAnchored;
    }

    /**
     * Returns the start state ID for unanchored searches.
     */
    public int startUnanchored() {
        return startUnanchored;
    }

    /**
     * Returns the total number of capture slots.
     * This is equal to {@code groupCount() * 2} (start and end for each group).
     */
    public int captureSlotCount() {
        return captureSlotCount;
    }

    /**
     * Returns the number of capture groups, including group 0 (the entire match).
     */
    public int groupCount() {
        return groupCount;
    }

    /**
     * Returns the list of capture group names.
     * Unnamed groups have a null entry at their index.
     */
    public List<String> groupNames() {
        return groupNames;
    }
}
