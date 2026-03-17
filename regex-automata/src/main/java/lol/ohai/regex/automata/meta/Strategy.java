package lol.ohai.regex.automata.meta;

import lol.ohai.regex.automata.dfa.dense.DenseDFA;
import lol.ohai.regex.automata.dfa.lazy.DFACache;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.dfa.lazy.SearchResult;
import lol.ohai.regex.automata.dfa.onepass.OnePassCache;
import lol.ohai.regex.automata.dfa.onepass.OnePassDFA;
import lol.ohai.regex.automata.nfa.thompson.backtrack.BoundedBacktracker;
import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.automata.util.Input;

/**
 * Meta engine strategy. Selects the best search approach based on pattern
 * characteristics and available engines.
 *
 * <p>{@code PrefilterOnly} handles patterns that are entirely fixed-length
 * literals — no regex engine needed, just {@code indexOf}.</p>
 *
 * <p>{@code Core} composes the PikeVM with optional forward and reverse LazyDFAs
 * and an optional prefilter. When both DFAs are available, search uses a
 * three-phase approach: the forward DFA finds the end position, the reverse DFA
 * finds the start position, and (for captures) the PikeVM runs on the narrowed
 * window. When only the forward DFA is available, falls back to two-phase
 * (forward DFA + PikeVM).</p>
 */
public sealed interface Strategy permits Strategy.Core, Strategy.PrefilterOnly,
        Strategy.ReverseSuffix, Strategy.ReverseInner {

    Cache createCache();
    boolean isMatch(Input input, Cache cache);
    Captures search(Input input, Cache cache);
    Captures searchCaptures(Input input, Cache cache);

    /**
     * Core strategy: PikeVM + optional forward/reverse LazyDFAs + optional prefilter.
     *
     * @param pikeVM     the PikeVM engine
     * @param forwardDFA the forward lazy DFA engine, or {@code null} if not available
     * @param reverseDFA the reverse lazy DFA engine, or {@code null} if not available
     * @param prefilter  the prefilter to use, or {@code null} for pure engine search
     * @param backtracker the bounded backtracker, or {@code null} if not available
     */
    record Core(PikeVM pikeVM, LazyDFA forwardDFA, LazyDFA reverseDFA,
                Prefilter prefilter, BoundedBacktracker backtracker,
                OnePassDFA onePassDFA, DenseDFA denseDFA) implements Strategy {

        @Override
        public Cache createCache() {
            return new Cache(
                    pikeVM.createCache(),
                    forwardDFA != null ? forwardDFA.createCache() : null,
                    reverseDFA != null ? reverseDFA.createCache() : null,
                    backtracker != null ? backtracker.createCache() : null,
                    null,  // prefixReverseDFACache — not used by Core
                    onePassDFA != null ? onePassDFA.createCache() : null
            );
        }

        @Override
        public boolean isMatch(Input input, Cache cache) {
            if (prefilter != null && !input.isAnchored()) {
                return isMatchPrefilter(input, cache);
            }
            // Forward DFA only — no reverse needed for isMatch
            if (denseDFA != null) {
                long result = denseDFA.searchFwd(input);
                if (SearchResult.isMatch(result)) return true;
                if (SearchResult.isNoMatch(result)) return false;
                // GaveUp — fall through to lazy DFA
            }
            if (forwardDFA != null) {
                long result = forwardDFA.searchFwdLong(input, cache.forwardDFACache());
                if (SearchResult.isMatch(result)) return true;
                if (SearchResult.isNoMatch(result)) return false;
            }
            return pikeVM.isMatch(input, cache.pikeVMCache());
        }

        private boolean isMatchPrefilter(Input input, Cache cache) {
            int start = input.start();
            int end = input.end();
            String haystackStr = input.haystackStr();

            while (start < end) {
                int pos = prefilter.find(haystackStr, start, end);
                if (pos < 0) {
                    return false;
                }
                Input candidateInput = input.withBounds(pos, end, false);
                if (denseDFA != null) {
                    long r = denseDFA.searchFwd(candidateInput);
                    if (SearchResult.isMatch(r)) return true;
                    if (SearchResult.isNoMatch(r)) { start = pos + 1; continue; }
                    // GaveUp — fall through to lazy DFA
                }
                if (forwardDFA != null) {
                    long r = forwardDFA.searchFwdLong(candidateInput, cache.forwardDFACache());
                    if (SearchResult.isMatch(r)) return true;
                    if (SearchResult.isNoMatch(r)) { start = pos + 1; continue; }
                    // GaveUp (both dense and lazy failed)
                }
                if (pikeVM.isMatch(candidateInput, cache.pikeVMCache())) return true;
                start = pos + 1;
            }
            return false;
        }

        @Override
        public Captures search(Input input, Cache cache) {
            if (prefilter != null && !input.isAnchored()) {
                if (denseDFA != null || forwardDFA != null) {
                    return prefilterLoop(input, cache, (in, c) -> dfaSearchImpl(in, c, false));
                }
                return prefilterLoop(input, cache,
                        (in, c) -> pikeVM.search(in, c.pikeVMCache()));
            }
            if (denseDFA != null || forwardDFA != null) {
                return dfaSearchImpl(input, cache, false);
            }
            return pikeVM.search(input, cache.pikeVMCache());
        }

        @Override
        public Captures searchCaptures(Input input, Cache cache) {
            if (prefilter != null && !input.isAnchored()) {
                if (denseDFA != null || forwardDFA != null) {
                    return prefilterLoop(input, cache, (in, c) -> dfaSearchImpl(in, c, true));
                }
                return prefilterLoop(input, cache,
                        (in, c) -> pikeVM.searchCaptures(in, c.pikeVMCache()));
            }
            if (denseDFA != null || forwardDFA != null) {
                return dfaSearchImpl(input, cache, true);
            }
            return pikeVM.searchCaptures(input, cache.pikeVMCache());
        }

        /**
         * Unified DFA search: forward DFA finds matchEnd, then either returns
         * a two-phase result (wantCaptures=false) or runs the capture engine
         * on a narrowed window (wantCaptures=true).
         *
         * <p>Matches upstream Rust behavior at
         * regex-automata/src/dfa/regex.rs:474-534.</p>
         *
         * <p>Our DFA implements leftmost-first semantics (via the break-on-Match
         * in computeNextState), so forward and reverse DFA results are correct
         * without PikeVM verification. Falls back to PikeVM when the DFA gives
         * up (e.g., Unicode word boundaries with quit chars).</p>
         */
        private Captures dfaSearchImpl(Input input, Cache cache, boolean wantCaptures) {
            long fwdResult;
            if (denseDFA != null) {
                fwdResult = denseDFA.searchFwd(input);
                if (SearchResult.isGaveUp(fwdResult)) {
                    fwdResult = forwardDFA != null
                            ? forwardDFA.searchFwdLong(input, cache.forwardDFACache())
                            : SearchResult.NO_MATCH;
                }
            } else if (forwardDFA != null) {
                fwdResult = forwardDFA.searchFwdLong(input, cache.forwardDFACache());
            } else {
                fwdResult = SearchResult.NO_MATCH;
            }
            if (SearchResult.isNoMatch(fwdResult)) return null;
            if (SearchResult.isGaveUp(fwdResult)) {
                return wantCaptures
                        ? pikeVM.searchCaptures(input, cache.pikeVMCache())
                        : pikeVM.search(input, cache.pikeVMCache());
            }

            int matchEnd = SearchResult.matchOffset(fwdResult);

            if (!wantCaptures) {
                // Two-phase (non-capture) search: forward → reverse → return directly
                if (matchEnd == input.start()) {
                    // Empty match
                    Captures caps = cache.forwardDFACache().scratchCaptures();
                    caps.set(0, matchEnd);
                    caps.set(1, matchEnd);
                    return caps;
                }
                if (input.isAnchored()) {
                    Captures caps = cache.forwardDFACache().scratchCaptures();
                    caps.set(0, input.start());
                    caps.set(1, matchEnd);
                    return caps;
                }
                if (reverseDFA == null) {
                    Input narrowed = input.withBounds(input.start(), matchEnd, false);
                    return pikeVM.search(narrowed, cache.pikeVMCache());
                }
                // Three-phase: reverse DFA finds match start, return directly
                Input revInput = input.withBounds(input.start(), matchEnd, true);
                long revResult = reverseDFA.searchRevLong(revInput, cache.reverseDFACache());
                if (SearchResult.isMatch(revResult)) {
                    Captures caps = cache.forwardDFACache().scratchCaptures();
                    caps.set(0, SearchResult.matchOffset(revResult));
                    caps.set(1, matchEnd);
                    return caps;
                }
                if (SearchResult.isGaveUp(revResult)) {
                    Input narrowed = input.withBounds(input.start(), matchEnd, false);
                    return pikeVM.search(narrowed, cache.pikeVMCache());
                }
                // NoMatch — should not happen, fall back
                Input narrowed = input.withBounds(input.start(), matchEnd, false);
                return pikeVM.search(narrowed, cache.pikeVMCache());
            }

            // Three-phase capture search: forward → reverse → capture engine
            if (matchEnd == input.start() || input.isAnchored()) {
                Input narrowed = input.withBounds(input.start(), matchEnd, false);
                return captureEngine(narrowed, cache);
            }
            if (reverseDFA == null) {
                Input narrowed = input.withBounds(input.start(), matchEnd, false);
                return captureEngine(narrowed, cache);
            }
            // Three-phase: reverse DFA narrows window for capture engine
            Input revInput = input.withBounds(input.start(), matchEnd, true);
            long revResult = reverseDFA.searchRevLong(revInput, cache.reverseDFACache());
            if (SearchResult.isMatch(revResult)) {
                // Window precisely narrowed by forward+reverse DFA — anchored=true
                // so that captureEngine can use the one-pass DFA.
                Input narrowed = input.withBounds(SearchResult.matchOffset(revResult), matchEnd, true);
                return captureEngine(narrowed, cache);
            }
            if (SearchResult.isGaveUp(revResult)) {
                Input narrowed = input.withBounds(input.start(), matchEnd, false);
                return pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
            }
            // NoMatch — should not happen, fall back
            Input narrowed = input.withBounds(input.start(), matchEnd, false);
            return captureEngine(narrowed, cache);
        }

        private Captures dfaSearch(Input input, Cache cache) {
            return dfaSearchImpl(input, cache, false);
        }

        private Captures dfaSearchCaptures(Input input, Cache cache) {
            return dfaSearchImpl(input, cache, true);
        }

        /**
         * Selects the best capture engine for the given narrowed window.
         * Prefers one-pass DFA when the window is precisely narrowed (anchored),
         * then bounded backtracker for small windows, then PikeVM as fallback.
         *
         * <p>The one-pass DFA only runs on anchored inputs where the window
         * boundaries are exactly the match boundaries (from three-phase DFA
         * narrowing). Non-anchored inputs may contain a shorter leftmost match
         * within the window, which the one-pass DFA can't find (it always
         * matches from position 0 of the window).</p>
         */
        private Captures captureEngine(Input narrowed, Cache cache) {
            // Only use one-pass DFA when input is anchored — meaning the
            // forward+reverse DFA precisely narrowed the window to the match.
            if (narrowed.isAnchored()
                    && onePassDFA != null && cache.onePassCache() != null) {
                int groupCount = onePassDFA.groupCount();
                int[] slots = new int[groupCount * 2];
                int pid = onePassDFA.search(narrowed, cache.onePassCache(), slots);
                if (pid >= 0) {
                    Captures caps = new Captures(groupCount);
                    System.arraycopy(slots, 0, caps.slots(), 0, slots.length);
                    return caps;
                }
            }
            return Strategy.doCaptureEngine(narrowed, cache, pikeVM, backtracker);
        }

        private Captures prefilterLoop(Input input, Cache cache,
                java.util.function.BiFunction<Input, Cache, Captures> searcher) {
            int start = input.start();
            int end = input.end();
            String haystackStr = input.haystackStr();

            while (start < end) {
                int pos = prefilter.find(haystackStr, start, end);
                if (pos < 0) {
                    return null;
                }
                Input candidateInput = input.withBounds(pos, end, false);
                Captures caps = searcher.apply(candidateInput, cache);
                if (caps != null) {
                    return caps;
                }
                start = pos + 1;
            }
            return null;
        }
    }

    // PrefilterOnly needs no engine cache — parameter required by Strategy interface contract.
    record PrefilterOnly(Prefilter prefilter) implements Strategy {

        @Override
        public Cache createCache() {
            return Cache.EMPTY;
        }

        @Override
        public boolean isMatch(Input input, Cache cache) {
            return prefilter.find(input.haystackStr(), input.start(), input.end()) >= 0;
        }

        @Override
        public Captures search(Input input, Cache cache) {
            long span = prefilter.findSpan(input.haystackStr(), input.start(), input.end());
            if (span < 0) {
                return null;
            }
            Captures caps = new Captures(1);
            caps.set(0, Prefilter.spanStart(span));
            caps.set(1, Prefilter.spanEnd(span));
            return caps;
        }

        @Override
        public Captures searchCaptures(Input input, Cache cache) {
            return search(input, cache);
        }
    }

    record ReverseSuffix(PikeVM pikeVM, LazyDFA forwardDFA, LazyDFA reverseDFA,
                         Prefilter suffixPrefilter, BoundedBacktracker backtracker) implements Strategy {

        @Override
        public Cache createCache() {
            return new Cache(
                    pikeVM.createCache(),
                    forwardDFA.createCache(),
                    reverseDFA.createCache(),
                    backtracker != null ? backtracker.createCache() : null,
                    null,  // prefixReverseDFACache — not used by ReverseSuffix
                    null   // onePassCache — not used by ReverseSuffix
            );
        }

        @Override
        public boolean isMatch(Input input, Cache cache) {
            if (input.isAnchored()) {
                return pikeVM.isMatch(input, cache.pikeVMCache());
            }
            int start = input.start();
            int end = input.end();
            String haystackStr = input.haystackStr();
            int minStart = input.start();

            while (start < end) {
                int suffixPos = suffixPrefilter.find(haystackStr, start, end);
                if (suffixPos < 0) return false;

                int reverseFrom = suffixPos + suffixPrefilter.matchLength();
                Input reverseInput = input.withBounds(minStart, reverseFrom, true);
                SearchResult revResult = reverseDFA.searchRev(reverseInput, cache.reverseDFACache());

                switch (revResult) {
                    case SearchResult.NoMatch n -> {
                        start = suffixPos + 1;
                        continue;
                    }
                    case SearchResult.GaveUp g -> {
                        return pikeVM.isMatch(input.withBounds(start, end, false), cache.pikeVMCache());
                    }
                    case SearchResult.Match m -> {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public Captures search(Input input, Cache cache) {
            if (input.isAnchored()) {
                return pikeVM.search(input, cache.pikeVMCache());
            }
            int start = input.start();
            int end = input.end();
            String haystackStr = input.haystackStr();
            int minStart = input.start();

            while (start < end) {
                int suffixPos = suffixPrefilter.find(haystackStr, start, end);
                if (suffixPos < 0) return null;

                int reverseFrom = suffixPos + suffixPrefilter.matchLength();
                Input reverseInput = input.withBounds(minStart, reverseFrom, true);
                SearchResult revResult = reverseDFA.searchRev(reverseInput, cache.reverseDFACache());

                switch (revResult) {
                    case SearchResult.NoMatch n -> {
                        start = suffixPos + 1;
                        continue;
                    }
                    case SearchResult.GaveUp g -> {
                        return pikeVM.search(input.withBounds(start, end, false), cache.pikeVMCache());
                    }
                    case SearchResult.Match m -> {
                        int matchStart = m.offset();
                        Input fwdInput = input.withBounds(matchStart, end, false);
                        SearchResult fwdResult = forwardDFA.searchFwd(fwdInput, cache.forwardDFACache());

                        switch (fwdResult) {
                            case SearchResult.Match fm -> {
                                // Use minStart (not matchStart) so PikeVM finds the true
                                // leftmost match — the reverse DFA may overshoot rightward.
                                Input narrowed = input.withBounds(minStart, fm.offset(), false);
                                return pikeVM.search(narrowed, cache.pikeVMCache());
                            }
                            case SearchResult.GaveUp g2 -> {
                                return pikeVM.search(
                                        input.withBounds(minStart, end, false), cache.pikeVMCache());
                            }
                            case SearchResult.NoMatch n2 -> {
                                start = suffixPos + 1;
                                continue;
                            }
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public Captures searchCaptures(Input input, Cache cache) {
            if (input.isAnchored()) {
                return pikeVM.searchCaptures(input, cache.pikeVMCache());
            }
            int start = input.start();
            int end = input.end();
            String haystackStr = input.haystackStr();
            int minStart = input.start();

            while (start < end) {
                int suffixPos = suffixPrefilter.find(haystackStr, start, end);
                if (suffixPos < 0) return null;

                int reverseFrom = suffixPos + suffixPrefilter.matchLength();
                Input reverseInput = input.withBounds(minStart, reverseFrom, true);
                SearchResult revResult = reverseDFA.searchRev(reverseInput, cache.reverseDFACache());

                switch (revResult) {
                    case SearchResult.NoMatch n -> {
                        start = suffixPos + 1;
                        continue;
                    }
                    case SearchResult.GaveUp g -> {
                        return pikeVM.searchCaptures(input.withBounds(start, end, false), cache.pikeVMCache());
                    }
                    case SearchResult.Match m -> {
                        int matchStart = m.offset();
                        Input fwdInput = input.withBounds(matchStart, end, false);
                        SearchResult fwdResult = forwardDFA.searchFwd(fwdInput, cache.forwardDFACache());

                        switch (fwdResult) {
                            case SearchResult.Match fm -> {
                                // Use minStart (not matchStart) so PikeVM finds the true
                                // leftmost match — the reverse DFA may overshoot rightward.
                                Input narrowed = input.withBounds(minStart, fm.offset(), false);
                                return Strategy.doCaptureEngine(narrowed, cache, pikeVM, backtracker);
                            }
                            case SearchResult.GaveUp g2 -> {
                                return pikeVM.searchCaptures(
                                        input.withBounds(minStart, end, false), cache.pikeVMCache());
                            }
                            case SearchResult.NoMatch n2 -> {
                                start = suffixPos + 1;
                                continue;
                            }
                        }
                    }
                }
            }
            return null;
        }
    }

    record ReverseInner(PikeVM pikeVM, LazyDFA forwardDFA, LazyDFA prefixReverseDFA,
                        Prefilter innerPrefilter, BoundedBacktracker backtracker) implements Strategy {

        @Override
        public Cache createCache() {
            return new Cache(
                    pikeVM.createCache(),
                    forwardDFA.createCache(),
                    null,  // reverseDFACache — not used by ReverseInner
                    backtracker != null ? backtracker.createCache() : null,
                    prefixReverseDFA.createCache(),  // prefix-only reverse DFA
                    null   // onePassCache — not used by ReverseInner
            );
        }

        @Override
        public boolean isMatch(Input input, Cache cache) {
            if (input.isAnchored()) {
                return pikeVM.isMatch(input, cache.pikeVMCache());
            }
            int start = input.start();
            int end = input.end();
            String haystackStr = input.haystackStr();
            int minStart = input.start();
            int minPreStart = input.start();

            while (start < end) {
                int innerPos = innerPrefilter.find(haystackStr, start, end);
                if (innerPos < 0) return false;

                // Quadratic-abort: if we're scanning backwards past where we've
                // already ruled out, fall back to PikeVM
                if (innerPos < minPreStart) {
                    return pikeVM.isMatch(input.withBounds(start, end, false), cache.pikeVMCache());
                }

                Input reverseInput = input.withBounds(minStart, innerPos, true);
                SearchResult revResult = prefixReverseDFA.searchRev(reverseInput, cache.prefixReverseDFACache());

                switch (revResult) {
                    case SearchResult.NoMatch n -> {
                        minStart = innerPos;
                        minPreStart = innerPos + 1;
                        start = innerPos + 1;
                        continue;
                    }
                    case SearchResult.GaveUp g -> {
                        return pikeVM.isMatch(input.withBounds(start, end, false), cache.pikeVMCache());
                    }
                    case SearchResult.Match m -> {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public Captures search(Input input, Cache cache) {
            if (input.isAnchored()) {
                return pikeVM.search(input, cache.pikeVMCache());
            }
            int start = input.start();
            int end = input.end();
            String haystackStr = input.haystackStr();
            int minStart = input.start();
            int minPreStart = input.start();

            while (start < end) {
                int innerPos = innerPrefilter.find(haystackStr, start, end);
                if (innerPos < 0) return null;

                // Quadratic-abort
                if (innerPos < minPreStart) {
                    return pikeVM.search(input.withBounds(start, end, false), cache.pikeVMCache());
                }

                Input reverseInput = input.withBounds(minStart, innerPos, true);
                SearchResult revResult = prefixReverseDFA.searchRev(reverseInput, cache.prefixReverseDFACache());

                switch (revResult) {
                    case SearchResult.NoMatch n -> {
                        minStart = innerPos;
                        minPreStart = innerPos + 1;
                        start = innerPos + 1;
                        continue;
                    }
                    case SearchResult.GaveUp g -> {
                        return pikeVM.search(input.withBounds(start, end, false), cache.pikeVMCache());
                    }
                    case SearchResult.Match m -> {
                        // Use minStart (not matchStart) so PikeVM finds the true
                        // leftmost match — the prefix reverse DFA may overshoot rightward.
                        Input fwdInput = input.withBounds(minStart, end, false);
                        SearchResult fwdResult = forwardDFA.searchFwd(fwdInput, cache.forwardDFACache());

                        switch (fwdResult) {
                            case SearchResult.Match fm -> {
                                Input narrowed = input.withBounds(minStart, fm.offset(), false);
                                return pikeVM.search(narrowed, cache.pikeVMCache());
                            }
                            case SearchResult.GaveUp g2 -> {
                                return pikeVM.search(fwdInput, cache.pikeVMCache());
                            }
                            case SearchResult.NoMatch n2 -> {
                                start = innerPos + 1;
                                continue;
                            }
                        }
                    }
                }
            }
            return null;
        }

        @Override
        public Captures searchCaptures(Input input, Cache cache) {
            if (input.isAnchored()) {
                return pikeVM.searchCaptures(input, cache.pikeVMCache());
            }
            int start = input.start();
            int end = input.end();
            String haystackStr = input.haystackStr();
            int minStart = input.start();
            int minPreStart = input.start();

            while (start < end) {
                int innerPos = innerPrefilter.find(haystackStr, start, end);
                if (innerPos < 0) return null;

                // Quadratic-abort
                if (innerPos < minPreStart) {
                    return pikeVM.searchCaptures(input.withBounds(start, end, false), cache.pikeVMCache());
                }

                Input reverseInput = input.withBounds(minStart, innerPos, true);
                SearchResult revResult = prefixReverseDFA.searchRev(reverseInput, cache.prefixReverseDFACache());

                switch (revResult) {
                    case SearchResult.NoMatch n -> {
                        minStart = innerPos;
                        minPreStart = innerPos + 1;
                        start = innerPos + 1;
                        continue;
                    }
                    case SearchResult.GaveUp g -> {
                        return pikeVM.searchCaptures(input.withBounds(start, end, false), cache.pikeVMCache());
                    }
                    case SearchResult.Match m -> {
                        // Use minStart (not matchStart) for same reason as search()
                        Input fwdInput = input.withBounds(minStart, end, false);
                        SearchResult fwdResult = forwardDFA.searchFwd(fwdInput, cache.forwardDFACache());

                        switch (fwdResult) {
                            case SearchResult.Match fm -> {
                                Input narrowed = input.withBounds(minStart, fm.offset(), false);
                                return Strategy.doCaptureEngine(narrowed, cache, pikeVM, backtracker);
                            }
                            case SearchResult.GaveUp g2 -> {
                                return pikeVM.searchCaptures(fwdInput, cache.pikeVMCache());
                            }
                            case SearchResult.NoMatch n2 -> {
                                start = innerPos + 1;
                                continue;
                            }
                        }
                    }
                }
            }
            return null;
        }
    }

    /**
     * Selects the best capture engine for the given narrowed window.
     * Prefers the bounded backtracker for small windows (faster than PikeVM
     * for captures); falls back to PikeVM for larger windows or when the
     * backtracker is unavailable.
     */
    static Captures doCaptureEngine(Input narrowed, Cache cache,
                                    PikeVM pikeVM, BoundedBacktracker backtracker) {
        if (backtracker != null) {
            int windowLen = narrowed.end() - narrowed.start();
            if (windowLen <= backtracker.maxHaystackLen()) {
                Captures caps = backtracker.searchCaptures(narrowed, cache.backtrackerCache());
                if (caps != null) return caps;
            }
        }
        return pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
    }

    /**
     * Per-search mutable state. Wraps a PikeVM cache and optional forward/reverse
     * LazyDFA caches. {@code EMPTY} is used by {@link PrefilterOnly} which needs
     * no engine state.
     */
    record Cache(
            lol.ohai.regex.automata.nfa.thompson.pikevm.Cache pikeVMCache,
            DFACache forwardDFACache,
            DFACache reverseDFACache,
            lol.ohai.regex.automata.nfa.thompson.backtrack.BoundedBacktracker.Cache backtrackerCache,
            DFACache prefixReverseDFACache,
            OnePassCache onePassCache
    ) {
        static final Cache EMPTY = new Cache(null, null, null, null, null, null);
    }
}
