package lol.ohai.regex.automata.dfa.lazy;

public sealed interface SearchResult {
    record Match(int offset) implements SearchResult {}
    record NoMatch() implements SearchResult {}
    record GaveUp(int offset) implements SearchResult {}

    // Primitive-encoded search results (avoids object allocation on hot path)
    long NO_MATCH = -1L;

    static long match(int offset) { return offset; }
    static long gaveUp(int offset) { return -(offset + 2L); }
    static boolean isMatch(long result) { return result >= 0; }
    static boolean isNoMatch(long result) { return result == NO_MATCH; }
    static boolean isGaveUp(long result) { return result <= -2; }
    static int matchOffset(long result) { return (int) result; }
    static int gaveUpOffset(long result) { return (int) (-(result + 2)); }
}
