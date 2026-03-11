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

## Results (2026-03-11, post meta engine + literal prefilter)

### Search throughput (ops/s, higher is better)

| Benchmark | ohai | JDK | Ratio | Change |
|---|---|---|---|---|
| literal | **4,753** | 3,168 | **1.5x faster** | was 233x slower |
| charClass | 12.9 | 231 | 18x slower | was ~4,750x slower |
| alternation | 44.5 | 105 | 2.4x slower | was 17x slower |
| captures | 59.5 | 15,940 | 268x slower | unchanged |
| unicodeWord | 13.0 | 32,961 | 2,536x slower | unchanged |

### Pathological patterns (ops/s, higher is better)

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| backtrack `(a+)+b` | **455,849** | 224,292 | **2.0x faster** |
| redosShort | 36,631 | 92,736 | 2.5x slower |
| redosLong (900KB) | 378 | N/A (hangs) | **ohai wins** |
| quadratic100 | 1,481 | 112,298 | 76x slower |
| quadratic1000 | 17.1 | 1,424 | 83x slower |

### Compilation (ops/s, higher is better)

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| simple | 1,124K | 10,434K | 9x slower |
| medium | 53K | 7,605K | 143x slower |
| complex | 744K | 12,296K | 17x slower |
| unicode | 31K | 18,227K | 588x slower |
| alternation | 365K | 3,892K | 11x slower |

### Previous results (2026-03-10, pre meta engine)

<details>
<summary>Click to expand</summary>

#### Search throughput

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| literal | 13.7 | 3,201 | 233x slower |
| charClass | 0.062 | 295 | ~4,750x slower |
| alternation | 6.1 | 105 | 17x slower |
| captures | 57 | 14,885 | 261x slower |
| unicodeWord | 12.1 | 31,946 | 2,643x slower |

#### Pathological patterns

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| backtrack `(a+)+b` | **449,238** | 219,111 | **2.0x faster** |
| redosShort | 38,020 | 92,183 | 2.4x slower |
| redosLong (900KB) | 379 | N/A (hangs) | **ohai wins** |
| quadratic100 | 1,323 | 111,278 | 84x slower |
| quadratic1000 | 16.6 | 1,408 | 85x slower |

#### Compilation

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| simple | 1,089K | 9,883K | 9x slower |
| medium | 52K | 7,376K | 142x slower |
| complex | 755K | 11,525K | 15x slower |
| unicode | 30K | 17,972K | 599x slower |
| alternation | 434K | 3,900K | 9x slower |

</details>

## Analysis

### Meta engine impact (2026-03-11)

The meta engine with literal prefilters delivered dramatic improvements:

- **literal (`Sherlock Holmes`)**: 13.7 → 4,753 ops/s (**347x faster**, now **1.5x faster than JDK**). The `PrefilterOnly` strategy bypasses the NFA entirely, using `String.indexOf()` (a JIT intrinsic) for pure literal patterns.
- **alternation (`Sherlock|Watson|Holmes|Irene`)**: 6.1 → 44.5 ops/s (**7.3x faster**). The `Core` strategy with `MultiLiteral` prefilter narrows candidates before PikeVM confirmation. Still 2.4x slower than JDK because multi-`indexOf` (one per alternative) is less efficient than JDK's native alternation optimization.
- **charClass (`[a-zA-Z]+`)**: 0.062 → 12.9 ops/s (**208x faster**). No prefilter (character classes have no extractable prefix), but the `Input` reuse optimization in `findAll` eliminated per-iteration 900KB char[] allocations that were killing GC.

Patterns without extractable prefixes (`captures`, `unicodeWord`) are unchanged — they still use pure PikeVM through the `Core` strategy with no prefilter.

### Where ohai wins

The backtracking benchmark `(a+)+b` on `"aaa..."` (no match) shows our advantage: **2.0x faster than JDK**. The JDK's backtracking engine explores exponentially many paths; our PikeVM processes it in linear time. The ReDoS benchmark on 900KB input demonstrates the safety guarantee — JDK hangs, we complete in ~2.6ms.

The literal search benchmark now **beats JDK by 1.5x** thanks to the `PrefilterOnly` strategy — pure `String.indexOf()` with no regex engine overhead.

### Roadmap to competitive throughput

To close the remaining gap with JDK for non-literal patterns:

1. **Lazy/Hybrid DFA** — build DFA states on demand during search. O(1) per char instead of O(m). This is the main throughput engine in the upstream Rust crate and would dramatically improve `charClass`, `alternation`, `captures`, and `unicodeWord`.
2. **Suffix/inner literal prefilters** — requires a reverse engine to determine match start position. Would help patterns like `\w+\s+Holmes` where the literal is not a prefix.
3. **One-pass DFA** — for simple patterns that can be matched in a single left-to-right pass with captures.
4. **Aho-Corasick** — for alternations with many branches (>10), more efficient than multi-`indexOf`.
5. **SIMD acceleration** — Java's Vector API or `MemorySegment` for vectorized literal scanning.

### Compilation cost

The `medium` and `unicode` compilation benchmarks are notably slow due to Unicode character class expansion. The `\d` class in Unicode mode expands to hundreds of ranges, each generating NFA states. The Rust crate addresses this with range tree compaction and DFA minimization. Our immediate path: cache compiled NFAs (the Regex class already does this for search, but the benchmark measures cold compilation).
