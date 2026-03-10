package lol.ohai.regex.syntax.ast;

/**
 * The available forms of Unicode character classes.
 */
public sealed interface ClassUnicodeKind {
    /** A one letter abbreviated class, e.g., {@code \pN}. */
    record OneLetter(char letter) implements ClassUnicodeKind {}

    /** A binary property, general category or script. */
    record Named(String name) implements ClassUnicodeKind {}

    /** A property name and an associated value. */
    record NamedValue(ClassUnicodeOpKind op, String name, String value) implements ClassUnicodeKind {}
}
