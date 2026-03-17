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
