package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.syntax.hir.LookKind;

/**
 * Look-behind context at a search start position.
 *
 * <p>Different start contexts produce different DFA start states because
 * look-behind assertions (e.g., {@code ^}, {@code \b}) may or may not be
 * satisfied depending on what precedes the search start position.</p>
 *
 * <p>The upstream Rust crate has a 6th variant {@code CustomLineTerminator}
 * for configurable line terminators. This will be added if/when the syntax
 * layer supports custom line terminators.</p>
 */
public enum Start {
    /** Position 0: start of input text. */
    TEXT,
    /** After {@code \n}: start of a new line (LF). */
    LINE_LF,
    /** After {@code \r}: possible start of CRLF pair. */
    LINE_CR,
    /** After a word character ({@code [0-9A-Za-z_]}). */
    WORD_BYTE,
    /** After a non-word character (or at a non-zero, non-line-terminator position). */
    NON_WORD_BYTE;

    /** Number of Start variants. Each × anchored/unanchored = one start state slot. */
    public static final int COUNT = values().length;

    /**
     * Classify the search start position to determine look-behind context.
     *
     * @param haystack the full haystack
     * @param pos      the search start position (0-based)
     * @return the Start variant for this position
     */
    public static Start from(char[] haystack, int pos) {
        if (pos == 0) return TEXT;
        char prev = haystack[pos - 1];
        if (prev == '\n') return LINE_LF;
        if (prev == '\r') return LINE_CR;
        if (isWordChar(prev)) return WORD_BYTE;
        return NON_WORD_BYTE;
    }

    /**
     * Returns the initial look-have set for this start variant.
     *
     * @param lookSetAny the pattern's full set of look-assertion kinds
     * @param reverse    true if this is a reverse NFA/DFA
     * @return the initial lookHave for a DFA start state with this context
     */
    public LookSet initialLookHave(LookSet lookSetAny, boolean reverse) {
        LookSet have = LookSet.EMPTY;
        switch (this) {
            case TEXT -> {
                if (lookSetAny.contains(LookKind.START_TEXT))
                    have = have.insert(LookKind.START_TEXT);
                if (lookSetAny.contains(LookKind.START_LINE))
                    have = have.insert(LookKind.START_LINE);
                if (lookSetAny.contains(LookKind.START_LINE_CRLF))
                    have = have.insert(LookKind.START_LINE_CRLF);
                if (lookSetAny.containsWord()) {
                    have = have.insert(LookKind.WORD_START_HALF_ASCII)
                               .insert(LookKind.WORD_START_HALF_UNICODE);
                }
            }
            case LINE_LF -> {
                if (lookSetAny.contains(LookKind.START_LINE))
                    have = have.insert(LookKind.START_LINE);
                if (lookSetAny.contains(LookKind.START_LINE_CRLF))
                    have = have.insert(LookKind.START_LINE_CRLF);
                if (lookSetAny.containsWord()) {
                    have = have.insert(LookKind.WORD_START_HALF_ASCII)
                               .insert(LookKind.WORD_START_HALF_UNICODE);
                }
            }
            case LINE_CR -> {
                if (lookSetAny.contains(LookKind.START_LINE_CRLF))
                    have = have.insert(LookKind.START_LINE_CRLF);
                if (lookSetAny.containsWord()) {
                    have = have.insert(LookKind.WORD_START_HALF_ASCII)
                               .insert(LookKind.WORD_START_HALF_UNICODE);
                }
            }
            case WORD_BYTE -> {
                // After word char: no start-half assertions, isFromWord handled separately
            }
            case NON_WORD_BYTE -> {
                if (lookSetAny.containsWord()) {
                    have = have.insert(LookKind.WORD_START_HALF_ASCII)
                               .insert(LookKind.WORD_START_HALF_UNICODE);
                }
            }
        }
        return have;
    }

    /** Returns true if this start context has isFromWord = true. */
    public boolean isFromWord() {
        return this == WORD_BYTE;
    }

    /**
     * Returns true if this start context has isHalfCrlf = true.
     * Direction-dependent: LINE_CR in forward mode, LINE_LF in reverse mode.
     */
    public boolean isHalfCrlf(boolean reverse) {
        return reverse ? this == LINE_LF : this == LINE_CR;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }
}
