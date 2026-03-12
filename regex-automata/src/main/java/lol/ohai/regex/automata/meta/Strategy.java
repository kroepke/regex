package lol.ohai.regex.automata.meta;

import lol.ohai.regex.automata.dfa.lazy.DFACache;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.dfa.lazy.SearchResult;
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
public sealed interface Strategy permits Strategy.Core, Strategy.PrefilterOnly {

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
                Prefilter prefilter, BoundedBacktracker backtracker) implements Strategy {

        @Override
        public Cache createCache() {
            return new Cache(
                    pikeVM.createCache(),
                    forwardDFA != null ? forwardDFA.createCache() : null,
                    reverseDFA != null ? reverseDFA.createCache() : null,
                    backtracker != null ? backtracker.createCache() : null
            );
        }

        @Override
        public boolean isMatch(Input input, Cache cache) {
            if (prefilter != null && !input.isAnchored()) {
                return isMatchPrefilter(input, cache);
            }
            if (forwardDFA != null) {
                SearchResult result = forwardDFA.searchFwd(input, cache.forwardDFACache());
                return switch (result) {
                    case SearchResult.Match m -> true;
                    case SearchResult.NoMatch n -> false;
                    case SearchResult.GaveUp g -> pikeVM.isMatch(input, cache.pikeVMCache());
                };
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
                if (forwardDFA != null) {
                    SearchResult r = forwardDFA.searchFwd(candidateInput, cache.forwardDFACache());
                    switch (r) {
                        case SearchResult.Match m -> { return true; }
                        case SearchResult.NoMatch n -> { start = pos + 1; continue; }
                        case SearchResult.GaveUp g -> {
                            if (pikeVM.isMatch(candidateInput, cache.pikeVMCache())) return true;
                            start = pos + 1;
                            continue;
                        }
                    }
                } else {
                    if (pikeVM.isMatch(candidateInput, cache.pikeVMCache())) {
                        return true;
                    }
                    start = pos + 1;
                }
            }
            return false;
        }

        @Override
        public Captures search(Input input, Cache cache) {
            if (prefilter != null && !input.isAnchored()) {
                if (forwardDFA != null) {
                    return prefilterLoop(input, cache, (in, c) -> dfaSearch(in, c));
                }
                return prefilterLoop(input, cache,
                        (in, c) -> pikeVM.search(in, c.pikeVMCache()));
            }
            if (forwardDFA != null) {
                return dfaSearch(input, cache);
            }
            return pikeVM.search(input, cache.pikeVMCache());
        }

        @Override
        public Captures searchCaptures(Input input, Cache cache) {
            if (prefilter != null && !input.isAnchored()) {
                if (forwardDFA != null) {
                    return prefilterLoop(input, cache,
                            (in, c) -> dfaSearchCaptures(in, c));
                }
                return prefilterLoop(input, cache,
                        (in, c) -> pikeVM.searchCaptures(in, c.pikeVMCache()));
            }
            if (forwardDFA != null) {
                return dfaSearchCaptures(input, cache);
            }
            return pikeVM.searchCaptures(input, cache.pikeVMCache());
        }

        private Captures dfaSearch(Input input, Cache cache) {
            SearchResult fwdResult = forwardDFA.searchFwd(input, cache.forwardDFACache());
            return switch (fwdResult) {
                case SearchResult.NoMatch n -> null;
                case SearchResult.GaveUp g -> pikeVM.search(input, cache.pikeVMCache());
                case SearchResult.Match m -> dfaSearchReverse(input, cache, m.offset());
            };
        }

        private Captures dfaSearchReverse(Input input, Cache cache, int matchEnd) {
            // Empty match: start == end, no reverse search needed
            if (matchEnd == input.start()) {
                Captures caps = new Captures(1);
                caps.set(0, matchEnd);
                caps.set(1, matchEnd);
                return caps;
            }

            // Anchored search: start is fixed at input.start()
            if (input.isAnchored()) {
                Captures caps = new Captures(1);
                caps.set(0, input.start());
                caps.set(1, matchEnd);
                return caps;
            }

            // No reverse DFA: fall back to PikeVM on narrowed window
            if (reverseDFA == null) {
                Input narrowed = input.withBounds(input.start(), matchEnd, false);
                return pikeVM.search(narrowed, cache.pikeVMCache());
            }

            // Three-phase: reverse DFA finds start
            Input revInput = input.withBounds(input.start(), matchEnd, true);
            SearchResult revResult = reverseDFA.searchRev(revInput, cache.reverseDFACache());
            return switch (revResult) {
                case SearchResult.Match rm -> {
                    Captures caps = new Captures(1);
                    caps.set(0, rm.offset());
                    caps.set(1, matchEnd);
                    yield caps;
                }
                case SearchResult.GaveUp g -> {
                    Input narrowed = input.withBounds(input.start(), matchEnd, false);
                    yield pikeVM.search(narrowed, cache.pikeVMCache());
                }
                case SearchResult.NoMatch n ->
                    throw new IllegalStateException("reverse DFA found no match after forward match");
            };
        }

        private Captures dfaSearchCaptures(Input input, Cache cache) {
            SearchResult fwdResult = forwardDFA.searchFwd(input, cache.forwardDFACache());
            return switch (fwdResult) {
                case SearchResult.NoMatch n -> null;
                case SearchResult.GaveUp g -> pikeVM.searchCaptures(input, cache.pikeVMCache());
                case SearchResult.Match m -> dfaSearchCapturesReverse(input, cache, m.offset());
            };
        }

        /**
         * After forward DFA finds matchEnd, use reverse DFA to narrow the window
         * for the capture engine. Falls back to PikeVM on the full window if
         * reverse DFA is unavailable or gives up.
         */
        private Captures dfaSearchCapturesReverse(Input input, Cache cache, int matchEnd) {
            // Empty match or anchored: capture engine on [start, matchEnd]
            if (matchEnd == input.start() || input.isAnchored()) {
                Input narrowed = input.withBounds(input.start(), matchEnd, true);
                return pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
            }

            // No reverse DFA: capture engine on [start, matchEnd]
            if (reverseDFA == null) {
                Input narrowed = input.withBounds(input.start(), matchEnd, false);
                return pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
            }

            // Three-phase: reverse DFA finds start, then capture engine on [start, end]
            Input revInput = input.withBounds(input.start(), matchEnd, true);
            SearchResult revResult = reverseDFA.searchRev(revInput, cache.reverseDFACache());
            return switch (revResult) {
                case SearchResult.Match rm -> {
                    Input narrowed = input.withBounds(rm.offset(), matchEnd, true);
                    yield captureEngine(narrowed, cache);
                }
                case SearchResult.GaveUp g -> {
                    Input narrowed = input.withBounds(input.start(), matchEnd, false);
                    yield pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
                }
                case SearchResult.NoMatch n ->
                    throw new IllegalStateException("reverse DFA found no match after forward match");
            };
        }

        /**
         * Selects the best capture engine for the given narrowed, anchored window.
         * Prefers the bounded backtracker for small windows (faster than PikeVM for
         * captures); falls back to PikeVM for larger windows or when the backtracker
         * is unavailable.
         */
        private Captures captureEngine(Input narrowed, Cache cache) {
            if (backtracker != null) {
                int windowLen = narrowed.end() - narrowed.start();
                if (windowLen <= backtracker.maxHaystackLen()) {
                    Captures caps = backtracker.searchCaptures(narrowed, cache.backtrackerCache());
                    if (caps != null) return caps;
                }
            }
            return pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
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

    record PrefilterOnly(Prefilter prefilter) implements Strategy {

        public PrefilterOnly {
            if (!prefilter.isExact()) {
                throw new IllegalArgumentException(
                        "PrefilterOnly requires an exact prefilter");
            }
        }

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
            int pos = prefilter.find(input.haystackStr(), input.start(), input.end());
            if (pos < 0) {
                return null;
            }
            Captures caps = new Captures(1);
            caps.set(0, pos);
            caps.set(1, pos + prefilter.matchLength());
            return caps;
        }

        @Override
        public Captures searchCaptures(Input input, Cache cache) {
            return search(input, cache);
        }
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
            lol.ohai.regex.automata.nfa.thompson.backtrack.BoundedBacktracker.Cache backtrackerCache
    ) {
        static final Cache EMPTY = new Cache(null, null, null, null);
    }
}
