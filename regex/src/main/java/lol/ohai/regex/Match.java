package lol.ohai.regex;

/**
 * A single match result, with start/end offsets in char units and the matched text.
 *
 * @param start the start char offset (inclusive) in the original input
 * @param end   the end char offset (exclusive) in the original input
 * @param text  the matched substring
 */
public record Match(int start, int end, String text) {

    /** Returns the length of the match in chars. */
    public int length() {
        return end - start;
    }
}
