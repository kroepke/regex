package lol.ohai.regex.automata.dfa.onepass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.State;
import lol.ohai.regex.automata.nfa.thompson.Transition;
import lol.ohai.regex.automata.util.SparseSet;

/**
 * Compiles a Thompson NFA into a one-pass DFA, or returns {@code null} if the
 * NFA is not one-pass (i.e., contains ambiguity).
 *
 * <p>The algorithm performs a worklist-driven construction where each NFA state
 * that begins a char-consuming sub-graph becomes a single DFA state. Epsilon
 * transitions (look-around, captures, alternations) are followed via DFS,
 * accumulating assertions and slot updates into {@link Epsilons}. If two
 * different epsilon paths lead to the same NFA state, or if two different
 * transitions map to the same equivalence class in the same DFA state, the
 * pattern is not one-pass and construction bails out.
 *
 * <p>After construction, match states are shuffled to the end of the table so
 * that match detection is a simple state-ID comparison.
 *
 * <p>Ref: upstream/regex/regex-automata/src/dfa/onepass.rs:454-924
 */
public final class OnePassBuilder {

    private final NFA nfa;
    private final CharClasses charClasses;
    private final TransitionTable table;
    private final int explicitSlotStart;
    private final int explicitSlotCount;

    // NFA-to-DFA state mapping. 0 (DEAD) means "not yet mapped".
    private final int[] nfaToDfaId;

    // Worklist of NFA state IDs whose DFA states haven't been compiled yet.
    private final List<Integer> worklist;

    // Epsilon closure DFS stack: parallel arrays for NFA state ID and epsilons.
    private int[] stackNfa;
    private long[] stackEps;
    private int stackSize;

    // Visited set for cycle detection within each DFA state's epsilon closure.
    private final SparseSet seen;

    // Whether a match state has been seen during the current DFA state's
    // epsilon closure. Set per outer-worklist iteration.
    private boolean matched;

    private OnePassBuilder(NFA nfa, CharClasses charClasses,
                           TransitionTable table,
                           int explicitSlotStart, int explicitSlotCount) {
        this.nfa = nfa;
        this.charClasses = charClasses;
        this.table = table;
        this.explicitSlotStart = explicitSlotStart;
        this.explicitSlotCount = explicitSlotCount;
        this.nfaToDfaId = new int[nfa.stateCount()];
        this.worklist = new ArrayList<>();
        this.stackNfa = new int[64];
        this.stackEps = new long[64];
        this.seen = new SparseSet(nfa.stateCount());
    }

    /**
     * Attempts to build a one-pass DFA from the given NFA and char classes.
     *
     * @param nfa         the Thompson NFA (single pattern, forward)
     * @param charClasses the equivalence class map
     * @return the one-pass DFA, or {@code null} if the NFA is not one-pass
     */
    public static OnePassDFA build(NFA nfa, CharClasses charClasses) {
        // Single-pattern: 2 implicit slots (group 0 start/end).
        int explicitSlotStart = 2;
        int explicitSlotCount = Math.max(0, nfa.captureSlotCount() - explicitSlotStart);
        if (explicitSlotCount > Epsilons.MAX_SLOTS) {
            return null; // too many capture groups
        }

        int alphabetLen = charClasses.classCount();
        TransitionTable table = new TransitionTable(alphabetLen);

        // State 0 = DEAD state
        int deadId = table.addState();
        assert deadId == TransitionTable.DEAD;

        OnePassBuilder builder = new OnePassBuilder(
                nfa, charClasses, table, explicitSlotStart, explicitSlotCount);
        return builder.doBuild();
    }

    private OnePassDFA doBuild() {
        // Add anchored start state
        int startNfaId = nfa.startAnchored();
        int startDfaId = addDfaStateForNfa(startNfaId);
        if (startDfaId < 0) return null;

        // Process worklist — each entry is an NFA state whose DFA state
        // needs epsilon closure + transition compilation.
        while (!worklist.isEmpty()) {
            int currentNfaId = worklist.removeLast();
            int dfaId = nfaToDfaId[currentNfaId];
            matched = false;
            seen.clear();
            stackSize = 0;

            // Push initial NFA state
            if (!stackPush(currentNfaId, Epsilons.EMPTY)) {
                return null;
            }

            while (stackSize > 0) {
                stackSize--;
                int nfaId = stackNfa[stackSize];
                long epsilons = stackEps[stackSize];

                State state = nfa.state(nfaId);

                switch (state) {
                    case State.CharRange cr -> {
                        if (!handleCharTransition(dfaId, cr.start(), cr.end(),
                                cr.next(), epsilons)) {
                            return null;
                        }
                    }
                    case State.Sparse sp -> {
                        for (Transition t : sp.transitions()) {
                            if (!handleCharTransition(dfaId, t.start(), t.end(),
                                    t.next(), epsilons)) {
                                return null;
                            }
                        }
                    }
                    case State.Look look -> {
                        long newEps = Epsilons.withLook(epsilons, look.look().ordinal());
                        if (!stackPush(look.next(), newEps)) {
                            return null;
                        }
                    }
                    case State.Union u -> {
                        // Push in reverse order so highest-priority is popped first
                        int[] alts = u.alternates();
                        for (int i = alts.length - 1; i >= 0; i--) {
                            if (!stackPush(alts[i], epsilons)) {
                                return null;
                            }
                        }
                    }
                    case State.BinaryUnion bu -> {
                        // Push alt2 first (lower priority), then alt1
                        if (!stackPush(bu.alt2(), epsilons)) return null;
                        if (!stackPush(bu.alt1(), epsilons)) return null;
                    }
                    case State.Capture cap -> {
                        long newEps = epsilons;
                        int slot = cap.slotIndex();
                        if (slot >= explicitSlotStart) {
                            int offset = slot - explicitSlotStart;
                            if (offset < Epsilons.MAX_SLOTS) {
                                newEps = Epsilons.withSlot(epsilons, offset);
                            }
                        }
                        if (!stackPush(cap.next(), newEps)) return null;
                    }
                    case State.Match m -> {
                        // Multiple paths to match for same DFA state = ambiguous
                        if (matched) return null;
                        matched = true;
                        table.setPatternEpsilons(dfaId,
                                TransitionTable.encodePatternEpsilons(
                                        m.patternId(), epsilons));
                    }
                    case State.Fail ignored -> {
                        // Dead end
                    }
                }
            }
        }

        // Shuffle match states to end of table
        int minMatchId = shuffleMatchStates();
        table.shrink();

        return new OnePassDFA(table, charClasses, nfa,
                nfaToDfaId[nfa.startAnchored()], minMatchId,
                explicitSlotStart, explicitSlotCount);
    }

    /**
     * Handles a char-consuming transition: ensures a DFA state exists for the
     * target NFA state, then compiles the transition for each equivalence class
     * in the char range.
     */
    private boolean handleCharTransition(int dfaId, int rangeStart, int rangeEnd,
                                          int nextNfaId, long epsilons) {
        int nextDfaId = addDfaStateForNfa(nextNfaId);
        if (nextDfaId < 0) return false;

        long newTrans = TransitionTable.encode(nextDfaId, matched, epsilons);

        // Iterate through the char range checking each equivalence class.
        // We skip duplicate class IDs for efficiency.
        // Also skip surrogate chars (0xD800-0xDFFF) — these are intermediate
        // states in the UTF-16 surrogate-pair encoding and cause false
        // conflicts in the one-pass check. Patterns needing supplementary
        // plane chars will fall back to PikeVM.
        int prevClassId = -1;
        for (int c = rangeStart; c <= rangeEnd; c++) {
            if (c >= 0xD800 && c <= 0xDFFF) continue; // skip surrogates
            int classId = charClasses.classify((char) c);
            if (classId == prevClassId) {
                continue; // same equivalence class, already handled
            }
            prevClassId = classId;

            long oldTrans = table.get(dfaId, classId);
            if (oldTrans == 0) {
                // First transition for this class — set it.
                table.set(dfaId, classId, newTrans);
            } else {
                // Compare ignoring matchWins bit. The matchWins flag depends
                // on whether a Match state was seen earlier in the DFS, which
                // varies by path order (e.g., \d+ loop-back vs match exit).
                // Upstream handles this by updating matchWins on existing
                // transitions when a match is seen (onepass.rs:783-786).
                // A real conflict is when state ID or epsilons differ.
                long oldNoMw = oldTrans & ~TransitionTable.MATCH_WINS_BIT;
                long newNoMw = newTrans & ~TransitionTable.MATCH_WINS_BIT;
                if (oldNoMw != newNoMw) {
                    return false; // conflict — not one-pass
                }
                // If we now have matchWins=true but old didn't, upgrade it.
                // (Once a match is reachable from this DFA state, outgoing
                // transitions should prefer stopping at the match.)
                if (matched && !TransitionTable.matchWins(oldTrans)) {
                    table.set(dfaId, classId, oldTrans | TransitionTable.MATCH_WINS_BIT);
                }
            }
        }
        return true;
    }

    /**
     * Gets or creates a DFA state for the given NFA state.
     * If the NFA state already has a mapped DFA state, returns it.
     * Otherwise allocates a new DFA state and adds the NFA state to the worklist.
     *
     * @return the DFA state ID, or -1 if the state limit is exceeded
     */
    private int addDfaStateForNfa(int nfaId) {
        int existing = nfaToDfaId[nfaId];
        if (existing != TransitionTable.DEAD) {
            return existing;
        }
        int dfaId = table.addState();
        if (dfaId > TransitionTable.MAX_STATE_ID) {
            return -1;
        }
        nfaToDfaId[nfaId] = dfaId;
        worklist.add(nfaId);
        return dfaId;
    }

    /**
     * Pushes an NFA state onto the epsilon closure DFS stack.
     * Returns false if the state was already seen (cycle = not one-pass).
     */
    private boolean stackPush(int nfaId, long epsilons) {
        if (!seen.insert(nfaId)) {
            return false; // already visited — ambiguous
        }
        if (stackSize >= stackNfa.length) {
            stackNfa = grow(stackNfa);
            stackEps = growLong(stackEps);
        }
        stackNfa[stackSize] = nfaId;
        stackEps[stackSize] = epsilons;
        stackSize++;
        return true;
    }

    /**
     * Shuffles match states to the end of the transition table.
     * Returns the minimum match state ID (stride-multiplied).
     */
    private int shuffleMatchStates() {
        int stateCount = table.stateCount();
        if (stateCount <= 1) {
            return Integer.MAX_VALUE;
        }

        int stride = table.stride();
        int alphabetLen = table.alphabetLen();

        // Identify match states
        boolean[] isMatch = new boolean[stateCount];
        int matchCount = 0;
        for (int i = 0; i < stateCount; i++) {
            int sid = i * stride;
            if (TransitionTable.hasPattern(table.getPatternEpsilons(sid))) {
                isMatch[i] = true;
                matchCount++;
            }
        }

        if (matchCount == 0) {
            return Integer.MAX_VALUE;
        }

        // Build remap: non-match states first, then match states
        int[] remap = new int[stateCount];
        int nonMatchIdx = 0;
        int matchIdx = stateCount - matchCount;

        for (int i = 0; i < stateCount; i++) {
            if (isMatch[i]) {
                remap[i] = matchIdx++ * stride;
            } else {
                remap[i] = nonMatchIdx++ * stride;
            }
        }

        int minMatchId = (stateCount - matchCount) * stride;

        // Build new table by copying and remapping
        long[] oldTable = table.rawTable();
        long[] newTable = new long[stateCount * stride];

        for (int i = 0; i < stateCount; i++) {
            int oldSid = i * stride;
            int newSid = remap[i];

            // Copy and remap char transitions
            for (int col = 0; col < alphabetLen; col++) {
                long trans = oldTable[oldSid + col];
                if (trans != 0) {
                    int targetOldSid = TransitionTable.stateId(trans);
                    int targetIdx = targetOldSid / stride;
                    int targetNewSid = remap[targetIdx];
                    trans = TransitionTable.encode(
                            targetNewSid,
                            TransitionTable.matchWins(trans),
                            TransitionTable.epsilons(trans));
                }
                newTable[newSid + col] = trans;
            }

            // Copy pattern epsilons as-is (stores pattern ID, not state ID)
            newTable[newSid + alphabetLen] = oldTable[oldSid + alphabetLen];
        }

        // Write back into the table
        for (int i = 0; i < stateCount; i++) {
            int sid = i * stride;
            for (int col = 0; col < alphabetLen; col++) {
                table.set(sid, col, newTable[sid + col]);
            }
            table.setPatternEpsilons(sid, newTable[sid + alphabetLen]);
        }

        // Also need to update nfaToDfaId mapping for the start state
        // (and any other references). The caller uses nfaToDfaId[startAnchored]
        // to get the start DFA ID, so we must remap it.
        for (int i = 0; i < nfaToDfaId.length; i++) {
            int oldDfaId = nfaToDfaId[i];
            if (oldDfaId != TransitionTable.DEAD) {
                int idx = oldDfaId / stride;
                nfaToDfaId[i] = remap[idx];
            }
        }

        return minMatchId;
    }

    private static int[] grow(int[] arr) {
        return Arrays.copyOf(arr, arr.length * 2);
    }

    private static long[] growLong(long[] arr) {
        return Arrays.copyOf(arr, arr.length * 2);
    }
}
