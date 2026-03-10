package lol.ohai.regex.automata.meta;

import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrategyTest {

    // -- PrefilterOnly tests --

    @Test
    void prefilterOnlyFindsLiteral() {
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.PrefilterOnly(pf);
        var cache = strategy.createCache();
        var input = Input.of("say hello world");

        var caps = strategy.search(input, cache);
        assertNotNull(caps);
        assertEquals(4, caps.start(0));
        assertEquals(9, caps.end(0));
    }

    @Test
    void prefilterOnlyReturnsNullOnNoMatch() {
        var pf = new SingleLiteral("xyz".toCharArray());
        var strategy = new Strategy.PrefilterOnly(pf);
        var cache = strategy.createCache();
        var input = Input.of("hello world");

        assertNull(strategy.search(input, cache));
    }

    @Test
    void prefilterOnlyIsMatch() {
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.PrefilterOnly(pf);
        var cache = strategy.createCache();

        assertTrue(strategy.isMatch(Input.of("say hello"), cache));
        assertFalse(strategy.isMatch(Input.of("say goodbye"), cache));
    }

    @Test
    void prefilterOnlySearchCapturesReturnsGroupZeroOnly() {
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.PrefilterOnly(pf);
        var cache = strategy.createCache();

        var caps = strategy.searchCaptures(Input.of("say hello"), cache);
        assertNotNull(caps);
        assertEquals(1, caps.groupCount());
        assertEquals(4, caps.start(0));
        assertEquals(9, caps.end(0));
    }

    @Test
    void prefilterOnlyRespectsInputBounds() {
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.PrefilterOnly(pf);
        var cache = strategy.createCache();

        var input = Input.of("hello world", 6, 11);
        assertNull(strategy.search(input, cache));
    }

    // -- Core with prefilter tests --

    @Test
    void coreWithPrefilterFindsMatch() {
        var pikeVM = compilePikeVM("hello\\w+");
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.Core(pikeVM, pf);
        var cache = strategy.createCache();

        var caps = strategy.search(Input.of("say helloWorld"), cache);
        assertNotNull(caps);
        assertEquals(4, caps.start(0));
        assertEquals(14, caps.end(0));
    }

    @Test
    void coreWithPrefilterSkipsFalsePositive() {
        var pikeVM = compilePikeVM("hello world");
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.Core(pikeVM, pf);
        var cache = strategy.createCache();

        var caps = strategy.search(Input.of("hello there hello world"), cache);
        assertNotNull(caps);
        assertEquals(12, caps.start(0));
        assertEquals(23, caps.end(0));
    }

    @Test
    void coreWithPrefilterReturnsNullOnNoMatch() {
        var pikeVM = compilePikeVM("hello world");
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.Core(pikeVM, pf);
        var cache = strategy.createCache();

        assertNull(strategy.search(Input.of("hello there"), cache));
    }

    @Test
    void coreWithoutPrefilterFallsThroughToPikeVM() {
        var pikeVM = compilePikeVM("[a-z]+");
        var strategy = new Strategy.Core(pikeVM, null);
        var cache = strategy.createCache();

        var caps = strategy.search(Input.of("123 abc"), cache);
        assertNotNull(caps);
        assertEquals(4, caps.start(0));
        assertEquals(7, caps.end(0));
    }

    @Test
    void coreSearchCapturesPopulatesGroups() {
        var pikeVM = compilePikeVM("(?P<word>[a-z]+)");
        var strategy = new Strategy.Core(pikeVM, null);
        var cache = strategy.createCache();

        var caps = strategy.searchCaptures(Input.of("123 abc"), cache);
        assertNotNull(caps);
        assertEquals(2, caps.groupCount());
        assertEquals(4, caps.start(1));
        assertEquals(7, caps.end(1));
    }

    // -- Helper --

    private static PikeVM compilePikeVM(String pattern) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            return new PikeVM(nfa);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile pattern: " + pattern, e);
        }
    }
}
