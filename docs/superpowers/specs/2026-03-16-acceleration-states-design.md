# Dense DFA Acceleration States — Spec 2

**Date:** 2026-03-16
**Status:** Draft
**Part of:** Full DFA engine (Spec 2 of 3)
**Depends on:** Stage 12 (dense DFA engine)

## Motivation

The dense DFA (stage 12) eliminated UNKNOWN-state handling, giving charClass +17% and unicodeWord +25%. But the per-char cost is still 2 array loads: `classify(haystack[pos])` + `transTable[sid + classId]`. For states that self-loop on most input chars, we can replace this with a 1-load scan using a precomputed escape table.

**Target patterns and their accelerable states:**
- `[a-zA-Z]+` matching state: self-loops on `[a-zA-Z]`, escapes on non-alpha → 1 escape class
- `\w+` matching state: self-loops on word chars, escapes on non-word → 1 escape class
- `(?m)^.+$` matching state: self-loops on everything except `\n` → 1 escape class (but requires Spec 3 multi-position start states to use dense DFA)

**Upstream ref:** `upstream/regex/regex-automata/src/dfa/accel.rs:1-51` (design), `upstream/regex/regex-automata/src/dfa/search.rs:150-172` (integration).

## Design

### Detection (build time)

After `DenseDFABuilder` constructs the transition table but before returning the `DenseDFA`, analyze each state for acceleration eligibility:

```
For each state sid (skip dead, quit, index-0 padding):
  escapeClassCount = 0
  for cls in 0..classCount-1:  // exclude EOI class
    if transTable[sid + cls] != sid:
      escapeClassCount++
      if escapeClassCount > 3: not accelerable, skip
  if escapeClassCount <= 3:
    build boolean[128] escape table
    mark state as accelerated
```

The `boolean[128]` escape table maps each ASCII char to `true` if its equivalence class transitions to a different state (i.e., "escapes" the self-loop). Non-ASCII chars (>= 128) always break out of the acceleration scan and fall through to normal DFA processing.

**Max escape classes:** 3 (matching upstream). More than 3 means the scan has too many branches per char to be faster than the normal 2-load path.

**0-escape-class states:** Unlike upstream, which requires ≥1 needle and panics on empty accels (`accel.rs:103`), our boolean-table approach naturally handles 0-escape states. The scan degenerates to a run-to-end on ASCII input, which is correct for states that truly never exit on ASCII (e.g., `[^\x00-\x7F]*`). This is an intentional divergence — the table-based scan has no special case for 0 needles.

**EOI class exclusion:** The escape-class detection loop iterates `0..classCount-1`, deliberately excluding the EOI class (`classCount`). EOI transitions are handled outside the main scan loop entirely (by `handleRightEdge` after the search loop exits). Including EOI in escape detection would create false escapes — EOI is not a char that appears in the haystack.

**Storage:** `boolean[][] accelTables` on `DenseDFA`, indexed by state index (`sid / stride`). `null` entry means not accelerated. Typical: 1-3 accelerated states × 128 bytes = 128-384 bytes total.

### Building the escape table

For each accelerated state, iterate all ASCII chars (0-127) and check if their equivalence class is an escape class:

```java
boolean[] table = new boolean[128];
for (int c = 0; c < 128; c++) {
    int cls = charClasses.classify((char) c);
    if (transTable[sid + cls] != sid) {
        table[c] = true;  // this char escapes the self-loop
    }
}
```

### Scan integration in DenseDFA.searchFwd

Add the acceleration scan at the top of the outer `while` loop, before the unrolled inner loop:

```java
while (pos < end) {
    // Acceleration: fast-scan self-looping states
    boolean[] escTable = accelTables[sid / stride];
    if (escTable != null) {
        // If this is also a match state, record match at entry
        if (sid >= minMatchState) {
            lastMatchEnd = pos;
        }
        // Scan: 1 array load per char instead of 2
        while (pos < end) {
            char c = haystack[pos];
            if (c >= 128 || escTable[c]) break;
            pos++;
        }
        // After scan: if match state, update match end to final pos
        if (sid >= minMatchState) {
            lastMatchEnd = pos;
        }
        if (pos >= end) break;
    }

    // Existing unrolled inner loop (unchanged)
    while (pos + 3 < end) { ... }

    // Existing outer dispatch (unchanged)
    ...
}
```

**Key properties:**
- The scan doesn't change `sid` — all scanned chars are self-loops
- After the scan, `pos` points at the escape char (or end)
- Non-ASCII chars (`c >= 128`) break out and fall through to normal DFA processing
- Match states that are also accelerated get `lastMatchEnd` updated at both entry and exit of the scan — every self-loop char extends the match
- The accel check is a single array lookup + null test — negligible overhead for non-accelerated states

**Estimated bytecode addition:** ~30-40 bytes. searchFwd is currently 330 bytes → ~370 after. Well within C2's optimization zone.

### Changes to DenseDFA

Add field:
```java
private final boolean[][] accelTables;  // indexed by stateIdx = sid / stride
```

Constructor takes the additional parameter. `null` entries for non-accelerated states. The array length equals `stateCount`.

### Changes to DenseDFABuilder

After constructing the transition table and shuffling match states, add an acceleration analysis pass:

```java
boolean[][] accelTables = new boolean[stateCount][];
for (int i = 0; i < stateCount; i++) {
    int sid = i * stride;
    if (sid == dead || sid == quit) continue;

    // Count escape classes
    int escapes = 0;
    for (int cls = 0; cls < classCount; cls++) {  // exclude EOI
        if (newTable[sid + cls] != sid) {
            escapes++;
            if (escapes > 3) break;
        }
    }

    if (escapes <= 3) {
        boolean[] table = new boolean[128];
        for (int c = 0; c < 128; c++) {
            int cls = charClasses.classify((char) c);
            if (newTable[sid + cls] != sid) {
                table[c] = true;
            }
        }
        accelTables[i] = table;
    }
}
```

Pass `accelTables` to the `DenseDFA` constructor.

## Testing Strategy

1. **Unit test: acceleration detection** — build dense DFA for `[a-z]+`, verify the matching state has a non-null escape table. Verify escape table marks non-alpha ASCII chars as `true` and alpha chars as `false`.

2. **Unit test: acceleration for alternation** — `cat|dog|bird` should have states with many transitions to different states (not accelerable for those states), but the "no match yet" scanning state may be accelerable.

3. **Comparison test** — extend `denseMatchesLazyForSimplePatterns` to cover patterns whose matching states are accelerated. Dense DFA with acceleration must produce identical match positions to lazy DFA.

4. **No acceleration for start-only patterns** — patterns like `a` where no state self-loops significantly shouldn't produce accelerated states. Verify `accelTables` entries are null.

5. **Full upstream TOML suite** — all tests must still pass after adding acceleration.

## Benchmarks

| Benchmark | Dense DFA? | Accelerated? | Expected impact |
|-----------|-----------|-------------|----------------|
| `[a-zA-Z]+` | Yes | Yes (matching state) | **Large improvement** — scan replaces 2-load/char with 1-load |
| `\w+` | Yes | Yes (matching state) | **Improvement** — same acceleration |
| `Sherlock\|Watson\|...` | Yes | Possibly (scanning states) | Moderate improvement |
| `(?m)^.+$` | No (look-assertions) | N/A | Unchanged (needs Spec 3) |
| `(\d{4})-(\d{2})` | Yes | Possibly (digit-matching states) | Slight improvement |

## Risks

| Risk | Mitigation |
|------|-----------|
| Acceleration scan adds bytecodes to searchFwd | ~30-40 bytes, well within C2 limits (330→370) |
| Non-accelerated states pay cost of null check | Single array load + null test, negligible |
| Match-state acceleration records wrong lastMatchEnd | Test against lazy DFA for all patterns |
| boolean[128] table misclassifies chars | Build from classify() which is already tested |
| Unicode `\w+` may not be accelerable | Unicode word-char patterns may produce >3 escape classes, making the matching state non-accelerable. Verify during implementation — if `\w+` has >3 escapes, the benchmark improvement is from the dense DFA itself (stage 12), not from acceleration |

## What We Don't Build

| Feature | Why deferred |
|---------|-------------|
| Start state acceleration | Less impactful than matching-state acceleration |
| Non-ASCII escape tables | `boolean[256]` or wider — only needed for non-ASCII-dominant text |
| `String.indexOf` single-char fast path | Optimization for 1-escape-char states — future |
| Unrolled acceleration scan | 4× unrolling the escape scan — future micro-opt |

## Summary

| Component | File | Change |
|-----------|------|--------|
| Acceleration analysis | `dfa/dense/DenseDFABuilder.java` | Add post-build analysis pass |
| Acceleration tables storage | `dfa/dense/DenseDFA.java` | Add `boolean[][] accelTables` field |
| Acceleration scan | `dfa/dense/DenseDFA.java` | Add scan block in searchFwd |
| Tests | `dfa/dense/DenseDFAAccelTest.java` | New test class |
