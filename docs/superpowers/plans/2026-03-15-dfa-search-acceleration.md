# DFA Search Acceleration Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate prefilter-at-start-state and acceleration states into the lazy DFA search loop, allowing the DFA to skip self-looping regions at ~1 array load/char instead of 2.

**Architecture:** Two complementary mechanisms in `searchFwdLong()`: (1) when at the start state, call the prefilter to skip ahead to the next candidate position, and (2) when at any "accelerated" state (where most transitions self-loop), use a `boolean[128]` escape table to scan forward with a single array load per char instead of classify + nextState. Both integrate into the existing outer dispatch after the unrolled inner loop.

**Tech Stack:** Java 21, JMH (benchmarks), JUnit 5 (tests)

**Spec:** `docs/superpowers/specs/2026-03-15-dfa-search-acceleration-design.md`

**Build/test commands:**
- Build: `./mvnw compile`
- All tests: `./mvnw test` (full reactor, never `-pl`)
- Single test: `./mvnw test -Dtest="fully.qualified.ClassName" -Dsurefire.failIfNoSpecifiedTests=false`
- Never use `./mvnw install`
- Never use `System.out.println` for debugging — use assertions in tests or the java-debugger skill
- Benchmarks: `./mvnw -P bench package -DskipTests && java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5`

---

## Chunk 1: Acceleration State Detection

### Task 1: Add acceleration analysis to DFACache

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/DFACache.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/DFACacheTest.java`

After a DFA state is computed (via `computeNextState` and cached in the transition table), analyze its transition row to detect if it's accelerable: a state where most transitions loop back to itself and only a small set of equivalence classes escape.

- [ ] **Step 1: Add acceleration storage to DFACache**

Add fields to `DFACache`:

```java
// Acceleration: per-state boolean[128] escape tables.
// Indexed by stateIdx (= stateId / stride).
// null = not analyzed or not accelerable. Non-null = escape chars for ASCII.
// escapeTable[c] == true means char c leaves the self-loop.
private boolean[][] accelTables;
```

Initialize in the constructor (after existing `transTable` init):
```java
this.accelTables = new boolean[initialCapacity][];
```

Grow in `ensureCapacity` alongside `transTable`:
```java
if (stateIdx >= accelTables.length) {
    accelTables = Arrays.copyOf(accelTables, Math.max(accelTables.length * 2, stateIdx + 1));
}
```

Clear in `clear()`:
```java
Arrays.fill(accelTables, null);
```

Add accessor:
```java
/** Returns the ASCII escape table for this state, or null if not accelerable. */
public boolean[] accelTable(int stateId) {
    int idx = stateId / stride;
    return idx < accelTables.length ? accelTables[idx] : null;
}
```

- [ ] **Step 2: Add acceleration analysis method**

Add to `DFACache`:

```java
/**
 * Analyze a state's transitions to determine if it's accelerable.
 * A state is accelerable if all but 1-3 equivalence classes self-loop.
 * Call this after a state's transitions are fully populated.
 *
 * @param stateId the state to analyze
 * @param stride  the transition table stride
 * @param classCount number of char equivalence classes
 * @param charClasses the char classifier (for building the escape table)
 */
public void analyzeAcceleration(int stateId, int stride, int classCount,
                                 CharClasses charClasses) {
    int idx = stateId / stride;
    if (idx >= accelTables.length) return;

    // Count non-self-loop classes
    int escapeCount = 0;
    for (int c = 0; c < classCount; c++) {
        int target = transTable[stateId + c];
        if (target != DFACache.UNKNOWN && target != stateId
                && (target & 0x7FFF_FFFF) != (stateId & 0x7FFF_FFFF)) {
            escapeCount++;
        }
    }

    // Only accelerate if 1-3 classes escape (matching upstream limit)
    if (escapeCount == 0 || escapeCount > 3) return;

    // Build boolean[128] escape table for ASCII chars
    boolean[] table = new boolean[128];
    boolean hasEscape = false;
    for (int c = 0; c < 128; c++) {
        int classId = charClasses.classify((char) c);
        int target = transTable[stateId + classId];
        // Escape = target is not self (ignoring match flag)
        if (target != DFACache.UNKNOWN && target != stateId
                && (target & 0x7FFF_FFFF) != (stateId & 0x7FFF_FFFF)) {
            table[c] = true;
            hasEscape = true;
        }
    }

    if (hasEscape) {
        accelTables[idx] = table;
    }
}
```

Note: match-flagged states (negative IDs) need the `& 0x7FFF_FFFF` mask to compare the raw state ID. A match-flagged self-loop is still a self-loop.

- [ ] **Step 3: Add tests for acceleration detection**

Add to `DFACacheTest.java`:

```java
@Test
void accelTableNullForNewState() {
    // Unanalyzed states should return null
    var cc = CharClasses.identity();
    var cache = new DFACache(cc, 64 * 1024, 10);
    assertNull(cache.accelTable(0)); // DEAD state
}
```

More meaningful tests will be in the integration test (Task 3) where we build a real DFA and verify acceleration detection on actual patterns.

- [ ] **Step 4: Run tests**

Run: `./mvnw test`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/DFACache.java
git add regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/DFACacheTest.java
git commit -m "feat: add acceleration state detection to DFACache"
```

---

## Chunk 2: Prefilter at Start State + Acceleration in Search Loop

### Task 2: Integrate prefilter and acceleration into searchFwdLong()

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java:103-217` (searchFwdLong)
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java:36-75` (fields, constructor, create)
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java`

#### Part A: Add prefilter support to LazyDFA

- [ ] **Step 1: Add prefilter field and wiring**

Add to `LazyDFA`:

```java
private final Prefilter prefilter; // nullable — set by Strategy
private final boolean universalStart; // true if no prefix look-around
```

Update constructor and `create()`:
```java
private LazyDFA(NFA nfa, CharClasses charClasses, Prefilter prefilter) {
    this.nfa = nfa;
    this.charClasses = charClasses;
    this.lookSetAny = nfa.lookSetAny();
    this.prefilter = prefilter;
    // Universal start = no prefix look-around assertions.
    // When true, the start state is always the same regardless of position,
    // so we can skip recomputing it after a prefilter jump.
    this.universalStart = lookSetAny.isEmpty();
}
```

Update `create()` to accept an optional prefilter:
```java
public static LazyDFA create(NFA nfa, CharClasses charClasses) {
    return create(nfa, charClasses, null);
}

public static LazyDFA create(NFA nfa, CharClasses charClasses, Prefilter prefilter) {
    // ... existing bail-out checks ...
    return new LazyDFA(nfa, charClasses, prefilter);
}
```

Add import for `Prefilter`:
```java
import lol.ohai.regex.automata.meta.Prefilter;
```

- [ ] **Step 2: Add prefilter-at-start and acceleration to searchFwdLong()**

The key changes to `searchFwdLong()`:

**Before the main loop** (after `int lastMatchEnd = -1;`): add initial prefilter skip:

```java
// Initial prefilter: skip ahead to first candidate before entering DFA loop
// Ref: upstream/regex/regex-automata/src/hybrid/search.rs:72-83
if (prefilter != null && !input.isAnchored()) {
    long span = prefilter.findSpan(input.haystackStr(), pos, end);
    if (span < 0) {
        cache.charsSearched = charsSearched;
        return SearchResult.NO_MATCH;
    }
    int candidatePos = Prefilter.spanStart(span);
    if (candidatePos > pos) {
        charsSearched += (candidatePos - pos);
        pos = candidatePos;
        if (!universalStart) {
            sid = getOrComputeStartState(input.withBounds(pos, end, false), cache);
            if (sid == dead) { cache.charsSearched = charsSearched; return SearchResult.NO_MATCH; }
        }
    }
}
```

**In the outer dispatch** (after `if (pos >= end) break;`): add start-state prefilter check and acceleration check BEFORE the normal classify+nextState:

```java
// Start-state prefilter acceleration
// Ref: upstream/regex/regex-automata/src/hybrid/search.rs:233-262
if (prefilter != null && !input.isAnchored() && sid == startSid) {
    long span = prefilter.findSpan(input.haystackStr(), pos, end);
    if (span < 0) {
        cache.charsSearched = charsSearched;
        // No more candidates — but we might have a pending match
        break;
    }
    int candidatePos = Prefilter.spanStart(span);
    if (candidatePos > pos) {
        charsSearched += (candidatePos - pos);
        pos = candidatePos;
        if (!universalStart) {
            sid = getOrComputeStartState(input.withBounds(pos, end, false), cache);
            if (sid == dead) break;
        }
        continue;
    }
    // candidatePos == pos: prefilter didn't advance, fall through to normal transition
}

// Acceleration: if current state has an escape table, scan forward
boolean[] accelTable = cache.accelTable(sid & 0x7FFF_FFFF);
if (accelTable != null) {
    // Scan ahead using the escape table (1 array load per char vs 2)
    int scanPos = pos;
    while (scanPos < end) {
        char c = haystack[scanPos];
        if (c < 128 && accelTable[c]) break; // escape char found
        if (c >= 128) break; // non-ASCII: fall back to normal DFA
        scanPos++;
    }
    if (scanPos > pos) {
        // Skipped some chars — state stayed the same (self-loop)
        charsSearched += (scanPos - pos);
        pos = scanPos;
        if (pos >= end) break;
        // Fall through to normal transition at the escape char
    }
}
```

**Important:** The `startSid` variable needs to be captured after `getOrComputeStartState()`:
```java
int sid = getOrComputeStartState(input, cache);
int startSid = sid; // remember for prefilter-at-start check
```

- [ ] **Step 3: Trigger acceleration analysis after computeNextState**

In `searchFwdLong()`, after the UNKNOWN slow path computes and caches a new state, trigger acceleration analysis:

```java
if (nextSid == DFACache.UNKNOWN) {
    cache.charsSearched = charsSearched;
    nextSid = computeNextState(cache, sid, classId, haystack[pos]);
    if (nextSid == quit) return SearchResult.gaveUp(pos);
    cache.setTransition(sid, classId, nextSid);

    // Analyze the TARGET state for acceleration (not the source)
    int rawNext = nextSid & 0x7FFF_FFFF;
    if (rawNext != dead && rawNext != quit && cache.accelTable(rawNext) == null) {
        cache.analyzeAcceleration(rawNext, stride, charClasses.classCount(), charClasses);
    }

    sid = nextSid;
    // ... rest of existing code
}
```

Note: We analyze the TARGET state (the state we just transitioned INTO), not the source state. This is because the target is the state we'll be in next iteration — if it's accelerable, we want to know for the next loop iteration.

We also need to analyze states that are computed via the fast path (when all transitions are already cached). The simplest approach: analyze on first UNKNOWN transition, which naturally covers all states as they're created. If a state is never hit via UNKNOWN (because all its transitions were precomputed), it's already fully cached and the unrolled inner loop handles it efficiently.

- [ ] **Step 4: Run full test suite**

Run: `./mvnw test`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java
git commit -m "$(cat <<'EOF'
feat: integrate prefilter-at-start and acceleration states in DFA search

Prefilter: when at start state during forward search, call the prefilter
to skip ahead to the next candidate position. Fires before the main
loop and whenever the DFA returns to start state.

Acceleration: after computing a new DFA state, analyze its transition
row. If most transitions self-loop with ≤3 escape classes, build a
boolean[128] ASCII escape table. At accelerated states, scan forward
with 1 array load/char instead of classify + nextState (2 loads).

Ref: upstream/regex/regex-automata/src/hybrid/search.rs:72-83, 233-262
Ref: upstream/regex/regex-automata/src/dfa/accel.rs
EOF
)"
```

### Task 3: Wire prefilter into LazyDFA from Regex.compile()

**Files:**
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`
- Test: full upstream suite (879 tests)

Currently `Regex.compile()` creates `LazyDFA.create(nfa, charClasses)` without passing the prefilter. We need to pass it through for patterns that have prefix literals.

- [ ] **Step 1: Pass prefilter to forward LazyDFA**

In `Regex.java`, update the forward DFA creation:

```java
// Before:
LazyDFA forwardDFA = charClasses != null
        ? LazyDFA.create(nfa, charClasses) : null;

// After:
LazyDFA forwardDFA = charClasses != null
        ? LazyDFA.create(nfa, charClasses, prefilter) : null;
```

The reverse DFA does NOT get a prefilter (it searches backwards, prefilters don't help there).

Note: the `prefilter` variable is already computed at this point in the code (lines 80-81). For the `PrefilterOnly` path (line 89), the prefilter is consumed there and never reaches the DFA. For the `Core` path, the prefilter is passed to `Strategy.Core` AND to the `LazyDFA`. Both use it — the Strategy uses it for the prefilter loop, the DFA uses it for start-state acceleration.

- [ ] **Step 2: Run full test suite**

Run: `./mvnw test`
Expected: All tests PASS (including 879 upstream tests).

- [ ] **Step 3: Commit**

```bash
git add regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "feat: pass prefilter to forward LazyDFA for start-state acceleration"
```

---

## Chunk 3: Benchmarks and Documentation

### Task 4: Run benchmarks and update documentation

- [ ] **Step 1: Build and run benchmarks**

```bash
./mvnw -P bench package -DskipTests
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5 2>&1 | tee /tmp/bench-accel.txt
```

Focus on:
- **charClass:** Expect improvement from acceleration in the matching state (fewer self-loop chars processed via DFA)
- **multiline:** Expect large improvement from `.+` state acceleration (scan for `\n` with 1 load/char)
- **alternation:** Expect improvement from prefilter-at-start (skip non-matching regions)
- **unicodeWord:** May see modest improvement (ASCII portions accelerated, non-ASCII falls back)

- [ ] **Step 2: Run profiling test**

```bash
./mvnw test -Dtest="lol.ohai.regex.automata.meta.StrategyProfilingTest#charClassComponentBreakdown" -Dsurefire.failIfNoSpecifiedTests=false
```

Compare three-phase time against the 10,968 µs baseline from stage-8.

- [ ] **Step 3: Compare against baseline**

No benchmark should regress by >2×. Expected improvements:

| Benchmark | Baseline (stage-9) | Target |
|---|---|---|
| charClass | 78 | > 100 |
| multiline | 244 | > 400 |
| alternation | 49 | > 60 |
| unicodeWord | 15,054 | > 18,000 |

- [ ] **Step 4: Update lazy-dfa-gaps.md**

Add "DFA Search Acceleration — Implemented" section.

- [ ] **Step 5: Commit and tag**

```bash
git add docs/architecture/lazy-dfa-gaps.md docs/architecture/stage-progression.md
git commit -m "docs: add DFA search acceleration to architecture docs, stage-10"
git tag stage-10-dfa-acceleration
git push && git push --tags
```
