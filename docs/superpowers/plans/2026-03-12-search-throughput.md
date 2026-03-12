# Search Throughput Improvement Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the search throughput gap with JDK by adding quit chars, three-phase search, and a bounded backtracker engine.

**Architecture:** Three independent features compose into the meta engine's search pipeline. Quit chars let the DFA handle Unicode word patterns (quitting on non-ASCII). Three-phase search (forward DFA → reverse DFA) eliminates PikeVM for non-capture searches. The bounded backtracker provides fast capture extraction on narrow match windows after three-phase narrows the search.

**Tech Stack:** Java 21, JUnit 5, JMH benchmarks. Build with `./mvnw test`. Upstream Rust reference in `upstream/regex/`.

**Spec:** `docs/superpowers/specs/2026-03-12-search-throughput-design.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/backtrack/BoundedBacktracker.java` | Bounded backtracker engine: search, searchCaptures, backtrack loop, step function, visited bitset |
| `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/backtrack/BoundedBacktrackerTest.java` | Unit tests for bounded backtracker |
| `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/backtrack/BoundedBacktrackerSuiteTest.java` | Upstream TOML suite run through backtracker |

### Modified Files
| File | Change |
|------|--------|
| `regex-automata/.../dfa/lazy/LookSet.java` | Split `containsUnsupportedByDFA()` into `containsUnicodeWord()` and `containsCrlf()` |
| `regex-automata/.../dfa/CharClassBuilder.java` | Add quit char boundary at char 128 to separate ASCII from non-ASCII classes |
| `regex-automata/.../dfa/CharClasses.java` | Add `boolean[] quitClass` array and `isQuitClass(int)` method |
| `regex-automata/.../dfa/lazy/LazyDFA.java` | Accept Unicode word patterns with quit chars; overwrite quit transitions in `computeNextState` |
| `regex-automata/.../dfa/lazy/DFACache.java` | No changes needed — `quit(stride)` sentinel already exists |
| `regex-automata/.../meta/Strategy.java` | Three-phase search in `search()`/`searchCaptures()`, backtracker integration, nofail fallback |
| `regex/.../Regex.java` | Build backtracker in `create()`, pass to `Strategy.Core` |
| `regex-automata/.../dfa/lazy/LazyDFATest.java` | Add quit chars tests |
| `regex-automata/.../meta/StrategyTest.java` or `StrategyLazyDFATest.java` | Add three-phase search tests |
| `docs/architecture/lazy-dfa-gaps.md` | Update gap statuses |
| `BENCHMARKS.md` | Update results after implementation |

---

## Chunk 1: Quit Chars

### Task 1: Add `containsUnicodeWord()` to LookSet

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LookSet.java:70-79`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LookSetTest.java`

- [ ] **Step 1: Write failing test for `containsUnicodeWord()`**

```java
@Test
void containsUnicodeWord() {
    LookSet set = LookSet.of(LookKind.WORD_BOUNDARY_UNICODE);
    assertTrue(set.containsUnicodeWord());
    assertFalse(set.containsCrlf());

    LookSet ascii = LookSet.of(LookKind.WORD_BOUNDARY_ASCII);
    assertFalse(ascii.containsUnicodeWord());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl regex-automata -Dtest="LookSetTest#containsUnicodeWord"`
Expected: FAIL — `containsUnicodeWord()` method does not exist

- [ ] **Step 3: Add `containsUnicodeWord()` method to LookSet**

In `LookSet.java`, add after `containsCrlf()`:

```java
/** Returns true if this set contains any Unicode (non-ASCII) word boundary assertion. */
public boolean containsUnicodeWord() {
    return contains(LookKind.WORD_BOUNDARY_UNICODE)
            || contains(LookKind.WORD_BOUNDARY_UNICODE_NEGATE)
            || contains(LookKind.WORD_START_UNICODE)
            || contains(LookKind.WORD_END_UNICODE)
            || contains(LookKind.WORD_START_HALF_UNICODE)
            || contains(LookKind.WORD_END_HALF_UNICODE);
}
```

Also update `containsUnsupportedByDFA()` to use the new methods:

```java
public boolean containsUnsupportedByDFA() {
    return containsUnicodeWord() || containsCrlf();
}
```

Wait — after quit chars, Unicode word boundaries will no longer be "unsupported by DFA." We need a new method that only returns true for CRLF (which still bail). Rename:

```java
/**
 * Returns true if this set contains look-assertion kinds that require
 * the DFA to bail out entirely. After quit-char support, only CRLF
 * line anchors cause full bail-out. Unicode word boundaries are handled
 * via quit chars instead.
 */
public boolean containsBailOutByDFA() {
    return containsCrlf();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl regex-automata -Dtest="LookSetTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LookSet.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LookSetTest.java
git commit -m "feat: add containsUnicodeWord() and containsBailOutByDFA() to LookSet"
```

---

### Task 2: Add quit class support to CharClasses and CharClassBuilder

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClasses.java`
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/CharClassesTest.java`

- [ ] **Step 1: Write failing test for quit class detection**

```java
@Test
void quitClassForNonAscii() {
    // Build char classes with quit chars enabled (Unicode word boundary pattern)
    Ast ast = Parser.parse("\\b", 250);
    Hir hir = Translator.translate("\\b", ast);
    NFA nfa = Compiler.compile(hir);
    CharClasses cc = CharClassBuilder.build(nfa, true); // true = quitNonAscii

    // ASCII chars should NOT be quit classes
    assertFalse(cc.isQuitClass(cc.classify('a')));
    assertFalse(cc.isQuitClass(cc.classify('Z')));
    assertFalse(cc.isQuitClass(cc.classify(' ')));

    // Non-ASCII chars SHOULD be quit classes
    assertTrue(cc.isQuitClass(cc.classify('\u00E9'))); // é
    assertTrue(cc.isQuitClass(cc.classify('\u4E2D'))); // 中
    assertTrue(cc.isQuitClass(cc.classify('\u0080'))); // first non-ASCII
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl regex-automata -Dtest="CharClassesTest#quitClassForNonAscii"`
Expected: FAIL — `build(NFA, boolean)` overload does not exist

- [ ] **Step 3: Implement quit class support**

In `CharClassBuilder.java`, add a `quitNonAscii` parameter to `build()`:

```java
public static CharClasses build(NFA nfa) {
    return build(nfa, false);
}

public static CharClasses build(NFA nfa, boolean quitNonAscii) {
    TreeSet<Integer> boundaries = new TreeSet<>();
    boundaries.add(0);
    boundaries.add(0x10000);

    // If quit chars are configured, force a boundary at char 128
    // to separate ASCII from non-ASCII equivalence classes
    if (quitNonAscii) {
        boundaries.add(128);
    }

    // ... rest of existing boundary computation ...
```

In `CharClasses.java`, add the `quitClass` array:

```java
private final boolean[] quitClass;  // indexed by class ID, true if class is quit

CharClasses(byte[][] rows, int[] highIndex, int classCount,
            boolean[] wordClass, boolean[] lineLF, boolean[] lineCR,
            boolean[] quitClass) {
    // ... existing fields ...
    this.quitClass = quitClass;
}

public boolean isQuitClass(int classId) {
    return quitClass != null && classId < quitClass.length && quitClass[classId];
}
```

In `CharClassBuilder.build()`, compute the `quitClass` array:

```java
boolean[] quitClassArr = null;
if (quitNonAscii) {
    quitClassArr = new boolean[classCount];
    for (int cls = 0; cls < classCount && cls < sortedBounds.length - 1; cls++) {
        int representative = sortedBounds[cls];
        if (representative >= 128 && representative < 65536) {
            quitClassArr[cls] = true;
        }
    }
}

return new CharClasses(uniqueRows.toArray(byte[][]::new), highIndex, classCount,
        wordClass, lineLF, lineCR, quitClassArr);
```

Update existing `CharClasses` constructor calls to pass `null` for the new parameter where quit is not needed (the `identity()` factory and any tests).

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl regex-automata -Dtest="CharClassesTest"`
Expected: PASS (including existing tests)

- [ ] **Step 5: Commit**

```
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClasses.java \
        regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/dfa/CharClassesTest.java
git commit -m "feat: add quit class support to CharClasses for non-ASCII chars"
```

---

### Task 3: Wire quit transitions into LazyDFA

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java:57-62,296+`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java`

- [ ] **Step 1: Write failing test — DFA handles Unicode word pattern with quit**

```java
@Test
void unicodeWordBoundaryUsesQuit() {
    // Previously this returned null (DFA bail). Now it should build and search.
    var dfa = buildDFAWithQuit("\\b\\w+\\b");
    assertNotNull(dfa, "DFA should be created with quit chars for Unicode word boundary");

    // ASCII-only input: DFA handles it entirely
    var result = dfa.searchFwd(Input.of("hello world"), dfa.createCache());
    assertInstanceOf(SearchResult.Match.class, result);

    // Input with non-ASCII: DFA should give up at the non-ASCII char
    var result2 = dfa.searchFwd(Input.of("café"), dfa.createCache());
    // DFA may match "caf" then give up at 'é', or give up earlier
    assertTrue(result2 instanceof SearchResult.Match || result2 instanceof SearchResult.GaveUp);
}
```

Add a helper `buildDFAWithQuit(String pattern)` to LazyDFATest that builds a DFA with quit chars when Unicode word boundaries are present (separate from the existing `buildDFA` helper which does not use quit chars):

```java
private static LazyDFA buildDFAWithQuit(String pattern) {
    try {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        NFA nfa = Compiler.compile(hir);
        boolean quitNonAscii = nfa.lookSetAny().containsUnicodeWord();
        CharClasses cc = CharClassBuilder.build(nfa, quitNonAscii);
        return LazyDFA.create(nfa, cc);
    } catch (Exception e) {
        throw new RuntimeException("Failed to compile: " + pattern, e);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl regex-automata -Dtest="LazyDFATest#unicodeWordBoundaryUsesQuit"`
Expected: FAIL — `LazyDFA.create()` returns null for Unicode word boundary patterns

- [ ] **Step 3: Update `LazyDFA.create()` to accept Unicode word patterns**

In `LazyDFA.java`, change `create()`:

```java
public static LazyDFA create(NFA nfa, CharClasses charClasses) {
    // CRLF line anchors still bail entirely
    if (nfa.lookSetAny().containsCrlf()) {
        return null;
    }
    return new LazyDFA(nfa, charClasses);
}
```

Note: `containsUnsupportedByDFA()` is replaced with `containsBailOutByDFA()` from Task 1. The Unicode word boundary check is removed — those patterns are now accepted.

- [ ] **Step 4: Update existing `bailsOutForUnicodeWordBoundary` test**

The existing test in `LazyDFATest.java` (line 146) asserts that `buildDFA("\\bfoo\\b")` returns null. After this change, Unicode word boundary patterns are accepted (with quit chars). Update the test:

```java
@Test
void unicodeWordBoundaryNoLongerBailsOut() {
    // Unicode word boundaries now use quit chars instead of bailing
    for (String pattern : List.of("\\bfoo\\b", "\\Binner\\B")) {
        assertNotNull(buildDFAWithQuit(pattern),
                "DFA should be created with quit chars for: " + pattern);
    }
}
```

Remove the old `bailsOutForUnicodeWordBoundary` test (it tested the pre-quit-chars behavior).

Note: The existing `buildDFA` helper does NOT pass `quitNonAscii=true`, so code that calls `buildDFA("\\bfoo\\b")` will still get null (the old behavior). Only `buildDFAWithQuit` enables quit chars. This is intentional for testing the boundary.

- [ ] **Step 5: Update `computeNextState()` to overwrite quit transitions**

In `LazyDFA.computeNextState()`, after allocating a new state (line 434, after `allocateOrGiveUp` call), overwrite transitions for quit char classes:

```java
int sid = allocateOrGiveUp(cache, content);
// Overwrite transitions for quit char classes
if (charClasses.hasQuitClasses()) {
    int rawSid = sid & 0x7FFF_FFFF;
    int quitSid = DFACache.quit(charClasses.stride());
    for (int cls = 0; cls < charClasses.classCount(); cls++) {
        if (charClasses.isQuitClass(cls)) {
            cache.setTransition(rawSid, cls, quitSid);
        }
    }
}
if (isMatch) {
    sid = sid | DFACache.MATCH_FLAG;
}
return sid;
```

Note: The existing code at line 435-438 (`if (isMatch) { sid = sid | MATCH_FLAG; } return sid;`) must be replaced — the quit overwrite must happen BEFORE the match flag is applied.

- [ ] **Step 6: Update `computeStartState()` to overwrite quit transitions**

Same treatment in `computeStartState()` (line 271), after `cache.allocateState(content)`:

```java
int sid = cache.allocateState(content);
// Overwrite transitions for quit char classes
if (charClasses.hasQuitClasses()) {
    int rawSid = sid & 0x7FFF_FFFF;
    int quitSid = DFACache.quit(charClasses.stride());
    for (int cls = 0; cls < charClasses.classCount(); cls++) {
        if (charClasses.isQuitClass(cls)) {
            cache.setTransition(rawSid, cls, quitSid);
        }
    }
}
// Strip the match flag — start states must not be match states (delay by 1).
return sid & 0x7FFF_FFFF;
```

Add `hasQuitClasses()` to `CharClasses`:

```java
public boolean hasQuitClasses() {
    return quitClass != null;
}
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./mvnw test -pl regex-automata -Dtest="LazyDFATest"`
Expected: PASS (all existing + new tests)

- [ ] **Step 8: Commit**

```
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java \
        regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClasses.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java
git commit -m "feat: wire quit char transitions into LazyDFA for Unicode word boundaries"
```

---

### Task 4: Update `Regex.create()` to pass quit flag to CharClassBuilder

**Files:**
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java:91-101`
- Test: existing `regex/src/test/` integration tests (run full suite)

- [ ] **Step 1: Update `Regex.create()` compilation pipeline**

In `Regex.create()`, change the DFA creation block:

```java
NFA nfa = Compiler.compile(hir);
boolean quitNonAscii = nfa.lookSetAny().containsUnicodeWord();
CharClasses charClasses = CharClassBuilder.build(nfa, quitNonAscii);
PikeVM pikeVM = new PikeVM(nfa);
LazyDFA forwardDFA = LazyDFA.create(nfa, charClasses);

LazyDFA reverseDFA = null;
if (forwardDFA != null) {
    NFA reverseNfa = Compiler.compileReverse(hir);
    CharClasses reverseCharClasses = CharClassBuilder.build(reverseNfa, quitNonAscii);
    reverseDFA = LazyDFA.create(reverseNfa, reverseCharClasses);
}
```

- [ ] **Step 2: Run full test suite to verify no regressions**

Run: `./mvnw test`
Expected: All existing tests pass. Unicode word patterns now use DFA+fallback instead of PikeVM-only.

- [ ] **Step 3: Commit**

```
git add regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "feat: enable DFA with quit chars for Unicode word boundary patterns"
```

---

### Task 5: Run search benchmarks for quit chars

- [ ] **Step 1: Build and run benchmarks**

```bash
./mvnw -P bench package -DskipTests
java -jar regex-bench/target/benchmarks.jar "SearchBenchmark.unicodeWord" -f 1 -wi 2 -i 3
```

- [ ] **Step 2: Record results in BENCHMARKS.md**

Expected: unicodeWord throughput should improve significantly (DFA handles ASCII portion, PikeVM only on non-ASCII segments).

- [ ] **Step 3: Commit benchmark results**

```
git add BENCHMARKS.md
git commit -m "docs: update benchmarks after quit chars implementation"
```

---

## Chunk 2: Three-Phase Search

### Task 6: Wire reverse DFA into `Strategy.Core.search()` (non-capture)

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java:99-145`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyLazyDFATest.java` or new test file

- [ ] **Step 1: Write failing test — three-phase search returns correct bounds**

Add to `StrategyLazyDFATest.java` (or create if not suitable):

```java
@Test
void threePhaseSearchReturnsCorrectBounds() {
    // Pattern with no captures, no lazy quantifiers
    Regex regex = Regex.compile("[a-z]+");
    // findAll should work via three-phase (forward DFA → reverse DFA, no PikeVM)
    List<Match> matches = regex.findAll("hello world").toList();
    assertEquals(2, matches.size());
    assertEquals(0, matches.get(0).start());
    assertEquals(5, matches.get(0).end());
    assertEquals(6, matches.get(1).start());
    assertEquals(11, matches.get(1).end());
}

@Test
void threePhaseSearchEmptyMatch() {
    // Empty match pattern
    Regex regex = Regex.compile("a*");
    Optional<Match> match = regex.find("bbb");
    assertTrue(match.isPresent());
    assertEquals(0, match.get().start());
    assertEquals(0, match.get().end());  // empty match at position 0
}

@Test
void threePhaseSearchAnchored() {
    Regex regex = Regex.compile("^[a-z]+");
    Optional<Match> match = regex.find("hello world");
    assertTrue(match.isPresent());
    assertEquals(0, match.get().start());
    assertEquals(5, match.get().end());
}
```

- [ ] **Step 2: Run test to verify current behavior (may already pass or fail)**

Run: `./mvnw test -pl regex-automata -Dtest="StrategyLazyDFATest#threePhaseSearchReturnsCorrectBounds"`
Note: These tests may already pass via two-phase. The purpose is to have regression coverage.

- [ ] **Step 3: Rewrite `dfaTwoPhaseSearch()` to use three-phase when reverse DFA available**

Replace `dfaTwoPhaseSearch` and `dfaTwoPhaseSearchCaptures` with:

```java
private Captures dfaSearch(Input input, Cache cache) {
    SearchResult fwdResult = forwardDFA.searchFwd(input, cache.forwardDFACache());
    return switch (fwdResult) {
        case SearchResult.NoMatch n -> null;
        case SearchResult.GaveUp g -> pikeVM.search(input, cache.pikeVMCache());
        case SearchResult.Match m -> dfaSearchReverse(input, cache, m.offset());
    };
}

/**
 * After forward DFA finds matchEnd, use reverse DFA to find matchStart.
 * Falls back to PikeVM if reverse DFA is unavailable or gives up.
 */
private Captures dfaSearchReverse(Input input, Cache cache, int matchEnd) {
    // Empty match: start == end, no reverse search needed
    if (matchEnd == input.start()) {
        Captures caps = new Captures(1);
        caps.set(0, matchEnd);
        caps.set(1, matchEnd);
        return caps;
    }

    // Anchored search: start is fixed at input.start()
    if (input.isAnchored()) {
        Captures caps = new Captures(1);
        caps.set(0, input.start());
        caps.set(1, matchEnd);
        return caps;
    }

    // No reverse DFA: fall back to PikeVM on narrowed window
    if (reverseDFA == null) {
        Input narrowed = input.withBounds(input.start(), matchEnd, false);
        return pikeVM.search(narrowed, cache.pikeVMCache());
    }

    // Three-phase: reverse DFA finds start
    Input revInput = input.withBounds(input.start(), matchEnd, true);
    SearchResult revResult = reverseDFA.searchRev(revInput, cache.reverseDFACache());
    return switch (revResult) {
        case SearchResult.Match rm -> {
            Captures caps = new Captures(1);
            caps.set(0, rm.offset());
            caps.set(1, matchEnd);
            yield caps;
        }
        case SearchResult.GaveUp g -> {
            // Reverse gave up: fall back to PikeVM on [start, matchEnd]
            Input narrowed = input.withBounds(input.start(), matchEnd, false);
            yield pikeVM.search(narrowed, cache.pikeVMCache());
        }
        case SearchResult.NoMatch n ->
            throw new IllegalStateException("reverse DFA found no match after forward match");
    };
}
```

Update `search()` to call `dfaSearch()` (rename from `dfaTwoPhaseSearch`):

```java
@Override
public Captures search(Input input, Cache cache) {
    if (prefilter != null && !input.isAnchored()) {
        if (forwardDFA != null) {
            return prefilterLoop(input, cache, (in, c) -> dfaSearch(in, c));
        }
        return prefilterLoop(input, cache,
                (in, c) -> pikeVM.search(in, c.pikeVMCache()));
    }
    if (forwardDFA != null) {
        return dfaSearch(input, cache);
    }
    return pikeVM.search(input, cache.pikeVMCache());
}
```

**Verification note:** To confirm three-phase search is actually being exercised (not just falling through to PikeVM), add a temporary `System.err.println` or breakpoint in `dfaSearchReverse()` during development. The integration tests from `regex/src/test/` exercise the full pipeline and will exercise three-phase for any pattern that produces both a forward and reverse DFA.

- [ ] **Step 4: Run all tests**

Run: `./mvnw test`
Expected: All tests pass

- [ ] **Step 5: Commit**

```
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyLazyDFATest.java
git commit -m "feat: implement three-phase search in Strategy.Core.search()"
```

---

### Task 7: Wire three-phase into `Strategy.Core.searchCaptures()`

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java:113-157`

- [ ] **Step 1: Write failing test for three-phase with captures**

```java
@Test
void threePhaseSearchCapturesNarrowsWindow() {
    Regex regex = Regex.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    var result = regex.captures("filed on 2024-03-14 done");
    assertTrue(result.isPresent());
    var caps = result.get();
    assertEquals("2024-03-14", caps.group(0));
    assertEquals("2024", caps.group(1));
    assertEquals("03", caps.group(2));
    assertEquals("14", caps.group(3));
}
```

- [ ] **Step 2: Rewrite `dfaTwoPhaseSearchCaptures()` to use three-phase**

```java
private Captures dfaSearchCaptures(Input input, Cache cache) {
    SearchResult fwdResult = forwardDFA.searchFwd(input, cache.forwardDFACache());
    return switch (fwdResult) {
        case SearchResult.NoMatch n -> null;
        case SearchResult.GaveUp g -> pikeVM.searchCaptures(input, cache.pikeVMCache());
        case SearchResult.Match m -> dfaSearchCapturesReverse(input, cache, m.offset());
    };
}

/**
 * After forward DFA finds matchEnd, use reverse DFA to narrow the window
 * for the capture engine. Falls back to PikeVM on the full window if
 * reverse DFA is unavailable or gives up.
 */
private Captures dfaSearchCapturesReverse(Input input, Cache cache, int matchEnd) {
    // Empty match or anchored: capture engine on [start, matchEnd]
    if (matchEnd == input.start() || input.isAnchored()) {
        Input narrowed = input.withBounds(input.start(), matchEnd, true);
        return pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
    }

    // No reverse DFA: capture engine on [start, matchEnd]
    if (reverseDFA == null) {
        Input narrowed = input.withBounds(input.start(), matchEnd, false);
        return pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
    }

    // Three-phase: reverse DFA finds start, then capture engine on [start, end]
    Input revInput = input.withBounds(input.start(), matchEnd, true);
    SearchResult revResult = reverseDFA.searchRev(revInput, cache.reverseDFACache());
    return switch (revResult) {
        case SearchResult.Match rm -> {
            Input narrowed = input.withBounds(rm.offset(), matchEnd, true);
            yield pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
        }
        case SearchResult.GaveUp g -> {
            Input narrowed = input.withBounds(input.start(), matchEnd, false);
            yield pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
        }
        case SearchResult.NoMatch n ->
            throw new IllegalStateException("reverse DFA found no match after forward match");
    };
}
```

Update `searchCaptures()` to call `dfaSearchCaptures()` (rename from `dfaTwoPhaseSearchCaptures`):

```java
@Override
public Captures searchCaptures(Input input, Cache cache) {
    if (prefilter != null && !input.isAnchored()) {
        if (forwardDFA != null) {
            return prefilterLoop(input, cache,
                    (in, c) -> dfaSearchCaptures(in, c));
        }
        return prefilterLoop(input, cache,
                (in, c) -> pikeVM.searchCaptures(in, c.pikeVMCache()));
    }
    if (forwardDFA != null) {
        return dfaSearchCaptures(input, cache);
    }
    return pikeVM.searchCaptures(input, cache.pikeVMCache());
}
```

Note: For now, the capture engine is PikeVM. The bounded backtracker will be added in Chunk 3.

- [ ] **Step 3: Run all tests**

Run: `./mvnw test`
Expected: All tests pass

- [ ] **Step 4: Commit**

```
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyLazyDFATest.java
git commit -m "feat: implement three-phase search for captures in Strategy.Core"
```

---

### Task 8: Run search benchmarks for three-phase

- [ ] **Step 1: Build and run benchmarks**

```bash
./mvnw -P bench package -DskipTests
java -jar regex-bench/target/benchmarks.jar "SearchBenchmark" -f 1 -wi 2 -i 3
```

- [ ] **Step 2: Record results in BENCHMARKS.md**

Expected: charClass should improve dramatically (20x → ~1-2x). captures should also improve (296x → ~20-50x, PikeVM on narrow window).

- [ ] **Step 3: Commit**

```
git add BENCHMARKS.md
git commit -m "docs: update benchmarks after three-phase search implementation"
```

---

## Chunk 3: Bounded Backtracker

### Task 9: Implement BoundedBacktracker engine

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/backtrack/BoundedBacktracker.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/backtrack/BoundedBacktrackerTest.java`

- [ ] **Step 1: Write failing tests for the bounded backtracker**

```java
package lol.ohai.regex.automata.nfa.thompson.backtrack;

import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoundedBacktrackerTest {

    @Test
    void anchoredLiteralMatch() {
        var bt = create("abc");
        var caps = bt.searchCaptures(Input.anchored("abcdef"), bt.createCache());
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(3, caps.end(0));
    }

    @Test
    void anchoredNoMatch() {
        var bt = create("abc");
        var caps = bt.searchCaptures(Input.anchored("xyzdef"), bt.createCache());
        assertNull(caps);
    }

    @Test
    void anchoredWithCaptures() {
        var bt = create("(\\d{4})-(\\d{2})-(\\d{2})");
        var caps = bt.searchCaptures(Input.anchored("2024-03-14"), bt.createCache());
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(10, caps.end(0));
        assertEquals(0, caps.start(1));  // year
        assertEquals(4, caps.end(1));
        assertEquals(5, caps.start(2));  // month
        assertEquals(7, caps.end(2));
        assertEquals(8, caps.start(3));  // day
        assertEquals(10, caps.end(3));
    }

    @Test
    void unanchoredSearch() {
        var bt = create("[a-z]+");
        var caps = bt.search(Input.of("123abc456"), bt.createCache());
        assertNotNull(caps);
        assertEquals(3, caps.start(0));
        assertEquals(6, caps.end(0));
    }

    @Test
    void visitedBitsetPreventsInfiniteLoop() {
        // Pattern with potential infinite loop via empty match
        var bt = create("(a*)*");
        var caps = bt.searchCaptures(Input.anchored("aaa"), bt.createCache());
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(3, caps.end(0)); // Greedy: matches all 'a's
    }

    @Test
    void alternationPrefersFirst() {
        var bt = create("(a|ab)");
        var caps = bt.searchCaptures(Input.anchored("ab"), bt.createCache());
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        // Leftmost-first: "a" is preferred over "ab"
        assertEquals(1, caps.end(0));
    }

    @Test
    void maxHaystackLenEnforced() {
        var bt = create("a+");
        int maxLen = bt.maxHaystackLen();
        assertTrue(maxLen > 0);
        // Searching beyond maxLen should return null (declined)
        String tooLong = "a".repeat(maxLen + 10);
        assertNull(bt.search(Input.of(tooLong), bt.createCache()));
    }

    private BoundedBacktracker create(String pattern) {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        NFA nfa = Compiler.compile(hir);
        return new BoundedBacktracker(nfa);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl regex-automata -Dtest="BoundedBacktrackerTest"`
Expected: FAIL — class does not exist

- [ ] **Step 3: Implement BoundedBacktracker**

Create `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/backtrack/BoundedBacktracker.java`:

The implementation follows the spec's algorithm exactly (see spec section "Component 3"). Key points:

- `sealed interface Frame` with `Step(stateId, at)` and `RestoreCapture(slot, prevValue)` records
- `Cache` inner class with `long[] visited`, `Frame[] stack`, `int stackTop`, `int[] slots`
- `search(Input, Cache)` — unanchored: first checks `input.end() - input.start() > maxHaystackLen()` and returns `null` if exceeded, then loops over start positions
- `searchCaptures(Input, Cache)` — anchored: first checks length limit, then single call to `backtrack()`
- `backtrack()` — explicit stack loop with Step/RestoreCapture dispatch
- `step()` — NFA state dispatch loop matching PikeVM's state handling conventions
- `maxHaystackLen()` — computed from NFA state count and default 256KB capacity
- Visited bitset: `insert(sid, offset)` returns false if already visited, true if newly inserted
- Slots use `-1` sentinel for "unset" (matching PikeVM convention)
- `at += 1` for all char-consuming states (CharRange, Sparse)
- Look assertion evaluation reuses the same `checkLook()` logic as PikeVM

Consult the PikeVM implementation (`PikeVM.java:418-509`) for `checkLook()` and `isWordChar()` / `isUnicodeWordChar()` logic — the backtracker needs identical look-assertion handling. Consider extracting shared look-assertion evaluation to avoid duplication, or copy the methods into the backtracker.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl regex-automata -Dtest="BoundedBacktrackerTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```
git add regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/backtrack/BoundedBacktracker.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/backtrack/BoundedBacktrackerTest.java
git commit -m "feat: implement BoundedBacktracker engine with visited-bitset bounding"
```

---

### Task 10: Run upstream TOML suite through BoundedBacktracker

**Files:**
- Create: `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/backtrack/BoundedBacktrackerSuiteTest.java`

- [ ] **Step 1: Write suite test runner**

Model this **exactly** after the existing `PikeVMSuiteTest.java`. The backtracker must be tested with the same input construction, match iteration, and byte-offset conversion as PikeVM — NOT on pre-narrowed windows.

```java
package lol.ohai.regex.automata.nfa.thompson.backtrack;

// Mirror PikeVMSuiteTest structure exactly:
// For each test case:
//   1. Compile pattern → NFA → BoundedBacktracker
//   2. Check if haystack length ≤ maxHaystackLen, skip if not
//   3. Run search on the FULL haystack (not pre-narrowed) with test's configured anchoring/bounds
//   4. Collect matches using iteration (same collectMatches approach as PikeVMSuiteTest)
//   5. Convert byte offsets ↔ char offsets using the existing conversion utilities
//   6. Compare against expected results
```

**Important:** Do NOT run anchored searches on `[expectedStart, expectedEnd]` — that gives trivially correct results and tests nothing about the engine's actual search capability. The test must prove the backtracker can find matches in full-size haystacks (up to its length limit).

**Suite test specifics:**
- `EngineCapabilities` should be `LEFTMOST_FIRST` only (no `EARLIEST`, no `ALL`)
- Blacklist `expensive/backtrack-blow-visited-capacity` by name (not just by `maxHaystackLen` check)
- Char-to-byte offset conversion helpers: share or copy from `PikeVMSuiteTest`
- Same skip conditions: multi-pattern, non-UTF-8, case-insensitive, regex-lite group, bounds-inside-codepoint

- [ ] **Step 2: Run suite tests**

Run: `./mvnw test -pl regex-automata -Dtest="BoundedBacktrackerSuiteTest"`
Expected: All applicable tests pass (some skipped due to haystack length)

- [ ] **Step 3: Commit**

```
git add regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/backtrack/BoundedBacktrackerSuiteTest.java
git commit -m "test: run upstream TOML suite through BoundedBacktracker"
```

---

### Task 11: Integrate BoundedBacktracker into Strategy and Regex

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java`
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`

- [ ] **Step 1: Add backtracker to Strategy.Core record**

Update the `Core` record to include the backtracker:

```java
record Core(PikeVM pikeVM, LazyDFA forwardDFA, LazyDFA reverseDFA,
            Prefilter prefilter, BoundedBacktracker backtracker) implements Strategy {
```

Update `createCache()` to include backtracker cache:

```java
public Cache createCache() {
    return new Cache(
            pikeVM.createCache(),
            forwardDFA != null ? forwardDFA.createCache() : null,
            reverseDFA != null ? reverseDFA.createCache() : null,
            backtracker != null ? backtracker.createCache() : null
    );
}
```

Update `Cache` record:

```java
record Cache(
        lol.ohai.regex.automata.nfa.thompson.pikevm.Cache pikeVMCache,
        DFACache forwardDFACache,
        DFACache reverseDFACache,
        lol.ohai.regex.automata.nfa.thompson.backtrack.BoundedBacktracker.Cache backtrackerCache
) {
    static final Cache EMPTY = new Cache(null, null, null, null);
}
```

- [ ] **Step 2: Use backtracker as capture engine in `dfaSearchCaptures()`**

In the three-phase capture path, replace direct PikeVM call with capture engine selection:

```java
// After reverse DFA finds matchStart:
Input narrowed = input.withBounds(matchStart, matchEnd, true);
yield captureEngine(narrowed, cache);
```

Add `captureEngine()` method:

```java
private Captures captureEngine(Input narrowed, Cache cache) {
    if (backtracker != null) {
        int windowLen = narrowed.end() - narrowed.start();
        if (windowLen <= backtracker.maxHaystackLen()) {
            Captures caps = backtracker.searchCaptures(narrowed, cache.backtrackerCache());
            if (caps != null) return caps;
        }
    }
    return pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
}
```

Update `pikeVM.searchCaptures(narrowed, cache.pikeVMCache())` calls in `dfaSearchCapturesReverse()` to use `captureEngine(narrowed, cache)` **only** for the narrowed, anchored path (the `SearchResult.Match rm` case). The GaveUp fallback path must continue using `pikeVM.searchCaptures()` directly because its input may be unanchored, and the backtracker's `searchCaptures()` is anchored-only (`nfa.startAnchored()`).

Specifically, only this call changes:
```java
case SearchResult.Match rm -> {
    Input narrowed = input.withBounds(rm.offset(), matchEnd, true);
    yield captureEngine(narrowed, cache);  // <-- backtracker OK: anchored, narrowed
}
```

The GaveUp and top-level GaveUp cases keep using `pikeVM.searchCaptures()` directly.

- [ ] **Step 3: Update Regex.create() to build the backtracker**

Inside the `else` branch of `Regex.create()` (the branch that builds the full engine, NOT the `PrefilterOnly` path), add backtracker construction alongside the existing PikeVM/DFA creation:

```java
// Inside the else branch (lines 90-105):
NFA nfa = Compiler.compile(hir);
// ... existing DFA creation ...
BoundedBacktracker backtracker = new BoundedBacktracker(nfa);

strategy = new Strategy.Core(pikeVM, forwardDFA, reverseDFA, prefilter, backtracker);
```

Also add import: `import lol.ohai.regex.automata.nfa.thompson.backtrack.BoundedBacktracker;`

- [ ] **Step 4: Run all tests**

Run: `./mvnw test`
Expected: All tests pass

- [ ] **Step 5: Commit**

```
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java \
        regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "feat: integrate BoundedBacktracker into Strategy.Core capture path"
```

---

### Task 12: Final benchmarks and documentation

- [ ] **Step 1: Run all benchmarks**

```bash
./mvnw -P bench package -DskipTests
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5
```

- [ ] **Step 2: Update BENCHMARKS.md with full results**

Record all search, pathological, and compile benchmarks. Add a new results section dated post search throughput improvement.

- [ ] **Step 3: Update lazy-dfa-gaps.md**

- Mark "Reverse DFA" as fully integrated (three-phase active)
- Mark "DFA Lazy Quantifier Limitation" as resolved (API contract clarified)
- Mark "Quit Bytes" as done (quit chars implemented)
- Add "Bounded Backtracker" as implemented

- [ ] **Step 4: Commit**

```
git add BENCHMARKS.md docs/architecture/lazy-dfa-gaps.md
git commit -m "docs: update benchmarks and gaps after search throughput improvements"
```

- [ ] **Step 5: Run full test suite one final time**

Run: `./mvnw test`
Expected: All tests pass
