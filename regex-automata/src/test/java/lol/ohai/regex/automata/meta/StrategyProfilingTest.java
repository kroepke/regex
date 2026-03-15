package lol.ohai.regex.automata.meta;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.DFACache;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.dfa.lazy.SearchResult;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.backtrack.BoundedBacktracker;
import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnostic test for profiling DFA search component breakdown.
 * Measures forward-only vs three-phase search to identify bottlenecks.
 */
class StrategyProfilingTest {

    private static final int WARMUP = 3;
    private static final int ITERS = 5;

    @Test
    void charClassComponentBreakdown() throws Exception {
        String haystack = Files.readString(
                Path.of("../upstream/rebar/benchmarks/haystacks/opensubtitles/en-sampled.txt"));

        // Build engines
        Ast ast = Parser.parse("[a-zA-Z]+", 250);
        Hir hir = Translator.translate("[a-zA-Z]+", ast);
        NFA fwdNfa = Compiler.compile(hir);
        CharClasses fwdCc = CharClassBuilder.build(fwdNfa);
        LazyDFA fwdDfa = LazyDFA.create(fwdNfa, fwdCc);

        NFA revNfa = Compiler.compileReverse(hir);
        CharClasses revCc = CharClassBuilder.build(revNfa);
        LazyDFA revDfa = LazyDFA.create(revNfa, revCc);

        PikeVM pikeVM = new PikeVM(fwdNfa);
        BoundedBacktracker bt = new BoundedBacktracker(fwdNfa);
        Strategy strategy = new Strategy.Core(pikeVM, fwdDfa, revDfa, null, bt, null);

        Input baseInput = Input.of(haystack);
        int end = baseInput.end();

        System.out.println("Haystack length: " + haystack.length() + " chars");
        System.out.println("Forward DFA classes: " + fwdCc.classCount() + ", stride: " + fwdCc.stride());
        System.out.println("Reverse DFA classes: " + revCc.classCount() + ", stride: " + revCc.stride());

        // ---- Component 1: Forward DFA only (find all match ends) ----
        long bestFwd = Long.MAX_VALUE;
        int matchCount = 0;
        for (int iter = 0; iter < WARMUP + ITERS; iter++) {
            DFACache cache = fwdDfa.createCache();
            int pos = 0, lastEnd = -1, count = 0;
            long t0 = System.nanoTime();
            while (pos <= end) {
                Input input = baseInput.withBounds(pos, end, false);
                SearchResult result = fwdDfa.searchFwd(input, cache);
                if (!(result instanceof SearchResult.Match m)) break;
                int me = m.offset();
                if (me == pos && me == lastEnd) { pos = me + 1; continue; }
                count++;
                lastEnd = me;
                pos = me;
            }
            long elapsed = System.nanoTime() - t0;
            if (iter >= WARMUP) {
                bestFwd = Math.min(bestFwd, elapsed);
                matchCount = count;
            }
        }

        // ---- Component 2: Reverse DFA only (given known match ends) ----
        // First collect all match ends
        DFACache fwdCache = fwdDfa.createCache();
        int[] matchEnds = new int[matchCount];
        int[] matchSearchStarts = new int[matchCount];
        {
            int pos = 0, lastEnd = -1, idx = 0;
            while (pos <= end) {
                Input input = baseInput.withBounds(pos, end, false);
                SearchResult result = fwdDfa.searchFwd(input, fwdCache);
                if (!(result instanceof SearchResult.Match m)) break;
                int me = m.offset();
                if (me == pos && me == lastEnd) { pos = me + 1; continue; }
                matchSearchStarts[idx] = pos;
                matchEnds[idx] = me;
                idx++;
                lastEnd = me;
                pos = me;
            }
        }

        long bestRev = Long.MAX_VALUE;
        for (int iter = 0; iter < WARMUP + ITERS; iter++) {
            DFACache revCache = revDfa.createCache();
            long t0 = System.nanoTime();
            for (int i = 0; i < matchCount; i++) {
                Input revInput = baseInput.withBounds(matchSearchStarts[i], matchEnds[i], true);
                revDfa.searchRev(revInput, revCache);
            }
            long elapsed = System.nanoTime() - t0;
            if (iter >= WARMUP) bestRev = Math.min(bestRev, elapsed);
        }

        // ---- Component 3: Full three-phase via Strategy ----
        long bestFull = Long.MAX_VALUE;
        for (int iter = 0; iter < WARMUP + ITERS; iter++) {
            Strategy.Cache cache = strategy.createCache();
            int pos = 0, lastEnd = -1;
            long t0 = System.nanoTime();
            while (pos <= end) {
                Input input = baseInput.withBounds(pos, end, false);
                Captures caps = strategy.search(input, cache);
                if (caps == null) break;
                int ms = caps.start(0), me = caps.end(0);
                if (ms == me && me == lastEnd) { pos = me + 1; continue; }
                lastEnd = me;
                pos = me;
            }
            long elapsed = System.nanoTime() - t0;
            if (iter >= WARMUP) bestFull = Math.min(bestFull, elapsed);
        }

        // ---- Component 4: Input.withBounds overhead (no DFA, just object creation) ----
        long bestInputOnly = Long.MAX_VALUE;
        for (int iter = 0; iter < WARMUP + ITERS; iter++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < matchCount; i++) {
                Input input = baseInput.withBounds(matchSearchStarts[i], end, false);
                // touch to prevent dead code elimination
                if (input.start() < 0) throw new AssertionError();
            }
            long elapsed = System.nanoTime() - t0;
            if (iter >= WARMUP) bestInputOnly = Math.min(bestInputOnly, elapsed);
        }

        // ---- Component 5: Captures allocation overhead ----
        long bestCapsOnly = Long.MAX_VALUE;
        for (int iter = 0; iter < WARMUP + ITERS; iter++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < matchCount; i++) {
                Captures caps = new Captures(1);
                caps.set(0, matchSearchStarts[i]);
                caps.set(1, matchEnds[i]);
                if (caps.start(0) < 0) throw new AssertionError();
            }
            long elapsed = System.nanoTime() - t0;
            if (iter >= WARMUP) bestCapsOnly = Math.min(bestCapsOnly, elapsed);
        }

        // ---- Report ----
        long overhead = bestFull - bestFwd - bestRev;
        System.out.println();
        System.out.printf("Matches: %,d%n", matchCount);
        System.out.printf("Forward DFA:       %,8d µs  (%.2f µs/match)%n", bestFwd / 1000, (double) bestFwd / matchCount / 1000);
        System.out.printf("Reverse DFA:       %,8d µs  (%.2f µs/match)%n", bestRev / 1000, (double) bestRev / matchCount / 1000);
        System.out.printf("Full three-phase:  %,8d µs  (%.2f µs/match)%n", bestFull / 1000, (double) bestFull / matchCount / 1000);
        System.out.printf("Overhead (full-fwd-rev): %,d µs  (%.2f µs/match)%n", overhead / 1000, (double) overhead / matchCount / 1000);
        System.out.printf("Input.withBounds:  %,8d µs  (%.2f µs/match)%n", bestInputOnly / 1000, (double) bestInputOnly / matchCount / 1000);
        System.out.printf("Captures alloc:    %,8d µs  (%.2f µs/match)%n", bestCapsOnly / 1000, (double) bestCapsOnly / matchCount / 1000);
        System.out.println();
        System.out.printf("Breakdown: fwd=%.0f%% rev=%.0f%% overhead=%.0f%%%n",
                100.0 * bestFwd / bestFull, 100.0 * bestRev / bestFull, 100.0 * overhead / bestFull);

        assertTrue(matchCount > 100000, "Expected many matches, got " + matchCount);
    }
}
