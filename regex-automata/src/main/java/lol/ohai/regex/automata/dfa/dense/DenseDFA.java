package lol.ohai.regex.automata.dfa.dense;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.SearchResult;
import lol.ohai.regex.automata.dfa.lazy.Start;
import lol.ohai.regex.automata.util.Input;

/**
 * A pre-compiled dense DFA with all states and transitions eagerly computed
 * at build time.
 *
 * <p>The transition table is a flat {@code int[]} array indexed by
 * {@code stateId + classId}. Transitions preserve MATCH_FLAG (0x8000_0000)
 * from the lazy DFA, which signals "the source state was a match state
 * (delayed match)." Match detection in the search loop uses this flag,
 * matching the lazy DFA's semantics exactly.</p>
 *
 * <p>Match states are also shuffled to a contiguous range at the end of
 * the table (sid >= minMatchState) for fast range-check detection of
 * unconditional match states. For patterns with look-assertions, the
 * MATCH_FLAG-based check is essential for correctness.</p>
 *
 * <p>Dead and quit sentinel states are preserved at their fixed positions
 * ({@code stride} and {@code stride * 2} respectively), matching the layout
 * used by {@link lol.ohai.regex.automata.dfa.lazy.DFACache}.</p>
 *
 * <p>Ref: upstream/regex/regex-automata/src/dfa/dense.rs</p>
 */
public final class DenseDFA {
    private final int[] transTable;
    private final CharClasses charClasses;
    /** Start states: [0..COUNT-1] = unanchored, [COUNT..2*COUNT-1] = anchored. */
    private final int[] startStates;
    private final int minMatchState;  // states >= this are match states
    private final int dead;
    private final int quit;
    private final int stateCount;
    private final int stride;
    private final boolean[][] accelTables;  // indexed by sid / stride; null entry = not accelerated

    DenseDFA(int[] transTable, CharClasses charClasses,
             int[] startStates,
             int minMatchState, int dead, int quit, int stateCount,
             boolean[][] accelTables) {
        this.transTable = transTable;
        this.charClasses = charClasses;
        this.startStates = startStates;
        this.minMatchState = minMatchState;
        this.dead = dead;
        this.quit = quit;
        this.stateCount = stateCount;
        this.stride = charClasses.stride();
        this.accelTables = accelTables;
    }

    /**
     * Returns true if at least one state has an acceleration table.
     */
    public boolean hasAcceleratedStates() {
        for (boolean[] t : accelTables) {
            if (t != null) return true;
        }
        return false;
    }

    /** Returns the flat transition table. Package-private for testing. */
    int[] transTable() { return transTable; }

    /** Returns the char classes used for input classification. */
    public CharClasses charClasses() { return charClasses; }

    /** Returns the start states array (package-private for testing). */
    int[] startStates() { return startStates; }

    /**
     * Selects the correct start state based on the input's position context
     * and anchored flag. Uses {@link Start#from} to classify the look-behind
     * context at the search start position.
     *
     * <p>Ref: upstream/regex/regex-automata/src/util/start.rs:141-158</p>
     */
    private int startState(Input input) {
        Start start = Start.from(input.haystack(), input.start());
        int idx = input.isAnchored() ? Start.COUNT + start.ordinal() : start.ordinal();
        return startStates[idx];
    }

    /** Returns the total number of states (including dead and quit). */
    public int stateCount() { return stateCount; }

    /** Returns the dead state ID (stride). */
    public int dead() { return dead; }

    /** Returns the quit state ID (stride * 2). */
    public int quit() { return quit; }

    /**
     * Returns the minimum match state ID. Any state with
     * {@code sid >= minMatchState} is a match state.
     */
    public int minMatchState() { return minMatchState; }

    /** Returns the stride (power-of-2 >= alphabet size). */
    public int stride() { return stride; }

    /**
     * Returns true if the given state ID represents a match state.
     */
    public boolean isMatch(int sid) {
        return sid >= minMatchState;
    }

    /**
     * Forward search for the end position of the leftmost-first match.
     *
     * <p>All transitions are pre-computed in the flat {@code transTable}, so
     * there are no UNKNOWN transitions and no {@code computeNextState} calls.
     * Transitions preserve MATCH_FLAG from the lazy DFA: when a transition
     * result has the high bit set (negative as signed int), the SOURCE state
     * was a match state and we record a match at the current position.</p>
     *
     * <p>This exactly mirrors the lazy DFA's delayed-match semantics.
     * Ref: upstream/regex/regex-automata/src/dfa/search.rs:45-186</p>
     *
     * @param input the search input (haystack + bounds + anchored flag)
     * @return primitive-encoded search result; decode with {@link SearchResult}
     */
    public long searchFwd(Input input) {
        final char[] haystack = input.haystack();
        int at = input.start();
        final int end = input.end();

        int sid = startState(input);
        if (sid == dead) return SearchResult.NO_MATCH;

        final int[] tt = transTable;
        final CharClasses cc = charClasses;
        final int d = dead;
        final int q = quit;

        int lastMatchEnd = -1;

        // Main search loop. Transitions may carry MATCH_FLAG (negative as signed int).
        // The inner unrolled loop stays in the fast path for non-special transitions
        // (nextSid > quit and nextSid >= 0). Any special condition breaks out.
        final boolean[][] accel = accelTables;
        while (at < end) {
            // Acceleration: fast-scan self-looping states.
            // Self-loop transitions don't change the DFA state, so we scan
            // until an escape char (non-self-loop) is found. Match recording
            // is deferred to the dispatch after the escape transition, or to
            // handleRightEdge if we scan to the end.
            boolean[] escTable = accel[sid / stride];
            if (escTable != null) {
                // Scan: 1 array load per char
                while (at < end) {
                    char c = haystack[at];
                    if (c >= 128 || escTable[c]) break;
                    at++;
                }
                if (at >= end) break;
            }

            // Inner 4x unrolled loop. Takes a transition and immediately checks
            // the result. Breaks out on special states:
            //   - nextSid < 0: MATCH_FLAG set (delayed match from source state)
            //   - nextSid <= quit: dead or quit
            //   - at + 3 >= end: need to switch to single-step for correct EOI
            while (at < end) {
                sid = tt[sid + cc.classify(haystack[at])];
                if (sid < 0 || sid <= q || at + 3 >= end) { break; }
                at++;

                sid = tt[sid + cc.classify(haystack[at])];
                if (sid < 0 || sid <= q) { break; }
                at++;

                sid = tt[sid + cc.classify(haystack[at])];
                if (sid < 0 || sid <= q) { break; }
                at++;

                sid = tt[sid + cc.classify(haystack[at])];
                if (sid < 0 || sid <= q) { break; }
                at++;
            }

            // Dispatch on the current state (result of the last transition).
            if (sid < 0) {
                // MATCH_FLAG set: the source state was a match state.
                // Record match at current position (delayed match).
                lastMatchEnd = at;
                sid = sid & 0x7FFF_FFFF; // strip MATCH_FLAG
                at++;
            } else if (sid == d) {
                break;
            } else if (sid == q) {
                return SearchResult.gaveUp(at);
            } else {
                at++;
            }
        }

        // Right-edge transition: handle EOI or char just past the search span
        // for correct look-ahead context ($ and \b assertions).
        // Ref: upstream/regex/regex-automata/src/dfa/search.rs:576-602 (eoi_fwd)
        lastMatchEnd = handleRightEdge(sid, haystack, end, lastMatchEnd);

        if (lastMatchEnd >= 0) return SearchResult.match(lastMatchEnd);
        return SearchResult.NO_MATCH;
    }

    /**
     * Process the right-edge transition after the forward search main loop.
     *
     * <p>Takes the EOI or right-context transition from the current state.
     * If the transition has MATCH_FLAG set (negative as signed int), the
     * current state was a match state and the match is confirmed by the
     * right-edge context (EOI or look-ahead char). This is the delayed-match
     * pattern: the match is one transition behind.</p>
     *
     * <p>Ref: upstream/regex/regex-automata/src/dfa/search.rs:576-602</p>
     *
     * @return updated lastMatchEnd (-1 if no match)
     */
    private int handleRightEdge(int sid, char[] haystack, int end,
                                 int lastMatchEnd) {
        if (sid != dead && sid != quit) {
            int rightEdgeSid;
            if (end < haystack.length) {
                rightEdgeSid = transTable[sid + charClasses.classify(haystack[end])];
            } else {
                rightEdgeSid = transTable[sid + charClasses.eoiClass()];
            }
            // MATCH_FLAG on the right-edge transition signals a delayed match
            // from the current state (look-ahead resolved at EOI/right-context).
            if (rightEdgeSid < 0) {
                return end;
            }
        }
        return lastMatchEnd;
    }
}
