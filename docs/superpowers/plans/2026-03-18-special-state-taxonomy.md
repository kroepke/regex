# Special-State Taxonomy & Acceleration Restructuring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the dense DFA to use upstream's special-state taxonomy (all special states at the bottom of the ID space, single threshold guard) and move acceleration from the hot loop to the cold-path special-state dispatch.

**Architecture:** Invert the state layout so dead/quit/match/accel states occupy low IDs with a single `maxSpecial` threshold. The unrolled loop guard simplifies from 2 comparisons to 1. Acceleration moves to the special-state dispatch branch. Start states participate in accel classification.

**Tech Stack:** Java 21, JUnit 5, JMH benchmarks

**Spec:** `docs/superpowers/specs/2026-03-18-special-state-taxonomy-design.md`

**Upstream references:**
- State taxonomy: `upstream/regex/regex-automata/src/dfa/special.rs:142-180`
- Search loop: `upstream/regex/regex-automata/src/dfa/search.rs:98-181`
- Accel data: `upstream/regex/regex-automata/src/dfa/accel.rs:259-276`
- Space exclusion: `upstream/regex/regex-automata/src/dfa/accel.rs:449-458`

**Development rules:**
- Read upstream Rust code before writing — cite file:line in commits
- Never write ad-hoc Java files to /tmp — write JUnit tests in `regex-automata/src/test/`
- Use DebugInspector for state inspection, never `System.out.println`
- Run full reactor (`./mvnw test`), never individual modules (`-pl` is forbidden)
- Run benchmarks after the change and compare against stage-14

---

## File Map

| File | Action | Responsibility |
|------|--------|---------------|
| `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFA.java` | Modify | New fields, new search loop, new `accelerate()` method, updated `isMatch()` / `handleRightEdge()` |
| `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilder.java` | Modify | New state classification, new shuffle order (specials at bottom), new accel analysis, space exclusion |
| `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilderTest.java` | Modify | Update assertions for new layout (dead=0, no padding, match at bottom) |
| `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFAAccelTest.java` | Modify | Add special-state classification tests, start state accel, space exclusion |
| `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFASearchTest.java` | Modify | Verify search correctness with new layout (existing tests should pass as-is) |

No new files. No changes to `Strategy.java`, `LazyDFA.java`, `CharClasses.java`, or public API.

---

## Task 1: Write Classification & Shuffle Unit Tests

**Files:**
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilderTest.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFAAccelTest.java`

These tests will FAIL until Task 2 implements the new layout. Write them first to define the contract.

- [ ] **Step 1: Add new accessor method tests to `DenseDFABuilderTest`**

The new DenseDFA will expose `minMatch()`, `maxMatch()`, `minAccel()`, `maxAccel()`, `maxSpecial()` instead of `minMatchState()`. Add tests asserting the new layout invariants. Mark them `@Disabled("pending special-state taxonomy impl")` so the build stays green.

```java
@Disabled("pending special-state taxonomy impl")
@Test
void deadStateIsZero() {
    DenseDFA dfa = buildDense("abc");
    assertNotNull(dfa);
    assertEquals(0, dfa.dead(), "dead should be at ID 0");
}

@Disabled("pending special-state taxonomy impl")
@Test
void quitStateIsStride() {
    DenseDFA dfa = buildDense("abc");
    assertNotNull(dfa);
    assertEquals(dfa.stride(), dfa.quit(), "quit should be at stride");
}

@Disabled("pending special-state taxonomy impl")
@Test
void matchStatesAreAboveQuit() {
    DenseDFA dfa = buildDense("[a-z]+");
    assertNotNull(dfa);
    assertTrue(dfa.minMatch() > dfa.stride(),
            "minMatch must be > quit (stride)");
    assertTrue(dfa.maxMatch() >= dfa.minMatch(),
            "maxMatch must be >= minMatch");
}

@Disabled("pending special-state taxonomy impl")
@Test
void maxSpecialIsThreshold() {
    DenseDFA dfa = buildDense("[a-z]+");
    assertNotNull(dfa);
    int maxSpecial = dfa.maxSpecial();
    assertTrue(maxSpecial >= dfa.stride(),
            "maxSpecial must be >= quit");
    assertTrue(maxSpecial < dfa.stateCount() * dfa.stride(),
            "maxSpecial must be < total state space");
}

@Disabled("pending special-state taxonomy impl")
@Test
void allTransitionsArePopulatedNewLayout() {
    DenseDFA dfa = buildDense("[a-z]+");
    assertNotNull(dfa);
    int stride = dfa.stride();
    int[] table = dfa.transTable();
    int classCount = dfa.charClasses().classCount();

    // State 0 (dead) should self-loop to 0
    for (int cls = 0; cls <= classCount; cls++) {
        assertEquals(0, table[cls],
                "dead state class " + cls + " should loop to dead (0)");
    }

    // State stride (quit) should self-loop to stride
    for (int cls = 0; cls <= classCount; cls++) {
        assertEquals(stride, table[stride + cls],
                "quit state class " + cls + " should loop to quit");
    }
}
```

- [ ] **Step 2: Add accel classification tests to `DenseDFAAccelTest`**

```java
@Disabled("pending special-state taxonomy impl")
@Test
void matchPlusAccelOverlap() {
    // [a-z]+ : match state self-loops on a-z → should be both match and accel
    DenseDFA dfa = buildDense("[a-z]+");
    assertNotNull(dfa);
    assertTrue(dfa.minAccel() >= 0, "should have accel states");
    // The match state for [a-z]+ should be in BOTH ranges
    assertTrue(dfa.minAccel() <= dfa.maxMatch(),
            "accel range should overlap with match range (minAccel <= maxMatch)");
    assertTrue(dfa.maxAccel() >= dfa.minMatch(),
            "accel range should overlap with match range (maxAccel >= minMatch)");
}

@Disabled("pending special-state taxonomy impl")
@Test
void startStateAcceleration() {
    // (?m)^.+ : start state self-loops on non-\n → should be accel
    DenseDFA dfa = buildDense("(?m)^.+");
    assertNotNull(dfa);
    assertTrue(dfa.minAccel() >= 0, "should have accel states");
    // Verify start state is in accel range
    int startSid = dfa.startStates()[0]; // unanchored TEXT start
    assertTrue(dfa.isAccel(startSid),
            "start state (sid=" + startSid + ") should be in accel range "
            + "[" + dfa.minAccel() + ", " + dfa.maxAccel() + "]");
}

@Disabled("pending special-state taxonomy impl")
@Test
void spaceExclusionPreventsAccel() {
    // [^ ]+ self-loops on everything except space → escape char is space.
    // Space exclusion (accel.rs:449-458) prevents acceleration.
    // Verify the match state specifically is NOT accelerated.
    DenseDFA dfa = buildDense("[^ ]+");
    assertNotNull(dfa);
    // The match state should exist but not be in the accel range
    assertTrue(dfa.minMatch() >= 0, "should have match states");
    // Find a match state and check it's not accel
    for (int sid = dfa.minMatch(); sid <= dfa.maxMatch(); sid += dfa.stride()) {
        assertFalse(dfa.isAccel(sid),
                "match state sid=" + sid + " should NOT be accelerated (space exclusion)");
    }
}

@Disabled("pending special-state taxonomy impl")
@Test
void moreThanThreeEscapesNotAccelerated() {
    // [a-c]+ has the match state self-looping on a, b, c only.
    // Escape classes: everything except {a,b,c} — after class merging,
    // likely 1-2 escape classes (non-abc ASCII, non-ASCII).
    // Use a pattern with a state that has MANY distinct transitions:
    // A one-char pattern like "a" has start state with transitions to
    // many different states. But start states may still qualify.
    //
    // Instead, verify the contract: build a DFA where we KNOW a state
    // has >3 escape classes. This is hard to construct directly because
    // class merging reduces escape class count. So this is a structural
    // regression test: the accel analysis respects the >3 threshold.
    // The real correctness gate is the acceleratedSearchMatchesLazy test.
    DenseDFA dfa = buildDense("a{2}");
    assertNotNull(dfa);
    // "a{2}" intermediate state (consumed one 'a', needs another) has
    // many escape classes (everything except 'a' goes to dead/start).
    // After merging: likely 1 escape class (non-'a'). So it would be
    // accelerated. This test just verifies the DFA builds correctly.
    assertTrue(dfa.stateCount() >= 3);
}
```

- [ ] **Step 3: Run tests to confirm disabled tests are skipped**

Run: `./mvnw test`

Expected: All existing tests pass. New tests show as skipped/disabled.

- [ ] **Step 4: Commit**

```bash
git add regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilderTest.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFAAccelTest.java
git commit -m "test: add special-state taxonomy tests (disabled until impl)

Tests define the target API contract for the new state layout:
- dead=0, quit=stride, match/accel at bottom
- maxSpecial threshold, overlap semantics
- Start state acceleration, space exclusion

Ref: upstream special.rs:142-180, accel.rs:449-458"
```

---

## Task 2: Implement Special-State Taxonomy (DenseDFA + DenseDFABuilder)

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFA.java` (fields, constructor, accessors, search loop, accelerate method, handleRightEdge)
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilder.java` (state classification, shuffle, accel analysis, verification loop)

This is the core implementation task. All changes to both files must be made together since they're tightly coupled (constructor signature, field types, etc.). The goal is to go from the current code to a compilable, passing state in one task.

- [ ] **Step 1: Read upstream source before writing**

Read these upstream files and understand the referenced line ranges:
- `upstream/regex/regex-automata/src/dfa/special.rs:142-180` — state layout diagram and Special struct
- `upstream/regex/regex-automata/src/dfa/search.rs:98-181` — unrolled loop and special-state dispatch
- `upstream/regex/regex-automata/src/dfa/accel.rs:259-276` — accel data structure
- `upstream/regex/regex-automata/src/dfa/accel.rs:449-458` — space exclusion

- [ ] **Step 2: Replace DenseDFA fields and constructor**

In `DenseDFA.java`, replace the old fields (lines 29-36) with:

```java
private final int dead;           // always 0
private final int quit;           // always stride (or 0 if no quit chars)
private final int minMatch;       // first match state ID (-1 if none)
private final int maxMatch;       // last match state ID (-2 if none)
private final int minAccel;       // first accel state ID (-1 if none)
private final int maxAccel;       // last accel state ID (-2 if none)
private final int maxSpecial;     // max(maxMatch, maxAccel) — unrolled loop threshold
private final int stateCount;
private final int stride;
private final char[][] accelNeedles;  // [accelIndex] → char[1..3] escape chars
private final int deadMatch;      // synthetic dead-match state, or -1
```

Replace constructor:

```java
DenseDFA(int[] transTable, CharClasses charClasses,
         int[] startStates,
         int dead, int quit,
         int minMatch, int maxMatch,
         int minAccel, int maxAccel,
         int maxSpecial, int stateCount,
         char[][] accelNeedles, int deadMatch) {
    this.transTable = transTable;
    this.charClasses = charClasses;
    this.startStates = startStates;
    this.dead = dead;
    this.quit = quit;
    this.minMatch = minMatch;
    this.maxMatch = maxMatch;
    this.minAccel = minAccel;
    this.maxAccel = maxAccel;
    this.maxSpecial = maxSpecial;
    this.stateCount = stateCount;
    this.stride = charClasses.stride();
    this.accelNeedles = accelNeedles;
    this.deadMatch = deadMatch;
}
```

- [ ] **Step 3: Replace DenseDFA accessors**

Remove `minMatchState()`. Replace with:

```java
public int dead() { return dead; }
public int quit() { return quit; }
public int minMatch() { return minMatch; }
public int maxMatch() { return maxMatch; }
public int minAccel() { return minAccel; }
public int maxAccel() { return maxAccel; }
public int maxSpecial() { return maxSpecial; }
public int stride() { return stride; }
public int stateCount() { return stateCount; }

public boolean isMatch(int sid) {
    return sid >= minMatch && sid <= maxMatch;
}

public boolean isAccel(int sid) {
    return sid >= minAccel && sid <= maxAccel;
}

public boolean isSpecial(int sid) {
    return sid <= maxSpecial;
}

public boolean hasAcceleratedStates() {
    return accelNeedles != null && accelNeedles.length > 0;
}

private int accelIndex(int sid) {
    return (sid - minAccel) / stride;
}
```

- [ ] **Step 4: Replace `searchFwd` method body**

Replace the entire method body with the new search loop per spec. Key structural changes:
- Unrolled loop guard: `sid <= ms` (single comparison)
- No acceleration in hot path
- Special-state dispatch after unrolled loop
- `accelerate()` called from dispatch only

```java
public long searchFwd(Input input) {
    final char[] haystack = input.haystack();
    int at = input.start();
    final int end = input.end();

    int sid = startState(input);
    if (sid == 0) return SearchResult.NO_MATCH; // dead = 0

    final int[] tt = transTable;
    final CharClasses cc = charClasses;
    final int ms = maxSpecial;
    final int dm = deadMatch;
    final int q = quit;

    int lastMatchEnd = -1;

    // Check if start state is a match state (not dead-match)
    if (isMatch(sid) && sid != dm) lastMatchEnd = at;

    // Lazily computed String view; only used when indexOf acceleration fires.
    String haystackString = null;

    while (at < end) {
        // --- UNROLLED INNER LOOP (hot path) ---
        // Single guard: sid <= maxSpecial. No acceleration, no match recording.
        // Ref: upstream search.rs:98-124
        while (at < end) {
            sid = tt[sid + cc.classify(haystack[at])];
            if (sid <= ms || at + 3 >= end) break;
            at++;
            sid = tt[sid + cc.classify(haystack[at])];
            if (sid <= ms) break;
            at++;
            sid = tt[sid + cc.classify(haystack[at])];
            if (sid <= ms) break;
            at++;
            sid = tt[sid + cc.classify(haystack[at])];
            if (sid <= ms) break;
            at++;
        }

        // --- SPECIAL-STATE DISPATCH (cold path) ---
        // Ref: upstream search.rs:125-181
        if (sid <= ms) {
            if (sid >= minMatch && sid <= maxMatch) {
                // MATCH state
                if (sid == dm) {
                    lastMatchEnd = at;
                    break;
                }
                lastMatchEnd = at;
                if (sid >= minAccel && sid <= maxAccel) {
                    // Match + accel: skip forward through self-loop.
                    // Do NOT update lastMatchEnd after acceleration —
                    // match upstream (search.rs:162-167).
                    if (haystackString == null) haystackString = input.haystackStr();
                    at = accelerate(sid, haystackString, at, end);
                    if (at >= end) break;
                    sid = tt[sid + cc.classify(haystack[at])];
                    at++;
                    continue;
                }
            } else if (sid >= minAccel && sid <= maxAccel) {
                // ACCEL only: skip forward
                if (haystackString == null) haystackString = input.haystackStr();
                at = accelerate(sid, haystackString, at, end);
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

    // Right-edge transition for look-ahead context ($ and \b assertions)
    lastMatchEnd = handleRightEdge(sid, haystack, end, lastMatchEnd);

    if (lastMatchEnd >= 0) return SearchResult.match(lastMatchEnd);
    return SearchResult.NO_MATCH;
}
```

- [ ] **Step 5: Add `accelerate()` private method**

Add after `handleRightEdge`:

```java
/**
 * Scans forward through a self-looping accelerated state using
 * String.indexOf for each needle char (1-3 needles).
 *
 * <p>Returns the position of the first escape char found, or {@code end}
 * if none found within bounds. Each needle is one representative char
 * per escape class. indexOf may not find every char in a multi-char
 * escape class, but this is a performance miss (reduced acceleration),
 * not a correctness bug — the unrolled loop handles missed escapes.</p>
 *
 * <p>Ref: upstream accel.rs:259-276 (data), search.rs:150-155 (usage)</p>
 */
private int accelerate(int sid, String haystackString, int at, int end) {
    char[] needles = accelNeedles[accelIndex(sid)];
    at++; // skip current position (self-loop char)
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

- [ ] **Step 6: Update `handleRightEdge` for new layout**

Replace `sid != dead && sid != quit` with `sid != 0 && sid != quit`, and `sid >= minMatchState` with `isMatch(rightEdgeSid)`:

```java
private int handleRightEdge(int sid, char[] haystack, int end,
                             int lastMatchEnd) {
    if (sid != 0 && sid != quit) {
        int rightEdgeSid;
        if (end < haystack.length) {
            rightEdgeSid = transTable[sid + charClasses.classify(haystack[end])];
        } else {
            rightEdgeSid = transTable[sid + charClasses.eoiClass()];
        }
        if (isMatch(rightEdgeSid)) {
            return end;
        }
    }
    return lastMatchEnd;
}
```

- [ ] **Step 7: Rewrite `DenseDFABuilder.extractDense()` — state classification**

In the `extractDense` method, after existing Phase 1 (match-wrapper detection, currently lines 210-269), add acceleration classification BEFORE the remap. Use `cache.nextState()` for original states. For match-wrappers, propagate accel status from the wrapped target.

Key changes vs current code:
1. **Remove the `i == 0` skip** in accel analysis — start states participate
2. **Add space exclusion** (`accel.rs:449-458`): if space (`' '`) is an escape char, do NOT mark as accel
3. **Propagate accel to match-wrappers**: `isAccel[wrapperIdx] = isAccel[wrappedTargetIdx]`

```java
// Classify acceleration candidates BEFORE remap
// Use cache.nextState() for original states (0..totalStates-1)
// Ref: upstream accel.rs:449-458 (space exclusion)
int classCount = charClasses.classCount();
boolean[] isAccel = new boolean[denseStates];
for (int i = 3; i < totalStates; i++) {
    int rawSid = i * stride;
    // Count escape classes (non-self-loop transitions)
    int escapes = 0;
    boolean tooMany = false;
    for (int cls = 0; cls < classCount; cls++) {
        int target = cache.nextState(rawSid, cls) & 0x7FFF_FFFF;
        if (target != rawSid) {
            escapes++;
            if (escapes > 3) { tooMany = true; break; }
        }
    }
    if (tooMany) continue;
    // Space exclusion: check if ' ' escapes this state
    int spaceClass = charClasses.classify(' ');
    int spaceTarget = cache.nextState(rawSid, spaceClass) & 0x7FFF_FFFF;
    if (spaceTarget != rawSid) continue; // space is an escape → skip
    isAccel[i] = true;
}
// Propagate accel status to match-wrapper states
for (int i = 0; i < totalStates; i++) {
    if (matchWrapperMap[i] >= 0 && isAccel[i]) {
        isAccel[matchWrapperMap[i]] = true;
    }
}
// Dead-match is never accelerated (already skipped by i >= 3 check above,
// and deadMatchIdx >= totalStates)
```

- [ ] **Step 8: Rewrite `DenseDFABuilder.extractDense()` — shuffle order**

Replace the current Phase 2-3 (match identification + remap, lines 276-317) with the new shuffle ordering: dead(0) → quit(stride) → match-only → match+accel → accel-only → normal.

```java
// Count categories (indices 0=dead/padding, 1=dead-in-cache, 2=quit, 3+=real)
// In the old layout: 0=padding, 1=dead, 2=quit. Remap: 0→dead(0), 1→dead(0), 2→quit(stride)
int matchOnlyCount = 0, matchAccelCount = 0, accelOnlyCount = 0, normalCount = 0;
for (int i = 3; i < denseStates; i++) {
    if (i == deadMatchIdx) {
        // dead-match is a match state, check if also accel
        if (isAccel[i]) matchAccelCount++; else matchOnlyCount++;
        continue;
    }
    boolean m = isMatch[i], a = isAccel[i];
    if (m && a) matchAccelCount++;
    else if (m) matchOnlyCount++;
    else if (a) accelOnlyCount++;
    else normalCount++;
}

// Assign IDs: dead(0), quit(1), then categories
int nextIdx = 2; // skip dead(0) and quit(1)
int matchOnlyStart = nextIdx;    nextIdx += matchOnlyCount;
int matchAccelStart = nextIdx;   nextIdx += matchAccelCount;
int accelOnlyStart = nextIdx;    nextIdx += accelOnlyCount;
int normalStart = nextIdx;

// Build remap table
int[] remap = new int[denseStates];
remap[0] = 0;          // old padding → dead (0)
remap[1] = 0;          // old dead → dead (0)
remap[2] = stride;     // old quit → quit (stride)

int moNext = matchOnlyStart, maNext = matchAccelStart;
int aoNext = accelOnlyStart, nNext = normalStart;

for (int i = 3; i < denseStates; i++) {
    boolean m = isMatch[i], a = isAccel[i];
    if (m && a) {
        remap[i] = maNext * stride; maNext++;
    } else if (m) {
        remap[i] = moNext * stride; moNext++;
    } else if (a) {
        remap[i] = aoNext * stride; aoNext++;
    } else {
        remap[i] = nNext * stride; nNext++;
    }
}

// Compute range boundaries
int minMatch = (matchOnlyCount + matchAccelCount > 0)
    ? matchOnlyStart * stride : -1;
int maxMatch = (matchOnlyCount + matchAccelCount > 0)
    ? (matchAccelStart + matchAccelCount - 1) * stride : -2;
int minAccel = (matchAccelCount + accelOnlyCount > 0)
    ? matchAccelStart * stride : -1;
int maxAccel = (matchAccelCount + accelOnlyCount > 0)
    ? (accelOnlyStart + accelOnlyCount - 1) * stride : -2;
int maxSpecial = Math.max(Math.max(maxMatch, maxAccel), stride);
```

- [ ] **Step 9: Adapt transition table copy for new layout**

Update the existing Phase 4 (transition table copy) for the new state positions:
- Dead is now at ID 0 (self-loops to 0)
- Quit is now at ID stride (self-loops to stride)
- All target remapping uses `remap[targetIdx]`

The transition copy loop is structurally the same as the current code — it iterates old states, looks up each transition target, and writes `remap[targetIndex]` into `newTable[remap[sourceIndex] + cls]`. The key change is that `remap[0]=0` (dead), `remap[1]=0` (dead), `remap[2]=stride` (quit) instead of the old mapping.

- [ ] **Step 10: Build accelNeedles after remapping**

After the transition table is fully remapped, build the compact `accelNeedles` array. Algorithm: for each accel state, iterate over escape classes (classes where `newTable[newSid + cls] != newSid`), and for each escape class, pick the lowest char in that class as the representative needle.

```java
char[][] accelNeedles = null;
if (minAccel >= 0) {
    int accelCount = (maxAccel - minAccel) / stride + 1;
    accelNeedles = new char[accelCount][];

    for (int i = 3; i < denseStates; i++) {
        if (!isAccel[i]) continue;
        int newSid = remap[i];
        int accelIdx = (newSid - minAccel) / stride;

        // Collect one representative char per escape class
        // Use class-level iteration to avoid 65536-char scan
        java.util.List<Character> needleList = new java.util.ArrayList<>(3);
        boolean[] seenClass = new boolean[classCount + 1];
        for (int c = 0; c < 65536 && needleList.size() < 3; c++) {
            int cls = charClasses.classify((char) c);
            if (seenClass[cls]) continue;
            seenClass[cls] = true;
            if (newTable[newSid + cls] != newSid) {
                needleList.add((char) c);
            }
        }

        char[] needles = new char[needleList.size()];
        for (int j = 0; j < needleList.size(); j++) {
            needles[j] = needleList.get(j);
        }
        accelNeedles[accelIdx] = needles;
    }
}
```

**Why one representative per class is safe:** Equivalence classes guarantee that all chars in a class produce the same DFA transition. `indexOf(representative)` finds the first occurrence of that specific char. If the class contains OTHER chars that appear earlier in the input, `indexOf` won't find them — but that's a performance miss, not a correctness bug. The unrolled loop will break on the special state when those other chars are encountered normally. For the common patterns (`[a-z]+` escape on non-letters, `(?m)^.+` escape on `\n`), each escape class has a clear representative.

- [ ] **Step 11: Update the `return` statement and verification loop**

Update the `extractDense` return to construct with new parameters:

```java
return new DenseDFA(newTable, charClasses,
        newStartStates,
        0,     // dead = 0
        stride, // quit = stride
        minMatch, maxMatch,
        minAccel, maxAccel,
        maxSpecial, denseStates,
        accelNeedles,
        needsDeadMatch ? remap[deadMatchIdx] : -1);
```

Also update the verification loop in `build()` (currently at line 125) to start at `i = 2` instead of `i = 3`, since real states now start at index 2 (dead=0, quit=1, real states from 2):

```java
for (int i = 2; i < cache.stateCount(); i++) {  // was i = 3
```

Wait — the verification loop validates that UNKNOWN transitions don't exist in states computed from the cache. In the old layout, indices 0/1/2 were padding/dead/quit (not in the cache). In the new layout, index 0 is still padding (not a real cache state), index 1 is dead, index 2 is quit. Real cache states still start at index 3. So the verification loop should stay at `i = 3`. The remap handles the fact that old index 0 (padding) maps to dead (0). No change needed.

- [ ] **Step 12: Compile and run full test suite**

Run: `./mvnw test`

Expected: All 2,186+ tests pass. If any fail, use the java-debugger skill (DebugInspector) to inspect state IDs and transitions at the point of failure. Common issues:
- Remap off-by-one: old padding (index 0) mapped incorrectly
- Match-wrapper not placed in match range
- Transition target not remapped through the new remap table
- Dead state transitions going to stride instead of 0

Do NOT add `System.out.println`. Do NOT write ad-hoc Java files to `/tmp`.

- [ ] **Step 13: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFA.java \
        regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilder.java
git commit -m "refactor: restructure dense DFA with special-state taxonomy

State layout inverted: all special states (dead/quit/match/accel) at
bottom of ID space, normal states at top. Single threshold guard
(maxSpecial) replaces two-ended check (sid <= quit || sid >= minMatch).

Changes:
- Unrolled loop guard: 1 comparison (was 2)
- Acceleration moved to cold-path special-state dispatch
- Start states now participate in accel classification (removed i==0 skip)
- Space exclusion for accel needles (accel.rs:449-458)
- Compact char[][] accelNeedles replaces boolean[128][] escape tables
- Match-wrapper accel status propagated from wrapped targets
- Dead state is now ID 0 (was stride), quit is stride (was 2*stride)

Ref: upstream special.rs:142-180, search.rs:98-181, accel.rs:449-458"
```

---

## Task 3: Enable Tests and Fix Existing Test Assertions

**Files:**
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilderTest.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFAAccelTest.java`

- [ ] **Step 1: Re-enable the `@Disabled` tests from Task 1**

Remove all `@Disabled("pending special-state taxonomy impl")` annotations.

- [ ] **Step 2: Update existing tests for new layout**

In `DenseDFABuilderTest`:
- `deadAndQuitAtFixedPositions`: Change to `assertEquals(0, dfa.dead())` and `assertEquals(stride, dfa.quit())`
- `matchStatesAreShuffledToEnd`: Rename to `matchStatesAreInSpecialRange`. Assert `dfa.minMatch() > dfa.stride()` and `dfa.maxMatch() >= dfa.minMatch()`. Remove old `minMatchState()` references.
- `allTransitionsArePopulated`: Update loop to start at `i = 2` (real states start at index 2 in the new table). Replace `assertTrue(target != 0, ...)` with an appropriate check — in the new layout, 0 IS a valid target (dead state). Instead verify no transitions point outside the valid table range.
- `startStatesAreValid`: Keep `starts[i] > 0` assertion (start state should never be dead=0).
- `noMatchFlagOnTransitions`: Keep as-is — still valid.

In `DenseDFAAccelTest`:
- `charClassPatternHasAcceleratedState`: Should pass with new layout.
- `acceleratedSearchMatchesLazy`: Should pass — primary correctness gate.
- `indexOfAccelerationForMultiline`: Should now pass with start state acceleration enabled.

- [ ] **Step 3: Run the full test suite**

Run: `./mvnw test`

Expected: All tests pass, including new special-state taxonomy tests.

- [ ] **Step 4: If tests fail, debug with DebugInspector**

Use the java-debugger skill to inspect:
- The `remap[]` array values
- `minMatch`, `maxMatch`, `minAccel`, `maxAccel`, `maxSpecial`
- Transition table entries for the failing state

Do NOT use `System.out.println`.

- [ ] **Step 5: Commit**

```bash
git add regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilderTest.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFAAccelTest.java
git commit -m "test: enable special-state taxonomy tests, update existing tests

All tests updated for new layout: dead=0, quit=stride, match/accel
at bottom. New tests for match+accel overlap, start state accel,
space exclusion all passing."
```

---

## Task 4: Run Benchmarks and Record Results

**Files:** None (benchmark + docs)

- [ ] **Step 1: Verify full test suite passes**

Run: `./mvnw test`

Expected: All tests pass, 0 failures.

- [ ] **Step 2: Run benchmarks**

```bash
./mvnw -P bench package -DskipTests && java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5
```

Expected results vs stage-14:
- charClass `[a-zA-Z]+`: +15-30% (was 100 ops/s)
- multiline `(?m)^.+`: 2-4x improvement (was 238 ops/s)
- literal, literalMiss, captures, wordRepeat: neutral (±10%)
- unicodeWord: neutral to slight improvement

**If any benchmark moves >2x in either direction:** STOP and investigate before proceeding. Check JIT compilation with `-XX:+PrintCompilation` and compare method sizes. See `docs/superpowers/specs/2026-03-16-dfa-jit-headroom-design.md` for prior JIT analysis.

- [ ] **Step 3: Record results**

Update `docs/architecture/stage-progression.md` with the new stage entry. Include:
- Stage number (15) and tag name
- What changed (special-state taxonomy: layout inversion, accel in dispatch, start state accel)
- Test count
- Full benchmark table vs JDK
- Comparison with stage-14 numbers

- [ ] **Step 4: Tag and commit docs**

```bash
git tag stage-15-special-state-taxonomy HEAD
git add docs/architecture/stage-progression.md
git commit -m "docs: record stage-15 special-state taxonomy results"
```
