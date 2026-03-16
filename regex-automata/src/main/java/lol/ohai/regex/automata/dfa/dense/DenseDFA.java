package lol.ohai.regex.automata.dfa.dense;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.SearchResult;
import lol.ohai.regex.automata.util.Input;

/**
 * A pre-compiled dense DFA with all states and transitions eagerly computed
 * at build time.
 *
 * <p>The transition table is a flat {@code int[]} array indexed by
 * {@code stateId + classId}. Match states are shuffled to a contiguous
 * range at the end of the table, so match detection is a simple
 * {@code sid >= minMatchState} comparison (no flag bits needed).</p>
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
    private final int startAnchored;
    private final int startUnanchored;
    private final int minMatchState;  // states >= this are match states
    private final int dead;
    private final int quit;
    private final int stateCount;
    private final int stride;

    DenseDFA(int[] transTable, CharClasses charClasses,
             int startAnchored, int startUnanchored,
             int minMatchState, int dead, int quit, int stateCount) {
        this.transTable = transTable;
        this.charClasses = charClasses;
        this.startAnchored = startAnchored;
        this.startUnanchored = startUnanchored;
        this.minMatchState = minMatchState;
        this.dead = dead;
        this.quit = quit;
        this.stateCount = stateCount;
        this.stride = charClasses.stride();
    }

    /** Returns the flat transition table. Package-private for searchFwd. */
    int[] transTable() { return transTable; }

    /** Returns the char classes used for input classification. */
    public CharClasses charClasses() { return charClasses; }

    /** Returns the anchored start state ID. */
    public int startAnchored() { return startAnchored; }

    /** Returns the unanchored start state ID. */
    public int startUnanchored() { return startUnanchored; }

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
     * <p>All transitions are pre-computed in the flat {@code transTable}, so there
     * are no UNKNOWN transitions and no {@code computeNextState} calls. Match
     * states are detected by {@code sid >= minMatchState} range check.</p>
     *
     * <p>Matches are delayed by one character, matching the lazy DFA semantics:
     * when we transition INTO a match state, the match end position is the
     * current {@code pos} (already one past the last char consumed). This
     * is equivalent to upstream's pattern of recording {@code at} before
     * incrementing it.</p>
     *
     * <p>Ref: upstream/regex/regex-automata/src/dfa/search.rs:45-186</p>
     *
     * @param input the search input (haystack + bounds + anchored flag)
     * @return primitive-encoded search result; decode with {@link SearchResult}
     */
    public long searchFwd(Input input) {
        final char[] haystack = input.haystack();
        int at = input.start();
        final int end = input.end();

        int sid = input.isAnchored() ? startAnchored : startUnanchored;
        if (sid == dead) return SearchResult.NO_MATCH;

        final int[] tt = transTable;
        final CharClasses cc = charClasses;
        final int mms = minMatchState;
        final int d = dead;
        final int q = quit;

        int lastMatchEnd = -1;

        // Main search loop. Follows upstream pattern: take transition, then
        // check for special state, then advance position.
        // Ref: upstream/regex/regex-automata/src/dfa/search.rs:83-183
        while (at < end) {
            // Inner 4x unrolled loop. Takes a transition and immediately checks
            // the result. Breaks out on special states (dead, quit, match).
            // The check `at + 3 >= end` ensures we break to the outer dispatch
            // when fewer than 4 chars remain, so we get correct match recording.
            //
            // Unlike the lazy DFA, we check BOTH <= quit (dead/quit) and
            // >= minMatchState (match) as break conditions.
            while (at < end) {
                sid = tt[sid + cc.classify(haystack[at])];
                if (sid <= q || sid >= mms || at + 3 >= end) { break; }
                at++;

                sid = tt[sid + cc.classify(haystack[at])];
                if (sid <= q || sid >= mms) { break; }
                at++;

                sid = tt[sid + cc.classify(haystack[at])];
                if (sid <= q || sid >= mms) { break; }
                at++;

                sid = tt[sid + cc.classify(haystack[at])];
                if (sid <= q || sid >= mms) { break; }
                at++;
            }

            // Dispatch on the current state (result of the last transition).
            // Ref: upstream/regex/regex-automata/src/dfa/search.rs:125-181
            if (sid >= mms) {
                // Match state: record match end position (exclusive).
                // `at` is the position of the char just consumed. The match
                // extends through this position, so the exclusive end is at+1.
                // This aligns with the lazy DFA convention where lastMatchEnd
                // is the exclusive end of the match.
                lastMatchEnd = at + 1;
            } else if (sid == d) {
                break;
            } else if (sid == q) {
                return SearchResult.gaveUp(at);
            }
            at++;
        }

        // Right-edge transition: handle EOI or char just past the search span
        // for correct look-ahead context ($ and \b assertions).
        // Ref: upstream/regex/regex-automata/src/dfa/search.rs:184 (eoi_fwd)
        lastMatchEnd = handleRightEdge(sid, haystack, end, lastMatchEnd);

        if (lastMatchEnd >= 0) return SearchResult.match(lastMatchEnd);
        return SearchResult.NO_MATCH;
    }

    /**
     * Process the right-edge transition after the forward search main loop.
     *
     * <p>In the dense DFA, match-flagged transitions to dead (e.g., EOI from
     * a match state) have their MATCH_FLAG stripped during table construction.
     * So we detect the match by checking if the CURRENT state is a match state
     * before taking the right-edge transition. If the current state is a match
     * state, the match end is {@code end}. We also check the EOI destination
     * in case it leads to another match state (unusual but possible).</p>
     *
     * @return updated lastMatchEnd (-1 if no match)
     */
    private int handleRightEdge(int sid, char[] haystack, int end,
                                 int lastMatchEnd) {
        if (sid != dead && sid != quit) {
            // Check if the current state is a match state — this captures
            // the delayed match that would be signaled by MATCH_FLAG on the
            // transition target in the lazy DFA.
            if (sid >= minMatchState) {
                lastMatchEnd = end;
            }
            // Also take the right-edge transition to check for EOI matches.
            int rightEdgeSid;
            if (end < haystack.length) {
                rightEdgeSid = transTable[sid + charClasses.classify(haystack[end])];
            } else {
                rightEdgeSid = transTable[sid + charClasses.eoiClass()];
            }
            if (rightEdgeSid >= minMatchState) {
                return end;
            }
        }
        return lastMatchEnd;
    }
}
