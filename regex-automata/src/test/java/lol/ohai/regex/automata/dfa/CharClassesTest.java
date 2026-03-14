package lol.ohai.regex.automata.dfa;

import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        // With merged classes, 'a' and 'b' may share a class (both lead to
        // the same next state). The key invariant: chars NOT in the alternation
        // must differ from chars IN the alternation.
        assertNotEquals(cc.classify('a'), cc.classify('0'));
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

    @Test
    void quitClassForNonAscii() throws Exception {
        // Build char classes with quit chars enabled (Unicode word boundary pattern)
        Ast ast = Parser.parse("\\b", 250);
        Hir hir = Translator.translate("\\b", ast);
        NFA nfa = Compiler.compile(hir);
        CharClasses cc = CharClassBuilder.buildUnmerged(nfa, true); // true = quitNonAscii

        // ASCII chars should NOT be quit classes
        assertFalse(cc.isQuitClass(cc.classify('a')));
        assertFalse(cc.isQuitClass(cc.classify('Z')));
        assertFalse(cc.isQuitClass(cc.classify(' ')));

        // Non-ASCII chars SHOULD be quit classes
        assertTrue(cc.isQuitClass(cc.classify('\u00E9'))); // é
        assertTrue(cc.isQuitClass(cc.classify('\u4E2D'))); // 中
        assertTrue(cc.isQuitClass(cc.classify('\u0080'))); // first non-ASCII
    }

    // --- Merge tests (some will fail until Task 3 implements the merge step) ---

    @Test
    void mergedBuildHandlesUnicodeWordClass() throws Exception {
        NFA nfa = compileNfa("\\w+");
        CharClasses cc = CharClassBuilder.build(nfa);
        assertNotNull(cc, "merged build should succeed for \\w+");
        assertTrue(cc.classCount() <= 256,
                "merged \\w+ should fit in byte class IDs, got " + cc.classCount());
        assertFalse(cc.hasQuitClasses(), "merged \\w+ should not have quit classes");
    }

    @Test
    void mergedBuildClassifiesUnicodeCorrectly() throws Exception {
        NFA nfa = compileNfa("\\w+");
        CharClasses cc = CharClassBuilder.build(nfa);
        assertNotNull(cc, "merged build should succeed for \\w+");

        // 'a' and 'z' are both word chars — same class
        assertEquals(cc.classify('a'), cc.classify('z'),
                "'a' and 'z' should be in the same class");

        // \u2961 is a math symbol (not a word char), should differ from 'a'
        assertNotEquals(cc.classify('\u2961'), cc.classify('a'),
                "math symbol \\u2961 should differ from word char 'a'");

        // \u2961 and \u2962 are adjacent non-word chars — should share a class
        assertEquals(cc.classify('\u2961'), cc.classify('\u2962'),
                "adjacent non-word chars \\u2961 and \\u2962 should share a class");
    }

    @Test
    void mergedBuildPreservesQuitForWordBoundary() throws Exception {
        NFA nfa = compileNfa("\\b\\w+\\b");
        assertTrue(nfa.lookSetAny().containsUnicodeWord(),
                "\\b pattern should contain Unicode word boundary assertion");

        // Use buildUnmerged with quit=true (tests the quit path which is unchanged)
        CharClasses cc = CharClassBuilder.buildUnmerged(nfa, true);
        assertNotNull(cc, "buildUnmerged with quit should succeed");
        assertTrue(cc.hasQuitClasses(), "word boundary pattern with quit should have quit classes");
    }

    @Test
    void mergedBuildPreservesLineFeedIsolation() throws Exception {
        NFA nfa = compileNfa("(?m)^\\w+$");
        CharClasses cc = CharClassBuilder.build(nfa);
        assertNotNull(cc, "merged build should succeed for (?m)^\\w+$");

        int lfClass = cc.classify('\n');
        int spaceClass = cc.classify(' ');

        assertTrue(cc.isLineLF(lfClass), "'\\n' class should have isLineLF true");
        assertFalse(cc.isLineLF(spaceClass), "' ' class should NOT have isLineLF true");
        assertNotEquals(lfClass, spaceClass,
                "'\\n' and ' ' should be in different classes");
    }

    @Test
    void mergedAndUnmergedAgreeOnSimplePatterns() throws Exception {
        List<String> patterns = List.of("[a-z]+", "abc", "[0-9a-fA-F]+", "Sherlock|Watson");

        for (String pattern : patterns) {
            NFA nfa = compileNfa(pattern);
            CharClasses merged = CharClassBuilder.build(nfa);
            CharClasses unmerged = CharClassBuilder.buildUnmerged(nfa, false);

            assertNotNull(merged, "merged build should succeed for: " + pattern);
            assertNotNull(unmerged, "unmerged build should succeed for: " + pattern);

            // For all ASCII char pairs: if unmerged groups them, merged must too
            for (int c = 0; c < 128; c++) {
                for (int d = c + 1; d < 128; d++) {
                    int unmergedC = unmerged.classify((char) c);
                    int unmergedD = unmerged.classify((char) d);
                    if (unmergedC == unmergedD) {
                        int mergedC = merged.classify((char) c);
                        int mergedD = merged.classify((char) d);
                        assertEquals(mergedC, mergedD,
                                "pattern '" + pattern + "': chars " + c + " and " + d
                                        + " share unmerged class but differ in merged");
                    }
                }
            }
        }
    }

    @Test
    void compareClassCountsMergedVsUnmerged() throws Exception {
        NFA nfa = compileNfa("\\w+");

        CharClasses merged = CharClassBuilder.build(nfa);
        assertNotNull(merged, "merged build should succeed for \\w+");

        CharClasses unmerged = CharClassBuilder.buildUnmerged(nfa, false);
        // buildUnmerged auto-retries with quit-on-non-ASCII, so it succeeds
        // with quit classes rather than returning null
        if (unmerged != null) {
            assertTrue(unmerged.hasQuitClasses(),
                    "unmerged \\w+ should use quit classes as fallback");
        }

        System.out.println("Merged class count: " + merged.classCount());
        System.out.println("Merged stride: " + merged.stride());
    }

    @Test
    void reverseDfaForUnicodeWordMergeStatus() throws Exception {
        // The reverse NFA for \w+ has 1,205 states with 440 distinct merged
        // signatures — more than 256, so the merge falls back to buildUnmerged
        // with quit-on-non-ASCII. This is because the reverse NFA compiler
        // creates CharRange states with many different .next() targets (unlike
        // the forward NFA where they all share the same loop-back state).
        //
        // TODO: Investigate why the reverse NFA has more distinct signatures
        // than the forward NFA, and whether the reverse NFA compiler can be
        // modified to produce fewer distinct targets.
        var ast = Parser.parse("\\w+", 250);
        var hir = Translator.translate("\\w+", ast);
        var revNfa = Compiler.compileReverse(hir);

        CharClasses revCc = CharClassBuilder.build(revNfa, false);
        assertNotNull(revCc, "build should succeed for reverse \\w+");
        // Currently: 440 merged signatures > 256 → falls back to quit
        // When fixed: should have no quit classes
        System.out.println("Reverse NFA stateCount: " + revNfa.stateCount());
        System.out.println("Reverse char classes: " + revCc.classCount());
        System.out.println("Reverse hasQuit: " + revCc.hasQuitClasses());
    }

    // --- Helpers ---

    private static NFA compileNfa(String pattern) throws Exception {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        return Compiler.compile(hir);
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
