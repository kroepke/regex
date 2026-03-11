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

import java.util.List;

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

    // -- Look-assertion DFA --

    @Test
    void noBailOutForSupportedLookAssertionPatterns() {
        // DFA handles text/line anchors and ASCII word boundaries
        for (String pattern : List.of("^abc", "abc$", "(?-u:\\b)foo(?-u:\\b)", "(?-u:\\B)inner(?-u:\\B)")) {
            assertNotNull(buildDFA(pattern), "DFA should not bail out for: " + pattern);
        }
    }

    @Test
    void bailsOutForUnicodeWordBoundary() {
        // Unicode word boundaries require Unicode word-char tables the DFA lacks
        for (String pattern : List.of("\\bfoo\\b", "\\Binner\\B")) {
            assertNull(buildDFA(pattern), "DFA should bail out for: " + pattern);
        }
    }

    @Test
    void lookAssertionFreePatternUnchanged() {
        var dfa = buildDFA("[a-z]+");
        assertNotNull(dfa);
        var cache = dfa.createCache();
        var result = dfa.searchFwd(Input.of("123 hello"), cache);
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(9, ((SearchResult.Match) result).offset());
    }

    @Test
    void forwardSearchWithStartTextAnchor() {
        // Pattern: ^abc — should match only at position 0
        var result = search("^abc", "abcdef");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(3, ((SearchResult.Match) result).offset());
    }

    @Test
    void startTextAnchorDoesNotMatchMidString() {
        var result = search("^abc", "xabc");
        assertInstanceOf(SearchResult.NoMatch.class, result);
    }

    @Test
    void forwardSearchWithEndAnchor() {
        // Pattern: abc$
        var result = search("abc$", "xyzabc");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(6, ((SearchResult.Match) result).offset());
    }

    @Test
    void forwardSearchWithWordBoundary() {
        // ASCII word boundary: (?-u:\b)word(?-u:\b)
        var result = search("(?-u:\\b)word(?-u:\\b)", "a word here");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(6, ((SearchResult.Match) result).offset());  // "word" ends at 6
    }

    @Test
    void forwardSearchWithWordBoundaryAtInputStart() {
        // (?-u:\b)foo at start of input
        var result = search("(?-u:\\b)foo", "foobar");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(3, ((SearchResult.Match) result).offset());
    }

    @Test
    void forwardSearchWithWordBoundaryNoMatch() {
        // (?-u:\b)ord(?-u:\b) should not match within a word
        var result = search("(?-u:\\b)ord(?-u:\\b)", "word");
        assertInstanceOf(SearchResult.NoMatch.class, result);
    }

    @Test
    void forwardSearchWithNegatedWordBoundary() {
        // (?-u:\B)ord — matches 'ord' when NOT at word boundary
        var result = search("(?-u:\\B)ord", "word");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(4, ((SearchResult.Match) result).offset());
    }

    @Test
    void multilineStartLineForwardSearch() {
        // (?m)^bar$ — multiline: ^ matches after \n
        var result = search("(?m)^bar$", "foo\nbar\n");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(7, ((SearchResult.Match) result).offset());
    }

    @Test
    void multilineCaretMatchesAfterNewline() {
        // (?m)^ matches at pos 0 and after each \n
        var result = search("(?m)^[a-z]+", "abc\ndef\nghi");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(3, ((SearchResult.Match) result).offset());
    }

    @Test
    void wordBoundaryEmptyMatch() {
        // ASCII \b matches at word boundary
        var dfa = buildDFA("(?-u:\\b)");
        assertNotNull(dfa);
        var cache = dfa.createCache();
        var result = dfa.searchFwd(Input.of("hello world"), cache);
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(0, ((SearchResult.Match) result).offset());
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

    // -- Reverse search --

    @Test
    void reverseSearchFindsMatchStart() {
        SearchResult result = searchReverse("abc", "xxabcxx", 0, 5);
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(2, ((SearchResult.Match) result).offset());
    }

    @Test
    void reverseSearchSingleChar() {
        SearchResult result = searchReverse("a", "xxax", 0, 3);
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(2, ((SearchResult.Match) result).offset());
    }

    @Test
    void reverseSearchNoMatch() {
        SearchResult result = searchReverse("abc", "xxdefxx", 0, 7);
        assertInstanceOf(SearchResult.NoMatch.class, result);
    }

    @Test
    void reverseSearchCharClass() {
        SearchResult result = searchReverse("[a-z]+", "xx hello xx", 0, 8);
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(3, ((SearchResult.Match) result).offset());
    }

    @Test
    void reverseSearchAtBoundary() {
        SearchResult result = searchReverse("abc", "abcxx", 0, 3);
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(0, ((SearchResult.Match) result).offset());
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

    private SearchResult searchReverse(String pattern, String haystack, int start, int end) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA reverseNfa = Compiler.compileReverse(hir);
            CharClasses charClasses = CharClassBuilder.build(reverseNfa);
            LazyDFA dfa = LazyDFA.create(reverseNfa, charClasses);
            assertNotNull(dfa);
            DFACache cache = dfa.createCache();
            Input input = Input.of(haystack).withBounds(start, end, true);
            return dfa.searchRev(input, cache);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile reverse: " + pattern, e);
        }
    }
}
