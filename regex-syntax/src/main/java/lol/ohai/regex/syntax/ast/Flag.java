package lol.ohai.regex.syntax.ast;

/**
 * A single flag.
 */
public enum Flag {
    /** {@code i} */
    CASE_INSENSITIVE,
    /** {@code m} */
    MULTI_LINE,
    /** {@code s} */
    DOT_MATCHES_NEW_LINE,
    /** {@code U} */
    SWAP_GREED,
    /** {@code u} */
    UNICODE,
    /** {@code R} */
    CRLF,
    /** {@code x} */
    IGNORE_WHITESPACE
}
