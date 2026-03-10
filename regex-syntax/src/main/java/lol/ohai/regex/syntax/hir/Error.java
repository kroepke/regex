package lol.ohai.regex.syntax.hir;

import lol.ohai.regex.syntax.ast.Span;

/**
 * An error that occurs during HIR translation.
 */
public final class Error extends Exception {

    /**
     * The kind of HIR translation error.
     */
    public enum ErrorKind {
        /** Unicode mode is not allowed in this context. */
        UNICODE_NOT_ALLOWED,
        /** The pattern is not valid UTF-8. */
        INVALID_UTF8,
        /** A Unicode property name was not found. */
        UNICODE_PROPERTY_NOT_FOUND,
        /** A Unicode property value was not found. */
        UNICODE_PROPERTY_VALUE_NOT_FOUND,
        /** A Unicode Perl class is not available (full Unicode tables not loaded). */
        UNICODE_PERL_CLASS_NOT_FOUND,
        /** Unicode case folding is not available. */
        UNICODE_CASE_UNAVAILABLE
    }

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
        return "HIR translation error: " + kind + " at " + span + " in pattern: " + pattern;
    }
}
