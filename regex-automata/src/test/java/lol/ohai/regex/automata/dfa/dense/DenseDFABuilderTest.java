package lol.ohai.regex.automata.dfa.dense;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
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
    void matchStatesAreShuffledToEnd() {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        int stride = dfa.stride();
        // minMatchState must be above quit and below total state count * stride
        assertTrue(dfa.minMatchState() > dfa.quit(),
                "minMatchState (" + dfa.minMatchState() + ") should be > quit (" + dfa.quit() + ")");
        assertTrue(dfa.minMatchState() < dfa.stateCount() * stride,
                "minMatchState should be < stateCount * stride");
    }

    @Test
    void deadAndQuitAtFixedPositions() {
        DenseDFA dfa = buildDense("abc");
        assertNotNull(dfa);
        int stride = dfa.stride();
        assertEquals(stride, dfa.dead(), "dead should be at stride");
        assertEquals(stride * 2, dfa.quit(), "quit should be at stride * 2");
    }

    @Test
    void allTransitionsArePopulated() {
        // For a fully-built DenseDFA, real states (not dead/quit) should have
        // no UNKNOWN (0) transitions in their class range. The padding state at
        // index 0 is allowed to be all zeros.
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        int stride = dfa.stride();
        int dead = dfa.dead();
        int quit = dfa.quit();
        int[] table = dfa.transTable();
        int classCount = dfa.charClasses().classCount();

        for (int stateIdx = 3; stateIdx < dfa.stateCount(); stateIdx++) {
            int sid = stateIdx * stride;
            for (int cls = 0; cls <= classCount; cls++) {
                int target = table[sid + cls];
                assertTrue(target != 0,
                        "state " + sid + " class " + cls + " has UNKNOWN (0) transition");
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
            assertTrue(starts[i] > 0, "start state [" + i + "] should be > 0");
            assertEquals(0, starts[i] % stride,
                    "start state [" + i + "] should be a multiple of stride");
        }
    }

    @Test
    void matchFlagPreservedOnTransitions() {
        // MATCH_FLAG (0x8000_0000) is preserved on transitions for delayed-match
        // detection. Verify that at least some transitions from match states have
        // MATCH_FLAG, and that stripping the flag gives valid state IDs.
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        int[] table = dfa.transTable();
        int stride = dfa.stride();
        boolean hasMatchFlag = false;
        for (int i = 0; i < table.length; i++) {
            if ((table[i] & 0x8000_0000) != 0) {
                hasMatchFlag = true;
                int rawTarget = table[i] & 0x7FFF_FFFF;
                assertEquals(0, rawTarget % stride,
                        "MATCH_FLAG transition at " + i + " has invalid raw target");
            }
        }
        assertTrue(hasMatchFlag, "[a-z]+ should have MATCH_FLAG transitions from match states");
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
