package lol.ohai.regex.syntax.hir;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LiteralExtractorTest {

    @Test
    void extractsLiteralAsEntirePattern() {
        var hir = new Hir.Literal("hello".toCharArray());
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("hello".toCharArray(), single.literal());
        assertTrue(single.coversEntirePattern());
    }

    @Test
    void extractsSingleCharLiteral() {
        var hir = new Hir.Literal("x".toCharArray());
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.Single.class, result);
        assertTrue(result.coversEntirePattern());
    }

    @Test
    void extractsPrefixFromConcat() {
        var hir = new Hir.Concat(List.of(
                new Hir.Literal("hello".toCharArray()),
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(
                                new ClassUnicode.ClassUnicodeRange('0', '9')))))
        ));
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("hello".toCharArray(), single.literal());
        assertFalse(single.coversEntirePattern());
    }

    @Test
    void mergesAdjacentLiteralsInConcat() {
        var hir = new Hir.Concat(List.of(
                new Hir.Literal("ab".toCharArray()),
                new Hir.Literal("c".toCharArray())
        ));
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("abc".toCharArray(), single.literal());
        assertTrue(single.coversEntirePattern());
    }

    @Test
    void mergesLeadingLiteralsInConcatWithTrailingNonLiteral() {
        var hir = new Hir.Concat(List.of(
                new Hir.Literal("ab".toCharArray()),
                new Hir.Literal("c".toCharArray()),
                new Hir.Class(new ClassUnicode(List.of(
                        new ClassUnicode.ClassUnicodeRange('0', '9'))))
        ));
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("abc".toCharArray(), single.literal());
        assertFalse(single.coversEntirePattern());
    }

    @Test
    void concatStartingWithNonLiteralReturnsNone() {
        var hir = new Hir.Concat(List.of(
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(
                                new ClassUnicode.ClassUnicodeRange('0', '9'))))),
                new Hir.Literal("hello".toCharArray())
        ));
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.None.class, result);
    }

    @Test
    void extractsThroughCapture() {
        var hir = new Hir.Capture(1, "word", new Hir.Literal("hello".toCharArray()));
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("hello".toCharArray(), single.literal());
        assertTrue(single.coversEntirePattern());
    }

    @Test
    void extractsAlternationOfLiterals() {
        var hir = new Hir.Alternation(List.of(
                new Hir.Literal("cat".toCharArray()),
                new Hir.Literal("dog".toCharArray()),
                new Hir.Literal("fish".toCharArray())
        ));
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.Alternation.class, result);
        var alt = (LiteralSeq.Alternation) result;
        assertEquals(3, alt.literals().size());
        assertTrue(alt.coversEntirePattern());
    }

    @Test
    void extractsPrefixesFromAlternationOfConcats() {
        var digit = new Hir.Class(new ClassUnicode(List.of(
                new ClassUnicode.ClassUnicodeRange('0', '9'))));
        var hir = new Hir.Alternation(List.of(
                new Hir.Concat(List.of(
                        new Hir.Literal("hello".toCharArray()), digit)),
                new Hir.Concat(List.of(
                        new Hir.Literal("world".toCharArray()), digit))
        ));
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.Alternation.class, result);
        var alt = (LiteralSeq.Alternation) result;
        assertEquals(2, alt.literals().size());
        assertFalse(alt.coversEntirePattern());
    }

    @Test
    void alternationWithNonLiteralBranchReturnsNone() {
        var hir = new Hir.Alternation(List.of(
                new Hir.Literal("hello".toCharArray()),
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(
                                new ClassUnicode.ClassUnicodeRange('0', '9')))))
        ));
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.None.class, result);
    }

    @Test
    void emptyReturnsNone() {
        var result = LiteralExtractor.extractPrefixes(new Hir.Empty());
        assertInstanceOf(LiteralSeq.None.class, result);
    }

    @Test
    void classReturnsNone() {
        var hir = new Hir.Class(new ClassUnicode(List.of(
                new ClassUnicode.ClassUnicodeRange('a', 'z'))));
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.None.class, result);
    }

    @Test
    void lookReturnsNone() {
        var hir = new Hir.Look(LookKind.START_TEXT);
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.None.class, result);
    }

    @Test
    void repetitionReturnsNone() {
        var hir = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                new Hir.Literal("a".toCharArray()));
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.None.class, result);
    }
}
