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
    private final int dead;
    private final int quit;

    private LazyDFA(NFA nfa, CharClasses charClasses) {
        this.nfa = nfa;
        this.charClasses = charClasses;
        this.lookSetAny = nfa.lookSetAny();
        int stride = charClasses.stride();
        this.dead = DFACache.dead(stride);
        this.quit = DFACache.quit(stride);
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
     * Eagerly computes transitions for ALL equivalence classes (including EOI)
     * for the given DFA state. Used by DenseDFABuilder to fully populate the
     * transition table.
     *
     * @return the state count before computation (compare with cache.stateCount()
     *         after to discover newly-created states)
     */
    public int computeAllTransitions(DFACache cache, int sid) {
        int beforeCount = cache.stateCount();
        int rawSid = sid & 0x7FFF_FFFF;
        for (int cls = 0; cls <= charClasses.classCount(); cls++) {
            if (cache.nextState(rawSid, cls) == DFACache.UNKNOWN) {
                int nextSid = computeNextState(cache, rawSid, cls);
                cache.setTransition(rawSid, cls, nextSid);
            }
        }
        return beforeCount;
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
        long result = searchFwdLong(input, cache);
        if (SearchResult.isMatch(result)) return new SearchResult.Match(SearchResult.matchOffset(result));
        if (SearchResult.isNoMatch(result)) return new SearchResult.NoMatch();
        return new SearchResult.GaveUp(SearchResult.gaveUpOffset(result));
    }

    /**
     * Forward search returning a primitive-encoded result to avoid allocation.
     * Use {@link SearchResult} static helpers to decode the return value.
     *
     * @see SearchResult#isMatch(long)
     * @see SearchResult#isNoMatch(long)
     * @see SearchResult#isGaveUp(long)
     */
    public long searchFwdLong(Input input, DFACache cache) {
        char[] haystack = input.haystack();
        int pos = input.start();
        int end = input.end();

        int sid = getOrComputeStartState(input, cache);
        if (sid == dead) return SearchResult.NO_MATCH;
        if (sid == quit) return SearchResult.gaveUp(pos);

        int lastMatchEnd = -1;
        int startPos = pos;

        while (pos < end) {
            // Inner unrolled loop: process 4 transitions per iteration.
            // The guard (pos + 3 < end) ensures all 4 haystack accesses are in bounds.
            // Only cached, non-special transitions (nextSid > quit) stay in the inner loop.
            // Any special state (match, UNKNOWN, dead, quit) breaks out to the outer loop.
            while (pos + 3 < end) {
                int s0 = cache.nextState(sid, charClasses.classify(haystack[pos]));
                if (s0 <= quit) { break; }

                int s1 = cache.nextState(s0, charClasses.classify(haystack[pos + 1]));
                if (s1 <= quit) { sid = s0; pos++; break; }

                int s2 = cache.nextState(s1, charClasses.classify(haystack[pos + 2]));
                if (s2 <= quit) { sid = s1; pos += 2; break; }

                int s3 = cache.nextState(s2, charClasses.classify(haystack[pos + 3]));
                if (s3 <= quit) { sid = s2; pos += 3; break; }

                sid = s3;
                pos += 4;
            }

            // Outer dispatch: handle the char at pos (either a break-out from the inner
            // loop or a tail char when fewer than 4 remain). Re-classify and dispatch.
            if (pos >= end) break;

            int classId = charClasses.classify(haystack[pos]);
            int nextSid = cache.nextState(sid, classId);

            if (nextSid > quit) {
                sid = nextSid;
                pos++;
            } else if (nextSid < 0) {
                lastMatchEnd = pos;
                sid = nextSid & 0x7FFF_FFFF;
                pos++;
            } else {
                // Slow path: sync charsSearched then delegate to helper
                cache.charsSearched += (pos - startPos);
                startPos = pos;
                long slowResult = handleSlowTransition(cache, sid, classId,
                        nextSid, haystack[pos], pos);
                int newSid = slowResultSid(slowResult);
                int newMatch = slowResultLastMatch(slowResult);
                if (newMatch >= 0) lastMatchEnd = newMatch;
                if (newSid == dead) { sid = dead; break; }
                if (newSid == quit) { return SearchResult.gaveUp(pos); }
                sid = newSid;
                pos++;
            }
        }

        // Right-edge transition
        // Ref: upstream/regex/regex-automata/src/hybrid/search.rs:693-726
        cache.charsSearched += (pos - startPos);
        lastMatchEnd = handleRightEdge(cache, sid, haystack, end, lastMatchEnd);
        if (lastMatchEnd == -2) return SearchResult.gaveUp(end);

        if (lastMatchEnd >= 0) return SearchResult.match(lastMatchEnd);
        return SearchResult.NO_MATCH;
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
        long result = searchRevLong(input, cache);
        if (SearchResult.isMatch(result)) return new SearchResult.Match(SearchResult.matchOffset(result));
        if (SearchResult.isNoMatch(result)) return new SearchResult.NoMatch();
        return new SearchResult.GaveUp(SearchResult.gaveUpOffset(result));
    }

    /**
     * Reverse search returning a primitive-encoded result to avoid allocation.
     * Use {@link SearchResult} static helpers to decode the return value.
     *
     * @see SearchResult#isMatch(long)
     * @see SearchResult#isNoMatch(long)
     * @see SearchResult#isGaveUp(long)
     */
    public long searchRevLong(Input input, DFACache cache) {
        char[] haystack = input.haystack();
        int start = input.start();
        int end = input.end();

        int sid = getOrComputeStartState(input, cache);
        if (sid == dead) return SearchResult.NO_MATCH;
        if (sid == quit) return SearchResult.gaveUp(end);

        int lastMatchStart = -1;

        int pos = end - 1;
        int startPos = pos;
        while (pos >= start) {
            // Inner unrolled loop: process 4 transitions per iteration (reverse).
            while (pos >= start + 3) {
                int s0 = cache.nextState(sid, charClasses.classify(haystack[pos]));
                if (s0 <= quit) { break; }

                int s1 = cache.nextState(s0, charClasses.classify(haystack[pos - 1]));
                if (s1 <= quit) { sid = s0; pos--; break; }

                int s2 = cache.nextState(s1, charClasses.classify(haystack[pos - 2]));
                if (s2 <= quit) { sid = s1; pos -= 2; break; }

                int s3 = cache.nextState(s2, charClasses.classify(haystack[pos - 3]));
                if (s3 <= quit) { sid = s2; pos -= 3; break; }

                sid = s3;
                pos -= 4;
            }

            // Outer dispatch: handle break-out or tail chars.
            if (pos < start) break;

            int classId = charClasses.classify(haystack[pos]);
            int nextSid = cache.nextState(sid, classId);

            if (nextSid > quit) {
                sid = nextSid;
                pos--;
            } else if (nextSid < 0) {
                lastMatchStart = pos + 1;
                sid = nextSid & 0x7FFF_FFFF;
                pos--;
            } else {
                // Slow path: sync charsSearched then delegate to helper
                cache.charsSearched += (startPos - pos);
                startPos = pos;
                long slowResult = handleSlowTransitionRev(cache, sid, classId,
                        nextSid, haystack[pos], pos);
                int newSid = slowResultSid(slowResult);
                int newMatch = slowResultLastMatch(slowResult);
                if (newMatch >= 0) lastMatchStart = newMatch;
                if (newSid == dead) { sid = dead; break; }
                if (newSid == quit) { return SearchResult.gaveUp(pos); }
                sid = newSid;
                pos--;
            }
        }

        // Left-edge transition
        // Ref: upstream/regex/regex-automata/src/hybrid/search.rs:737-754
        cache.charsSearched += (startPos - pos);
        lastMatchStart = handleLeftEdge(cache, sid, haystack, start, lastMatchStart);
        if (lastMatchStart == -2) return SearchResult.gaveUp(start);

        if (lastMatchStart >= 0) return SearchResult.match(lastMatchStart);
        return SearchResult.NO_MATCH;
    }

    // -- Internal: start state --

    // public for DenseDFABuilder
    public int getOrComputeStartState(Input input, DFACache cache) {
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

        // Look-assertion path: classify start position.
        // Forward search: look-behind from haystack[start - 1]
        // Reverse search: look-behind from haystack[end] (the char AFTER the span)
        // Ref: upstream/regex/regex-automata/src/util/start.rs:141-158
        boolean reverse = nfa.isReverse();
        if (charClasses.hasQuitClasses()) {
            if (!reverse && input.start() > 0) {
                int cls = charClasses.classify(input.haystack()[input.start() - 1]);
                if (charClasses.isQuitClass(cls)) {
                    return DFACache.quit(charClasses.stride());
                }
            } else if (reverse && input.end() < input.haystack().length) {
                int cls = charClasses.classify(input.haystack()[input.end()]);
                if (charClasses.isQuitClass(cls)) {
                    return DFACache.quit(charClasses.stride());
                }
            }
        }
        Start start = reverse
                ? Start.fromReverse(input.haystack(), input.end())
                : Start.from(input.haystack(), input.start());
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
    /**
     * Compute next state without an input char (used for EOI transitions).
     * Falls back to class-based range check in charInRange.
     */
    private int computeNextState(DFACache cache, int sourceSid, int classId) {
        return computeNextState(cache, sourceSid, classId, -1);
    }

    /**
     * Compute next state with an input char for direct range comparison.
     * When {@code inputChar >= 0}, charInRange uses direct comparison
     * (correct with merged equivalence classes). When {@code inputChar < 0}
     * (EOI), falls back to class-based comparison.
     */
    private int computeNextState(DFACache cache, int sourceSid, int classId, int inputChar) {
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
                    if (charInRange(classId, inputChar, cr.start(), cr.end())) {
                        epsilonClosure(cache, cr.next(), destLookHaveSet);
                    }
                }
                case State.Sparse sp -> {
                    for (Transition t : sp.transitions()) {
                        if (charInRange(classId, inputChar, t.start(), t.end())) {
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
     * Check if the input matches the NFA transition range [rangeStart, rangeEnd].
     * Uses direct char comparison when available (correct with merged equivalence
     * classes). Falls back to class-representative comparison when no input char
     * is provided (e.g., EOI transitions or DenseDFA builder).
     *
     * <p>The fallback uses the class representative: a concrete char known to
     * belong to the given class. This avoids the broken class-ID comparison
     * ({@code classId >= classify(rangeStart) && classId <= classify(rangeEnd)})
     * which fails when a range spans multiple equivalence classes with
     * non-contiguous IDs or when merged classes collapse distinct ranges
     * to the same class ID.</p>
     *
     * Ref: upstream/regex/regex-automata/src/util/determinize/mod.rs:288-289
     * (upstream uses trans.matches_unit(unit) — a direct byte comparison)
     */
    private boolean charInRange(int classId, int inputChar, int rangeStart, int rangeEnd) {
        if (rangeStart > 0xFFFF || rangeEnd > 0xFFFF) return false;
        if (inputChar >= 0) {
            return inputChar >= rangeStart && inputChar <= rangeEnd;
        }
        // Fallback for when no concrete input char is available (EOI transitions
        // or DenseDFA builder's computeAllTransitions).
        //
        // Check 1: class-ID comparison. Correct when the range endpoints map
        // to distinct classes with contiguous IDs.
        int classOfStart = charClasses.classify((char) rangeStart);
        int classOfEnd = charClasses.classify((char) rangeEnd);
        if (classId >= classOfStart && classId <= classOfEnd) {
            return true;
        }
        // Check 2: use the class representative. Handles the case where a wide
        // range (e.g., prefix loop's [0, 0xFFFF]) has both endpoints in class 0
        // but the target classId is > 0. The representative char is a concrete
        // member of the class and can be directly compared against the range.
        int rep = charClasses.classRepresentative(classId);
        if (rep >= 0) {
            return rep >= rangeStart && rep <= rangeEnd;
        }
        return false;
    }

    // -- Packed result helpers --

    private static long packSlowResult(int sid, int lastMatch) {
        return ((long) lastMatch << 32) | (sid & 0xFFFF_FFFFL);
    }

    private static int slowResultSid(long result) {
        return (int) result;
    }

    private static int slowResultLastMatch(long result) {
        return (int) (result >>> 32);
    }

    /**
     * Handle slow-path state transitions in forward search: UNKNOWN (compute
     * new state), dead (stop), or quit (give up to PikeVM).
     *
     * <p>Returns a packed long: low 32 bits = new state ID (or dead/quit
     * sentinel), high 32 bits = updated lastMatchEnd (-1 if unchanged).</p>
     */
    private long handleSlowTransition(DFACache cache, int sid, int classId,
                                       int nextSid, char inputChar, int pos) {
        // cache.charsSearched is already up-to-date (caller synced it)
        int lastMatchEnd = -1;

        if (nextSid == DFACache.UNKNOWN) {
            nextSid = computeNextState(cache, sid, classId, inputChar);
            if (nextSid == quit) {
                return packSlowResult(quit, -1);
            }
            cache.setTransition(sid, classId, nextSid);
            if (nextSid < 0) {
                lastMatchEnd = pos;
                nextSid = nextSid & 0x7FFF_FFFF;
            }
            return packSlowResult(nextSid, lastMatchEnd);
        }
        if (nextSid == dead) {
            return packSlowResult(dead, -1);
        }
        // nextSid == quit
        return packSlowResult(quit, -1);
    }

    /**
     * Handle slow-path state transitions in reverse search.
     * Same as handleSlowTransition but match position is pos + 1.
     */
    private long handleSlowTransitionRev(DFACache cache, int sid, int classId,
                                          int nextSid, char inputChar, int pos) {
        // cache.charsSearched is already up-to-date (caller synced it)
        int lastMatchStart = -1;

        if (nextSid == DFACache.UNKNOWN) {
            nextSid = computeNextState(cache, sid, classId, inputChar);
            if (nextSid == quit) {
                return packSlowResult(quit, -1);
            }
            cache.setTransition(sid, classId, nextSid);
            if (nextSid < 0) {
                lastMatchStart = pos + 1;
                nextSid = nextSid & 0x7FFF_FFFF;
            }
            return packSlowResult(nextSid, lastMatchStart);
        }
        if (nextSid == dead) {
            return packSlowResult(dead, -1);
        }
        // nextSid == quit
        return packSlowResult(quit, -1);
    }

    /**
     * Process the right-edge transition after the forward search main loop.
     * Handles EOI class or the character just past the search span for
     * correct look-ahead context ($ and \b assertions).
     *
     * @return updated lastMatchEnd, or -2 as sentinel for gaveUp at end
     */
    private int handleRightEdge(DFACache cache, int sid, char[] haystack,
                                 int end, int lastMatchEnd) {
        // cache.charsSearched is already up-to-date (caller synced it)
        int rawSid = sid & 0x7FFF_FFFF;

        if (rawSid != dead && rawSid != quit) {
            int rightEdgeSid;
            if (end < haystack.length) {
                int classId = charClasses.classify(haystack[end]);
                if (charClasses.hasQuitClasses() && charClasses.isQuitClass(classId)) {
                    return -2; // sentinel: gaveUp at end
                }
                rightEdgeSid = computeNextState(cache, rawSid, classId, haystack[end]);
            } else {
                rightEdgeSid = computeNextState(cache, rawSid, charClasses.eoiClass());
            }
            if (rightEdgeSid < 0) {
                return end; // match at right edge
            }
        }
        return lastMatchEnd; // -2 is safe: valid positions are >= 0, -1 means no match
    }

    /**
     * Process the left-edge transition after the reverse search main loop.
     * Handles start-of-text EOI or the character before the search span
     * for correct look-behind context.
     *
     * @return updated lastMatchStart, or -2 as sentinel for gaveUp at start
     */
    private int handleLeftEdge(DFACache cache, int sid, char[] haystack,
                                int start, int lastMatchStart) {
        // cache.charsSearched is already up-to-date (caller synced it)
        int rawSid = sid & 0x7FFF_FFFF;

        if (rawSid != dead && rawSid != quit) {
            int leftEdgeSid;
            if (start > 0) {
                int classId = charClasses.classify(haystack[start - 1]);
                if (charClasses.hasQuitClasses() && charClasses.isQuitClass(classId)) {
                    return -2; // sentinel: gaveUp at start
                }
                leftEdgeSid = computeNextState(cache, rawSid, classId, haystack[start - 1]);
            } else {
                leftEdgeSid = computeNextState(cache, rawSid, charClasses.eoiClass());
            }
            if (leftEdgeSid < 0) {
                return start; // match at left edge
            }
        }
        return lastMatchStart;
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
