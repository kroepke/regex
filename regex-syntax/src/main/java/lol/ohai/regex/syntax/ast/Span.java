package lol.ohai.regex.syntax.ast;

/**
 * A span in a regex pattern string, consisting of a start and end position.
 */
public record Span(Position start, Position end) {
    public static Span of(Position start, Position end) {
        return new Span(start, end);
    }

    /** Create a span where start and end are the same position. */
    public static Span splat(Position pos) {
        return new Span(pos, pos);
    }

    /** Return a new span with the same start but a different end. */
    public Span withEnd(Position pos) {
        return new Span(start, pos);
    }

    /** Return a new span with the same end but a different start. */
    public Span withStart(Position pos) {
        return new Span(pos, end);
    }

    public boolean isEmpty() {
        return start.offset() == end.offset();
    }
}
