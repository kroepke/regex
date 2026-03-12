package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.syntax.hir.LookKind;

/**
 * Immutable 32-bit bitset over {@link LookKind} values.
 *
 * <p>Each {@code LookKind} maps to one bit via {@link LookKind#asBit()}.
 * All operations return new instances — {@code LookSet} is a value type.</p>
 */
public record LookSet(int bits) {
    public static final LookSet EMPTY = new LookSet(0);

    public static LookSet of(LookKind k) {
        return new LookSet(k.asBit());
    }

    public LookSet insert(LookKind k) {
        return new LookSet(bits | k.asBit());
    }

    public boolean contains(LookKind k) {
        return (bits & k.asBit()) != 0;
    }

    public LookSet union(LookSet other) {
        return new LookSet(bits | other.bits);
    }

    public LookSet intersect(LookSet other) {
        return new LookSet(bits & other.bits);
    }

    public LookSet subtract(LookSet other) {
        return new LookSet(bits & ~other.bits);
    }

    public boolean isEmpty() {
        return bits == 0;
    }

    /** Returns true if this set contains any word boundary assertion. */
    public boolean containsWord() {
        return contains(LookKind.WORD_BOUNDARY_ASCII)
                || contains(LookKind.WORD_BOUNDARY_ASCII_NEGATE)
                || contains(LookKind.WORD_BOUNDARY_UNICODE)
                || contains(LookKind.WORD_BOUNDARY_UNICODE_NEGATE)
                || contains(LookKind.WORD_START_ASCII)
                || contains(LookKind.WORD_END_ASCII)
                || contains(LookKind.WORD_START_HALF_ASCII)
                || contains(LookKind.WORD_END_HALF_ASCII)
                || contains(LookKind.WORD_START_UNICODE)
                || contains(LookKind.WORD_END_UNICODE)
                || contains(LookKind.WORD_START_HALF_UNICODE)
                || contains(LookKind.WORD_END_HALF_UNICODE);
    }

    /** Returns true if this set contains any CRLF-aware anchor. */
    public boolean containsCrlf() {
        return contains(LookKind.START_LINE_CRLF)
                || contains(LookKind.END_LINE_CRLF);
    }

    /** Returns true if this set contains any Unicode (non-ASCII) word boundary assertion. */
    public boolean containsUnicodeWord() {
        return contains(LookKind.WORD_BOUNDARY_UNICODE)
                || contains(LookKind.WORD_BOUNDARY_UNICODE_NEGATE)
                || contains(LookKind.WORD_START_UNICODE)
                || contains(LookKind.WORD_END_UNICODE)
                || contains(LookKind.WORD_START_HALF_UNICODE)
                || contains(LookKind.WORD_END_HALF_UNICODE);
    }

    /**
     * Returns true if this set contains any look-assertion kind that the
     * lazy DFA cannot handle.  The DFA only has ASCII word-char tables, so
     * Unicode word boundaries and special Unicode word-start/end kinds are
     * unsupported.  CRLF-aware line anchors are also unsupported.
     */
    public boolean containsUnsupportedByDFA() {
        return containsUnicodeWord() || containsCrlf();
    }

    /**
     * Returns true if this set contains look-assertion kinds that require
     * the DFA to bail out entirely. After quit-char support, only CRLF
     * line anchors cause full bail-out. Unicode word boundaries are handled
     * via quit chars instead.
     */
    public boolean containsBailOutByDFA() {
        return containsCrlf();
    }
}
