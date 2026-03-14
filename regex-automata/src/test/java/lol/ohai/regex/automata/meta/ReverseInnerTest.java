package lol.ohai.regex.automata.meta;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.backtrack.BoundedBacktracker;
import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.LiteralExtractor;
import lol.ohai.regex.syntax.hir.LiteralSeq;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReverseInnerTest {

    private record Setup(Strategy.ReverseInner strategy, Strategy.Cache cache) {}

    private Setup build(String pattern) {
        try {
            Ast ast = Parser.parse(pattern, 100);
            Hir hir = Translator.translate(pattern, ast);

            LiteralExtractor.InnerLiteral inner = LiteralExtractor.extractInner(hir);
            assertNotNull(inner, "pattern must have extractable inner literal");

            Prefilter innerPrefilter = buildPrefilter(inner.literal());
            assertNotNull(innerPrefilter, "inner literal must produce a prefilter");

            NFA nfa = Compiler.compile(hir);
            boolean quitNonAscii = nfa.lookSetAny().containsUnicodeWord();
            CharClasses cc = CharClassBuilder.buildUnmerged(nfa, quitNonAscii);
            PikeVM pikeVM = new PikeVM(nfa);
            LazyDFA forwardDFA = cc != null ? LazyDFA.create(nfa, cc) : null;
            assertNotNull(forwardDFA, "pattern must support forward DFA");

            // Compile separate prefix-only reverse DFA
            NFA prefixRevNfa = Compiler.compileReverse(inner.prefixHir());
            CharClasses prefixRevCc = CharClassBuilder.buildUnmerged(prefixRevNfa, quitNonAscii);
            LazyDFA prefixReverseDFA = prefixRevCc != null
                    ? LazyDFA.create(prefixRevNfa, prefixRevCc) : null;
            assertNotNull(prefixReverseDFA, "prefix reverse DFA must be available");

            BoundedBacktracker bt = new BoundedBacktracker(nfa);
            Strategy.ReverseInner strategy = new Strategy.ReverseInner(
                    pikeVM, forwardDFA, prefixReverseDFA, innerPrefilter, bt);
            Strategy.Cache cache = strategy.createCache();
            return new Setup(strategy, cache);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build: " + pattern, e);
        }
    }

    private static Prefilter buildPrefilter(LiteralSeq seq) {
        return switch (seq) {
            case LiteralSeq.None ignored -> null;
            case LiteralSeq.Single single -> new SingleLiteral(single.literal());
            case LiteralSeq.Alternation alt ->
                    new MultiLiteral(alt.literals().toArray(char[][]::new));
        };
    }

    @Test
    void simpleInnerMatch() {
        // \w+Holmes\w+ with inner "Holmes"
        Setup s = build("[a-zA-Z]+Holmes[a-zA-Z]+");
        Input input = Input.of("xxxHolmesyyy");
        Captures caps = s.strategy.search(input, s.cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(12, caps.end(0));
    }

    @Test
    void innerNoMatch() {
        Setup s = build("[a-zA-Z]+Holmes[a-zA-Z]+");
        Input input = Input.of("no match here");
        Captures caps = s.strategy.search(input, s.cache);
        assertNull(caps);
    }

    @Test
    void innerIsMatch() {
        Setup s = build("[a-zA-Z]+Holmes[a-zA-Z]+");
        assertTrue(s.strategy.isMatch(Input.of("xxxHolmesyyy"), s.cache));
        assertFalse(s.strategy.isMatch(Input.of("no match"), s.cache));
    }

    @Test
    void innerCaptures() {
        Setup s = build("([a-zA-Z]+)Holmes([a-zA-Z]+)");
        Input input = Input.of("xxxHolmesyyy");
        Captures caps = s.strategy.searchCaptures(input, s.cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(12, caps.end(0));
    }

    @Test
    void innerAnchoredFallsBack() {
        Setup s = build("[a-zA-Z]+Holmes[a-zA-Z]+");
        Input input = Input.anchored("xxxHolmesyyy");
        Captures caps = s.strategy.search(input, s.cache);
        assertNotNull(caps);
    }
}
