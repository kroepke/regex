package lol.ohai.regex.automata.nfa.thompson;

import lol.ohai.regex.syntax.hir.LookKind;

/**
 * A single state in a Thompson NFA.
 *
 * <p>Each variant corresponds to a different kind of NFA state. The state
 * graph is constructed by the {@link Builder} and stored in the {@link NFA}.</p>
 */
public sealed interface State {

    /**
     * Matches a single byte in range [start, end] inclusive, then transitions to next.
     *
     * @param start the inclusive lower bound of the byte range
     * @param end   the inclusive upper bound of the byte range
     * @param next  the target state ID on a successful match
     */
    record ByteRange(int start, int end, int next) implements State {}

    /**
     * Multiple byte range transitions from this state.
     * Each transition is tried in order; the first matching one is taken.
     *
     * @param transitions the ordered list of transitions
     */
    record Sparse(Transition[] transitions) implements State {}

    /**
     * Dense byte transition table with 256 entries.
     * Index by byte value to get the next state ID; -1 means no transition.
     *
     * @param next the transition table, indexed by byte value
     */
    record Dense(int[] next) implements State {}

    /**
     * A zero-width assertion. If the assertion is satisfied at the current
     * position, transitions to next.
     *
     * @param look the kind of look-around assertion
     * @param next the target state ID if the assertion holds
     */
    record Look(LookKind look, int next) implements State {}

    /**
     * Epsilon transition to multiple alternatives, ordered by priority.
     * The first alternative has highest priority (used for greedy vs. lazy).
     *
     * @param alternates the state IDs of the alternatives
     */
    record Union(int[] alternates) implements State {}

    /**
     * Optimized {@link Union} for the common two-alternative case.
     *
     * @param alt1 the first (higher priority) alternative state ID
     * @param alt2 the second (lower priority) alternative state ID
     */
    record BinaryUnion(int alt1, int alt2) implements State {}

    /**
     * Records a capture group slot, then transitions to next.
     * Used to track the start and end positions of capture groups.
     *
     * @param next       the target state ID
     * @param groupIndex the capture group index (0 = entire match)
     * @param slotIndex  the slot index (groupIndex * 2 for start, groupIndex * 2 + 1 for end)
     */
    record Capture(int next, int groupIndex, int slotIndex) implements State {}

    /**
     * Indicates a match for the given pattern ID.
     * In a single-pattern NFA, the pattern ID is always 0.
     *
     * @param patternId the pattern that matched
     */
    record Match(int patternId) implements State {}

    /**
     * A state that never transitions anywhere (dead state).
     */
    record Fail() implements State {}
}
