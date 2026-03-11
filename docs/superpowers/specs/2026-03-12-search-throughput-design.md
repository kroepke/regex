# Search Throughput Improvement — Design Spec

**Date:** 2026-03-12
**Status:** Draft
**Goal:** Close the search throughput gap with JDK for non-literal patterns

## Problem Statement

Current search benchmarks show significant throughput gaps vs JDK's `java.util.regex`:

| Benchmark | Pattern | Gap | Root Cause |
|-----------|---------|-----|------------|
| charClass | `[a-zA-Z]+` | 20x slower | DFA finds match end, PikeVM re-scans per match |
| captures | `(\d{4})-(\d{2})-(\d{2})` | 296x slower | PikeVM runs on wide window per match for capture extraction |
| unicodeWord | `\w+` | 2,821x slower | DFA bails entirely on Unicode `\w`, pure PikeVM |
| alternation | `Sherlock\|Watson\|Holmes\|Irene` | 2.4x slower | 4 separate `indexOf()` scans (deferred — Aho-Corasick future work) |

The literal search benchmark (`Sherlock Holmes`) is already 1.4x **faster** than JDK via `PrefilterOnly`. Pathological patterns are 79-129x faster (linear-time guarantee).

## Approach

Three complementary features, aligned with the upstream Rust regex crate's architecture:

1. **Three-Phase Search** — forward DFA + reverse DFA gives full match bounds without PikeVM
2. **Quit Chars** — DFA handles ASCII portions of Unicode word patterns, quits on non-ASCII
3. **Bounded Backtracker** — fast capture extraction on narrow match windows

### Expected Impact

| Benchmark | Current | After | Why |
|-----------|---------|-------|-----|
| charClass | 20x slower | ~1-2x | Three-phase eliminates PikeVM entirely |
| captures | 296x slower | ~3-10x | Three-phase narrows window, backtracker extracts captures |
| unicodeWord | 2,821x slower | ~5-20x | DFA handles ASCII (majority), quits only on non-ASCII |
| alternation | 2.4x slower | unchanged | Requires Aho-Corasick (future work) |

## Design

### Overall Search Pipeline

After this work, `Strategy.Core` search follows this pipeline:

```
search() [non-capture — findAll()]:
  1. Prefilter skip-ahead (if available)
  2. Forward DFA → match end
     ├─ GaveUp → nofail fallback
     └─ Match(end) →
  3. Reverse DFA → match start (anchored search from end backwards)
     ├─ GaveUp → nofail fallback (PikeVM on [searchStart, end])
     └─ Match(start) →
  4. Return Match(start, end) — NO PikeVM needed

searchCaptures() [with captures — capturesAll()]:
  1. Prefilter skip-ahead (if available)
  2. Forward DFA → match end
     ├─ GaveUp → nofail fallback
     └─ Match(end) →
  3. Reverse DFA → match start
     ├─ GaveUp → nofail fallback (PikeVM on [searchStart, end])
     └─ Match(start) →
  4. Capture engine on anchored [start, end]:
     ├─ BoundedBacktracker (if window ≤ maxLen) → captures
     └─ PikeVM → captures (fallback for large windows)

nofail fallback [when DFA gives up or is unavailable]:
  1. BoundedBacktracker (if haystack ≤ maxLen)
  2. PikeVM (always available)
```

For the DFA search loop (both forward and reverse):

```
for each char in haystack:
  1. Look up char class (existing)
  2. If char class is QUIT_CLASS → return GaveUp(offset)
  3. Look up next state from transition table (existing)
  4. Check for match/dead state (existing)
```

### Integration Points

| Component | Location | Change |
|-----------|----------|--------|
| `Strategy.Core` | `regex-automata/.../meta/Strategy.java` | Add three-phase search, backtracker integration, nofail fallback |
| `Strategy.Cache` | `regex-automata/.../meta/Strategy.java` | Add `BoundedBacktracker.Cache`, reverse DFA cache |
| `LazyDFA` | `regex-automata/.../dfa/lazy/LazyDFA.java` | Quit char support in search loop |
| `LazyDFA.create()` | same | Accept patterns with Unicode word boundaries (configure quit chars) |
| `CharClasses` | `regex-automata/.../dfa/CharClasses.java` | Quit char class support |
| `DFACache` | `regex-automata/.../dfa/lazy/DFACache.java` | Quit state handling in transitions |
| `BoundedBacktracker` (new) | `regex-automata/.../nfa/thompson/backtrack/BoundedBacktracker.java` | New engine |
| `Regex.create()` | `regex/.../Regex.java` | Build backtracker, pass to Strategy |

---

## Component 1: Quit Chars

### Concept

Instead of rejecting patterns with Unicode word boundaries at `LazyDFA.create()` time (returning `null`), build the DFA but mark non-ASCII chars (≥ 128) as quit triggers. When the DFA encounters a quit char during search, it returns `GaveUp(offset)` and the meta engine falls back to PikeVM or the bounded backtracker for that search.

### Design (Aligned with Upstream)

The upstream integrates quit bytes into the **transition table**, not as a pre-check. Quit bytes get their own equivalence class, and transitions for quit classes point to a sentinel QUIT state. The search loop checks for QUIT as part of the existing state-tag inspection (alongside match/dead checks).

Our design follows the same approach:

**1. `CharClasses` gains quit char support:**

During char class construction, if quit chars are configured, ensure quit chars form separate equivalence classes from non-quit chars. This allows their transitions to be independently set to a QUIT state without affecting non-quit transitions.

**2. `DFACache` state computation handles quit transitions:**

When adding a new state to the cache, overwrite transitions for quit char classes to point to a sentinel QUIT state ID. This mirrors the upstream's approach in `dfa.rs` lines 2313-2318:

```
for each new state added to cache:
  for each quit char class:
    set_transition(state, quit_class, QUIT_STATE_ID)
```

**3. Search loop gains QUIT state check:**

No new branch needed. The existing state classification (match / dead) extends with quit:

```
next_state = transition[current_state][char_class]
if is_match(next_state)  → record match, continue
if is_dead(next_state)   → return last match or NoMatch
if is_quit(next_state)   → return GaveUp(offset)
// else: normal state, continue
```

**4. `LazyDFA.create()` configuration:**

- Patterns with Unicode word boundaries → build DFA, configure quit chars for all chars ≥ 128
- Patterns with CRLF line anchors → still bail entirely (rare, low priority)
- Patterns with only ASCII look-assertions → no quit chars needed (already supported)

### Byte-to-Char Adaptation

- Upstream quits on bytes 0x80-0xFF (non-ASCII UTF-8 lead/continuation bytes)
- We quit on chars ≥ 128 (non-ASCII UTF-16 code units) — same semantic intent, different encoding unit

---

## Component 2: Three-Phase Search

### Concept

The current two-phase search (forward DFA → PikeVM) is replaced by three-phase (forward DFA → reverse DFA → result). For non-capture searches, this eliminates PikeVM entirely. For capture searches, the reverse DFA narrows the window to just the match, dramatically reducing PikeVM/backtracker work.

### Key Finding: No Lazy Quantifier Analysis Needed

Our `lazy-dfa-gaps.md` documented that three-phase search is "blocked" on HIR-level analysis to detect lazy quantifiers. Research into the upstream reveals this is unnecessary:

1. The upstream's `Core::search()` **always** uses the DFA (forward + reverse) — zero conditional logic for lazy quantifiers
2. The DFA uses `LeftmostFirst` semantics which in DFA terms produces leftmost-longest matches
3. For captures, PikeVM/BoundedBacktracker handles correct lazy semantics on the narrowed window
4. The upstream test suite confirms this behavior (e.g., `flags.toml` tests for `(?U)` flag patterns)

This means we activate three-phase for **all patterns** — no pattern analysis step, matching upstream exactly.

### Design

**Changes to `Strategy.Core.search()` (non-capture):**

Current (two-phase):
```
1. forwardDFA.searchFwd(input, cache) → Match(end)
2. pikeVM.search(input.withBounds(start, end), cache) → Captures
```

New (three-phase):
```
1. forwardDFA.searchFwd(input, cache) → Match(end)
2. reverseDFA.searchRev(input.withBounds(searchStart, end, true), cache) → Match(start)
3. return Captures with group 0 = [start, end]
```

On `GaveUp` from either DFA: fall back to nofail path (BoundedBacktracker or PikeVM on the best available bounds).

**Changes to `Strategy.Core.searchCaptures()` (with captures):**

Current:
```
1. forwardDFA.searchFwd(input, cache) → Match(end)
2. pikeVM.searchCaptures(input.withBounds(searchStart, end), cache) → Captures
```

New:
```
1. forwardDFA.searchFwd(input, cache) → Match(end)
2. reverseDFA.searchRev(input.withBounds(searchStart, end, true), cache) → Match(start)
3. captureEngine(input.withBounds(start, end, true), cache) → Captures
   // captureEngine = BoundedBacktracker if window ≤ maxLen, else PikeVM
```

The critical improvement: the capture engine now runs on `[matchStart, matchEnd]` (just the match itself) instead of `[searchStart, matchEnd]` (from last match to new match end). For a 10-char date in a 15KB haystack, this is a 1500x reduction in the PikeVM/backtracker work window.

**Reverse DFA input construction:**

The reverse DFA searches backwards from `end` toward `searchStart`. The input must be:
- Anchored (we know the match ends at `end`)
- Bounded to `[searchStart, end]`

The reverse DFA's `searchRev()` already supports this — it was implemented in the reverse DFA work (2026-03-11) but never wired into the strategy.

**What's already implemented:**
- `Compiler.compileReverse()` — reverse NFA construction
- `LazyDFA.searchRev()` — reverse DFA search
- `Strategy.Core` has `reverseDFA` and `reverseDFACache` fields
- Forward DFA `searchFwd()` works correctly

**What's new:**
- Wire reverse DFA call into `search()` and `searchCaptures()`
- Construct `Captures` directly from DFA match bounds (no PikeVM) for non-capture path
- Add capture engine selection logic after three-phase

---

## Component 3: Bounded Backtracker

### Concept

A new NFA-based engine that uses classical backtracking bounded by a visited bitset. Faster than PikeVM for captures on small haystacks because it explores one path at a time (no per-position thread/slot copying).

### Algorithm (Matching Upstream)

**Core data structures:**

```java
public final class BoundedBacktracker {
    private final NFA nfa;
    private final int maxHaystackLen;  // from capacity / nfa.stateCount()

    public static final class Cache {
        long[] visited;      // bitset indexed by (stateId * stride + offset)
        int stride;          // haystackLen + 1
        Frame[] stack;       // explicit backtrack stack
        int stackTop;
        int[] slots;         // capture slot values (mutable during search)
    }
}

sealed interface Frame {
    record Step(int stateId, int at) implements Frame {}
    record RestoreCapture(int slot, int prevValue) implements Frame {}
}
```

**Search entry point:**

For anchored search (our primary use case after three-phase narrowing):
```
searchCaptures(input, cache):
  clear visited bitset and slots
  return backtrack(cache, input, input.start(), nfa.startAnchored(), slots)
```

For unanchored search (nofail fallback when DFA unavailable):
```
search(input, cache):
  for at = input.start() to input.end():
    clear visited bitset
    result = backtrack(cache, input, at, nfa.startAnchored(), slots)
    if result != null → return result
  return null
```

**Backtrack function:**

```
backtrack(cache, input, at, startId, slots):
  push Step(startId, at)
  while stack not empty:
    pop frame
    if Step(sid, at):
      result = step(cache, input, sid, at, slots)
      if result is Match → return match
    if RestoreCapture(slot, prev):
      slots[slot] = prev
  return null
```

**Step function (NFA state dispatch):**

```
step(cache, input, sid, at, slots):
  loop:
    if !visited.insert(sid, at - input.start()) → return null

    match nfa.state(sid):
      CharRange(start, end, next):
        if at >= input.end() → return null
        c = haystack[at]
        if c < start || c > end → return null
        at += Character.charCount(c)
        sid = next

      Sparse(transitions):
        if at >= input.end() → return null
        find matching transition for haystack[at]
        if none → return null
        at += 1; sid = transition.next

      Look(look, next):
        if look assertion satisfied at 'at' → sid = next
        else → return null

      Union(alternates):
        push Step(alternates[n-1..1], at) in reverse
        sid = alternates[0]

      BinaryUnion(alt1, alt2):
        push Step(alt2, at)
        sid = alt1

      Capture(next, groupIndex, slotIndex):
        push RestoreCapture(slotIndex, slots[slotIndex])
        slots[slotIndex] = at
        sid = next

      Match(patternId) → return HalfMatch(patternId, at)
      Fail → return null
```

### Size Limit

```java
// Default capacity: 256KB = 2,097,152 bits
static final int DEFAULT_VISITED_CAPACITY = 256 * 1024;

int maxHaystackLen(NFA nfa) {
    int capacityBits = DEFAULT_VISITED_CAPACITY * 8;
    return (capacityBits / nfa.stateCount()) - 1;
}
```

After three-phase narrowing, the match window is typically 10-50 chars. Even with 100+ NFA states, this is well within capacity. The PikeVM fallback handles the rare case where the window exceeds the limit.

### Byte-to-Char Adaptation

- Upstream advances `at += 1` (one byte). We advance `at += 1` for BMP chars or use `Character.charCount()` for surrogate-aware advancement in `CharRange`.
- Visited bitset indexed by char position, not byte position.
- NFA state types map 1:1: `CharRange` ↔ `ByteRange`, `Sparse` ↔ `SparseTransitions`, `Union` ↔ `Union`, etc.
- This matches our existing PikeVM's char-unit adaptation.

### Integration with Strategy

```java
// In Regex.create():
BoundedBacktracker backtracker = new BoundedBacktracker(nfa);

// In Strategy.Core:
// The backtracker is always built (shares NFA with PikeVM, minimal overhead).
// At search time, selection is by window size:
private Captures captureEngine(Input narrowed, Cache cache) {
    int windowLen = narrowed.end() - narrowed.start();
    if (windowLen <= backtracker.maxHaystackLen()) {
        return backtracker.searchCaptures(narrowed, cache.backtracker());
    } else {
        return pikeVM.searchCaptures(narrowed, cache.pikeVM());
    }
}
```

---

## Implementation Order

These components have minimal dependencies and could be developed in parallel, but the natural order is:

1. **Quit Chars** — lowest complexity, enables DFA for Unicode patterns, independently testable
2. **Three-Phase Search** — medium complexity, infrastructure already in place (reverse DFA exists), biggest single impact on non-capture benchmarks
3. **Bounded Backtracker** — medium complexity, new engine but well-understood algorithm, biggest impact on capture benchmarks

Each component is independently valuable and testable:
- Quit chars alone improves unicodeWord from 2,821x → ~100x slower (DFA with PikeVM fallback)
- Three-phase alone improves charClass from 20x → ~1-2x
- Bounded backtracker alone (without three-phase) still helps captures via faster PikeVM fallback

Combined, they compose multiplicatively: quit chars enables DFA → three-phase eliminates PikeVM → backtracker speeds up captures.

---

## Testing Strategy

### Quit Chars
- Unit tests: verify DFA returns GaveUp on non-ASCII chars when quit chars configured
- Unit tests: verify DFA handles ASCII portions correctly before quitting
- Integration tests: `\w+`, `\b`, Unicode word patterns go through DFA+fallback path
- Regression: all existing DFA tests still pass

### Three-Phase Search
- Unit tests: verify reverse DFA finds correct match start for various patterns
- Unit tests: verify non-capture search returns correct bounds without PikeVM
- Integration tests: `findAll()` returns same results as PikeVM-only path for patterns including lazy quantifiers, empty alternatives
- Upstream test suite: all existing TOML tests continue to pass
- **Critical:** verify lazy quantifier behavior matches upstream (leftmost-longest for `find()`, correct lazy for `captures()`)

### Bounded Backtracker
- Unit tests: visited bitset insertion and duplicate detection
- Unit tests: capture slot save/restore via stack frames
- Unit tests: anchored search on narrow windows
- Unit tests: max haystack length enforcement (returns error / falls back)
- Integration tests: `capturesAll()` returns identical results to PikeVM path
- Upstream test suite: all TOML capture tests pass through backtracker

### Benchmarks
- Run `SearchBenchmark` after each component to measure improvement
- Run `PathologicalBenchmark` to verify no regressions
- Expected final results tracked against targets in this spec

---

## Upstream References

| Component | Upstream File | Key Lines |
|-----------|--------------|-----------|
| Quit bytes config | `regex-automata/src/hybrid/dfa.rs` | 3871-3875 (Unicode word → quit 0x80-0xFF) |
| Quit bytes in transitions | `regex-automata/src/hybrid/dfa.rs` | 2313-2318 (overwrite quit transitions) |
| Three-phase search | `regex-automata/src/meta/wrappers.rs` | 516-623 (HybridEngine forward + reverse) |
| Core search fallback | `regex-automata/src/meta/strategy.rs` | 705-730 (DFA → nofail cascade) |
| Bounded backtracker | `regex-automata/src/nfa/thompson/backtrack.rs` | Full file (1908 lines) |
| Backtracker in meta | `regex-automata/src/meta/wrappers.rs` | 142-324 (wrapper + eligibility checks) |

## Gap Document Updates

After implementation, update `docs/architecture/lazy-dfa-gaps.md`:
- Mark "DFA Lazy Quantifier Limitation" as resolved (three-phase activated without analysis)
- Mark "Quit Bytes" as done
- Update "Reverse DFA" status from "partially implemented" to "fully integrated"
- Add note about bounded backtracker as new capture engine
