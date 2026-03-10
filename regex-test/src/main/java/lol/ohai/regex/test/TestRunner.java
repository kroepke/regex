package lol.ohai.regex.test;

import org.junit.jupiter.api.DynamicTest;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Generates JUnit 6 DynamicTests from the upstream TOML test suite.
 */
public final class TestRunner {

    private TestRunner() {}

    public static Stream<DynamicTest> run(
            RegexTestSuite suite,
            EngineCapabilities capabilities,
            Function<RegexTest, CompiledRegex> compiler) {

        return suite.tests().stream().map(test -> {
            String displayName = test.groupName() + "/" + test.name();
            return DynamicTest.dynamicTest(displayName, () -> {
                if (!capabilities.supports(test)) {
                    return; // skip unsupported
                }
                CompiledRegex compiled;
                try {
                    compiled = compiler.apply(test);
                } catch (Exception e) {
                    if (!test.compiles()) {
                        return; // expected compilation failure
                    }
                    throw e;
                }
                if (!test.compiles()) {
                    fail("Expected compilation to fail for: " + test.regexes());
                }
                TestResult result = compiled.run(test);
                assertResult(test, result);
            });
        });
    }

    private static void assertResult(RegexTest test, TestResult result) {
        switch (result) {
            case TestResult.Skipped s -> { /* OK */ }
            case TestResult.Failed f ->
                fail("Test failed: " + f.reason());
            case TestResult.Matched m ->
                assertMatched(test, m);
            case TestResult.Matches m ->
                assertMatchSpans(test, m);
            case TestResult.CaptureResults c ->
                assertCaptures(test, c);
        }
    }

    private static void assertMatched(RegexTest test, TestResult.Matched result) {
        boolean expected = !test.matches().isEmpty();
        assertEquals(expected, result.matches(),
            "match expectation mismatch for pattern: " + test.regexes());
    }

    private static void assertMatchSpans(RegexTest test, TestResult.Matches result) {
        List<Captures> expected = applyMatchLimit(test);
        List<Span> expectedSpans = expected.stream().map(Captures::span).toList();
        assertEquals(expectedSpans, result.spans(),
            "match spans mismatch for pattern: " + test.regexes());
    }

    private static void assertCaptures(RegexTest test, TestResult.CaptureResults result) {
        List<Captures> expected = applyMatchLimit(test);
        assertEquals(expected.size(), result.captures().size(),
            "capture count mismatch for pattern: " + test.regexes());
        for (int i = 0; i < expected.size(); i++) {
            Captures exp = expected.get(i);
            Captures act = result.captures().get(i);
            assertEquals(exp.id(), act.id(),
                "pattern id mismatch at match " + i);
            assertEquals(exp.groups(), act.groups(),
                "capture groups mismatch at match " + i);
        }
    }

    private static List<Captures> applyMatchLimit(RegexTest test) {
        List<Captures> matches = test.matches();
        if (test.matchLimit() != null && test.matchLimit() < matches.size()) {
            return matches.subList(0, test.matchLimit());
        }
        return matches;
    }
}
