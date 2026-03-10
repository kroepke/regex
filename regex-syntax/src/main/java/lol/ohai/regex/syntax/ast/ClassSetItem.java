package lol.ohai.regex.syntax.ast;

import java.util.List;

/**
 * A single component of a character class set.
 */
public sealed interface ClassSetItem {
    Span span();

    /** An empty item. */
    record Empty(Span span) implements ClassSetItem {}

    /** A single literal. */
    record Literal(Span span, LiteralKind kind, char c) implements ClassSetItem {}

    /** A range between two literals. */
    record Range(Span span, Literal start, Literal end) implements ClassSetItem {
        public boolean isValid() {
            return start.c() <= end.c();
        }
    }

    /** An ASCII character class, e.g., {@code [:alnum:]}. */
    record Ascii(Span span, ClassAsciiKind kind, boolean negated) implements ClassSetItem {}

    /** A Unicode character class, e.g., {@code \pL}. */
    record Unicode(Span span, boolean negated, ClassUnicodeKind kind) implements ClassSetItem {}

    /** A Perl character class, e.g., {@code \d}. */
    record Perl(Span span, ClassPerlKind kind, boolean negated) implements ClassSetItem {}

    /** A bracketed character class set. */
    record Bracketed(Span span, boolean negated, ClassSet kind) implements ClassSetItem {}

    /** A union of items. */
    record Union(Span span, List<ClassSetItem> items) implements ClassSetItem {}
}
