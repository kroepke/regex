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

    @Test
    void prefixLiteralsAreExact() {
        Hir hir = new Hir.Concat(List.of(
                new Hir.Literal("hello".toCharArray()),
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('0', '9')))))
        ));
        LiteralSeq result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.Single.class, result);
        LiteralSeq.Single single = (LiteralSeq.Single) result;
        assertTrue(single.exact());
        assertFalse(single.coversEntirePattern());
    }

    @Test
    void entirePatternLiteralsAreExact() {
        Hir hir = new Hir.Literal("hello".toCharArray());
        LiteralSeq result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.Single.class, result);
        LiteralSeq.Single single = (LiteralSeq.Single) result;
        assertTrue(single.exact());
        assertTrue(single.coversEntirePattern());
    }

    // === Suffix extraction tests ===

    @Test
    void suffixFromPureLiteral() {
        var hir = new Hir.Literal("hello".toCharArray());
        var result = LiteralExtractor.extractSuffixes(hir);
        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("hello".toCharArray(), single.literal());
        assertTrue(single.exact());
        assertTrue(single.coversEntirePattern());
    }

    @Test
    void suffixFromConcatTrailingLiteral() {
        // \w+Holmes
        var hir = new Hir.Concat(List.of(
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(
                                new ClassUnicode.ClassUnicodeRange('a', 'z'))))),
                new Hir.Literal("Holmes".toCharArray())
        ));
        var result = LiteralExtractor.extractSuffixes(hir);
        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("Holmes".toCharArray(), single.literal());
        assertTrue(single.exact());
        assertFalse(single.coversEntirePattern());
    }

    @Test
    void suffixFromConcatMultipleTrailingLiterals() {
        // \w+ "foo" "bar"
        var hir = new Hir.Concat(List.of(
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(
                                new ClassUnicode.ClassUnicodeRange('a', 'z'))))),
                new Hir.Literal("foo".toCharArray()),
                new Hir.Literal("bar".toCharArray())
        ));
        var result = LiteralExtractor.extractSuffixes(hir);
        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("foobar".toCharArray(), single.literal());
        assertTrue(single.exact());
        assertFalse(single.coversEntirePattern());
    }

    @Test
    void suffixFromConcatNoTrailingLiteral() {
        // "hello"\w+
        var hir = new Hir.Concat(List.of(
                new Hir.Literal("hello".toCharArray()),
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(
                                new ClassUnicode.ClassUnicodeRange('a', 'z')))))
        ));
        var result = LiteralExtractor.extractSuffixes(hir);
        assertInstanceOf(LiteralSeq.None.class, result);
    }

    @Test
    void suffixFromAlternation() {
        // \w+"foo" | \w+"bar"
        var wordClass = new Hir.Class(new ClassUnicode(List.of(
                new ClassUnicode.ClassUnicodeRange('a', 'z'))));
        var hir = new Hir.Alternation(List.of(
                new Hir.Concat(List.of(
                        new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true, wordClass),
                        new Hir.Literal("foo".toCharArray()))),
                new Hir.Concat(List.of(
                        new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true, wordClass),
                        new Hir.Literal("bar".toCharArray())))
        ));
        var result = LiteralExtractor.extractSuffixes(hir);
        assertInstanceOf(LiteralSeq.Alternation.class, result);
        var alt = (LiteralSeq.Alternation) result;
        assertEquals(2, alt.literals().size());
        assertArrayEquals("foo".toCharArray(), alt.literals().get(0));
        assertArrayEquals("bar".toCharArray(), alt.literals().get(1));
        assertTrue(alt.exact());
        assertFalse(alt.coversEntirePattern());
    }

    @Test
    void suffixThroughCapture() {
        // \w+(Holmes)
        var hir = new Hir.Concat(List.of(
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(
                                new ClassUnicode.ClassUnicodeRange('a', 'z'))))),
                new Hir.Capture(1, null, new Hir.Literal("Holmes".toCharArray()))
        ));
        var result = LiteralExtractor.extractSuffixes(hir);
        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("Holmes".toCharArray(), single.literal());
    }

    @Test
    void suffixFromRepetition() {
        var hir = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                new Hir.Literal("a".toCharArray()));
        var result = LiteralExtractor.extractSuffixes(hir);
        assertInstanceOf(LiteralSeq.None.class, result);
    }

    // === Inner literal extraction tests ===

    @Test
    void innerFromConcatWithMiddleLiteral() {
        // \w+Holmes\w+ → inner "Holmes", inexact, prefixHir = \w+
        Hir wordRep = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))));
        Hir hir = new Hir.Concat(List.of(
                wordRep,
                new Hir.Literal("Holmes".toCharArray()),
                wordRep
        ));
        LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
        assertNotNull(result);
        assertInstanceOf(LiteralSeq.Single.class, result.literal());
        LiteralSeq.Single single = (LiteralSeq.Single) result.literal();
        assertArrayEquals("Holmes".toCharArray(), single.literal());
        assertFalse(single.exact(), "inner literals must be inexact");
        assertNotNull(result.prefixHir());
    }

    @Test
    void innerSelectsLongest() {
        // \w+ab\w+Holmes\w+ → inner "Holmes" (longer than "ab")
        Hir wordRep = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))));
        Hir hir = new Hir.Concat(List.of(
                wordRep,
                new Hir.Literal("ab".toCharArray()),
                wordRep,
                new Hir.Literal("Holmes".toCharArray()),
                wordRep
        ));
        LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
        assertNotNull(result);
        LiteralSeq.Single single = (LiteralSeq.Single) result.literal();
        assertArrayEquals("Holmes".toCharArray(), single.literal());
    }

    @Test
    void innerReturnsNullForPureLiteral() {
        // "hello" → no inner (position 0 is prefix territory, nothing else)
        Hir hir = new Hir.Literal("hello".toCharArray());
        LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
        assertNull(result);
    }

    @Test
    void innerReturnsNullForNonConcat() {
        // \w+ → no inner (not a concat)
        Hir hir = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))));
        LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
        assertNull(result);
    }

    @Test
    void innerSkipsPosition0() {
        // helloWorld\w+ → no inner (position 0 is "helloWorld", nothing at inner positions)
        Hir hir = new Hir.Concat(List.of(
                new Hir.Literal("helloWorld".toCharArray()),
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))))
        ));
        LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
        assertNull(result);
    }

    @Test
    void innerThroughCapture() {
        // \w+(Holmes)\w+ → inner "Holmes", inexact
        Hir wordRep = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))));
        Hir hir = new Hir.Concat(List.of(
                wordRep,
                new Hir.Capture(1, null,
                        new Hir.Literal("Holmes".toCharArray())),
                wordRep
        ));
        LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
        assertNotNull(result);
        LiteralSeq.Single single = (LiteralSeq.Single) result.literal();
        assertArrayEquals("Holmes".toCharArray(), single.literal());
        assertFalse(single.exact());
    }

    @Test
    void innerDoesNotPickAdjacentLeadingLiterals() {
        // Concat([Literal("hel"), Literal("lo"), Repetition, Literal("World")])
        // Should return "World" (not "lo" which is adjacent to the leading literal)
        Hir hir = new Hir.Concat(List.of(
                new Hir.Literal("hel".toCharArray()),
                new Hir.Literal("lo".toCharArray()),
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z'))))),
                new Hir.Literal("World".toCharArray())
        ));
        LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
        assertNotNull(result);
        LiteralSeq.Single single = (LiteralSeq.Single) result.literal();
        // "World" (5 chars) is longer than "lo" (2 chars), so it wins
        assertArrayEquals("World".toCharArray(), single.literal());
    }

    @Test
    void innerPrefixHirIsCorrect() {
        // \w+Holmes\w+ → prefixHir should be the \w+ repetition wrapped in a Concat
        Hir wordRep = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))));
        Hir hir = new Hir.Concat(List.of(
                wordRep,
                new Hir.Literal("Holmes".toCharArray()),
                wordRep
        ));
        LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
        assertNotNull(result);
        // prefixHir is the sub-expression(s) before the inner literal
        assertInstanceOf(Hir.class, result.prefixHir());
    }
}
