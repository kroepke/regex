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
 *
 * <h3>Benchmark parameter design</h3>
 *
 * <p>Parameters are tiered by throughput to ensure adequate C2 warmup (≥10K
 * invocations) before measurement begins. C2 needs ~10K method invocations
 * to trigger compilation; under-warming measures C1/interpreter performance.</p>
 *
 * <ul>
 *   <li><b>Fast</b> (>1000 ops/s): class defaults — 5s iterations, 5 warmup.
 *       Benchmarks: literal, literalMiss, captures, unicodeWord, wordRepeat.</li>
 *   <li><b>Medium</b> (200–1000 ops/s): 10s iterations, 5 warmup.
 *       Benchmarks: multiline.</li>
 *   <li><b>Slow</b> (<200 ops/s): 20s iterations, 10 warmup.
 *       Benchmarks: charClass, alternation.</li>
 * </ul>
 *
 * <p>3 forks is the minimum for capturing JIT compilation variance across
 * JVM invocations. Use {@code -f 1 -wi 3 -i 3} for quick dev feedback only;
 * never record single-fork numbers as stage results.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(3)
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

    // Loop unrolling stress: high state-creation (worst case for unrolling)
    private Regex ohaiWordRepeat;
    private Pattern jdkWordRepeat;

    // Loop unrolling stress: moderate transitions with look-around
    private Regex ohaiMultiline;
    private Pattern jdkMultiline;

    // Loop unrolling stress: literal not found, mostly self-transitions (best case)
    private Regex ohaiLiteralMiss;
    private Pattern jdkLiteralMiss;

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

        ohaiWordRepeat = Regex.compile("\\w{50}");
        jdkWordRepeat = Pattern.compile("\\w{50}", Pattern.UNICODE_CHARACTER_CLASS);

        ohaiMultiline = Regex.compile("(?m)^.+$");
        jdkMultiline = Pattern.compile("(?m)^.+$");

        ohaiLiteralMiss = Regex.compile("ZQZQZQZQ");
        jdkLiteralMiss = Pattern.compile("ZQZQZQZQ");
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
    // Slow tier: ~100 ops/s → needs 20s × 10 warmup iterations = 200s = ~20K invocations

    @Benchmark
    @Warmup(iterations = 10, time = 20)
    @Measurement(iterations = 5, time = 20)
    public void charClassOhai(Blackhole bh) {
        ohaiCharClass.findAll(Haystacks.SHERLOCK_EN).forEach(bh::consume);
    }

    @Benchmark
    @Warmup(iterations = 10, time = 20)
    @Measurement(iterations = 5, time = 20)
    public void charClassJdk(Blackhole bh) {
        Matcher m = jdkCharClass.matcher(Haystacks.SHERLOCK_EN);
        while (m.find()) {
            bh.consume(m.start());
        }
    }

    // ---- Alternation: Sherlock|Watson|Holmes|Irene ----
    // Slow tier: ~50 ops/s → needs 20s × 10 warmup iterations = 200s = ~10K invocations

    @Benchmark
    @Warmup(iterations = 10, time = 20)
    @Measurement(iterations = 5, time = 20)
    public void alternationOhai(Blackhole bh) {
        ohaiAlternation.findAll(Haystacks.SHERLOCK_EN).forEach(bh::consume);
    }

    @Benchmark
    @Warmup(iterations = 10, time = 20)
    @Measurement(iterations = 5, time = 20)
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

    // ---- Loop unrolling stress: \w{50} (high state creation) ----

    @Benchmark
    public void wordRepeatOhai(Blackhole bh) {
        ohaiWordRepeat.findAll(Haystacks.UNICODE_MIXED).forEach(bh::consume);
    }

    @Benchmark
    public void wordRepeatJdk(Blackhole bh) {
        Matcher m = jdkWordRepeat.matcher(Haystacks.UNICODE_MIXED);
        while (m.find()) {
            bh.consume(m.start());
        }
    }

    // ---- Loop unrolling stress: (?m)^.+$ (multiline look-around) ----
    // Medium tier: ~350 ops/s → needs 10s × 5 warmup iterations = 50s = ~17K invocations

    @Benchmark
    @Warmup(iterations = 5, time = 10)
    @Measurement(iterations = 5, time = 10)
    public void multilineOhai(Blackhole bh) {
        ohaiMultiline.findAll(Haystacks.SHERLOCK_EN).forEach(bh::consume);
    }

    @Benchmark
    @Warmup(iterations = 5, time = 10)
    @Measurement(iterations = 5, time = 10)
    public void multilineJdk(Blackhole bh) {
        Matcher m = jdkMultiline.matcher(Haystacks.SHERLOCK_EN);
        while (m.find()) {
            bh.consume(m.start());
        }
    }

    // ---- Loop unrolling stress: ZQZQZQZQ (literal miss, self-transitions) ----

    @Benchmark
    public void literalMissOhai(Blackhole bh) {
        ohaiLiteralMiss.findAll(Haystacks.SHERLOCK_EN).forEach(bh::consume);
    }

    @Benchmark
    public void literalMissJdk(Blackhole bh) {
        Matcher m = jdkLiteralMiss.matcher(Haystacks.SHERLOCK_EN);
        while (m.find()) {
            bh.consume(m.start());
        }
    }
}
