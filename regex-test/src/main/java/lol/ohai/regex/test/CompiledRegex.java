package lol.ohai.regex.test;

import java.util.function.Function;

public final class CompiledRegex {
    private final Function<RegexTest, TestResult> matcher;

    private CompiledRegex(Function<RegexTest, TestResult> matcher) {
        this.matcher = matcher;
    }

    public static CompiledRegex compiled(Function<RegexTest, TestResult> matcher) {
        return new CompiledRegex(matcher);
    }

    public static CompiledRegex skip() {
        return new CompiledRegex(t -> new TestResult.Skipped("unsupported"));
    }

    public TestResult run(RegexTest test) {
        return matcher.apply(test);
    }
}
