package lol.ohai.regex.automata.nfa.thompson;

import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompilerTest {

    @Test
    void compileLiteral() {
        NFA nfa = compile("a");
        assertTrue(nfa.stateCount() > 0);
        // Should have CharRange for 'a', Capture states, Match
        assertTrue(hasStateOfType(nfa, State.CharRange.class));
        assertTrue(hasStateOfType(nfa, State.Capture.class));
        assertTrue(hasStateOfType(nfa, State.Match.class));
    }

    @Test
    void compileMultiByteLiteral() {
        NFA nfa = compile("abc");
        assertTrue(nfa.stateCount() > 3);
        // Should have 3 CharRange states for 'a', 'b', 'c'
        int charRangeCount = countStatesOfType(nfa, State.CharRange.class);
        // 3 for the literal + 1 for the unanchored skip loop
        assertTrue(charRangeCount >= 3, "Expected at least 3 CharRange states, got " + charRangeCount);
    }

    @Test
    void compileConcat() {
        NFA nfa = compile("ab");
        assertTrue(nfa.stateCount() > 2);
    }

    @Test
    void compileAlternation() {
        NFA nfa = compile("a|b");
        boolean hasUnion = hasStateOfType(nfa, State.Union.class)
                || hasStateOfType(nfa, State.BinaryUnion.class);
        assertTrue(hasUnion, "Expected Union or BinaryUnion state for alternation");
    }

    @Test
    void compileStar() {
        NFA nfa = compile("a*");
        assertTrue(nfa.stateCount() > 2);
        assertTrue(hasStateOfType(nfa, State.BinaryUnion.class),
                "Star should produce a BinaryUnion for the loop");
    }

    @Test
    void compilePlus() {
        NFA nfa = compile("a+");
        assertTrue(nfa.stateCount() > 2);
        assertTrue(hasStateOfType(nfa, State.BinaryUnion.class),
                "Plus should produce a BinaryUnion for the loop");
    }

    @Test
    void compileQuestion() {
        NFA nfa = compile("a?");
        assertTrue(nfa.stateCount() > 2);
        assertTrue(hasStateOfType(nfa, State.BinaryUnion.class),
                "Question should produce a BinaryUnion");
    }

    @Test
    void compileCapture() {
        NFA nfa = compile("(a)");
        assertEquals(2, nfa.groupCount()); // group 0 (implicit) + group 1
        assertEquals(4, nfa.captureSlotCount()); // 2 groups * 2 slots each
    }

    @Test
    void compileNestedCaptures() {
        NFA nfa = compile("(a(b))");
        assertEquals(3, nfa.groupCount()); // group 0 + group 1 + group 2
        assertEquals(6, nfa.captureSlotCount());
    }

    @Test
    void compileBounded() {
        NFA nfa = compile("a{2,4}");
        assertTrue(nfa.stateCount() > 4);
        // Should have at least 2 CharRange states for 'a' (required copies)
        // plus optional copies
    }

    @Test
    void compileBoundedExact() {
        NFA nfa = compile("a{3}");
        // Should unroll 3 copies
        int charRangeCount = countStatesOfType(nfa, State.CharRange.class);
        // 3 for the literal copies + 1 for unanchored skip
        assertTrue(charRangeCount >= 3, "Expected at least 3 CharRange states, got " + charRangeCount);
    }

    @Test
    void compileBoundedUnbounded() {
        NFA nfa = compile("a{2,}");
        assertTrue(nfa.stateCount() > 3);
        // Should have required copies + a plus-like loop
    }

    @Test
    void compileCharClass() {
        NFA nfa = compile("[a-z]");
        assertTrue(nfa.stateCount() > 0);
        // Should have a CharRange state covering 'a' to 'z'
        boolean hasRange = false;
        for (int i = 0; i < nfa.stateCount(); i++) {
            if (nfa.state(i) instanceof State.CharRange cr
                    && cr.start() == 'a' && cr.end() == 'z') {
                hasRange = true;
                break;
            }
        }
        assertTrue(hasRange, "Expected a CharRange [a-z]");
    }

    @Test
    void compileMultiByteCharClass() {
        // Euro sign U+20AC — now a single BMP char, single CharRange
        NFA nfa = compile("[\\u20AC]");
        boolean found = false;
        for (int i = 0; i < nfa.stateCount(); i++) {
            if (nfa.state(i) instanceof State.CharRange cr
                    && cr.start() == 0x20AC && cr.end() == 0x20AC) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Expected CharRange for U+20AC");
    }

    @Test
    void compileDot() {
        // Dot in default mode matches any codepoint except newline.
        // This gets translated to a character class.
        NFA nfa = compile(".");
        assertTrue(nfa.stateCount() > 0);
    }

    @Test
    void compileEmpty() {
        NFA nfa = compile("");
        assertTrue(nfa.stateCount() > 0);
        assertTrue(hasStateOfType(nfa, State.Match.class));
    }

    @Test
    void compileLook() {
        NFA nfa = compile("^a$");
        assertTrue(hasStateOfType(nfa, State.Look.class));
    }

    @Test
    void startStatesAreSet() {
        NFA nfa = compile("a");
        assertTrue(nfa.startAnchored() >= 0, "Anchored start should be set");
        assertTrue(nfa.startUnanchored() >= 0, "Unanchored start should be set");
        assertNotEquals(nfa.startAnchored(), nfa.startUnanchored(),
                "Anchored and unanchored starts should differ");
    }

    @Test
    void compileComplexPattern() {
        // A more complex pattern exercising multiple features
        NFA nfa = compile("(foo|bar)+baz?");
        assertTrue(nfa.stateCount() > 10);
        assertEquals(2, nfa.groupCount()); // group 0 + group 1
    }

    @Test
    void compileLazyQuantifiers() {
        NFA nfa = compile("a*?b+?c??");
        assertTrue(nfa.stateCount() > 5);
    }

    @Test
    void compileUnicodeRange() {
        // Cyrillic block: U+0400-U+04FF — now a single BMP CharRange
        NFA nfa = compile("[\\u0400-\\u04FF]");
        boolean found = false;
        for (int i = 0; i < nfa.stateCount(); i++) {
            if (nfa.state(i) instanceof State.CharRange cr
                    && cr.start() == 0x0400 && cr.end() == 0x04FF) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Expected CharRange [U+0400, U+04FF]");
    }

    @Test
    void compileSingleAsciiCharClass() {
        // Single char in a class: [x]
        NFA nfa = compile("[x]");
        boolean found = false;
        for (int i = 0; i < nfa.stateCount(); i++) {
            if (nfa.state(i) instanceof State.CharRange cr
                    && cr.start() == 'x' && cr.end() == 'x') {
                found = true;
                break;
            }
        }
        assertTrue(found, "Expected CharRange for 'x'");
    }

    // --- Helpers ---

    private static NFA compile(String pattern) {
        try {
            Ast ast = Parser.parse(pattern);
            Hir hir = Translator.translate(pattern, ast);
            return Compiler.compile(hir);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile pattern: " + pattern, e);
        }
    }

    private static boolean hasStateOfType(NFA nfa, Class<? extends State> type) {
        for (int i = 0; i < nfa.stateCount(); i++) {
            if (type.isInstance(nfa.state(i))) {
                return true;
            }
        }
        return false;
    }

    private static int countStatesOfType(NFA nfa, Class<? extends State> type) {
        int count = 0;
        for (int i = 0; i < nfa.stateCount(); i++) {
            if (type.isInstance(nfa.state(i))) {
                count++;
            }
        }
        return count;
    }
}
