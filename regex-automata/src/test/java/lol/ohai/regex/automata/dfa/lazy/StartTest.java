package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.syntax.hir.LookKind;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StartTest {

    @Test void position0IsText() {
        assertEquals(Start.TEXT, Start.from("hello".toCharArray(), 0));
    }

    @Test void afterNewlineIsLineLF() {
        assertEquals(Start.LINE_LF, Start.from("a\nb".toCharArray(), 2));
    }

    @Test void afterCarriageReturnIsLineCR() {
        assertEquals(Start.LINE_CR, Start.from("a\rb".toCharArray(), 2));
    }

    @Test void afterWordCharIsWordByte() {
        assertEquals(Start.WORD_BYTE, Start.from("ab".toCharArray(), 1));
        assertEquals(Start.WORD_BYTE, Start.from("a9".toCharArray(), 1));
        assertEquals(Start.WORD_BYTE, Start.from("a_".toCharArray(), 1));
    }

    @Test void afterNonWordCharIsNonWordByte() {
        assertEquals(Start.NON_WORD_BYTE, Start.from("a b".toCharArray(), 2));
        assertEquals(Start.NON_WORD_BYTE, Start.from("a.b".toCharArray(), 2));
    }

    @Test void textInitialLookHave() {
        LookSet any = LookSet.EMPTY
                .insert(LookKind.START_TEXT)
                .insert(LookKind.START_LINE)
                .insert(LookKind.WORD_BOUNDARY_ASCII);
        LookSet have = Start.TEXT.initialLookHave(any, false);
        assertTrue(have.contains(LookKind.START_TEXT));
        assertTrue(have.contains(LookKind.START_LINE));
        assertTrue(have.contains(LookKind.WORD_START_HALF_ASCII));
    }

    @Test void wordByteInitialLookHaveIsEmpty() {
        LookSet any = LookSet.of(LookKind.WORD_BOUNDARY_ASCII);
        LookSet have = Start.WORD_BYTE.initialLookHave(any, false);
        assertFalse(have.contains(LookKind.WORD_START_HALF_ASCII));
    }

    @Test void isFromWord() {
        assertTrue(Start.WORD_BYTE.isFromWord());
        assertFalse(Start.TEXT.isFromWord());
        assertFalse(Start.LINE_LF.isFromWord());
        assertFalse(Start.NON_WORD_BYTE.isFromWord());
    }

    @Test void isHalfCrlfForward() {
        assertTrue(Start.LINE_CR.isHalfCrlf(false));
        assertFalse(Start.LINE_LF.isHalfCrlf(false));
        assertFalse(Start.TEXT.isHalfCrlf(false));
    }

    @Test void isHalfCrlfReverse() {
        assertTrue(Start.LINE_LF.isHalfCrlf(true));
        assertFalse(Start.LINE_CR.isHalfCrlf(true));
    }

    @Test void countIs5() {
        assertEquals(5, Start.COUNT);
    }
}
