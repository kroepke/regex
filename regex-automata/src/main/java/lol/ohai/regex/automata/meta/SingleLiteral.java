package lol.ohai.regex.automata.meta;

import java.util.Objects;

/**
 * A prefilter that searches for a single literal string using
 * {@link String#indexOf(String, int)}.
 *
 * <p>The haystack is passed as a {@link String} to avoid per-call allocation.
 * The caller (Strategy/Input) constructs the String once per search.</p>
 */
public final class SingleLiteral implements Prefilter {
    private final String needle;
    private final int needleLen;

    public SingleLiteral(char[] needle) {
        Objects.requireNonNull(needle);
        this.needle = new String(needle);
        this.needleLen = needle.length;
    }

    @Override
    public int find(String haystack, int from, int to) {
        int pos = haystack.indexOf(needle, from);
        if (pos < 0 || pos + needleLen > to) {
            return -1;
        }
        return pos;
    }

    @Override
    public boolean isExact() {
        return true;
    }

    @Override
    public int matchLength() {
        return needleLen;
    }
}
