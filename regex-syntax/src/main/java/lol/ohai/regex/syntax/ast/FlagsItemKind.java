package lol.ohai.regex.syntax.ast;

/**
 * The kind of an item in a group of flags.
 */
public sealed interface FlagsItemKind {
    record Negation() implements FlagsItemKind {}
    record FlagKind(Flag flag) implements FlagsItemKind {}

    FlagsItemKind NEGATION = new Negation();
}
