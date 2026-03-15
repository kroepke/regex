package lol.ohai.regex.automata.dfa.onepass;

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

import static org.junit.jupiter.api.Assertions.*;

class OnePassDFATest {

    @Test
    void simpleLiteralCapture() {
        int[] slots = searchCaptures("(abc)", "abc", 0, 3);
        assertNotNull(slots);
        assertEquals(0, slots[0]);  // group 0 start
        assertEquals(3, slots[1]);  // group 0 end
        assertEquals(0, slots[2]);  // group 1 start
        assertEquals(3, slots[3]);  // group 1 end
    }

    @Test
    void dateCapture() {
        int[] slots = searchCaptures("(\\d{4})-(\\d{2})-(\\d{2})", "2026-03-15", 0, 10);
        assertNotNull(slots);
        assertEquals(0, slots[0]);   // group 0 start
        assertEquals(10, slots[1]);  // group 0 end
        assertEquals(0, slots[2]);   // group 1 start (year)
        assertEquals(4, slots[3]);   // group 1 end
        assertEquals(5, slots[4]);   // group 2 start (month)
        assertEquals(7, slots[5]);   // group 2 end
        assertEquals(8, slots[6]);   // group 3 start (day)
        assertEquals(10, slots[7]);  // group 3 end
    }

    @Test
    void noMatch() {
        int[] slots = searchCaptures("(abc)", "xyz", 0, 3);
        assertNull(slots);
    }

    @Test
    void alternationCapture() {
        int[] slots = searchCaptures("(a|b)c", "bc", 0, 2);
        assertNotNull(slots);
        assertEquals(0, slots[0]);  // group 0 start
        assertEquals(2, slots[1]);  // group 0 end
        assertEquals(0, slots[2]);  // group 1 start
        assertEquals(1, slots[3]);  // group 1 end
    }

    @Test
    void nonParticipatingGroup() {
        // (a)|(b) on "b" — group 1 doesn't participate
        int[] slots = searchCaptures("(a)|(b)", "b", 0, 1);
        assertNotNull(slots);
        assertEquals(0, slots[0]);   // group 0 start
        assertEquals(1, slots[1]);   // group 0 end
        assertEquals(-1, slots[2]);  // group 1 — not matched
        assertEquals(-1, slots[3]);
        assertEquals(0, slots[4]);   // group 2 start
        assertEquals(1, slots[5]);   // group 2 end
    }

    @Test
    void repeatCapture() {
        // (a+)b on "aaab"
        int[] slots = searchCaptures("(a+)b", "aaab", 0, 4);
        assertNotNull(slots);
        assertEquals(0, slots[0]);  // group 0 start
        assertEquals(4, slots[1]);  // group 0 end
        assertEquals(0, slots[2]);  // group 1 start
        assertEquals(3, slots[3]);  // group 1 end (captures "aaa")
    }

    @Test
    void noCaptureGroups() {
        // Pattern with no explicit groups — just group 0
        int[] slots = searchCaptures("abc", "abc", 0, 3);
        assertNotNull(slots);
        assertEquals(0, slots[0]);  // group 0 start
        assertEquals(3, slots[1]);  // group 0 end
    }

    @Test
    void digitPlusCapture() {
        int[] slots = searchCaptures("(\\d+)-(\\d+)", "123-456", 0, 7);
        assertNotNull(slots);
        assertEquals(0, slots[0]);  // group 0 start
        assertEquals(7, slots[1]);  // group 0 end
        assertEquals(0, slots[2]);  // group 1 start
        assertEquals(3, slots[3]);  // group 1 end
        assertEquals(4, slots[4]);  // group 2 start
        assertEquals(7, slots[5]);  // group 2 end
    }

    @Test
    void charClassCapture() {
        int[] slots = searchCaptures("([a-z]+)@([a-z]+)", "foo@bar", 0, 7);
        assertNotNull(slots);
        assertEquals(0, slots[0]);  // group 0 start
        assertEquals(7, slots[1]);  // group 0 end
        assertEquals(0, slots[2]);  // group 1 start
        assertEquals(3, slots[3]);  // group 1 end
        assertEquals(4, slots[4]);  // group 2 start
        assertEquals(7, slots[5]);  // group 2 end
    }

    // -- Helper --

    private int[] searchCaptures(String pattern, String haystack, int start, int end) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            CharClasses cc = CharClassBuilder.build(nfa);
            OnePassDFA dfa = OnePassBuilder.build(nfa, cc);
            if (dfa == null) {
                fail("Pattern should be one-pass: " + pattern);
            }

            OnePassCache cache = dfa.createCache();
            int[] slots = new int[nfa.captureSlotCount()];
            Input input = Input.of(haystack).withBounds(start, end, true);
            int pid = dfa.search(input, cache, slots);
            if (pid < 0) return null;
            return slots;
        } catch (Exception e) {
            throw new RuntimeException("Failed: " + pattern, e);
        }
    }
}
