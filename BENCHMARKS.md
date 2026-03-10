# Benchmarks

## Running Benchmarks

The benchmark module uses JMH and is activated via the `bench` Maven profile.

```bash
# Build the benchmark JAR (skip tests for speed)
./mvnw -P bench package -DskipTests

# Run all benchmarks
java -jar regex-bench/target/benchmarks.jar

# Run a specific benchmark class
java -jar regex-bench/target/benchmarks.jar "SearchBenchmark"
java -jar regex-bench/target/benchmarks.jar "CompileBenchmark"
java -jar regex-bench/target/benchmarks.jar "PathologicalBenchmark"

# Run a single benchmark method
java -jar regex-bench/target/benchmarks.jar "SearchBenchmark.literalOhai"

# Quick run (fewer iterations, useful during development)
java -jar regex-bench/target/benchmarks.jar "SearchBenchmark" -f 1 -wi 2 -i 3

# Full run (default: 1 fork, 3 warmup, 5 measurement iterations)
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5
```

JMH options: `-f` forks, `-wi` warmup iterations, `-i` measurement iterations, `-t` threads.

## Benchmark Descriptions

### SearchBenchmark

Throughput search on a ~900KB haystack (`rebar/benchmarks/haystacks/opensubtitles/en-sampled.txt`):

| Benchmark | Pattern | Notes |
|---|---|---|
| `literalOhai/Jdk` | `Sherlock Holmes` | Literal substring search |
| `charClassOhai/Jdk` | `[A-Z][a-z]+\\s[A-Z][a-z]+` | Character class + repetition |
| `alternationOhai/Jdk` | `Sherlock\|Watson\|Holmes\|...` | Multi-literal alternation |
| `capturesOhai/Jdk` | `(?P<first>[A-Z][a-z]+)\\s(?P<last>[A-Z][a-z]+)` | Named capture groups |
| `unicodeWordOhai/Jdk` | `\\w+\\s+Holmes` | Unicode word class |

### CompileBenchmark

Pattern compilation throughput (no search):

| Benchmark | Pattern |
|---|---|
| `simpleOhai/Jdk` | `^bc(d\|e)*$` |
| `mediumOhai/Jdk` | `(\\d{4})-(\\d{2})-(\\d{2})` |
| `complexOhai/Jdk` | `[a-zA-Z_][a-zA-Z0-9_]*\\b` |
| `unicodeOhai/Jdk` | `\\w+\\s+\\d+` |
| `alternationOhai/Jdk` | `Sherlock\|Watson\|Holmes\|...` |

### PathologicalBenchmark

Patterns that cause catastrophic backtracking in java.util.regex:

| Benchmark | Pattern | Haystack | Notes |
|---|---|---|---|
| `redosShortOhai/Jdk` | `.*.*=.*` | 100 chars | Cloudflare ReDoS |
| `redosLongOhai` | `.*.*=.*` | ~900KB | JDK would hang |
| `quadratic100Ohai/Jdk` | `.*[^A-Z]\|[A-Z]` | 100 A's | Quadratic behavior |
| `quadratic1000Ohai/Jdk` | `.*[^A-Z]\|[A-Z]` | 1000 A's | Quadratic behavior |
| `backtrackOhai/Jdk` | `(a+)+b` | 25 a's | Classic backtracking |

## Results (2026-03-10, post UTF-16 migration)

### Search throughput (ops/s, higher is better)

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| literal | 13.7 | 3,201 | 233x slower |
| charClass | 0.062 | 295 | ~4,750x slower |
| alternation | 6.1 | 105 | 17x slower |
| captures | 57 | 14,885 | 261x slower |
| unicodeWord | 12.1 | 31,946 | 2,643x slower |

### Pathological patterns (ops/s, higher is better)

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| backtrack `(a+)+b` | **449,238** | 219,111 | **2.0x faster** |
| redosShort | 38,020 | 92,183 | 2.4x slower |
| redosLong (900KB) | 379 | N/A (hangs) | **ohai wins** |
| quadratic100 | 1,323 | 111,278 | 84x slower |
| quadratic1000 | 16.6 | 1,408 | 85x slower |

### Compilation (ops/s, higher is better)

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| simple | 1,089K | 9,883K | 9x slower |
| medium | 52K | 7,376K | 142x slower |
| complex | 755K | 11,525K | 15x slower |
| unicode | 30K | 17,972K | 599x slower |
| alternation | 434K | 3,900K | 9x slower |

## Analysis

### Why is ohai slower than JDK for simple searches?

This is **expected and by design**. Our engine currently has only one search strategy: the PikeVM (Thompson NFA simulation). The PikeVM explores every NFA state at every char position, giving O(m × n) guaranteed linear time — but it does a lot of work per character for simple patterns.

The JDK's `java.util.regex` uses an optimized backtracking engine with:
- Literal prefix extraction (Boyer-Moore-style skipping)
- Internal DFA optimization for simple patterns
- Heavily tuned native code paths

The upstream Rust regex crate has the **same architecture** — its PikeVM is equally slow for simple literals. The Rust crate achieves competitive throughput through the **meta engine**, which selects from:

1. **Literal prefilters** (memchr/SIMD) — for patterns with extractable literal prefixes, the regex engine is bypassed entirely and SIMD-accelerated substring search is used
2. **Lazy/Hybrid DFA** — builds DFA states on demand, O(1) per char, handles most patterns
3. **Full DFA** — pre-compiled, fastest search, limited patterns
4. **BoundedBacktracker** — NFA + backtracking with captures
5. **PikeVM** — last-resort fallback for the most complex patterns

### Where ohai wins

The backtracking benchmark `(a+)+b` on `"aaa..."` (no match) shows our advantage: **2.3x faster than JDK**. The JDK's backtracking engine explores exponentially many paths; our PikeVM processes it in linear time. The ReDoS benchmark on 900KB input demonstrates the safety guarantee — JDK hangs, we complete in ~2.7ms.

### Roadmap to competitive throughput

To close the gap with JDK for common patterns, the following features from the upstream architecture are needed (roughly in priority order):

1. **Literal prefilter extraction** — identify patterns with literal prefixes/suffixes and use `String.indexOf()` or vectorized search to skip ahead. This alone would close much of the gap for literal-heavy patterns.
2. **Lazy/Hybrid DFA** — build DFA states on demand during search. O(1) per char instead of O(m). This is the main throughput engine in the Rust crate.
3. **Meta engine** — automatically select the best strategy based on pattern characteristics.
4. **One-pass DFA** — for simple patterns that can be matched in a single left-to-right pass with captures.
5. **SIMD acceleration** — Java's Vector API or `MemorySegment` for vectorized literal scanning.

### Compilation cost

The `medium` and `unicode` compilation benchmarks are notably slow due to Unicode character class expansion. The `\d` class in Unicode mode expands to hundreds of ranges, each generating NFA states. The Rust crate addresses this with range tree compaction and DFA minimization. Our immediate path: cache compiled NFAs (the Regex class already does this for search, but the benchmark measures cold compilation).
