package lol.ohai.regex.syntax.ast;

/**
 * A character class set.
 *
 * Every bracketed character class is one of two types: a union of items
 * or a tree of binary set operations.
 */
public sealed interface ClassSet {
    Span span();

    /** An item, which can be a single literal, range, nested class or a union. */
    record Item(ClassSetItem item) implements ClassSet {
        @Override
        public Span span() {
            return item.span();
        }
    }

    /** A single binary operation (&&, -- or ~~). */
    record BinaryOp(Span span, ClassSetBinaryOpKind kind, ClassSet lhs, ClassSet rhs) implements ClassSet {}

    /** Build a set from a union. */
    static ClassSet union(ClassSetUnion union) {
        return new Item(union.intoItem());
    }

    default boolean isEmpty() {
        return this instanceof Item i && i.item() instanceof ClassSetItem.Empty;
    }
}
