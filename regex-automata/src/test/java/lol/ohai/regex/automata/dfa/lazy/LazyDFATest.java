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
    void unicodeWordBoundaryNoLongerBailsOut() {
        for (String pattern : List.of("\\bfoo\\b", "\\Binner\\B")) {
            assertNotNull(buildDFAWithQuit(pattern),
                    "DFA should be created with quit chars for: " + pattern);
        }
    }

    @Test
    void unicodeWordBoundaryUsesQuit() {
        var dfa = buildDFAWithQuit("\\b\\w+\\b");
        assertNotNull(dfa, "DFA should be created with quit chars for Unicode word boundary");

        // ASCII-only input: DFA handles it entirely
        var result = dfa.searchFwd(Input.of("hello world"), dfa.createCache());
        assertInstanceOf(SearchResult.Match.class, result);

        // Input with non-ASCII: DFA should give up at the non-ASCII char
        var result2 = dfa.searchFwd(Input.of("caf\u00e9"), dfa.createCache());
        assertTrue(result2 instanceof SearchResult.Match || result2 instanceof SearchResult.GaveUp);
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
        // Word boundary patterns need quit chars for correct word-char classification
        var dfa = buildDFAWithQuit("(?-u:\\b)ord(?-u:\\b)");
        assertNotNull(dfa);
        var result = dfa.searchFwd(Input.of("word"), dfa.createCache());
        assertInstanceOf(SearchResult.NoMatch.class, result);
    }

    @Test
    void forwardSearchWithNegatedWordBoundary() {
        // (?-u:\B)ord — matches 'ord' when NOT at word boundary
        var dfa = buildDFAWithQuit("(?-u:\\B)ord");
        assertNotNull(dfa);
        var result = dfa.searchFwd(Input.of("word"), dfa.createCache());
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
        // ASCII \b matches at word boundary — needs quit chars for word-char classes
        var dfa = buildDFAWithQuit("(?-u:\\b)");
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

    // -- Loop unrolling edge cases (forward) --

    @Test
    void fwdEmptyHaystack() {
        // 0 chars: inner loop never enters, outer tail handles it
        var result = search("a", "");
        assertInstanceOf(SearchResult.NoMatch.class, result);
    }

    @Test
    void fwdOneCharMatch() {
        // 1 char: inner loop guard (pos + 3 < end) fails, tail processes
        var result = search("a", "a");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(1, ((SearchResult.Match) result).offset());
    }

    @Test
    void fwdTwoCharMatch() {
        var result = search("ab", "ab");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(2, ((SearchResult.Match) result).offset());
    }

    @Test
    void fwdThreeCharMatch() {
        var result = search("abc", "abc");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(3, ((SearchResult.Match) result).offset());
    }

    @Test
    void fwdExactlyFourCharMatch() {
        // 4 chars: inner loop runs one full iteration
        var result = search("abcd", "abcd");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(4, ((SearchResult.Match) result).offset());
    }

    @Test
    void fwdFiveCharMatch() {
        // 5 chars: one full unrolled iteration + 1 char in tail
        var result = search("abcde", "abcde");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(5, ((SearchResult.Match) result).offset());
    }

    @Test
    void fwdLongLiteralMatch() {
        // Many full unrolled iterations
        var result = search("abcdefgh", "xxabcdefghxx");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(10, ((SearchResult.Match) result).offset());
    }

    @Test
    void fwdMatchAtUnrollBoundary() {
        // Pattern that matches at position 4 (one full unrolled iteration)
        var result = search("e", "abcde");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(5, ((SearchResult.Match) result).offset());
    }

    @Test
    void fwdDeadStateInLongInput() {
        // No match in a long input — dead state reached after exhausting alternatives
        var result = search("ZQZQ", "abcdefghijklmnopqrstuvwxyz");
        assertInstanceOf(SearchResult.NoMatch.class, result);
    }

    @Test
    void fwdMatchMidStreamAfterFullUnroll() {
        // 8-char haystack, match ends at pos 5: one full unrolled iteration (pos 0-3),
        // then break-out at step 1 of the second iteration when match state is hit.
        var result = search("[a-z]{5}", "abcdeXXX");
        assertInstanceOf(SearchResult.Match.class, result);
        assertEquals(5, ((SearchResult.Match) result).offset());
    }

    @Test
    void fwdMatchMidStreamAtStep2() {
        // 10-char haystack, match ends at pos 6: one full iteration (0-3),
        // then step 0 ok (pos 4), step 1 ok (pos 5), step 2 triggers match.
        var result = search("[a-z]{6}", "abcdefXXXX");
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

    private static LazyDFA buildDFAWithQuit(String pattern) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            boolean quitNonAscii = nfa.lookSetAny().containsUnicodeWord();
            CharClasses cc = CharClassBuilder.buildUnmerged(nfa, quitNonAscii);
            return LazyDFA.create(nfa, cc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile: " + pattern, e);
        }
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
