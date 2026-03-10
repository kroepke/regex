package lol.ohai.regex.syntax.ast;

import java.util.List;

/**
 * An abstract syntax tree for a single regular expression.
 */
public sealed interface Ast {

    /** Return the span of this AST node. */
    Span span();

    // --- Variants ---

    /** An empty regex that matches everything. */
    record Empty(Span span) implements Ast {}

    /** A set of flags, e.g., {@code (?is)}. */
    record Flags(Span span, FlagItems flags) implements Ast {}

    /** A single character literal, including escape sequences. */
    record Literal(Span span, LiteralKind kind, char c) implements Ast {}

    /** The "any character" class ({@code .}). */
    record Dot(Span span) implements Ast {}

    /** A single zero-width assertion. */
    record Assertion(Span span, AssertionKind kind) implements Ast {}

    /** A single Unicode character class, e.g., {@code \pL} or {@code \p{Greek}}. */
    record ClassUnicode(Span span, boolean negated, ClassUnicodeKind kind) implements Ast {}

    /** A single Perl character class, e.g., {@code \d} or {@code \W}. */
    record ClassPerl(Span span, ClassPerlKind kind, boolean negated) implements Ast {}

    /** A bracketed character class set, e.g., {@code [a-zA-Z\pL]}. */
    record ClassBracketed(Span span, boolean negated, ClassSet kind) implements Ast {}

    /** A repetition operator applied to an arbitrary regular expression. */
    record Repetition(Span span, RepetitionOp op, boolean greedy, Ast ast) implements Ast {}

    /** A grouped regular expression. */
    record Group(Span span, GroupKind kind, Ast ast) implements Ast {}

    /** An alternation of regular expressions. */
    record Alternation(Span span, List<Ast> asts) implements Ast {}

    /** A concatenation of regular expressions. */
    record Concat(Span span, List<Ast> asts) implements Ast {}
}
