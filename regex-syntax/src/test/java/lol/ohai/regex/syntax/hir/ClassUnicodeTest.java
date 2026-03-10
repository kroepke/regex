package lol.ohai.regex.syntax.hir;

import lol.ohai.regex.syntax.hir.ClassUnicode.ClassUnicodeRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClassUnicodeTest {

    @Test
    void emptySet() {
        ClassUnicode cls = new ClassUnicode();
        assertTrue(cls.isEmpty());
        assertEquals(List.of(), cls.ranges());
    }

    @Test
    void singleRange() {
        ClassUnicode cls = ClassUnicode.of(new ClassUnicodeRange('a', 'z'));
        assertEquals(List.of(new ClassUnicodeRange('a', 'z')), cls.ranges());
    }

    @Test
    void sortAndMerge() {
        // Overlapping and out-of-order ranges should be merged
        ClassUnicode cls = ClassUnicode.of(
                new ClassUnicodeRange('m', 'z'),
                new ClassUnicodeRange('a', 'n')
        );
        assertEquals(List.of(new ClassUnicodeRange('a', 'z')), cls.ranges());
    }

    @Test
    void sortAndMergeAdjacent() {
        // Adjacent ranges should be merged
        ClassUnicode cls = ClassUnicode.of(
                new ClassUnicodeRange('a', 'm'),
                new ClassUnicodeRange('n', 'z')
        );
        assertEquals(List.of(new ClassUnicodeRange('a', 'z')), cls.ranges());
    }

    @Test
    void sortAndMergeMultiple() {
        ClassUnicode cls = ClassUnicode.of(
                new ClassUnicodeRange('x', 'z'),
                new ClassUnicodeRange('a', 'c'),
                new ClassUnicodeRange('m', 'p')
        );
        assertEquals(List.of(
                new ClassUnicodeRange('a', 'c'),
                new ClassUnicodeRange('m', 'p'),
                new ClassUnicodeRange('x', 'z')
        ), cls.ranges());
    }

    @Test
    void union() {
        ClassUnicode a = ClassUnicode.of(new ClassUnicodeRange('a', 'c'));
        ClassUnicode b = ClassUnicode.of(new ClassUnicodeRange('x', 'z'));
        a.union(b);
        assertEquals(List.of(
                new ClassUnicodeRange('a', 'c'),
                new ClassUnicodeRange('x', 'z')
        ), a.ranges());
    }

    @Test
    void unionOverlapping() {
        ClassUnicode a = ClassUnicode.of(new ClassUnicodeRange('a', 'm'));
        ClassUnicode b = ClassUnicode.of(new ClassUnicodeRange('k', 'z'));
        a.union(b);
        assertEquals(List.of(new ClassUnicodeRange('a', 'z')), a.ranges());
    }

    @Test
    void intersect() {
        ClassUnicode a = ClassUnicode.of(new ClassUnicodeRange('a', 'z'));
        ClassUnicode b = ClassUnicode.of(new ClassUnicodeRange('m', 'p'));
        a.intersect(b);
        assertEquals(List.of(new ClassUnicodeRange('m', 'p')), a.ranges());
    }

    @Test
    void intersectDisjoint() {
        ClassUnicode a = ClassUnicode.of(new ClassUnicodeRange('a', 'c'));
        ClassUnicode b = ClassUnicode.of(new ClassUnicodeRange('x', 'z'));
        a.intersect(b);
        assertTrue(a.isEmpty());
    }

    @Test
    void intersectMultiple() {
        ClassUnicode a = ClassUnicode.of(
                new ClassUnicodeRange('a', 'f'),
                new ClassUnicodeRange('m', 'z')
        );
        ClassUnicode b = ClassUnicode.of(
                new ClassUnicodeRange('d', 'p')
        );
        a.intersect(b);
        assertEquals(List.of(
                new ClassUnicodeRange('d', 'f'),
                new ClassUnicodeRange('m', 'p')
        ), a.ranges());
    }

    @Test
    void difference() {
        ClassUnicode a = ClassUnicode.of(new ClassUnicodeRange('a', 'z'));
        ClassUnicode b = ClassUnicode.of(new ClassUnicodeRange('m', 'p'));
        a.difference(b);
        assertEquals(List.of(
                new ClassUnicodeRange('a', 'l'),
                new ClassUnicodeRange('q', 'z')
        ), a.ranges());
    }

    @Test
    void differenceSubset() {
        ClassUnicode a = ClassUnicode.of(new ClassUnicodeRange('m', 'p'));
        ClassUnicode b = ClassUnicode.of(new ClassUnicodeRange('a', 'z'));
        a.difference(b);
        assertTrue(a.isEmpty());
    }

    @Test
    void differenceDisjoint() {
        ClassUnicode a = ClassUnicode.of(new ClassUnicodeRange('a', 'c'));
        ClassUnicode b = ClassUnicode.of(new ClassUnicodeRange('x', 'z'));
        a.difference(b);
        assertEquals(List.of(new ClassUnicodeRange('a', 'c')), a.ranges());
    }

    @Test
    void negate() {
        ClassUnicode cls = ClassUnicode.of(new ClassUnicodeRange('a', 'z'));
        cls.negate();
        assertEquals(List.of(
                new ClassUnicodeRange(0x00, 'a' - 1),
                new ClassUnicodeRange('z' + 1, 0x10FFFF)
        ), cls.ranges());
    }

    @Test
    void negateEmpty() {
        ClassUnicode cls = new ClassUnicode();
        cls.negate();
        assertEquals(List.of(
                new ClassUnicodeRange(0x00, 0x10FFFF)
        ), cls.ranges());
    }

    @Test
    void negateFull() {
        ClassUnicode cls = ClassUnicode.of(new ClassUnicodeRange(0x00, 0x10FFFF));
        cls.negate();
        assertTrue(cls.isEmpty());
    }

    @Test
    void symmetricDifference() {
        ClassUnicode a = ClassUnicode.of(new ClassUnicodeRange('a', 'm'));
        ClassUnicode b = ClassUnicode.of(new ClassUnicodeRange('g', 'z'));
        a.symmetricDifference(b);
        assertEquals(List.of(
                new ClassUnicodeRange('a', 'f'),
                new ClassUnicodeRange('n', 'z')
        ), a.ranges());
    }

    @Test
    void caseFoldSimpleAscii() {
        ClassUnicode cls = ClassUnicode.of(new ClassUnicodeRange('a', 'a'));
        cls.caseFoldSimple();
        assertEquals(List.of(
                new ClassUnicodeRange('A', 'A'),
                new ClassUnicodeRange('a', 'a')
        ), cls.ranges());
    }

    @Test
    void caseFoldSimpleAsciiUpperCase() {
        ClassUnicode cls = ClassUnicode.of(new ClassUnicodeRange('A', 'A'));
        cls.caseFoldSimple();
        assertEquals(List.of(
                new ClassUnicodeRange('A', 'A'),
                new ClassUnicodeRange('a', 'a')
        ), cls.ranges());
    }

    @Test
    void caseFoldSimpleRange() {
        ClassUnicode cls = ClassUnicode.of(new ClassUnicodeRange('a', 'z'));
        cls.caseFoldSimple();
        assertEquals(List.of(
                new ClassUnicodeRange('A', 'Z'),
                new ClassUnicodeRange('a', 'z')
        ), cls.ranges());
    }

    @Test
    void caseFoldNonLetter() {
        // Case folding a digit range should not change it
        ClassUnicode cls = ClassUnicode.of(new ClassUnicodeRange('0', '9'));
        cls.caseFoldSimple();
        assertEquals(List.of(new ClassUnicodeRange('0', '9')), cls.ranges());
    }

    @Test
    void rangeConstructorSwaps() {
        // Constructor should swap start > end
        ClassUnicodeRange r = new ClassUnicodeRange('z', 'a');
        assertEquals('a', r.start());
        assertEquals('z', r.end());
    }

    @Test
    void pushAddsAndCanonicalizes() {
        ClassUnicode cls = ClassUnicode.of(new ClassUnicodeRange('a', 'c'));
        cls.push(new ClassUnicodeRange('b', 'e'));
        assertEquals(List.of(new ClassUnicodeRange('a', 'e')), cls.ranges());
    }
}
