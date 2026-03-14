# Lazy DFA Loop Unrolling — Design Spec

**Date:** 2026-03-14
**Status:** Draft
**Scope:** Performance optimization — no semantic changes

## Goal

Improve lazy DFA search throughput by unrolling the main transition loop 4×, reducing per-char branch overhead and enabling better JIT optimization. Applies to both forward and reverse search.

## Context

The current `LazyDFA.searchFwd()` and `searchRev()` process one transition per loop iteration, with inline checks for match states, UNKNOWN, DEAD, and QUIT on every step. The upstream Rust regex crate unrolls this loop 4× and reports ~30-50% throughput improvement on common patterns.

This is a **pure optimization** — no changes to match semantics, state encoding, or strategy selection.

### Upstream Reference

- `upstream/regex/regex-automata/src/hybrid/search.rs:110-221` (forward)
- `upstream/regex/regex-automata/src/hybrid/search.rs:339-397` (reverse)
- Upstream tested three configurations and settled on 4× outer unrolling with no inner self-transition loop (`unroll3`)

## Design Constraints

- **Safe Java only** — no `sun.misc.Unsafe` or `jdk.internal.misc.Unsafe`. Rely on JIT bounds-check elimination.
- **JIT-first optimization** — structure code to help HotSpot C2 rather than faithfully porting Rust's `unsafe` patterns.
- **Current state encoding preserved** — no changes to `DFACache` sentinel layout. A separate encoding experiment (Scheme 2: high-bit tags) may follow later, benchmarked independently.
- **Both directions** — forward and reverse unrolled together; they are structural mirrors.

## Architecture

### Loop Structure

Split the search into an **outer dispatch loop** and an **inner unrolled loop**:

```
outer loop: while (pos < end)
  │
  ├─ inner unrolled loop: while (pos + 3 < end)
  │    ├─ Step 0: classify → lookup → check (s0 <= quit?)
  │    ├─ Step 1: classify → lookup → check (s1 <= quit?)
  │    ├─ Step 2: classify → lookup → check (s2 <= quit?)
  │    ├─ Step 3: classify → lookup → check (s3 <= quit?)
  │    └─ All fast: sid = s3, pos += 4, charsSearched += 4
  │
  ├─ [on break-out: sid = last good state, pos = triggering char]
  │
  ├─ slow-path dispatch (existing logic, unchanged):
  │    ├─ nextSid < 0       → record match, strip flag
  │    ├─ nextSid == UNKNOWN → computeNextState, cache result
  │    ├─ nextSid == dead    → break
  │    └─ nextSid == quit    → return GaveUp
  │
  └─ tail loop: remaining 0-3 chars processed one at a time
```

### Forward Search — Inner Loop

```java
while (pos + 3 < end) {
    int s0 = cache.nextState(sid, charClasses.classify(haystack[pos]));
    if (s0 <= quit) { break; }

    int s1 = cache.nextState(s0, charClasses.classify(haystack[pos + 1]));
    if (s1 <= quit) { sid = s0; pos++; break; }

    int s2 = cache.nextState(s1, charClasses.classify(haystack[pos + 2]));
    if (s2 <= quit) { sid = s1; pos += 2; break; }

    int s3 = cache.nextState(s2, charClasses.classify(haystack[pos + 3]));
    if (s3 <= quit) { sid = s2; pos += 3; break; }

    sid = s3;
    pos += 4;
    cache.charsSearched += 4;
}
```

**On break-out:** `sid` is set to the last successful (non-special) state, `pos` points to the char that produced the special state. The outer loop re-classifies `haystack[pos]` and runs the existing slow-path dispatch. Re-classifying one char is cheap (two array lookups) and keeps the break-out path simple.

### Reverse Search — Inner Loop

```java
while (pos - 3 >= start) {
    int s0 = cache.nextState(sid, charClasses.classify(haystack[pos]));
    if (s0 <= quit) { break; }

    int s1 = cache.nextState(s0, charClasses.classify(haystack[pos - 1]));
    if (s1 <= quit) { sid = s0; pos--; break; }

    int s2 = cache.nextState(s1, charClasses.classify(haystack[pos - 2]));
    if (s2 <= quit) { sid = s1; pos -= 2; break; }

    int s3 = cache.nextState(s2, charClasses.classify(haystack[pos - 3]));
    if (s3 <= quit) { sid = s2; pos -= 3; break; }

    sid = s3;
    pos -= 4;
    cache.charsSearched += 4;
}
```

### JIT Bounds-Check Elimination

The `pos + 3 < end` guard (forward) and `pos - 3 >= start` guard (reverse) give HotSpot C2 a clear range proof:

- **Forward:** `pos + 3 < end` implies `haystack[pos]` through `haystack[pos + 3]` are in bounds (assuming `end <= haystack.length`, which is guaranteed by `Input`).
- **Reverse:** `pos - 3 >= start` implies `haystack[pos]` through `haystack[pos - 3]` are in bounds (assuming `start >= 0`).
- **Transition table:** `cache.nextState(sid, classId)` accesses `transTable[sid + classId]`. The JIT may or may not eliminate this bounds check — it depends on whether C2 can prove the index is in range. This is acceptable; the `transTable` access is a single array lookup that is likely L1-hot.

### Fast-Path Guard

The current encoding uses `nextSid > quit` as the fast-path guard:

```
UNKNOWN = 0
dead    = stride        (e.g. 64)
quit    = 2 * stride    (e.g. 128)
normal  = 3*stride+     (always > quit)
match   = normal | 0x8000_0000 (always < 0, i.e. <= quit)
```

In the unrolled loop, `s <= quit` catches all special states:
- Match states: `s < 0` (bit 31 set), so `s <= quit` is true
- UNKNOWN: `s == 0`, so `s <= quit` is true
- Dead: `s == stride`, so `s <= quit` is true
- Quit: `s == 2*stride`, so `s <= quit` is true

This is a single integer comparison per step — equivalent cost to upstream's `is_tagged()` bitmask check.

### Future Experiment: High-Bit Tag Encoding (Scheme 2)

A potential alternative encoding where all special states use high-bit tags:

```
TAG_BIT  = 0x4000_0000  (bit 30)
MATCH    = 0x8000_0000  (bit 31, unchanged)
normal   = 0, stride, 2*stride, ...  (bits 30-31 clear)
unknown  = TAG_BIT | 1
dead     = TAG_BIT | 2
quit     = TAG_BIT | 3
Fast-path guard: (nextSid & 0xC000_0000) == 0
```

This is **not part of this implementation**. It will be benchmarked as a separate follow-up experiment only if the unrolling shows promise and we want to squeeze out more.

## Changes

### Files Modified

| File | Change | Risk |
|------|--------|------|
| `LazyDFA.java` | Replace inner loops of `searchFwd()` and `searchRev()` with 4× unrolled versions | Low — slow path unchanged, full test suite validates |
| `SearchBenchmark.java` | Add 3 targeted benchmark patterns (×2 for ohai/jdk) | None — additive |
| `Haystacks.java` | Potentially add/verify haystacks for new benchmarks | None — additive |

### Files NOT Modified

- `DFACache.java` — encoding unchanged
- `CharClasses.java` — `classify()` unchanged
- `computeNextState()` — slow path unchanged
- Edge transitions (right-edge / left-edge) — unchanged
- `Strategy.java` — unchanged

## Benchmarks

### Targeted Patterns

Three patterns that stress different DFA behaviors:

| Pattern | Haystack | DFA Behavior | Expected Impact |
|---------|----------|-------------|-----------------|
| `\w{50}` | `UNICODE_MIXED` | High state-creation (many UNKNOWN hits) | Neutral or slight regression (worst case) |
| `(?m)^.+$` | `SHERLOCK_EN` | Moderate transitions with look-around | Moderate improvement |
| `ZQZQZQZQ` | `SHERLOCK_EN` | Pure self-transitions, literal not found | Maximum improvement (best case) |

### Measurement Protocol

1. **Baseline:** Run full benchmark suite on `main` before the change
2. **After:** Run full suite with unrolling applied
3. **Gate:** Any benchmark changing by >2× in either direction requires investigation before committing (per project rules)
4. **Existing benchmarks:** Must not regress (literal, charClass, alternation, captures, unicodeWord)

## Risks & Mitigations

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| JIT pessimization from larger loop body | Low | Benchmark before/after; revert if regression |
| Correctness bug in break-out logic | Low | Full 2,154-test suite; no semantic changes |
| `charsSearched` inaccuracy | None | Fast path adds 4, slow path adds 1 — sum is correct |
| Bounds check not eliminated by JIT | Medium | Acceptable fallback — bounds checks are cheap vs. the transition lookup |

## Non-Goals

- No `Unsafe` / `VarHandle` for unchecked array access
- No inner self-transition loop (upstream removed it due to regressions)
- No start-state specialization changes
- No encoding changes (Scheme 2 is a separate experiment)
- No changes to `Strategy.java` or engine selection
