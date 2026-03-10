package lol.ohai.regex.syntax.ast;

/**
 * The type of a Unicode hex literal.
 */
public enum HexLiteralKind {
    /** {@code \x} prefix. Without brackets: 2 digits. */
    X(2),
    /** Backslash-u prefix. Without brackets: 4 digits. */
    UNICODE_SHORT(4),
    /** Backslash-U prefix. Without brackets: 8 digits. */
    UNICODE_LONG(8);

    private final int digits;

    HexLiteralKind(int digits) {
        this.digits = digits;
    }

    /** The number of digits required when used without brackets. */
    public int digits() {
        return digits;
    }
}
