package lol.ohai.regex.automata.nfa.thompson;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Converts ranges of Unicode scalar values to equivalent ranges of UTF-8 bytes.
 *
 * <p>This is a port of the Rust regex crate's {@code utf8.rs} module. For a given
 * codepoint range [start, end], it produces a sequence of {@link Utf8Sequence} values.
 * Each sequence is a chain of byte ranges that, when matched in order, accept exactly
 * the UTF-8 encodings of codepoints in that range.</p>
 *
 * <p>The algorithm was originally described by Russ Cox and Ken Thompson.</p>
 */
final class Utf8Sequences implements Iterator<Utf8Sequence> {

    private final Deque<long[]> rangeStack = new ArrayDeque<>();
    private Utf8Sequence next;

    /**
     * Creates a new iterator over UTF-8 byte range sequences for the given
     * codepoint range [start, end] (inclusive).
     *
     * @param start the start codepoint (inclusive)
     * @param end   the end codepoint (inclusive)
     */
    Utf8Sequences(int start, int end) {
        push(start, end);
        advance();
    }

    private void push(int start, int end) {
        // Pack start and end into a long array of size 1 for the stack
        rangeStack.push(new long[]{start, end});
    }

    @Override
    public boolean hasNext() {
        return next != null;
    }

    @Override
    public Utf8Sequence next() {
        if (next == null) {
            throw new NoSuchElementException();
        }
        Utf8Sequence result = next;
        advance();
        return result;
    }

    private void advance() {
        next = null;
        top:
        while (!rangeStack.isEmpty()) {
            long[] range = rangeStack.pop();
            int start = (int) range[0];
            int end = (int) range[1];

            inner:
            while (true) {
                // Split around surrogate range
                if (start < 0xE000 && end > 0xD7FF) {
                    push(0xE000, end);
                    end = 0xD7FF;
                    continue inner;
                }

                // Invalid range
                if (start > end) {
                    continue top;
                }

                // Split by UTF-8 byte length boundaries
                for (int i = 1; i < 4; i++) {
                    int max = maxScalarValue(i);
                    if (start <= max && max < end) {
                        push(max + 1, end);
                        end = max;
                        continue inner;
                    }
                }

                // ASCII case
                if (start <= 0x7F && end <= 0x7F) {
                    next = Utf8Sequence.one(start, end);
                    return;
                }

                // Split so that the start and end encode to the same byte-length
                // and share the same high bits, allowing a single sequence of
                // byte ranges to cover the entire sub-range.
                for (int i = 1; i < 4; i++) {
                    int m = (1 << (6 * i)) - 1;
                    if ((start & ~m) != (end & ~m)) {
                        if ((start & m) != 0) {
                            push((start | m) + 1, end);
                            end = start | m;
                            continue inner;
                        }
                        if ((end & m) != m) {
                            push(end & ~m, end);
                            end = (end & ~m) - 1;
                            continue inner;
                        }
                    }
                }

                // At this point, start and end encode to the same number of bytes
                // and each byte position has a contiguous range.
                byte[] startBytes = encodeUtf8(start);
                byte[] endBytes = encodeUtf8(end);
                assert startBytes.length == endBytes.length;

                int[][] ranges = new int[startBytes.length][2];
                for (int i = 0; i < startBytes.length; i++) {
                    ranges[i][0] = startBytes[i] & 0xFF;
                    ranges[i][1] = endBytes[i] & 0xFF;
                }
                next = Utf8Sequence.of(ranges);
                return;
            }
        }
    }

    private static int maxScalarValue(int nbytes) {
        return switch (nbytes) {
            case 1 -> 0x007F;
            case 2 -> 0x07FF;
            case 3 -> 0xFFFF;
            case 4 -> 0x10FFFF;
            default -> throw new IllegalArgumentException("invalid UTF-8 byte sequence size: " + nbytes);
        };
    }

    /**
     * Encodes a Unicode codepoint to its UTF-8 byte representation.
     */
    static byte[] encodeUtf8(int codepoint) {
        if (codepoint <= 0x7F) {
            return new byte[]{(byte) codepoint};
        } else if (codepoint <= 0x7FF) {
            return new byte[]{
                    (byte) (0xC0 | (codepoint >> 6)),
                    (byte) (0x80 | (codepoint & 0x3F))
            };
        } else if (codepoint <= 0xFFFF) {
            return new byte[]{
                    (byte) (0xE0 | (codepoint >> 12)),
                    (byte) (0x80 | ((codepoint >> 6) & 0x3F)),
                    (byte) (0x80 | (codepoint & 0x3F))
            };
        } else if (codepoint <= 0x10FFFF) {
            return new byte[]{
                    (byte) (0xF0 | (codepoint >> 18)),
                    (byte) (0x80 | ((codepoint >> 12) & 0x3F)),
                    (byte) (0x80 | ((codepoint >> 6) & 0x3F)),
                    (byte) (0x80 | (codepoint & 0x3F))
            };
        } else {
            throw new IllegalArgumentException("Invalid codepoint: " + codepoint);
        }
    }
}
