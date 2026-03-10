package lol.ohai.regex.syntax.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * A union of items inside a character class set.
 * This is a mutable builder used during parsing.
 */
public final class ClassSetUnion {
    Span span;
    final List<ClassSetItem> items;

    public ClassSetUnion(Span span) {
        this.span = span;
        this.items = new ArrayList<>();
    }

    public void push(ClassSetItem item) {
        if (items.isEmpty()) {
            span = new Span(item.span().start(), span.end());
        }
        span = new Span(span.start(), item.span().end());
        items.add(item);
    }

    /** Convert this union into a ClassSetItem. */
    public ClassSetItem intoItem() {
        return switch (items.size()) {
            case 0 -> new ClassSetItem.Empty(span);
            case 1 -> items.getFirst();
            default -> new ClassSetItem.Union(span, List.copyOf(items));
        };
    }

    public Span span() {
        return span;
    }

    public List<ClassSetItem> items() {
        return items;
    }
}
