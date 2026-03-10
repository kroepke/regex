package lol.ohai.regex.automata.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SparseSetTest {

    @Test
    void insertAndContains() {
        var set = new SparseSet(10);
        assertFalse(set.contains(3));
        assertTrue(set.insert(3));
        assertTrue(set.contains(3));
        assertFalse(set.contains(5));
    }

    @Test
    void insertDuplicate() {
        var set = new SparseSet(10);
        assertTrue(set.insert(3));
        assertFalse(set.insert(3));
        assertEquals(1, set.size());
    }

    @Test
    void clear() {
        var set = new SparseSet(10);
        set.insert(1);
        set.insert(2);
        set.insert(3);
        assertEquals(3, set.size());

        set.clear();
        assertEquals(0, set.size());
        assertTrue(set.isEmpty());
        assertFalse(set.contains(1));
        assertFalse(set.contains(2));
        assertFalse(set.contains(3));
    }

    @Test
    void insertionOrder() {
        var set = new SparseSet(10);
        set.insert(7);
        set.insert(2);
        set.insert(5);
        assertEquals(3, set.size());
        assertEquals(7, set.get(0));
        assertEquals(2, set.get(1));
        assertEquals(5, set.get(2));
    }

    @Test
    void capacity() {
        var set = new SparseSet(16);
        assertEquals(16, set.capacity());
        assertTrue(set.isEmpty());
    }

    @Test
    void insertAfterClear() {
        var set = new SparseSet(10);
        set.insert(3);
        set.insert(5);
        set.clear();
        assertTrue(set.insert(3));
        assertTrue(set.contains(3));
        assertFalse(set.contains(5));
        assertEquals(1, set.size());
    }
}
