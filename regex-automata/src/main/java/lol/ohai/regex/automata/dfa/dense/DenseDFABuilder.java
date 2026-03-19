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
 *   <li>After all states are discovered, classifies states into categories:
 *       match-only, match+accel, accel-only, normal</li>
 *   <li>Shuffles states into the special-state taxonomy layout: dead(0),
 *       quit(stride), match, accel, then normal states</li>
 *   <li>Copies into a flat {@code int[]} table (MATCH_FLAG stripped from
 *       all transitions; match detection uses range-based checks)</li>
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
    /**
     * Maximum NFA state count for dense DFA construction. Patterns with more
     * NFA states than this skip the dense DFA and use the lazy DFA instead.
     * Matches upstream's heuristic at wrappers.rs:851-860 (default: 30).
     *
     * <p>Large NFA state counts produce large DFAs with many states, where the
     * pre-compiled transition table offers little benefit over on-demand lazy
     * DFA computation. The lazy DFA only materializes states actually reached
     * during search, giving better cache behavior for patterns with many
     * unreachable or rarely-reached states.</p>
     */
    private static final int DEFAULT_NFA_STATE_LIMIT = 30;

    public DenseDFA build(NFA nfa, CharClasses charClasses) {
        // Don't build dense DFA when char classes have quit chars (e.g., Unicode
        // word boundaries). Quit-char patterns can't handle all inputs in the DFA
        // and require frequent fallback to the lazy DFA, so the dense DFA provides
        // minimal benefit while risking edge-case correctness issues.
        if (charClasses.hasQuitClasses()) {
            return null;
        }

        // Fast NFA state count check: skip dense DFA for complex patterns.
        // Ref: upstream wrappers.rs:851-860 (dfa_state_limit, default 30)
        if (nfa.stateCount() > DEFAULT_NFA_STATE_LIMIT) {
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

        // Extract into dense table with special-state taxonomy layout
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
     * Extracts the fully-computed DFACache into a flat int[] with the
     * special-state taxonomy layout.
     *
     * <p>Layout: dead at 0, quit at stride, then match-only, match+accel,
     * accel-only, normal states. All special states (dead, quit, match, accel)
     * are at the bottom of the ID space, with {@code maxSpecial} as the
     * single threshold guard.</p>
     *
     * <p>Ref: upstream/regex/regex-automata/src/dfa/special.rs:142-180,
     * upstream/regex/regex-automata/src/dfa/search.rs:98-181,
     * upstream/regex/regex-automata/src/dfa/accel.rs:449-458</p>
     */
    private static DenseDFA extractDense(DFACache cache, CharClasses charClasses,
                                          int[] startStates,
                                          int dead, int quit, int stride) {
        int totalStates = cache.stateCount(); // includes padding, dead, quit
        int classCount = charClasses.classCount();
        int eoiClass = charClasses.eoiClass();

        // ================================================================
        // Phase 1: Match-wrapper detection (unchanged from previous impl)
        // ================================================================

        // Pass 1a: check if dead-match is needed
        boolean needsDeadMatch = false;
        int deadWithFlagVal = dead | DFACache.MATCH_FLAG;
        for (int i = 3; i < totalStates && !needsDeadMatch; i++) {
            int rawSid = i * stride;
            boolean sourceMatch = cache.getState(rawSid).isMatch();
            for (int cls = 0; cls <= classCount; cls++) {
                if (cache.nextState(rawSid, cls) == deadWithFlagVal) {
                    if (!sourceMatch || cls == eoiClass) {
                        needsDeadMatch = true;
                        break;
                    }
                }
            }
        }

        // Pass 1b: find targets needing match-wrappers
        int[] matchWrapperMap = new int[totalStates];
        java.util.Arrays.fill(matchWrapperMap, -1);
        int wrapperCount = 0;
        int wrapperBase = totalStates + (needsDeadMatch ? 1 : 0);

        for (int i = 3; i < totalStates; i++) {
            int rawSid = i * stride;
            boolean sourceIsMatch = cache.getState(rawSid).isMatch();
            if (sourceIsMatch) continue;
            for (int cls = 0; cls <= classCount; cls++) {
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

        // ================================================================
        // Phase 2: Identify match states
        // ================================================================
        boolean[] isMatch = new boolean[denseStates];
        for (int i = 3; i < totalStates; i++) {
            int rawSid = i * stride;
            if (cache.getState(rawSid).isMatch()) {
                isMatch[i] = true;
            }
        }
        if (needsDeadMatch) {
            isMatch[deadMatchIdx] = true;
        }
        for (int i = 0; i < totalStates; i++) {
            if (matchWrapperMap[i] >= 0) {
                isMatch[matchWrapperMap[i]] = true;
            }
        }

        // ================================================================
        // Phase 3: Acceleration classification (BEFORE remap)
        // ================================================================
        // For each original state, count escape classes (transitions that
        // don't self-loop). <=3 means acceleratable.
        // Space exclusion: if space (' ', U+0020) is an escape char, skip.
        // Ref: upstream/regex/regex-automata/src/dfa/accel.rs:449-458
        boolean[] isAccel = new boolean[denseStates];

        for (int i = 3; i < totalStates; i++) {
            int rawSid = i * stride;
            int escapeCount = 0;
            boolean tooMany = false;
            boolean hasSpaceEscape = false;

            for (int cls = 0; cls < classCount; cls++) {
                int target = cache.nextState(rawSid, cls) & 0x7FFF_FFFF;
                if (target != rawSid) {
                    escapeCount++;
                    if (escapeCount > 3) { tooMany = true; break; }
                }
            }

            if (!tooMany && escapeCount > 0) {
                // Check space exclusion: classify space and see if it's an escape
                int spaceClass = charClasses.classify(' ');
                int spaceTarget = cache.nextState(rawSid, spaceClass) & 0x7FFF_FFFF;
                hasSpaceEscape = (spaceTarget != rawSid);

                if (!hasSpaceEscape) {
                    isAccel[i] = true;
                }
            }
        }

        // Propagate accel status to match-wrapper states
        for (int i = 0; i < totalStates; i++) {
            if (matchWrapperMap[i] >= 0 && isAccel[i]) {
                isAccel[matchWrapperMap[i]] = true;
            }
        }

        // Dead-match is never accel (all transitions point to dead = all escapes)

        // ================================================================
        // Phase 4: Classify and count states per category
        // ================================================================
        // Categories: match-only, match+accel, accel-only, normal
        int matchOnlyCount = 0;
        int matchAccelCount = 0;
        int accelOnlyCount = 0;

        for (int i = 3; i < denseStates; i++) {
            boolean m = isMatch[i];
            boolean a = isAccel[i];
            if (m && a) matchAccelCount++;
            else if (m) matchOnlyCount++;
            else if (a) accelOnlyCount++;
        }

        // ================================================================
        // Phase 5: Build remap table with new shuffle order
        // ================================================================
        // Layout: dead(0) → quit(stride) → match-only → match+accel → accel-only → normal
        int[] remap = new int[denseStates];
        remap[0] = 0;       // padding → dead
        remap[1] = 0;       // old dead → new dead at 0
        remap[2] = stride;  // old quit → new quit at stride

        // Assign positions for each category
        int nextMatchOnly = 2;  // starts after quit (index 2 → sid = 2*stride)
        int nextMatchAccel = nextMatchOnly + matchOnlyCount;
        int nextAccelOnly = nextMatchAccel + matchAccelCount;
        int nextNormal = nextAccelOnly + accelOnlyCount;

        // Track current position in each category
        int curMatchOnly = nextMatchOnly;
        int curMatchAccel = nextMatchAccel;
        int curAccelOnly = nextAccelOnly;
        int curNormal = nextNormal;

        for (int i = 3; i < denseStates; i++) {
            boolean m = isMatch[i];
            boolean a = isAccel[i];
            if (m && a) {
                remap[i] = curMatchAccel * stride;
                curMatchAccel++;
            } else if (m) {
                remap[i] = curMatchOnly * stride;
                curMatchOnly++;
            } else if (a) {
                remap[i] = curAccelOnly * stride;
                curAccelOnly++;
            } else {
                remap[i] = curNormal * stride;
                curNormal++;
            }
        }

        // Compute range boundaries
        int minMatch, maxMatch;
        if (matchOnlyCount + matchAccelCount > 0) {
            minMatch = nextMatchOnly * stride;
            maxMatch = (nextMatchAccel + matchAccelCount - 1) * stride;
        } else {
            minMatch = -1;
            maxMatch = -2;  // trivially false range check
        }

        int minAccel, maxAccel;
        if (matchAccelCount + accelOnlyCount > 0) {
            minAccel = nextMatchAccel * stride;
            maxAccel = (nextAccelOnly + accelOnlyCount - 1) * stride;
        } else {
            minAccel = -1;
            maxAccel = -2;
        }

        int maxSpecial = Math.max(maxMatch, maxAccel);
        // If no match and no accel states, maxSpecial should be quit (stride)
        if (maxSpecial < stride) {
            maxSpecial = stride;
        }

        int newDeadMatch = needsDeadMatch ? remap[deadMatchIdx] : -1;

        // ================================================================
        // Phase 6: Build the new transition table
        // ================================================================
        int[] newTable = new int[denseStates * stride];

        // Dead (at 0): all transitions → 0
        for (int cls = 0; cls <= classCount; cls++) {
            newTable[cls] = 0;
        }

        // Quit (at stride): all transitions → stride
        for (int cls = 0; cls <= classCount; cls++) {
            newTable[stride + cls] = stride;
        }

        // Dead-match: all transitions → dead (0)
        if (needsDeadMatch) {
            for (int cls = 0; cls <= classCount; cls++) {
                newTable[newDeadMatch + cls] = 0;
            }
        }

        // Copy real states, handling MATCH_FLAG
        // Build match-wrapper remap: old target state index → new match-wrapper sid
        int[] wrapperNewSid = new int[totalStates];
        for (int i = 0; i < totalStates; i++) {
            wrapperNewSid[i] = (matchWrapperMap[i] >= 0) ? remap[matchWrapperMap[i]] : -1;
        }

        for (int i = 3; i < totalStates; i++) {
            int oldRawSid = i * stride;
            int newSid = remap[i];
            boolean sourceIsMatch = cache.getState(oldRawSid).isMatch();

            for (int cls = 0; cls <= classCount; cls++) {
                int target = cache.nextState(oldRawSid, cls);
                boolean hasFlag = (target & DFACache.MATCH_FLAG) != 0;
                int rawTarget = target & 0x7FFF_FFFF;

                if (hasFlag && rawTarget == dead && (!sourceIsMatch || cls == eoiClass)) {
                    newTable[newSid + cls] = newDeadMatch;
                } else if (hasFlag && rawTarget == dead) {
                    newTable[newSid + cls] = 0;  // dead is now 0
                } else if (hasFlag && !sourceIsMatch) {
                    int targetIdx = rawTarget / stride;
                    if (targetIdx >= 3 && targetIdx < totalStates
                            && wrapperNewSid[targetIdx] >= 0) {
                        newTable[newSid + cls] = wrapperNewSid[targetIdx];
                    } else {
                        newTable[newSid + cls] = (targetIdx < totalStates) ? remap[targetIdx] : 0;
                    }
                } else {
                    int targetIdx = rawTarget / stride;
                    newTable[newSid + cls] = (targetIdx < totalStates) ? remap[targetIdx] : 0;
                }
            }
        }

        // Copy match-wrapper states: same transitions as their original targets
        for (int i = 3; i < totalStates; i++) {
            if (matchWrapperMap[i] < 0) continue;
            int wrapSid = remap[matchWrapperMap[i]];
            int originalSid = remap[i];
            System.arraycopy(newTable, originalSid, newTable, wrapSid, stride);
        }

        // Remap start states
        int[] newStartStates = new int[startStates.length];
        for (int i = 0; i < startStates.length; i++) {
            int rawSid = startStates[i] & 0x7FFF_FFFF;
            int stateIdx = rawSid / stride;
            newStartStates[i] = (stateIdx < remap.length) ? remap[stateIdx] : rawSid;
        }

        // ================================================================
        // Phase 7: Build acceleration tables from the REMAPPED transition table
        // ================================================================
        // For each accel state, build a boolean[128] escape table (true for
        // ASCII chars that are escape chars) and detect single-escape-char
        // states for indexOf optimization.
        boolean[][] accelTablesArr = null;
        char[] accelSingleEscapeArr = null;
        if (matchAccelCount + accelOnlyCount > 0) {
            int accelCount = matchAccelCount + accelOnlyCount;
            accelTablesArr = new boolean[accelCount][];
            accelSingleEscapeArr = new char[accelCount];

            for (int i = 3; i < denseStates; i++) {
                if (!isAccel[i]) continue;
                int newSid = remap[i];
                int accelIdx = (newSid - minAccel) / stride;

                // Build boolean[128] escape table
                boolean[] table = new boolean[128];
                for (int c = 0; c < 128; c++) {
                    int cls = charClasses.classify((char) c);
                    if (newTable[newSid + cls] != newSid) {
                        table[c] = true;
                    }
                }
                accelTablesArr[accelIdx] = table;

                // Count ASCII escape chars to detect single-escape states
                int escapeCharCount = 0;
                char singleEscape = 0;
                for (int c = 0; c < 128; c++) {
                    if (table[c]) {
                        escapeCharCount++;
                        singleEscape = (char) c;
                    }
                }
                if (escapeCharCount == 1) {
                    accelSingleEscapeArr[accelIdx] = singleEscape;
                }
            }
        }

        return new DenseDFA(newTable, charClasses, newStartStates,
                0, stride,  // dead=0, quit=stride
                minMatch, maxMatch, minAccel, maxAccel,
                maxSpecial, denseStates,
                accelTablesArr, accelSingleEscapeArr,
                needsDeadMatch ? remap[deadMatchIdx] : -1);
    }
}
