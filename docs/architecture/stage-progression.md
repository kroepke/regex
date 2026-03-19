# Stage Progression

This document tracks the engine's development stages. Each stage is tagged in git (`stage-N-name`) at the commit where the feature was complete and benchmarkable. Use these tags to reproduce benchmarks at any historical point:

```bash
# Build and benchmark a specific stage
git worktree add .worktrees/bench-stageN stage-N-name
cd .worktrees/bench-stageN && git submodule update --init
./mvnw -P bench package -DskipTests
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5
```

## Stages

### stage-1-pikevm (`65accee`)
**PikeVM only, post UTF-16 migration**

The baseline. All search goes through NFA simulation (PikeVM). No DFA, no prefilters, no meta engine. Char-unit UTF-16 throughout.

- Engines: PikeVM
- Test count: ~1,954
- Key metric: literal search 241x slower than JDK, backtrack 2.1x faster (ReDoS safe)

### stage-2-lazy-dfa (`47b8d6d`)
**Meta engine + lazy DFA + look-assertion encoding**

Introduced the meta engine with strategy selection, literal prefilter extraction (`String.indexOf`), forward lazy DFA, and look-assertion encoding in DFA states.

- Engines: PikeVM + forward lazy DFA
- Strategies: Core, PrefilterOnly
- Key wins: literal search **1.5x faster** than JDK (via PrefilterOnly + indexOf), charClass 30x slower (from 5,131x), quadratic 7x slower (from 84x)

### stage-3-reverse-dfa (`e48968a`)
**Reverse DFA integration**

Added reverse NFA compilation and reverse lazy DFA. Wired into Strategy.Core for two-phase search (forward DFA → PikeVM on narrowed window). Note: three-phase (forward → reverse → return) was not yet active due to DFA edge-transition bugs that were not yet understood.

- Engines: PikeVM + forward lazy DFA + reverse lazy DFA
- Key change: reverse DFA available for match-start finding

### stage-4-quit-3phase-backtracker (`6f13c3e`)
**Quit chars + three-phase search + bounded backtracker**

Quit chars allow the DFA to handle ASCII portions of Unicode word boundary patterns. Bounded backtracker provides fast captures for small match windows. Note: this stage had three-phase DFA-only search active (forward → reverse → return directly) which was fast but had edge-case bugs (later removed in commit `6789c01`, then correctly restored in stage 6).

- Engines: PikeVM + forward/reverse lazy DFA + bounded backtracker
- Key wins: unicodeWord **2.0x slower** (from 2,530x), captures 33x slower (from 262x), redosShort **25.7x faster** than JDK
- Caveat: 27 upstream test failures existed due to DFA edge-transition bugs (surrogate handling, span boundary context). These were masked by the benchmark run.

### stage-5-suffix-inner-prefilters (`3cd3b89`)
**ReverseSuffix and ReverseInner strategies**

Added suffix and inner literal extraction with prefilter-based strategies. ReverseSuffix uses `indexOf` on suffix literals, then reverse DFA for match start. ReverseInner does the same for inner (non-prefix, non-suffix) literals.

- Engines: PikeVM + forward/reverse lazy DFA + bounded backtracker
- Strategies: Core, PrefilterOnly, ReverseSuffix, ReverseInner
- Key wins: backtrack benchmark **732x faster** than JDK
- Note: Core search was two-phase (forward DFA → PikeVM) at this point — three-phase had been removed by commit `6789c01`

### stage-6-three-phase-restored (`2883acf`)
**Three-phase DFA-only search restored + DFA edge fixes**

Discovered that the DFA was already leftmost-first (break-on-Match in `computeNextState` matches upstream's `determinize/mod.rs:284`). Restored three-phase DFA-only search. Fixed 4 DFA edge-case bugs: span boundary transitions, cached dead-state handling, reverse DFA start state context, char class overflow.

- Engines: PikeVM + forward/reverse lazy DFA + bounded backtracker
- Strategies: Core (three-phase DFA), PrefilterOnly, ReverseSuffix, ReverseInner
- Tests: 2,154 total, 879/879 upstream, 0 failures
- Key wins vs stage 5: charClass **6.1x faster** (11.6 → 70.6), captures **3.2x faster** (110.6 → 350)
- Remaining gap: unicodeWord limited by 256-class char class overflow (needs byte→short class IDs)

### stage-7-charclass-merge (`2abfe9a`)
**Equivalence class merging for Unicode char class overflow**

Added equivalence class merging to `CharClassBuilder`. Boundary regions with identical NFA transition targets (using `cr.next()` with surrogate-pair resolution) are collapsed into a single class. This eliminates the 256-class overflow for Unicode patterns like `\w+` without falling back to quit-on-non-ASCII.

- Engines: PikeVM + forward/reverse lazy DFA + bounded backtracker
- Strategies: Core (three-phase DFA), PrefilterOnly, ReverseSuffix, ReverseInner
- Tests: 2,160 total (6 new merge tests), 879/879 upstream, 0 failures
- Key win: unicodeWord **750x faster** (18.3 → 13,499 ops/s), now **2.3x slower** than JDK (was 2,090x)
- Other benchmarks unchanged

Key fixes:
- `computeSignature` uses `cr.next()` (transition target) not state ID — all word-char ranges sharing the same loop-back merge into one class
- `resolveTarget` follows surrogate-pair CharRange chains to the ultimate non-surrogate target — prevents reverse NFA from producing 440 signatures instead of 2
- `charInRange` in LazyDFA changed to direct char comparison (class-ID ordering breaks with merge)
- Patterns with `\b` skip merge, use existing quit-on-non-ASCII path

### stage-8-dfa-hot-path (`7d7d7e5`)
**Loop unrolling + ASCII fast-path + allocation reduction**

Added 4× loop unrolling to both `searchFwd()` and `searchRev()`. Then profiled the DFA hot path and applied three targeted optimizations: ASCII fast-path for `classify()` (1 array load vs 2 for c < 128), local `charsSearched` counter (eliminates per-char field write), primitive-encoded `searchFwdLong()`/`searchRevLong()` (eliminates SearchResult record allocation), and pooled `Captures` in `DFACache` (eliminates per-match Captures allocation).

Also added `Regex.Searcher` — a zero-allocation match iteration API analogous to JDK's `Matcher` — and `RawEngineBenchmark` for apples-to-apples engine throughput comparison.

- Engines: PikeVM + forward/reverse lazy DFA + bounded backtracker
- Strategies: Core (three-phase DFA), PrefilterOnly, ReverseSuffix, ReverseInner
- Tests: 2,183 total, 879/879 upstream, 0 failures
- Key wins vs stage 7: charClass **+30%** (75 → 91 via SearchBenchmark), allocation **-49%** (25.5 → 13.0 MB/op on charClass)
- New benchmarks: wordRepeat (94,535 ops/s — 8.5x faster than JDK), multiline (211 ops/s), literalMiss (4,964 ops/s — 2x faster than JDK)

### stage-9-one-pass-dfa (`2600edd`)
**One-pass DFA for capture group extraction**

Added a specialized one-pass DFA that extracts capture groups in a single forward scan, replacing PikeVM/backtracker as the capture engine for eligible patterns. Each 64-bit transition encodes state ID (21 bits), match_wins flag, look assertions (18 bits), and capture slot bitset (24 bits). Builder detects ambiguity and falls back to PikeVM for non-one-pass patterns.

- Engines: PikeVM + forward/reverse lazy DFA + bounded backtracker + **one-pass DFA**
- Strategies: Core (three-phase DFA + one-pass capture engine), PrefilterOnly, ReverseSuffix, ReverseInner
- Tests: 2,026 total, 879/879 upstream, 0 failures
- Key win: captures **33.8× faster** (366 → 12,362 ops/s), now **1.0× JDK** (was 43× slower)
- Other benchmarks unchanged (one-pass DFA only affects capture path)

### stage-10-allocation-cleanup (`87ced70`)
**Allocation elimination + collection modernization + metadata consolidation**

Comprehensive codebase cleanup targeting three zones: search hot-path allocations, compilation-path efficiency, and code quality. Guided by a systematic audit of `System.arraycopy` usage, unused parameters, wasteful allocations, and Collections-vs-arrays patterns.

Search hot-path changes:
- `Match` converted from record to final class with lazy `text()` — substring computed on demand, not at match creation. `source` field nulled after first `text()` call to allow GC. `equals`/`hashCode` based on offsets only (matching upstream Rust).
- `Input.setBounds()` for in-place mutation — `Searcher.find()` and `BaseFindIterator.advance()` reuse a single `Input` instead of allocating via `withBounds()` per match.
- `CharClasses` metadata consolidated: four `boolean[]` arrays → single `byte[] classFlags` with bit constants. Eliminates null checks on DFA state computation path. ASCII table init loop replaced with `System.arraycopy`.
- `Captures` internal storage changed from `List<Optional<Match>>` to `Match[]` — `Optional.ofNullable()` wrapping deferred to accessor.

Compilation-path changes:
- `CharClassBuilder.collectBoundaries()` returns `int[]` instead of `TreeSet<Integer>` — eliminates all `Integer` boxing and red-black tree overhead.
- `computeSignatureLong()` fast path for NFAs with ≤62 states — `long` bitmask instead of `BitSet` allocation per region.
- 64KB `flatMap` staging buffer eliminated — rows computed directly via binary search on sorted boundaries.
- `Collections.unmodifiableList/Map` → `List.copyOf`/`Map.copyOf`.

Code quality:
- `OnePassBuilder.grow()`/`growLong()` → `Arrays.copyOf`
- `PrefilterOnly` unused `cache` parameter documented

- Engines: PikeVM + forward/reverse lazy DFA + bounded backtracker + one-pass DFA
- Strategies: Core (three-phase DFA + one-pass capture engine), PrefilterOnly, ReverseSuffix, ReverseInner
- Tests: 2,186 total (3 new CharClasses tests), 879/879 upstream, 0 failures
- Key wins vs S9 (same-session measurement): captures **+18%** (14,330 → 16,928), unicodeWord **+12%** (13,650 → 15,267), charClass **+12%** (73 → 81), multiline **+16%** (201 → 233)
- vs JDK: captures now **1.1x faster** than JDK (was 1.0x), literal **1.55x faster**, literalMiss **2.0x faster**, wordRepeat **8.4x faster**

### stage-11-jit-headroom (`bff8f93`)
**DFA search method bytecode reduction for JIT headroom**

Reduced `searchFwdLong` from 613 to 472 bytecode bytes (-23%) and `searchRevLong` from 625 to 480 bytes (-23%) by extracting cold paths and eliminating per-char bookkeeping. This creates headroom for prefilter-at-start code in a future stage.

Cold path extraction:
- Slow-path dispatch (UNKNOWN/dead/quit handling) → `handleSlowTransition()` / `handleSlowTransitionRev()` — returns packed `long` encoding (sid + lastMatchEnd) to avoid mutable state.
- Right-edge / left-edge transitions → `handleRightEdge()` / `handleLeftEdge()` — post-loop EOI/boundary handling extracted from the search methods.

Hot loop micro-optimizations:
- `charsSearched` local variable eliminated from the inner loop. Previously incremented in lockstep with `pos` at every branch (8 long operations per iteration). Now computed on demand as `pos - startPos` at the two sync points (slow-path helper and edge transition).
- Unnecessary `continue` statements removed (each generated a `goto` bytecode).
- `dead` and `quit` state IDs precomputed as instance fields instead of recomputing from `stride` per search call.

JIT validation (C2 `-XX:+PrintInlining` analysis):
- `classify` (43 bytes) inlined at 3/5 call sites in the searchFwdLong inner loop (positions 0, 1, and outer dispatch — same as baseline). Positions 2 and 3 still rejected by C2, but the reduced method size produces better overall code generation.
- Right-edge `classify` call moved to helper — no longer counts against searchFwdLong's inline budget.

- Engines: unchanged from stage 10
- Strategies: unchanged from stage 10
- Tests: 2,186 total, 0 failures
- Key wins vs S10 (same-session): rawUnicodeWord **+14%** (9,532 → 10,916), multiline **+5%** (238 → 251), charClass **+4%** (80 → 84). No regressions.

### stage-12-dense-dfa (`99fffd5`)
**Pre-compiled dense DFA engine**

New `DenseDFA` engine that pre-compiles all DFA states and transitions into a flat `int[]` table at `Regex.compile()` time. Eliminates UNKNOWN-state handling, HashMap lookups, and lazy computation from the forward search hot path. Match states shuffled to contiguous range for fast `sid >= minMatch` detection.

- `DenseDFABuilder` reuses LazyDFA's determinization logic (`computeAllTransitions`) to eagerly build all reachable states via worklist-driven construction
- `DenseDFA.searchFwd()` is 330 bytecodes — all `classify` calls inlined by C2
- Strategy.Core prefers dense DFA for forward search when available
- Falls back to lazy DFA for patterns with look-assertions (`\b`, `^`, `$`) or state explosion
- Memory-based state limit: 2MB → ~8,000 states at stride=64
- Also fixed a `charInRange` bug in LazyDFA where class-based comparison failed for wide NFA ranges spanning multiple merged equivalence classes

- Engines: PikeVM + lazy DFA + bounded backtracker + one-pass DFA + **dense DFA**
- Strategies: Core (dense DFA forward + lazy DFA reverse + capture engines), PrefilterOnly, ReverseSuffix, ReverseInner
- Tests: 2,193 total, 0 failures
- Key wins vs S11: charClass **+17%** (83 → 94), unicodeWord **+25%** (15,037 → 19,807), rawCharClass **+17%** (89 → 105), rawUnicodeWord **+64%** (9,532 → 15,686)

### stage-13-acceleration-states (`d1ad55a`)
**DFA acceleration states for self-looping states**

Per-state `boolean[128]` escape tables for dense DFA states where ≤3 equivalence classes escape the self-loop. Reduces per-char cost from 2 array loads (classify + transTable) to 1 (escape table check). Detection at build time in DenseDFABuilder, scan in searchFwd before the unrolled inner loop.

- Match states that are also accelerated get `lastMatchEnd` updated at both entry and exit of the scan — every self-loop char extends the match
- 0-escape-class states permitted (intentional divergence from upstream which requires ≥1 needle)
- Non-ASCII chars (≥128) break out of the scan and fall through to normal DFA processing

- Engines: PikeVM + lazy DFA + bounded backtracker + one-pass DFA + dense DFA (with acceleration)
- Tests: 2,197 total, 0 failures
- Key wins vs S12: charClass **+9%** (94 → 102), captures **+25%** (16.7k → 20.9k, regression fixed), rawCharClass **+5%** (105 → 110)
- Key wins vs S11 baseline: charClass **+27%**, unicodeWord **+22%**, captures **+14%**, rawCharClass **+24%**, rawUnicodeWord **+61%**

### stage-14-multi-start (`0b70a57`)
**Multi-position start states for dense DFA**

Enables the dense DFA for patterns with look-assertions (`^`, `$`, `\b`) by supporting 10 start states (5 `Start` variants × anchored/unanchored). Removes the `lookSetAny.isEmpty()` guard from `DenseDFABuilder`.

Key design change: switched from range-based match detection (`sid >= minMatchState`) to MATCH_FLAG-based detection (`sid < 0`) in `DenseDFA.searchFwd`. This is necessary because look-assertion patterns have conditional matches where a state may be a match only on certain transitions (depending on look-ahead context). Range-based detection would incorrectly signal matches regardless of context.

- Engines: unchanged from stage 13
- Tests: 2,201 total, 0 failures
- Key wins vs S13: rawMultiline **+10%** (214 → 237), multiline **+5%** (215 → 228), wordRepeat **+4%** (96k → 100k)
- Initially had charClass -8% regression from MATCH_FLAG per-transition overhead. Fixed by restoring range-based match detection (`sid >= minMatchState`) — match property is per-state not per-transition (confirmed by upstream `dfa/search.rs`). Builder now introduces "dead-match" and "match-wrapper" synthetic states to preserve delayed-match semantics without MATCH_FLAG on transitions.
- Final S14 results: charClass **recovered** (102), rawCharClass **+5%** (110 → 115), multiline **+6%** (215 → 229), rawMultiline **+12%** (214 → 241), captures **+2%** (20.9k → 21.3k).

### stage-15-special-state-taxonomy (`ed291af`)
**Special-state taxonomy: layout inversion + cold-path acceleration dispatch**

Restructures the dense DFA to match upstream's special-state taxonomy (`special.rs:142-180`). All special states (dead, quit, match, accel) are shuffled to the bottom of the state ID space with a single `maxSpecial` threshold guard. Acceleration moves from the hot loop to the cold-path special-state dispatch.

Key changes:
- Dead=0, quit=stride (was padding/dead/quit at 0/stride/2*stride)
- Unrolled loop guard: `sid <= maxSpecial` (1 comparison, was `sid <= q || sid >= mms` = 2)
- No acceleration check in hot path — fires only in special-state dispatch
- Start states participate in accel classification (removed `i == 0` skip)
- Space exclusion for accel needles (upstream `accel.rs:449-458`)
- Compact densely-indexed accel tables (boolean[128][] indexed by accelIndex, not sparse by sid/stride)

- Engines: unchanged from stage 14
- Tests: 2,282 total, 0 failures
- 3-fork results vs S14: multiline **+41%** (229 → 324), captures stable, charClass -8% (100 → 92)
- Also includes match-delay elimination (recording `at+1` in dispatch instead of per-iteration delay check)
- **wordRepeat regression discovered:** 91K (S14) → 56K (S15) = -39%. Under investigation.

## Benchmark Comparison — Authoritative 3-Fork Results (2026-03-19)

All numbers below are from a single overnight backfill run using proper JMH parameters:
3 forks, tiered warmup (slow benchmarks: 20s × 10 warmup iters; medium: 10s × 5; fast: 5s × 5).
Raw data in `docs/benchmarks/stage-*-benchmarks.txt`.

| Benchmark | S6 | S10 | S12 | S14 | **S15** | JDK (S15) | **vs JDK** |
|---|---|---|---|---|---|---|---|
| literal (ops/s) | 5,206 | 5,418 | 4,943 | 4,870 | **4,827** | 3,271 | **1.5x faster** |
| literalMiss | — | 5,658 | 5,279 | 5,170 | **5,172** | 2,447 | **2.1x faster** |
| charClass | 79 | 91 | 106 | 100 | **92** | 253 | 2.7x slower |
| alternation | 51 | 52 | 51 | 46 | **46** | 105 | 2.3x slower |
| captures | 414 | 20,350 | 18,564 | 21,352 | **21,663** | 17,867 | **1.2x faster** |
| unicodeWord | 21 | 18,112 | 20,379 | 18,797 | **17,449** | 35,172 | 2.0x slower |
| multiline | — | 275 | 225 | 229 | **324** | 1,031 | 3.2x slower |
| wordRepeat | — | 103,825 | 94,733 | 91,415 | **55,888** | 11,568 | **4.8x faster** |

### Measurement methodology change (2026-03-19)

All numbers prior to this date (S1–S14 in the old table below) used `@Fork(1)` with 2-second
iterations and 3 warmup iterations. This was insufficient:

1. **Single-fork misses JIT variance.** wordRepeat measured 87K (1-fork) vs 60K (3-fork) — a 47% discrepancy.
2. **Short warmup under-warms slow benchmarks.** charClass at ~100 ops/s got only ~600 warmup
   invocations vs the ~10K C2 needs to compile. charClass and alternation may have been measured
   with C1/interpreter code in all stages prior to this fix.
3. **JDK baseline varies ~20% across runs** (charClass JDK: 321 at S6 run, 253 at S15 run).
   Cross-stage JDK ratios are only meaningful within the same run.

The 3-fork table above is the authoritative reference. The old single-fork table is preserved below
for historical context but should not be used for performance comparisons.

<details>
<summary>Historical single-fork numbers (unreliable — click to expand)</summary>

| Benchmark | S1 | S6 | S9 | S10 | S11 | S12 | S13 | S14 | JDK |
|---|---|---|---|---|---|---|---|---|---|
| literal (ops/s) | 14 | 4,663 | 3,326 | 4,787 | 4,880 | 4,956 | 4,821 | 4,930 | 2,564 |
| charClass | 0.06 | 70.6 | 60.9 | 81.4 | 83.5 | 94 | 102 | 102 | 289 |
| alternation | 6.5 | 45.0 | 39.0 | 44.1 | 45.3 | 46.5 | 46.5 | 46.6 | 105 |
| captures | 60 | 350 | 12,362 | 16,928 | 18,190 | 16,499 | 20,939 | 21,290 | 18,831 |
| unicodeWord | 13 | 18.3 | 11,291 | 15,267 | 15,037 | 19,807 | 19,264 | 19,070 | 38,147 |
| multiline | — | — | 192 | 233 | 215 | 215 | 215 | 229 | 1,034 |
| literalMiss | — | — | 3,982 | 5,107 | 5,223 | 5,252 | 5,147 | 5,123 | 3,248 |
| wordRepeat | — | — | 85,364 | 93,662 | 90,326 | 92,508 | 96,098 | 91,825 | 11,246 |

</details>

### Key findings from the re-measurement

1. **wordRepeat regression is real and large.** S10 (103,825) → S12 (94,733) → S14 (91,415) → S15 (55,888). The dense DFA introduction (S12) started the decline, and the special-state taxonomy (S15) accelerated it. Under investigation.
2. **charClass peaked at S12 (106) and has regressed.** S12 → S14 (100) → S15 (92). The dense DFA was the high point; subsequent changes to the search loop shape have hurt.
3. **multiline improvement is confirmed.** S14 (229) → S15 (324) = +41%. The taxonomy dispatch with acceleration is working.
4. **captures is stable and competitive.** 21,663 vs JDK 17,867 = 1.2x faster since S10.
5. **literal/literalMiss are stable.** These use PrefilterOnly strategy (indexOf), not affected by DFA changes.

### Historical notes (from single-fork era)
- S4 unicodeWord (15,717) was based on three-phase with DFA edge bugs (27 test failures). S7 unicodeWord (13,499) is fully correct.
- S5 regressions vs S4 were caused by the removal of three-phase search, not the prefilter work.
- S7 unicodeWord improvement is from equivalence class merging + surrogate-pair resolution.
- S9 captures improvement is from the one-pass DFA replacing PikeVM on the narrowed capture window.
