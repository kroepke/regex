package lol.ohai.regex.bench;

import lol.ohai.regex.Regex;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pathological pattern benchmarks demonstrating linear-time guarantees.
 *
 * <p>These patterns cause catastrophic backtracking in java.util.regex but
 * execute in linear time with our NFA-based engine. The JDK benchmarks use
 * short timeouts to prevent hanging.</p>
 *
 * <p>Inspired by rebar's curated benchmarks:
 * <ul>
 *   <li>{@code 06-cloud-flare-redos.toml} — nested {@code .*} causing ReDoS</li>
 *   <li>{@code 14-quadratic.toml} — quadratic iteration with {@code .*[^A-Z]|[A-Z]}</li>
 * </ul></p>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
@State(Scope.Thread)
public class PathologicalBenchmark {

    // ---- Cloudflare ReDoS: .*.*=.* on short haystack ----

    private Regex ohaiRedosShort;
    private Pattern jdkRedosShort;
    private static final String REDOS_SHORT_HAYSTACK =
            "x=" + "x".repeat(98);

    // ---- Cloudflare ReDoS: .*.*=.* on long haystack ----

    private Regex ohaiRedosLong;
    // JDK version intentionally omitted for long haystack — it would hang

    // ---- Quadratic: .*[^A-Z]|[A-Z] ----

    private Regex ohaiQuad100;
    private Regex ohaiQuad1000;
    private Pattern jdkQuad100;
    private Pattern jdkQuad1000;

    // ---- Classic backtracking: (a+)+b ----

    private Regex ohaiBacktrack;
    private Pattern jdkBacktrack;
    private static final String BACKTRACK_HAYSTACK = "a".repeat(25);

    @Setup(Level.Trial)
    public void setup() {
        ohaiRedosShort = Regex.compile(".*.*=.*");
        jdkRedosShort = Pattern.compile(".*.*=.*");

        ohaiRedosLong = Regex.compile(".*.*=.*");

        ohaiQuad100 = Regex.compile(".*[^A-Z]|[A-Z]");
        ohaiQuad1000 = Regex.compile(".*[^A-Z]|[A-Z]");
        jdkQuad100 = Pattern.compile(".*[^A-Z]|[A-Z]");
        jdkQuad1000 = Pattern.compile(".*[^A-Z]|[A-Z]");

        ohaiBacktrack = Regex.compile("(a+)+b");
        jdkBacktrack = Pattern.compile("(a+)+b");
    }

    // ---- Cloudflare ReDoS (short) ----

    @Benchmark
    public void redosShortOhai(Blackhole bh) {
        ohaiRedosShort.findAll(REDOS_SHORT_HAYSTACK).forEach(bh::consume);
    }

    @Benchmark
    public void redosShortJdk(Blackhole bh) {
        Matcher m = jdkRedosShort.matcher(REDOS_SHORT_HAYSTACK);
        while (m.find()) {
            bh.consume(m.start());
        }
    }

    // ---- Cloudflare ReDoS (long) — ohai only, JDK would hang ----

    @Benchmark
    public void redosLongOhai(Blackhole bh) {
        ohaiRedosLong.findAll(Haystacks.CLOUD_FLARE_REDOS).forEach(bh::consume);
    }

    // ---- Quadratic (100 A's) ----

    @Benchmark
    public void quadratic100Ohai(Blackhole bh) {
        ohaiQuad100.findAll(Haystacks.REPEAT_A_100).forEach(bh::consume);
    }

    @Benchmark
    public void quadratic100Jdk(Blackhole bh) {
        Matcher m = jdkQuad100.matcher(Haystacks.REPEAT_A_100);
        while (m.find()) {
            bh.consume(m.start());
        }
    }

    // ---- Quadratic (1000 A's) ----

    @Benchmark
    public void quadratic1000Ohai(Blackhole bh) {
        ohaiQuad1000.findAll(Haystacks.REPEAT_A_1000).forEach(bh::consume);
    }

    @Benchmark
    public void quadratic1000Jdk(Blackhole bh) {
        Matcher m = jdkQuad1000.matcher(Haystacks.REPEAT_A_1000);
        while (m.find()) {
            bh.consume(m.start());
        }
    }

    // ---- Classic backtracking: (a+)+b on "aaa..." (no match) ----

    @Benchmark
    public boolean backtrackOhai() {
        return ohaiBacktrack.isMatch(BACKTRACK_HAYSTACK);
    }

    @Benchmark
    @Timeout(time = 5)
    public boolean backtrackJdk() {
        return jdkBacktrack.matcher(BACKTRACK_HAYSTACK).find();
    }
}
