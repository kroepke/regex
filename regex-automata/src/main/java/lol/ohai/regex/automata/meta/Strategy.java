package lol.ohai.regex.automata.meta;

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
 * <p>{@code Core} composes the PikeVM (and future engines) with an optional
 * prefilter. The prefilter scans for prefix candidates, then the engine
 * confirms matches.</p>
 */
public sealed interface Strategy permits Strategy.Core, Strategy.PrefilterOnly {

    Cache createCache();
    boolean isMatch(Input input, Cache cache);
    Captures search(Input input, Cache cache);
    Captures searchCaptures(Input input, Cache cache);

    /**
     * Core strategy: PikeVM (and future engines) with an optional prefilter.
     *
     * @param pikeVM    the PikeVM engine
     * @param prefilter the prefilter to use, or {@code null} for pure PikeVM
     */
    record Core(PikeVM pikeVM, Prefilter prefilter) implements Strategy {

        @Override
        public Cache createCache() {
            return new Cache(pikeVM.createCache());
        }

        @Override
        public boolean isMatch(Input input, Cache cache) {
            if (prefilter == null || input.isAnchored()) {
                return pikeVM.isMatch(input, cache.pikeVMCache());
            }
            return isMatchPrefilter(input, cache);
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
                if (pikeVM.isMatch(candidateInput, cache.pikeVMCache())) {
                    return true;
                }
                start = pos + 1;
            }
            return false;
        }

        @Override
        public Captures search(Input input, Cache cache) {
            if (prefilter == null || input.isAnchored()) {
                return pikeVM.search(input, cache.pikeVMCache());
            }
            return prefilterLoop(input, cache,
                    (in, c) -> pikeVM.search(in, c.pikeVMCache()));
        }

        @Override
        public Captures searchCaptures(Input input, Cache cache) {
            if (prefilter == null || input.isAnchored()) {
                return pikeVM.searchCaptures(input, cache.pikeVMCache());
            }
            return prefilterLoop(input, cache,
                    (in, c) -> pikeVM.searchCaptures(in, c.pikeVMCache()));
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
     * Per-search mutable state. Wraps a PikeVM cache (if present).
     * {@code EMPTY} is used by {@link PrefilterOnly} which needs no engine state.
     */
    record Cache(lol.ohai.regex.automata.nfa.thompson.pikevm.Cache pikeVMCache) {
        static final Cache EMPTY = new Cache(null);
    }
}
