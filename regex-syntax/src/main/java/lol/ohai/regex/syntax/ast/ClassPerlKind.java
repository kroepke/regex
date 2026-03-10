package lol.ohai.regex.syntax.ast;

/**
 * The available Perl character classes.
 */
public enum ClassPerlKind {
    /** Decimal numbers ({@code \d}, {@code \D}). */
    DIGIT,
    /** Whitespace ({@code \s}, {@code \S}). */
    SPACE,
    /** Word characters ({@code \w}, {@code \W}). */
    WORD
}
