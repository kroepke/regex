package lol.ohai.regex.bench;

import lol.ohai.regex.Regex;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apples-to-apples engine benchmark: both JDK and ohai consume only int offsets,
 * with zero per-match allocation on both sides.
 *
 * <p>Uses {@link Regex.Searcher} (analogous to JDK's {@code Matcher}) to iterate
 * matches via {@code find()} + {@code start()}/{@code end()}, avoiding the
 * {@link lol.ohai.regex.Match} record and substring allocation that dominate
 * the existing {@link SearchBenchmark}.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class RawEngineBenchmark {

    private Regex ohaiCharClass;
    private Regex ohaiAlternation;
    private Regex ohaiMultiline;
    private Regex ohaiUnicodeWord;
    private Regex ohaiLiteral;
    private Regex ohaiLiteralMiss;

    private Pattern jdkCharClass;
    private Pattern jdkAlternation;
    private Pattern jdkMultiline;
    private Pattern jdkUnicodeWord;
    private Pattern jdkLiteral;
    private Pattern jdkLiteralMiss;

    @Setup(Level.Trial)
    public void setup() {
        ohaiCharClass = Regex.compile("[a-zA-Z]+");
        ohaiAlternation = Regex.compile("Sherlock|Watson|Holmes|Irene");
        ohaiMultiline = Regex.compile("(?m)^.+$");
        ohaiUnicodeWord = Regex.compile("\\w+");
        ohaiLiteral = Regex.compile("Sherlock Holmes");
        ohaiLiteralMiss = Regex.compile("ZQZQZQZQ");

        jdkCharClass = Pattern.compile("[a-zA-Z]+");
        jdkAlternation = Pattern.compile("Sherlock|Watson|Holmes|Irene");
        jdkMultiline = Pattern.compile("(?m)^.+$");
        jdkUnicodeWord = Pattern.compile("\\w+", Pattern.UNICODE_CHARACTER_CLASS);
        jdkLiteral = Pattern.compile("Sherlock Holmes");
        jdkLiteralMiss = Pattern.compile("ZQZQZQZQ");
    }

    // ---- charClass: [a-zA-Z]+ ----

    @Benchmark
    public void rawCharClassOhai(Blackhole bh) {
        Regex.Searcher s = ohaiCharClass.searcher(Haystacks.SHERLOCK_EN);
        while (s.find()) { bh.consume(s.start()); }
    }

    @Benchmark
    public void rawCharClassJdk(Blackhole bh) {
        Matcher m = jdkCharClass.matcher(Haystacks.SHERLOCK_EN);
        while (m.find()) { bh.consume(m.start()); }
    }

    // ---- alternation: Sherlock|Watson|Holmes|Irene ----

    @Benchmark
    public void rawAlternationOhai(Blackhole bh) {
        Regex.Searcher s = ohaiAlternation.searcher(Haystacks.SHERLOCK_EN);
        while (s.find()) { bh.consume(s.start()); }
    }

    @Benchmark
    public void rawAlternationJdk(Blackhole bh) {
        Matcher m = jdkAlternation.matcher(Haystacks.SHERLOCK_EN);
        while (m.find()) { bh.consume(m.start()); }
    }

    // ---- multiline: (?m)^.+$ ----

    @Benchmark
    public void rawMultilineOhai(Blackhole bh) {
        Regex.Searcher s = ohaiMultiline.searcher(Haystacks.SHERLOCK_EN);
        while (s.find()) { bh.consume(s.start()); }
    }

    @Benchmark
    public void rawMultilineJdk(Blackhole bh) {
        Matcher m = jdkMultiline.matcher(Haystacks.SHERLOCK_EN);
        while (m.find()) { bh.consume(m.start()); }
    }

    // ---- unicodeWord: \w+ ----

    @Benchmark
    public void rawUnicodeWordOhai(Blackhole bh) {
        Regex.Searcher s = ohaiUnicodeWord.searcher(Haystacks.UNICODE_MIXED);
        while (s.find()) { bh.consume(s.start()); }
    }

    @Benchmark
    public void rawUnicodeWordJdk(Blackhole bh) {
        Matcher m = jdkUnicodeWord.matcher(Haystacks.UNICODE_MIXED);
        while (m.find()) { bh.consume(m.start()); }
    }

    // ---- literal: "Sherlock Holmes" ----

    @Benchmark
    public void rawLiteralOhai(Blackhole bh) {
        Regex.Searcher s = ohaiLiteral.searcher(Haystacks.SHERLOCK_EN);
        while (s.find()) { bh.consume(s.start()); }
    }

    @Benchmark
    public void rawLiteralJdk(Blackhole bh) {
        Matcher m = jdkLiteral.matcher(Haystacks.SHERLOCK_EN);
        while (m.find()) { bh.consume(m.start()); }
    }

    // ---- literalMiss: ZQZQZQZQ ----

    @Benchmark
    public void rawLiteralMissOhai(Blackhole bh) {
        Regex.Searcher s = ohaiLiteralMiss.searcher(Haystacks.SHERLOCK_EN);
        while (s.find()) { bh.consume(s.start()); }
    }

    @Benchmark
    public void rawLiteralMissJdk(Blackhole bh) {
        Matcher m = jdkLiteralMiss.matcher(Haystacks.SHERLOCK_EN);
        while (m.find()) { bh.consume(m.start()); }
    }
}
