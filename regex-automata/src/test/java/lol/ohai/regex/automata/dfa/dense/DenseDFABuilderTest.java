package lol.ohai.regex.automata.dfa.dense;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DenseDFABuilderTest {

    @Test
    void buildSimplePattern() {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa, "simple char class pattern should produce a DenseDFA");
        assertTrue(dfa.stateCount() >= 3, "should have at least padding + dead + quit + real states");
    }

    @Test
    void buildAlternation() {
        DenseDFA dfa = buildDense("cat|dog");
        assertNotNull(dfa, "alternation pattern should produce a DenseDFA");
        assertTrue(dfa.stateCount() >= 3);
    }

    @Test
    void buildReturnsNullForQuitCharPatterns() {
        // Patterns with quit chars (e.g., Unicode word boundaries) are rejected
        // because the dense DFA can't handle all inputs correctly.
        DenseDFA dfa = buildDenseWithQuit("\\bword\\b");
        assertNull(dfa, "pattern with quit chars should return null");
    }

    @Test
    void buildSucceedsForMultilineAnchors() {
        // (?m)^line$ uses START_LINE and END_LINE look-assertions
        // With multi-position start states, these patterns can now build dense DFAs
        DenseDFA dfa = buildDense("(?m)^line$");
        assertNotNull(dfa, "pattern with multiline anchors should now produce a DenseDFA");
    }

    @Test
    void matchStatesAreInRange() {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        int stride = dfa.stride();
        // minMatch must be above quit and below total state count * stride
        assertTrue(dfa.minMatch() > dfa.quit(),
                "minMatch (" + dfa.minMatch() + ") should be > quit (" + dfa.quit() + ")");
        assertTrue(dfa.maxMatch() >= dfa.minMatch(),
                "maxMatch (" + dfa.maxMatch() + ") should be >= minMatch (" + dfa.minMatch() + ")");
    }

    @Test
    void deadAndQuitAtFixedPositions() {
        DenseDFA dfa = buildDense("abc");
        assertNotNull(dfa);
        int stride = dfa.stride();
        assertEquals(0, dfa.dead(), "dead should be at 0");
        assertEquals(stride, dfa.quit(), "quit should be at stride");
    }

    @Test
    void allTransitionsArePopulated() {
        // For a fully-built DenseDFA, all transition targets must be valid
        // state IDs (multiples of stride within the table bounds).
        // Dead is now at 0, so transitions to 0 are valid (they mean "dead").
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        int stride = dfa.stride();
        int[] table = dfa.transTable();
        int classCount = dfa.charClasses().classCount();
        int maxSid = (dfa.stateCount() - 1) * stride;

        for (int stateIdx = 2; stateIdx < dfa.stateCount(); stateIdx++) {
            int sid = stateIdx * stride;
            for (int cls = 0; cls <= classCount; cls++) {
                int target = table[sid + cls];
                assertTrue(target >= 0 && target <= maxSid,
                        "state " + sid + " class " + cls + " has out-of-range target " + target);
                assertEquals(0, target % stride,
                        "state " + sid + " class " + cls + " target " + target + " not aligned to stride");
            }
        }
    }

    @Test
    void startStatesAreValid() {
        DenseDFA dfa = buildDense("abc");
        assertNotNull(dfa);
        int stride = dfa.stride();
        int[] starts = dfa.startStates();
        // Should have Start.COUNT * 2 start states
        assertEquals(lol.ohai.regex.automata.dfa.lazy.Start.COUNT * 2, starts.length);
        for (int i = 0; i < starts.length; i++) {
            assertTrue(starts[i] >= 0, "start state [" + i + "] should be >= 0");
            assertEquals(0, starts[i] % stride,
                    "start state [" + i + "] should be a multiple of stride");
        }
    }

    @Test
    void noMatchFlagOnTransitions() {
        // With range-based match detection, transitions are plain state IDs
        // with no MATCH_FLAG. Verify no transition has the high bit set.
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        int[] table = dfa.transTable();
        for (int i = 0; i < table.length; i++) {
            assertTrue((table[i] & 0x8000_0000) == 0,
                    "transition at " + i + " should not have MATCH_FLAG");
        }
    }

    @Test
    void literalPatternStructure() {
        // "abc" should produce: start -> a -> b -> c -> match
        // Plus dead, quit, padding = at least 7 states
        DenseDFA dfa = buildDense("abc");
        assertNotNull(dfa);
        // unanchored start adds states for the loop-back prefix
        assertTrue(dfa.stateCount() >= 5, "abc should have multiple states");
    }

    @Disabled("pending special-state taxonomy impl")
    @Test
    void deadStateIsZero() {
        DenseDFA dfa = buildDense("abc");
        assertNotNull(dfa);
        assertEquals(0, dfa.dead(), "dead should be at ID 0");
    }

    @Disabled("pending special-state taxonomy impl")
    @Test
    void quitStateIsStride() {
        DenseDFA dfa = buildDense("abc");
        assertNotNull(dfa);
        assertEquals(dfa.stride(), dfa.quit(), "quit should be at stride");
    }

    @Disabled("pending special-state taxonomy impl")
    @Test
    void matchStatesAreAboveQuit() {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        assertTrue(dfa.minMatch() > dfa.stride(),
                "minMatch must be > quit (stride)");
        assertTrue(dfa.maxMatch() >= dfa.minMatch(),
                "maxMatch must be >= minMatch");
    }

    @Disabled("pending special-state taxonomy impl")
    @Test
    void maxSpecialIsThreshold() {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        int maxSpecial = dfa.maxSpecial();
        assertTrue(maxSpecial >= dfa.stride(),
                "maxSpecial must be >= quit");
        assertTrue(maxSpecial < dfa.stateCount() * dfa.stride(),
                "maxSpecial must be < total state space");
    }

    @Disabled("pending special-state taxonomy impl")
    @Test
    void allTransitionsArePopulatedNewLayout() {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        int stride = dfa.stride();
        int[] table = dfa.transTable();
        int classCount = dfa.charClasses().classCount();

        // State 0 (dead) should self-loop to 0
        for (int cls = 0; cls <= classCount; cls++) {
            assertEquals(0, table[cls],
                    "dead state class " + cls + " should loop to dead (0)");
        }

        // State stride (quit) should self-loop to stride
        for (int cls = 0; cls <= classCount; cls++) {
            assertEquals(stride, table[stride + cls],
                    "quit state class " + cls + " should loop to quit");
        }
    }

    // -- Helpers --

    private static DenseDFA buildDense(String pattern) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            CharClasses cc = CharClassBuilder.build(nfa);
            return new DenseDFABuilder().build(nfa, cc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile: " + pattern, e);
        }
    }

    private static DenseDFA buildDenseWithQuit(String pattern) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            boolean quitNonAscii = nfa.lookSetAny().containsUnicodeWord();
            CharClasses cc = CharClassBuilder.buildUnmerged(nfa, quitNonAscii);
            return new DenseDFABuilder().build(nfa, cc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile: " + pattern, e);
        }
    }
}
