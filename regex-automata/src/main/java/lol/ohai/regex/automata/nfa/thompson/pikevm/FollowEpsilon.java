package lol.ohai.regex.automata.nfa.thompson.pikevm;

/**
 * A frame on the epsilon closure stack.
 *
 * <p>During epsilon closure, we either explore a new state or restore a
 * previously saved capture slot value. This sealed interface mirrors the
 * upstream Rust {@code FollowEpsilon} enum.</p>
 */
sealed interface FollowEpsilon {

    /**
     * Explore the epsilon transitions out of the given state.
     *
     * @param stateId the NFA state to explore
     */
    record Explore(int stateId) implements FollowEpsilon {}

    /**
     * Restore a capture slot to its previous value.
     * This is pushed before modifying a slot so that the original value is
     * restored when backtracking through alternation branches.
     *
     * @param slot   the slot index to restore
     * @param offset the previous value of the slot (-1 if absent)
     */
    record RestoreCapture(int slot, int offset) implements FollowEpsilon {}
}
