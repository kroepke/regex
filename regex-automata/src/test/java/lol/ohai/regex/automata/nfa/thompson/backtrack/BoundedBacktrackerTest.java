package lol.ohai.regex.automata.nfa.thompson.backtrack;

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

class BoundedBacktrackerTest {

    @Test
    void anchoredLiteralMatch() throws Exception {
        var bt = create("abc");
        var caps = bt.searchCaptures(Input.anchored("abcdef"), bt.createCache());
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(3, caps.end(0));
    }

    @Test
    void anchoredNoMatch() throws Exception {
        var bt = create("abc");
        var caps = bt.searchCaptures(Input.anchored("xyzdef"), bt.createCache());
        assertNull(caps);
    }

    @Test
    void anchoredWithCaptures() throws Exception {
        var bt = create("(\\d{4})-(\\d{2})-(\\d{2})");
        var caps = bt.searchCaptures(Input.anchored("2024-03-14"), bt.createCache());
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(10, caps.end(0));
        assertEquals(0, caps.start(1));  // year
        assertEquals(4, caps.end(1));
        assertEquals(5, caps.start(2));  // month
        assertEquals(7, caps.end(2));
        assertEquals(8, caps.start(3));  // day
        assertEquals(10, caps.end(3));
    }

    @Test
    void unanchoredSearch() throws Exception {
        var bt = create("[a-z]+");
        var caps = bt.search(Input.of("123abc456"), bt.createCache());
        assertNotNull(caps);
        assertEquals(3, caps.start(0));
        assertEquals(6, caps.end(0));
    }

    @Test
    void visitedBitsetPreventsInfiniteLoop() throws Exception {
        var bt = create("(a*)*");
        var caps = bt.searchCaptures(Input.anchored("aaa"), bt.createCache());
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(3, caps.end(0));
    }

    @Test
    void alternationPrefersFirst() throws Exception {
        var bt = create("(a|ab)");
        var caps = bt.searchCaptures(Input.anchored("ab"), bt.createCache());
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(1, caps.end(0));
    }

    @Test
    void maxHaystackLenEnforced() throws Exception {
        var bt = create("a+");
        int maxLen = bt.maxHaystackLen();
        assertTrue(maxLen > 0);
        String tooLong = "a".repeat(maxLen + 10);
        assertNull(bt.search(Input.of(tooLong), bt.createCache()));
    }

    private BoundedBacktracker create(String pattern) throws Exception {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        NFA nfa = Compiler.compile(hir);
        return new BoundedBacktracker(nfa);
    }
}
