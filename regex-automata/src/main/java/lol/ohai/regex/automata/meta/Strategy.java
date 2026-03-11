package lol.ohai.regex.automata.meta;

import lol.ohai.regex.automata.dfa.lazy.DFACache;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.dfa.lazy.SearchResult;
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
     */
    record Core(PikeVM pikeVM, LazyDFA forwardDFA, LazyDFA reverseDFA, Prefilter prefilter) implements Strategy {

        @Override
        public Cache createCache() {
            return new Cache(
                    pikeVM.createCache(),
                    forwardDFA != null ? forwardDFA.createCache() : null,
                    reverseDFA != null ? reverseDFA.createCache() : null
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
                    return prefilterLoop(input, cache, (in, c) -> dfaTwoPhaseSearch(in, c));
                }
                return prefilterLoop(input, cache,
                        (in, c) -> pikeVM.search(in, c.pikeVMCache()));
            }
            if (forwardDFA != null) {
                return dfaTwoPhaseSearch(input, cache);
            }
            return pikeVM.search(input, cache.pikeVMCache());
        }

        @Override
        public Captures searchCaptures(Input input, Cache cache) {
            if (prefilter != null && !input.isAnchored()) {
                if (forwardDFA != null) {
                    return prefilterLoop(input, cache,
                            (in, c) -> dfaTwoPhaseSearchCaptures(in, c));
                }
                return prefilterLoop(input, cache,
                        (in, c) -> pikeVM.searchCaptures(in, c.pikeVMCache()));
            }
            if (forwardDFA != null) {
                return dfaTwoPhaseSearchCaptures(input, cache);
            }
            return pikeVM.searchCaptures(input, cache.pikeVMCache());
        }

        private Captures dfaTwoPhaseSearch(Input input, Cache cache) {
            SearchResult fwdResult = forwardDFA.searchFwd(input, cache.forwardDFACache());
            return switch (fwdResult) {
                case SearchResult.Match m -> {
                    int matchEnd = m.offset();
                    // Bound the search window using the forward DFA's match end.
                    // The PikeVM determines the exact match with correct semantics (lazy
                    // quantifiers, alternation preference). The reverse DFA is not used here
                    // because its start-position hint may be incorrect for patterns where the
                    // forward DFA overestimates the match end.
                    Input narrowed = input.withBounds(input.start(), matchEnd, input.isAnchored());
                    yield pikeVM.search(narrowed, cache.pikeVMCache());
                }
                case SearchResult.NoMatch n -> null;
                case SearchResult.GaveUp g -> pikeVM.search(input, cache.pikeVMCache());
            };
        }

        private Captures dfaTwoPhaseSearchCaptures(Input input, Cache cache) {
            SearchResult fwdResult = forwardDFA.searchFwd(input, cache.forwardDFACache());
            return switch (fwdResult) {
                case SearchResult.Match m -> {
                    int matchEnd = m.offset();
                    // Bound the search window using the forward DFA's match end, then run
                    // the PikeVM for exact semantics. The reverse DFA hint is not used as
                    // the PikeVM anchor because the forward DFA may overestimate the end.
                    Input narrowed = input.withBounds(input.start(), matchEnd, input.isAnchored());
                    yield pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
                }
                case SearchResult.NoMatch n -> null;
                case SearchResult.GaveUp g -> pikeVM.searchCaptures(input, cache.pikeVMCache());
            };
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
            DFACache reverseDFACache
    ) {
        static final Cache EMPTY = new Cache(null, null, null);
    }
}
