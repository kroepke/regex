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
     * Whether the extracted literals are exact (i.e., they represent the complete
     * prefix of the pattern with no approximation). Prefix extraction always
     * produces exact literals; suffix or inner extraction may not.
     */
    default boolean exact() {
        return false;
    }

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
    record Single(char[] literal, boolean exact, boolean entirePattern) implements LiteralSeq {
        public Single {
            Objects.requireNonNull(literal);
        }

        @Override
        public boolean exact() {
            return exact;
        }

        @Override
        public boolean coversEntirePattern() {
            return exact && entirePattern;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Single s
                    && Arrays.equals(literal, s.literal)
                    && exact == s.exact
                    && entirePattern == s.entirePattern;
        }

        @Override
        public int hashCode() {
            int h = 31 * Arrays.hashCode(literal) + Boolean.hashCode(exact);
            return 31 * h + Boolean.hashCode(entirePattern);
        }
    }

    /** Multiple alternative literal prefixes. */
    record Alternation(List<char[]> literals, boolean exact, boolean entirePattern) implements LiteralSeq {
        public Alternation {
            Objects.requireNonNull(literals);
            literals = List.copyOf(literals);
        }

        @Override
        public boolean exact() {
            return exact;
        }

        @Override
        public boolean coversEntirePattern() {
            return exact && entirePattern;
        }
    }
}
