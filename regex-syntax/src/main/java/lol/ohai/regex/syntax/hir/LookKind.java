package lol.ohai.regex.syntax.hir;

/**
 * The kind of a zero-width look-around assertion.
 */
public enum LookKind {
    /** Match the beginning of a line (or text, depending on multiline mode). */
    START_LINE,
    /** Match the end of a line (or text, depending on multiline mode). */
    END_LINE,
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
    /** Start of a word boundary ({@code \b\{start\}}). */
    WORD_START_UNICODE,
    /** End of a word boundary ({@code \b\{end\}}). */
    WORD_END_UNICODE,
    /** Start-half of a word boundary ({@code \b\{start-half\}}). */
    WORD_START_HALF_UNICODE,
    /** End-half of a word boundary ({@code \b\{end-half\}}). */
    WORD_END_HALF_UNICODE
}
