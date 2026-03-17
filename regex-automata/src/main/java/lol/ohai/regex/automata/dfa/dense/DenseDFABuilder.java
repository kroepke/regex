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
 *   <li>After all states are discovered, identifies match states via
 *       StateContent.isMatch()</li>
 *   <li>Shuffles match states to the end for range-check detection
 *       ({@code sid >= minMatchState})</li>
 *   <li>Copies into a flat {@code int[]} table (MATCH_FLAG stripped from
 *       all transitions; match detection uses range-based sid >= minMatchState)</li>
 * </ol>
 *
 * <p>Returns {@code null} if the pattern exceeds the state limit or has
 * quit chars (e.g., Unicode word boundaries).</p>
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
     * states. Match states are shuffled to higher IDs so that
     * {@code sid >= minMatchState} indicates a match. All transition targets
     * are remapped and MATCH_FLAG is stripped.</p>
     *
     * <p>Transitions that were {@code dead | MATCH_FLAG} in the lazy DFA
     * (signaling "source was a match, transition to dead") are remapped to a
     * synthetic "dead-match" state. This state behaves like dead (all
     * transitions point to dead) but has sid >= minMatchState, preserving the
     * match signal for range-based detection. This is needed for patterns
     * with end-assertions ($, \z) where the EOI transition resolves the
     * look-ahead and confirms the match.</p>
     */
    private static DenseDFA extractDense(DFACache cache, CharClasses charClasses,
                                          int[] startStates,
                                          int dead, int quit, int stride) {
        int totalStates = cache.stateCount(); // includes padding, dead, quit

        // Phase 1: Scan transitions for MATCH_FLAG signals that require
        // synthetic states.
        //
        // MATCH_FLAG on a transition means "the source resolved to a match via
        // look-ahead." Two cases need synthetic states:
        //
        // (a) dead|MATCH_FLAG: "match confirmed, then dead." We create one
        //     dead-match state that acts like dead but sid >= minMatchState.
        //
        // (b) target|MATCH_FLAG where target is a real non-match state AND
        //     source is NOT isMatch(): "conditional match from look-ahead
        //     resolution, continue to target." We create match-wrapper states
        //     that mirror the target's transitions but are in the match range.
        //     This happens for patterns with end-assertions like (?m)^.+$
        //     where $ is resolved per-transition.

        // Pass 1: check if dead-match is needed. Dead-match is required when
        // dead|MATCH_FLAG appears on transitions from non-match sources, OR
        // on EOI transitions from any source (handleRightEdge needs it).
        boolean needsDeadMatch = false;
        int deadWithFlagVal = dead | DFACache.MATCH_FLAG;
        int eoiClass = charClasses.eoiClass();
        for (int i = 3; i < totalStates && !needsDeadMatch; i++) {
            int rawSid = i * stride;
            boolean sourceMatch = cache.getState(rawSid).isMatch();
            for (int cls = 0; cls <= charClasses.classCount(); cls++) {
                if (cache.nextState(rawSid, cls) == deadWithFlagVal) {
                    if (!sourceMatch || cls == eoiClass) {
                        needsDeadMatch = true;
                        break;
                    }
                }
            }
        }

        // Pass 2: find targets needing match-wrappers
        int[] matchWrapperMap = new int[totalStates];
        java.util.Arrays.fill(matchWrapperMap, -1);
        int wrapperCount = 0;
        int wrapperBase = totalStates + (needsDeadMatch ? 1 : 0);

        for (int i = 3; i < totalStates; i++) {
            int rawSid = i * stride;
            boolean sourceIsMatch = cache.getState(rawSid).isMatch();
            if (sourceIsMatch) continue; // match states already handle MATCH_FLAG
            for (int cls = 0; cls <= charClasses.classCount(); cls++) {
                int target = cache.nextState(rawSid, cls);
                if ((target & DFACache.MATCH_FLAG) == 0) continue;
                int rawTarget = target & 0x7FFF_FFFF;
                if (rawTarget == dead) continue; // handled by dead-match
                int targetIdx = rawTarget / stride;
                if (targetIdx >= 3 && targetIdx < totalStates
                        && !cache.getState(rawTarget).isMatch()
                        && matchWrapperMap[targetIdx] == -1) {
                    matchWrapperMap[targetIdx] = wrapperBase + wrapperCount;
                    wrapperCount++;
                }
            }
        }

        // Total states: original + optional dead-match + match-wrappers
        int extraStates = (needsDeadMatch ? 1 : 0) + wrapperCount;
        int denseStates = totalStates + extraStates;
        int deadMatchIdx = needsDeadMatch ? totalStates : -1;

        // Phase 2: Identify match states.
        boolean[] isMatch = new boolean[denseStates];
        int matchCount = 0;
        for (int i = 3; i < totalStates; i++) {
            int rawSid = i * stride;
            if (cache.getState(rawSid).isMatch()) {
                isMatch[i] = true;
                matchCount++;
            }
        }
        if (needsDeadMatch) {
            isMatch[deadMatchIdx] = true;
            matchCount++;
        }
        // Match-wrapper states are match states
        for (int i = 0; i < totalStates; i++) {
            if (matchWrapperMap[i] >= 0) {
                isMatch[matchWrapperMap[i]] = true;
                matchCount++;
            }
        }

        // Phase 3: Build remap table.
        int[] remap = new int[denseStates];
        remap[0] = 0;
        remap[1] = stride;
        remap[2] = stride * 2;

        int nonMatchNext = 3;
        int matchNext = denseStates - matchCount;

        for (int i = 3; i < denseStates; i++) {
            if (isMatch[i]) {
                remap[i] = matchNext * stride;
                matchNext++;
            } else {
                remap[i] = nonMatchNext * stride;
                nonMatchNext++;
            }
        }

        int minMatchState = (denseStates - matchCount) * stride;
        int newDeadMatch = needsDeadMatch ? remap[deadMatchIdx] : -1;

        // Build match-wrapper remap: old target state index → new match-wrapper sid
        int[] wrapperNewSid = new int[totalStates];
        for (int i = 0; i < totalStates; i++) {
            wrapperNewSid[i] = (matchWrapperMap[i] >= 0) ? remap[matchWrapperMap[i]] : -1;
        }

        // Phase 4: Build the new transition table.
        int[] newTable = new int[denseStates * stride];

        // Dead: all transitions → dead
        int newDead = remap[1];
        for (int cls = 0; cls <= charClasses.classCount(); cls++) {
            newTable[newDead + cls] = newDead;
        }

        // Quit: all transitions → quit
        int newQuit = remap[2];
        for (int cls = 0; cls <= charClasses.classCount(); cls++) {
            newTable[newQuit + cls] = newQuit;
        }

        // Dead-match: all transitions → dead
        if (needsDeadMatch) {
            for (int cls = 0; cls <= charClasses.classCount(); cls++) {
                newTable[newDeadMatch + cls] = newDead;
            }
        }

        // Copy real states, handling MATCH_FLAG.
        for (int i = 3; i < totalStates; i++) {
            int oldRawSid = i * stride;
            int newSid = remap[i];
            boolean sourceIsMatch = cache.getState(oldRawSid).isMatch();

            for (int cls = 0; cls <= charClasses.classCount(); cls++) {
                int target = cache.nextState(oldRawSid, cls);
                boolean hasFlag = (target & DFACache.MATCH_FLAG) != 0;
                int rawTarget = target & 0x7FFF_FFFF;

                if (hasFlag && rawTarget == dead && (!sourceIsMatch || cls == charClasses.eoiClass())) {
                    // dead|MATCH_FLAG → dead-match when:
                    // - source is NOT a match state (look-ahead resolution), OR
                    // - this is the EOI transition (handleRightEdge needs it)
                    newTable[newSid + cls] = newDeadMatch;
                } else if (hasFlag && rawTarget == dead) {
                    // dead|MATCH_FLAG from match source on non-EOI → plain dead.
                    // The match single-step records the match before transitioning.
                    newTable[newSid + cls] = newDead;
                } else if (hasFlag && !sourceIsMatch) {
                    // Conditional match from look-ahead. Target needs wrapper.
                    int targetIdx = rawTarget / stride;
                    if (targetIdx >= 3 && targetIdx < totalStates
                            && wrapperNewSid[targetIdx] >= 0) {
                        newTable[newSid + cls] = wrapperNewSid[targetIdx];
                    } else {
                        // Target is already a match state or target wrapper
                        // wasn't created — use normal remap
                        newTable[newSid + cls] = (targetIdx < totalStates) ? remap[targetIdx] : newDead;
                    }
                } else {
                    int targetIdx = rawTarget / stride;
                    newTable[newSid + cls] = (targetIdx < totalStates) ? remap[targetIdx] : newDead;
                }
            }
        }

        // Copy match-wrapper states: same transitions as their original targets.
        for (int i = 3; i < totalStates; i++) {
            if (matchWrapperMap[i] < 0) continue;
            int wrapperSid = remap[matchWrapperMap[i]];
            int originalSid = remap[i];
            System.arraycopy(newTable, originalSid, newTable, wrapperSid, stride);
        }

        // Remap start states
        int[] newStartStates = new int[startStates.length];
        for (int i = 0; i < startStates.length; i++) {
            int rawSid = startStates[i] & 0x7FFF_FFFF;
            int stateIdx = rawSid / stride;
            newStartStates[i] = (stateIdx < remap.length) ? remap[stateIdx] : rawSid;
        }

        // Acceleration analysis
        int classCount = charClasses.classCount();
        boolean[][] accelTables = new boolean[denseStates][];
        for (int i = 0; i < denseStates; i++) {
            int sid = i * stride;
            if (sid == newDead || sid == newQuit || i == 0) continue;
            if (needsDeadMatch && sid == newDeadMatch) continue;

            int escapes = 0;
            boolean tooMany = false;
            for (int cls = 0; cls < classCount; cls++) {
                if (newTable[sid + cls] != sid) {
                    escapes++;
                    if (escapes > 3) { tooMany = true; break; }
                }
            }

            if (!tooMany) {
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
                newStartStates,
                minMatchState, newDead, newQuit, denseStates, accelTables,
                needsDeadMatch ? newDeadMatch : -1);
    }
}
