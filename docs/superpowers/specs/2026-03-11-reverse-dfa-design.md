# Reverse Lazy DFA — Design Spec

## Goal

Add a reverse lazy DFA that searches backwards through the haystack to find match start positions. This eliminates PikeVM from the non-capturing search path, enabling pure O(n) matching with cached transitions for forward + reverse.

## Context

Currently, after the forward lazy DFA finds the match end, `Strategy.Core` runs PikeVM on a narrowed window to find the match start (and captures). PikeVM is O(m * n) per character, making it the bottleneck for non-capturing searches on patterns like `[a-zA-Z]+` where the forward DFA works well but the start-position PikeVM pass is expensive.

The upstream Rust crate builds a separate reverse NFA by compiling the pattern with concatenations reversed, then wraps it in its own lazy DFA instance. Forward DFA finds match end, reverse DFA finds match start, PikeVM is only invoked when captures are requested.

## Approach: Compiler-level reversal (matches upstream)

The reverse NFA is built by adding a `reverse` flag to the Thompson `Compiler`. When enabled, concatenations, literals, and character class sequences are compiled in reverse order, and look-assertions are flipped. This produces a structurally independent NFA — no shared state with the forward NFA.

This mirrors the upstream Rust approach and was chosen over HIR-level reversal to keep changes localized to the compiler and avoid intermediate reversed HIR trees.

## Design

### 1. NFA Compiler Changes

**API:** Add a static factory `Compiler.compileReverse(Hir hir)` alongside the existing `Compiler.compile(Hir hir)`. Internally, both delegate to a shared `compileInternal` that checks a `boolean reverse` instance field.

**Compilation behavior when `reverse = true`:**

- `Hir.Concat`: iterate children in reverse order (last to first) instead of first to last
- `Hir.Literal`: reverse the `char[]` sequence. A literal `"abc"` (compiled as `CharRange(a) → CharRange(b) → CharRange(c)`) must become `CharRange(c) → CharRange(b) → CharRange(a)` so the reverse DFA matches them in right-to-left order.
- `Hir.Class` (Unicode classes with surrogate pair sequences): when compiling UTF-16 multi-char-unit sequences via `Utf16Sequences`, reverse the sequence order within each range alternative (e.g., `[high, low]` becomes `[low, high]`). This ensures supplementary characters are matched correctly when iterating backwards through a surrogate pair.
- `Hir.Look`: flip assertion directions. Complete mapping for all `LookKind` variants:
  - `START_LINE` ↔ `END_LINE`
  - `START_LINE_CRLF` ↔ `END_LINE_CRLF`
  - `START_TEXT` ↔ `END_TEXT`
  - `WORD_START_ASCII` ↔ `WORD_END_ASCII`
  - `WORD_START_HALF_ASCII` ↔ `WORD_END_HALF_ASCII`
  - `WORD_START_UNICODE` ↔ `WORD_END_UNICODE`
  - `WORD_START_HALF_UNICODE` ↔ `WORD_END_HALF_UNICODE`
  - `WORD_BOUNDARY_ASCII`, `WORD_BOUNDARY_ASCII_NEGATE`, `WORD_BOUNDARY_UNICODE`, `WORD_BOUNDARY_UNICODE_NEGATE`: unchanged (symmetric)
  - Note: look-assertion flipping is added for future correctness (when look-assertion encoding is implemented). Currently the lazy DFA bails out entirely for patterns with look-assertions, so the flipped values have no observable effect yet.
- `Hir.Capture`: compile the inner expression but skip the `State.Capture` start/end slot instructions. The reverse NFA only needs match detection, not capture positions.
- Implicit group-0 capture wrapper: skip the `State.Capture(slot=0)` / `State.Capture(slot=1)` wrapper in `compileInternal`. The reverse NFA goes directly from `body → Match` without capture states.
- All other HIR nodes (`Empty`, `Repetition`, `Alternation`): compiled identically to forward mode.

**NFA metadata:**
- Add `boolean reverse` field to `NFA` (informational, available for search direction checks)
- Reverse NFA has `captureSlotCount = 0` and `groupCount = 0`

### 2. LazyDFA Changes

**New method: `searchRev(Input input, DFACache cache)`**

Searches backwards from `input.end() - 1` to `input.start()`. Uses the same DFA state machine logic as `searchFwd` (epsilon closure, state allocation, cache clearing, give-up heuristics) with these differences:

**Position iteration:** Decrements from `input.end() - 1` down to `input.start()`.

**Match position semantics with 1-char delay:** When a match-flagged state is entered during reverse search, the match start position is `pos + 1`. This parallels forward mode: in forward mode, entering a match-flagged state at `pos` means the match end is `pos` (exclusive). In reverse mode, entering a match-flagged state at `pos` means the delayed match corresponds to the char at `pos + 1`, so the match start (inclusive) is `pos + 1`.

**EOI handling:** After the main loop exits (when `pos < input.start()`), check whether the current state's `StateContent.isMatch()` is true. If so, report `input.start()` as the match start. This mirrors the forward EOI check that reports `input.end()` as the match end.

**Start state look-behind context:** In reverse mode, "look-behind" is the char at `input.end()` (just after the reverse search region), rather than the char before `input.start()` as in forward mode. Not used until look-assertion encoding is implemented, but the interface accommodates it.

**Return type:** Same `SearchResult` sealed type. Rename the `Match` record field from `end` to `offset` to be direction-neutral: `record Match(int offset)`. In forward search, `offset` is the exclusive match end. In reverse search, `offset` is the inclusive match start. Callers interpret the value based on which method they called (`searchFwd` vs `searchRev`).

**Reverse search is always anchored.** When used in the three-phase search, the reverse DFA searches a narrowed window `[input.start(), matchEnd]` anchored at `matchEnd`. It uses the NFA's `startAnchored` state because the reverse search must begin matching exactly at the forward match's end position. The reverse NFA does not need an unanchored start state for this use case. However, we still build the unanchored start state in the NFA for generality (suffix/inner literal strategies will need it later).

### 3. Strategy.Core Changes

**Record signature change:**
- Rename `lazyDFA` field to `forwardDFA`
- Add `LazyDFA reverseDFA` field (nullable)
- Update `createCache()` to create the third cache when `reverseDFA != null`

**`search()` (non-capturing) — three-phase when both DFAs available:**
1. Forward DFA `searchFwd(input)` → finds match end
2. Reverse DFA `searchRev(narrowedInput)` on `[input.start(), matchEnd]`, anchored → finds match start
3. Return `Captures(matchStart, matchEnd)` — no PikeVM needed

If the reverse DFA gives up in step 2, fall back to PikeVM on the narrowed window `[input.start(), matchEnd]`. If the forward DFA gives up in step 1, fall back to PikeVM on the full input.

**`searchCaptures()` — enhanced with reverse DFA narrowing:**
1. Forward DFA `searchFwd(input)` → finds match end
2. Reverse DFA `searchRev(narrowedInput)` on `[input.start(), matchEnd]`, anchored → finds match start
3. PikeVM `searchCaptures` on `[matchStart, matchEnd]` → extracts captures on minimal window

This narrows the PikeVM window further than the current two-phase approach (which uses `[input.start(), matchEnd]`), improving capture extraction performance. If either DFA gives up, fall back to PikeVM on the appropriate window.

**`isMatch()` — unchanged:**
Forward DFA alone is sufficient (only needs to know if a match exists, not where it starts).

### 4. Cache Structure

`Strategy.Cache` gains a third field:

```java
record Cache(
    pikevm.Cache pikeVMCache,
    DFACache forwardDFACache,   // renamed from lazyDFACache
    DFACache reverseDFACache    // new, null when reverseDFA is null
)
```

Each DFA cache is fully independent: separate state tables, start states, clear counts, chars-searched counters, and give-up heuristics.

### 5. Regex Compilation Wiring

In `Regex` (or wherever the compilation pipeline lives):

```
HIR hir = translate(pattern)

// Forward path (existing)
NFA forwardNFA = Compiler.compile(hir)                    // reverse=false
CharClasses fwdClasses = CharClassBuilder.build(forwardNFA)
LazyDFA forwardDFA = LazyDFA.create(forwardNFA, fwdClasses)

// Reverse path (new)
NFA reverseNFA = Compiler.compileReverse(hir)             // reverse=true
CharClasses revClasses = CharClassBuilder.build(reverseNFA)
LazyDFA reverseDFA = LazyDFA.create(reverseNFA, revClasses)

// Strategy
Strategy strategy = new Strategy.Core(pikeVM, forwardDFA, reverseDFA, prefilter)
```

**CharClasses sharing:** The forward and reverse NFAs contain the same char ranges (just wired differently), so their `CharClasses` equivalence maps will be identical. For simplicity, we build separate instances in the initial implementation. Sharing can be added as a follow-up optimization if memory is a concern.

The reverse DFA is `null` when the pattern has look-assertions (same bail-out as forward DFA).

### 6. Prefilter Integration

The prefilter loop in `Strategy.Core` continues to work as before. When a prefilter candidate is found, the three-phase search runs on the candidate window. No changes to `Prefilter`, `SingleLiteral`, or `MultiLiteral`.

## Out of Scope (Deferred)

These are noted for follow-up work:

- **Suffix/inner literal prefilter strategies**: `ReverseSuffix` and `ReverseInner` strategies that use the reverse DFA as the primary search driver, with suffix or inner literals as the prefilter. Requires extending `LiteralExtractor` to extract suffixes and inner literals from HIR. These strategies will use the reverse NFA's unanchored start state.
- **Look-assertion encoding in DFA states**: Both forward and reverse DFAs still bail out on patterns with look-assertions. This is the next-highest-priority gap after reverse DFA. The look-assertion flip table in Section 1 is pre-positioned for this work.
- **Anchored reverse search optimization**: Specialized start state handling for reverse anchored searches.
- **CharClasses sharing between forward and reverse DFAs**: Can share a single instance since the equivalence maps are identical.

## Testing Strategy

- **Unit tests for `searchRev`**: Mirror existing `LazyDFATest` cases but verify start positions
- **Three-phase integration tests**: Verify `Strategy.Core` returns correct `(start, end)` spans using reverse DFA path
- **Upstream suite regression**: All 839+ TOML test cases must continue to pass
- **Give-up fallback tests**: Verify correct fallback to PikeVM when reverse DFA gives up
- **Literal reversal tests**: Verify multi-char literals match correctly in reverse (e.g., pattern `"abc"` finds correct start in `"xabcx"`)
- **Surrogate pair tests**: Verify supplementary characters (e.g., emoji, CJK extension B) match correctly with reversed surrogate pair sequences
- **Edge cases**: Empty matches, single-char matches, matches at input boundaries, full-input matches
- **Match position precision**: Verify off-by-one correctness of `searchRev` match start reporting vs `searchFwd` match end reporting

## Performance Expectations

For non-capturing searches on patterns without look-assertions:
- Patterns like `[a-zA-Z]+`: should improve dramatically (currently 26x slower than JDK due to PikeVM start-finding)
- Patterns with prefix prefilters: marginal improvement (PikeVM already runs on narrowed window)
- Capturing searches: moderate improvement from narrower PikeVM window (`[matchStart, matchEnd]` vs `[input.start(), matchEnd]`)
