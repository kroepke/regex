package lol.ohai.regex.test;

import java.util.Set;

public record EngineCapabilities(
    boolean supportsCaptures,
    boolean supportsUnicode,
    boolean supportsAnchored,
    Set<MatchKind> supportedMatchKinds,
    Set<SearchKind> supportedSearchKinds
) {
    /** Default capabilities for PikeVM. */
    public static EngineCapabilities pikeVm() {
        return new EngineCapabilities(
            true, true, true,
            Set.of(MatchKind.LEFTMOST_FIRST, MatchKind.ALL),
            Set.of(SearchKind.LEFTMOST, SearchKind.EARLIEST)
        );
    }

    /** Check if this engine can handle the given test. */
    public boolean supports(RegexTest test) {
        if (!supportsUnicode && test.unicode()) return false;
        if (!supportsAnchored && test.anchored()) return false;
        if (!supportedMatchKinds.contains(test.matchKind())) return false;
        if (!supportedSearchKinds.contains(test.searchKind())) return false;
        return true;
    }
}
