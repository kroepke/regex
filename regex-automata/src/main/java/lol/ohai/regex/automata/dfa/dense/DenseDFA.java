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
 * {@code stateId + classId}. Transitions are plain state IDs with no flags.
 * Match states are shuffled to a contiguous range at the end of the table
 * so that {@code sid >= minMatchState} indicates a match state. This is a
 * per-state property determined at build time via StateContent.isMatch().</p>
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
    private final char[] accelEscapeChars;  // indexed by sid / stride; '\0' means use table scan
    private final int deadMatch;  // synthetic dead-match state, or -1

    DenseDFA(int[] transTable, CharClasses charClasses,
             int[] startStates,
             int minMatchState, int dead, int quit, int stateCount,
             boolean[][] accelTables, char[] accelEscapeChars, int deadMatch) {
        this.transTable = transTable;
        this.charClasses = charClasses;
        this.startStates = startStates;
        this.minMatchState = minMatchState;
        this.dead = dead;
        this.quit = quit;
        this.stateCount = stateCount;
        this.stride = charClasses.stride();
        this.accelTables = accelTables;
        this.accelEscapeChars = accelEscapeChars;
        this.deadMatch = deadMatch;
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
     * Match detection uses range-based checking: any state with
     * {@code sid >= minMatchState} is a match state. This is determined at
     * build time by shuffling match states to the end of the state space.</p>
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

        int sid = startState(input);
        if (sid == dead) return SearchResult.NO_MATCH;

        final int[] tt = transTable;
        final CharClasses cc = charClasses;
        final int q = quit;
        final int mms = minMatchState;
        final int dm = deadMatch;

        int lastMatchEnd = -1;

        // Check if start state is a match state (not dead-match)
        if (sid >= mms && sid != dm) lastMatchEnd = at;

        final boolean[][] accel = accelTables;
        final char[] escChars = accelEscapeChars;
        // Lazily computed String view of haystack; only used when indexOf acceleration fires.
        String haystackString = null;
        while (at < end) {
            // Acceleration: fast-scan self-looping states.
            // Self-loop transitions don't change the DFA state, so we scan
            // until an escape char (non-self-loop) is found.
            boolean[] escTable = accel[sid / stride];
            if (escTable != null) {
                // If we're in a match state, extend match through the self-loop scan
                if (sid >= mms) lastMatchEnd = at;

                char esc = escChars[sid / stride];
                if (esc != 0) {
                    // Single-escape-char state: use SIMD-intrinsified String.indexOf
                    if (haystackString == null) haystackString = input.haystackStr();
                    int found = haystackString.indexOf(esc, at);
                    if (found < 0 || found >= end) {
                        at = end;
                    } else {
                        at = found;
                    }
                } else {
                    // Multi-escape state: table-based scan (1 array load per char)
                    while (at < end) {
                        char c = haystack[at];
                        if (c >= 128 || escTable[c]) break;
                        at++;
                    }
                }

                if (sid >= mms) lastMatchEnd = at;
                if (at >= end) break;
            }

            // Match state single-step: record match at current position,
            // then take one transition. The lazy DFA uses MATCH_FLAG on
            // transitions FROM match states; we replicate this by recording
            // the match BEFORE taking the transition. This avoids adding
            // per-transition overhead to the inner unrolled loop.
            //
            // Dead-match is treated as dead (break after recording). It is
            // a synthetic match state created for transitions that were
            // dead|MATCH_FLAG in the lazy DFA (e.g., EOI from match states).
            if (sid >= mms) {
                if (sid == dm) {
                    // Dead-match: record match and break like dead.
                    // dead-match means "a match was confirmed on the
                    // transition that led here" (from dead|MATCH_FLAG
                    // in the lazy DFA).
                    lastMatchEnd = at;
                    break;
                }
                lastMatchEnd = at;
                sid = tt[sid + cc.classify(haystack[at])];
                at++;
                if (sid == dead) break;
                if (sid == q) return SearchResult.gaveUp(at - 1);
                continue;
            }

            // Inner 4x unrolled loop (only entered from non-match states).
            // Breaks out on special states:
            //   - sid <= quit: dead or quit
            //   - sid >= minMatchState: match state (need to record)
            //   - at + 3 >= end: need to switch to single-step for correct EOI
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

            if (at >= end) break;

            // Dispatch on the current state (result of the last transition).
            if (sid >= mms) {
                if (sid == dm) {
                    // Dead-match from inner loop: record and break.
                    lastMatchEnd = at;
                    break;
                }
                // Entered a match state. Record match at current position
                // (position of the char consumed to enter this state).
                lastMatchEnd = at;
                at++;
            } else if (sid == dead) {
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
     * If the resulting state is a match state (sid >= minMatchState), the
     * match is confirmed by the right-edge context. This handles both:
     * (a) end-assertion patterns ($, \z) where the EOI transition leads to a
     * dead-match state, and (b) normal patterns where we're in a match state
     * and the right-edge transition also leads to a match state.</p>
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
            if (rightEdgeSid >= minMatchState) {
                return end;
            }
        }
        return lastMatchEnd;
    }
}
