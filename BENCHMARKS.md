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

## Results (2026-03-11, post reverse DFA)

*Note: This run was on different hardware than the previous results. Absolute ops/s values are not comparable across runs — use the ohai/JDK ratio (same-run comparison) to assess relative performance.*

### Search throughput (ops/s, higher is better)

| Benchmark | ohai | JDK | Ratio | Change |
|---|---|---|---|---|
| literal | **2,353** | 1,614 | **1.5x faster** | unchanged (PrefilterOnly) |
| charClass | 4.5 | 179 | 40x slower | unchanged (DFA give-up) |
| alternation | 20.3 | 45.3 | 2.2x slower | unchanged (prefilter path) |
| captures | 19.8 | 6,230 | 315x slower | unchanged (DFA give-up) |
| unicodeWord | 4.4 | 14,211 | 3,252x slower | unchanged (DFA give-up) |

### Pathological patterns (ops/s, higher is better)

| Benchmark | ohai | JDK | Ratio | Change |
|---|---|---|---|---|
| backtrack `(a+)+b` | **9,901,459** | 76,814 | **129x faster** | unchanged |
| redosShort | 13,352 | 35,579 | 2.7x slower | unchanged |
| redosLong (900KB) | 133 | N/A (hangs) | **ohai wins** | unchanged |
| quadratic100 | 6,536 | 51,664 | 7.9x slower | unchanged |
| quadratic1000 | 345 | 642 | 1.9x slower | unchanged |

### Compilation (ops/s, higher is better)

Reverse DFA compilation adds a second NFA + CharClasses + LazyDFA per pattern (skipped for patterns with look-assertions where the DFA bails out).

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| simple | 33.7K | 3,568K | 106x slower |
| medium | 4.0K | 2,781K | 695x slower |
| complex | 32.3K | 4,420K | 137x slower |
| unicode | 1.4K | 6,777K | 4,841x slower |
| alternation | 14.4K | 1,334K | 92x slower |

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

### Reverse DFA impact (2026-03-11)

The reverse DFA infrastructure is in place but **does not yet affect search performance**. The three-phase search (forward DFA → reverse DFA → done without PikeVM) is not active because the forward DFA overestimates match end for lazy quantifier and empty-alternative patterns (see `docs/architecture/lazy-dfa-gaps.md`). The current search path remains two-phase: forward DFA narrows the window, PikeVM finds the exact match.

#### Why search benchmarks are unchanged

The reverse DFA doesn't participate in the search path yet. All search and pathological benchmark ratios are consistent with previous results (within hardware variance).

#### Compilation cost increased

Adding a reverse NFA + CharClasses + LazyDFA per pattern roughly doubles the compilation work for patterns without look-assertions. Patterns with ASCII-only look-assertions (e.g., `^`, `$`, `(?-u:\b)`) now build both forward and reverse DFAs. Patterns with Unicode word boundaries or CRLF line anchors still skip DFA compilation.

### Where ohai wins

- **Literal search**: **1.5x faster than JDK** via `PrefilterOnly` strategy (pure `String.indexOf()`)
- **Backtracking safety**: **129x faster than JDK** on `(a+)+b` — linear-time guarantee vs JDK's exponential backtracking
- **ReDoS immunity**: 900KB haystack with `.*.*=.*` completes; JDK hangs indefinitely

### Roadmap to competitive throughput

To close the remaining gap with JDK for Unicode-heavy patterns:

1. ~~**Look-around encoding in DFA states**~~ — **DONE** (2026-03-11). The DFA now handles `^`, `$`, `(?m)^`, `(?m)$`, and ASCII word boundaries (`(?-u:\b)`, `(?-u:\B)`) inline. Unicode word boundaries and CRLF line anchors still bail out to PikeVM. The current search benchmarks don't exercise look-assertion patterns so no throughput change is visible, but patterns like `^abc` and `(?m)^line$` now benefit from DFA acceleration.
2. **Suffix/inner literal prefilters** — patterns like `\w+\s+Holmes` have an extractable literal suffix. The reverse DFA infrastructure is now in place for this. This is the next highest-impact optimization.
3. **Three-phase search activation** — requires HIR-level analysis to determine when lazy/greedy semantics don't affect match span, so the reverse DFA can safely replace PikeVM for start-position finding.
4. **Quit bytes** — instead of bailing out entirely for Unicode word boundaries, designate rare char values as "quit" triggers and fall back to PikeVM only at those points, keeping the DFA for the common case.
5. **One-pass DFA** — for simple patterns that can be matched in a single left-to-right pass with captures.
6. **Aho-Corasick** — for alternations with many branches (>10), more efficient than multi-`indexOf`.
7. **SIMD acceleration** — Java's Vector API or `MemorySegment` for vectorized literal scanning.

### Compilation cost

The `medium` and `unicode` compilation benchmarks are notably slow due to Unicode character class expansion. The `\d` class in Unicode mode expands to hundreds of ranges, each generating NFA states. Adding reverse DFA compilation roughly doubles this cost. The Rust crate addresses this with range tree compaction and DFA minimization. Our immediate path: cache compiled NFAs (the Regex class already does this for search, but the benchmark measures cold compilation).
