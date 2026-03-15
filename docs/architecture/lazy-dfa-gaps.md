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

## One-Pass DFA — Implemented

**Status: DONE** (2026-03-15)

A specialized DFA that extracts capture groups in a single forward scan, replacing PikeVM/backtracker as the capture engine for eligible patterns. Each 64-bit transition encodes the next state ID (21 bits), a match_wins flag, look-around assertions (18 bits), and a capture slot bitset (24 bits, up to 12 explicit groups).

**Eligibility:** Patterns where at most one NFA thread is active at any point. Common capture patterns like `(\d{4})-(\d{2})-(\d{2})`, `([a-z]+)@([a-z]+)`, `(a|b)c` are one-pass. Ambiguous patterns like `(a*)(a*)` fall back to PikeVM.

**Integration:** Used in `Strategy.Core.captureEngine()` on the precisely-narrowed (anchored) window from three-phase DFA search. Falls back to PikeVM/backtracker for non-one-pass patterns or non-anchored windows.

**Result:** captures benchmark improved from 366 → 12,362 ops/s (33.8×), reaching JDK parity.

**UTF-16 adaptations vs upstream:** Surrogate chars (0xD800-0xDFFF) skipped in transition compilation. `matchWins` bit excluded from conflict comparison to avoid false positives from Union path ordering.

**Design spec:** `docs/superpowers/specs/2026-03-15-one-pass-dfa-design.md`

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

## Search Loop Unrolling — Implemented

**Status: DONE** (2026-03-15)

Both `searchFwd()` and `searchRev()` now use 4× loop unrolling. An inner loop processes 4 transitions per iteration with a single `s <= quit` guard per step. On break-out (match, UNKNOWN, dead, quit), the outer loop re-classifies the triggering char and dispatches via the existing slow path. No semantic changes.

**Design spec:** `docs/superpowers/specs/2026-03-14-lazy-dfa-loop-unrolling-design.md`

## Hot Path Optimizations — Implemented

**Status: DONE** (2026-03-15)

Three optimizations to the DFA search hot path, targeting per-char and per-match overhead:

1. **ASCII fast-path for `classify()`**: A flat `byte[128]` lookup table for ASCII characters, reducing per-char cost from 2 array loads to 1 for ASCII-dominant haystacks. Measured 23% improvement on the forward DFA.

2. **Local `charsSearched` counter**: The per-char `cache.charsSearched++` field write moved to a local variable, with write-back before `computeNextState` calls (where `shouldGiveUp` reads it) and at method exit.

3. **Primitive-encoded search results**: `searchFwdLong()`/`searchRevLong()` return `long` instead of `SearchResult` records, eliminating ~350K record allocations per high-match-count search. `Strategy.Core` updated; `ReverseSuffix`/`ReverseInner` still use record-returning methods.

4. **Pooled `Captures`**: `DFACache.scratchCaptures()` provides a reusable `Captures(1)` instance for non-capture searches, eliminating 174K allocations per search. Allocation dropped from 25.5 MB/op to 13.0 MB/op on charClass.

**Combined result:** charClass improved from 70 → 91 ops/s (+30%), gap vs JDK narrowed from 4.1x to 3.2x.

**Design spec:** `docs/superpowers/plans/2026-03-15-dfa-hot-path-optimizations.md`

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
