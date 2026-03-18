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
 * Special states (dead, quit, match, accel) are shuffled to the bottom of the
 * state ID space so that {@code sid <= maxSpecial} indicates a special state
 * requiring dispatch. This is the upstream "special-state taxonomy" layout.</p>
 *
 * <p>State layout (bottom to top):
 * <ul>
 *   <li>Dead state at ID 0</li>
 *   <li>Quit state at ID stride</li>
 *   <li>Match-only states</li>
 *   <li>Match+accel states</li>
 *   <li>Accel-only states</li>
 *   <li>Normal states (above maxSpecial)</li>
 * </ul></p>
 *
 * <p>Ref: upstream/regex/regex-automata/src/dfa/dense.rs,
 * upstream/regex/regex-automata/src/dfa/special.rs:142-180</p>
 */
public final class DenseDFA {
    private final int[] transTable;
    private final CharClasses charClasses;
    /** Start states: [0..COUNT-1] = unanchored, [COUNT..2*COUNT-1] = anchored. */
    private final int[] startStates;
    private final int dead;           // always 0
    private final int quit;           // always stride
    private final int stateCount;
    private final int stride;
    private final int minMatch;       // first match state ID (-1 if none)
    private final int maxMatch;       // last match state ID (-2 if none)
    private final int minAccel;       // first accel state ID (-1 if none)
    private final int maxAccel;       // last accel state ID (-2 if none)
    private final int maxSpecial;     // max(maxMatch, maxAccel) — unrolled loop threshold
    private final boolean[][] accelTables;  // [accelIndex] → boolean[128] escape table
    private final char[] accelSingleEscape; // [accelIndex] → single escape char, or '\0'
    private final int deadMatch;      // synthetic dead-match state, or -1

    DenseDFA(int[] transTable, CharClasses charClasses,
             int[] startStates,
             int dead, int quit,
             int minMatch, int maxMatch, int minAccel, int maxAccel,
             int maxSpecial, int stateCount,
             boolean[][] accelTables, char[] accelSingleEscape, int deadMatch) {
        this.transTable = transTable;
        this.charClasses = charClasses;
        this.startStates = startStates;
        this.dead = dead;
        this.quit = quit;
        this.stateCount = stateCount;
        this.stride = charClasses.stride();
        this.minMatch = minMatch;
        this.maxMatch = maxMatch;
        this.minAccel = minAccel;
        this.maxAccel = maxAccel;
        this.maxSpecial = maxSpecial;
        this.accelTables = accelTables;
        this.accelSingleEscape = accelSingleEscape;
        this.deadMatch = deadMatch;
    }

    /**
     * Returns true if at least one state has an acceleration table.
     */
    public boolean hasAcceleratedStates() {
        return accelTables != null && accelTables.length > 0;
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

    /** Returns the dead state ID (0). */
    public int dead() { return dead; }

    /** Returns the quit state ID (stride). */
    public int quit() { return quit; }

    /** Returns the stride (power-of-2 >= alphabet size). */
    public int stride() { return stride; }

    /** Returns the minimum match state ID (bottom of match range). */
    public int minMatch() { return minMatch; }

    /** Returns the maximum match state ID (top of match range). */
    public int maxMatch() { return maxMatch; }

    /** Returns the minimum accelerated state ID. */
    public int minAccel() { return minAccel; }

    /** Returns the maximum accelerated state ID. */
    public int maxAccel() { return maxAccel; }

    /** Returns the maximum special state ID (the threshold guard). */
    public int maxSpecial() { return maxSpecial; }

    /**
     * Returns true if the given state ID represents a match state.
     */
    public boolean isMatch(int sid) {
        return sid >= minMatch && sid <= maxMatch;
    }

    /**
     * Returns true if the given state ID is an accelerated state.
     */
    public boolean isAccel(int sid) {
        return sid >= minAccel && sid <= maxAccel;
    }

    /**
     * Returns true if the given state ID is a special state (dead, quit, match, or accel).
     */
    public boolean isSpecial(int sid) {
        return sid <= maxSpecial;
    }

    /**
     * Returns the minimum match state ID. Any state with
     * {@code sid >= minMatchState} is a match state.
     * @deprecated Use {@link #minMatch()} instead.
     */
    public int minMatchState() { return minMatch; }

    private int accelIndex(int sid) { return (sid - minAccel) / stride; }

    /**
     * Forward search for the end position of the leftmost-first match.
     *
     * <p>All transitions are pre-computed in the flat {@code transTable}, so
     * there are no UNKNOWN transitions and no {@code computeNextState} calls.
     * Special states (dead, quit, match, accel) are at the bottom of the state
     * ID space. The unrolled inner loop uses a single guard
     * {@code sid <= maxSpecial} to break out for dispatch.</p>
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
        if (sid == 0) return SearchResult.NO_MATCH;

        final int[] tt = transTable;
        final CharClasses cc = charClasses;
        final int ms = maxSpecial;
        final int dm = deadMatch;
        final int q = quit;

        int lastMatchEnd = -1;
        if (isMatch(sid) && sid != dm) lastMatchEnd = at;

        String haystackString = null;

        while (at < end) {
            // 1-char match delay: if we're currently in a match state
            // (including dead-match), record match at current position BEFORE
            // consuming the next char. This replicates the lazy DFA's
            // MATCH_FLAG behavior where transitions FROM match states carry
            // the match signal.
            if (sid >= minMatch && sid <= maxMatch) {
                lastMatchEnd = at;
            }

            // UNROLLED INNER LOOP — single guard, no accel
            while (at < end) {
                sid = tt[sid + cc.classify(haystack[at])];
                if (sid <= ms || at + 3 >= end) break;
                at++;
                sid = tt[sid + cc.classify(haystack[at])];
                if (sid <= ms) break;
                at++;
                sid = tt[sid + cc.classify(haystack[at])];
                if (sid <= ms) break;
                at++;
                sid = tt[sid + cc.classify(haystack[at])];
                if (sid <= ms) break;
                at++;
            }

            // SPECIAL-STATE DISPATCH
            if (sid <= ms) {
                if (sid >= minMatch && sid <= maxMatch) {
                    if (sid == dm) {
                        lastMatchEnd = at;
                        break;
                    }
                    lastMatchEnd = at;
                    if (sid >= minAccel && sid <= maxAccel) {
                        if (haystackString == null) haystackString = input.haystackStr();
                        at = accelerate(sid, haystack, haystackString, at, end);
                        // Update match through the accelerated range: the match
                        // state self-looped through all skipped positions.
                        lastMatchEnd = at;
                        if (at >= end) break;
                        sid = tt[sid + cc.classify(haystack[at])];
                        at++;
                        continue;
                    }
                } else if (sid >= minAccel && sid <= maxAccel) {
                    if (haystackString == null) haystackString = input.haystackStr();
                    at = accelerate(sid, haystack, haystackString, at, end);
                    if (at >= end) break;
                    sid = tt[sid + cc.classify(haystack[at])];
                    // If the post-accel transition leads to a match state,
                    // record match at the escape char position and handle it.
                    if (sid >= minMatch && sid <= maxMatch) {
                        lastMatchEnd = at;
                        if (sid == dm) {
                            break;
                        }
                    }
                    at++;
                    continue;
                } else if (sid == 0) {
                    break;
                } else {
                    return SearchResult.gaveUp(at);
                }
            }
            at++;
        }

        // If we exited the loop while still in a match state, the match
        // extends to `at` (position after the last consumed char). This
        // handles the match-delay for the final iteration where the loop
        // exits before the match-delay at the top can run.
        if (sid >= minMatch && sid <= maxMatch) {
            lastMatchEnd = at;
        }

        lastMatchEnd = handleRightEdge(sid, haystack, end, lastMatchEnd);
        if (lastMatchEnd >= 0) return SearchResult.match(lastMatchEnd);
        return SearchResult.NO_MATCH;
    }

    /**
     * Accelerate by scanning ahead for escape chars using either
     * String.indexOf (single escape char) or a boolean table scan.
     *
     * <p>Ref: upstream/regex/regex-automata/src/dfa/accel.rs:449-458</p>
     */
    private int accelerate(int sid, char[] haystack, String haystackString, int at, int end) {
        int idx = accelIndex(sid);
        char singleEsc = accelSingleEscape[idx];
        if (singleEsc != 0) {
            // Single-escape-char state: use SIMD-intrinsified String.indexOf
            int found = haystackString.indexOf(singleEsc, at);
            return (found < 0 || found >= end) ? end : found;
        } else {
            // Multi-escape state: table-based scan (1 array load per char)
            boolean[] escTable = accelTables[idx];
            while (at < end) {
                char c = haystack[at];
                if (c >= 128 || escTable[c]) break;
                at++;
            }
            return at;
        }
    }

    /**
     * Process the right-edge transition after the forward search main loop.
     *
     * <p>Takes the EOI or right-context transition from the current state.
     * If the resulting state is a match state, the match is confirmed by the
     * right-edge context.</p>
     *
     * <p>Ref: upstream/regex/regex-automata/src/dfa/search.rs:576-602</p>
     *
     * @return updated lastMatchEnd (-1 if no match)
     */
    private int handleRightEdge(int sid, char[] haystack, int end,
                                 int lastMatchEnd) {
        if (sid != 0 && sid != quit) {
            int rightEdgeSid;
            if (end < haystack.length) {
                rightEdgeSid = transTable[sid + charClasses.classify(haystack[end])];
            } else {
                rightEdgeSid = transTable[sid + charClasses.eoiClass()];
            }
            if (isMatch(rightEdgeSid)) {
                return end;
            }
        }
        return lastMatchEnd;
    }
}
