package lol.ohai.regex.test;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RegexTestSuiteTest {

    @Test
    void loadMiscToml() throws Exception {
        var suite = new RegexTestSuite();
        suite.load(Path.of("../upstream/regex/testdata/misc.toml"));
        assertFalse(suite.tests().isEmpty());
        assertEquals("misc", suite.tests().getFirst().groupName());
    }

    @Test
    void loadAllTestData() throws Exception {
        var suite = RegexTestSuite.loadAll(Path.of("../upstream/regex/testdata"));
        assertTrue(suite.tests().size() > 100,
            "Expected >100 tests, got " + suite.tests().size());
    }
}
