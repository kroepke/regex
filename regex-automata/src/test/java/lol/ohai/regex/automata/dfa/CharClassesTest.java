package lol.ohai.regex.automata.dfa;

import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CharClassesTest {

    @Test
    void singleClassForAllChars() {
        var cc = CharClasses.identity();
        assertEquals(1, cc.classCount());
        assertEquals(0, cc.classify('a'));
        assertEquals(0, cc.classify('z'));
        assertEquals(0, cc.classify('\u4e00'));
    }

    @Test
    void strideIsPowerOfTwo() {
        var cc = CharClasses.identity();
        assertTrue(cc.stride() >= cc.classCount() + 1);
        assertEquals(0, cc.stride() & (cc.stride() - 1), "stride must be power of 2");
    }

    @Test
    void eoiClassIsDistinct() {
        var cc = CharClasses.identity();
        assertEquals(cc.classCount(), cc.eoiClass());
    }

    @Test
    void builderWithLiteralPattern() {
        var cc = buildCharClasses("abc");
        assertTrue(cc.classCount() >= 2, "literal should create multiple classes");
        assertEquals(cc.classify('x'), cc.classify('y'));
        assertEquals(cc.classify('x'), cc.classify('\u4e00'));
    }

    @Test
    void builderWithCharClassPattern() {
        var cc = buildCharClasses("[a-z]");
        int classA = cc.classify('a');
        assertEquals(classA, cc.classify('m'));
        assertEquals(classA, cc.classify('z'));
        assertNotEquals(classA, cc.classify('A'));
        assertNotEquals(classA, cc.classify('0'));
    }

    @Test
    void builderWithAlternation() {
        var cc = buildCharClasses("a|b");
        assertNotEquals(cc.classify('a'), cc.classify('b'));
        assertEquals(cc.classify('c'), cc.classify('z'));
    }

    @Test
    void builderWithUnicodeRange() {
        var cc = buildCharClasses("[\u4e00-\u9fff]");
        int classCjk = cc.classify('\u4e00');
        assertEquals(classCjk, cc.classify('\u5000'));
        assertEquals(classCjk, cc.classify('\u9fff'));
        assertNotEquals(classCjk, cc.classify('a'));
    }

    @Test
    void builderProducesValidStride() {
        var cc = buildCharClasses("[a-z]+");
        assertTrue(cc.stride() >= cc.classCount() + 1);
        assertEquals(0, cc.stride() & (cc.stride() - 1));
    }

    @Test
    void builderClassifyIsConsistent() {
        var cc = buildCharClasses("[0-9a-fA-F]");
        for (int c = 0; c < 256; c++) {
            int cls = cc.classify((char) c);
            assertTrue(cls >= 0 && cls < cc.classCount(),
                    "char " + c + " mapped to invalid class " + cls);
        }
    }

    private static CharClasses buildCharClasses(String pattern) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            return CharClassBuilder.build(nfa);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile: " + pattern, e);
        }
    }
}
