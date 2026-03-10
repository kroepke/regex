package lol.ohai.regex.syntax.ast;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A group of flags.
 *
 * This corresponds to the sequence of flags themselves, e.g., {@code is-u}.
 */
public final class FlagItems {
    private final Span span;
    private final List<FlagsItem> items;

    public FlagItems(Span span, List<FlagsItem> items) {
        this.span = span;
        this.items = items;
    }

    public FlagItems(Span span) {
        this(span, new ArrayList<>());
    }

    public Span span() {
        return span;
    }

    public List<FlagsItem> items() {
        return items;
    }

    /**
     * Add the given item. Returns the index of a duplicate if one exists, otherwise -1.
     */
    public int addItem(FlagsItem item) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).kind().equals(item.kind())) {
                return i;
            }
        }
        items.add(item);
        return -1;
    }

    /**
     * Returns the state of the given flag.
     * Returns {@code Optional.of(true)} if present and not negated,
     * {@code Optional.of(false)} if present and negated,
     * {@code Optional.empty()} if not present.
     */
    public Optional<Boolean> flagState(Flag flag) {
        boolean negated = false;
        for (FlagsItem item : items) {
            switch (item.kind()) {
                case FlagsItemKind.Negation() -> negated = true;
                case FlagsItemKind.FlagKind(Flag f) -> {
                    if (f == flag) return Optional.of(!negated);
                }
            }
        }
        return Optional.empty();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
