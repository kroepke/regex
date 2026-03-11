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

## Results (2026-03-11, post lazy DFA)

### Search throughput (ops/s, higher is better)

| Benchmark | ohai | JDK | Ratio | Change |
|---|---|---|---|---|
| literal | **5,053** | 3,400 | **1.5x faster** | unchanged (PrefilterOnly) |
| charClass | 11.7 | 308 | 26x slower | unchanged (DFA give-up) |
| alternation | 46.4 | 106 | 2.3x slower | unchanged (prefilter path) |
| captures | 61.2 | 16,935 | 277x slower | unchanged (DFA give-up) |
| unicodeWord | 13.8 | 33,299 | 2,415x slower | unchanged (DFA give-up) |

### Pathological patterns (ops/s, higher is better)

| Benchmark | ohai | JDK | Ratio | Change |
|---|---|---|---|---|
| backtrack `(a+)+b` | **20,023,875** | 208,685 | **96x faster** | was 2x faster (44x improvement) |
| redosShort | 39,506 | 97,576 | 2.5x slower | unchanged |
| redosLong (900KB) | 388 | N/A (hangs) | **ohai wins** | unchanged |
| quadratic100 | **17,443** | 117,049 | 6.7x slower | was 76x slower (12x improvement) |
| quadratic1000 | **801** | 1,463 | 1.8x slower | was 83x slower (47x improvement) |

### Compilation (ops/s, higher is better)

Unchanged from previous results — lazy DFA compilation adds negligible overhead (char class construction + NFA scan for look-assertions).

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| simple | 1,124K | 10,434K | 9x slower |
| medium | 53K | 7,605K | 143x slower |
| complex | 744K | 12,296K | 17x slower |
| unicode | 31K | 18,227K | 588x slower |
| alternation | 365K | 3,892K | 11x slower |

### Previous results (2026-03-11, post meta engine, pre lazy DFA)

<details>
<summary>Click to expand</summary>

#### Search throughput

| Benchmark | ohai | JDK | Ratio | Change |
|---|---|---|---|---|
| literal | **4,753** | 3,168 | **1.5x faster** | was 233x slower |
| charClass | 12.9 | 231 | 18x slower | was ~4,750x slower |
| alternation | 44.5 | 105 | 2.4x slower | was 17x slower |
| captures | 59.5 | 15,940 | 268x slower | unchanged |
| unicodeWord | 13.0 | 32,961 | 2,536x slower | unchanged |

#### Pathological patterns

| Benchmark | ohai | JDK | Ratio |
|---|---|---|---|
| backtrack `(a+)+b` | **455,849** | 224,292 | **2.0x faster** |
| redosShort | 36,631 | 92,736 | 2.5x slower |
| redosLong (900KB) | 378 | N/A (hangs) | **ohai wins** |
| quadratic100 | 1,481 | 112,298 | 76x slower |
| quadratic1000 | 17.1 | 1,424 | 83x slower |

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

### Lazy DFA impact (2026-03-11)

The lazy DFA delivers **dramatic improvements on pathological patterns** but has limited impact on the search benchmarks:

#### Where the lazy DFA transforms performance

- **backtrack `(a+)+b`**: 455K → **20M ops/s** (**44x faster**, now **96x faster than JDK**). The DFA quickly determines "no match" — it builds a few states covering `{a, b, other}`, scans 25 chars in one pass, and reports NoMatch. PikeVM had to simulate ~20 active NFA states per char per starting position.
- **quadratic100 `.*[^A-Z]|[A-Z]`**: 1,481 → **17,443 ops/s** (**12x faster**, now 6.7x slower vs 76x). Small alphabet (3 classes: A-Z, non-A-Z, other) → few DFA states → hot cache → O(1) per char.
- **quadratic1000**: 17.1 → **801 ops/s** (**47x faster**, now 1.8x slower vs 83x). Same pattern on longer input shows even more dramatic improvement since the DFA amortizes state construction over more chars.

#### Why search benchmarks didn't improve

The search benchmark patterns use Unicode character classes (`\s`, `\w`) that expand to hundreds of ranges. This creates many equivalence classes and a large DFA state space. The cache fills up quickly:

1. `\s` (Unicode whitespace) has ~20 ranges → adds ~40 boundary points
2. `\w` (Unicode word) has ~700 ranges → adds ~1400 boundary points
3. Combined with the unanchored `.*?` prefix, the powerset construction generates thousands of DFA states
4. The 2MB cache fills up, the give-up heuristic triggers after 3 clears, and the engine falls back to PikeVM

This is expected and matches the upstream Rust crate's behavior — the lazy DFA is most effective for patterns with small effective alphabets.

### Where ohai wins

- **Literal search**: **1.5x faster than JDK** via `PrefilterOnly` strategy (pure `String.indexOf()`)
- **Backtracking safety**: **96x faster than JDK** on `(a+)+b` — linear-time guarantee vs JDK's exponential backtracking
- **ReDoS immunity**: 900KB haystack with `.*.*=.*` completes in ~2.6ms; JDK hangs indefinitely

### Roadmap to competitive throughput

To close the remaining gap with JDK for Unicode-heavy patterns:

1. **Suffix/inner literal prefilters** — patterns like `\w+\s+Holmes` have an extractable literal suffix. A reverse DFA or simple reverse scan to find the match start would allow skipping ahead in the haystack. This is the next highest-impact optimization.
2. **Look-around encoding in DFA states** — the `look_have`/`look_need` approach from the Rust crate would let the DFA handle `^`, `$`, `\b` inline instead of bailing out. See `docs/architecture/lazy-dfa-gaps.md`.
3. **Quit bytes** — instead of bailing out entirely for Unicode patterns, designate rare byte values as "quit" triggers and fall back to PikeVM only at those points, keeping the DFA for the common case.
4. **One-pass DFA** — for simple patterns that can be matched in a single left-to-right pass with captures.
5. **Aho-Corasick** — for alternations with many branches (>10), more efficient than multi-`indexOf`.
6. **SIMD acceleration** — Java's Vector API or `MemorySegment` for vectorized literal scanning.

### Compilation cost

The `medium` and `unicode` compilation benchmarks are notably slow due to Unicode character class expansion. The `\d` class in Unicode mode expands to hundreds of ranges, each generating NFA states. The Rust crate addresses this with range tree compaction and DFA minimization. Our immediate path: cache compiled NFAs (the Regex class already does this for search, but the benchmark measures cold compilation).
