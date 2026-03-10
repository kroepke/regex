package lol.ohai.regex.automata.nfa.thompson;

/**
 * A reference to a fragment of a Thompson NFA under construction.
 *
 * <p>The {@code start} is the entry state ID. The {@code end} is a state ID
 * whose outgoing transition(s) have not yet been wired up (a "dangling" state).
 * After the fragment is connected to whatever comes next, the end state is
 * patched via {@link Builder#patch(int, int)}.</p>
 *
 * @param start the entry state ID of this fragment
 * @param end   the exit state ID (to be patched)
 */
record ThompsonRef(int start, int end) {}
