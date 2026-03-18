# Design: DFA Special-State Taxonomy & Acceleration Restructuring

**Date:** 2026-03-18
**Status:** Approved
**Upstream Reference:** `upstream/regex/regex-automata/src/dfa/special.rs`, `search.rs`, `accel.rs`, `dense.rs`
**Benchmarks Targeted:** charClass (2.9x → ~2x), multiline (4.3x → ~1.5x)

## Background

The upstream comparison (`docs/architecture/upstream-comparison.md`) identified three tightly coupled structural gaps in our dense DFA:

1. **Gap 2:** Acceleration is checked at the TOP of every search loop iteration (before the unrolled inner loop). Upstream checks it ONLY in the special-state dispatch (cold path after the unrolled loop exits). This bloats our hot path with an array load + null check + branch on every state.

2. **Gap 3:** Start states are explicitly excluded from acceleration (`DenseDFABuilder.java:409`, `i == 0` skip). Upstream accelerates start states naturally because they participate in the special-state taxonomy. This is the primary cause of the 4.3x multiline regression.

3. **Issue 1:** `boolean[128]` escape tables (~144 bytes each) vs upstream's compact 1-3 byte needles (~8 bytes). Memory overhead and cache pressure.

These three changes are tightly coupled — they all modify `DenseDFA.searchFwd()` and `DenseDFABuilder` — so they are designed as a single atomic change.

## Upstream Architecture Reference

### Special State Taxonomy (`special.rs:142-180`)

Upstream partitions state IDs into contiguous ranges at the BOTTOM of the state space:

```
[dead(0)] [quit] [match states...] [accel states...] [normal states(>max)]
                                    ↑ may overlap ↑
```

The `Special` struct (`special.rs:159-180`) stores:
- `max: StateID` — single threshold; `id <= max` means special
- `quit_id` — quit state (dead if no quit bytes)
- `min_match, max_match` — contiguous match range
- `min_accel, max_accel` — contiguous accel range (may overlap with match)

Key method (`special.rs:419-423`):
```rust
pub(crate) fn is_special_state(&self, id: StateID) -> bool {
    id <= self.max
}
```

### Overlap Semantics (`special.rs:45-59`)

- Match + accel: allowed (a match state that self-loops, like `[a-z]+`)
- Start + accel: allowed (a start state that self-loops, like `(?m)^.+`)
- Match + start: guaranteed NOT to overlap (matches delayed by 1 position)

### Search Loop (`search.rs:98-181`)

The unrolled loop guard is `dfa.is_special_state(sid)` — one comparison. After breaking out:

```rust
// search.rs:125-181 (simplified)
if dfa.is_start_state(sid) {
    if has_prefilter { apply_prefilter(); }
    else if dfa.is_accel_state(sid) { accelerate(); continue; }
} else if dfa.is_match_state(sid) {
    record_match();
    if dfa.is_accel_state(sid) { accelerate(); continue; }
} else if dfa.is_accel_state(sid) {
    accelerate(); continue;
} else if dfa.is_dead_state(sid) {
    return;
} else { // quit
    return error;
}
```

### Accel Data Structure (`accel.rs:259-276`)

Fixed 8-byte records: `[length(1), needle0, needle1, needle2, padding(4)]`. Indexed densely by `(sid - min_accel) >> stride2`. Uses `memchr`/`memchr2`/`memchr3` (SIMD-accelerated) to scan.

## Design

### State ID Layout

**Current:**
```
[padding(0)] [dead(stride)] [quit(2*stride)] [normal states...] [match states(≥minMatchState)]
Guard: sid <= quit || sid >= minMatchState  (2 comparisons)
```

**New:**
```
[dead(0)] [quit(stride)] [match-only...] [match+accel...] [accel-only...] [normal states...]
                          ^minMatch       ^minAccel                        ^maxSpecial + 1
                                          ^maxMatch        ^maxAccel
                          ←──── match range ────→
                                          ←──── accel range ────→
Guard: sid <= maxSpecial  (1 comparison)
```

Properties:
- Dead state = 0 (zero-initialized arrays default to dead)
- Quit state = stride (equals dead if no quit chars)
- Match states contiguous: `[minMatch, maxMatch]`
- Accel states contiguous: `[minAccel, maxAccel]`, overlapping with match range
- `maxSpecial` = max(maxMatch, maxAccel) — single threshold
- Normal states: everything above `maxSpecial`

**Invariant:** `minMatch > stride` and `minAccel > stride` always (dead=0 and quit=stride are never in match/accel ranges). This ensures the dead/quit branches in the dispatch are only reachable through the final else clauses, not through the match/accel range checks.

**Empty ranges:** When no match states exist, `minMatch = -1, maxMatch = -2` (range check trivially false). Same for accel. When both are empty, `maxSpecial = stride` (quit state), and the unrolled loop only breaks on dead/quit.

### Deliberate Simplification: No Start State Specialization

Upstream tracks `min_start`/`max_start` as a separate special-state category (`special.rs:176-179`), used for DFA-internal prefilter application at start states (`search.rs:126-155`). We intentionally **omit start state specialization** because:

1. Our prefilters are applied at the Strategy level, not inside the DFA search loop (confirmed correct by the upstream comparison — `docs/architecture/upstream-comparison.md`, "Prefilter Architecture Comparison")
2. Start state **acceleration** works without specialization: if a start state has ≤3 escape classes, it lands in the accel range and gets accelerated through the normal accel dispatch
3. YAGNI — if we later add DFA-internal prefilters (e.g., for Aho-Corasick integration), we can add `minStart`/`maxStart` then

Upstream's `set_no_special_start_states()` (`special.rs:412-417`) excludes starts from the special range when no prefilter exists — our design is equivalent to permanently having that mode active.

### DenseDFA Fields

Replace:
```java
// Old
private final int minMatchState;
private final boolean[][] accelTables;
private final char[] accelEscapeChars;
```

With:
```java
// New
private final int minMatch;      // first match state ID
private final int maxMatch;      // last match state ID
private final int minAccel;      // first accel state ID
private final int maxAccel;      // last accel state ID
private final int maxSpecial;    // max(maxMatch, maxAccel) — unrolled loop threshold
private final char[][] accelNeedles;  // [accelIndex] → char[1..3] escape chars
```

Methods:
```java
boolean isSpecial(int sid) { return sid <= maxSpecial; }
boolean isMatch(int sid)   { return sid >= minMatch && sid <= maxMatch; }
boolean isAccel(int sid)   { return sid >= minAccel && sid <= maxAccel; }
boolean isDead(int sid)    { return sid == 0; }

int accelIndex(int sid)    { return (sid - minAccel) / stride; }
```

### Search Loop (`searchFwd`)

```java
outer:
while (at < end) {
    // --- UNROLLED INNER LOOP (hot path) ---
    // One guard, no special-state logic
    while (at < end) {
        sid = tt[sid + cc.classify(haystack[at])];
        if (sid <= maxSpecial || at + 3 >= end) break;
        at++;
        sid = tt[sid + cc.classify(haystack[at])];
        if (sid <= maxSpecial) break;
        at++;
        sid = tt[sid + cc.classify(haystack[at])];
        if (sid <= maxSpecial) break;
        at++;
        sid = tt[sid + cc.classify(haystack[at])];
        if (sid <= maxSpecial) break;
        at++;
    }

    // --- SPECIAL-STATE DISPATCH (cold path) ---
    if (sid <= maxSpecial) {
        if (sid >= minMatch && sid <= maxMatch) {
            // MATCH state
            if (sid == deadMatch) {
                lastMatchEnd = at;
                break;
            }
            lastMatchEnd = at;
            if (sid >= minAccel && sid <= maxAccel) {
                // Match + accel: skip forward through self-loop.
                // Do NOT update lastMatchEnd after acceleration —
                // match upstream (search.rs:162-167): mat is recorded
                // at the position where the match state was entered,
                // then acceleration jumps forward. The next time the
                // unrolled loop exits at a match state, mat is updated.
                // The self-loop invariant guarantees every position in
                // the skipped range is also a valid match end.
                at = accelerate(sid, haystack, at, end);
                if (at >= end) break;
                // Take transition on the escape char
                sid = tt[sid + cc.classify(haystack[at])];
                at++;
                continue;
            }
        } else if (sid >= minAccel && sid <= maxAccel) {
            // ACCEL only: skip forward
            at = accelerate(sid, haystack, at, end);
            if (at >= end) break;
            sid = tt[sid + cc.classify(haystack[at])];
            at++;
            continue;
        } else if (sid == 0) {
            // DEAD
            break;
        } else {
            // QUIT
            return SearchResult.gaveUp(at);
        }
    }
    at++;
}
```

Key differences from current loop:
1. Unrolled loop guard: `sid <= maxSpecial` (1 comparison, was 2)
2. No acceleration array access in hot path
3. Match recording in dispatch only
4. Acceleration naturally handles match+accel and start+accel overlaps

### Acceleration Method

```java
private int accelerate(int sid, char[] haystack, int at, int end) {
    char[] needles = accelNeedles[accelIndex(sid)];
    at++;  // skip current position (we're on a self-loop char)
    if (needles.length == 1) {
        int found = haystackString.indexOf(needles[0], at);
        return (found < 0 || found >= end) ? end : found;
    } else if (needles.length == 2) {
        int f0 = haystackString.indexOf(needles[0], at);
        int f1 = haystackString.indexOf(needles[1], at);
        if (f0 < 0 || f0 >= end) f0 = end;
        if (f1 < 0 || f1 >= end) f1 = end;
        return Math.min(f0, f1);
    } else {
        int f0 = haystackString.indexOf(needles[0], at);
        int f1 = haystackString.indexOf(needles[1], at);
        int f2 = haystackString.indexOf(needles[2], at);
        if (f0 < 0 || f0 >= end) f0 = end;
        if (f1 < 0 || f1 >= end) f1 = end;
        if (f2 < 0 || f2 >= end) f2 = end;
        return Math.min(f0, Math.min(f1, f2));
    }
}
```

Non-ASCII escape chars are allowed (unlike upstream which is byte-constrained). The ≤3 escape class limit remains the real gate.

### Acceleration Exclusions

Matching upstream (`accel.rs:449-458`), the ASCII space character (`' '`, U+0020) is **excluded** from acceleration needles. Space occurs too frequently in natural text, causing acceleration to find hits on nearly every word boundary — the overhead of the `indexOf` call plus re-entering the DFA loop exceeds the cost of just running the DFA through the spaces. If a state's only escape chars include space, it is not accelerated.

Concretely, during accel classification in the builder: after computing the set of escape chars for a state, if `' '` is among them, the state is **not** marked as acceleratable.

### Builder Changes (`DenseDFABuilder`)

The build sequence becomes:

**Phase 1: Materialize states** (existing, unchanged)
- Eagerly compute all reachable states via lazy DFA cache

**Phase 2: Classify states**
- For each state (excluding dead/quit):
  - Is it a match state? (`cache.getState(sid).isMatch()` or match-wrapper/dead-match)
  - Is it an accel candidate? (≤3 escape classes that transition away from self-loop, AND none of the escape chars is `' '` (U+0020) — see Acceleration Exclusions above)
  - Both? Neither?
- **Remove the `i == 0` skip** — start states participate in accel classification

**Phase 3: Compute shuffle order**
- Assign state IDs bottom-up: dead(0) → quit(stride) → match-only → match+accel → accel-only → normal
- Record ranges:
  - `minMatch` = first match-only state; `maxMatch` = last match+accel state (or last match-only if no overlap)
  - `minAccel` = first match+accel state; `maxAccel` = last accel-only state (or last match+accel if no accel-only)
  - This ensures: match range = `[minMatch, maxMatch]`, accel range = `[minAccel, maxAccel]`, with the match+accel overlap zone in both ranges
- Compute `maxSpecial = Math.max(maxMatch, maxAccel)`
- The `accelNeedles` array is indexed by `(sid - minAccel) / stride`, so every state in `[minAccel, maxAccel]` has an entry — all of them are acceleratable by construction (match+accel and accel-only states)

**Phase 4: Build remap table and apply**
- Remap all transitions in the transition table
- Remap start states array
- Remap dead-match state ID

**Phase 5: Build accelNeedles**
- For each state in `[minAccel, maxAccel]`:
  - Collect the escape chars (transitions that don't self-loop)
  - Store as `char[1..3]` in `accelNeedles[accelIndex(sid)]`

### Dead-Match and Match-Wrapper States

These synthetic states from the current implementation are preserved:

- **Dead-match state**: Placed in the match range. All transitions point to dead. Handled specially in dispatch (`if (sid == deadMatch) { lastMatchEnd = at; break; }`).

- **Match-wrapper states**: Placed in the match range. If the wrapped target is also an accel candidate, the wrapper is placed in the match+accel overlap zone so it gets acceleration treatment.

### What Does NOT Change

- `Strategy.java` — calls `DenseDFA.searchFwd()` the same way
- `LazyDFA.java` — keeps its own search loop (separate concern)
- `CharClasses.java` — unaffected
- `SearchResult.java` — return type unchanged
- Public API — no changes visible outside `dfa.dense` package
- `handleRightEdge()` — still processes EOI transitions after the main loop; uses `isMatch(sid)` which changes implementation but not semantics

## Testing Strategy

- **Full test suite (2,186 tests)**: Must pass with zero failures before and after
- **Upstream TOML suite (882 tests)**: Primary correctness gate — these encode exact match positions
- **Benchmark comparison**: Run full JMH suite against stage-14. Key targets:
  - charClass: expect +15-30%
  - multiline: expect 2-4x improvement
  - All others: neutral or slight improvement
  - Any benchmark moving >2x in either direction requires investigation

### Specific Shuffle/Classification Unit Tests

New tests in the DenseDFA test class for each special-state category:

1. **No match states** — unmatchable pattern (e.g., `[^\x00-\x{FFFF}]`): verify empty match range (`minMatch > maxMatch`), DFA returns no matches
2. **No accel states** — pattern where all states have >3 escape classes: verify empty accel range, search still works
3. **Match+accel overlap** — `[a-z]+`: the match state self-loops on a-z, has few escape chars. Verify it appears in both match and accel ranges, that acceleration fires, and match positions are correct
4. **Start state acceleration** — `(?m)^.+`: the start state self-loops on non-`\n`. Verify the start state is in the accel range, that `indexOf('\n')` acceleration fires, and match positions span full lines
5. **Dead-match state** — `foo$`: requires dead-match for EOI transition. Verify dead-match is in the match range, match positions are correct at end of input
6. **Match-wrapper states** — `(?m)^.+$`: match-wrappers for `\n`-triggered matches. Verify wrappers are in match range, positions correct at line boundaries
7. **Space exclusion** — pattern where a state's escape chars include `' '`: verify the state is NOT accelerated
8. **Accel with >3 escapes** — verify the state is NOT accelerated (existing behavior, regression test)

## Development Rules

Per CLAUDE.md:
- **Read upstream Rust code before writing** — cite file:line in commits
- **Never write ad-hoc Java files to /tmp** — write JUnit tests in `regex-automata/src/test/`
- **Use DebugInspector** for state inspection, never `System.out.println`
- **Run full reactor** (`./mvnw test`), never individual modules
- **Run benchmarks** after the change: `./mvnw -P bench package -DskipTests && java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5`

## Risk Assessment

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| Shuffle logic bug corrupts transition table | Medium | 2,186 tests catch match position errors |
| Match-wrapper/dead-match placement wrong | Medium | Existing tests cover end-assertion patterns |
| JIT regression from changed loop shape | Low | Benchmark gate catches >2x changes |
| Accel overlap miscounting | Low | Unit test each category: match-only, accel-only, match+accel |
