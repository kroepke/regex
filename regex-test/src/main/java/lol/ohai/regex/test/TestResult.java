package lol.ohai.regex.test;

import java.util.List;

public sealed interface TestResult {
    /** Engine reports whether there was a match at all. */
    record Matched(boolean matches) implements TestResult {}
    /** Engine reports match spans (no capture groups). */
    record Matches(List<Span> spans) implements TestResult {}
    /** Engine reports full captures. */
    record CaptureResults(List<Captures> captures) implements TestResult {}
    /** Engine chose to skip this test. */
    record Skipped(String reason) implements TestResult {}
    /** Engine failed. */
    record Failed(String reason) implements TestResult {}
}
