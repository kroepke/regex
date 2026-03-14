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

class ReverseSuffixTest {

    private record Setup(Strategy.ReverseSuffix strategy, Strategy.Cache cache) {}

    private Setup build(String pattern) {
        try {
            Ast ast = Parser.parse(pattern, 100);
            Hir hir = Translator.translate(pattern, ast);

            LiteralSeq suffixes = LiteralExtractor.extractSuffixes(hir);
            Prefilter suffixPrefilter = buildPrefilter(suffixes);
            assertNotNull(suffixPrefilter, "pattern must have extractable suffix");

            NFA nfa = Compiler.compile(hir);
            boolean quitNonAscii = nfa.lookSetAny().containsUnicodeWord();
            CharClasses cc = CharClassBuilder.build(nfa, quitNonAscii);
            PikeVM pikeVM = new PikeVM(nfa);
            LazyDFA forwardDFA = cc != null ? LazyDFA.create(nfa, cc) : null;
            assertNotNull(forwardDFA, "pattern must support forward DFA");

            NFA reverseNfa = Compiler.compileReverse(hir);
            CharClasses revCc = CharClassBuilder.build(reverseNfa, quitNonAscii);
            LazyDFA reverseDFA = revCc != null ? LazyDFA.create(reverseNfa, revCc) : null;
            assertNotNull(reverseDFA, "pattern must support reverse DFA");

            BoundedBacktracker bt = new BoundedBacktracker(nfa);
            Strategy.ReverseSuffix strategy = new Strategy.ReverseSuffix(
                    pikeVM, forwardDFA, reverseDFA, suffixPrefilter, bt);
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
            case LiteralSeq.Alternation alt -> new MultiLiteral(
                    alt.literals().toArray(char[][]::new));
        };
    }

    @Test
    void simpleSuffixMatch() {
        Setup s = build("[a-zA-Z]+Holmes");
        Input input = Input.of("SherlockHolmes");
        Captures caps = s.strategy.search(input, s.cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(14, caps.end(0));
    }

    @Test
    void suffixNoMatch() {
        Setup s = build("[a-zA-Z]+Holmes");
        Input input = Input.of("no match here");
        Captures caps = s.strategy.search(input, s.cache);
        assertNull(caps);
    }

    @Test
    void suffixIsMatch() {
        Setup s = build("[a-zA-Z]+Holmes");
        assertTrue(s.strategy.isMatch(Input.of("SherlockHolmes"), s.cache));
        assertFalse(s.strategy.isMatch(Input.of("no match"), s.cache));
    }

    @Test
    void suffixCaptures() {
        Setup s = build("([a-zA-Z]+)Holmes");
        Input input = Input.of("SherlockHolmes");
        Captures caps = s.strategy.searchCaptures(input, s.cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(14, caps.end(0));
    }

    @Test
    void suffixMultipleMatches() {
        // Verify the strategy returns the first (leftmost) match
        Setup s = build("[a-zA-Z]+Holmes");
        Input input = Input.of("xHolmes yHolmes");
        Captures caps = s.strategy.search(input, s.cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(7, caps.end(0));
    }

    @Test
    void anchoredFallsBack() {
        Setup s = build("[a-zA-Z]+Holmes");
        Input input = Input.anchored("SherlockHolmes");
        // Anchored should still work (falls back to PikeVM)
        Captures caps = s.strategy.search(input, s.cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(14, caps.end(0));
    }
}
