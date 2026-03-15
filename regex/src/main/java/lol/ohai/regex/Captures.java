package lol.ohai.regex;

import java.util.Map;
import java.util.Optional;

/**
 * Capture group results from a regex match.
 *
 * <p>Group 0 is always the overall match. Named groups can be accessed by name via
 * {@link #group(String)}. Unnamed groups are accessed by index via {@link #group(int)}.</p>
 */
public final class Captures {
    private final Match overall;
    private final Match[] groups;  // nulls for unmatched groups
    private final Map<String, Integer> namedGroups;

    Captures(Match overall, Match[] groups, Map<String, Integer> namedGroups) {
        this.overall = overall;
        this.groups = groups;
        this.namedGroups = namedGroups;
    }

    /** Returns the overall match (group 0). */
    public Match overall() {
        return overall;
    }

    /**
     * Returns the match for the given group index.
     *
     * @param index the group index (0 = overall match)
     * @return the match, or empty if the group did not participate
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public Optional<Match> group(int index) {
        return Optional.ofNullable(groups[index]);
    }

    /**
     * Returns the match for the given named group.
     *
     * @param name the group name
     * @return the match, or empty if the group did not participate
     * @throws IllegalArgumentException if no group with that name exists
     */
    public Optional<Match> group(String name) {
        Integer index = namedGroups.get(name);
        if (index == null) {
            throw new IllegalArgumentException("no group named: " + name);
        }
        return Optional.ofNullable(groups[index]);
    }

    /** Returns the number of capture groups (including group 0). */
    public int groupCount() {
        return groups.length;
    }

    /** Returns an unmodifiable map of group names to their indices. */
    public Map<String, Integer> namedGroups() {
        return namedGroups;
    }
}
