package lol.ohai.regex.syntax.hir;

/**
 * The kind of a zero-width look-around assertion.
 */
public enum LookKind {
    /** Match the beginning of a line (or text, depending on multiline mode). Uses {@code \n} as line terminator. */
    START_LINE,
    /** Match the end of a line (or text, depending on multiline mode). Uses {@code \n} as line terminator. */
    END_LINE,
    /** Match the beginning of a line using CRLF line terminator semantics. Matches after {@code \n} or after {@code \r} when not followed by {@code \n}. */
    START_LINE_CRLF,
    /** Match the end of a line using CRLF line terminator semantics. Matches before {@code \r} or before {@code \n} when not preceded by {@code \r}. */
    END_LINE_CRLF,
    /** Match the beginning of the input text ({@code \A}). */
    START_TEXT,
    /** Match the end of the input text ({@code \z}). */
    END_TEXT,
    /** A Unicode word boundary ({@code \b}). */
    WORD_BOUNDARY_UNICODE,
    /** A negated Unicode word boundary ({@code \B}). */
    WORD_BOUNDARY_UNICODE_NEGATE,
    /** An ASCII word boundary. */
    WORD_BOUNDARY_ASCII,
    /** A negated ASCII word boundary. */
    WORD_BOUNDARY_ASCII_NEGATE,
    /** Start of an ASCII word boundary ({@code (?-u:\b\{start\})}). */
    WORD_START_ASCII,
    /** End of an ASCII word boundary ({@code (?-u:\b\{end\})}). */
    WORD_END_ASCII,
    /** Start-half of an ASCII word boundary ({@code (?-u:\b\{start-half\})}). */
    WORD_START_HALF_ASCII,
    /** End-half of an ASCII word boundary ({@code (?-u:\b\{end-half\})}). */
    WORD_END_HALF_ASCII,
    /** Start of a Unicode word boundary ({@code \b\{start\}}). */
    WORD_START_UNICODE,
    /** End of a Unicode word boundary ({@code \b\{end\}}). */
    WORD_END_UNICODE,
    /** Start-half of a Unicode word boundary ({@code \b\{start-half\}}). */
    WORD_START_HALF_UNICODE,
    /** End-half of a Unicode word boundary ({@code \b\{end-half\}}). */
    WORD_END_HALF_UNICODE;

    /** Returns the single-bit mask for this look kind: {@code 1 << ordinal()}. */
    public int asBit() {
        return 1 << ordinal();
    }
}
