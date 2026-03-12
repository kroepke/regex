package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.State;
import lol.ohai.regex.automata.nfa.thompson.Transition;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.hir.LookKind;

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
 * <p>Look-assertions ({@code ^}, {@code $}, {@code \b}, etc.) are handled by
 * encoding look-behind context ({@code lookHave}, {@code lookNeed},
 * {@code isFromWord}, {@code isHalfCrlf}) in the DFA state key. The epsilon
 * closure conditionally follows {@link State.Look} transitions based on which
 * assertions are currently satisfied. When a pattern has no look-assertions,
 * the fast path is identical to the look-free case with zero overhead.</p>
 */
public final class LazyDFA {

    private static final int DEFAULT_CACHE_CAPACITY = 2 * 1024 * 1024; // 2MB
    private static final int MIN_CLEAR_COUNT = 3;
    private static final int MIN_CHARS_PER_STATE = 10;

    private final NFA nfa;
    private final CharClasses charClasses;
    private final LookSet lookSetAny;

    private LazyDFA(NFA nfa, CharClasses charClasses) {
        this.nfa = nfa;
        this.charClasses = charClasses;
        this.lookSetAny = nfa.lookSetAny();
    }

    /**
     * Creates a LazyDFA for the given NFA, or {@code null} if the NFA
     * contains look-assertion kinds that the DFA cannot handle (e.g.
     * Unicode word boundaries, CRLF-aware line anchors).
     */
    public static LazyDFA create(NFA nfa, CharClasses charClasses) {
        LookSet looks = nfa.lookSetAny();
        // CRLF line anchors always bail — DFA can't handle them
        if (looks.containsCrlf()) {
            return null;
        }
        // Unicode word boundaries require quit chars to be configured.
        // Without quit chars, the DFA would use ASCII word-char tables
        // on non-ASCII input, producing incorrect results.
        if (looks.containsUnicodeWord() && !charClasses.hasQuitClasses()) {
            return null;
        }
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

        // EOI transition: compute next state with EOI class to resolve
        // look-ahead assertions (END_TEXT, END_LINE) at end-of-input.
        int rawSid = sid & 0x7FFF_FFFF;
        if (rawSid != dead && rawSid != quit) {
            int eoiClassId = charClasses.eoiClass();
            int eoiSid = computeNextState(cache, rawSid, eoiClassId);
            if (eoiSid < 0) {
                // Match-flagged result from EOI
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

        // EOI: compute next state with EOI class to resolve look-ahead
        // assertions at left boundary.
        int rawSid = sid & 0x7FFF_FFFF;
        if (rawSid != dead && rawSid != quit) {
            int eoiClassId = charClasses.eoiClass();
            int eoiSid = computeNextState(cache, rawSid, eoiClassId);
            if (eoiSid < 0) {
                lastMatchStart = start;
            }
        }

        if (lastMatchStart >= 0) return new SearchResult.Match(lastMatchStart);
        return new SearchResult.NoMatch();
    }

    // -- Internal: start state --

    private int getOrComputeStartState(Input input, DFACache cache) {
        if (lookSetAny.isEmpty()) {
            // Fast path: no look-assertions, use simple anchored/unanchored
            if (input.isAnchored()) {
                if (cache.startAnchored == DFACache.UNKNOWN) {
                    cache.startAnchored = computeStartState(nfa.startAnchored(),
                            cache, LookSet.EMPTY, false, false);
                }
                return cache.startAnchored;
            } else {
                if (cache.startUnanchored == DFACache.UNKNOWN) {
                    cache.startUnanchored = computeStartState(nfa.startUnanchored(),
                            cache, LookSet.EMPTY, false, false);
                }
                return cache.startUnanchored;
            }
        }

        // Look-assertion path: classify start position
        Start start = Start.from(input.haystack(), input.start());
        int existing = cache.getStartState(start, input.isAnchored());
        if (existing != DFACache.UNKNOWN) return existing;

        int nfaStartId = input.isAnchored() ? nfa.startAnchored() : nfa.startUnanchored();
        LookSet initialLookHave = start.initialLookHave(lookSetAny, nfa.isReverse());
        int sid = computeStartState(nfaStartId, cache, initialLookHave,
                start.isFromWord(), start.isHalfCrlf(nfa.isReverse()));
        cache.setStartState(start, input.isAnchored(), sid);
        return sid;
    }

    private int computeStartState(int nfaStartId, DFACache cache,
                                   LookSet lookHave, boolean isFromWord, boolean isHalfCrlf) {
        cache.nfaStateSet.clear();
        cache.closureLookNeed = 0;
        boolean hasMatch = epsilonClosure(cache, nfaStartId, lookHave);
        int[] nfaStates = collect(cache);
        if (nfaStates.length == 0) return DFACache.dead(charClasses.stride());

        StateContent content = new StateContent(nfaStates, hasMatch,
                isFromWord, isHalfCrlf, lookHave.bits(), cache.closureLookNeed);
        int sid = cache.allocateState(content);
        // Overwrite transitions for quit char classes
        if (charClasses.hasQuitClasses()) {
            int rawSid = sid & 0x7FFF_FFFF;
            int quitSid = DFACache.quit(charClasses.stride());
            for (int cls = 0; cls < charClasses.classCount(); cls++) {
                if (charClasses.isQuitClass(cls)) {
                    cache.setTransition(rawSid, cls, quitSid);
                }
            }
        }
        // Strip the match flag — start states must not be match states (delay by 1).
        return sid & 0x7FFF_FFFF;
    }

    // -- Internal: determinization --

    /**
     * Compute the next DFA state from source state on the given class ID.
     *
     * <p>Implements two-phase look computation and 1-char match delay with
     * leftmost-first semantics. Phase 1 resolves look-ahead assertions on the
     * source state (before char transitions). Phase 2 computes look-behind
     * context on the destination state (after char transitions).</p>
     */
    private int computeNextState(DFACache cache, int sourceSid, int classId) {
        int rawSourceId = sourceSid & 0x7FFF_FFFF;
        StateContent sourceContent = cache.getState(rawSourceId);

        cache.nfaStateSet.clear();
        cache.closureLookNeed = 0;
        boolean isMatch = false;

        // Phase 1: Look-ahead resolution on source state
        int[] sourceNfaStates = sourceContent.nfaStates();
        LookSet lookHave;
        if (!lookSetAny.isEmpty()) {
            lookHave = computeLookAhead(sourceContent, classId);

            // Re-computation check: if new assertions became true that the source
            // state needed, re-run epsilon closure with updated lookHave
            LookSet newlyTrue = lookHave.subtract(new LookSet(sourceContent.lookHave()))
                                        .intersect(new LookSet(sourceContent.lookNeed()));
            if (!newlyTrue.isEmpty()) {
                for (int nfaStateId : sourceNfaStates) {
                    epsilonClosure(cache, nfaStateId, lookHave);
                }
                sourceNfaStates = collect(cache);
                cache.nfaStateSet.clear();
                cache.closureLookNeed = 0;
            }
        } else {
            lookHave = LookSet.EMPTY;
        }

        // EOI handling (AFTER look-ahead re-computation)
        if (classId == charClasses.eoiClass()) {
            // Check source for match (delayed match at end-of-input)
            // We need to check the possibly re-computed sourceNfaStates for Match
            boolean hasMatch = sourceContent.isMatch();
            if (!hasMatch) {
                // Check re-computed states for Match (may have become reachable
                // after look-ahead resolved new assertions)
                for (int nfaStateId : sourceNfaStates) {
                    if (nfa.state(nfaStateId) instanceof State.Match) {
                        hasMatch = true;
                        break;
                    }
                }
            }
            if (hasMatch) {
                return DFACache.dead(charClasses.stride()) | DFACache.MATCH_FLAG;
            }
            return DFACache.dead(charClasses.stride());
        }

        // Phase 2: Compute destination look-behind context from classId.
        // This must happen BEFORE the char-transition loop because the epsilon
        // closure of destination NFA states needs the destination's lookHave
        // (not the source's look-ahead).
        boolean destIsFromWord = false;
        boolean destIsHalfCrlf = false;
        int destLookHave = 0;
        LookSet destLookHaveSet = LookSet.EMPTY;

        if (!lookSetAny.isEmpty()) {
            boolean isLF = charClasses.isLineLF(classId);
            boolean isCR = charClasses.isLineCR(classId);
            boolean isWord = charClasses.isWordClass(classId);
            boolean reverse = nfa.isReverse();

            // START_LINE: previous char was \n
            if (isLF) {
                destLookHave |= LookKind.START_LINE.asBit();
            }
            // START_LINE_CRLF: depends on direction
            if ((reverse && isCR) || (!reverse && isLF)) {
                destLookHave |= LookKind.START_LINE_CRLF.asBit();
            }
            // WORD_START_HALF: previous char was not a word char
            if (!isWord) {
                destLookHave |= LookKind.WORD_START_HALF_ASCII.asBit()
                              | LookKind.WORD_START_HALF_UNICODE.asBit();
            }
            destIsFromWord = isWord;
            destIsHalfCrlf = reverse ? isLF : isCR;
            destLookHaveSet = new LookSet(destLookHave);
        }

        // Char-transition loop: iterate source NFA states in sorted order.
        // Epsilon closures use the DESTINATION's lookHave for correct look-
        // assertion resolution in the destination state.
        // For leftmost-first: once we see a Match NFA state, mark the
        // destination as a match and stop processing further NFA states.
        for (int nfaStateId : sourceNfaStates) {
            State state = nfa.state(nfaStateId);
            switch (state) {
                case State.CharRange cr -> {
                    if (charInRange(classId, cr.start(), cr.end())) {
                        epsilonClosure(cache, cr.next(), destLookHaveSet);
                    }
                }
                case State.Sparse sp -> {
                    for (Transition t : sp.transitions()) {
                        if (charInRange(classId, t.start(), t.end())) {
                            epsilonClosure(cache, t.next(), destLookHaveSet);
                            break;
                        }
                    }
                }
                case State.Match ignored -> {
                    isMatch = true;
                    break;
                }
                default -> {
                    // Non-char-consuming states (Union, Capture, Look, Fail)
                    // are resolved during epsilon closure and have no char
                    // transitions. They may appear in the set but are no-ops here.
                }
            }
            if (isMatch) break;
        }

        int[] nfaStates = collect(cache);

        // Check if destination NFA states include a Match
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
            return DFACache.dead(charClasses.stride()) | DFACache.MATCH_FLAG;
        }

        StateContent content = new StateContent(nfaStates, destHasMatch,
                destIsFromWord, destIsHalfCrlf, destLookHave, cache.closureLookNeed);
        int sid = allocateOrGiveUp(cache, content);
        // Overwrite transitions for quit char classes
        if (charClasses.hasQuitClasses()) {
            int rawSid = sid & 0x7FFF_FFFF;
            int quitSid = DFACache.quit(charClasses.stride());
            for (int cls = 0; cls < charClasses.classCount(); cls++) {
                if (charClasses.isQuitClass(cls)) {
                    cache.setTransition(rawSid, cls, quitSid);
                }
            }
        }
        if (isMatch) {
            sid = sid | DFACache.MATCH_FLAG;
        }
        return sid;
    }

    /**
     * Compute look-ahead assertions on the source state based on the input unit.
     * These assertions fire on the transition FROM the source state.
     */
    private LookSet computeLookAhead(StateContent source, int classId) {
        LookSet have = new LookSet(source.lookHave());

        boolean isEoi = (classId == charClasses.eoiClass());
        boolean isLF = !isEoi && charClasses.isLineLF(classId);
        boolean isCR = !isEoi && charClasses.isLineCR(classId);
        boolean isWord = !isEoi && charClasses.isWordClass(classId);
        boolean reverse = nfa.isReverse();

        // END_LINE: current unit is \n
        if (isLF) {
            have = have.insert(LookKind.END_LINE);
        }

        // END_LINE_CRLF: complex CRLF handling (direction-dependent)
        if (isCR && (!reverse || !source.isHalfCrlf())) {
            have = have.insert(LookKind.END_LINE_CRLF);
        }
        if (isLF && (reverse || !source.isHalfCrlf())) {
            have = have.insert(LookKind.END_LINE_CRLF);
        }

        // END_TEXT + END_LINE + END_LINE_CRLF at EOI
        if (isEoi) {
            have = have.insert(LookKind.END_TEXT)
                       .insert(LookKind.END_LINE)
                       .insert(LookKind.END_LINE_CRLF);
        }

        // START_LINE_CRLF: source isHalfCrlf and current is NOT the completing half
        if (source.isHalfCrlf()) {
            boolean completing = reverse ? isCR : isLF;
            if (!completing) {
                have = have.insert(LookKind.START_LINE_CRLF);
            }
        }

        // Word boundaries
        boolean fromWord = source.isFromWord();
        if (fromWord != isWord) {
            have = have.insert(LookKind.WORD_BOUNDARY_ASCII)
                       .insert(LookKind.WORD_BOUNDARY_UNICODE);
        } else {
            have = have.insert(LookKind.WORD_BOUNDARY_ASCII_NEGATE)
                       .insert(LookKind.WORD_BOUNDARY_UNICODE_NEGATE);
        }
        if (!fromWord && isWord) {
            have = have.insert(LookKind.WORD_START_ASCII)
                       .insert(LookKind.WORD_START_UNICODE);
        }
        if (fromWord && !isWord) {
            have = have.insert(LookKind.WORD_END_ASCII)
                       .insert(LookKind.WORD_END_UNICODE);
        }
        if (!isWord) {
            have = have.insert(LookKind.WORD_END_HALF_ASCII)
                       .insert(LookKind.WORD_END_HALF_UNICODE);
        }

        return have;
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
     * to {@code cache.nfaStateSet}. Conditionally follows {@link State.Look}
     * transitions based on which assertions are satisfied in {@code lookHave}.
     *
     * <p>Also accumulates {@code cache.closureLookNeed} — the set of all
     * Look assertions encountered during closure (both followed and not).
     * This is used to determine when re-computation is needed.</p>
     *
     * @return true if a Match state was reached
     */
    private boolean epsilonClosure(DFACache cache, int startStateId, LookSet lookHave) {
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
                case State.Look look -> {
                    cache.closureLookNeed |= look.look().asBit();
                    if (lookHave.contains(look.look())) {
                        stack = ensureStackCapacity(stack, stackTop);
                        stack[stackTop++] = look.next();
                    }
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

    /**
     * Collect the NFA state set into an array, preserving insertion order.
     *
     * <p>Insertion order is critical for leftmost-first match semantics: the
     * epsilon closure's DFS traversal visits pattern-continuation states before
     * unanchored-prefix states (via {@link State.BinaryUnion}'s alt1-first
     * ordering). When {@code computeNextState} iterates this array and breaks
     * at the first Match state, the insertion order ensures that continuation
     * states are processed (extending the current match) while unanchored-prefix
     * states after Match are skipped (preventing new match attempts from
     * polluting the successor state).</p>
     *
     * <p>This matches the upstream Rust regex crate's use of a SparseSet with
     * insertion-order iteration in the determinization loop.</p>
     */
    private int[] collect(DFACache cache) {
        int size = cache.nfaStateSet.size();
        int[] result = new int[size];
        for (int i = 0; i < size; i++) {
            result[i] = cache.nfaStateSet.get(i);
        }
        return result;
    }
}
