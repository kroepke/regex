package lol.ohai.regex.syntax.hir;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A high-level intermediate representation (HIR) for a regular expression.
 *
 * <p>The HIR is a simplified, normalized form of the AST. Unicode properties
 * are resolved, character classes are flattened and sorted, and flags are
 * applied. The HIR is what the NFA compiler consumes.
 */
public sealed interface Hir {

    /** An empty regex that matches the empty string. */
    record Empty() implements Hir {}

    /**
     * A literal byte sequence (UTF-8 encoded).
     *
     * <p>We override equals/hashCode because the default record implementation
     * uses reference equality for byte arrays.
     */
    record Literal(byte[] bytes) implements Hir {
        public Literal {
            Objects.requireNonNull(bytes);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Literal lit && Arrays.equals(bytes, lit.bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "Literal" + Arrays.toString(bytes);
        }
    }

    /** A character class (Unicode codepoint ranges). */
    record Class(ClassUnicode unicode) implements Hir {}

    /** A byte-oriented character class. */
    record ClassB(ClassBytes bytes) implements Hir {}

    /** A zero-width assertion. */
    record Look(LookKind look) implements Hir {}

    /** A repetition of a sub-expression. */
    record Repetition(int min, int max, boolean greedy, Hir sub) implements Hir {
        /** Sentinel value indicating unbounded repetition. */
        public static final int UNBOUNDED = Integer.MAX_VALUE;
    }

    /** A capturing group. */
    record Capture(int index, String name, Hir sub) implements Hir {}

    /** A concatenation of sub-expressions. */
    record Concat(List<Hir> subs) implements Hir {
        public Concat {
            subs = List.copyOf(subs);
        }
    }

    /** An alternation of sub-expressions. */
    record Alternation(List<Hir> subs) implements Hir {
        public Alternation {
            subs = List.copyOf(subs);
        }
    }
}
