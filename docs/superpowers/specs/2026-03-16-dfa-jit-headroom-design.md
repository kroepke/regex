# DFA JIT Headroom: Cold Path Extraction + Prefilter at Start State

**Date:** 2026-03-16
**Status:** Draft
**Depends on:** Stage 10 (allocation cleanup)

## Motivation

`searchFwdLong()` is 613 bytecode bytes. JIT analysis (`-XX:+PrintInlining`) shows that C2 only inlines 2 of 5 `classify()` call sites (43 bytes each) — it exhausts its inline budget after the first unrolled position and the outer dispatch. Positions 1-3 in the 4× unrolled inner loop make full method calls to `classify`, costing 2 array loads per char instead of the inlined fast path.

The method is also flagged **"hot method too big"** by callers (`dfaSearch`), preventing it from being inlined into the caller.

Previous attempt to add prefilter-at-start code inside `searchFwdLong` caused a JIT optimization cliff — even dead code caused 2.3× regressions. The root cause was pushing the method past C2's optimization thresholds.

## Approach

**Two phases with a hard gate between them.**

Phase 1 extracts cold paths from `searchFwdLong()` to reduce bytecode size, creating JIT headroom. Phase 2 (conditional on Phase 1 passing) adds prefilter-at-start into the freed headroom.

Phase 2 only proceeds if Phase 1 demonstrates:
1. The `classify` inline count increases (from 2/5 to 4/5 or 5/5)
2. Benchmarks show no regression (improvement expected)

## JIT Context

**Key C2 thresholds (JDK 21 defaults):**

| Threshold | Value | Relevance |
|-----------|-------|-----------|
| `MaxInlineSize` | 35 bytes | `classify` (43b) exceeds this — only inlined when "hot" |
| `FreqInlineSize` | 325 bytes | Maximum bytecode size for hot method inlining |
| `MaxNodeLimit` | 80,000 | IR node limit for C2 compilation |
| `LiveNodeCountInliningCutoff` | 40,000 | C2 stops inlining when live node count exceeds this |
| `DontCompileHugeMethods` | true | Methods above 8,000 bytes are not compiled |

**Current searchFwdLong inlining (C2 compilation, measured):**
- `classify` (43b): inline at 2 of 5 call sites, rejected at 3 ("too big" — budget exhausted)
- `nextState` (9b): inline at all 5 call sites
- `computeNextState` (902b): never inlined (correct — cold path)
- `getOrComputeStartState` (353b): never inlined (correct — runs once)

## Phase 1: Cold Path Extraction

### What to extract

**A. Slow-path dispatch** (lines 156-187 in current `searchFwdLong`)

Handles UNKNOWN, dead, and quit states — cache misses and terminal conditions. Hit rarely during steady-state search (only on first encounter of a DFA state, or at end of match).

Extract to a private method:
```java
/**
 * Handle slow-path state transitions: UNKNOWN (compute new state),
 * dead (stop searching), or quit (give up to PikeVM).
 *
 * Returns a primitive-encoded result:
 * - If positive: the new state ID (continue searching with this sid)
 * - If SearchResult.isGaveUp(): return this value from searchFwdLong
 * - If SearchResult.isNoMatch(): the state is dead, break the loop
 *
 * Updates lastMatchEnd in the cache if a match-flagged state is computed.
 */
private int handleSlowTransition(DFACache cache, int sid, int classId,
                                  char inputChar, long charsSearched)
```

The caller checks the return value and either continues, breaks, or returns.

**Estimated bytecode savings:** ~100-120 bytes

**B. Right-edge transition** (lines 190-216 in current `searchFwdLong`)

Runs exactly once per search call, after the main loop exits. Handles end-of-input and span-boundary look-ahead context.

Extract to a private method:
```java
/**
 * Process the right-edge transition after the main search loop.
 * Handles EOI class or the character just past the search span
 * for correct look-ahead context ($ and \b assertions).
 *
 * Returns the updated lastMatchEnd (-1 if no match at edge).
 */
private int handleRightEdge(DFACache cache, int sid, char[] haystack,
                             int end, int lastMatchEnd, long charsSearched)
```

**Estimated bytecode savings:** ~100-110 bytes

**C. Mirror in searchRevLong** (625 bytes)

Apply the same extractions to `searchRevLong()`:
- Slow-path dispatch → `handleSlowTransitionRev()`
- Left-edge transition → `handleLeftEdge()`

### What stays in searchFwdLong

The hot path only:
- Field reads and start state computation call
- The 4× unrolled inner loop (classify + nextState per position, break on special)
- Fast outer dispatch: nextSid > quit (advance), nextSid < 0 (match — record and advance)
- Call to extracted slow-path helper
- Call to extracted right-edge helper
- Return logic

**Estimated post-extraction size:** ~385-420 bytecode bytes

### JIT Validation Protocol

This is the gate for proceeding to Phase 2.

**Step 1: Record baseline JIT behavior**

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation \
  -XX:LogFile=/tmp/jit-baseline.xml \
  -jar regex-bench/target/benchmarks.jar "RawEngineBenchmark.rawCharClassOhai" \
  -f 1 -wi 2 -i 1 -t 1
```

Parse the XML to count `classify` inline successes inside `searchFwdLong`'s C2 compilation. Expected baseline: 2 of 5.

**Step 2: Record baseline benchmarks**

```bash
java -jar regex-bench/target/benchmarks.jar \
  "SearchBenchmark|RawEngineBenchmark" -f 1 -wi 3 -i 5
```

**Step 3: Apply cold path extractions**

Implement the extractions described above. Run full test suite (2,186 tests).

**Step 4: Record post-extraction JIT behavior**

Same `-XX:+LogCompilation` run. Parse and count `classify` inline successes.

**Step 5: Compare inline counts**

| Metric | Baseline | Post-extraction | Gate |
|--------|----------|-----------------|------|
| `classify` inlined in searchFwdLong | 2/5 | ? | Must increase |
| `classify` inlined in searchRevLong | ? | ? | Must not decrease |
| searchFwdLong bytecode size | 613b | ~385-420b | Must decrease |

**Step 6: Run post-extraction benchmarks**

Same benchmark suite as Step 2. Compare.

| Metric | Gate |
|--------|------|
| No benchmark regresses by >2× | Required |
| charClass/unicodeWord improve | Expected (more classify inlining) |

**Step 7: Decision**

- If inline count increased AND benchmarks improved or held: **proceed to Phase 2**
- If inline count increased but benchmarks regressed: **investigate** — the extracted helpers may be too expensive to call. Consider `@jdk.internal.vm.annotation.ForceInline` or restructuring.
- If inline count did NOT increase: **stop** — the theory was wrong. Document findings and reconsider approach.

## Phase 2: Prefilter at Start State (conditional)

**Only proceeds if Phase 1 passes the JIT gate.**

### Changes to LazyDFA

Add an optional `Prefilter` reference:

```java
private final Prefilter prefilter; // null if no prefilter available
```

Passed via a new factory method or added to `create()`. Only the forward DFA receives a prefilter (reverse DFA searches narrowed windows where prefilter doesn't help).

Cache the start state ID for the `sid == startSid` check:

```java
private int cachedStartSid = DFACache.UNKNOWN;
```

Set on first `getOrComputeStartState()` call.

### Integration points in searchFwdLong

**Point 1 — Before the main loop:**

If prefilter exists, search is unanchored, and the start state is universal (`lookSetAny.isEmpty()`), skip to the first candidate:

```java
if (prefilter != null && !input.isAnchored() && lookSetAny.isEmpty()) {
    int candidate = prefilter.find(input.haystackStr(), pos, end);
    if (candidate < 0) return SearchResult.NO_MATCH;
    charsSearched += (candidate - pos);
    pos = candidate;
}
```

**Point 2 — When returning to start state in outer dispatch:**

After the unrolled inner loop breaks out, if the current state equals the cached start state:

```java
if (sid == cachedStartSid && prefilter != null) {
    int candidate = prefilter.find(input.haystackStr(), pos, end);
    if (candidate < 0) break;
    if (candidate > pos) {
        charsSearched += (candidate - pos);
        pos = candidate;
    }
}
```

**Guard: universal start only.** Patterns with look-assertions (word boundaries, line anchors) have position-dependent start states. The prefilter skip changes `pos`, which would require recomputing the start state. For simplicity, prefilter-at-start only activates when `lookSetAny.isEmpty()` — covering the common case of simple literal-prefix patterns. Patterns with look-assertions continue to use the existing Strategy-level prefilter loop.

**Estimated bytecode addition:** ~50-60 bytes. With Phase 1 freeing ~200 bytes, this fits with ~140 bytes to spare.

### Changes to Regex.java / Strategy

`Regex.create()` passes the prefilter to `LazyDFA.create()` when building the forward DFA for the `Core` strategy. The prefilter is the same one extracted from `LiteralExtractor.extractPrefixes()`.

### JIT Validation (Phase 2)

After adding prefilter code, repeat the `-XX:+PrintInlining` analysis. Verify:
- `classify` inline count is still ≥ Phase 1 level (not regressed)
- searchFwdLong bytecode size is still below ~500 bytes
- No "hot method too big" regression

### Expected benchmark impact

| Benchmark | Expected change | Why |
|-----------|----------------|-----|
| multiline `(?m)^.+$` | Large improvement | DFA in start state between lines; prefilter uses `indexOf('\n')` |
| literal `"Sherlock Holmes"` | Moderate improvement | Start-state skip on first and subsequent matches |
| literalMiss `"ZQZQZQZQ"` | Large improvement | Single prefilter call returns -1 immediately |
| charClass `[a-zA-Z]+` | No change | No literal prefix — prefilter doesn't apply |
| alternation | No change | Would need Aho-Corasick (separate effort) |
| unicodeWord `\w+` | No change | Has look-assertions (word boundary) — universal start guard skips |

## Testing Strategy

1. **All existing tests must pass** (2,186 tests) after each phase
2. **JIT validation is a first-class test** — not just benchmarks, but `-XX:+PrintInlining` analysis confirming the inline count hypothesis
3. **Benchmark comparison** with same-session baseline/post protocol (as established in stage-10)

## Risks

| Risk | Mitigation |
|------|-----------|
| Cold path extraction doesn't increase inline count | Phase 1 gate — stop if theory is wrong |
| Extracted methods add call overhead that offsets inline gains | Benchmark gate; C2 may inline the helpers anyway if they're small enough |
| Prefilter code pushes past JIT threshold again | JIT validation in Phase 2; ~140 bytes of spare headroom |
| `haystackStr()` lazy computation adds latency on first prefilter call | Already cached by `Input.of()` — zero cost |
| Prefilter false positives cause DFA restart overhead | Same trade-off as upstream; prefilter only fires at start state |

## Summary

| Phase | Files Modified | Risk | Gate |
|-------|---------------|------|------|
| Phase 1: Extract cold paths | LazyDFA.java | Low | JIT inline count must increase |
| Phase 2: Prefilter at start | LazyDFA.java, Regex.java | Medium | JIT inline count must not regress |
