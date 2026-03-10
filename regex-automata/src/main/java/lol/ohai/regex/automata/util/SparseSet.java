package lol.ohai.regex.automata.util;

/**
 * A sparse set for efficiently tracking integer values in range [0, capacity).
 * Provides O(1) insert, contains, and clear operations.
 *
 * <p>This data structure is used to track active NFA states in the PikeVM.
 * The key property is that {@link #clear()} is O(1), which matters because
 * the set is cleared on every byte of input during NFA simulation.</p>
 */
public final class SparseSet {
    private final int[] dense;
    private final int[] sparse;
    private int size;

    /**
     * Creates a new sparse set that can hold values in [0, capacity).
     *
     * @param capacity the exclusive upper bound on values that can be stored
     */
    public SparseSet(int capacity) {
        this.dense = new int[capacity];
        this.sparse = new int[capacity];
        this.size = 0;
    }

    /**
     * Returns true if the given value is in the set.
     *
     * @param value a value in [0, capacity)
     * @return true if the value is present
     */
    public boolean contains(int value) {
        int i = sparse[value];
        return i < size && dense[i] == value;
    }

    /**
     * Inserts a value into the set.
     *
     * @param value a value in [0, capacity)
     * @return true if the value was newly inserted, false if already present
     */
    public boolean insert(int value) {
        if (contains(value)) {
            return false;
        }
        int i = size;
        dense[i] = value;
        sparse[value] = i;
        size++;
        return true;
    }

    /**
     * Clears all elements from the set in O(1) time.
     */
    public void clear() {
        size = 0;
    }

    /**
     * Returns the number of elements in the set.
     */
    public int size() {
        return size;
    }

    /**
     * Returns the capacity (maximum value + 1) of this set.
     */
    public int capacity() {
        return dense.length;
    }

    /**
     * Returns true if the set is empty.
     */
    public boolean isEmpty() {
        return size == 0;
    }

    /**
     * Returns the element at the given index in insertion order.
     *
     * @param index an index in [0, size)
     * @return the value at that index
     */
    public int get(int index) {
        return dense[index];
    }
}
