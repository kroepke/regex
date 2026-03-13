# DFA Match Semantics Gap: LeftmostFirst vs LeftmostLongest

**Status: OPEN — #1 performance/correctness priority**

## The Problem

Our lazy DFA always uses **leftmost-longest** match semantics. The upstream Rust crate's DFA uses **leftmost-first** (and leftmost-longest is explicitly NOT implemented in upstream). This mismatch is the root cause of repeated correctness bugs and performance degradation throughout the engine.

### What upstream does

The upstream defines match semantics in `regex-automata/src/util/search.rs`:

```rust
pub enum MatchKind {
    All,           // overlapping multi-pattern only
    LeftmostFirst, // default — the ONLY universally supported mode
    // NOTE: LeftmostLongest is documented as a future possibility but NOT implemented
}
```

Because all engines (DFA, PikeVM, bounded backtracker, one-pass DFA) implement leftmost-first, they are **semantically equivalent by construction**. The upstream's search strategies **never cross-validate** results between engines:

- Forward DFA finds matchEnd → reverse DFA finds matchStart → return `[matchStart, matchEnd]` directly
- No PikeVM verification. Ever.
- Fallback to PikeVM only happens when the DFA **cannot handle the pattern** (e.g., Unicode word boundaries), not to verify correctness.

### What we do

Our `LazyDFA.computeNextState()` iterates NFA states in ascending state ID order. Because the `Match` state has a high ID, char-consuming states are processed before `Match` during epsilon closure. This means the DFA always extends the match as far as possible — leftmost-longest semantics.

This disagrees with PikeVM (which implements leftmost-first) for:

1. **Lazy quantifiers**: `a+?` on `aaa` — DFA returns `[0,3]`, PikeVM returns `[0,1]`
2. **Length-ambiguous alternation**: `(a|ab)` on `ab` — DFA returns `[0,2]`, PikeVM returns `[0,1]`
3. **Optional/star groups**: `(aa$)?` on `aa` — DFA skips empty match, PikeVM finds it
4. **Empty match vs non-empty**: `(?:\n?[a-z]{3}$)*` — DFA finds longest non-empty match, PikeVM finds empty match at position 0

### Consequences

Because our forward DFA can return a different matchEnd than PikeVM would, we must run PikeVM to verify every DFA result. This means:

- **No DFA-only search**: `search()` (no captures) must still call PikeVM on `[input.start(), matchEnd]`
- **No three-phase narrowing for search()**: The reverse DFA's matchStart is computed relative to the forward DFA's (potentially wrong) matchEnd, so PikeVM on `[matchStart, matchEnd]` can miss the correct leftmost-first match
- **Three-phase narrowing for searchCaptures() is safe**: The reverse DFA narrows the window for the capture engine, and PikeVM/backtracker on the narrowed window will find the correct leftmost-first match **if the correct match is within the window** (which is guaranteed when the pattern cannot match empty at a position before matchStart)

### Performance impact

For patterns like `\w+` on 900KB text (tens of thousands of matches), the DFA-only path would return in ~63µs (15,717 ops/s). With PikeVM verification, it takes ~81ms (12 ops/s) — a **1,277x slowdown**. Similar impact on `[a-zA-Z]+`, `.*.*=.*`, and any high-match-count pattern.

## The Fix

Implement `MatchKind.LeftmostFirst` in our DFA. The key change is in `computeNextState()` epsilon closure: when an NFA `Match` state is reachable, it should take priority over char-consuming states for alternatives listed earlier in the pattern. This mirrors upstream's `regex-automata/src/nfa/thompson/compiler.rs` where NFA state IDs encode alternation priority.

### Implementation approach

Reference: `upstream/regex/regex-automata/src/util/search.rs:1698-1721` for `MatchKind` enum, and `upstream/regex/regex-automata/src/dfa/` for how the DFA handles it.

1. Add `MatchKind` enum (`LeftmostFirst`, `All`)
2. Modify `computeNextState()` epsilon closure to respect alternation priority when `MatchKind.LeftmostFirst`
3. Remove PikeVM verification from `Core.dfaSearch()` — return DFA results directly
4. Restore three-phase search: forward DFA → reverse DFA → return `[matchStart, matchEnd]`
5. Remove three-phase from `Core.dfaSearchCaptures()` only when captures are needed

### Complexity

Medium-high. Requires understanding the NFA state ID ordering, epsilon closure priority, and match reporting. Get this right by reading the upstream implementation, NOT by guessing.

## Interim Mitigation

Until leftmost-first DFA is implemented:

1. **Core.dfaSearch()** must always verify with PikeVM on `[input.start(), matchEnd]`
2. **Core.dfaSearchCaptures()** can use three-phase (reverse DFA narrows window) for patterns that cannot match empty, but must fall back to two-phase for patterns that can
3. **ReverseSuffix and ReverseInner** already use PikeVM verification and are correct
4. **Never return DFA match positions directly** without PikeVM verification
