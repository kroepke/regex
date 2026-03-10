package lol.ohai.regex.automata.nfa.thompson;

/**
 * A sequence of byte ranges that, when matched in order, accept the UTF-8
 * encoding of a set of Unicode codepoints.
 *
 * <p>Each element is a pair {@code [start, end]} representing an inclusive byte range.
 * For example, the sequence {@code [[0xC2, 0xDF], [0x80, 0xBF]]} matches any
 * 2-byte UTF-8 encoded codepoint in the range U+0080 to U+07FF.</p>
 *
 * @param ranges array of byte ranges, each a 2-element array [start, end]
 */
record Utf8Sequence(int[][] ranges) {

    /**
     * Creates a single-byte sequence.
     */
    static Utf8Sequence one(int start, int end) {
        return new Utf8Sequence(new int[][]{{start, end}});
    }

    /**
     * Creates a sequence from the given byte ranges.
     */
    static Utf8Sequence of(int[][] ranges) {
        return new Utf8Sequence(ranges);
    }

    /**
     * Returns the number of byte ranges in this sequence (1-4).
     */
    int length() {
        return ranges.length;
    }
}
