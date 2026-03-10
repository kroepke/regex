package lol.ohai.regex.automata.meta;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SingleLiteralTest {

    @Test
    void findsLiteralAtStart() {
        var pf = new SingleLiteral("hello".toCharArray());
        String hay = "hello world";
        assertEquals(0, pf.find(hay, 0, hay.length()));
    }

    @Test
    void findsLiteralInMiddle() {
        var pf = new SingleLiteral("world".toCharArray());
        String hay = "hello world";
        assertEquals(6, pf.find(hay, 0, hay.length()));
    }

    @Test
    void returnsMinusOneWhenNotFound() {
        var pf = new SingleLiteral("xyz".toCharArray());
        String hay = "hello world";
        assertEquals(-1, pf.find(hay, 0, hay.length()));
    }

    @Test
    void respectsFromBound() {
        var pf = new SingleLiteral("hello".toCharArray());
        String hay = "hello hello";
        assertEquals(6, pf.find(hay, 1, hay.length()));
    }

    @Test
    void respectsToBound() {
        var pf = new SingleLiteral("world".toCharArray());
        String hay = "hello world";
        // "world" starts at 6, needs positions 6..10 — but to=8 truncates
        assertEquals(-1, pf.find(hay, 0, 8));
    }

    @Test
    void findsSingleCharLiteral() {
        var pf = new SingleLiteral("x".toCharArray());
        String hay = "abcxdef";
        assertEquals(3, pf.find(hay, 0, hay.length()));
    }

    @Test
    void isExactReturnsTrue() {
        var pf = new SingleLiteral("test".toCharArray());
        assertTrue(pf.isExact());
        assertEquals(4, pf.matchLength());
    }

    @Test
    void findsAtExactEnd() {
        var pf = new SingleLiteral("end".toCharArray());
        String hay = "the end";
        assertEquals(4, pf.find(hay, 0, hay.length()));
    }

    @Test
    void emptyNeedleFindsAtFrom() {
        var pf = new SingleLiteral(new char[0]);
        String hay = "hello";
        assertEquals(0, pf.find(hay, 0, hay.length()));
    }
}
