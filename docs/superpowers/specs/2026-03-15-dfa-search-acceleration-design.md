# DFA Search Acceleration Design Spec

## Goal

Integrate prefilter-at-start-state and acceleration states into the lazy DFA search loop. These two mechanisms allow the DFA to skip large regions of the haystack that cannot contribute to a match, reducing per-char cost from 2 array loads to near-zero for skippable regions.

**Target:** Close the 3-4x gap on charClass (78 vs 304), multiline (244 vs 1,080), and unicodeWord (15,054 vs 35,373) benchmarks vs JDK.

## Background

Currently, our forward DFA search loop processes every character in the haystack: `classify(haystack[pos])` → `nextState(sid, classId)` → check special states. For patterns like `[a-zA-Z]+` on English text, the DFA spends ~40% of its time in the start state scanning non-matching whitespace/punctuation. For `(?m)^.+$`, the `.+` state self-loops on almost every character except `\n`.

Upstream has two mechanisms to skip past these self-looping regions:

### 1. Prefilter at Start State (upstream lazy DFA)

When the DFA is in the start state, call the prefilter to jump ahead to the next candidate position. The DFA search loop checks `sid.is_start()` after the unrolled inner loop breaks out on a tagged state, and calls `prefilter.find()` to skip ahead.

**Ref:** `upstream/regex/regex-automata/src/hybrid/search.rs:72-83` (initial prefilter), `233-262` (at-start-state prefilter during loop).

### 2. Acceleration States (upstream full DFA)

Any state where most transitions are self-loops and only 1-3 byte/char classes escape is an "acceleration candidate." At such a state, instead of following transitions char-by-char, use a fast scan (like `memchr` or `String.indexOf(char)`) to find the next escape character and jump directly to it.

Upstream implements this only in the full (pre-compiled) DFA, not in the lazy DFA. We adapt it for our lazy DFA since we don't have a separate full DFA engine.

**Ref:** `upstream/regex/regex-automata/src/dfa/accel.rs:1-51` (design), `upstream/regex/regex-automata/src/dfa/search.rs:150-172` (integration).

## Design

### Feature 1: Prefilter at Start State

#### Integration into searchFwdLong()

The prefilter is called at two points:

1. **Before the main loop:** If a prefilter exists and the search is unanchored, call `prefilter.find()` to skip ahead to the first candidate. If no candidate exists, return NoMatch immediately.

2. **At the start state during the loop:** When the unrolled inner loop breaks out because it hit a tagged/special state, check if the current state is the start state. If so, call the prefilter to skip ahead to the next candidate.

#### Start State Tagging

Upstream marks start states as "tagged" so the unrolled inner loop breaks out when it reaches them. We need a similar mechanism. Our current unrolled loop breaks on `s0 <= quit` (any special state). We need to also break when the state is a start state.

**Approach:** Add a `startStateId` field to `LazyDFA`. In the outer dispatch (after the unrolled loop breaks out), check `if (sid == startStateId && prefilter != null)` before the normal UNKNOWN/dead/quit dispatch.

Note: We don't need to tag the start state in the *inner* unrolled loop — that would hurt performance for patterns without prefilters (as upstream notes in their comment at `search.rs:155-160`). We only check at the start state in the *outer* dispatch path.

#### prefilterRestart

After the prefilter jumps ahead, we may need to recompute the start state if the pattern has prefix look-around assertions (like `\b` or `^`). The start state depends on the character before the current position.

Optimization: if the NFA has no prefix look-around (`nfa.lookSetAny().isEmpty()` for prefix looks), skip the restart — the start state is always the same ("universal start"). This matches upstream's `universal_start` check.

#### Changes needed

- `LazyDFA`: Store optional `Prefilter` reference, `startStateId` (computed on first search), and `universalStart` flag.
- `searchFwdLong()`: Add prefilter integration at the two points described above.
- The prefilter operates on `String` (for `indexOf` intrinsics). The `Input` already caches `haystackStr`.

### Feature 2: Acceleration States

#### Concept

A DFA state is "accelerable" if most of its transitions loop back to itself, and only a small number (1-3) of equivalence classes lead to different states. At such a state, instead of processing chars one-by-one through the DFA, we can scan ahead for the next "escape character" — a character whose class leads to a different state.

**Example:** For `[a-zA-Z]+`, the matching state loops on all `[a-zA-Z]` chars and escapes on non-`[a-zA-Z]` chars. If we can identify the escape chars (space, digits, punctuation), we can use `String.indexOf` or a fast scan to find the next one.

#### Detection

After a DFA state is computed (via `computeNextState`), analyze its transition row:
- Count how many distinct non-self-loop target states exist
- If only 1-3 classes escape the self-loop, mark the state as accelerated
- Store the escape characters (representative chars for those classes)

This analysis happens lazily — only when a state is first computed, same as the rest of the lazy DFA.

#### Representation

Add to `DFACache`:
```java
// Acceleration data per state. Indexed by stateIdx (= stateId / stride).
// null entry = not analyzed yet. Empty array = not accelerable.
// Non-empty array (1-3 chars) = accelerable, these are the escape chars.
char[][] accelChars;  // or a more compact representation
```

#### Fast Scan

When the search loop enters an accelerated state, instead of the normal transition logic:

```java
if (accelChars != null && accelChars.length > 0) {
    // Fast scan: find next escape char
    int nextPos = scanForEscape(haystack, pos, end, accelChars);
    if (nextPos >= end) {
        // No escape found — the state self-loops to end
        pos = end;
        break;
    }
    // Jump to escape position, resume normal DFA
    charsSearched += (nextPos - pos);
    pos = nextPos;
    // Fall through to normal transition at pos
}
```

The `scanForEscape` function depends on the number of escape chars:
- **1 escape char:** Use a tight `while (haystack[pos] != escapeChar) pos++` loop, or for String haystacks, `haystackStr.indexOf(escapeChar, pos)` which is SIMD-intrinsified.
- **2-3 escape chars:** Use a simple loop with 2-3 comparisons per char. Still faster than classify + nextState (2 array loads).

#### Limitations

- **Maximum 3 escape chars** (matching upstream). More than 3 escape chars means the memchr/indexOf approach loses its advantage.
- **ASCII space exclusion** (matching upstream's `accel.rs:456`): Space occurs so frequently in most text that accelerating on it tends to hurt rather than help.
- **Unicode caveat:** For patterns with Unicode char classes (like `\w+` in Unicode mode), the DFA has many equivalence classes and acceleration may not apply. However, for ASCII-range patterns (which covers charClass and multiline benchmarks), it works well.
- **Lazy analysis:** The acceleration check happens when a state is first computed. If the DFA cache is cleared (due to capacity overflow), acceleration data is lost and must be recomputed.

### How They Work Together

For `[a-zA-Z]+` on Sherlock text:

1. **Start state** transitions on `[a-zA-Z]` → matching state, all others → self-loop.
   - Start state is an acceleration candidate, but with 52 escape chars (too many for the 3-char limit).
   - However, if a prefilter exists, the prefilter-at-start handles this case instead.
   - If no prefilter: start state acceleration won't fire (too many escape chars).

2. **Matching state** transitions on `[a-zA-Z]` → self-loop, non-`[a-zA-Z]` → match/dead.
   - Matching state IS an acceleration candidate — escape chars are the few non-alpha chars that appear between words (space, comma, period, etc.)
   - Wait — actually, the matching state escapes on ALL non-`[a-zA-Z]` chars, which is more than 3. So acceleration doesn't directly apply here either.

Hmm. Let me reconsider. For `[a-zA-Z]+`, the DFA has roughly 2 equivalence classes: `[a-zA-Z]` and everything else. The start state's transitions: class 0 → matching state, class 1 → self-loop (start). The matching state: class 0 → self-loop, class 1 → match/dead.

The escape from the start state is class 0 (1 class = 1 escape). The escape from the matching state is class 1 (1 class = 1 escape).

**So acceleration DOES apply** — each state has exactly 1 escape class. The key insight: we accelerate on **equivalence class representatives**, not on individual characters. If class 0 has representative `a`, we scan for `a` to find the next start-of-match. If class 1 has representative ` ` (space), we scan for space to find the end of match.

But we can't use `indexOf('a')` because the class contains ALL of `[a-zA-Z]`, not just `a`. We need to scan for any char in the class.

For the start state: we need to find the next `[a-zA-Z]` char. That's not a single-char indexOf — it's a predicate scan.

**Revised approach:** Instead of storing escape *characters*, store escape *class IDs*. Then the fast scan checks `classify(c) == escapeClassId`. But that still requires classify() per char, which is what we're trying to avoid.

**Better approach:** For ASCII-dominant patterns, build a `boolean[128]` table for "is this char an escape char?" at acceleration analysis time. Then the scan is `while (pos < end && !escapeTable[haystack[pos]]) pos++`. Single array load + branch per char, no classify needed.

For `[a-zA-Z]+`:
- Start state escape table: `[a-zA-Z]` chars are escapes (they leave the start state)
- Matching state escape table: non-`[a-zA-Z]` chars are escapes (they leave the matching state)
- Scan: `while (!escapeTable[c]) pos++` — 1 array load per char vs 2 (classify + nextState)

### Revised Acceleration Representation

```java
// Per accelerated state: a boolean[128] table for ASCII escape detection.
// true = this char escapes the self-loop (transitions to a different state).
// null = not accelerable (or not analyzed yet, or non-ASCII escapes).
boolean[][] accelEscapeTable;  // indexed by stateIdx = stateId / stride
```

For non-ASCII chars that aren't in the table, fall through to normal DFA processing.

### Expected Impact

For `[a-zA-Z]+` on Sherlock (174K matches, 898K chars):
- **Start state:** Currently scans ~360K non-alpha chars at 2 loads/char. With acceleration: 1 load/char. Saves ~50% of start-state time.
- **Matching state:** Currently scans ~538K alpha chars at 2 loads/char. With acceleration: 1 load/char. Saves ~50% of matching-state time.
- **Combined:** Forward DFA should improve by ~40-50%.

For `(?m)^.+$`:
- The `.+` state self-loops on everything except `\n`. Acceleration with `escapeTable[\n] = true` means we scan for `\n` at 1 load/char instead of running the DFA. For English text with ~13K lines, this is a massive speedup — we essentially use `indexOf('\n')` to find line boundaries.

## Testing Strategy

1. **Unit tests for acceleration detection:** Given a DFA state's transition row, verify correct identification of accelerable states and escape tables.
2. **Correctness tests:** All 879 upstream tests must pass. Acceleration is purely an optimization — semantics are unchanged.
3. **Benchmarks:** charClass, multiline, unicodeWord should all improve. No regressions on other benchmarks.

## Risks and Mitigations

- **Acceleration analysis cost:** Computing the escape table adds work when a DFA state is first computed. Mitigated by lazy computation and by only analyzing states that are visited frequently (the hot path naturally selects these).
- **Memory per state:** `boolean[128]` = 128 bytes per accelerated state. For typical patterns with 5-10 DFA states, this is <1KB total. Acceptable.
- **Non-ASCII fallback:** For chars >= 128, we fall through to normal DFA processing. This is correct but means acceleration doesn't help for non-ASCII-heavy text. Acceptable since our benchmark haystacks are primarily ASCII.
- **Cache clears:** When the lazy DFA cache overflows and clears, acceleration data is lost. States will be re-analyzed on next visit. Same cost model as the DFA states themselves.
