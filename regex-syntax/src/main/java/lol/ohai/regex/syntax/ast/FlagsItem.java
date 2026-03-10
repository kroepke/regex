package lol.ohai.regex.syntax.ast;

/**
 * A single item in a group of flags.
 */
public record FlagsItem(Span span, FlagsItemKind kind) {}
