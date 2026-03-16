package lol.ohai.regex.automata.dfa.dense;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.DFACache;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
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
 * <p>Returns {@code null} if the pattern uses look-assertions (Spec 1
 * limitation) or exceeds the state limit.</p>
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
        // Spec 1 limitation: no look-assertions
        if (!nfa.lookSetAny().isEmpty()) {
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

        // Compute start states. Content doesn't matter since lookSetAny is empty.
        Input unanchoredInput = Input.of("x");
        Input anchoredInput = Input.anchored("x");

        int startUnanchored = lazyDFA.getOrComputeStartState(unanchoredInput, cache);
        int startAnchored = lazyDFA.getOrComputeStartState(anchoredInput, cache);

        // Worklist-driven exploration of all reachable states
        // Track which raw state IDs have been enqueued
        int dead = DFACache.dead(stride);
        int quit = DFACache.quit(stride);

        boolean[] enqueued = new boolean[maxStates + 1];
        Deque<Integer> worklist = new ArrayDeque<>();

        // Enqueue start states (they may be the same state)
        enqueueState(worklist, enqueued, startUnanchored, dead, quit, stride);
        enqueueState(worklist, enqueued, startAnchored, dead, quit, stride);

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
        return extractDense(cache, charClasses, startAnchored, startUnanchored,
                dead, quit, stride);
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
                                          int startAnchored, int startUnanchored,
                                          int dead, int quit, int stride) {
        int totalStates = cache.stateCount(); // includes padding, dead, quit

        // Identify match states (skip index 0 = padding)
        boolean[] isMatch = new boolean[totalStates];
        int matchCount = 0;
        for (int i = 3; i < totalStates; i++) { // skip padding(0), dead(1), quit(2)
            if (cache.getState(i * stride).isMatch()) {
                isMatch[i] = true;
                matchCount++;
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

        // Copy real states (index 3+)
        for (int i = 3; i < totalStates; i++) {
            int oldRawSid = i * stride;
            int newSid = remap[i];

            for (int cls = 0; cls <= charClasses.classCount(); cls++) {
                int target = cache.nextState(oldRawSid, cls);

                // Strip MATCH_FLAG from target
                int rawTarget = target & 0x7FFF_FFFF;

                // Remap target to new state ID
                int targetIdx = rawTarget / stride;
                if (targetIdx < totalStates) {
                    newTable[newSid + cls] = remap[targetIdx];
                } else {
                    // Unknown/uncomputed transition — should not happen after
                    // full worklist exploration; map to dead as safety
                    newTable[newSid + cls] = newDead;
                }
            }
        }

        // Remap start states
        int rawStartAnchored = startAnchored & 0x7FFF_FFFF;
        int rawStartUnanchored = startUnanchored & 0x7FFF_FFFF;
        int newStartAnchored = remap[rawStartAnchored / stride];
        int newStartUnanchored = remap[rawStartUnanchored / stride];

        // Acceleration analysis: find states where most transitions self-loop
        // and ≤3 equivalence classes escape. Build boolean[128] escape tables.
        // Ref: upstream/regex/regex-automata/src/dfa/accel.rs:1-51
        int classCount = charClasses.classCount();  // excludes EOI
        boolean[][] accelTables = new boolean[totalStates][];
        for (int i = 0; i < totalStates; i++) {
            int sid = i * stride;
            if (sid == newDead || sid == newQuit || i == 0) continue;  // skip padding/dead/quit

            // Count non-self-loop transitions (escape classes), excluding EOI
            int escapes = 0;
            boolean tooMany = false;
            for (int cls = 0; cls < classCount; cls++) {
                if (newTable[sid + cls] != sid) {
                    escapes++;
                    if (escapes > 3) { tooMany = true; break; }
                }
            }

            if (!tooMany) {
                // Build boolean[128] escape table
                boolean[] table = new boolean[128];
                for (int c = 0; c < 128; c++) {
                    int cls = charClasses.classify((char) c);
                    if (newTable[sid + cls] != sid) {
                        table[c] = true;
                    }
                }
                accelTables[i] = table;
            }
        }

        return new DenseDFA(newTable, charClasses,
                newStartAnchored, newStartUnanchored,
                minMatchState, newDead, newQuit, totalStates, accelTables);
    }
}
