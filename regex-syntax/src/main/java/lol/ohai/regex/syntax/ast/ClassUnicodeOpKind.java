package lol.ohai.regex.syntax.ast;

/**
 * The type of op used in a Unicode character class.
 */
public enum ClassUnicodeOpKind {
    /** {@code \p{scx=Katakana}} */
    EQUAL,
    /** {@code \p{scx:Katakana}} */
    COLON,
    /** {@code \p{scx!=Katakana}} */
    NOT_EQUAL;

    public boolean isEqual() {
        return this == EQUAL || this == COLON;
    }
}
