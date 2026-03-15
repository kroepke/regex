package lol.ohai.regex.automata.meta;

/**
 * A prefilter that quickly scans a haystack for candidate match positions.
 *
 * <p>Prefilters are used by the meta engine to skip regions of the haystack
 * that cannot possibly match, before running the full regex engine.</p>
 *
 * <p>A prefilter may have false positives (report positions that don't lead
 * to matches) but must never have false negatives (miss positions that do).</p>
 *
 * <p>The {@code find} method takes a {@link String} haystack to leverage
 * {@link String#indexOf} JIT intrinsics without per-call allocation.
 * Callers should construct the String once per search and reuse it.</p>
 */
public interface Prefilter {

    /**
     * Find the next occurrence of this prefilter's pattern in the haystack,
     * starting the search at position {@code from} (inclusive) and ending
     * at position {@code to} (exclusive).
     *
     * @param haystack the string to search
     * @param from     start position (inclusive)
     * @param to       end position (exclusive)
     * @return the start index of the match, or -1 if not found
     */
    int find(String haystack, int from, int to);

    /**
     * Whether this prefilter reports exact match boundaries.
     * When true, the match spans from the returned position to
     * position + {@link #matchLength()}.
     */
    boolean isExact();

    /**
     * The length of the match when {@link #isExact()} is true.
     * Undefined when {@code isExact()} is false.
     */
    int matchLength();

    /**
     * Find the next occurrence and return both start and end as a packed long.
     * Returns -1 if not found. Otherwise: start in low 32 bits, end in high 32 bits.
     *
     * <p>Default implementation uses {@link #find} + {@link #matchLength()}.
     * Subclasses with variable-length matches should override this.</p>
     */
    default long findSpan(String haystack, int from, int to) {
        int pos = find(haystack, from, to);
        if (pos < 0) return -1;
        return ((long)(pos + matchLength()) << 32) | pos;
    }

    static int spanStart(long span) { return (int) span; }
    static int spanEnd(long span) { return (int) (span >>> 32); }
}
