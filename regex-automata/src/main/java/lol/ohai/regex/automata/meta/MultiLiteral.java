package lol.ohai.regex.automata.meta;

import java.util.Objects;

/**
 * A prefilter that searches for the earliest occurrence of any of several
 * literal strings. Uses multiple {@link String#indexOf} calls internally.
 *
 * <p>For a small number of needles (typical for regex alternations), this
 * is efficient. For large numbers (>10), consider Aho-Corasick.</p>
 */
public final class MultiLiteral implements Prefilter {
    private final String[] needles;
    private final boolean exact;
    private final int matchLength;

    public MultiLiteral(char[][] needles) {
        Objects.requireNonNull(needles);
        if (needles.length == 0) {
            throw new IllegalArgumentException("needles must not be empty");
        }
        this.needles = new String[needles.length];
        for (int i = 0; i < needles.length; i++) {
            this.needles[i] = new String(needles[i]);
        }

        // Check if all needles have the same length
        int firstLen = needles[0].length;
        boolean allSame = true;
        for (int i = 1; i < needles.length; i++) {
            if (needles[i].length != firstLen) {
                allSame = false;
                break;
            }
        }
        this.exact = allSame;
        this.matchLength = allSame ? firstLen : -1;
    }

    @Override
    public int find(String haystack, int from, int to) {
        int best = -1;
        for (String needle : needles) {
            int pos = haystack.indexOf(needle, from);
            if (pos >= 0 && pos + needle.length() <= to) {
                if (best == -1 || pos < best) {
                    best = pos;
                }
            }
        }
        return best;
    }

    @Override
    public long findSpan(String haystack, int from, int to) {
        int bestPos = -1;
        int bestLen = 0;
        for (String needle : needles) {
            int pos = haystack.indexOf(needle, from);
            if (pos >= 0 && pos + needle.length() <= to) {
                if (bestPos == -1 || pos < bestPos) {
                    bestPos = pos;
                    bestLen = needle.length();
                }
            }
        }
        if (bestPos < 0) return -1;
        return ((long)(bestPos + bestLen) << 32) | bestPos;
    }

    @Override
    public boolean isExact() {
        return exact;
    }

    @Override
    public int matchLength() {
        return matchLength;
    }
}
