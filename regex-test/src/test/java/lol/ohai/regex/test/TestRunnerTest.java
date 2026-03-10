package lol.ohai.regex.test;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class TestRunnerTest {

    @TestFactory
    Stream<DynamicTest> mockEnginePassesSimpleTests() {
        var suite = new RegexTestSuite();
        RegexTest test = RegexTest.forTest("basic", "a", "a", List.of(
            new Captures(0, List.of(Optional.of(new Span(0, 1))))
        ));
        suite.add(test);

        // Mock engine that always returns the expected match
        Function<RegexTest, CompiledRegex> compiler = t ->
            CompiledRegex.compiled(rt ->
                new TestResult.Matches(List.of(new Span(0, 1)))
            );

        return Stream.of(
            DynamicTest.dynamicTest("mock passes", () -> {
                CompiledRegex compiled = compiler.apply(test);
                TestResult result = compiled.run(test);
                assertInstanceOf(TestResult.Matches.class, result);
            })
        );
    }

    @TestFactory
    Stream<DynamicTest> runnerSkipsUnsupportedTests() {
        var suite = new RegexTestSuite();
        // Create a test that requires OVERLAPPING search kind
        RegexTest test = RegexTest.forTest("overlapping", "a", "a", List.of());
        test.setSearchKind(SearchKind.OVERLAPPING);
        suite.add(test);

        // PikeVM doesn't support OVERLAPPING
        EngineCapabilities caps = EngineCapabilities.pikeVm();
        Function<RegexTest, CompiledRegex> compiler = t ->
            CompiledRegex.compiled(rt -> new TestResult.Matches(List.of()));

        return TestRunner.run(suite, caps, compiler);
    }

    @TestFactory
    Stream<DynamicTest> runnerDetectsMatchMismatch() {
        // Verify assertResult via the runner
        var suite = new RegexTestSuite();
        RegexTest test = RegexTest.forTest("span-check", "a", "a", List.of(
            new Captures(0, List.of(Optional.of(new Span(0, 1))))
        ));
        suite.add(test);

        EngineCapabilities caps = EngineCapabilities.pikeVm();
        Function<RegexTest, CompiledRegex> compiler = t ->
            CompiledRegex.compiled(rt ->
                new TestResult.Matches(List.of(new Span(0, 1)))
            );

        return TestRunner.run(suite, caps, compiler);
    }
}
