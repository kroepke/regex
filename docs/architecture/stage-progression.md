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

## Benchmark Comparison (same machine, 2026-03-14)

| Benchmark | S1 | S2 | S3 | S4 | S5 | S6 | S7 |
|---|---|---|---|---|---|---|---|
| literal (ops/s) | 14 | 4,746 | 4,225 | 4,491 | 4,380 | 4,663 | 4,543 |
| charClass | 0.06 | 9.5 | 11.0 | 69.5 | 11.6 | 70.6 | 75.1 |
| alternation | 6.5 | 44.8 | 44.3 | 44.4 | 43.1 | 45.0 | 45.0 |
| captures | 60 | 58.8 | 58.7 | 452 | 110.6 | 350 | 356 |
| unicodeWord | 13 | 12.9 | 12.6 | 15,717 | 12.3 | 18.3 | **13,499** |
| backtrack | 489K | 16.4M | 19.9M | 16.0M | 141M | — | — |
| redosShort | 40.7K | 37.9K | 38.0K | 2.3M | 35.3K | — | — |

Notes:
- S4 unicodeWord (15,717) was based on three-phase with DFA edge bugs (27 test failures). S7 unicodeWord (13,499) is fully correct.
- S5 regressions vs S4 were caused by the removal of three-phase search, not the prefilter work.
- S7 unicodeWord improvement is from equivalence class merging + surrogate-pair resolution, enabling the DFA to handle full Unicode without quit fallback.
