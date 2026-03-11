package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LazyDFATest {

    // -- Basic match detection --

    @Test
    void literalMatch() {
        var result = search("abc", "xyzabcdef");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(6, ((SearchResult.Match) result).offset());
    }

    @Test
    void literalNoMatch() {
        var result = search("abc", "xyzdef");
        assertInstanceOf(SearchResult.NoMatch.class, result);
    }

    @Test
    void charClassMatch() {
        var result = search("[a-z]+", "123abc456");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(6, ((SearchResult.Match) result).offset());
    }

    @Test
    void alternationMatch() {
        var result = search("cat|dog", "I have a dog");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(12, ((SearchResult.Match) result).offset());
    }

    @Test
    void quantifierMatch() {
        var result = search("a+", "bbaaa");
        assertInstanceOf(SearchResult.Match.class, result);
        // Leftmost-first: match "aaa" at positions 2-5
        assertEquals(5, ((SearchResult.Match) result).offset());
    }

    @Test
    void matchAtStartOfInput() {
        var result = search("abc", "abcdef");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(3, ((SearchResult.Match) result).offset());
    }

    @Test
    void matchAtEndOfInput() {
        var result = search("[0-9]+", "abc123");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(6, ((SearchResult.Match) result).offset());
    }

    @Test
    void emptyInput() {
        var result = search("abc", "");
        assertInstanceOf(SearchResult.NoMatch.class, result);
    }

    @Test
    void dotStarMatch() {
        // .* matches everything (greedy), should match at position 0
        var result = search("a.*b", "axxxb");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(5, ((SearchResult.Match) result).offset());
    }

    // -- Empty matches --

    @Test
    void emptyMatchAtStart() {
        // a* can match empty string at position 0
        var result = search("a*", "xyz");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(0, ((SearchResult.Match) result).offset());
    }

    @Test
    void optionalMatch() {
        var result = search("a?", "bbb");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(0, ((SearchResult.Match) result).offset());
    }

    // -- Unicode --

    @Test
    void unicodeCharClassMatch() {
        // CJK range — exercises non-zero high-byte rows in two-level table
        var result = search("[\u4e00-\u9fff]+", "hello\u4e16\u754c");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(7, ((SearchResult.Match) result).offset());
    }

    // -- Anchored search --

    @Test
    void anchoredMatchAtStart() {
        var dfa = buildDFA("abc");
        assertNotNull(dfa);
        var cache = dfa.createCache();
        var input = Input.anchored("abcdef");
        var result = dfa.searchFwd(input, cache);
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(3, ((SearchResult.Match) result).offset());
    }

    @Test
    void anchoredNoMatchWhenNotAtStart() {
        var dfa = buildDFA("abc");
        assertNotNull(dfa);
        var cache = dfa.createCache();
        var input = Input.anchored("xyzabc");
        var result = dfa.searchFwd(input, cache);
        assertInstanceOf(SearchResult.NoMatch.class, result);
    }

    // -- Look-assertion bailout --

    @Test
    void lookAssertionPatternReturnsNull() {
        // Pattern with ^ should bail out
        assertNull(buildDFA("^abc"));
        assertNull(buildDFA("abc$"));
        assertNull(buildDFA("\\bword\\b"));
    }

    @Test
    void patternWithoutLookAssertionReturnsNonNull() {
        assertNotNull(buildDFA("abc"));
        assertNotNull(buildDFA("[a-z]+"));
        assertNotNull(buildDFA("a|b|c"));
    }

    // -- Input bounds --

    @Test
    void respectsInputBounds() {
        var dfa = buildDFA("abc");
        assertNotNull(dfa);
        var cache = dfa.createCache();

        // Search only in "xyzabc" starting at position 3
        var input = Input.of("xyzabcdef", 3, 9);
        var result = dfa.searchFwd(input, cache);
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(6, ((SearchResult.Match) result).offset());
    }

    // -- Helpers --

    private SearchResult search(String pattern, String haystack) {
        var dfa = buildDFA(pattern);
        assertNotNull(dfa, "pattern should not bail out: " + pattern);
        var cache = dfa.createCache();
        return dfa.searchFwd(Input.of(haystack), cache);
    }

    private static LazyDFA buildDFA(String pattern) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            CharClasses cc = CharClassBuilder.build(nfa);
            return LazyDFA.create(nfa, cc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile: " + pattern, e);
        }
    }
}
