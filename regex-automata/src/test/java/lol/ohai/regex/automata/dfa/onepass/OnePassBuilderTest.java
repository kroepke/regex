package lol.ohai.regex.automata.dfa.onepass;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;

class OnePassBuilderTest {

    private static OnePassDFA buildOnePass(String pattern) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            CharClasses cc = CharClassBuilder.build(nfa);
            return OnePassBuilder.build(nfa, cc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile: " + pattern, e);
        }
    }

    // --- Eligible patterns (build should return non-null) ---

    @Test
    void simpleLiteral() {
        OnePassDFA dfa = buildOnePass("abc");
        assertNotNull(dfa, "simple literal 'abc' should be one-pass");
        assertNotNull(dfa.createCache());
    }

    @Test
    void captureGroupsDigits() {
        OnePassDFA dfa = buildOnePass("(\\d+)-(\\d+)");
        assertNotNull(dfa, "'(\\d+)-(\\d+)' should be one-pass");
    }

    @Test
    void datePattern() {
        OnePassDFA dfa = buildOnePass("(\\d{4})-(\\d{2})-(\\d{2})");
        assertNotNull(dfa, "date pattern should be one-pass");
    }

    @Test
    void nonOverlappingAlternation() {
        OnePassDFA dfa = buildOnePass("(a|b)c");
        assertNotNull(dfa, "'(a|b)c' should be one-pass");
    }

    @Test
    void charClassCaptures() {
        OnePassDFA dfa = buildOnePass("([a-z]+)@([a-z]+)");
        assertNotNull(dfa, "'([a-z]+)@([a-z]+)' should be one-pass");
    }

    // --- Ineligible patterns (build should return null) ---

    @Test
    void ambiguousSplit() {
        OnePassDFA dfa = buildOnePass("(a*)(a*)");
        assertNull(dfa, "'(a*)(a*)' is ambiguous and should NOT be one-pass");
    }

    @Test
    void overlappingAlternation() {
        OnePassDFA dfa = buildOnePass("(a|ab)c");
        assertNull(dfa, "'(a|ab)c' has overlapping alternation and should NOT be one-pass");
    }

    @Test
    void simpleDigitPlus() {
        OnePassDFA dfa = buildOnePass("\\d+");
        assertNotNull(dfa, "'\\d+' should be one-pass");
    }

    @Test
    void simpleLiteralWithCapture() {
        OnePassDFA dfa = buildOnePass("(a+)b");
        assertNotNull(dfa, "'(a+)b' should be one-pass");
    }

}
