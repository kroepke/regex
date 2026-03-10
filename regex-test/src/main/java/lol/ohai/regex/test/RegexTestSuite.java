package lol.ohai.regex.test;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Collection of regex tests loaded from upstream TOML files.
 */
public final class RegexTestSuite {
    private final List<RegexTest> tests = new ArrayList<>();
    private static final TomlMapper MAPPER = new TomlMapper();

    /** Load tests from a single TOML file. Group name derived from filename. */
    public void load(Path path) throws IOException {
        String groupName = pathToGroupName(path);
        RegexTestFile file = MAPPER.readValue(path.toFile(), RegexTestFile.class);
        for (RegexTest test : file.tests()) {
            test.setGroupName(groupName);
            tests.add(test);
        }
    }

    /** Load all .toml files from a directory. */
    public static RegexTestSuite loadAll(Path directory) throws IOException {
        RegexTestSuite suite = new RegexTestSuite();
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> tomlFiles = files
                .filter(p -> p.toString().endsWith(".toml"))
                .sorted()
                .toList();
            for (Path p : tomlFiles) {
                suite.load(p);
            }
        }
        return suite;
    }

    public List<RegexTest> tests() {
        return Collections.unmodifiableList(tests);
    }

    /** Add a test programmatically (for unit testing). */
    public void add(RegexTest test) {
        tests.add(test);
    }

    private static String pathToGroupName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(0, dot) : name;
    }
}
