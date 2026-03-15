# DFA Hot Path Optimizations Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce per-character and per-match overhead in the lazy DFA search loop to close the 4× throughput gap vs JDK on high-match-count patterns.

**Architecture:** Three independent optimizations targeting the two biggest costs identified by profiling: (1) per-char `classify()` cost (2 array loads → 1 for ASCII via a flat `byte[128]` fast-path), (2) per-char `charsSearched` field write (move to local variable, write back once), (3) per-match object allocation (~100 bytes/match from `Captures`, `Input.withBounds`, `SearchResult` records — eliminate via mutable out-params and int return encoding in `Strategy.Core.dfaSearch`). Each optimization is independent and benchmarked separately.

**Tech Stack:** Java 21, JMH (benchmarks), JUnit 5 (tests)

**Profiling baseline:** `[a-zA-Z]+` on Sherlock (898K chars, 174K matches): 14 ms full three-phase. JDK: 3.5 ms. Gap: 4×.

---

## Chunk 1: ASCII Fast-Path for classify()

### Task 1: Add ASCII fast-path to CharClasses

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClasses.java:1-64`
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java` (constructor call sites)
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/CharClassesTest.java`

The current `classify()` does two array dereferences for every character:
```java
return rows[highIndex[c >>> 8]][c & 0xFF] & 0xFF;
```
For ASCII-dominant text (which covers most haystacks), we can use a flat `byte[128]` lookup:
```java
if (c < 128) return asciiTable[c];
return rows[highIndex[c >>> 8]][c & 0xFF] & 0xFF;
```
This trades one well-predicted branch for one saved array dereference on the hot path.

- [ ] **Step 1: Add a test for classify() ASCII vs two-level consistency**

Add to `CharClassesTest.java`. This test explicitly compares the ASCII fast-path table against the two-level table via a package-private accessor, ensuring they agree for every ASCII char:

```java
@Test
void classifyAsciiMatchesTwoLevel() {
    // Build CharClasses for a pattern that creates multiple ASCII classes
    var nfa = compileNFA("[a-zA-Z0-9]+");
    var cc = CharClassBuilder.build(nfa);

    // Compare the ASCII fast-path result against the two-level table result
    // for every ASCII char. classifyTwoLevel() bypasses the asciiTable.
    for (int c = 0; c < 128; c++) {
        int fast = cc.classify((char) c);
        int slow = cc.classifyTwoLevel((char) c);
        assertEquals(slow, fast,
                "ASCII fast-path mismatch at char " + c + " (" + (char) c + ")");
    }
}
```

Also add a package-private accessor to `CharClasses.java` that always uses the two-level path (for testing):

```java
/** Two-level classify, bypassing ASCII fast-path. Package-private for testing. */
int classifyTwoLevel(char c) {
    return rows[highIndex[c >>> 8]][c & 0xFF] & 0xFF;
}
```

Run: `./mvnw test -Dtest="lol.ohai.regex.automata.dfa.CharClassesTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (before the fast-path, both paths are the same code).

- [ ] **Step 2: Add asciiTable field and populate it in constructor**

In `CharClasses.java`, add the field and modify the constructor:

```java
private final byte[] asciiTable; // flat lookup for c < 128
```

Add after the existing constructor body (after `this.quitClass = quitClass;`):

```java
// Build ASCII fast-path table from the two-level table
this.asciiTable = new byte[128];
for (int c = 0; c < 128; c++) {
    asciiTable[c] = rows[highIndex[0]][c];
}
```

Note: all ASCII chars have `c >>> 8 == 0`, so `highIndex[0]` is the correct row. We just copy the first 128 bytes of that row.

- [ ] **Step 3: Add the fast-path branch to classify()**

Replace the `classify()` method:

```java
public int classify(char c) {
    if (c < 128) return asciiTable[c] & 0xFF;
    return rows[highIndex[c >>> 8]][c & 0xFF] & 0xFF;
}
```

- [ ] **Step 4: Run CharClasses tests**

Run: `./mvnw test -Dtest="lol.ohai.regex.automata.dfa.CharClassesTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: All PASS.

- [ ] **Step 5: Run full test suite**

Run: `./mvnw test`
Expected: All tests PASS. Zero failures.

- [ ] **Step 6: Run benchmarks**

```bash
./mvnw -P bench package -DskipTests
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5 "RawEngineBenchmark" 2>&1 | tee /tmp/bench-ascii-fastpath.txt
```

Compare against the raw engine baseline numbers. The ASCII-heavy benchmarks (charClass, alternation, literal, multiline) should improve. unicodeWord (mixed input) may show less improvement.

- [ ] **Step 7: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClasses.java
git add regex-automata/src/test/java/lol/ohai/regex/automata/dfa/CharClassesTest.java
git commit -m "$(cat <<'EOF'
perf: add ASCII fast-path to CharClasses.classify()

Adds a flat byte[128] lookup table for ASCII characters, avoiding the
two-level highIndex+rows indirection for c < 128. Reduces per-char
cost from 2 array loads to 1 for ASCII-dominant haystacks.
EOF
)"
```

---

## Chunk 2: Local charsSearched Counter

### Task 2: Move charsSearched to local variable in search loops

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java:88-175` (searchFwd)
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java:210-290` (searchRev)

Currently, every char transition in both the inner unrolled loop and outer dispatch writes `cache.charsSearched++`. This is a field write (to a `long` field on the `DFACache` object) on every character — the JIT cannot promote it to a register because the field is visible to other code paths (the `shouldGiveUp` check). By accumulating in a local and writing back once at the end, we eliminate one memory store per char from the hot loop.

- [ ] **Step 1: Modify searchFwd() to use local counter**

At the top of `searchFwd()`, after `int lastMatchEnd = -1;`, add:

```java
long charsSearched = cache.charsSearched;
```

Then replace every `cache.charsSearched++` with `charsSearched++` and every `cache.charsSearched += N` with `charsSearched += N` throughout the method body (both inner unrolled loop and outer dispatch).

**Critical: `shouldGiveUp()` reads `cache.charsSearched` during the search loop** (via `computeNextState` → `allocateOrGiveUp` → `shouldGiveUp`). If we only write back at method exit, the give-up heuristic sees a stale value (0) and never triggers on adversarial patterns. We must write back before every call to `computeNextState`.

Add `cache.charsSearched = charsSearched;` at these points:

1. **Before the UNKNOWN slow path's `computeNextState` call** (inside the outer dispatch). This is the only place during the main loop where `shouldGiveUp` can fire.
2. **Before the right-edge transition block** (which also calls `computeNextState`).
3. **Before each early `return new SearchResult.GaveUp(pos)`** (2 occurrences).

The write-back is not needed in the inner unrolled loop (it only processes cached transitions, never hits UNKNOWN) or in the fast-path/match branches of the outer dispatch.

- [ ] **Step 2: Modify searchRev() identically**

Same transformation for `searchRev()`: local `charsSearched` at the top, replace all field writes, write back before each return and before the left-edge transition.

- [ ] **Step 3: Run full test suite**

Run: `./mvnw test`
Expected: All tests PASS.

- [ ] **Step 4: Run benchmarks**

```bash
./mvnw -P bench package -DskipTests
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5 "RawEngineBenchmark" 2>&1 | tee /tmp/bench-local-chars.txt
```

- [ ] **Step 5: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java
git commit -m "$(cat <<'EOF'
perf: use local counter for charsSearched in DFA search loops

Moves the per-char charsSearched increment from a field write on
DFACache to a local variable, writing back once at method exit.
Eliminates one memory store per char from the hot loop.
EOF
)"
```

---

## Chunk 3: Per-Match Allocation Reduction in Strategy.Core

### Task 3: Eliminate SearchResult allocation in DFA search methods

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java:88-175` (searchFwd)
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java:210-290` (searchRev)
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/SearchResult.java`
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java:145-191` (dfaSearch callers)
- Modify: all other callers of searchFwd/searchRev (check with grep)
- Test: existing tests via full suite

Currently `searchFwd()` and `searchRev()` return `SearchResult` — a sealed interface with three record variants. Each call allocates one record object. For 174K matches with forward + reverse per match, that's ~350K record allocations.

Replace the return type with a primitive `long` encoding:
- **Match**: return `matchOffset` (non-negative)
- **NoMatch**: return `-1`
- **GaveUp**: return `-(offset + 2)` (always ≤ -2, distinguishes from NoMatch's -1)

Add static decode helpers to `SearchResult`:

```java
public static boolean isMatch(long result) { return result >= 0; }
public static boolean isNoMatch(long result) { return result == -1; }
public static boolean isGaveUp(long result) { return result <= -2; }
public static int matchOffset(long result) { return (int) result; }
public static int gaveUpOffset(long result) { return (int) (-(result + 2)); }
```

- [ ] **Step 1: Add encoding/decoding helpers to SearchResult**

Add to `SearchResult.java`:

```java
// Primitive-encoded search results (avoids object allocation on hot path)
long NO_MATCH = -1L;

static long match(int offset) { return offset; }
static long gaveUp(int offset) { return -(offset + 2L); }
static boolean isMatch(long result) { return result >= 0; }
static boolean isNoMatch(long result) { return result == NO_MATCH; }
static boolean isGaveUp(long result) { return result <= -2; }
static int matchOffset(long result) { return (int) result; }
static int gaveUpOffset(long result) { return (int) (-(result + 2)); }
```

- [ ] **Step 2: Add primitive-returning search methods to LazyDFA**

Add `searchFwdLong()` and `searchRevLong()` methods that return `long` instead of `SearchResult`. These are copies of the existing methods with `return new SearchResult.Match(x)` replaced by `return SearchResult.match(x)`, etc. Keep the original methods for backward compatibility (they can delegate to the long-returning versions).

For `searchFwdLong()`, the changes from `searchFwd()` are:
- Return type: `long` instead of `SearchResult`
- `return new SearchResult.NoMatch()` → `return SearchResult.NO_MATCH`
- `return new SearchResult.GaveUp(pos)` → `return SearchResult.gaveUp(pos)`
- `return new SearchResult.Match(lastMatchEnd)` → `return SearchResult.match(lastMatchEnd)`

Apply the same transformation for `searchRevLong()`.

Then update `searchFwd()` to delegate:
```java
public SearchResult searchFwd(Input input, DFACache cache) {
    long result = searchFwdLong(input, cache);
    if (SearchResult.isMatch(result)) return new SearchResult.Match(SearchResult.matchOffset(result));
    if (SearchResult.isNoMatch(result)) return new SearchResult.NoMatch();
    return new SearchResult.GaveUp(SearchResult.gaveUpOffset(result));
}
```

Same for `searchRev()`.

- [ ] **Step 3: Update Strategy.Core.dfaSearch() to use long-returning methods**

In `Strategy.java`, update `dfaSearch()` to call `searchFwdLong()` and `searchRevLong()`:

```java
private Captures dfaSearch(Input input, Cache cache) {
    long fwdResult = forwardDFA.searchFwdLong(input, cache.forwardDFACache());
    if (SearchResult.isNoMatch(fwdResult)) return null;
    if (SearchResult.isGaveUp(fwdResult)) return pikeVM.search(input, cache.pikeVMCache());

    int matchEnd = SearchResult.matchOffset(fwdResult);
    if (matchEnd == input.start()) {
        Captures caps = new Captures(1);
        caps.set(0, matchEnd);
        caps.set(1, matchEnd);
        return caps;
    }
    if (input.isAnchored()) {
        Captures caps = new Captures(1);
        caps.set(0, input.start());
        caps.set(1, matchEnd);
        return caps;
    }
    if (reverseDFA == null) {
        Input narrowed = input.withBounds(input.start(), matchEnd, false);
        return pikeVM.search(narrowed, cache.pikeVMCache());
    }
    Input revInput = input.withBounds(input.start(), matchEnd, true);
    long revResult = reverseDFA.searchRevLong(revInput, cache.reverseDFACache());
    if (SearchResult.isMatch(revResult)) {
        Captures caps = new Captures(1);
        caps.set(0, SearchResult.matchOffset(revResult));
        caps.set(1, matchEnd);
        return caps;
    }
    if (SearchResult.isGaveUp(revResult)) {
        Input narrowed = input.withBounds(input.start(), matchEnd, false);
        return pikeVM.search(narrowed, cache.pikeVMCache());
    }
    // NoMatch — should not happen, fall back
    Input narrowed = input.withBounds(input.start(), matchEnd, false);
    return pikeVM.search(narrowed, cache.pikeVMCache());
}
```

Apply the same transformation to `dfaSearchCaptures()`.

- [ ] **Step 4: Update other Strategy variants (ReverseSuffix, ReverseInner) — scoped to Core only**

For this plan, limit the `searchFwdLong`/`searchRevLong` migration to `Strategy.Core` only. `ReverseSuffix` and `ReverseInner` use `switch` pattern matching on `SearchResult`, and restructuring them to if-chains is a separate change with its own correctness risk. The `Core` path covers the `[a-zA-Z]+` charClass benchmark (the primary target). Leave `ReverseSuffix`/`ReverseInner` using the record-returning methods for now — they still benefit from the ASCII fast-path and local charsSearched optimizations. Document the remaining migration as follow-up work.

- [ ] **Step 5: Run full test suite**

Run: `./mvnw test`
Expected: All tests PASS.

- [ ] **Step 6: Run benchmarks**

```bash
./mvnw -P bench package -DskipTests
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5 "RawEngineBenchmark" 2>&1 | tee /tmp/bench-no-alloc.txt
```

- [ ] **Step 7: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/SearchResult.java
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java
git commit -m "$(cat <<'EOF'
perf: eliminate SearchResult allocation on DFA hot path

Add long-returning searchFwdLong/searchRevLong methods that encode
match/nomatch/gaveup in a primitive long. Update Strategy.Core to
use these, avoiding ~350K record allocations per search on high-
match-count patterns.
EOF
)"
```

### Task 4: Pool Captures in Strategy.Core.dfaSearch

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java:42-60` (Cache class)
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java:145-191` (dfaSearch)

`dfaSearch()` allocates `new Captures(1)` on every match (even for non-capture searches). Since the `Captures` is consumed by the caller and then discarded, we can reuse a single `Captures` instance stored in the `Strategy.Cache`.

- [ ] **Step 1: Add a reusable Captures to DFACache**

`Strategy.Cache` is a `record` (immutable), so we cannot add mutable fields to it. Instead, store the scratch `Captures` in `DFACache`, which is already a mutable class used for per-search state.

In `DFACache.java`, add a field:

```java
private Captures scratchCaptures;

/** Returns a reusable Captures(1) instance, lazily created. */
public Captures scratchCaptures() {
    if (scratchCaptures == null) {
        scratchCaptures = new Captures(1);
    }
    scratchCaptures.clear();
    return scratchCaptures;
}
```

In `Strategy.Core.dfaSearch()`, replace every `new Captures(1)` with:

```java
Captures caps = cache.forwardDFACache().scratchCaptures();
caps.set(0, matchStart);
caps.set(1, matchEnd);
return caps;
```

**Important:** This is only safe because the caller (`Searcher.find()` or `BaseFindIterator.advance()`) reads the start/end values immediately and doesn't hold a reference. Verify this by checking all callers of `strategy.search()` — they must extract `caps.start(0)` and `caps.end(0)` before the next search call.

- [ ] **Step 2: Run full test suite**

Run: `./mvnw test`
Expected: All tests PASS.

- [ ] **Step 3: Run benchmarks**

```bash
./mvnw -P bench package -DskipTests
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5 "RawEngineBenchmark" 2>&1 | tee /tmp/bench-pool-caps.txt
```

- [ ] **Step 4: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java
git commit -m "$(cat <<'EOF'
perf: pool Captures in Strategy.Cache for non-capture searches

Reuse a single Captures instance across dfaSearch() calls, avoiding
174K allocations per high-match-count search.
EOF
)"
```

---

## Chunk 4: Benchmark, Compare, and Document

### Task 5: Final benchmark comparison and documentation

**Files:**
- None (measurement only) for benchmarks
- Modify: `docs/architecture/lazy-dfa-gaps.md` (if documenting new status)

- [ ] **Step 1: Run full benchmark suite**

```bash
./mvnw -P bench package -DskipTests
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5 2>&1 | tee /tmp/bench-all-optimizations.txt
```

- [ ] **Step 2: Compare against baseline**

Compare `/tmp/bench-all-optimizations.txt` against the raw engine baseline numbers:

| Benchmark | Baseline (ops/s) | Target |
|---|---|---|
| rawCharClassOhai | 70 | > 100 |
| rawAlternationOhai | 45 | > 60 |
| rawMultilineOhai | 192 | > 250 |
| rawUnicodeWordOhai | 9,777 | > 12,000 |
| rawLiteralOhai | 4,815 | no regression |
| rawLiteralMissOhai | 4,984 | no regression |

If any existing benchmark regresses by >2×, investigate before proceeding.

- [ ] **Step 3: Run profiling test to measure improvement**

```bash
./mvnw test -Dtest="lol.ohai.regex.automata.meta.StrategyProfilingTest#charClassComponentBreakdown" -Dsurefire.failIfNoSpecifiedTests=false
```

Compare the full three-phase time against the 13,890 µs baseline.

- [ ] **Step 4: Clean up profiling test**

Delete `regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyProfilingTest.java` — it was a diagnostic tool, not a regression test. The benchmark suite covers ongoing performance tracking.

- [ ] **Step 5: Commit any remaining changes**

```bash
git add -A
git commit -m "bench: final optimization comparison and cleanup"
```
