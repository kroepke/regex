package lol.ohai.regex.automata.dfa.dense;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.DFACache;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.dfa.lazy.Start;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.util.Input;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Builds a {@link DenseDFA} by eagerly computing all reachable states and
 * transitions via the lazy DFA determinization machinery.
 *
 * <p>The build process:
 * <ol>
 *   <li>Creates a temporary {@link LazyDFA} and {@link DFACache}</li>
 *   <li>Computes start states (anchored + unanchored)</li>
 *   <li>Drives a worklist: for each state, calls
 *       {@link LazyDFA#computeAllTransitions} to compute all transitions</li>
 *   <li>After all states are discovered, copies into a flat {@code int[]}
 *       table</li>
 *   <li>Shuffles match states to the end for range-check detection</li>
 *   <li>Remaps all transition targets and strips MATCH_FLAG</li>
 * </ol>
 *
 * <p>Returns {@code null} if the pattern exceeds the state limit.</p>
 *
 * <p>Ref: upstream/regex/regex-automata/src/dfa/determinize/mod.rs:60-200</p>
 */
public final class DenseDFABuilder {

    private static final int DEFAULT_MAX_BYTES = 2 * 1024 * 1024; // 2MB

    private final int maxBytes;

    public DenseDFABuilder() {
        this(DEFAULT_MAX_BYTES);
    }

    public DenseDFABuilder(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    /**
     * Attempts to build a dense DFA from the given NFA and char classes.
     *
     * @return the dense DFA, or {@code null} if the pattern is unsupported
     *         or exceeds the state limit
     */
    public DenseDFA build(NFA nfa, CharClasses charClasses) {
        // Don't build dense DFA when char classes have quit chars (e.g., Unicode
        // word boundaries). Quit-char patterns can't handle all inputs in the DFA
        // and require frequent fallback to the lazy DFA, so the dense DFA provides
        // minimal benefit while risking edge-case correctness issues.
        if (charClasses.hasQuitClasses()) {
            return null;
        }

        // Create temporary lazy DFA + cache with generous capacity
        LazyDFA lazyDFA = LazyDFA.create(nfa, charClasses);
        if (lazyDFA == null) {
            return null;
        }

        int stride = charClasses.stride();
        int maxStates = maxBytes / (stride * 4);

        // Use a large cache capacity so it doesn't clear/give-up during build
        DFACache cache = new DFACache(charClasses, maxBytes, nfa.stateCount());

        // Compute all 10 start states (5 Start variants × anchored/unanchored).
        // Each Start variant requires a synthetic Input whose haystack and start
        // position produce the correct look-behind context.
        // Ref: upstream/regex/regex-automata/src/util/start.rs:141-158
        int[] startStates = new int[Start.COUNT * 2];
        for (Start start : Start.values()) {
            for (int a = 0; a < 2; a++) {
                boolean anchored = (a == 1);
                Input syntheticInput = createSyntheticInput(start, anchored);
                int sid = lazyDFA.getOrComputeStartState(syntheticInput, cache);
                int idx = anchored ? Start.COUNT + start.ordinal() : start.ordinal();
                startStates[idx] = sid;
            }
        }

        // Worklist-driven exploration of all reachable states
        // Track which raw state IDs have been enqueued
        int dead = DFACache.dead(stride);
        int quit = DFACache.quit(stride);

        boolean[] enqueued = new boolean[maxStates + 1];
        Deque<Integer> worklist = new ArrayDeque<>();

        // Enqueue all start states (some may be the same state)
        for (int sid : startStates) {
            enqueueState(worklist, enqueued, sid, dead, quit, stride);
        }

        while (!worklist.isEmpty()) {
            int sid = worklist.poll();

            int beforeCount = lazyDFA.computeAllTransitions(cache, sid);
            int afterCount = cache.stateCount();

            // Discover newly created states
            for (int i = beforeCount; i < afterCount; i++) {
                int newRawSid = i * stride;
                enqueueState(worklist, enqueued, newRawSid, dead, quit, stride);
            }

            // Check state limit
            if (cache.stateCount() > maxStates) {
                return null;
            }
        }

        // Verify: after worklist completes, no real state should have UNKNOWN
        // transitions. If this fires, the worklist didn't discover all states.
        for (int i = 3; i < cache.stateCount(); i++) {
            int rawSid = i * stride;
            for (int cls = 0; cls <= charClasses.classCount(); cls++) {
                if (cache.nextState(rawSid, cls) == DFACache.UNKNOWN) {
                    throw new IllegalStateException(
                        "UNKNOWN transition at state " + rawSid + " class " + cls
                        + " (stateCount=" + cache.stateCount() + ")");
                }
            }
        }

        // Extract into dense table with match-state shuffling
        return extractDense(cache, charClasses, startStates, dead, quit, stride);
    }

    /**
     * Creates a synthetic Input that triggers the given Start variant's
     * look-behind context. Each Start variant depends on the char BEFORE
     * the search position.
     */
    private static Input createSyntheticInput(Start start, boolean anchored) {
        return switch (start) {
            case TEXT -> anchored ? Input.anchored("x") : Input.of("x");
            case LINE_LF -> {
                Input in = Input.of("\nx", 1, 2);
                yield anchored ? in.withBounds(1, 2, true) : in;
            }
            case LINE_CR -> {
                Input in = Input.of("\rx", 1, 2);
                yield anchored ? in.withBounds(1, 2, true) : in;
            }
            case WORD_BYTE -> {
                Input in = Input.of("ax", 1, 2);
                yield anchored ? in.withBounds(1, 2, true) : in;
            }
            case NON_WORD_BYTE -> {
                Input in = Input.of(" x", 1, 2);
                yield anchored ? in.withBounds(1, 2, true) : in;
            }
        };
    }

    /**
     * Enqueue a state for transition computation if it's a real state
     * (not padding, dead, quit, or already enqueued).
     */
    private static void enqueueState(Deque<Integer> worklist, boolean[] enqueued,
                                      int sid, int dead, int quit, int stride) {
        // Strip match flag
        int rawSid = sid & 0x7FFF_FFFF;
        // Skip padding (0), dead, quit
        if (rawSid == 0 || rawSid == dead || rawSid == quit) {
            return;
        }
        int stateIdx = rawSid / stride;
        if (stateIdx >= enqueued.length) {
            return; // shouldn't happen, but safety
        }
        if (!enqueued[stateIdx]) {
            enqueued[stateIdx] = true;
            worklist.add(rawSid);
        }
    }

    /**
     * Extracts the fully-computed DFACache into a flat int[] with match states
     * shuffled to the end.
     *
     * <p>Layout: padding at 0, dead at stride, quit at stride*2, then real
     * states. Match states are shuffled to higher IDs. All transition targets
     * are remapped and MATCH_FLAG is stripped.</p>
     */
    private static DenseDFA extractDense(DFACache cache, CharClasses charClasses,
                                          int[] startStates,
                                          int dead, int quit, int stride) {
        int totalStates = cache.stateCount(); // includes padding, dead, quit

        // Identify match states (skip index 0 = padding).
        //
        // A DFA state is a "match state" if the lazy DFA would signal a match
        // when transitioning FROM it. Two sources of match-ness:
        //
        // 1. StateContent.isMatch() — the NFA Match state was reachable during
        //    epsilon closure at state-creation time (no look-ahead needed).
        //
        // 2. MATCH_FLAG on outgoing transitions from look-ahead re-computation —
        //    for look-assertion patterns (e.g., (?m)^.+$), the Match NFA state
        //    may NOT be in the closure, but MATCH_FLAG is set on transitions
        //    where look-ahead resolution makes the match visible (e.g., $ on \n/EOI).
        //    We detect this by checking if MATCH_FLAG is set on any transition
        //    whose raw target state is NOT itself a match state (i.e., MATCH_FLAG
        //    was contributed by the source, not the destination's allocation).
        //
        // Ref: computeNextState in LazyDFA.java — isMatch set from source's
        // State.Match, OR'd onto the returned sid after allocation.
        boolean[] isMatch = new boolean[totalStates];
        int matchCount = 0;
        for (int i = 3; i < totalStates; i++) { // skip padding(0), dead(1), quit(2)
            int rawSid = i * stride;
            // Check 1: unconditional match from epsilon closure
            if (cache.getState(rawSid).isMatch()) {
                isMatch[i] = true;
                matchCount++;
                continue;
            }
            // Check 2: conditional match from look-ahead re-computation
            // Scan outgoing transitions for MATCH_FLAG that wasn't set by dest
            for (int cls = 0; cls <= charClasses.classCount(); cls++) {
                int trans = cache.nextState(rawSid, cls);
                if ((trans & DFACache.MATCH_FLAG) != 0) {
                    int rawTarget = trans & 0x7FFF_FFFF;
                    int targetIdx = rawTarget / stride;
                    // If the destination is dead, quit, or its StateContent is
                    // NOT a match state, then MATCH_FLAG came from the source.
                    if (targetIdx < 3 || !cache.getState(rawTarget).isMatch()) {
                        isMatch[i] = true;
                        matchCount++;
                        break;
                    }
                }
            }
        }

        // Build remap table: maps old state index -> new state ID (stride-multiplied)
        // Fixed positions: 0 -> 0 (padding), 1 -> stride (dead), 2 -> stride*2 (quit)
        // Non-match real states get low IDs, match states get high IDs
        int[] remap = new int[totalStates];
        remap[0] = 0;       // padding stays at 0
        remap[1] = stride;  // dead stays at stride
        remap[2] = stride * 2; // quit stays at stride * 2

        int nonMatchNext = 3; // next available slot for non-match states
        int matchNext = totalStates - matchCount; // first match slot

        for (int i = 3; i < totalStates; i++) {
            if (isMatch[i]) {
                remap[i] = matchNext * stride;
                matchNext++;
            } else {
                remap[i] = nonMatchNext * stride;
                nonMatchNext++;
            }
        }

        int minMatchState = (totalStates - matchCount) * stride;

        // Build the new transition table
        int[] newTable = new int[totalStates * stride];

        // Copy dead state: all transitions point to dead
        int newDead = remap[1];
        for (int cls = 0; cls <= charClasses.classCount(); cls++) {
            newTable[newDead + cls] = newDead;
        }

        // Copy quit state: all transitions point to quit
        int newQuit = remap[2];
        for (int cls = 0; cls <= charClasses.classCount(); cls++) {
            newTable[newQuit + cls] = newQuit;
        }

        // Copy real states (index 3+), preserving MATCH_FLAG on transitions.
        // MATCH_FLAG (0x8000_0000) on a transition means "the source state
        // was a match state" — the delayed-match signal used by the search loop.
        for (int i = 3; i < totalStates; i++) {
            int oldRawSid = i * stride;
            int newSid = remap[i];

            for (int cls = 0; cls <= charClasses.classCount(); cls++) {
                int target = cache.nextState(oldRawSid, cls);

                // Separate MATCH_FLAG from raw target
                int matchFlag = target & DFACache.MATCH_FLAG;
                int rawTarget = target & 0x7FFF_FFFF;

                // Remap target to new state ID, preserving MATCH_FLAG
                int targetIdx = rawTarget / stride;
                if (targetIdx < totalStates) {
                    newTable[newSid + cls] = remap[targetIdx] | matchFlag;
                } else {
                    // Unknown/uncomputed transition — should not happen after
                    // full worklist exploration; map to dead as safety
                    newTable[newSid + cls] = newDead;
                }
            }
        }

        // Remap all 10 start states
        int[] newStartStates = new int[startStates.length];
        for (int i = 0; i < startStates.length; i++) {
            int rawSid = startStates[i] & 0x7FFF_FFFF;
            int stateIdx = rawSid / stride;
            newStartStates[i] = (stateIdx < remap.length) ? remap[stateIdx] : rawSid;
        }

        // Acceleration analysis: find states where most transitions self-loop
        // and ≤3 equivalence classes escape. Build boolean[128] escape tables.
        // Since transitions may carry MATCH_FLAG, we strip it before comparing
        // the raw target to the state ID for self-loop detection.
        // Ref: upstream/regex/regex-automata/src/dfa/accel.rs:1-51
        int classCount = charClasses.classCount();  // excludes EOI
        boolean[][] accelTables = new boolean[totalStates][];
        for (int i = 0; i < totalStates; i++) {
            int sid = i * stride;
            if (sid == newDead || sid == newQuit || i == 0) continue;  // skip padding/dead/quit

            // Count non-self-loop transitions (escape classes), excluding EOI.
            // Strip MATCH_FLAG before comparing: a transition that self-loops
            // WITH MATCH_FLAG is still a self-loop for acceleration purposes.
            int escapes = 0;
            boolean tooMany = false;
            for (int cls = 0; cls < classCount; cls++) {
                int rawTarget = newTable[sid + cls] & 0x7FFF_FFFF;
                if (rawTarget != sid) {
                    escapes++;
                    if (escapes > 3) { tooMany = true; break; }
                }
            }

            if (!tooMany) {
                // Build boolean[128] escape table
                boolean[] table = new boolean[128];
                for (int c = 0; c < 128; c++) {
                    int cls = charClasses.classify((char) c);
                    int rawTarget = newTable[sid + cls] & 0x7FFF_FFFF;
                    if (rawTarget != sid) {
                        table[c] = true;
                    }
                }
                accelTables[i] = table;
            }
        }

        return new DenseDFA(newTable, charClasses,
                newStartStates,
                minMatchState, newDead, newQuit, totalStates, accelTables);
    }
}
