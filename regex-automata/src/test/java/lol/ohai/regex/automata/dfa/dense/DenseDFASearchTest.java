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

class DenseDFASearchTest {

    /**
     * Cross-validation: dense DFA must produce identical results to lazy DFA
     * for every pattern x input combination.
     */
    @Test
    void denseMatchesLazyForSimplePatterns() throws Exception {
        List<String> patterns = List.of(
                "[a-z]+", "[a-zA-Z0-9]+", "cat|dog|bird",
                "ab+c", "[^x]+", "a", "abc", "Sherlock|Watson|Holmes");
        List<String> inputs = List.of(
                "hello world", "abc123 def456", "I have a cat and a dog",
                "abbbbc xyz", "xxhelloxx", "", "a", "abc",
                "Sherlock Holmes and Dr Watson");

        for (String pattern : patterns) {
            DenseDFA dense = buildDense(pattern);
            if (dense == null) continue;
            LazyDFA lazy = buildLazy(pattern);
            DFACache cache = lazy.createCache();

            for (String input : inputs) {
                long denseResult = dense.searchFwd(Input.of(input));
                long lazyResult = lazy.searchFwdLong(Input.of(input), cache);

                boolean denseIsMatch = SearchResult.isMatch(denseResult);
                boolean lazyIsMatch = SearchResult.isMatch(lazyResult);
                assertEquals(lazyIsMatch, denseIsMatch,
                        "match/no-match mismatch for /" + pattern + "/ on \"" + input
                                + "\": lazy=" + lazyResult + " dense=" + denseResult);

                if (lazyIsMatch && denseIsMatch) {
                    assertEquals(
                            SearchResult.matchOffset(lazyResult),
                            SearchResult.matchOffset(denseResult),
                            "offset mismatch for /" + pattern + "/ on \"" + input + "\"");
                }
            }
        }
    }

    @Test
    void denseSearchNoMatch() throws Exception {
        DenseDFA dfa = buildDense("[0-9]+");
        assertNotNull(dfa);
        assertTrue(SearchResult.isNoMatch(dfa.searchFwd(Input.of("no digits here"))));
    }

    @Test
    void denseSearchMatchAtStart() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.of("hello 123"));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(5, SearchResult.matchOffset(result));
    }

    @Test
    void denseSearchMatchAtEnd() throws Exception {
        DenseDFA dfa = buildDense("[0-9]+");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.of("abc 123"));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(7, SearchResult.matchOffset(result));
    }

    @Test
    void denseSearchEmptyInput() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        assertTrue(SearchResult.isNoMatch(dfa.searchFwd(Input.of(""))));
    }

    @Test
    void denseSearchWithBounds() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.of("123 hello 456", 4, 9));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(9, SearchResult.matchOffset(result));
    }

    @Test
    void denseSearchAnchored() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.anchored("hello 123"));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(5, SearchResult.matchOffset(result));
    }

    @Test
    void denseSearchAnchoredNoMatchAtStart() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.anchored("123 hello"));
        LazyDFA lazy = buildLazy("[a-z]+");
        DFACache cache = lazy.createCache();
        long lazyResult = lazy.searchFwdLong(Input.anchored("123 hello"), cache);
        assertEquals(SearchResult.isMatch(lazyResult), SearchResult.isMatch(result));
    }

    @Test
    void denseSearchSingleCharMatch() throws Exception {
        DenseDFA dfa = buildDense("a");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.of("xax"));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(2, SearchResult.matchOffset(result));
    }

    @Test
    void denseSearchLiteralInMiddle() throws Exception {
        DenseDFA dfa = buildDense("cat");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.of("a cat!"));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(5, SearchResult.matchOffset(result));
    }

    @Test
    void denseSearchAlternation() throws Exception {
        DenseDFA dfa = buildDense("cat|dog|bird");
        assertNotNull(dfa);

        // Verify the DFA walk for "cat" reaches a match-flagged transition.
        // Transitions preserve MATCH_FLAG, so we strip it between steps.
        CharClasses cc = dfa.charClasses();
        int[] tt = dfa.transTable();
        int sid = dfa.startStates()[0]; // TEXT unanchored
        int s1 = tt[sid + cc.classify('c')] & 0x7FFF_FFFF;
        int s2 = tt[s1 + cc.classify('a')] & 0x7FFF_FFFF;
        int catSid = tt[s2 + cc.classify('t')];
        // The transition to catSid should carry MATCH_FLAG (delayed match for "cat")
        assertTrue(catSid < 0, "transition after 'cat' should carry MATCH_FLAG");

        // Test the full search
        long result = dfa.searchFwd(Input.of("I have a cat and a dog"));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(12, SearchResult.matchOffset(result));
    }

    @Test
    void denseSearchRepeatedMatches() throws Exception {
        DenseDFA dfa = buildDense("[0-9]+");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.of("abc 123 def 456"));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(7, SearchResult.matchOffset(result));
    }

    private static DenseDFA buildDense(String pattern) throws Exception {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        NFA nfa = Compiler.compile(hir);
        CharClasses cc = CharClassBuilder.build(nfa);
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
