package lol.ohai.regex.automata.nfa.thompson;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts ranges of Unicode codepoints to equivalent ranges of UTF-16 char units.
 *
 * <p>For BMP codepoints (U+0000–U+D7FF, U+E000–U+FFFF), each codepoint maps to a single
 * char unit. For supplementary codepoints (U+10000–U+10FFFF), each maps to a surrogate
 * pair (high surrogate followed by low surrogate).</p>
 */
final class Utf16Sequences {

    private Utf16Sequences() {}

    /**
     * Compiles a codepoint range into a list of char-unit range sequences.
     * Each sequence is an {@code int[][]} where each {@code int[]} is a [start, end] char range.
     * BMP ranges produce length-1 sequences; supplementary ranges produce length-2 sequences.
     */
    static List<int[][]> compile(int startCp, int endCp) {
        List<int[][]> result = new ArrayList<>();
        // Split around surrogate gap
        if (startCp <= 0xD7FF && endCp >= 0xD800) {
            // BMP portion below surrogates
            if (startCp <= 0xD7FF) {
                addBmp(result, startCp, Math.min(endCp, 0xD7FF));
            }
            // BMP portion above surrogates
            if (endCp >= 0xE000) {
                addBmpOrSupp(result, Math.max(startCp, 0xE000), endCp);
            }
            return result;
        }
        // No surrogate gap crossing
        addBmpOrSupp(result, startCp, endCp);
        return result;
    }

    private static void addBmpOrSupp(List<int[][]> result, int start, int end) {
        if (end <= 0xFFFF) {
            addBmp(result, start, end);
        } else if (start >= 0x10000) {
            addSupplementary(result, start, end);
        } else {
            // Split at BMP/supplementary boundary
            addBmp(result, start, 0xFFFF);
            addSupplementary(result, 0x10000, end);
        }
    }

    private static void addBmp(List<int[][]> result, int start, int end) {
        result.add(new int[][]{{start, end}});
    }

    private static void addSupplementary(List<int[][]> result, int startCp, int endCp) {
        int startHigh = highSurrogate(startCp);
        int startLow = lowSurrogate(startCp);
        int endHigh = highSurrogate(endCp);
        int endLow = lowSurrogate(endCp);

        if (startHigh == endHigh) {
            // Same high surrogate
            result.add(new int[][]{{startHigh, startHigh}, {startLow, endLow}});
            return;
        }

        // Different high surrogates — split
        if (startLow != 0xDC00) {
            result.add(new int[][]{{startHigh, startHigh}, {startLow, 0xDFFF}});
            startHigh++;
            if (startHigh > endHigh) return;
        }
        if (endLow != 0xDFFF) {
            result.add(new int[][]{{endHigh, endHigh}, {0xDC00, endLow}});
            endHigh--;
            if (startHigh > endHigh) return;
        }
        // Middle range: full low surrogate range
        if (startHigh <= endHigh) {
            result.add(new int[][]{{startHigh, endHigh}, {0xDC00, 0xDFFF}});
        }
    }

    private static int highSurrogate(int cp) {
        return 0xD800 + ((cp - 0x10000) >> 10);
    }

    private static int lowSurrogate(int cp) {
        return 0xDC00 + ((cp - 0x10000) & 0x3FF);
    }
}
