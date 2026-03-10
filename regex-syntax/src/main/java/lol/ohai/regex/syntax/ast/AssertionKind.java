package lol.ohai.regex.syntax.ast;

/**
 * An assertion kind.
 */
public enum AssertionKind {
    /** {@code ^} */
    START_LINE,
    /** {@code $} */
    END_LINE,
    /** {@code \A} */
    START_TEXT,
    /** {@code \z} */
    END_TEXT,
    /** {@code \b} */
    WORD_BOUNDARY,
    /** {@code \B} */
    NOT_WORD_BOUNDARY,
    /** {@code \b{start}} */
    WORD_BOUNDARY_START,
    /** {@code \b{end}} */
    WORD_BOUNDARY_END,
    /** {@code \<} (alias for {@code \b{start}}) */
    WORD_BOUNDARY_START_ANGLE,
    /** {@code \>} (alias for {@code \b{end}}) */
    WORD_BOUNDARY_END_ANGLE,
    /** {@code \b{start-half}} */
    WORD_BOUNDARY_START_HALF,
    /** {@code \b{end-half}} */
    WORD_BOUNDARY_END_HALF
}
