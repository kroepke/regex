package lol.ohai.regex.syntax.ast;

/**
 * The type of a special literal escape sequence.
 */
public enum SpecialLiteralKind {
    /** Bell, {@code \a} ({@code \x07}). */
    BELL,
    /** Form feed, {@code \f} ({@code \x0C}). */
    FORM_FEED,
    /** Tab, {@code \t} ({@code \x09}). */
    TAB,
    /** Line feed, {@code \n} ({@code \x0A}). */
    LINE_FEED,
    /** Carriage return, {@code \r} ({@code \x0D}). */
    CARRIAGE_RETURN,
    /** Vertical tab, {@code \v} ({@code \x0B}). */
    VERTICAL_TAB,
    /** Space, {@code \ } ({@code \x20}). Only in verbose mode. */
    SPACE
}
