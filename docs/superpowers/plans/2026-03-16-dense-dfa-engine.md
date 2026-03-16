# Dense DFA Engine (Spec 1) Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a pre-compiled dense DFA engine that eliminates UNKNOWN-state handling from the search hot path, integrated into Strategy.Core as the forward DFA when pattern state count is within limits.

**Architecture:** Reuse LazyDFA's determinization logic (computeNextState, epsilonClosure) to eagerly build all DFA states at compile time. Store transitions in a flat `int[]` table. Search loop has no lazy computation — just `transTable[sid + classId]`. Match states shuffled to contiguous range at end for fast `sid >= minMatch` check. Falls back to LazyDFA for patterns with look-assertions (Spec 1 limitation) or state explosion.

**Tech Stack:** Java 21, JMH benchmarks, JUnit 5

**Upstream references (read before implementing):**
- `upstream/regex/regex-automata/src/dfa/determinize/mod.rs:60-200` — worklist-driven determinization
- `upstream/regex/regex-automata/src/dfa/search.rs:45-186` — dense DFA search with unrolled loop
- `upstream/regex/regex-automata/src/dfa/special.rs:73-116` — special state layout

**IMPORTANT project rules:**
- Never use `./mvnw install` or `-pl` — always run full reactor (`./mvnw test`)
- Never use `System.out.println` for debugging — use the java-debugger skill (DebugInspector)
- Read the upstream Rust source cited above before implementing — cite file:line in commit messages
- Run `./mvnw test` after every change (full 2,186-test suite)

**KNOWN ISSUES FROM PLAN REVIEW — implementer must address:**

1. **Package visibility:** `getOrComputeStartState` and `computeAllTransitions` on LazyDFA need to be `public` (not package-private) since `DenseDFABuilder` is in `dfa.dense`, a different package from `dfa.lazy`.
2. **Index-0 padding state:** `DFACache.initSentinels()` creates 3 states at indices 0 (padding), 1 (dead at stride), 2 (quit at stride*2). The builder's `extractDense` loop must skip index 0 — it's not a real DFA state. Dead and quit must be preserved at their fixed stride-multiplied positions during shuffling.
3. **`queued` array sizing:** `new boolean[maxStates]` may be off-by-one if `cache.stateCount()` temporarily reaches `maxStates` before the overflow check. Use `maxStates + 1` or check before adding to worklist.
4. **Forward DFA cache must NOT be nulled:** `Strategy.Core.createCache()` must always create the forward DFA cache even when denseDFA exists — the fallback from denseDFA GaveUp to lazy DFA needs it. Or skip the lazy forward DFA fallback and go straight to PikeVM on GaveUp (simpler, acceptable for Spec 1).
5. **Dummy input:** Remove unused `dummyInput` variable. Use `Input.of("x")` directly (content doesn't matter for universal start states where `lookSetAny.isEmpty()`).
6. **Missing fallback test:** Add a Regex API-level test that verifies a pattern with look-assertions (`\b\w+\b`) correctly matches via lazy DFA fallback when dense DFA returns null.
7. **`dfaSearchCaptures` needs explicit code** — don't just say "same pattern" since it touches `scratchCaptures()` which depends on the DFA cache being non-null.

---

## Chunk 1: DenseDFA Builder + Compilation

### Task 1: Add `computeAllTransitions` to LazyDFA

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java` (create if needed)

- [ ] **Step 1: Write a test that exercises computeAllTransitions**

Create `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java`:

```java
package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LazyDFATest {

    @Test
    void computeAllTransitionsPopulatesAllClasses() throws Exception {
        NFA nfa = compileNfa("[a-z]+");
        CharClasses cc = CharClassBuilder.build(nfa);
        LazyDFA dfa = LazyDFA.create(nfa, cc);
        assertNotNull(dfa);
        DFACache cache = dfa.createCache();

        // Get start state
        Input input = Input.of("test");
        int sid = dfa.getOrComputeStartState(input, cache);

        int beforeCount = dfa.computeAllTransitions(cache, sid);

        // After computeAllTransitions, no transition should be UNKNOWN
        int rawSid = sid & 0x7FFF_FFFF;
        for (int cls = 0; cls <= cc.classCount(); cls++) {
            assertNotEquals(DFACache.UNKNOWN, cache.nextState(rawSid, cls),
                    "class " + cls + " should not be UNKNOWN after computeAllTransitions");
        }

        // New states should have been created
        assertTrue(cache.stateCount() > beforeCount,
                "computeAllTransitions should create new states");
    }

    private static NFA compileNfa(String pattern) throws Exception {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        return Compiler.compile(hir);
    }
}
```

Note: `getOrComputeStartState` is currently private. This test will fail to compile until we make it accessible.

- [ ] **Step 2: Make getOrComputeStartState package-private on LazyDFA**

In `LazyDFA.java`, change:
```java
private int getOrComputeStartState(Input input, DFACache cache) {
```
to:
```java
// visible for DenseDFABuilder and testing
int getOrComputeStartState(Input input, DFACache cache) {
```

- [ ] **Step 3: Implement computeAllTransitions**

Add to `LazyDFA.java`:

```java
/**
 * Eagerly computes transitions for ALL equivalence classes (including EOI)
 * for the given DFA state. Used by DenseDFABuilder to fully populate the
 * transition table.
 *
 * @return the state count before computation (compare with cache.stateCount()
 *         after to discover newly-created states)
 */
public int computeAllTransitions(DFACache cache, int sid) {
    int beforeCount = cache.stateCount();
    int rawSid = sid & 0x7FFF_FFFF;
    for (int cls = 0; cls <= charClasses.classCount(); cls++) {
        if (cache.nextState(rawSid, cls) == DFACache.UNKNOWN) {
            int nextSid = computeNextState(cache, rawSid, cls);
            cache.setTransition(rawSid, cls, nextSid);
        }
    }
    return beforeCount;
}
```

- [ ] **Step 4: Run tests**

```bash
./mvnw test
```

Expected: All tests pass including the new LazyDFATest.

- [ ] **Step 5: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java \
       regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java
git commit -m "feat: add computeAllTransitions to LazyDFA for dense DFA builder

Eagerly computes transitions for all equivalence classes (including EOI)
for a given DFA state. Returns state count before computation so callers
can discover newly-created states. Also makes getOrComputeStartState
package-private for DenseDFABuilder access.

Upstream ref: determinize/mod.rs:60-200 (worklist-driven determinization)
Spec: docs/superpowers/specs/2026-03-16-dense-dfa-engine-design.md"
```

---

### Task 2: DenseDFA class (data structure)

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFA.java`

- [ ] **Step 1: Create the DenseDFA class**

Create `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFA.java`:

```java
package lol.ohai.regex.automata.dfa.dense;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.SearchResult;
import lol.ohai.regex.automata.util.Input;

/**
 * A pre-compiled (dense) DFA with all states and transitions materialized
 * in a flat {@code int[]} table. Immutable and thread-safe.
 *
 * <p>Unlike {@link lol.ohai.regex.automata.dfa.lazy.LazyDFA}, this DFA has
 * no UNKNOWN transitions — every state/class combination is pre-computed.
 * This eliminates lazy-computation overhead from the search hot path.</p>
 *
 * <p>Match states are shuffled to a contiguous range at the end of the
 * state ID space, enabling a single comparison {@code sid >= minMatchState}
 * instead of per-transition flag checks.</p>
 *
 * <p>Ref: upstream/regex/regex-automata/src/dfa/dense.rs</p>
 */
public final class DenseDFA {

    private final int[] transTable;
    private final CharClasses charClasses;
    private final int startAnchored;
    private final int startUnanchored;
    private final int minMatchState;
    private final int dead;
    private final int quit;
    private final int stateCount;
    private final int stride;

    DenseDFA(int[] transTable, CharClasses charClasses,
             int startAnchored, int startUnanchored,
             int minMatchState, int dead, int quit,
             int stateCount) {
        this.transTable = transTable;
        this.charClasses = charClasses;
        this.startAnchored = startAnchored;
        this.startUnanchored = startUnanchored;
        this.minMatchState = minMatchState;
        this.dead = dead;
        this.quit = quit;
        this.stateCount = stateCount;
        this.stride = charClasses.stride();
    }

    /** Number of DFA states (including dead and quit sentinels). */
    public int stateCount() { return stateCount; }

    /** The dead state ID. */
    public int dead() { return dead; }

    /** The quit state ID. */
    public int quit() { return quit; }

    /** Minimum match state ID. States >= this are match states. */
    public int minMatchState() { return minMatchState; }

    // searchFwd will be added in Task 4
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./mvnw compile
```

- [ ] **Step 3: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFA.java
git commit -m "feat: add DenseDFA data structure

Flat int[] transition table with match states shuffled to contiguous
range at end. Immutable and thread-safe. Search method added in
next commit.

Upstream ref: dfa/dense.rs (flat transition table, stride-multiplied IDs)
Spec: docs/superpowers/specs/2026-03-16-dense-dfa-engine-design.md"
```

---

### Task 3: DenseDFABuilder (compilation)

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilder.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilderTest.java`

- [ ] **Step 1: Write builder tests**

Create `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilderTest.java`:

```java
package lol.ohai.regex.automata.dfa.dense;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DenseDFABuilderTest {

    @Test
    void buildSimplePattern() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa, "simple pattern should produce a dense DFA");
        assertTrue(dfa.stateCount() >= 3, "should have at least dead + quit + start states");
        assertTrue(dfa.minMatchState() > dfa.quit(),
                "match states should be after quit in ID space");
    }

    @Test
    void buildAlternation() throws Exception {
        DenseDFA dfa = buildDense("cat|dog");
        assertNotNull(dfa);
        assertTrue(dfa.stateCount() >= 4);
    }

    @Test
    void buildReturnsNullForLookAssertions() throws Exception {
        // Patterns with look-assertions are excluded in Spec 1
        DenseDFA dfa = buildDense("\\bword\\b");
        assertNull(dfa, "patterns with \\b should not produce dense DFA in Spec 1");
    }

    @Test
    void buildReturnsNullForMultilineAnchors() throws Exception {
        DenseDFA dfa = buildDense("(?m)^line$");
        assertNull(dfa, "patterns with multiline anchors should not produce dense DFA in Spec 1");
    }

    @Test
    void matchStatesAreShuffledToEnd() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        // minMatchState should be > quit (non-match states come first)
        assertTrue(dfa.minMatchState() > dfa.quit());
        // minMatchState should be < stateCount * stride (match states exist)
        // minMatchState < stateCount * stride means match states exist in the range
        int stride = dfa.dead(); // dead == stride by convention
        assertTrue(dfa.minMatchState() < dfa.stateCount() * stride,
                "at least one match state should exist");
    }

    @Test
    void deadAndQuitAtFixedPositions() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        // dead = stride, quit = stride * 2 (same as DFACache convention)
        int stride = dfa.dead(); // dead == stride
        assertEquals(stride * 2, dfa.quit());
    }

    private static DenseDFA buildDense(String pattern) throws Exception {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        NFA nfa = Compiler.compile(hir);
        CharClasses cc = CharClassBuilder.build(nfa);
        if (cc == null) return null;
        return DenseDFABuilder.build(nfa, cc);
    }
}
```

- [ ] **Step 2: Implement DenseDFABuilder**

Create `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilder.java`:

```java
package lol.ohai.regex.automata.dfa.dense;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.DFACache;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.util.Input;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * Builds a {@link DenseDFA} by eagerly computing all DFA states and transitions.
 *
 * <p>Reuses {@link LazyDFA}'s determinization logic ({@code computeNextState},
 * {@code epsilonClosure}) by creating a temporary LazyDFA and DFACache, forcing
 * all transitions to be computed, then extracting the results into the dense
 * format.</p>
 *
 * <p>Ref: upstream/regex/regex-automata/src/dfa/determinize/mod.rs:60-200</p>
 */
public final class DenseDFABuilder {

    /** Default memory limit for the dense DFA transition table (2MB). */
    private static final int DEFAULT_MAX_BYTES = 2 * 1024 * 1024;

    private DenseDFABuilder() {}

    /**
     * Attempts to build a DenseDFA from the given NFA and char classes.
     *
     * <p>Returns {@code null} if:</p>
     * <ul>
     *   <li>The pattern has look-assertions (Spec 1 limitation — no per-position start states)</li>
     *   <li>The LazyDFA cannot be created for this NFA</li>
     *   <li>The state count exceeds the memory limit</li>
     * </ul>
     */
    public static DenseDFA build(NFA nfa, CharClasses charClasses) {
        return build(nfa, charClasses, DEFAULT_MAX_BYTES);
    }

    public static DenseDFA build(NFA nfa, CharClasses charClasses, int maxBytes) {
        // Spec 1: skip patterns with look-assertions (need per-position start states)
        if (!nfa.lookSetAny().isEmpty()) {
            return null;
        }

        // Create a temporary lazy DFA for determinization
        LazyDFA lazyDFA = LazyDFA.create(nfa, charClasses);
        if (lazyDFA == null) {
            return null;
        }

        int stride = charClasses.stride();
        int maxStates = maxBytes / (stride * 4);
        if (maxStates < 4) {
            return null; // stride too large for any meaningful DFA
        }

        // Create a cache with generous capacity for the build phase
        DFACache cache = new DFACache(charClasses, maxBytes, nfa.stateCount());

        // Compute start states using a dummy input (unanchored, full range)
        // For lookSetAny.isEmpty(), the start state is universal (position-independent)
        Input dummyInput = Input.of("");
        int startUnanchored = lazyDFA.getOrComputeStartState(
                Input.of("x"), cache);  // unanchored
        int startAnchored = lazyDFA.getOrComputeStartState(
                Input.anchored("x"), cache);  // anchored

        int dead = DFACache.dead(stride);
        int quit = DFACache.quit(stride);

        // Worklist-driven eager computation of all states
        Deque<Integer> worklist = new ArrayDeque<>();
        boolean[] queued = new boolean[maxStates];

        // Seed worklist with start states
        addToWorklist(worklist, queued, startUnanchored, stride);
        addToWorklist(worklist, queued, startAnchored, stride);

        while (!worklist.isEmpty()) {
            int sid = worklist.poll();
            int rawSid = sid & 0x7FFF_FFFF;

            // Skip dead and quit — they have no meaningful transitions
            if (rawSid == dead || rawSid == quit) continue;

            // Compute all transitions for this state
            int beforeCount = lazyDFA.computeAllTransitions(cache, sid);

            // Discover newly-created states and add to worklist
            for (int i = beforeCount; i < cache.stateCount(); i++) {
                int newSid = i * stride;
                if (newSid != dead && newSid != quit) {
                    addToWorklist(worklist, queued, newSid, stride);
                    // Also check match-flagged variant
                    int matchSid = newSid | DFACache.MATCH_FLAG;
                    // The match flag is on the transition target, not the state itself
                    // We track raw state IDs in the worklist
                }
            }

            // Check state limit
            if (cache.stateCount() > maxStates) {
                return null;
            }
        }

        // Extract into dense format
        return extractDense(cache, charClasses, startAnchored, startUnanchored,
                stride, dead, quit);
    }

    private static void addToWorklist(Deque<Integer> worklist, boolean[] queued,
                                       int sid, int stride) {
        int rawSid = sid & 0x7FFF_FFFF;
        int idx = rawSid / stride;
        if (idx < queued.length && !queued[idx]) {
            queued[idx] = true;
            worklist.add(rawSid);
        }
    }

    /**
     * Extract the fully-computed cache into a dense DFA.
     * Shuffles match states to the end of the ID space.
     */
    private static DenseDFA extractDense(DFACache cache, CharClasses charClasses,
                                          int startAnchored, int startUnanchored,
                                          int stride, int dead, int quit) {
        int stateCount = cache.stateCount();
        int classCount = charClasses.classCount();

        // Identify match states (check each state's transitions for MATCH_FLAG)
        boolean[] isMatch = new boolean[stateCount];
        int matchCount = 0;
        for (int i = 0; i < stateCount; i++) {
            int sid = i * stride;
            // A state is a match state if any of its incoming transitions
            // had the MATCH_FLAG set. In our DFACache, the MATCH_FLAG is on
            // the transition target, not stored on the state itself.
            // We need to check if this state's content has isMatch().
            if (sid != dead && sid != quit) {
                var content = cache.getState(sid);
                if (content.isMatch()) {
                    isMatch[i] = true;
                    matchCount++;
                }
            }
        }

        // Build remap: non-match states first, then match states
        int[] remap = new int[stateCount];
        int nonMatchIdx = 0;
        int matchIdx = stateCount - matchCount;

        for (int i = 0; i < stateCount; i++) {
            int sid = i * stride;
            if (sid == dead) {
                // Dead stays at its fixed position
                remap[i] = dead;
            } else if (sid == quit) {
                // Quit stays at its fixed position
                remap[i] = quit;
            } else if (isMatch[i]) {
                remap[i] = matchIdx++ * stride;
            } else {
                // Skip indices that would collide with dead/quit
                while (nonMatchIdx * stride == dead || nonMatchIdx * stride == quit) {
                    nonMatchIdx++;
                }
                remap[i] = nonMatchIdx++ * stride;
            }
        }

        int minMatchState = (stateCount - matchCount) * stride;

        // Build new transition table with remapped state IDs
        int[] newTable = new int[stateCount * stride];

        for (int i = 0; i < stateCount; i++) {
            int oldSid = i * stride;
            int newSid = remap[i];

            // Copy and remap transitions for all classes including EOI
            for (int cls = 0; cls <= classCount; cls++) {
                int target = cache.nextState(oldSid, cls);

                // Strip match flag, remap, then re-apply if needed
                boolean hasMatch = (target & DFACache.MATCH_FLAG) != 0;
                int rawTarget = target & 0x7FFF_FFFF;
                int targetIdx = rawTarget / stride;

                if (targetIdx < stateCount) {
                    int remappedTarget = remap[targetIdx];
                    // In the dense DFA, we don't use MATCH_FLAG —
                    // match states are detected by sid >= minMatchState.
                    // But we need the match flag during shuffling to know
                    // which DESTINATION states are match states...
                    // Actually, match detection is by position in the range,
                    // not by flag. So we just store the remapped raw target.
                    newTable[newSid + cls] = remappedTarget;
                }
            }
        }

        // Remap start states
        int newStartAnchored = remap[(startAnchored & 0x7FFF_FFFF) / stride];
        int newStartUnanchored = remap[(startUnanchored & 0x7FFF_FFFF) / stride];

        return new DenseDFA(newTable, charClasses,
                newStartAnchored, newStartUnanchored,
                minMatchState, dead, quit, stateCount);
    }
}
```

**Important note for the implementer:** The match-state shuffling and remapping logic above is a starting point. The actual implementation must handle edge cases:
- Dead (index 0 in DFACache, or index 1 — check DFACache.initSentinels) and quit (index 2) are pre-allocated sentinels. They must keep their fixed stride-multiplied positions.
- The MATCH_FLAG (0x8000_0000) on transition targets in DFACache must be stripped when copying to the dense table. In the dense DFA, matches are detected by range check (`sid >= minMatchState`), not by flag.
- Start state IDs from the cache may have the MATCH_FLAG set — strip before remapping.

**Read upstream `determinize/mod.rs` and `special.rs` before implementing.** The shuffling is tricky. Write tests that compare dense DFA search results against lazy DFA for multiple patterns BEFORE trusting the shuffling.

- [ ] **Step 3: Run tests**

```bash
./mvnw test
```

Expected: All existing tests pass + new DenseDFABuilderTest tests pass.

- [ ] **Step 4: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilder.java \
       regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilderTest.java
git commit -m "feat: add DenseDFABuilder — worklist-driven eager DFA compilation

Builds a DenseDFA by creating a temporary LazyDFA, eagerly computing
all transitions via computeAllTransitions, then extracting into a flat
int[] table with match states shuffled to the end.

Returns null for patterns with look-assertions (Spec 1 limitation)
or state explosion beyond memory limit.

Upstream ref: determinize/mod.rs:60-200, special.rs:73-116
Spec: docs/superpowers/specs/2026-03-16-dense-dfa-engine-design.md"
```

---

## Chunk 2: Dense DFA Search + Correctness Verification

### Task 4: Forward search method

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFA.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFASearchTest.java`

- [ ] **Step 1: Write search tests comparing dense vs lazy DFA**

Create `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFASearchTest.java`:

```java
package lol.ohai.regex.automata.dfa.dense;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.DFACache;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.dfa.lazy.SearchResult;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DenseDFASearchTest {

    /** Dense and lazy DFA must produce identical match positions. */
    @Test
    void denseMatchesLazyForSimplePatterns() throws Exception {
        List<String> patterns = List.of(
                "[a-z]+", "[a-zA-Z0-9]+", "cat|dog|bird",
                "ab+c", "a(b|c)d", "[^x]+");
        List<String> inputs = List.of(
                "hello world", "abc123 def456", "I have a cat and a dog",
                "abbbbc xyz", "abd acd aed", "xxhelloxx");

        for (String pattern : patterns) {
            DenseDFA dense = buildDense(pattern);
            if (dense == null) continue; // skip patterns that can't build dense DFA

            LazyDFA lazy = buildLazy(pattern);
            DFACache cache = lazy.createCache();

            for (String input : inputs) {
                Input in = Input.of(input);
                long denseResult = dense.searchFwd(in);
                long lazyResult = lazy.searchFwdLong(in, cache);

                assertEquals(
                        SearchResult.isMatch(lazyResult),
                        SearchResult.isMatch(denseResult),
                        "match/noMatch mismatch for /" + pattern + "/ on \"" + input + "\"");

                if (SearchResult.isMatch(lazyResult)) {
                    assertEquals(
                            SearchResult.matchOffset(lazyResult),
                            SearchResult.matchOffset(denseResult),
                            "match offset mismatch for /" + pattern + "/ on \"" + input + "\"");
                }
            }
        }
    }

    @Test
    void denseSearchNoMatch() throws Exception {
        DenseDFA dfa = buildDense("[0-9]+");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.of("no digits here"));
        assertTrue(SearchResult.isNoMatch(result));
    }

    @Test
    void denseSearchMatchAtStart() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.of("hello 123"));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(5, SearchResult.matchOffset(result));
    }

    @Test
    void denseSearchMatchAtEnd() throws Exception {
        DenseDFA dfa = buildDense("[0-9]+");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.of("abc 123"));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(7, SearchResult.matchOffset(result));
    }

    @Test
    void denseSearchEmptyInput() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.of(""));
        assertTrue(SearchResult.isNoMatch(result));
    }

    @Test
    void denseSearchAnchored() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        long result = dfa.searchFwd(Input.anchored("hello 123"));
        assertTrue(SearchResult.isMatch(result));
        assertEquals(5, SearchResult.matchOffset(result));
    }

    @Test
    void denseSearchWithBounds() throws Exception {
        DenseDFA dfa = buildDense("[a-z]+");
        assertNotNull(dfa);
        // Search only in "23 he" portion of "abc 123 hello"
        long result = dfa.searchFwd(Input.of("abc 123 hello", 6, 11));
        assertTrue(SearchResult.isMatch(result));
    }

    private static DenseDFA buildDense(String pattern) throws Exception {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        NFA nfa = Compiler.compile(hir);
        CharClasses cc = CharClassBuilder.build(nfa);
        if (cc == null) return null;
        return DenseDFABuilder.build(nfa, cc);
    }

    private static LazyDFA buildLazy(String pattern) throws Exception {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        NFA nfa = Compiler.compile(hir);
        CharClasses cc = CharClassBuilder.build(nfa);
        return LazyDFA.create(nfa, cc);
    }
}
```

- [ ] **Step 2: Implement searchFwd on DenseDFA**

Add to `DenseDFA.java`:

```java
/**
 * Forward search for the end position of the leftmost-first match.
 * Returns a primitive-encoded result (use SearchResult helpers to decode).
 *
 * <p>All transitions are pre-computed — no UNKNOWN handling, no lazy
 * state computation. Match states are detected by {@code sid >= minMatchState}.</p>
 *
 * <p>Ref: upstream/regex/regex-automata/src/dfa/search.rs:45-186</p>
 */
public long searchFwd(Input input) {
    char[] haystack = input.haystack();
    int pos = input.start();
    int end = input.end();
    int sid = input.isAnchored() ? startAnchored : startUnanchored;
    if (sid == dead) return SearchResult.NO_MATCH;

    int lastMatchEnd = -1;

    while (pos < end) {
        // Unrolled inner loop — 4 transitions per iteration.
        // Break on special states: dead/quit (sid <= quit) or match (sid >= minMatchState).
        while (pos + 3 < end) {
            int s0 = transTable[sid + charClasses.classify(haystack[pos])];
            if (s0 <= quit || s0 >= minMatchState) break;
            int s1 = transTable[s0 + charClasses.classify(haystack[pos + 1])];
            if (s1 <= quit || s1 >= minMatchState) { sid = s0; pos++; break; }
            int s2 = transTable[s1 + charClasses.classify(haystack[pos + 2])];
            if (s2 <= quit || s2 >= minMatchState) { sid = s1; pos += 2; break; }
            int s3 = transTable[s2 + charClasses.classify(haystack[pos + 3])];
            if (s3 <= quit || s3 >= minMatchState) { sid = s2; pos += 3; break; }
            sid = s3;
            pos += 4;
        }
        if (pos >= end) break;

        // Check if current sid is a match state (from unrolled loop break-out)
        if (sid >= minMatchState) {
            lastMatchEnd = pos;
        }

        // Take one transition
        sid = transTable[sid + charClasses.classify(haystack[pos])];
        pos++;

        // Check result
        if (sid >= minMatchState) {
            lastMatchEnd = pos;
        } else if (sid == dead) {
            break;
        } else if (sid == quit) {
            return SearchResult.gaveUp(pos - 1);
        }
    }

    // Check if final sid is a match state (for matches at end of input)
    if (sid >= minMatchState && pos == end) {
        lastMatchEnd = pos;
    }

    // Right-edge transition
    lastMatchEnd = handleRightEdge(sid, haystack, end, lastMatchEnd);
    if (lastMatchEnd == -2) return SearchResult.gaveUp(end);

    if (lastMatchEnd >= 0) return SearchResult.match(lastMatchEnd);
    return SearchResult.NO_MATCH;
}

/**
 * Right-edge transition: transition on the character past the search span
 * (or EOI class) for correct look-ahead context ($ and \b assertions).
 */
private int handleRightEdge(int sid, char[] haystack, int end, int lastMatchEnd) {
    int rawSid = sid >= minMatchState ? sid : sid; // no match-flag stripping needed
    if (rawSid == dead || rawSid == quit) {
        return lastMatchEnd;
    }

    int rightEdgeSid;
    if (end < haystack.length) {
        int classId = charClasses.classify(haystack[end]);
        rightEdgeSid = transTable[rawSid + classId];
    } else {
        rightEdgeSid = transTable[rawSid + charClasses.eoiClass()];
    }

    // Check for quit transition
    if (rightEdgeSid == quit) {
        return -2; // sentinel: gaveUp at end
    }

    if (rightEdgeSid >= minMatchState) {
        return end;
    }
    return lastMatchEnd;
}
```

**CRITICAL NOTE FOR IMPLEMENTER:** The match-detection logic above is a starting point based on the spec's description of delayed-match semantics. The exact offsets may be wrong. **You MUST:**
1. Read upstream `dfa/search.rs:125-181` to understand the match-delay semantics
2. Run the `denseMatchesLazyForSimplePatterns` test and compare results
3. Use the java-debugger skill to inspect match positions at breakpoints if results diverge
4. Iterate on the match recording logic until ALL tests pass

Do NOT guess. Do NOT assume the pseudocode above is correct. **Test, debug, fix.**

- [ ] **Step 3: Run tests — expect some may need match-logic adjustments**

```bash
./mvnw test
```

If `denseMatchesLazyForSimplePatterns` fails, use the java-debugger skill to compare dense vs lazy DFA at the point of divergence. Fix the match recording logic.

- [ ] **Step 4: Commit once all tests pass**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFA.java \
       regex-automata/src/test/java/lol/ohai/regex/automata/dfa/dense/DenseDFASearchTest.java
git commit -m "feat: add DenseDFA.searchFwd — forward search with no UNKNOWN handling

4x unrolled inner loop with flat transTable lookup. Match states
detected by sid >= minMatchState range check. Right-edge transition
for look-ahead context.

Verified against LazyDFA for multiple pattern/input combinations.

Upstream ref: dfa/search.rs:45-186
Spec: docs/superpowers/specs/2026-03-16-dense-dfa-engine-design.md"
```

---

## Chunk 3: Strategy Integration + Benchmarks

### Task 5: Wire DenseDFA into Strategy.Core

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java`
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`

- [ ] **Step 1: Add DenseDFA field to Strategy.Core record**

In `Strategy.java`, update the `Core` record to include a `DenseDFA` parameter:

```java
import lol.ohai.regex.automata.dfa.dense.DenseDFA;

record Core(PikeVM pikeVM, LazyDFA forwardDFA, LazyDFA reverseDFA,
            Prefilter prefilter, BoundedBacktracker backtracker,
            OnePassDFA onePassDFA, DenseDFA denseDFA) implements Strategy {
```

- [ ] **Step 2: Update createCache — no DFACache needed for forward when dense DFA exists**

In `Core.createCache()`, the forward DFA cache can be null when denseDFA is used:

```java
@Override
public Cache createCache() {
    return new Cache(
            pikeVM.createCache(),
            denseDFA == null && forwardDFA != null ? forwardDFA.createCache() : null,
            reverseDFA != null ? reverseDFA.createCache() : null,
            backtracker != null ? backtracker.createCache() : null,
            null,
            onePassDFA != null ? onePassDFA.createCache() : null
    );
}
```

- [ ] **Step 3: Update isMatch to prefer dense DFA**

In `Core.isMatch()`, add dense DFA preference:

```java
@Override
public boolean isMatch(Input input, Cache cache) {
    if (prefilter != null && !input.isAnchored()) {
        return isMatchPrefilter(input, cache);
    }
    if (denseDFA != null) {
        long result = denseDFA.searchFwd(input);
        if (SearchResult.isMatch(result)) return true;
        if (SearchResult.isNoMatch(result)) return false;
        // GaveUp — fall through to lazy DFA or PikeVM
    }
    if (forwardDFA != null) {
        long result = forwardDFA.searchFwdLong(input, cache.forwardDFACache());
        if (SearchResult.isMatch(result)) return true;
        if (SearchResult.isNoMatch(result)) return false;
        return pikeVM.isMatch(input, cache.pikeVMCache());
    }
    return pikeVM.isMatch(input, cache.pikeVMCache());
}
```

- [ ] **Step 4: Update dfaSearch to prefer dense DFA**

```java
private Captures dfaSearch(Input input, Cache cache) {
    long fwdResult;
    if (denseDFA != null) {
        fwdResult = denseDFA.searchFwd(input);
        if (SearchResult.isNoMatch(fwdResult)) return null;
        if (SearchResult.isGaveUp(fwdResult)) {
            // Dense DFA gave up (quit chars) — fall through to lazy or PikeVM
            if (forwardDFA != null) {
                fwdResult = forwardDFA.searchFwdLong(input, cache.forwardDFACache());
            } else {
                return pikeVM.search(input, cache.pikeVMCache());
            }
        }
    } else {
        fwdResult = forwardDFA.searchFwdLong(input, cache.forwardDFACache());
    }
    if (SearchResult.isNoMatch(fwdResult)) return null;
    if (SearchResult.isGaveUp(fwdResult)) return pikeVM.search(input, cache.pikeVMCache());

    // ... rest of three-phase (reverse DFA + captures) unchanged
```

- [ ] **Step 5: Update dfaSearchCaptures similarly**

Same pattern: prefer denseDFA.searchFwd for the forward phase, fall through to lazy or PikeVM on GaveUp.

- [ ] **Step 6: Update isMatchPrefilter similarly**

Replace the `forwardDFA.searchFwdLong` call inside `isMatchPrefilter` with dense DFA preference.

- [ ] **Step 7: Update all ReverseSuffix/ReverseInner callers — they should NOT use dense DFA**

Verify that `ReverseSuffix` and `ReverseInner` strategies do NOT reference `denseDFA`. They use their own forward/reverse lazy DFAs. The dense DFA is Core-strategy only.

- [ ] **Step 8: Run full test suite**

```bash
./mvnw test
```

Expected: All 2,186+ tests pass. The upstream TOML suite exercises the dense DFA automatically for patterns without look-assertions.

- [ ] **Step 9: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java
git commit -m "feat: integrate DenseDFA into Strategy.Core

Prefer DenseDFA for forward search when available. Falls back to
LazyDFA or PikeVM on GaveUp (quit chars). Updated: isMatch, dfaSearch,
dfaSearchCaptures, isMatchPrefilter.

Spec: docs/superpowers/specs/2026-03-16-dense-dfa-engine-design.md"
```

---

### Task 6: Wire DenseDFA construction in Regex.create

**Files:**
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`

- [ ] **Step 1: Build DenseDFA in Regex.create**

In `Regex.create()`, after the NFA and CharClasses are built but before strategy selection, attempt to build a DenseDFA:

```java
import lol.ohai.regex.automata.dfa.dense.DenseDFA;
import lol.ohai.regex.automata.dfa.dense.DenseDFABuilder;

// After: CharClasses charClasses = CharClassBuilder.build(nfa, quitNonAscii);
// Before: PikeVM pikeVM = new PikeVM(nfa);

DenseDFA denseDFA = charClasses != null
        ? DenseDFABuilder.build(nfa, charClasses) : null;
```

Then pass it to `Strategy.Core`:

```java
strategy = new Strategy.Core(pikeVM, forwardDFA, reverseDFA,
        prefilter, backtracker, onePassDFA, denseDFA);
```

Also update any other `Strategy.Core` constructor calls (ReverseSuffix/ReverseInner don't use Core, so just the one in the main compilation path).

- [ ] **Step 2: Run full test suite**

```bash
./mvnw test
```

Expected: All tests pass. This is the critical integration test — the upstream TOML suite now exercises dense DFA for all applicable patterns.

- [ ] **Step 3: Commit**

```bash
git add regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "feat: build DenseDFA at compile time, pass to Strategy.Core

Attempts DenseDFABuilder.build() for every compiled regex. Returns null
for patterns with look-assertions or state explosion. Core strategy
prefers dense DFA for forward search when available.

Spec: docs/superpowers/specs/2026-03-16-dense-dfa-engine-design.md"
```

---

### Task 7: Benchmarks

**Files:**
- None modified

- [ ] **Step 1: Build benchmark jar**

```bash
./mvnw -P bench package -DskipTests
```

- [ ] **Step 2: Run search benchmarks**

```bash
java -jar regex-bench/target/benchmarks.jar \
  "SearchBenchmark|RawEngineBenchmark" -f 1 -wi 3 -i 5 \
  | tee /tmp/dense-dfa-post.txt
```

- [ ] **Step 3: Run compile benchmarks**

```bash
java -jar regex-bench/target/benchmarks.jar CompileBenchmark -f 1 -wi 3 -i 5 \
  | tee /tmp/dense-dfa-compile.txt
```

- [ ] **Step 4: Compare and record results**

Compare against stage-11 baseline numbers. Expected:
- charClass and alternation: improvement (no UNKNOWN checks in forward DFA)
- multiline and unicodeWord: unchanged (have look-assertions, still use lazy DFA)
- Compile benchmarks: slight regression (dense DFA build cost) — document, don't treat as failure

- [ ] **Step 5: JIT validation**

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation \
  -jar regex-bench/target/benchmarks.jar "RawEngineBenchmark.rawCharClassOhai" \
  -f 1 -wi 2 -i 1 -t 1 2>&1 | grep "DenseDFA::searchFwd"
```

Verify: DenseDFA.searchFwd is compiled at tier 4 (C2), bytecode size is ~200-250 bytes. All `classify` calls should be inlined.

- [ ] **Step 6: Commit results to docs**

Update stage-progression.md with the new numbers if they're significant enough to warrant a stage tag.
