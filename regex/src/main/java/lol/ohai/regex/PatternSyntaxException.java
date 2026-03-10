package lol.ohai.regex;

/**
 * Thrown when a regex pattern cannot be compiled.
 *
 * <p>Wraps underlying parse ({@code ast.Error}) or translation ({@code hir.Error}) errors
 * from the syntax layer.</p>
 */
public class PatternSyntaxException extends IllegalArgumentException {
    private final String pattern;

    public PatternSyntaxException(String pattern, Throwable cause) {
        super("failed to compile regex: " + pattern + ": " + cause.getMessage(), cause);
        this.pattern = pattern;
    }

    /** Returns the pattern that failed to compile. */
    public String pattern() {
        return pattern;
    }
}
