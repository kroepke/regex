# Dense DFA Multi-Position Start States — Spec 3

**Date:** 2026-03-17
**Status:** Draft
**Part of:** Full DFA engine (Spec 3 of 3)
**Depends on:** Stage 13 (acceleration states)

## Motivation

The dense DFA currently returns `null` for any pattern with look-assertions (`^`, `$`, `\b`, etc.) because it only supports two start states (anchored + unanchored). Patterns with look-assertions need position-dependent start states — the DFA's start state depends on the character before the search position (e.g., after `\n`, the `^` assertion is satisfied).

This excludes the multiline benchmark `(?m)^.+$` (4.8x behind JDK) and word-boundary patterns like `\b\w+\b`. With multi-position start states, these patterns get the dense DFA with acceleration — the `.+` matching state in multiline has exactly 1 escape class (`\n`), making it ideal for acceleration.

**Upstream ref:** `upstream/regex/regex-automata/src/util/start.rs:141-158` — `Start` enum with 5 variants based on look-behind context.

## Design

### Start State Table

Replace the two scalar start state fields on `DenseDFA` with an array of 10 start states:

```java
// 5 Start variants × 2 (anchored/unanchored) = 10 entries
private final int[] startStates;  // indexed by Start.ordinal() + (anchored ? Start.COUNT : 0)
```

This matches the `DFACache.startStates` layout already used by the lazy DFA.

### Start State Selection in searchFwd

Replace:
```java
int sid = input.isAnchored() ? startAnchored : startUnanchored;
```

With:
```java
int sid = startState(input);
```

Where `startState()` calls `Start.from()` to classify the search position context:

```java
private int startState(Input input) {
    Start start = Start.from(input.haystack(), input.start());
    int idx = input.isAnchored() ? Start.COUNT + start.ordinal() : start.ordinal();
    return startStates[idx];
}
```

`Start.from()` already exists and is used by `LazyDFA.getOrComputeStartState()`. It inspects `haystack[start - 1]` to determine the look-behind context:
- `start == 0` → `Start.TEXT` (beginning of text)
- `haystack[start - 1] == '\n'` → `Start.LINE_LF`
- `haystack[start - 1] == '\r'` → `Start.LINE_CR`
- `isWordChar(haystack[start - 1])` → `Start.WORD_BYTE`
- otherwise → `Start.NON_WORD_BYTE`

### Builder Changes

**Remove the `lookSetAny.isEmpty()` guard.** The dense DFA builder currently returns `null` when `!nfa.lookSetAny().isEmpty()`. Remove this check.

**Compute all 10 start states** instead of just 2:

```java
int[] startStates = new int[Start.COUNT * 2];
for (Start start : Start.values()) {
    for (boolean anchored : new boolean[]{false, true}) {
        // Create a synthetic Input that triggers this Start variant
        Input syntheticInput = createSyntheticInput(start, anchored);
        int sid = lazyDFA.getOrComputeStartState(syntheticInput, cache);
        int idx = anchored ? Start.COUNT + start.ordinal() : start.ordinal();
        startStates[idx] = sid;
        enqueueState(worklist, queued, sid, stride);
    }
}
```

**Synthetic inputs for each Start variant:** We need to create `Input` objects that cause `Start.from()` to return each variant:
- `TEXT`: `Input.of("x")` (start=0, no previous char)
- `LINE_LF`: `Input.of("\nx", 1, 2)` (previous char is `\n`)
- `LINE_CR`: `Input.of("\rx", 1, 2)` (previous char is `\r`)
- `WORD_BYTE`: `Input.of("ax", 1, 2)` (previous char is word char `a`)
- `NON_WORD_BYTE`: `Input.of(" x", 1, 2)` (previous char is space)

For anchored variants, use `Input.anchored()` or set the anchored flag accordingly.

**Note:** `getOrComputeStartState` already handles all Start variants correctly — it calls `Start.from()` internally and caches per-variant start states. We just need to trigger each variant once.

### Start State Remapping After Shuffle

After match-state shuffling, all start state IDs must be remapped through the remap table. The current code remaps `startAnchored` and `startUnanchored`. Extend to remap all 10 entries in `startStates[]`.

### Quit Char Handling

Patterns with `\b` (word boundaries) use quit chars for non-ASCII. The dense DFA builder must handle quit transitions correctly. Currently, `computeAllTransitions` fills in quit transitions via the LazyDFA's existing logic. When the DFA encounters a quit char during search, `searchFwd` returns `GaveUp`, and Strategy.Core falls back to the lazy DFA or PikeVM. This works unchanged — no new logic needed.

## What This Unlocks

| Pattern | Before | After |
|---------|--------|-------|
| `(?m)^.+$` | Lazy DFA | Dense DFA + acceleration (`.+` state: 1 escape `\n`) |
| `\b\w+\b` | Lazy DFA | Dense DFA (with quit on non-ASCII) |
| `^\w+$` | Lazy DFA | Dense DFA + acceleration |
| `(?m)^\d+$` | Lazy DFA | Dense DFA |

## Testing Strategy

1. **Multi-start correctness** — build dense DFA for `(?m)^.+$`, verify non-null. Compare search results against lazy DFA for multi-line inputs.
2. **Word boundary** — build dense DFA for `\b\w+\b`, verify non-null. Compare against lazy DFA. Test inputs with non-ASCII to trigger GaveUp fallback.
3. **Start state variety** — verify that searching at different positions (after `\n`, after word char, at text start) produces correct matches.
4. **Full upstream TOML suite** — all tests must pass. Many TOML tests use `^`, `$`, `\b`.
5. **Acceleration verification** — `(?m)^.+$` should have accelerated states (`.+` matching state).

## Benchmarks

| Benchmark | Expected impact |
|-----------|----------------|
| `(?m)^.+$` | **Large improvement** — dense DFA + acceleration (`.+` scans for `\n`) |
| `\w+` | No change (already uses dense DFA — no look-assertions in `\w+` itself) |
| `[a-zA-Z]+` | No change (already uses dense DFA) |
| Captures `(\d{4})-(\d{2})` | No change (already uses dense DFA) |

## Risks

| Risk | Mitigation |
|------|-----------|
| Synthetic input construction gets Start variant wrong | Test each variant explicitly, compare against lazy DFA |
| Start state remapping bug after shuffling | Extend existing remap logic, test with patterns that have match states in different Start contexts |
| Quit char patterns return GaveUp too often with dense DFA | Same behavior as lazy DFA — no new risk |
| Additional start states increase build time | 10 start states instead of 2 — negligible (5 extra computeAllTransitions calls at most) |

## Summary

| Component | File | Change |
|-----------|------|--------|
| Start state table | `DenseDFA.java` | Replace 2 ints with `int[10]`, add `startState(Input)` |
| Guard removal + 10-start computation | `DenseDFABuilder.java` | Remove guard, compute all Start variants |
| Start state remapping | `DenseDFABuilder.java` | Remap all 10 entries after shuffle |
| Tests | `DenseDFAStartTest.java` | New test class |
