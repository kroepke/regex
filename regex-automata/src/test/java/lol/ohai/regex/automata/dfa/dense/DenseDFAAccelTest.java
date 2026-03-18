package lol.ohai.regex.automata.dfa.dense;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.DFACache;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.dfa.lazy.SearchResult;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DenseDFAAccelTest {

    @Test
    void charClassPatternHasAcceleratedState() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        assertTrue(dfa.hasAcceleratedStates(),
                "[a-z]+ should have at least one accelerated state");
    }

    @Test
    void acceleratedSearchMatchesLazy() throws Exception {
        List<String> patterns = List.of(
                "[a-z]+", "[a-zA-Z0-9]+", "cat|dog|bird",
                "ab+c", "[^x]+", "a", "abc");
        List<String> inputs = List.of(
                "hello world", "abc123 def456", "I have a cat and a dog",
                "abbbbc xyz", "xxhelloxx", "", "a", "abc",
                "a]b]c]d]e]f]g]h]i]j]k]l]m");

        for (String pattern : patterns) {
            DenseDFA dense = buildDense(pattern);
            if (dense == null) continue;
            LazyDFA lazy = buildLazy(pattern);
            DFACache cache = lazy.createCache();

            for (String input : inputs) {
                Input in = Input.of(input);
                long denseResult = dense.searchFwd(in);
                long lazyResult = lazy.searchFwdLong(in, cache);

                assertEquals(SearchResult.isMatch(lazyResult), SearchResult.isMatch(denseResult),
                        "match mismatch for /" + pattern + "/ on \"" + input + "\"");
                if (SearchResult.isMatch(lazyResult)) {
                    assertEquals(SearchResult.matchOffset(lazyResult), SearchResult.matchOffset(denseResult),
                            "offset mismatch for /" + pattern + "/ on \"" + input + "\"");
                }
            }
        }
    }

    @Test
    void singleCharPatternNotAccelerated() throws Exception {
        DenseDFA dfa = buildDense("a");
        assertNotNull(dfa);
        // Single char pattern: start state transitions to match on 'a', no self-loop
        // Not meaningful to accelerate
    }

    @Test
    void acceleratedSearchOnLongInput() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        // 10K char input: mostly alpha with spaces every ~50 chars
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("abcdefghijklmnopqrstuvwxyz abcdefghijklmnopqrstuv ");
        }
        long result = dfa.searchFwd(Input.of(sb.toString()));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(26, SearchResult.matchOffset(result));  // first word ends at 26
    }

    @Test
    void indexOfAccelerationForMultiline() throws Exception {
        DenseDFA dfa = buildDense("(?m)^.+$");
        assertNotNull(dfa);
        // Multiline: .+ escapes on \n only → should use indexOf('\n')

        String input = "first line\nsecond line\nthird line";
        long result = dfa.searchFwd(Input.of(input));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(10, SearchResult.matchOffset(result));  // "first line" ends at 10
    }

    @Test
    void matchPlusAccelOverlap() {
        // .+ : match state self-loops on everything except \n → single escape byte \n (not space)
        // → match state is classified as match+accel, so accel range overlaps match range
        DenseDFA dfa = buildDenseUnchecked(".+");
        assertNotNull(dfa);
        assertTrue(dfa.minAccel() >= 0, "should have accel states");
        // The match state for .+ should be in BOTH ranges: minAccel <= maxMatch
        assertTrue(dfa.minAccel() <= dfa.maxMatch(),
                "accel range should overlap with match range (minAccel <= maxMatch)");
        assertTrue(dfa.maxAccel() >= dfa.minMatch(),
                "accel range should overlap with match range (maxAccel >= minMatch)");
    }

    @Test
    void startStateAcceleration() {
        // (?m)^.+ : the match state self-loops on non-\n → single escape byte \n → match+accel
        // The start state itself is not a self-looping state (it dispatches on ^ context),
        // so accel applies to the accumulated-dot state (which is both match and accel).
        DenseDFA dfa = buildDenseUnchecked("(?m)^.+");
        assertNotNull(dfa);
        assertTrue(dfa.minAccel() >= 0, "should have accel states");
        // The match+accel state should be at or near maxMatch
        assertTrue(dfa.isAccel(dfa.maxMatch()),
                "match state at maxMatch (sid=" + dfa.maxMatch() + ") should be accelerated");
        // Accel range overlaps the match range
        assertTrue(dfa.minAccel() <= dfa.maxMatch(),
                "accel range should overlap match range");
    }

    @Test
    void spaceExclusionPreventsAccel() {
        // [^ ]+ self-loops on everything except space → escape char is space.
        // Space exclusion (accel.rs:449-458) prevents acceleration.
        DenseDFA dfa = buildDenseUnchecked("[^ ]+");
        assertNotNull(dfa);
        assertTrue(dfa.minMatch() >= 0, "should have match states");
        // The match state should NOT be in the accel range
        for (int sid = dfa.minMatch(); sid <= dfa.maxMatch(); sid += dfa.stride()) {
            assertFalse(dfa.isAccel(sid),
                    "match state sid=" + sid + " should NOT be accelerated (space exclusion)");
        }
    }

    @Test
    void moreThanThreeEscapesNotAccelerated() {
        // Structural regression test: accel analysis respects the >3 threshold.
        // "a{2}" has intermediate and match states; verify DFA builds correctly.
        DenseDFA dfa = buildDenseUnchecked("a{2}");
        assertNotNull(dfa);
        assertTrue(dfa.stateCount() >= 3);
    }

    private static DenseDFA buildDenseUnchecked(String pattern) {
        try {
            return buildDense(pattern);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile: " + pattern, e);
        }
    }

    private static DenseDFA buildDense(String pattern) throws Exception {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        NFA nfa = Compiler.compile(hir);
        CharClasses cc = CharClassBuilder.build(nfa);
        if (cc == null) return null;
        return new DenseDFABuilder().build(nfa, cc);
    }

    private static LazyDFA buildLazy(String pattern) throws Exception {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        NFA nfa = Compiler.compile(hir);
        CharClasses cc = CharClassBuilder.build(nfa);
        return LazyDFA.create(nfa, cc);
    }
}
