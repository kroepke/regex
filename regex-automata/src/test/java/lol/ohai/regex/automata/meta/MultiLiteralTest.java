package lol.ohai.regex.automata.meta;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultiLiteralTest {

    @Test
    void findsEarliestOfMultiple() {
        var pf = new MultiLiteral(new char[][] {
                "world".toCharArray(), "hello".toCharArray()
        });
        String hay = "hello world";
        assertEquals(0, pf.find(hay, 0, hay.length()));
    }

    @Test
    void findsSecondNeedleWhenFirstAbsent() {
        var pf = new MultiLiteral(new char[][] {
                "xyz".toCharArray(), "world".toCharArray()
        });
        String hay = "hello world";
        assertEquals(6, pf.find(hay, 0, hay.length()));
    }

    @Test
    void returnsMinusOneWhenNoneFound() {
        var pf = new MultiLiteral(new char[][] {
                "xyz".toCharArray(), "abc".toCharArray()
        });
        String hay = "hello world";
        assertEquals(-1, pf.find(hay, 0, hay.length()));
    }

    @Test
    void respectsFromBound() {
        var pf = new MultiLiteral(new char[][] {
                "hello".toCharArray(), "world".toCharArray()
        });
        String hay = "hello world hello";
        assertEquals(6, pf.find(hay, 1, hay.length()));
    }

    @Test
    void isExactWhenAllSameLength() {
        var pf = new MultiLiteral(new char[][] {
                "cat".toCharArray(), "dog".toCharArray()
        });
        assertTrue(pf.isExact());
        assertEquals(3, pf.matchLength());
    }

    @Test
    void notExactWhenDifferentLengths() {
        var pf = new MultiLiteral(new char[][] {
                "cat".toCharArray(), "elephant".toCharArray()
        });
        assertFalse(pf.isExact());
    }

    @Test
    void singleNeedleBehavesLikeSingleLiteral() {
        var pf = new MultiLiteral(new char[][] {
                "hello".toCharArray()
        });
        String hay = "say hello";
        assertEquals(4, pf.find(hay, 0, hay.length()));
        assertTrue(pf.isExact());
        assertEquals(5, pf.matchLength());
    }
}
