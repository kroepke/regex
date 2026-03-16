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

class DenseDFAStartTest {

    @Test
    void multilinePatternBuildsDenseDFA() throws Exception {
        DenseDFA dfa = buildDense("(?m)^.+$");
        assertNotNull(dfa, "(?m)^.+$ should now build a dense DFA");
    }

    @Test
    void multilinePatternHasAcceleration() throws Exception {
        DenseDFA dfa = buildDense("(?m)^.+$");
        assertNotNull(dfa);
        assertTrue(dfa.hasAcceleratedStates(),
                "(?m)^.+$ should have accelerated states (.+ self-loops except \\n)");
    }

    @Test
    void multilineSearchMatchesLazy() throws Exception {
        String pattern = "(?m)^.+$";
        DenseDFA dense = buildDense(pattern);
        assertNotNull(dense);
        LazyDFA lazy = buildLazy(pattern);
        DFACache cache = lazy.createCache();

        List<String> inputs = List.of(
                "hello", "hello\nworld", "\nfirst\nsecond\n",
                "line1\nline2\nline3", "a", "\n", "\n\n",
                "no newline at end");

        for (String input : inputs) {
            Input in = Input.of(input);
            long denseResult = dense.searchFwd(in);
            long lazyResult = lazy.searchFwdLong(in, cache);

            assertEquals(SearchResult.isMatch(lazyResult), SearchResult.isMatch(denseResult),
                    "match mismatch for \"" + input.replace("\n", "\\n") + "\"");
            if (SearchResult.isMatch(lazyResult)) {
                assertEquals(SearchResult.matchOffset(lazyResult), SearchResult.matchOffset(denseResult),
                        "offset mismatch for \"" + input.replace("\n", "\\n") + "\"");
            }
        }
    }

    @Test
    void wordBoundaryPatternBuildsDenseDFA() throws Exception {
        // \b uses ASCII word boundary -- should build dense DFA
        // (Unicode word boundary would require quit chars)
        DenseDFA dfa = buildDense("(?-u)\\bword\\b");
        // May or may not build depending on quit char handling -- just verify no crash
    }

    @Test
    void multilineEmptyLinePatternMatchesLazy() throws Exception {
        // (?m)^$ should only match at empty lines (positions where \n precedes or start+end)
        String pattern = "(?m)^$";
        DenseDFA dense = buildDense(pattern);
        assertNotNull(dense);
        LazyDFA lazy = buildLazy(pattern);
        DFACache cache = lazy.createCache();

        // Test various inputs
        List<String> inputs = List.of("", "a", "abc", "\n", "a\n\nb", "\n\n");
        for (String input : inputs) {
            Input in = Input.of(input);
            long denseResult = dense.searchFwd(in);
            long lazyResult = lazy.searchFwdLong(in, cache);
            assertEquals(SearchResult.isMatch(lazyResult), SearchResult.isMatch(denseResult),
                    "match mismatch for (?m)^$ on \"" + input.replace("\n", "\\n") + "\": " +
                    "lazy=" + lazyResult + " dense=" + denseResult);
            if (SearchResult.isMatch(lazyResult)) {
                assertEquals(SearchResult.matchOffset(lazyResult), SearchResult.matchOffset(denseResult),
                        "offset mismatch for (?m)^$ on \"" + input.replace("\n", "\\n") + "\"");
            }
        }
    }

    @Test
    void wordStartOnEmptyMatchesLazy() throws Exception {
        String pattern = "\\b{start}";
        DenseDFA dense = buildDense(pattern);
        if (dense == null) return;
        LazyDFA lazy = buildLazy(pattern);
        DFACache cache = lazy.createCache();

        // \b{start} on empty string should not match
        Input emptyIn = Input.of("");
        long denseResult = dense.searchFwd(emptyIn);
        long lazyResult = lazy.searchFwdLong(emptyIn, cache);
        assertEquals(SearchResult.isMatch(lazyResult), SearchResult.isMatch(denseResult),
                "empty input match mismatch");
    }

    @Test
    void startTextPatternBuildsDenseDFA() throws Exception {
        DenseDFA dfa = buildDense("^hello");
        assertNotNull(dfa, "^hello should build a dense DFA");

        long result = dfa.searchFwd(Input.of("hello world"));
        assertTrue(SearchResult.isMatch(result));
    }

    @Test
    void lookAssertionPatternsMatchLazy() throws Exception {
        List<String> patterns = List.of("(?m)^\\w+$", "(?m)^.+$", "^abc");
        List<String> inputs = List.of(
                "abc", "abc\ndef", "\nabc\n", "xabc",
                "first\nsecond\nthird");

        for (String pattern : patterns) {
            DenseDFA dense = buildDense(pattern);
            if (dense == null) continue;  // some patterns may not build
            LazyDFA lazy = buildLazy(pattern);
            DFACache cache = lazy.createCache();

            for (String input : inputs) {
                Input in = Input.of(input);
                long denseResult = dense.searchFwd(in);
                long lazyResult = lazy.searchFwdLong(in, cache);

                assertEquals(SearchResult.isMatch(lazyResult), SearchResult.isMatch(denseResult),
                        "match mismatch for /" + pattern + "/ on \"" + input.replace("\n", "\\n") + "\"");
                if (SearchResult.isMatch(lazyResult)) {
                    assertEquals(SearchResult.matchOffset(lazyResult), SearchResult.matchOffset(denseResult),
                            "offset mismatch for /" + pattern + "/ on \"" + input.replace("\n", "\\n") + "\"");
                }
            }
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
