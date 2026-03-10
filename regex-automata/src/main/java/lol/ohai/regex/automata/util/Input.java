package lol.ohai.regex.automata.util;

import java.nio.charset.StandardCharsets;

/**
 * Search input configuration. Encodes CharSequence to UTF-8 for byte-oriented NFA matching.
 *
 * <p>The NFA operates on bytes, but Java strings are char-based. This class handles the
 * conversion and maintains a mapping from byte offsets back to char offsets so that
 * match results can be reported in terms of the original CharSequence.</p>
 */
public final class Input {
    private final byte[] haystack;
    private final int start;
    private final int end;
    private final boolean anchored;

    // byteToChar[byteOffset] = charOffset in the original CharSequence.
    // Length is haystack.length + 1 so that end-of-input byte offset maps correctly.
    private final int[] byteToChar;

    private Input(byte[] haystack, int start, int end, boolean anchored, int[] byteToChar) {
        this.haystack = haystack;
        this.start = start;
        this.end = end;
        this.anchored = anchored;
        this.byteToChar = byteToChar;
    }

    /**
     * Creates an unanchored search input from the given text, searching the entire string.
     */
    public static Input of(CharSequence text) {
        return create(text, 0, text.length(), false);
    }

    /**
     * Creates an unanchored search input from the given text, searching between
     * char offsets {@code start} (inclusive) and {@code end} (exclusive).
     */
    public static Input of(CharSequence text, int start, int end) {
        return create(text, start, end, false);
    }

    /**
     * Creates an anchored search input from the given text.
     */
    public static Input anchored(CharSequence text) {
        return create(text, 0, text.length(), true);
    }

    /**
     * Creates a search input with explicit byte-level bounds and anchored flag.
     * The full text is encoded to UTF-8, but the search is restricted to the
     * byte range [byteStart, byteEnd).
     *
     * @param text      the input text
     * @param byteStart the byte offset at which to start searching (inclusive)
     * @param byteEnd   the byte offset at which to stop searching (exclusive)
     * @param anchored  whether the search is anchored
     * @return the configured Input
     */
    public static Input withByteBounds(CharSequence text, int byteStart, int byteEnd, boolean anchored) {
        // Encode the full text first, then override start/end with byte offsets.
        Input full = create(text, 0, text.length(), anchored);
        if (byteStart < 0 || byteEnd > full.haystack.length || byteStart > byteEnd) {
            throw new IllegalArgumentException(
                "invalid byte bounds: [" + byteStart + ", " + byteEnd + ") for haystack of " + full.haystack.length + " bytes");
        }
        return new Input(full.haystack, byteStart, byteEnd, anchored, full.byteToChar);
    }

    private static Input create(CharSequence text, int charStart, int charEnd, boolean anchored) {
        // Encode the full text to UTF-8 and build byte-to-char mapping.
        // We encode the entire CharSequence so that look-behind at the search boundaries works.
        int len = text.length();
        // Worst case: each char could be up to 3 bytes (BMP), or 4 bytes for surrogate pairs.
        // We'll compute exact sizes.
        byte[] buf = new byte[len * 4]; // over-allocate then trim
        int[] b2c = new int[len * 4 + 1];
        int bytePos = 0;
        int byteStart = -1;
        int byteEnd = -1;

        for (int charIdx = 0; charIdx < len; ) {
            if (charIdx == charStart) {
                byteStart = bytePos;
            }
            if (charIdx == charEnd) {
                byteEnd = bytePos;
            }

            int cp = Character.codePointAt(text, charIdx);
            int charCount = Character.charCount(cp);

            if (cp <= 0x7F) {
                b2c[bytePos] = charIdx;
                buf[bytePos++] = (byte) cp;
            } else if (cp <= 0x7FF) {
                b2c[bytePos] = charIdx;
                buf[bytePos++] = (byte) (0xC0 | (cp >> 6));
                b2c[bytePos] = charIdx;
                buf[bytePos++] = (byte) (0x80 | (cp & 0x3F));
            } else if (cp <= 0xFFFF) {
                b2c[bytePos] = charIdx;
                buf[bytePos++] = (byte) (0xE0 | (cp >> 12));
                b2c[bytePos] = charIdx;
                buf[bytePos++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                b2c[bytePos] = charIdx;
                buf[bytePos++] = (byte) (0x80 | (cp & 0x3F));
            } else {
                b2c[bytePos] = charIdx;
                buf[bytePos++] = (byte) (0xF0 | (cp >> 18));
                b2c[bytePos] = charIdx;
                buf[bytePos++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                b2c[bytePos] = charIdx;
                buf[bytePos++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                b2c[bytePos] = charIdx;
                buf[bytePos++] = (byte) (0x80 | (cp & 0x3F));
            }
            charIdx += charCount;
        }

        // Handle end-of-string boundaries
        if (charStart == len) {
            byteStart = bytePos;
        }
        if (charEnd == len) {
            byteEnd = bytePos;
        }
        // The sentinel entry at bytePos maps to the char length
        b2c[bytePos] = len;

        // Trim to actual size
        byte[] haystack = new byte[bytePos];
        System.arraycopy(buf, 0, haystack, 0, bytePos);
        int[] byteToChar = new int[bytePos + 1];
        System.arraycopy(b2c, 0, byteToChar, 0, bytePos + 1);

        return new Input(haystack, byteStart, byteEnd, anchored, byteToChar);
    }

    /** Returns the UTF-8 encoded haystack bytes. */
    public byte[] haystack() {
        return haystack;
    }

    /** Returns the byte offset at which to start the search. */
    public int start() {
        return start;
    }

    /** Returns the byte offset at which to end the search (exclusive). */
    public int end() {
        return end;
    }

    /** Returns true if this is an anchored search. */
    public boolean isAnchored() {
        return anchored;
    }

    /**
     * Convert a byte offset to a char offset in the original CharSequence.
     *
     * @param byteOffset a byte offset in [0, haystack.length]
     * @return the corresponding char offset
     */
    public int toCharOffset(int byteOffset) {
        return byteToChar[byteOffset];
    }
}
