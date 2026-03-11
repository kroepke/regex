package lol.ohai.regex.automata.dfa.lazy;

public sealed interface SearchResult {
    record Match(int end) implements SearchResult {}
    record NoMatch() implements SearchResult {}
    record GaveUp(int offset) implements SearchResult {}
}
