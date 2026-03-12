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

## Results (2026-03-12, post search throughput improvements)

*Quit chars, three-phase search, and bounded backtracker.*

### Search throughput (ops/s, higher is better)

| Benchmark | ohai | JDK | Ratio | Change |
|---|---|---|---|---|
| literal | **5,275** | 3,529 | **1.5x faster** | unchanged (PrefilterOnly) |
| charClass | 79.0 | 320 | 4.0x slower | **10x improvement** (DFA handles more) |
| alternation | 50.8 | 82.8 | 1.6x slower | improved (was 2.2x slower) |
| captures | 513 | 17,250 | 33.6x slower | **~10x improvement** (three-phase + backtracker) |
| unicodeWord | **17,983** | 35,724 | 2.0x slower | **~1,600x improvement** (quit chars) |

### Pathological patterns (ops/s, higher is better)

| Benchmark | ohai | JDK | Ratio | Change |
|---|---|---|---|---|
| backtrack `(a+)+b` | **18,033K** | 247K | **73x faster** | unchanged |
| redosShort | **2,716K** | 102K | **27x faster** | **massive** (was 2.7x slower) |
| redosLong (900KB) | **28,394** | N/A (hangs) | **ohai wins** | unchanged |
| quadratic100 | 89,010 | 123,707 | 1.4x slower | **5.6x improvement** (was 7.9x slower) |
| quadratic1000 | 1,376 | 1,556 | 1.1x slower | improved (was 1.9x slower) |

### Compilation (ops/s, higher is better)

Compilation now includes forward DFA, reverse DFA, and BoundedBacktracker per pattern.

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| simple | 41.6K | 11,253K | 271x slower |
| medium | 11.6K | 8,321K | 718x slower |
| complex | 40.3K | 13,594K | 337x slower |
| unicode | 4.1K | 19,671K | 4,820x slower |
| alternation | 35.3K | 4,102K | 116x slower |

### Previous results (2026-03-11, post reverse DFA)

<details>
<summary>Click to expand (different hardware — ratios only comparable within same run)</summary>

#### Search throughput

| Benchmark | ohai | JDK | Ratio | Change |
|---|---|---|---|---|
| literal | **2,353** | 1,614 | **1.5x faster** | unchanged (PrefilterOnly) |
| charClass | 4.5 | 179 | 40x slower | unchanged (DFA give-up) |
| alternation | 20.3 | 45.3 | 2.2x slower | unchanged (prefilter path) |
| captures | 19.8 | 6,230 | 315x slower | unchanged (DFA give-up) |
| unicodeWord | 4.4 | 14,211 | 3,252x slower | unchanged (DFA give-up) |

#### Pathological patterns

| Benchmark | ohai | JDK | Ratio | Change |
|---|---|---|---|---|
| backtrack `(a+)+b` | **9,901,459** | 76,814 | **129x faster** | unchanged |
| redosShort | 13,352 | 35,579 | 2.7x slower | unchanged |
| redosLong (900KB) | 133 | N/A (hangs) | **ohai wins** | unchanged |
| quadratic100 | 6,536 | 51,664 | 7.9x slower | unchanged |
| quadratic1000 | 345 | 642 | 1.9x slower | unchanged |

#### Compilation

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| simple | 33.7K | 3,568K | 106x slower |
| medium | 4.0K | 2,781K | 695x slower |
| complex | 32.3K | 4,420K | 137x slower |
| unicode | 1.4K | 6,777K | 4,841x slower |
| alternation | 14.4K | 1,334K | 92x slower |

</details>

### Previous results (2026-03-11, post lazy DFA, pre reverse DFA)

<details>
<summary>Click to expand (different hardware — ratios only comparable within same run)</summary>

#### Search throughput

| Benchmark | ohai | JDK | Ratio | Change |
|---|---|---|---|---|
| literal | **5,053** | 3,400 | **1.5x faster** | unchanged (PrefilterOnly) |
| charClass | 11.7 | 308 | 26x slower | unchanged (DFA give-up) |
| alternation | 46.4 | 106 | 2.3x slower | unchanged (prefilter path) |
| captures | 61.2 | 16,935 | 277x slower | unchanged (DFA give-up) |
| unicodeWord | 13.8 | 33,299 | 2,415x slower | unchanged (DFA give-up) |

#### Pathological patterns

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| backtrack `(a+)+b` | **20,023,875** | 208,685 | **96x faster** |
| redosShort | 39,506 | 97,576 | 2.5x slower |
| redosLong (900KB) | 388 | N/A (hangs) | **ohai wins** |
| quadratic100 | **17,443** | 117,049 | 6.7x slower |
| quadratic1000 | **801** | 1,463 | 1.8x slower |

#### Compilation

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| simple | 1,124K | 10,434K | 9x slower |
| medium | 53K | 7,605K | 143x slower |
| complex | 744K | 12,296K | 17x slower |
| unicode | 31K | 18,227K | 588x slower |
| alternation | 365K | 3,892K | 11x slower |

</details>

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

### Search throughput improvements (2026-03-12)

Three changes drove major search throughput gains:

1. **Quit chars** — Instead of bailing out entirely for Unicode word boundary patterns, the DFA now designates non-ASCII chars as "quit" triggers and falls back to PikeVM only at those points. The `unicodeWord` benchmark improved from 3,252x slower to just **2.0x slower** — a ~1,600x improvement in the ohai/JDK ratio.

2. **Three-phase search** — Forward DFA finds match end → reverse DFA finds match start → capture engine on narrowed window. This avoids running PikeVM/backtracker over the full haystack. The `captures` benchmark improved from 315x slower to **33.6x slower**.

3. **Bounded backtracker** — For small match windows, the backtracker (O(m×n) with low constant factor) is faster than PikeVM for captures. Combined with three-phase narrowing, this accelerates capture-heavy patterns.

#### Pathological pattern improvements

The `redosShort` benchmark (`.*.*=.*` on 100 chars) improved from 2.7x slower to **27x faster than JDK**. The `quadratic` patterns also improved significantly (7.9x → 1.4x slower for 100 chars, 1.9x → 1.1x slower for 1000 chars). These improvements come from the DFA handling more of the search before falling back.

### Where ohai wins

- **Literal search**: **1.5x faster than JDK** via `PrefilterOnly` strategy (pure `String.indexOf()`)
- **Backtracking safety**: **73x faster than JDK** on `(a+)+b` — linear-time guarantee vs JDK's exponential backtracking
- **ReDoS immunity**: 900KB haystack with `.*.*=.*` completes at 28K ops/s; JDK hangs indefinitely
- **ReDoS short**: **27x faster than JDK** on `.*.*=.*` with 100-char input

### Roadmap to competitive throughput

Remaining gaps and optimizations:

1. ~~**Look-around encoding in DFA states**~~ — **DONE** (2026-03-11).
2. ~~**Quit chars**~~ — **DONE** (2026-03-12). DFA handles ASCII portions of Unicode word boundary patterns.
3. ~~**Three-phase search**~~ — **DONE** (2026-03-12). Forward DFA → reverse DFA → capture engine.
4. ~~**Bounded backtracker**~~ — **DONE** (2026-03-12). Preferred capture engine for small windows.
5. **Suffix/inner literal prefilters** — patterns like `\w+\s+Holmes` have an extractable literal suffix. This is the next highest-impact optimization for closing the `captures` gap.
6. **One-pass DFA** — for simple patterns that can be matched in a single left-to-right pass with captures.
7. **Aho-Corasick** — for alternations with many branches (>10), more efficient than multi-`indexOf`.
8. **SIMD acceleration** — Java's Vector API or `MemorySegment` for vectorized literal scanning.
9. **Search loop unrolling** — process 4 chars at a time in the DFA hot loop (~30-50% throughput improvement per upstream).

### Compilation cost

The `medium` and `unicode` compilation benchmarks are notably slow due to Unicode character class expansion. The `\d` class in Unicode mode expands to hundreds of ranges, each generating NFA states. Compilation now includes forward DFA, reverse DFA, and BoundedBacktracker per pattern. The Rust crate addresses this with range tree compaction and DFA minimization. Our immediate path: cache compiled NFAs (the Regex class already does this for search, but the benchmark measures cold compilation).
