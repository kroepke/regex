package lol.ohai.regex.syntax.ast;

/**
 * The kind of a single literal expression.
 */
public sealed interface LiteralKind {
    /** The literal is written verbatim, e.g., {@code a} or {@code ☃}. */
    record Verbatim() implements LiteralKind {}

    /** The literal is written as an escape of a meta character, e.g., {@code \*}. */
    record Meta() implements LiteralKind {}

    /** The literal is an unnecessary escape, e.g., {@code \%}. */
    record Superfluous() implements LiteralKind {}

    /** The literal is written as an octal escape, e.g., {@code \141}. */
    record Octal() implements LiteralKind {}

    /** The literal is written as a hex code with a fixed number of digits. */
    record HexFixed(HexLiteralKind hexKind) implements LiteralKind {}

    /** The literal is written as a hex code with a bracketed number of digits. */
    record HexBrace(HexLiteralKind hexKind) implements LiteralKind {}

    /** The literal is written as a special escape sequence, e.g., {@code \n}. */
    record Special(SpecialLiteralKind specialKind) implements LiteralKind {}

    // Singleton instances for convenience
    LiteralKind VERBATIM = new Verbatim();
    LiteralKind META = new Meta();
    LiteralKind SUPERFLUOUS = new Superfluous();
    LiteralKind OCTAL = new Octal();
}
