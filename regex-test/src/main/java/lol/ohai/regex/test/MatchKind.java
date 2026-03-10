package lol.ohai.regex.test;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Describes which matches should be reported for a given regex search.
 * Mirrors the upstream {@code MatchKind} enum in regex-test/lib.rs.
 */
public enum MatchKind {
    @JsonProperty("all") ALL,
    @JsonProperty("leftmost-first") LEFTMOST_FIRST,
    @JsonProperty("leftmost-longest") LEFTMOST_LONGEST
}
