package lol.ohai.regex;

/**
 * Builder for configuring and compiling a {@link Regex}.
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Regex re = Regex.builder()
 *     .caseInsensitive(true)
 *     .nestLimit(100)
 *     .build("\\d+");
 * }</pre>
 */
public final class RegexBuilder {
    private boolean caseInsensitive = false;
    private int nestLimit = 250;

    RegexBuilder() {}

    /**
     * Sets whether matching is case-insensitive.
     * When true, the pattern {@code (?i)} flag is prepended.
     */
    public RegexBuilder caseInsensitive(boolean caseInsensitive) {
        this.caseInsensitive = caseInsensitive;
        return this;
    }

    /**
     * Sets the nest limit for the parser. Patterns with deeper nesting than this
     * limit will fail to compile. Default is 250.
     */
    public RegexBuilder nestLimit(int nestLimit) {
        if (nestLimit < 0) {
            throw new IllegalArgumentException("nestLimit must be non-negative: " + nestLimit);
        }
        this.nestLimit = nestLimit;
        return this;
    }

    /**
     * Compiles the given pattern with the configured options.
     *
     * @param pattern the regex pattern
     * @return the compiled Regex
     * @throws PatternSyntaxException if the pattern is invalid
     */
    public Regex build(String pattern) throws PatternSyntaxException {
        String effectivePattern = pattern;
        if (caseInsensitive) {
            effectivePattern = "(?i)" + pattern;
        }
        return Regex.create(effectivePattern, nestLimit);
    }
}
