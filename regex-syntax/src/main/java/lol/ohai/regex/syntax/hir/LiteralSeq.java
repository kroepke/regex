package lol.ohai.regex.syntax.hir;

import java.util.List;
import java.util.Objects;
import java.util.Arrays;

/**
 * Result of literal prefix extraction from an HIR tree.
 *
 * <p>Used by the meta engine to decide whether a prefilter can be used
 * and whether the regex engine can be skipped entirely.</p>
 */
public sealed interface LiteralSeq {

    /**
     * Whether this literal sequence covers the entire pattern,
     * meaning no regex engine is needed for matching.
     */
    default boolean coversEntirePattern() {
        return false;
    }

    /** No useful literals could be extracted. */
    record None() implements LiteralSeq {}

    /** A single literal prefix. */
    record Single(char[] literal, boolean entirePattern) implements LiteralSeq {
        public Single {
            Objects.requireNonNull(literal);
        }

        @Override
        public boolean coversEntirePattern() {
            return entirePattern;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Single s
                    && Arrays.equals(literal, s.literal)
                    && entirePattern == s.entirePattern;
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(literal) + Boolean.hashCode(entirePattern);
        }
    }

    /** Multiple alternative literal prefixes. */
    record Alternation(List<char[]> literals, boolean entirePattern) implements LiteralSeq {
        public Alternation {
            Objects.requireNonNull(literals);
            literals = List.copyOf(literals);
        }

        @Override
        public boolean coversEntirePattern() {
            return entirePattern;
        }
    }
}
