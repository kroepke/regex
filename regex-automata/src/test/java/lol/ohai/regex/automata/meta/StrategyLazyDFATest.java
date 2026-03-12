package lol.ohai.regex.automata.meta;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrategyLazyDFATest {

    @Test
    void coreWithDFAFindsMatchStartAndEnd() {
        var core = buildCore("[a-z]+");
        var cache = core.createCache();
        var caps = core.search(Input.of("123abc456"), cache);
        assertNotNull(caps);
        assertEquals(3, caps.start(0));
        assertEquals(6, caps.end(0));
    }

    @Test
    void coreWithDFAIsMatch() {
        var core = buildCore("[a-z]+");
        var cache = core.createCache();
        assertTrue(core.isMatch(Input.of("123abc"), cache));
        assertFalse(core.isMatch(Input.of("123456"), cache));
    }

    @Test
    void coreWithDFASearchCaptures() {
        var core = buildCore("([a-z]+)([0-9]+)");
        var cache = core.createCache();
        var caps = core.searchCaptures(Input.of("abc123"), cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(6, caps.end(0));
        assertEquals(0, caps.start(1));
        assertEquals(3, caps.end(1));
        assertEquals(3, caps.start(2));
        assertEquals(6, caps.end(2));
    }

    @Test
    void coreWithNullDFAFallsBackToPikeVM() {
        // Patterns with look-assertions get null LazyDFA
        var core = buildCore("^abc");
        var cache = core.createCache();
        var caps = core.search(Input.of("abc"), cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(3, caps.end(0));
    }

    @Test
    void coreWithPrefilterAndDFA() {
        var core = buildCoreWithPrefilter("hello[a-z]+", "hello");
        var cache = core.createCache();
        var caps = core.search(Input.of("say helloworld"), cache);
        assertNotNull(caps);
        assertEquals(4, caps.start(0));
        assertEquals(14, caps.end(0));
    }

    // -- Helpers --

    private Strategy.Core buildCore(String pattern) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            CharClasses cc = CharClassBuilder.build(nfa);
            PikeVM pikeVM = new PikeVM(nfa);
            LazyDFA lazyDFA = LazyDFA.create(nfa, cc);
            return new Strategy.Core(pikeVM, lazyDFA, null, null, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed: " + pattern, e);
        }
    }

    private Strategy.Core buildCoreWithPrefilter(String pattern, String prefix) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            CharClasses cc = CharClassBuilder.build(nfa);
            PikeVM pikeVM = new PikeVM(nfa);
            LazyDFA lazyDFA = LazyDFA.create(nfa, cc);
            Prefilter prefilter = new SingleLiteral(prefix.toCharArray());
            return new Strategy.Core(pikeVM, lazyDFA, null, prefilter, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed: " + pattern, e);
        }
    }
}
