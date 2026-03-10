package lol.ohai.regex.bench;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shared haystack data for benchmarks.
 * Loads text files from upstream/rebar/benchmarks/haystacks/.
 */
final class Haystacks {

    private static final Path HAYSTACKS = findHaystacksDir();

    static final String SHERLOCK_EN = load("opensubtitles/en-sampled.txt");
    static final String CLOUD_FLARE_REDOS = load("cloud-flare-redos.txt");

    /** 100 repetitions of 'A' for quadratic benchmarks. */
    static final String REPEAT_A_100 = "A".repeat(100);
    /** 1000 repetitions of 'A' for quadratic benchmarks. */
    static final String REPEAT_A_1000 = "A".repeat(1000);

    /** Mixed ASCII/Unicode text for Unicode-aware benchmarks. */
    static final String UNICODE_MIXED =
            "Hello world 42 δοκιμή 测试 1234 café naïve résumé über αβγ 日本語テスト "
                    .repeat(100);

    /** Text with embedded dates for capture group benchmarks. */
    static final String DATES_TEXT =
            ("Report filed on 2024-03-14 by agent 007. "
                    + "Updated 2025-01-30 and reviewed 2025-12-25. "
                    + "No dates here just filler text to pad the haystack. ")
                    .repeat(100);

    private Haystacks() {}

    private static String load(String relativePath) {
        Path file = HAYSTACKS.resolve(relativePath);
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot load haystack: " + file + " (run from project root)", e);
        }
    }

    private static Path findHaystacksDir() {
        // Try relative to working directory (project root)
        Path candidate = Path.of("upstream/rebar/benchmarks/haystacks");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        // Try one level up (running from regex-bench/)
        candidate = Path.of("../upstream/rebar/benchmarks/haystacks");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        throw new IllegalStateException(
                "Cannot find rebar haystacks directory. "
                        + "Ensure upstream/rebar submodule is initialized.");
    }
}
