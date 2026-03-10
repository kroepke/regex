package lol.ohai.regex.test;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Top-level wrapper for a TOML test file containing {@code [[test]]} entries.
 *
 * <p>Deserialize with {@link com.fasterxml.jackson.dataformat.toml.TomlMapper}:
 * <pre>{@code
 * RegexTestFile file = mapper.readValue(tomlFile, RegexTestFile.class);
 * }</pre>
 */
public record RegexTestFile(@JsonProperty("test") List<RegexTest> tests) {

    public RegexTestFile {
        if (tests == null) tests = List.of();
    }
}
