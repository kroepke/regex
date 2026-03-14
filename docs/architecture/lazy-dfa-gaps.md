# Lazy DFA — Deferred Features

This document tracks features intentionally deferred from the initial lazy DFA implementation. Each entry describes what was left out, why it matters, and when it should be revisited.

See `docs/superpowers/specs/2026-03-11-lazy-dfa-design.md` for the initial design spec.

## Reverse DFA — Implemented

**Status: DONE** (2026-03-12)

The reverse NFA compiler (`Compiler.compileReverse()`), reverse DFA search (`LazyDFA.searchRev()`), and `Strategy.Core` wiring are fully implemented. Three-phase search is active: forward DFA finds match end → reverse DFA finds match start → capture engine (bounded backtracker or PikeVM) runs on the narrowed window.

**Suffix/inner prefilters:** DONE (2026-03-13). `ReverseSuffix` and `ReverseInner` strategies use the reverse DFA to find match start after `indexOf`-based literal prefiltering. See `docs/superpowers/specs/2026-03-12-suffix-inner-prefilter-design.md`.

**Design spec:** `docs/superpowers/specs/2026-03-11-reverse-dfa-design.md`

## DFA Match Semantics — RESOLVED

**Status: DONE** (2026-03-14)

Our DFA already implements leftmost-first semantics correctly via the `break`-on-Match in `computeNextState` and NFA state ordering. Three-phase DFA-only search (forward → reverse → return) is now active, matching upstream's `dfa/regex.rs:474-534`.

**Char class overflow:** Resolved via equivalence class merging. `CharClassBuilder.build()` merges boundary regions with identical NFA transition targets, reducing `\w+` from ~1,400 regions to ~55 classes. Patterns with `\b` skip the merge and use quit-on-non-ASCII.

**Performance:** After surrogate-pair target resolution, `unicodeWord` improved from 18 ops/s to 13,499 ops/s (2.3x slower than JDK). Forward NFA merges to ~55 classes, reverse NFA merges to ~2 classes. No quit-on-non-ASCII fallback needed.

See `docs/architecture/dfa-match-semantics-gap.md` for full analysis.

## Overlapping Match Mode

**What:** Find all matches including those that overlap with each other.

**Why it matters:** Required for certain regex operations (e.g., `regex-automata`'s `OverlappingState` in upstream). Not needed for standard `findAll()` which finds non-overlapping leftmost matches.

**When to add:** When we need overlapping match semantics (likely driven by a specific API requirement or upstream test suite gap).

**Complexity:** Low. The search loop tracks state differently — doesn't reset on match, continues from match position.

## Quit Chars — Implemented

**Status: DONE** (2026-03-12)

Quit chars allow the DFA to handle the ASCII portions of patterns with Unicode word boundaries. When the DFA encounters a quit char (non-ASCII for Unicode word boundary patterns), it returns `GaveUp` and falls back to PikeVM for that search. This improved the `unicodeWord` benchmark from 3,252x slower to 2.0x slower than JDK.

Implementation: `CharClasses` tracks quit chars via `quitNonAscii` flag, `LazyDFA` returns `SearchResult.GaveUp` on quit transitions, `Strategy.Core` handles `GaveUp` by falling back to PikeVM.

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

## Look-Around Encoding in DFA States — Implemented

**Status: DONE** (2026-03-11)

The lazy DFA now encodes look-behind context (`lookHave`, `lookNeed`, `isFromWord`, `isHalfCrlf`) in DFA state keys. The epsilon closure conditionally follows `State.Look` transitions based on which assertions are currently satisfied. A two-phase computation resolves look-ahead on the source state (Phase 1) and look-behind on the destination state (Phase 2).

**Supported look kinds:** `START_TEXT`, `END_TEXT`, `START_LINE`, `END_LINE`, `WORD_BOUNDARY_ASCII`, `WORD_BOUNDARY_ASCII_NEGATE`, `WORD_START_ASCII`, `WORD_END_ASCII`, `WORD_START_HALF_ASCII`, `WORD_END_HALF_ASCII`.

**Unsupported (DFA bails to PikeVM):** `WORD_BOUNDARY_UNICODE`, `WORD_BOUNDARY_UNICODE_NEGATE`, `WORD_START_UNICODE`, `WORD_END_UNICODE`, `WORD_START_HALF_UNICODE`, `WORD_END_HALF_UNICODE`, `START_LINE_CRLF`, `END_LINE_CRLF`. These require Unicode word-char property tables or CRLF-specific handling that the DFA's compact character classification doesn't support.

## Bounded Backtracker — Implemented

**Status: DONE** (2026-03-12)

The bounded backtracker is an NFA backtracking engine bounded by a visited bitset of `(stateId, offset)` pairs, guaranteeing O(m×n) worst-case time. It's used as the preferred capture engine for small match windows (determined by `maxHaystackLen` based on NFA state count and a 256KB visited budget). Falls back to PikeVM for larger windows.

Implementation: `BoundedBacktracker` in `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/backtrack/`. Integrated into `Strategy.Core.captureEngine()`.

## Anchored Start State Optimization — Implemented

**Status: DONE** (2026-03-11)

The `Start` enum encodes look-behind context at the search start position (TEXT, LINE_LF, LINE_CR, WORD_BYTE, NON_WORD_BYTE), with 5 variants × 2 (anchored/unanchored) = 10 DFA start states. `DFACache.startStates` caches these, and `Start.from()` selects the correct start state based on the character before the search start position. This is used by `getOrComputeStartState()` when `lookSetAny` is non-empty.
