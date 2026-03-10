package lol.ohai.regex.bench;

import lol.ohai.regex.Regex;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Search throughput benchmarks comparing lol.ohai.regex against java.util.regex.
 *
 * <p>Each benchmark finds all matches of a pattern in a haystack, consuming the
 * results via a {@link Blackhole} to prevent dead-code elimination.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class SearchBenchmark {

    // --- Compiled patterns (set up once per trial) ---

    // Literal
    private Regex ohaiLiteral;
    private Pattern jdkLiteral;

    // Character class
    private Regex ohaiCharClass;
    private Pattern jdkCharClass;

    // Alternation
    private Regex ohaiAlternation;
    private Pattern jdkAlternation;

    // Captures (date extraction)
    private Regex ohaiDate;
    private Pattern jdkDate;

    // Unicode word
    private Regex ohaiUnicodeWord;
    private Pattern jdkUnicodeWord;

    @Setup(Level.Trial)
    public void setup() {
        ohaiLiteral = Regex.compile("Sherlock Holmes");
        jdkLiteral = Pattern.compile("Sherlock Holmes");

        ohaiCharClass = Regex.compile("[a-zA-Z]+");
        jdkCharClass = Pattern.compile("[a-zA-Z]+");

        ohaiAlternation = Regex.compile("Sherlock|Watson|Holmes|Irene");
        jdkAlternation = Pattern.compile("Sherlock|Watson|Holmes|Irene");

        ohaiDate = Regex.compile("(\\d{4})-(\\d{2})-(\\d{2})");
        jdkDate = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

        ohaiUnicodeWord = Regex.compile("\\w+");
        jdkUnicodeWord = Pattern.compile("\\w+", Pattern.UNICODE_CHARACTER_CLASS);
    }

    // ---- Literal: "Sherlock Holmes" in English text ----

    @Benchmark
    public void literalOhai(Blackhole bh) {
        ohaiLiteral.findAll(Haystacks.SHERLOCK_EN).forEach(bh::consume);
    }

    @Benchmark
    public void literalJdk(Blackhole bh) {
        Matcher m = jdkLiteral.matcher(Haystacks.SHERLOCK_EN);
        while (m.find()) {
            bh.consume(m.start());
        }
    }

    // ---- Character class: [a-zA-Z]+ ----

    @Benchmark
    public void charClassOhai(Blackhole bh) {
        ohaiCharClass.findAll(Haystacks.SHERLOCK_EN).forEach(bh::consume);
    }

    @Benchmark
    public void charClassJdk(Blackhole bh) {
        Matcher m = jdkCharClass.matcher(Haystacks.SHERLOCK_EN);
        while (m.find()) {
            bh.consume(m.start());
        }
    }

    // ---- Alternation: Sherlock|Watson|Holmes|Irene ----

    @Benchmark
    public void alternationOhai(Blackhole bh) {
        ohaiAlternation.findAll(Haystacks.SHERLOCK_EN).forEach(bh::consume);
    }

    @Benchmark
    public void alternationJdk(Blackhole bh) {
        Matcher m = jdkAlternation.matcher(Haystacks.SHERLOCK_EN);
        while (m.find()) {
            bh.consume(m.start());
        }
    }

    // ---- Captures: date extraction ----

    @Benchmark
    public void capturesOhai(Blackhole bh) {
        ohaiDate.capturesAll(Haystacks.DATES_TEXT).forEach(bh::consume);
    }

    @Benchmark
    public void capturesJdk(Blackhole bh) {
        Matcher m = jdkDate.matcher(Haystacks.DATES_TEXT);
        while (m.find()) {
            bh.consume(m.group(1));
            bh.consume(m.group(2));
            bh.consume(m.group(3));
        }
    }

    // ---- Unicode word: \w+ on mixed text ----

    @Benchmark
    public void unicodeWordOhai(Blackhole bh) {
        ohaiUnicodeWord.findAll(Haystacks.UNICODE_MIXED).forEach(bh::consume);
    }

    @Benchmark
    public void unicodeWordJdk(Blackhole bh) {
        Matcher m = jdkUnicodeWord.matcher(Haystacks.UNICODE_MIXED);
        while (m.find()) {
            bh.consume(m.start());
        }
    }
}
