package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.State;
import lol.ohai.regex.automata.nfa.thompson.Transition;
import lol.ohai.regex.automata.util.Input;

import java.util.Arrays;

/**
 * A lazy (hybrid) DFA that builds states on demand during search.
 *
 * <p>Immutable and thread-safe. All mutable state lives in {@link DFACache},
 * which is created per-thread via {@link #createCache()}.</p>
 *
 * <p>The DFA lazily constructs states via powerset construction: each DFA state
 * is a set of simultaneously active NFA states. Transitions are computed
 * on first encounter and cached in the {@link DFACache#nextState transition table}.</p>
 *
 * <p>Matches are delayed by one char unit, following the approach used by the
 * Rust regex crate. When computing the next DFA state, if the source state
 * contains an NFA Match state, the destination is flagged as a match state.
 * For leftmost-first semantics, no further NFA states are added after the
 * Match state, causing the destination to become effectively dead. This
 * ensures the search terminates after finding the leftmost-first match.</p>
 *
 * <p>Patterns containing look-assertions ({@code ^}, {@code $}, {@code \b}, etc.)
 * are not supported. Use {@link #create} which returns {@code null} for such patterns.</p>
 */
public final class LazyDFA {

    private static final int DEFAULT_CACHE_CAPACITY = 2 * 1024 * 1024; // 2MB
    private static final int MIN_CLEAR_COUNT = 3;
    private static final int MIN_CHARS_PER_STATE = 10;

    private final NFA nfa;
    private final CharClasses charClasses;

    private LazyDFA(NFA nfa, CharClasses charClasses) {
        this.nfa = nfa;
        this.charClasses = charClasses;
    }

    /**
     * Creates a LazyDFA for the given NFA, or returns {@code null} if the NFA
     * contains look-assertion states which the lazy DFA cannot handle.
     */
    public static LazyDFA create(NFA nfa, CharClasses charClasses) {
        if (hasLookStates(nfa)) return null;
        return new LazyDFA(nfa, charClasses);
    }

    /** Creates a per-search cache. */
    public DFACache createCache() {
        return new DFACache(charClasses, DEFAULT_CACHE_CAPACITY, nfa.stateCount());
    }

    /**
     * Forward search for the end position of the leftmost-first match.
     *
     * <p>Because matches are delayed by one char unit, the match end position
     * reported when a match state is entered equals the current {@code pos},
     * which is already one past the last char consumed by the match.</p>
     *
     * @param input the search input (haystack + bounds + anchored flag)
     * @param cache per-search mutable state
     * @return Match(endPos), NoMatch, or GaveUp(offset)
     */
    public SearchResult searchFwd(Input input, DFACache cache) {
        char[] haystack = input.haystack();
        int pos = input.start();
        int end = input.end();
        int stride = charClasses.stride();
        int dead = DFACache.dead(stride);
        int quit = DFACache.quit(stride);

        int sid = getOrComputeStartState(input, cache);
        if (sid == dead) return new SearchResult.NoMatch();
        if (sid == quit) return new SearchResult.GaveUp(pos);

        int lastMatchEnd = -1;

        // Start state is NEVER a match state with 1-char match delay.
        // (The start state's match flag is carried to the first transition's
        //  destination state instead.)

        while (pos < end) {
            int classId = charClasses.classify(haystack[pos]);
            int nextSid = cache.nextState(sid, classId);

            if (nextSid > quit) {
                // Fast path: normal cached transition, non-special, non-match
                sid = nextSid;
                pos++;
                cache.charsSearched++;
                continue;
            }

            if (nextSid < 0) {
                // Match state (high bit set). With 1-char delay, the match
                // ended at 'pos' (the char we just consumed triggered the
                // delayed match notification). Record pos as the exclusive end.
                lastMatchEnd = pos;
                sid = nextSid & 0x7FFF_FFFF;
                pos++;
                cache.charsSearched++;
                continue;
            }

            // Slow path: UNKNOWN, DEAD, or QUIT
            if (nextSid == DFACache.UNKNOWN) {
                nextSid = computeNextState(cache, sid, classId);
                if (nextSid == quit) return new SearchResult.GaveUp(pos);
                cache.setTransition(sid, classId, nextSid);
                sid = nextSid;
                if (sid < 0) {
                    lastMatchEnd = pos;
                    sid = sid & 0x7FFF_FFFF;
                }
                pos++;
                cache.charsSearched++;
                continue;
            }
            if (nextSid == dead) {
                // Dead state: no further progress possible.
                // If we have a match recorded, return it. Otherwise, no match.
                break;
            }
            // nextSid == quit
            return new SearchResult.GaveUp(pos);
        }

        // EOI transition: check if the current state has a delayed match
        // that should be reported at end-of-input.
        // With 1-char delay, if the current state's StateContent has isMatch=true,
        // it means there's a pending match that should be reported at EOI.
        int rawSid = sid & 0x7FFF_FFFF;
        if (rawSid != dead && rawSid != quit) {
            StateContent currentContent = cache.getState(rawSid);
            if (currentContent.isMatch()) {
                lastMatchEnd = end;
            }
        }

        if (lastMatchEnd >= 0) return new SearchResult.Match(lastMatchEnd);
        return new SearchResult.NoMatch();
    }

    /**
     * Reverse search for the start position of the leftmost-first match.
     *
     * <p>Searches backwards from {@code input.end() - 1} to {@code input.start()}.
     * Uses the same 1-char match delay as forward search. When a match-flagged
     * state is entered at position {@code pos}, the match start is {@code pos + 1}
     * (the char that triggered the delayed match was at {@code pos}, and the
     * match extends from {@code pos + 1} onward in forward coordinates).</p>
     *
     * @param input the search input (haystack + bounds + anchored flag).
     *              Typically anchored at the forward match's end position.
     * @param cache per-search mutable state
     * @return Match(startPos), NoMatch, or GaveUp(offset)
     */
    public SearchResult searchRev(Input input, DFACache cache) {
        char[] haystack = input.haystack();
        int start = input.start();
        int end = input.end();
        int stride = charClasses.stride();
        int dead = DFACache.dead(stride);
        int quit = DFACache.quit(stride);

        int sid = getOrComputeStartState(input, cache);
        if (sid == dead) return new SearchResult.NoMatch();
        if (sid == quit) return new SearchResult.GaveUp(end);

        int lastMatchStart = -1;

        int pos = end - 1;
        while (pos >= start) {
            int classId = charClasses.classify(haystack[pos]);
            int nextSid = cache.nextState(sid, classId);

            if (nextSid > quit) {
                sid = nextSid;
                pos--;
                cache.charsSearched++;
                continue;
            }

            if (nextSid < 0) {
                lastMatchStart = pos + 1;
                sid = nextSid & 0x7FFF_FFFF;
                pos--;
                cache.charsSearched++;
                continue;
            }

            if (nextSid == DFACache.UNKNOWN) {
                nextSid = computeNextState(cache, sid, classId);
                if (nextSid == quit) return new SearchResult.GaveUp(pos);
                cache.setTransition(sid, classId, nextSid);
                sid = nextSid;
                if (sid < 0) {
                    lastMatchStart = pos + 1;
                    sid = sid & 0x7FFF_FFFF;
                }
                pos--;
                cache.charsSearched++;
                continue;
            }
            if (nextSid == dead) {
                break;
            }
            return new SearchResult.GaveUp(pos);
        }

        // EOI: check for delayed match at left boundary
        int rawSid = sid & 0x7FFF_FFFF;
        if (rawSid != dead && rawSid != quit) {
            StateContent currentContent = cache.getState(rawSid);
            if (currentContent.isMatch()) {
                lastMatchStart = start;
            }
        }

        if (lastMatchStart >= 0) return new SearchResult.Match(lastMatchStart);
        return new SearchResult.NoMatch();
    }

    // -- Internal: start state --

    private int getOrComputeStartState(Input input, DFACache cache) {
        if (input.isAnchored()) {
            if (cache.startAnchored == DFACache.UNKNOWN) {
                cache.startAnchored = computeStartState(nfa.startAnchored(), cache);
            }
            return cache.startAnchored;
        } else {
            if (cache.startUnanchored == DFACache.UNKNOWN) {
                cache.startUnanchored = computeStartState(nfa.startUnanchored(), cache);
            }
            return cache.startUnanchored;
        }
    }

    private int computeStartState(int nfaStartId, DFACache cache) {
        // Compute epsilon closure of the NFA start state.
        // The start state is never a match state (matches are delayed by 1 char).
        // We still record whether Match NFA states are present in the StateContent
        // so that computeNextState can detect and delay them.
        cache.nfaStateSet.clear();
        boolean hasMatch = epsilonClosure(cache, nfaStartId);
        int[] nfaStates = collectSorted(cache);
        if (nfaStates.length == 0) return DFACache.dead(charClasses.stride());

        // hasMatch is stored in StateContent for use during transition computation,
        // but the DFA state ID itself is NOT flagged as a match (delay by 1).
        StateContent content = new StateContent(nfaStates, hasMatch);
        int sid = cache.allocateState(content);
        // Strip the match flag from the state ID — start states must not be match states.
        return sid & 0x7FFF_FFFF;
    }

    // -- Internal: determinization --

    /**
     * Compute the next DFA state from source state on the given class ID.
     *
     * <p>Implements 1-char match delay with leftmost-first semantics:
     * <ol>
     *   <li>If the source state contains an NFA Match state, mark the
     *       destination as a match state.</li>
     *   <li>For leftmost-first: once a Match NFA state is encountered while
     *       iterating source NFA states, stop adding more NFA states. This
     *       causes the destination to become effectively dead (no outgoing
     *       char transitions), ensuring the search terminates.</li>
     * </ol>
     *
     * <p>NFA states in the source are iterated in sorted (ascending ID) order.
     * Char-consuming states (CharRange, Sparse) that appear before the Match
     * state in sort order still have their transitions followed, which allows
     * greedy match extension.</p>
     */
    private int computeNextState(DFACache cache, int sourceSid, int classId) {
        int rawSourceId = sourceSid & 0x7FFF_FFFF;
        StateContent sourceContent = cache.getState(rawSourceId);

        cache.nfaStateSet.clear();
        boolean isMatch = false;

        if (classId == charClasses.eoiClass()) {
            // EOI: the only thing that matters is whether the source state
            // contains a Match NFA state (delayed match at end-of-input).
            if (sourceContent.isMatch()) {
                // Return a match-flagged dead state
                return DFACache.dead(charClasses.stride()) | DFACache.MATCH_FLAG;
            }
            return DFACache.dead(charClasses.stride());
        }

        // Iterate source NFA states in sorted order.
        // For leftmost-first: once we see a Match NFA state, we mark the
        // destination as a match and stop processing further NFA states.
        for (int nfaStateId : sourceContent.nfaStates()) {
            State state = nfa.state(nfaStateId);
            switch (state) {
                case State.CharRange cr -> {
                    if (charInRange(classId, cr.start(), cr.end())) {
                        epsilonClosure(cache, cr.next());
                    }
                }
                case State.Sparse sp -> {
                    for (Transition t : sp.transitions()) {
                        if (charInRange(classId, t.start(), t.end())) {
                            epsilonClosure(cache, t.next());
                            break;
                        }
                    }
                }
                case State.Match ignored -> {
                    // 1-char match delay: the SOURCE state has a Match,
                    // so the DESTINATION state gets the match flag.
                    isMatch = true;
                    // Leftmost-first: stop processing further NFA states.
                    // NFA states with higher IDs than Match are skipped,
                    // making the destination state effectively dead after
                    // this match is recorded.
                    break;
                }
                default -> {
                    // Non-char-consuming states (Union, Capture, Fail)
                    // are resolved during epsilon closure and have no char
                    // transitions. They may appear in the set but are no-ops here.
                }
            }
            // If we found a match (leftmost-first break), exit the outer loop
            if (isMatch) break;
        }

        int[] nfaStates = collectSorted(cache);

        // If the destination has NFA states, check if any of them is a Match
        // (for the StateContent's isMatch flag, used in future transitions).
        boolean destHasMatch = false;
        for (int nfaStateId : nfaStates) {
            if (nfa.state(nfaStateId) instanceof State.Match) {
                destHasMatch = true;
                break;
            }
        }

        if (nfaStates.length == 0 && !isMatch) {
            return DFACache.dead(charClasses.stride());
        }
        if (nfaStates.length == 0 && isMatch) {
            // Match-flagged dead: the destination has no NFA states but IS a match.
            // We use the dead state ID with the match flag.
            return DFACache.dead(charClasses.stride()) | DFACache.MATCH_FLAG;
        }

        StateContent content = new StateContent(nfaStates, destHasMatch);
        int sid = allocateOrGiveUp(cache, content);
        if (isMatch) {
            sid = sid | DFACache.MATCH_FLAG;
        }
        return sid;
    }

    /**
     * Check if the given equivalence class overlaps with [rangeStart, rangeEnd].
     */
    private boolean charInRange(int classId, int rangeStart, int rangeEnd) {
        if (rangeStart > 0xFFFF || rangeEnd > 0xFFFF) return false;
        return classId >= charClasses.classify((char) rangeStart)
                && classId <= charClasses.classify((char) rangeEnd);
    }

    private int allocateOrGiveUp(DFACache cache, StateContent content) {
        if (cache.isFull()) {
            cache.clear(content);
            if (shouldGiveUp(cache)) {
                return DFACache.quit(charClasses.stride());
            }
        }
        return cache.allocateState(content);
    }

    private boolean shouldGiveUp(DFACache cache) {
        if (cache.clearCount() < MIN_CLEAR_COUNT) return false;
        return cache.charsSearched < (long) MIN_CHARS_PER_STATE * cache.statesCreated;
    }

    // -- Internal: epsilon closure --

    /**
     * Compute epsilon closure starting from the given NFA state.
     * Adds all reachable states (including char-consuming and Match states)
     * to {@code cache.nfaStateSet}.
     *
     * @return true if a Match state was reached
     */
    private boolean epsilonClosure(DFACache cache, int startStateId) {
        boolean isMatch = false;
        int[] stack = cache.closureStack;
        int stackTop = 0;
        stack[stackTop++] = startStateId;

        while (stackTop > 0) {
            int sid = stack[--stackTop];
            if (!cache.nfaStateSet.insert(sid)) continue;

            State state = nfa.state(sid);
            switch (state) {
                case State.CharRange ignored -> { /* char-consuming, keep in set */ }
                case State.Sparse ignored -> { /* char-consuming, keep in set */ }
                case State.Match ignored -> isMatch = true;
                case State.Union u -> {
                    for (int i = u.alternates().length - 1; i >= 0; i--) {
                        stack = ensureStackCapacity(stack, stackTop);
                        stack[stackTop++] = u.alternates()[i];
                    }
                }
                case State.BinaryUnion bu -> {
                    stack = ensureStackCapacity(stack, stackTop + 1);
                    stack[stackTop++] = bu.alt2();
                    stack[stackTop++] = bu.alt1();
                }
                case State.Capture cap -> {
                    stack = ensureStackCapacity(stack, stackTop);
                    stack[stackTop++] = cap.next();
                }
                case State.Look ignored -> {
                    // Should not happen -- patterns with Look bailed out.
                }
                case State.Fail ignored -> { /* dead end */ }
            }
        }

        cache.closureStack = stack;
        return isMatch;
    }

    private static int[] ensureStackCapacity(int[] stack, int needed) {
        if (needed < stack.length) return stack;
        return java.util.Arrays.copyOf(stack, stack.length * 2);
    }

    /** Collect the NFA state set into a sorted array. */
    private int[] collectSorted(DFACache cache) {
        int size = cache.nfaStateSet.size();
        int[] result = new int[size];
        for (int i = 0; i < size; i++) {
            result[i] = cache.nfaStateSet.get(i);
        }
        Arrays.sort(result);
        return result;
    }

    /** Check if any NFA state is a Look state. */
    private static boolean hasLookStates(NFA nfa) {
        for (int i = 0; i < nfa.stateCount(); i++) {
            if (nfa.state(i) instanceof State.Look) return true;
        }
        return false;
    }
}
