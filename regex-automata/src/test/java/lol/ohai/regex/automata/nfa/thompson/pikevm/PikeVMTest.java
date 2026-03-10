package lol.ohai.regex.automata.nfa.thompson.pikevm;

import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PikeVMTest {

    @Test
    void matchLiteral() {
        assertMatch("a", "a", 0, 1);
    }

    @Test
    void noMatch() {
        assertNoMatch("a", "b");
    }

    @Test
    void matchInMiddle() {
        assertMatch("b", "abc", 1, 2);
    }

    @Test
    void matchConcat() {
        assertMatch("abc", "xabcy", 1, 4);
    }

    @Test
    void matchAlternation() {
        assertMatch("a|b", "b", 0, 1);
    }

    @Test
    void matchStar() {
        assertMatch("a*", "", 0, 0); // matches empty
    }

    @Test
    void matchStarGreedy() {
        assertMatch("a*", "aaa", 0, 3);
    }

    @Test
    void matchPlus() {
        assertMatch("a+", "aaa", 0, 3);
    }

    @Test
    void matchPlusNoMatch() {
        assertNoMatch("a+", "bbb");
    }

    @Test
    void matchQuestion() {
        assertMatch("a?", "a", 0, 1);
    }

    @Test
    void matchQuestionEmpty() {
        assertMatch("a?", "b", 0, 0); // matches empty at start
    }

    @Test
    void matchDot() {
        assertMatch(".", "x", 0, 1);
    }

    @Test
    void matchDotDoesNotMatchNewline() {
        assertNoMatch("^.$", "\n");
    }

    @Test
    void matchCapture() {
        var result = findCaptures("(a)(b)", "ab");
        assertNotNull(result);
        assertEquals(0, result.start(0)); // overall match
        assertEquals(2, result.end(0));
        assertEquals(0, result.start(1)); // group 1: "a"
        assertEquals(1, result.end(1));
        assertEquals(1, result.start(2)); // group 2: "b"
        assertEquals(2, result.end(2));
    }

    @Test
    void matchNamedCapture() {
        var result = findCaptures("(?P<x>a)(?P<y>b)", "ab");
        assertNotNull(result);
        assertEquals(0, result.start(1));
        assertEquals(1, result.end(1));
    }

    @Test
    void matchAnchored() {
        assertMatch("^abc", "abc", 0, 3);
        assertNoMatch("^abc", "xabc");
    }

    @Test
    void matchEndAnchor() {
        assertMatch("abc$", "abc", 0, 3);
        assertNoMatch("abc$", "abcx");
    }

    @Test
    void matchCharClass() {
        assertMatch("[a-z]+", "hello", 0, 5);
    }

    @Test
    void matchPerlDigit() {
        assertMatch("(?-u)\\d+", "abc123", 3, 6);
    }

    @Test
    void matchWordBoundary() {
        assertMatch("\\bfoo\\b", "the foo bar", 4, 7);
    }

    @Test
    void matchLazy() {
        assertMatch("a+?", "aaa", 0, 1);
    }

    @Test
    void matchBounded() {
        assertMatch("a{2,3}", "aaaaa", 0, 3);
    }

    @Test
    void leftmostFirst() {
        // "a|ab" on "ab" should match "a" (leftmost-first), not "ab"
        assertMatch("a|ab", "ab", 0, 1);
    }

    @Test
    void emptyMatch() {
        assertMatch("", "abc", 0, 0);
    }

    @Test
    void isMatchBasic() {
        NFA nfa = compileToNfa("foo");
        PikeVM vm = new PikeVM(nfa);
        Cache cache = vm.createCache();
        assertTrue(vm.isMatch(Input.of("foo"), cache));
        assertTrue(vm.isMatch(Input.of("xxfooyy"), cache));
        assertFalse(vm.isMatch(Input.of("bar"), cache));
    }

    @Test
    void anchoredSearch() {
        NFA nfa = compileToNfa("abc");
        PikeVM vm = new PikeVM(nfa);
        Cache cache = vm.createCache();
        // Anchored should only match at start
        Captures caps = vm.search(Input.anchored("abc"), cache);
        assertNotNull(caps);
        // Anchored should fail if pattern is not at start
        caps = vm.search(Input.anchored("xabc"), cache);
        assertNull(caps);
    }

    @Test
    void matchMultiByteUnicode() {
        // Euro sign is U+20AC, 1 char in UTF-16
        assertMatch(".", "\u20AC", 0, 1);
    }

    @Test
    void captureNestedGroups() {
        var result = findCaptures("(a(b)c)", "abc");
        assertNotNull(result);
        assertEquals(0, result.start(0));
        assertEquals(3, result.end(0));
        assertEquals(0, result.start(1)); // group 1: "abc"
        assertEquals(3, result.end(1));
        assertEquals(1, result.start(2)); // group 2: "b"
        assertEquals(2, result.end(2));
    }

    @Test
    void matchAtEndOfString() {
        assertMatch("c", "abc", 2, 3);
    }

    @Test
    void matchEmptyAlternation() {
        // "a|" matches either "a" or empty string
        assertMatch("a|", "b", 0, 0);
    }

    // ---- Helpers ----

    private void assertMatch(String pattern, String haystack, int expectedStart, int expectedEnd) {
        NFA nfa = compileToNfa(pattern);
        PikeVM vm = new PikeVM(nfa);
        Cache cache = vm.createCache();
        Input input = Input.of(haystack);
        Captures caps = vm.search(input, cache);
        assertNotNull(caps, "Expected match for /" + pattern + "/ on \"" + haystack + "\"");
        assertEquals(expectedStart, caps.start(0), "start offset for /" + pattern + "/ on \"" + haystack + "\"");
        assertEquals(expectedEnd, caps.end(0), "end offset for /" + pattern + "/ on \"" + haystack + "\"");
    }

    private void assertNoMatch(String pattern, String haystack) {
        NFA nfa = compileToNfa(pattern);
        PikeVM vm = new PikeVM(nfa);
        Cache cache = vm.createCache();
        Input input = Input.of(haystack);
        Captures caps = vm.search(input, cache);
        assertNull(caps, "Expected no match for /" + pattern + "/ on \"" + haystack + "\"");
    }

    private Captures findCaptures(String pattern, String haystack) {
        NFA nfa = compileToNfa(pattern);
        PikeVM vm = new PikeVM(nfa);
        Cache cache = vm.createCache();
        Input input = Input.of(haystack);
        return vm.searchCaptures(input, cache);
    }

    private static NFA compileToNfa(String pattern) {
        try {
            Ast ast = Parser.parse(pattern);
            Hir hir = Translator.translate(pattern, ast);
            return Compiler.compile(hir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile pattern: " + pattern, e);
        }
    }
}
