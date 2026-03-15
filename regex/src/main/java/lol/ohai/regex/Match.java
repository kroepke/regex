package lol.ohai.regex;

/**
 * A single match result, with start/end offsets in char units.
 * The matched text is computed lazily on first access to {@link #text()}.
 */
public final class Match {
    private final int start;
    private final int end;
    private CharSequence source;
    private String text; // lazily computed

    Match(int start, int end, CharSequence source) {
        this.start = start;
        this.end = end;
        this.source = source;
    }

    public int start() { return start; }
    public int end() { return end; }

    public String text() {
        if (text == null) {
            text = source.subSequence(start, end).toString();
            source = null;
        }
        return text;
    }

    public int length() { return end - start; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Match m)) return false;
        return start == m.start && end == m.end;
    }

    @Override
    public int hashCode() {
        return 31 * start + end;
    }

    @Override
    public String toString() {
        return "Match[start=" + start + ", end=" + end + ", text=" + text() + "]";
    }
}
