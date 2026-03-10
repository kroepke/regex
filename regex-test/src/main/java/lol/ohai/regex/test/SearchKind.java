package lol.ohai.regex.test;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Describes what kind of search to execute.
 * Mirrors the upstream {@code SearchKind} enum in regex-test/lib.rs.
 */
public enum SearchKind {
    @JsonProperty("earliest") EARLIEST,
    @JsonProperty("leftmost") LEFTMOST,
    @JsonProperty("overlapping") OVERLAPPING
}
