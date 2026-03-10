package lol.ohai.regex.test;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;
import java.util.Optional;

/**
 * Represents the capture groups of a single match.
 *
 * <p>The {@code id} is the pattern ID (always 0 for single-pattern regexes).
 * The {@code groups} list contains one entry per capture group, where group 0
 * is the overall match span. A group value of {@link Optional#empty()} indicates
 * a non-participating group (spelled {@code []} in TOML).
 *
 * <p>Mirrors the upstream {@code Captures} type in regex-test/lib.rs.
 */
@JsonDeserialize(using = CapturesDeserializer.class)
public record Captures(int id, List<Optional<Span>> groups) {

    /**
     * Returns the overall match span (group 0).
     *
     * @throws IllegalStateException if group 0 is not present (should never happen in valid tests)
     */
    public Span span() {
        return groups.getFirst().orElseThrow(
                () -> new IllegalStateException("group 0 must always be present"));
    }
}
