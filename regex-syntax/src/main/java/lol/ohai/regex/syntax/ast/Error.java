package lol.ohai.regex.syntax.ast;

/**
 * An error that occurred while parsing a regular expression into an AST.
 */
public final class Error extends Exception {
    private final ErrorKind kind;
    private final String pattern;
    private final Span span;

    public Error(ErrorKind kind, String pattern, Span span) {
        super(formatMessage(kind, pattern, span));
        this.kind = kind;
        this.pattern = pattern;
        this.span = span;
    }

    public ErrorKind kind() {
        return kind;
    }

    public String pattern() {
        return pattern;
    }

    public Span span() {
        return span;
    }

    private static String formatMessage(ErrorKind kind, String pattern, Span span) {
        return "regex parse error: " + kind + " at " + span.start().offset() + ".." + span.end().offset()
                + " in pattern: " + pattern;
    }
}
