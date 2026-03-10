package lol.ohai.regex.bench;

import lol.ohai.regex.Regex;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Compilation cost benchmarks comparing lol.ohai.regex against java.util.regex.
 *
 * <p>Measures the time to compile patterns of varying complexity, from simple
 * literals to patterns with Unicode character classes.</p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class CompileBenchmark {

    // ---- Simple: literal with anchors and alternation ----

    @Benchmark
    public Regex simpleOhai() {
        return Regex.compile("^bc(d|e)*$");
    }

    @Benchmark
    public Pattern simpleJdk() {
        return Pattern.compile("^bc(d|e)*$");
    }

    // ---- Medium: date pattern with repetition ----

    @Benchmark
    public Regex mediumOhai() {
        return Regex.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    }

    @Benchmark
    public Pattern mediumJdk() {
        return Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    }

    // ---- Complex: identifier pattern with word boundary ----

    @Benchmark
    public Regex complexOhai() {
        return Regex.compile("[a-zA-Z_][a-zA-Z0-9_]*\\b");
    }

    @Benchmark
    public Pattern complexJdk() {
        return Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*\\b");
    }

    // ---- Unicode: Perl character classes (triggers Unicode table expansion) ----

    @Benchmark
    public Regex unicodeOhai() {
        return Regex.compile("\\w+\\s+\\d+");
    }

    @Benchmark
    public Pattern unicodeJdk() {
        return Pattern.compile("\\w+\\s+\\d+", Pattern.UNICODE_CHARACTER_CLASS);
    }

    // ---- Alternation: multi-literal alternation ----

    @Benchmark
    public Regex alternationOhai() {
        return Regex.compile("Sherlock|Watson|Holmes|Irene|Adler|Moriarty|Lestrade|Hudson");
    }

    @Benchmark
    public Pattern alternationJdk() {
        return Pattern.compile("Sherlock|Watson|Holmes|Irene|Adler|Moriarty|Lestrade|Hudson");
    }
}
