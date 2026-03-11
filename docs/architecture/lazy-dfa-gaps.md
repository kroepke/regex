# Lazy DFA — Deferred Features

This document tracks features intentionally deferred from the initial lazy DFA implementation. Each entry describes what was left out, why it matters, and when it should be revisited.

See `docs/superpowers/specs/2026-03-11-lazy-dfa-design.md` for the initial design spec.

## Reverse DFA

**What:** A DFA that searches backwards through the input to find match start positions.

**Why it matters:** Currently, after the forward lazy DFA finds the match end, we run PikeVM on the narrowed window to find the start. A reverse DFA would find the start in O(n) with cached transitions, eliminating PikeVM from the non-capturing `search()` path entirely. Also a prerequisite for suffix and inner literal prefilters.

**When to add:** After the forward lazy DFA is stable and benchmarked. This is the highest-priority gap.

**Complexity:** Medium-high. Requires building a reverse NFA (or reversing the existing one), a second `LazyDFA` instance, and coordinating forward/reverse search in `Strategy.Core`.

## Overlapping Match Mode

**What:** Find all matches including those that overlap with each other.

**Why it matters:** Required for certain regex operations (e.g., `regex-automata`'s `OverlappingState` in upstream). Not needed for standard `findAll()` which finds non-overlapping leftmost matches.

**When to add:** When we need overlapping match semantics (likely driven by a specific API requirement or upstream test suite gap).

**Complexity:** Low. The search loop tracks state differently — doesn't reset on match, continues from match position.

## Quit Bytes

**What:** Configure specific byte/char values that cause the DFA to immediately give up and fall back.

**Why it matters:** Useful for patterns that are ASCII-only — non-ASCII chars can trigger a quit, avoiding DFA state explosion on Unicode input. The upstream uses this for `\b` (Unicode word boundary) which would require enormous state sets to handle in the DFA.

**When to add:** When we observe specific patterns where the DFA's cache thrashes on Unicode input that could be avoided by quitting early on non-ASCII chars. May also be needed for correct `\b` handling in the DFA.

**Complexity:** Low. Add a `BitSet` of quit chars, check in the search loop before the transition.

## Per-Pattern Start States

**What:** Multiple start states, one per pattern in a regex set (multi-pattern matching).

**Why it matters:** Required for `RegexSet`-style APIs where multiple patterns are compiled together and searched simultaneously.

**When to add:** When we implement `RegexSet` or multi-pattern matching.

**Complexity:** Medium. The start state computation and match reporting need to be pattern-aware.

## Search Loop Unrolling

**What:** Process 4 chars at a time without checking for special states, then handle specials after the batch.

**Why it matters:** The upstream reports ~30-50% throughput improvement from loop unrolling. Reduces branch mispredictions in the hot loop.

**When to add:** After the basic search loop is correct and benchmarked. This is a pure optimization — no semantic change.

**Complexity:** Low-medium. Need to handle the case where a special state is hit mid-batch (rewind and process one at a time).

## Look-Around Encoding in DFA States

**What:** Encode look-around assertion context (`look_have`/`look_need` bitsets, word-char flags, CRLF state) in the DFA state content, so the DFA can evaluate `^`, `$`, `\b`, `\B`, and `(?m)` anchors natively without delegating to PikeVM.

**Why it matters:** The initial lazy DFA bails out entirely for patterns containing look-assertions — `Strategy.Core` skips the lazy DFA and uses PikeVM directly. This means common patterns like `^\w+`, `\bword\b`, and multiline patterns don't benefit from the DFA at all. The upstream Rust crate encodes look-behind as part of the DFA state key, allowing the DFA to handle these assertions natively.

**When to add:** After the forward lazy DFA is stable and benchmarked. This is the second-highest priority gap (after reverse DFA). It's needed before the lazy DFA can be a universal replacement for PikeVM.

**Complexity:** Medium. Requires extending `StateContent` to include look-behind flags, tracking the previous char class in the search loop, and correctly computing `look_have` during epsilon closure.

## Anchored Start State Optimization

**What:** Specialized start state handling for anchored searches beyond the basic anchored/unanchored distinction.

**Why it matters:** The upstream has per-byte start state groups that encode look-behind context at the start position. This allows the DFA to immediately resolve start-of-line anchors without special epsilon closure handling.

**When to add:** When anchored search performance becomes a bottleneck, or when look-behind encoding (above) is implemented.

**Note:** The upstream uses a `StartByteMap` that maps the byte before the start position to a start state group, enabling correct `\b` and `^` after `\n` handling at search start. This is a prerequisite once look-assertion encoding is supported.

**Complexity:** Medium. More start states to compute and cache.
