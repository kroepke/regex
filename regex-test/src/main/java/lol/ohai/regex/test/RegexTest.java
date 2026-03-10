package lol.ohai.regex.test;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import java.util.List;

/**
 * A single regex test case deserialized from a {@code [[test]]} TOML block.
 *
 * <p>Mirrors the upstream {@code RegexTest} struct in regex-test/lib.rs.
 * Uses a mutable POJO (not a record) so that Jackson can apply default values
 * for missing fields.
 */
public class RegexTest {

    private String name;

    /** Set after deserialization, derived from the TOML file stem. */
    private String groupName;

    /**
     * Raw regex value — either a single {@link String} or a {@link List} of strings
     * for multi-pattern tests. Use {@link #regexes()} to always get a list.
     */
    @JsonProperty("regex")
    private Object regexRaw;

    private String haystack;

    /**
     * Optional search bounds. In TOML, serialized as {@code [start, end]}.
     * If absent, the bounds default to the full haystack.
     */
    private Span bounds;

    @JsonSetter(nulls = Nulls.SKIP)
    private List<Captures> matches = List.of();

    @JsonProperty("match-limit")
    private Integer matchLimit;

    private boolean compiles = true;
    private boolean anchored = false;

    @JsonProperty("case-insensitive")
    private boolean caseInsensitive = false;

    private boolean unescape = false;
    private boolean unicode = true;
    private boolean utf8 = true;

    @JsonProperty("line-terminator")
    private String lineTerminator = "\n";

    @JsonProperty("match-kind")
    private MatchKind matchKind = MatchKind.LEFTMOST_FIRST;

    @JsonProperty("search-kind")
    private SearchKind searchKind = SearchKind.LEFTMOST;

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String groupName() {
        return groupName;
    }

    /** Sets the group name after deserialization (derived from TOML file stem). */
    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    /**
     * Returns the full test name as {@code groupName/name}, or just {@code name}
     * if no group name has been set.
     */
    public String fullName() {
        return groupName != null ? groupName + "/" + name : name;
    }

    /**
     * Returns the regex patterns as a list.
     *
     * <p>The TOML {@code regex} field can be either a single string or a list of strings.
     * This method always returns a {@link List}, normalizing the single-string case.
     */
    @SuppressWarnings("unchecked")
    public List<String> regexes() {
        if (regexRaw instanceof String s) {
            return List.of(s);
        } else if (regexRaw instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    public String haystack() {
        return haystack;
    }

    public void setHaystack(String haystack) {
        this.haystack = haystack;
    }

    public Span bounds() {
        return bounds;
    }

    public void setBounds(Span bounds) {
        this.bounds = bounds;
    }

    public List<Captures> matches() {
        return matches;
    }

    public void setMatches(List<Captures> matches) {
        this.matches = matches != null ? matches : List.of();
    }

    public Integer matchLimit() {
        return matchLimit;
    }

    public void setMatchLimit(Integer matchLimit) {
        this.matchLimit = matchLimit;
    }

    public boolean compiles() {
        return compiles;
    }

    public void setCompiles(boolean compiles) {
        this.compiles = compiles;
    }

    public boolean anchored() {
        return anchored;
    }

    public void setAnchored(boolean anchored) {
        this.anchored = anchored;
    }

    public boolean caseInsensitive() {
        return caseInsensitive;
    }

    public void setCaseInsensitive(boolean caseInsensitive) {
        this.caseInsensitive = caseInsensitive;
    }

    public boolean unescape() {
        return unescape;
    }

    public void setUnescape(boolean unescape) {
        this.unescape = unescape;
    }

    public boolean unicode() {
        return unicode;
    }

    public void setUnicode(boolean unicode) {
        this.unicode = unicode;
    }

    public boolean utf8() {
        return utf8;
    }

    public void setUtf8(boolean utf8) {
        this.utf8 = utf8;
    }

    public String lineTerminator() {
        return lineTerminator;
    }

    public void setLineTerminator(String lineTerminator) {
        this.lineTerminator = lineTerminator != null ? lineTerminator : "\n";
    }

    public MatchKind matchKind() {
        return matchKind;
    }

    public void setMatchKind(MatchKind matchKind) {
        this.matchKind = matchKind != null ? matchKind : MatchKind.LEFTMOST_FIRST;
    }

    public SearchKind searchKind() {
        return searchKind;
    }

    public void setSearchKind(SearchKind searchKind) {
        this.searchKind = searchKind != null ? searchKind : SearchKind.LEFTMOST;
    }

    @Override
    public String toString() {
        return "RegexTest{name='" + fullName() + "', regex=" + regexes() + "}";
    }
}
