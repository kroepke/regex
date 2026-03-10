package lol.ohai.regex.automata.util;

/**
 * Search input configuration. Holds char[] for direct char-unit NFA matching.
 *
 * <p>Since Java strings are natively UTF-16, this class simply extracts the char[]
 * from the input CharSequence. No encoding or offset mapping is needed.</p>
 */
public final class Input {
    private final char[] haystack;
    private final int start;
    private final int end;
    private final boolean anchored;

    private Input(char[] haystack, int start, int end, boolean anchored) {
        this.haystack = haystack;
        this.start = start;
        this.end = end;
        this.anchored = anchored;
    }

    /**
     * Creates an unanchored search input from the given text, searching the entire string.
     */
    public static Input of(CharSequence text) {
        char[] chars = text.toString().toCharArray();
        return new Input(chars, 0, chars.length, false);
    }

    /**
     * Creates an unanchored search input from the given text, searching between
     * char offsets {@code start} (inclusive) and {@code end} (exclusive).
     */
    public static Input of(CharSequence text, int start, int end) {
        char[] chars = text.toString().toCharArray();
        return new Input(chars, start, end, false);
    }

    /**
     * Creates an anchored search input from the given text.
     */
    public static Input anchored(CharSequence text) {
        char[] chars = text.toString().toCharArray();
        return new Input(chars, 0, chars.length, true);
    }

    /** Returns the char[] haystack. */
    public char[] haystack() { return haystack; }

    /** Returns the char offset at which to start the search. */
    public int start() { return start; }

    /** Returns the char offset at which to end the search (exclusive). */
    public int end() { return end; }

    /** Returns true if this is an anchored search. */
    public boolean isAnchored() { return anchored; }

    /**
     * Creates a new Input with the same haystack but different bounds and/or anchored flag.
     */
    public Input withBounds(int newStart, int newEnd, boolean newAnchored) {
        return new Input(haystack, newStart, newEnd, newAnchored);
    }

    /**
     * Returns true if the position is NOT in the middle of a surrogate pair.
     * Positions 0 and haystack.length are always boundaries.
     * A position is NOT a char boundary if the char at that position is a low surrogate
     * (meaning we're between a high and low surrogate).
     */
    public boolean isCharBoundary(int pos) {
        if (pos <= 0 || pos >= haystack.length) return true;
        return !Character.isLowSurrogate(haystack[pos]);
    }
}
