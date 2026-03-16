# Dense DFA Engine — Spec 1: Compilation + Search

**Date:** 2026-03-16
**Status:** Draft
**Part of:** Full DFA engine (multi-spec project)
**Depends on:** Stage 11 (JIT headroom)

## Project Context

This is the first of three planned specs for a full pre-compiled DFA engine:

| Spec | Scope | Deliverable |
|------|-------|-------------|
| **Spec 1 (this)** | Dense DFA compilation + search | New `DenseDFA` engine, integrated into Strategy as forward DFA alternative |
| Spec 2 | Acceleration states | Escape tables for self-looping states, target benchmark improvements |
| Spec 3 | Selection heuristics | Meta strategy auto-selects DenseDFA vs LazyDFA |

Each spec is independently testable and benchmarkable. Spec 1 produces a working engine. Spec 2 adds the acceleration that closes the benchmark gaps. Spec 3 makes it automatic.

## Motivation

The lazy DFA (`LazyDFA`) builds states on demand during search. This has three costs:
1. **UNKNOWN checks** — every transition in the inner loop checks for uncached states
2. **No acceleration** — upstream's acceleration states require knowing a state's full transition profile, which the lazy DFA doesn't have until all transitions are visited
3. **HashMap overhead** — state deduplication uses `HashMap<StateContent, Integer>` instead of contiguous arrays

The upstream Rust regex crate has a separate full (dense) DFA engine (`dfa/dense.rs`) that pre-compiles all states and transitions at build time. This eliminates UNKNOWN handling, enables acceleration (Spec 2), and uses flat arrays for maximum cache locality.

Our target patterns have small DFA state counts:
- `[a-zA-Z]+` → ~3 states
- `(?m)^.+$` → ~4 states (with look-assertion encoding)
- `\w+` → ~3 states (with quit classes)
- `Sherlock|Watson|Holmes|Irene` → ~30 states

All fit comfortably within a pre-compiled DFA.

## What We Build (Spec 1)

A `DenseDFA` class that:
1. Compiles an NFA into a complete DFA with all transitions pre-computed
2. Stores transitions in a flat `int[]` table for O(1) lookup
3. Provides a forward search method with no UNKNOWN handling
4. Integrates into `Strategy.Core` as the forward DFA when available
5. Falls back to `LazyDFA` when state count exceeds a limit

**Not in scope for Spec 1:** Acceleration states (Spec 2), automatic selection heuristics (Spec 3), dense reverse DFA, DFA minimization, serialization, overlapping search, multi-pattern per-pattern start states.

## Upstream Reference

**Determinization:** `upstream/regex/regex-automata/src/dfa/determinize/mod.rs` — the `determinize()` function drives a worklist, computing all transitions for each state. We adapt this pattern but reuse our existing `LazyDFA.computeNextState()` and `epsilonClosure()` instead of porting the Rust determinizer.

**Dense DFA structure:** `upstream/regex/regex-automata/src/dfa/dense.rs` — flat transition table, special state ranges, stride-multiplied state IDs.

**Search:** `upstream/regex/regex-automata/src/dfa/search.rs:45-186` — `find_fwd_imp()` is 142 lines with 4× unrolling and special-state dispatch. Our version is simpler (no acceleration yet, no prefilter — those come in Spec 2).

## Design

### DenseDFA Class

```
File: regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFA.java

DenseDFA:
  final int[] transTable       — flat [stateCount * stride], indexed by sid + classId
  final CharClasses charClasses
  final int startAnchored      — start state ID for anchored search
  final int startUnanchored    — start state ID for unanchored search
  final int minMatchState      — states with sid >= this are match states
  final int dead               — dead state ID (stride)
  final int quit               — quit state ID (stride * 2)
  final int stateCount         — total number of DFA states
```

State IDs are stride-multiplied (same convention as LazyDFA/DFACache). States are shuffled at build time so match states occupy a contiguous range at the end: `[minMatchState, stateCount * stride)`. Dead and quit are at fixed positions (stride and stride*2).

The class is immutable and thread-safe. No per-thread cache needed.

### DenseDFABuilder

```
File: regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilder.java

DenseDFABuilder:
  - Uses a temporary LazyDFA + DFACache to do powerset construction
  - Drives a worklist: for each state, compute transitions for ALL equivalence classes
  - Tracks states via DFACache (reuses existing determinization logic)
  - After completion: copies transitions into flat int[] table
  - Shuffles match states to end (same technique as OnePassBuilder.shuffleMatchStates)
  - Returns DenseDFA or null if state limit exceeded
```

**Why reuse LazyDFA's determinization:** The `computeNextState` method in LazyDFA implements:
- Powerset construction (NFA state sets → DFA states)
- Look-assertion encoding (two-phase: look-ahead on source, look-behind on destination)
- Leftmost-first match semantics (break on Match NFA state)
- Quit-char handling

This is ~150 lines of complex, well-tested code. Duplicating it would be error-prone. Instead, the builder creates a temporary LazyDFA and DFACache, forces all transitions to be computed, then extracts the results into the dense format.

**Upstream ref:** `determinize/mod.rs:60-200` — worklist-driven, calls `add_transition` per (state, class) pair.

### Build Algorithm

```
1. Create temporary LazyDFA + DFACache for the given NFA + CharClasses
2. Compute start states (anchored + unanchored)
3. Initialize worklist with start states
4. While worklist not empty:
   a. Pop a state ID from worklist
   b. Snapshot stateCount = cache.stateCount()
   c. For each equivalence class (0..classCount), inclusive of EOI class:
      - Call computeNextState on the temporary LazyDFA
      - Cache the transition in the temporary DFACache
   d. For each newly-created state (index stateCount..cache.stateCount()):
      - Add the new state ID to worklist
   e. If state count exceeds limit (e.g., 5,000): return null
5. Copy transitions from DFACache.transTable into flat int[]
6. Shuffle match states to end, compute minMatchState
7. Remap ALL transition targets in the flat table to reflect shuffled IDs.
   Dead and quit sentinels remain at their fixed positions.
8. Return DenseDFA
```

**State limit:** Memory-based rather than flat state count. Default: 2MB (matching the lazy DFA's default cache capacity). Effective state limit = `maxMemory / (stride * 4)`. At stride=64 this is ~8,000 states; at stride=256 this is ~2,000 states. This avoids surprises with Unicode-heavy patterns that have large strides.

**Accessing LazyDFA internals:** The builder needs to call `computeNextState`, `epsilonClosure`, and `getOrComputeStartState` — which are currently private on LazyDFA. Options:
- Make them package-private (both classes in `dfa.lazy` or a shared parent package)
- Move the builder into the same package as LazyDFA
- Extract determinization logic into a shared utility

**Recommendation:** Place `DenseDFABuilder` in `dfa.dense` package and make the necessary LazyDFA methods package-private with a `// visible for DenseDFABuilder` comment. Alternatively, expose a minimal `DeterminizationDriver` interface — but that's over-engineering for Spec 1. The simplest approach: make `computeNextState`, `epsilonClosure`, `collect`, and `getOrComputeStartState` accessible to the builder. Since they're in different packages, this means making them `public` or using a shared helper. The cleanest option for now is to extract the worklist-driving logic into a new method on LazyDFA that the builder calls:

```java
// On LazyDFA — new public method for DenseDFABuilder
// Computes transitions for ALL equivalence classes including EOI.
// Returns the number of states before computation (caller compares
// with cache.stateCount() after to discover newly-created states).
public int computeAllTransitions(DFACache cache, int sid) {
    int beforeCount = cache.stateCount();
    int rawSid = sid & 0x7FFF_FFFF;
    // Include EOI class (classCount) — needed for right-edge transitions
    for (int cls = 0; cls <= charClasses.classCount(); cls++) {
        if (cache.nextState(rawSid, cls) == DFACache.UNKNOWN) {
            int nextSid = computeNextState(cache, rawSid, cls);
            cache.setTransition(rawSid, cls, nextSid);
        }
    }
    return beforeCount;
}
```

This keeps `computeNextState` private while exposing a single, safe public method for eager computation.

### Search Method

```java
public long searchFwd(Input input) {
    char[] haystack = input.haystack();
    int pos = input.start();
    int end = input.end();
    int sid = input.isAnchored() ? startAnchored : startUnanchored;
    int lastMatchEnd = -1;

    while (pos < end) {
        // Unrolled inner loop — 4 transitions, no UNKNOWN possible
        while (pos + 3 < end) {
            int s0 = transTable[sid + charClasses.classify(haystack[pos])];
            if (s0 <= quit || s0 >= minMatch) break;
            int s1 = transTable[s0 + charClasses.classify(haystack[pos + 1])];
            if (s1 <= quit || s1 >= minMatch) { sid = s0; pos++; break; }
            int s2 = transTable[s1 + charClasses.classify(haystack[pos + 2])];
            if (s2 <= quit || s2 >= minMatch) { sid = s1; pos += 2; break; }
            int s3 = transTable[s2 + charClasses.classify(haystack[pos + 3])];
            if (s3 <= quit || s3 >= minMatch) { sid = s2; pos += 3; break; }
            sid = s3; pos += 4;
        }
        if (pos >= end) break;

        // After inner loop break-out, check if current sid is a match state.
        // Match semantics: sid >= minMatch means the SOURCE state contained
        // an NFA Match — the match end is the current pos (one past the
        // last consumed char). Record it before taking the next transition.
        if (sid >= minMatch) {
            lastMatchEnd = pos;
        }

        sid = transTable[sid + charClasses.classify(haystack[pos])];
        if (sid >= minMatch) {
            lastMatchEnd = pos + 1;
            pos++;
        } else if (sid == dead) {
            break;
        } else if (sid == quit) {
            return SearchResult.gaveUp(pos);
        } else {
            pos++;
        }
    }

    // Right-edge transition for look-ahead context
    lastMatchEnd = handleRightEdge(sid, haystack, end, lastMatchEnd);
    if (lastMatchEnd == -2) return SearchResult.gaveUp(end);

    if (lastMatchEnd >= 0) return SearchResult.match(lastMatchEnd);
    return SearchResult.NO_MATCH;
}
```

**Right-edge handling:** Same as lazy DFA — transition on the character past the search span (or EOI class) for correct `$` and `\b` context. Extracted into a small helper on DenseDFA itself (not shared with LazyDFA — the dense DFA does its own table lookup, no `computeNextState` call since all transitions are pre-computed).

**No `charsSearched` tracking** — the dense DFA never gives up due to cache pressure (there's no cache to clear). The only give-up is quit chars.

**Match semantics:** Identical to lazy DFA. Matches are delayed by one char (match flag on destination state). `sid >= minMatchState` means the source state contained an NFA Match, and the match end is the current `pos`. **Critical:** after the unrolled inner loop breaks out (because it saw `s_N >= minMatch`), the outer dispatch must check if the current `sid` is a match state BEFORE taking the next transition — otherwise the match goes unrecorded. The implementer must verify match-recording logic against the lazy DFA's behavior and upstream's `find_fwd_imp` (`dfa/search.rs:125-181`). Write a focused test comparing dense vs lazy DFA match positions for edge cases (empty matches, matches at span boundaries, consecutive matches).

**Estimated bytecode:** ~200-250 bytes. Well within C2's optimization zone. All `classify` calls should be inlined.

### Strategy Integration

In `Regex.create()`, after building the NFA and CharClasses, attempt `DenseDFA.build()`:

```java
// In Regex.create(), after NFA + CharClasses are built:
DenseDFA denseDFA = null;
if (charClasses != null) {
    denseDFA = DenseDFABuilder.build(nfa, charClasses);
}

// Pass to Strategy.Core alongside existing engines
strategy = new Strategy.Core(pikeVM, forwardDFA, reverseDFA,
        prefilter, backtracker, onePassDFA, denseDFA);
```

In `Strategy.Core.dfaSearch()`, prefer dense DFA for forward search:

```java
private Captures dfaSearch(Input input, Cache cache) {
    long fwdResult;
    if (denseDFA != null) {
        fwdResult = denseDFA.searchFwd(input);
    } else if (forwardDFA != null) {
        fwdResult = forwardDFA.searchFwdLong(input, cache.forwardDFACache());
    } else {
        return pikeVM.search(input, cache.pikeVMCache());
    }
    // ... rest of three-phase search unchanged
}
```

The reverse DFA remains lazy (it runs on narrow windows where pre-compilation has less value).

**All call sites that need the dense DFA preference:**
- `dfaSearch()` — forward DFA in non-capture three-phase search
- `dfaSearchCaptures()` — forward DFA in capture three-phase search
- `isMatch()` — forward DFA for quick match test (line 66)
- `isMatchPrefilter()` — forward DFA inside prefilter candidate loop (line 86)

Each of these currently calls `forwardDFA.searchFwdLong(input, cache.forwardDFACache())`. Replace with: if `denseDFA != null`, call `denseDFA.searchFwd(input)` instead.

**Strategy.Core record:** Add optional `DenseDFA denseDFA` parameter. Callers that don't have a dense DFA pass `null`.

### Start State Handling

The dense DFA needs start states for both anchored and unanchored search. For patterns with look-assertions, upstream uses per-position start states (the start state depends on the character before the search position). This is the same `Start.from()` logic our lazy DFA uses.

**Spec 1 simplification:** Support two start states (anchored + unanchored) for patterns where `lookSetAny.isEmpty()`. For patterns with look-assertions, the dense DFA builder returns `null` and the lazy DFA handles them. This covers the common case (`[a-zA-Z]+`, `Sherlock|Watson`, most patterns without `\b` or `^`/`$`).

**Future (Spec 3):** Add per-position start states to the dense DFA (10 start states × stride entries), matching upstream's `Start` enum. This enables dense DFA for `(?m)^.+$` and `\b\w+\b`.

### What We Don't Build

| Feature | Why deferred |
|---------|-------------|
| Acceleration states | Spec 2 — separate concern, needs state analysis |
| Dense reverse DFA | Low priority — reverse search runs on narrow windows |
| DFA minimization | Optimization — dense DFA works correctly without it |
| Serialization | No current use case |
| Overlapping search | No current API need |
| Per-pattern start states | Single-pattern only for now |
| Multi-position start states | Spec 3 — enables dense DFA for `^`/`$`/`\b` patterns |
| Compile-effort API | Future — `RegexBuilder.compileEffort(HIGH)` |

## Testing Strategy

1. **Unit tests for DenseDFABuilder** — compile known patterns, verify state count, verify each transition matches lazy DFA output for sample inputs.
2. **Unit tests for DenseDFA.searchFwd** — direct search, verify match positions match lazy DFA for a set of patterns × inputs.
3. **Full upstream TOML suite** — the existing 879 upstream tests exercise `Regex.find()`/`isMatch()`/etc. When Strategy.Core selects the dense DFA, tests exercise it automatically. Zero failures is the gate.
4. **Fallback test** — pattern with state explosion returns `null` from builder, strategy falls back to lazy DFA.
5. **Thread-safety smoke test** — concurrent searches on the same compiled `Regex` from multiple threads.
6. **Patterns that should NOT get dense DFA** — patterns with look-assertions (`\b`, `(?m)^`) are correctly skipped in Spec 1 (they get lazy DFA).

## Benchmarks

**Search benchmarks:** Patterns without look-assertions should show improvement (no UNKNOWN handling, flat array access). Patterns with look-assertions are unchanged (still lazy DFA).

| Benchmark | Dense DFA? | Expected impact |
|-----------|-----------|----------------|
| `[a-zA-Z]+` | Yes | Improvement — no UNKNOWN checks |
| `Sherlock Holmes` | No (PrefilterOnly) | Unchanged |
| `Sherlock\|Watson\|Holmes\|Irene` | Yes | Improvement |
| `(?m)^.+$` | No (has look-assertions) | Unchanged until Spec 3 |
| `\w+` | No (has quit classes + look-assertions for `\b`) | Unchanged until Spec 3 |
| `(\d{4})-(\d{2})-(\d{2})` | Yes (captures path uses one-pass DFA, but forward search uses dense) | Slight improvement |

**Compile benchmarks:** Increased compile time for patterns that build a dense DFA. This is the trade-off — more work at compile time for faster search. Should be documented, not treated as a regression.

## Risks

| Risk | Mitigation |
|------|-----------|
| `computeNextState` reuse introduces coupling between LazyDFA and DenseDFABuilder | Minimal API surface: single `computeAllTransitions` method on LazyDFA |
| State explosion on complex patterns | Hard limit (5,000 states), return null, fall back to lazy DFA |
| Match state shuffling correctness | Reuse OnePassBuilder's proven `shuffleMatchStates` pattern, extensive testing |
| Right-edge transition divergence from lazy DFA | Same logic, test both paths produce identical results |
| Dense DFA memory usage | 5,000 states × 64 stride × 4 bytes = 1.28MB max. Acceptable. |

## Future Enhancements (noted, not built)

- **Dense reverse DFA** — for patterns with frequent matches where reverse search is on the critical path. Build when both forward and reverse state counts are small.
- **Compile-effort API** — `RegexBuilder.compileEffort(CompileEffort.HIGH)` raises state limits, enables dense reverse DFA, minimization.
- **Multi-position start states** — enables dense DFA for patterns with look-assertions (Spec 3).

## Summary

| Component | File | New/Modify |
|-----------|------|-----------|
| DenseDFA | `dfa/dense/DenseDFA.java` | New |
| DenseDFABuilder | `dfa/dense/DenseDFABuilder.java` | New |
| LazyDFA.computeAllTransitions | `dfa/lazy/LazyDFA.java` | Modify (add public method) |
| Strategy.Core | `meta/Strategy.java` | Modify (add denseDFA field, prefer in dfaSearch) |
| Regex.create | `regex/Regex.java` | Modify (build DenseDFA, pass to Core) |
