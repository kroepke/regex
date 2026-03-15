# Codebase Cleanup Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate wasteful allocations, modernize collection usage, and clean up style issues across the regex codebase, with benchmark verification between each phase.

**Architecture:** Three phases executed sequentially with benchmark gates. Phase 1 targets search hot-path allocations (measured by SearchBenchmark/RawEngineBenchmark/PathologicalBenchmark). Phase 2 targets compilation-path efficiency (measured by CompileBenchmark). Phase 3 is code quality with test-only verification.

**Tech Stack:** Java 21, JMH benchmarks, JUnit 5, Maven wrapper

---

## Chunk 1: Phase 1 — Search Hot-Path

### Task 1: Baseline Benchmarks for Phase 1

**Files:**
- None modified

- [ ] **Step 1: Build the benchmark jar**

```bash
./mvnw -P bench package -DskipTests
```

- [ ] **Step 2: Run search benchmarks and save baseline**

```bash
java -jar regex-bench/target/benchmarks.jar "SearchBenchmark|RawEngineBenchmark|PathologicalBenchmark" -f 1 -wi 3 -i 5 | tee /tmp/phase1-baseline.txt
```

Record these numbers — they are the baseline for Phase 1.

- [ ] **Step 3: Run full test suite to confirm green baseline**

```bash
./mvnw test
```

Expected: 2,183 tests pass, 0 failures.

---

### Task 2: Lazy `Match.text()` (spec 1.1)

**Files:**
- Modify: `regex/src/main/java/lol/ohai/regex/Match.java`
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`
- Test: `regex/src/test/java/lol/ohai/regex/RegexTest.java`

- [ ] **Step 1: Write tests for the new Match behavior**

Add to `RegexTest.java`:

```java
@Test
void matchTextIsLazy() {
    Regex re = Regex.compile("\\d+");
    Match m = re.find("abc123def").orElseThrow();
    // text() should still work correctly
    assertEquals(3, m.start());
    assertEquals(6, m.end());
    assertEquals("123", m.text());
    // length() still works
    assertEquals(3, m.length());
}

@Test
void matchEquality() {
    Regex re = Regex.compile("\\d+");
    Match m1 = re.find("abc123def").orElseThrow();
    Match m2 = re.find("abc123def").orElseThrow();
    assertEquals(m1, m2);
    assertEquals(m1.hashCode(), m2.hashCode());
}

@Test
void matchToString() {
    Regex re = Regex.compile("\\d+");
    Match m = re.find("abc123def").orElseThrow();
    String s = m.toString();
    assertTrue(s.contains("3") && s.contains("6"), "toString should include start and end");
}
```

- [ ] **Step 2: Run tests to verify the new tests pass with current code**

```bash
./mvnw test -Dtest="RegexTest#matchTextIsLazy+matchEquality+matchToString"
```

Expected: All three pass with current record-based Match (record equality compares start, end, text — all value-equal). After the refactor, they should still pass with the new explicit equals/hashCode.

- [ ] **Step 3: Convert Match from record to final class**

Replace `Match.java` entirely:

```java
package lol.ohai.regex;

import java.util.Objects;

/**
 * A single match result, with start/end offsets in char units.
 * The matched text is computed lazily on first access to {@link #text()}.
 */
public final class Match {
    private final int start;
    private final int end;
    private final CharSequence source;
    private String text; // lazily computed

    Match(int start, int end, CharSequence source) {
        this.start = start;
        this.end = end;
        this.source = source;
    }

    /** Returns the start char offset (inclusive) in the original input. */
    public int start() { return start; }

    /** Returns the end char offset (exclusive) in the original input. */
    public int end() { return end; }

    /** Returns the matched substring. Computed lazily on first call. */
    public String text() {
        if (text == null) {
            text = source.subSequence(start, end).toString();
        }
        return text;
    }

    /** Returns the length of the match in chars. */
    public int length() { return end - start; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Match m)) return false;
        return start == m.start && end == m.end && text().equals(m.text());
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, end, text());
    }

    @Override
    public String toString() {
        return "Match[start=" + start + ", end=" + end + ", text=" + text() + "]";
    }
}
```

- [ ] **Step 4: Update Regex.toMatch() to use the new constructor**

In `Regex.java`, change `toMatch()`:

```java
private Match toMatch(CharSequence text,
                      lol.ohai.regex.automata.util.Captures caps, int group) {
    int start = caps.start(group);
    int end = caps.end(group);
    return new Match(start, end, text);
}
```

- [ ] **Step 5: Fix RegexTest.matchLength — it constructs Match directly**

`RegexTest.java` line 149 creates `new Match(3, 7, "test")`. This must be updated since the constructor signature changed. The test constructs a Match for a substring of length 4 at offsets 3-7 — we need a source CharSequence that covers those offsets:

```java
@Test
void matchLength() {
    // Match from offset 3 to 7 in a source string
    Match m = new Match(3, 7, "___test_");
    assertEquals(4, m.length());
    assertEquals("test", m.text());
}
```

Note: `Match` constructor is package-private, so this works from the test in the same package.

- [ ] **Step 6: Run full test suite**

```bash
./mvnw test
```

Expected: All 2,183 tests pass.

- [ ] **Step 7: Commit**

```bash
git add regex/src/main/java/lol/ohai/regex/Match.java regex/src/main/java/lol/ohai/regex/Regex.java regex/src/test/java/lol/ohai/regex/RegexTest.java
git commit -m "refactor: lazy Match.text() — defer substring allocation until accessed

Convert Match from record to final class. The text() method now computes
the substring lazily on first call, eliminating O(n) String allocations
in findAll() loops where callers only need offsets.

Spec: docs/superpowers/specs/2026-03-15-codebase-cleanup-design.md §1.1"
```

---

### Task 3: Mutable Input bounds in Searcher and iterators (spec 1.2)

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/util/Input.java`
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`

- [ ] **Step 1: Make the mutable fields non-final**

In `Input.java`, change the field declarations (remove `final` from `start`, `end`, `anchored`). The `haystack` and `haystackStr` fields stay as-is:

```java
private int start;
private int end;
private boolean anchored;
```

- [ ] **Step 2: Add setBounds() to Input**

Add this method to `Input.java`:

```java
/**
 * Mutates this Input's bounds and anchored flag in-place.
 * For use in tight iteration loops that reuse a single Input instance.
 */
public void setBounds(int newStart, int newEnd, boolean newAnchored) {
    this.start = newStart;
    this.end = newEnd;
    this.anchored = newAnchored;
}
```

- [ ] **Step 3: Update Searcher.find() to reuse Input**

In `Regex.java`, modify `Searcher` to use a mutable `searchInput` instead of calling `withBounds()`:

Change the `Searcher` class:

```java
public final class Searcher {
    private final CharSequence text;
    private final Input searchInput;
    private final Strategy.Cache cache;
    private int searchStart;
    private int lastMatchEnd = -1;
    private int matchStart = -1;
    private int matchEnd = -1;

    Searcher(CharSequence text) {
        this.text = text;
        this.searchInput = Input.of(text);
        this.cache = strategy.createCache();
    }

    public boolean find() {
        int end = text.length();
        while (searchStart <= end) {
            searchInput.setBounds(searchStart, end, false);
            lol.ohai.regex.automata.util.Captures caps = strategy.search(searchInput, cache);
            if (caps == null) return false;

            int s = caps.start(0);
            int e = caps.end(0);

            if (s == e && e == lastMatchEnd) {
                if (e < end) {
                    searchStart = e + Character.charCount(
                            Character.codePointAt(text, e));
                } else {
                    searchStart = e + 1;
                }
                continue;
            }

            matchStart = s;
            matchEnd = e;
            lastMatchEnd = e;
            searchStart = e;
            return true;
        }
        return false;
    }

    public int start() { return matchStart; }
    public int end() { return matchEnd; }
}
```

- [ ] **Step 4: Update BaseFindIterator.advance() similarly**

Change `BaseFindIterator` to use a mutable `searchInput`:

```java
private abstract class BaseFindIterator<T> implements Iterator<T> {
    final CharSequence text;
    private final Input searchInput;
    private int searchCharStart = 0;
    private int lastMatchCharEnd = -1;
    private T nextResult;
    private boolean done = false;

    BaseFindIterator(CharSequence text) {
        this.text = text;
        this.searchInput = Input.of(text);
    }

    // hasNext() and next() are unchanged — they reference fields
    // searchCharStart, lastMatchCharEnd, nextResult, done which
    // keep the same names and types.

    private void advance() {
        Strategy.Cache cache = cachePool.get();

        while (!done) {
            if (searchCharStart > text.length()) {
                done = true;
                return;
            }

            searchInput.setBounds(searchCharStart, text.length(), false);
            lol.ohai.regex.automata.util.Captures caps = doSearch(searchInput, cache);

            if (caps == null) {
                done = true;
                return;
            }

            int charStart = caps.start(0);
            int charEnd = caps.end(0);

            if (charStart == charEnd && charEnd == lastMatchCharEnd) {
                if (charEnd < text.length()) {
                    searchCharStart = charEnd + Character.charCount(
                            Character.codePointAt(text, charEnd));
                } else {
                    searchCharStart = charEnd + 1;
                }
                continue;
            }

            lastMatchCharEnd = charEnd;
            searchCharStart = charEnd;

            nextResult = toResult(text, caps);
            return;
        }
    }
}
```

- [ ] **Step 5: Run full test suite**

```bash
./mvnw test
```

Expected: All 2,183 tests pass.

- [ ] **Step 6: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/util/Input.java regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "refactor: reuse Input object in Searcher and iterator loops

Add Input.setBounds() for in-place mutation. Searcher.find() and
BaseFindIterator.advance() now reuse a single Input instance instead
of allocating a new one per withBounds() call.

Spec: docs/superpowers/specs/2026-03-15-codebase-cleanup-design.md §1.2"
```

---

### Task 4: CharClasses metadata consolidation (spec 1.3)

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClasses.java`
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/CharClassesTest.java`

- [ ] **Step 1: Write tests for the flag-based accessors**

Add to `CharClassesTest.java`:

```java
@Test
void wordClassFlagsPreserved() throws Exception {
    NFA nfa = compileNfa("\\w+");
    CharClasses cc = CharClassBuilder.build(nfa);
    assertNotNull(cc);

    // 'a' is a word char
    assertTrue(cc.isWordClass(cc.classify('a')));
    // ' ' is not a word char
    assertFalse(cc.isWordClass(cc.classify(' ')));
}

@Test
void lineFeedFlagsPreserved() throws Exception {
    NFA nfa = compileNfa("(?m)^test$");
    CharClasses cc = CharClassBuilder.build(nfa);
    assertNotNull(cc);

    assertTrue(cc.isLineLF(cc.classify('\n')));
    assertFalse(cc.isLineLF(cc.classify('a')));
    assertTrue(cc.isLineCR(cc.classify('\r')));
    assertFalse(cc.isLineCR(cc.classify('a')));
}

@Test
void identityHasNoFlags() {
    CharClasses cc = CharClasses.identity();
    // identity() has classCount=1, class 0 should have no flags
    assertFalse(cc.isWordClass(0));
    assertFalse(cc.isLineLF(0));
    assertFalse(cc.isLineCR(0));
    assertFalse(cc.isQuitClass(0));
    assertFalse(cc.hasQuitClasses());
}
```

- [ ] **Step 2: Run new tests to verify they pass with current code**

```bash
./mvnw test -Dtest="CharClassesTest#wordClassFlagsPreserved+lineFeedFlagsPreserved+identityHasNoFlags"
```

Expected: All pass (existing behavior preserved).

- [ ] **Step 3: Refactor CharClasses to use byte[] classFlags**

Replace `CharClasses.java` with:

```java
package lol.ohai.regex.automata.dfa;

public final class CharClasses {
    static final byte FLAG_WORD = 1;
    static final byte FLAG_LF   = 2;
    static final byte FLAG_CR   = 4;
    static final byte FLAG_QUIT = 8;

    private final byte[][] rows;
    private final int[] highIndex;
    private final int classCount;
    private final int stride;
    private final int strideShift;
    private final byte[] classFlags;   // indexed by class ID, bit flags
    private final boolean hasQuitClasses;
    private final byte[] asciiTable;   // flat lookup for c < 128

    CharClasses(byte[][] rows, int[] highIndex, int classCount,
                byte[] classFlags) {
        this.rows = rows;
        this.highIndex = highIndex;
        this.classCount = classCount;
        int alphabetSize = classCount + 1; // +1 for EOI
        this.stride = Integer.highestOneBit(alphabetSize - 1) << 1;
        this.strideShift = Integer.numberOfTrailingZeros(this.stride);
        this.classFlags = classFlags;

        // Precompute hasQuitClasses
        boolean hasQuit = false;
        for (int i = 0; i < classFlags.length; i++) {
            if ((classFlags[i] & FLAG_QUIT) != 0) {
                hasQuit = true;
                break;
            }
        }
        this.hasQuitClasses = hasQuit;

        this.asciiTable = new byte[128];
        System.arraycopy(rows[highIndex[0]], 0, asciiTable, 0, 128);
    }

    public int classify(char c) {
        if (c < 128) return asciiTable[c] & 0xFF;
        return rows[highIndex[c >>> 8]][c & 0xFF] & 0xFF;
    }

    /** Two-level classify, bypassing ASCII fast-path. Package-private for testing. */
    int classifyTwoLevel(char c) {
        return rows[highIndex[c >>> 8]][c & 0xFF] & 0xFF;
    }

    public int classCount() { return classCount; }
    public int eoiClass() { return classCount; }
    public int stride() { return stride; }
    public int strideShift() { return strideShift; }

    public boolean isWordClass(int classId) {
        return classId < classFlags.length && (classFlags[classId] & FLAG_WORD) != 0;
    }

    public boolean isLineLF(int classId) {
        return classId < classFlags.length && (classFlags[classId] & FLAG_LF) != 0;
    }

    public boolean isLineCR(int classId) {
        return classId < classFlags.length && (classFlags[classId] & FLAG_CR) != 0;
    }

    public boolean isQuitClass(int classId) {
        return classId < classFlags.length && (classFlags[classId] & FLAG_QUIT) != 0;
    }

    public boolean hasQuitClasses() {
        return hasQuitClasses;
    }

    public static CharClasses identity() {
        byte[] singleRow = new byte[256];
        byte[][] rows = { singleRow };
        int[] highIndex = new int[256];
        return new CharClasses(rows, highIndex, 1, new byte[1]);
    }
}
```

- [ ] **Step 4: Update CharClassBuilder to produce classFlags**

In `CharClassBuilder.java`, update both `build()` and `buildUnmerged()` to build `byte[] classFlags` instead of four separate `boolean[]` arrays.

In `build()`, replace lines 102-116 (the wordClass/lineLF/lineCR arrays and the constructor call):

```java
byte[] classFlags = new byte[classCount];
for (int cls = 0; cls < classCount; cls++) {
    int rep = classRep[cls];
    if (rep >= 0 && rep < 65536) {
        char c = (char) rep;
        if (isWordChar(c))  classFlags[cls] |= CharClasses.FLAG_WORD;
        if (c == '\n')      classFlags[cls] |= CharClasses.FLAG_LF;
        if (c == '\r')      classFlags[cls] |= CharClasses.FLAG_CR;
    }
}

return new CharClasses(uniqueRows.toArray(byte[][]::new), highIndex, classCount,
        classFlags);
```

In `buildUnmerged()`, replace lines 229-254 (the wordClass/lineLF/lineCR/quitClass arrays and constructor call):

```java
byte[] classFlags = new byte[classCount];
for (int cls = 0; cls < classCount && cls < sortedBounds.length - 1; cls++) {
    int representative = sortedBounds[cls];
    if (representative < 65536) {
        char c = (char) representative;
        if (isWordChar(c))  classFlags[cls] |= CharClasses.FLAG_WORD;
        if (c == '\n')      classFlags[cls] |= CharClasses.FLAG_LF;
        if (c == '\r')      classFlags[cls] |= CharClasses.FLAG_CR;
    }
}

if (quitNonAscii) {
    for (int cls = 0; cls < classCount && cls < sortedBounds.length - 1; cls++) {
        int representative = sortedBounds[cls];
        if (representative >= 128 && representative < 65536) {
            classFlags[cls] |= CharClasses.FLAG_QUIT;
        }
    }
}

return new CharClasses(uniqueRows.toArray(byte[][]::new), highIndex, classCount,
        classFlags);
```

- [ ] **Step 5: Run full test suite**

```bash
./mvnw test
```

Expected: All 2,183 tests pass.

- [ ] **Step 6: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClasses.java regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java regex-automata/src/test/java/lol/ohai/regex/automata/dfa/CharClassesTest.java
git commit -m "refactor: consolidate CharClasses metadata into byte[] classFlags

Replace four boolean[] arrays (wordClass, lineLF, lineCR, quitClass)
with a single byte[] using bit flags. Eliminates null checks on
accessor methods and reduces memory. Also replaces manual asciiTable
init loop with System.arraycopy.

Spec: docs/superpowers/specs/2026-03-15-codebase-cleanup-design.md §1.3"
```

---

### Task 5: Unnamed pattern variables in Strategy.java (spec 1.4)

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java`

- [ ] **Step 1: Replace all unused pattern variables with `_`**

In `Strategy.java`, apply these replacements throughout the file:

- `case SearchResult.NoMatch n ->` → `case SearchResult.NoMatch _ ->`
- `case SearchResult.NoMatch n2 ->` → `case SearchResult.NoMatch _ ->`
- `case SearchResult.GaveUp g ->` → `case SearchResult.GaveUp _ ->`
- `case SearchResult.GaveUp g2 ->` → `case SearchResult.GaveUp _ ->`

For `SearchResult.Match`, only replace the ones where the variable is not used:
- Inner switch `case SearchResult.Match fm ->` where `fm` is NOT used → `case SearchResult.Match _ ->`

Do NOT replace `case SearchResult.Match m ->` where `m.offset()` is called — those are used.

Concretely, the replacements are at these line ranges:
- Lines 340, 374, 427, 502, 543, 601: `NoMatch n` → `NoMatch _`
- Lines 344, 378, 431, 508, 549, 607: `GaveUp g` → `GaveUp _`
- Lines 397, 450, 566, 623: `NoMatch n2` → `NoMatch _`
- Lines 393, 446, 563, 620: `GaveUp g2` → `GaveUp _`
- Lines 387, 440, 559, 616: `Match fm` → `Match _` (where `fm` is not referenced in the block — check each one: the block uses `m.offset()` from the outer match, not `fm`)

- [ ] **Step 2: Run full test suite**

```bash
./mvnw test
```

Expected: All 2,183 tests pass.

- [ ] **Step 3: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java
git commit -m "style: replace unused pattern variables with _ in Strategy.java

Use Java 21 unnamed patterns for ~30 switch case variables that were
bound but never read (NoMatch, GaveUp, and some Match cases).

Spec: docs/superpowers/specs/2026-03-15-codebase-cleanup-design.md §1.4"
```

---

### Task 6: Phase 1 Post-Benchmarks

**Files:**
- None modified

- [ ] **Step 1: Build the benchmark jar**

```bash
./mvnw -P bench package -DskipTests
```

- [ ] **Step 2: Run the same benchmarks as Task 1**

```bash
java -jar regex-bench/target/benchmarks.jar "SearchBenchmark|RawEngineBenchmark|PathologicalBenchmark" -f 1 -wi 3 -i 5 | tee /tmp/phase1-post.txt
```

- [ ] **Step 3: Compare baseline vs post-phase**

Compare `/tmp/phase1-baseline.txt` and `/tmp/phase1-post.txt`. Check:
- No benchmark regressed by more than 2x
- Note any improvements

If any regression exceeds 2x, stop and investigate before proceeding to Phase 2.

---

## Chunk 2: Phase 2 — Compilation Path

### Task 7: Baseline Benchmarks for Phase 2

**Files:**
- None modified

- [ ] **Step 1: Run compile benchmarks and save baseline**

```bash
java -jar regex-bench/target/benchmarks.jar CompileBenchmark -f 1 -wi 3 -i 5 | tee /tmp/phase2-baseline.txt
```

(Benchmark jar is already built from Task 6.)

---

### Task 8: Replace TreeSet with int[] + sort (spec 2.1)

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`

- [ ] **Step 1: Replace collectBoundaries() to return int[]**

Replace the `collectBoundaries` method. The new version collects into a growable `int[]`, then sorts and deduplicates:

```java
/**
 * Collect character class boundaries from the NFA's character ranges.
 * Returns a sorted, deduplicated int[] of boundary code points.
 * When {@code quitNonAscii} is true, boundaries above 128 are collapsed.
 */
private static int[] collectBoundaries(NFA nfa, boolean quitNonAscii) {
    // Growable int array — start with reasonable capacity
    int[] buf = new int[64];
    int size = 0;

    // Always include 0 and 0x10000
    buf[size++] = 0;
    buf[size++] = 0x10000;

    if (quitNonAscii) {
        buf[size++] = 128;
    }

    for (int i = 0; i < nfa.stateCount(); i++) {
        State state = nfa.state(i);
        switch (state) {
            case State.CharRange cr -> {
                if (size + 2 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                buf[size++] = cr.start();
                buf[size++] = cr.end() + 1;
            }
            case State.Sparse sp -> {
                Transition[] ts = sp.transitions();
                if (size + ts.length * 2 > buf.length)
                    buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + ts.length * 2));
                for (Transition t : ts) {
                    buf[size++] = t.start();
                    buf[size++] = t.end() + 1;
                }
            }
            default -> {}
        }
    }

    // When quit chars are configured, remove boundaries in [129, 0x10000)
    // by simply not including them. We filter after sort.

    // Force boundaries for look-assertion characters
    LookSet lookSetAny = nfa.lookSetAny();
    if (!lookSetAny.isEmpty()) {
        if (size + 8 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
        buf[size++] = '\n';
        buf[size++] = '\n' + 1;
        buf[size++] = '\r';
        buf[size++] = '\r' + 1;

        if (lookSetAny.containsWord()) {
            if (size + 8 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            buf[size++] = '0';
            buf[size++] = '9' + 1;
            buf[size++] = 'A';
            buf[size++] = 'Z' + 1;
            buf[size++] = '_';
            buf[size++] = '_' + 1;
            buf[size++] = 'a';
            buf[size++] = 'z' + 1;
        }
    }

    // Sort and deduplicate
    Arrays.sort(buf, 0, size);

    // Compact: remove duplicates in-place
    if (size <= 1) return Arrays.copyOf(buf, size);
    int write = 1;
    for (int read = 1; read < size; read++) {
        if (buf[read] != buf[write - 1]) {
            buf[write++] = buf[read];
        }
    }

    // If quitNonAscii, remove boundaries in [129, 0x10000)
    if (quitNonAscii) {
        int filtered = 0;
        for (int i = 0; i < write; i++) {
            if (buf[i] < 129 || buf[i] >= 0x10000) {
                buf[filtered++] = buf[i];
            }
        }
        write = filtered;
    }

    return Arrays.copyOf(buf, write);
}
```

- [ ] **Step 2: Update callers in build() and buildUnmerged()**

In `build()`, change:

```java
// Old:
TreeSet<Integer> boundaries = collectBoundaries(nfa, false);
int[] sortedBounds = boundaries.stream().mapToInt(Integer::intValue).toArray();

// New:
int[] sortedBounds = collectBoundaries(nfa, false);
```

In `buildUnmerged()`, change:

```java
// Old:
TreeSet<Integer> boundaries = collectBoundaries(nfa, quitNonAscii);
if (boundaries.size() - 1 > 256 && !quitNonAscii) {
    boundaries = collectBoundaries(nfa, true);
    quitNonAscii = true;
}
int[] sortedBounds = boundaries.stream().mapToInt(Integer::intValue).toArray();

// New:
int[] sortedBounds = collectBoundaries(nfa, quitNonAscii);
if (sortedBounds.length - 1 > 256 && !quitNonAscii) {
    sortedBounds = collectBoundaries(nfa, true);
    quitNonAscii = true;
}
```

Remove the `import java.util.TreeSet;` since it's no longer used.

- [ ] **Step 3: Run full test suite**

```bash
./mvnw test
```

Expected: All 2,183 tests pass.

- [ ] **Step 4: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java
git commit -m "refactor: replace TreeSet<Integer> with int[] in collectBoundaries

Eliminates all Integer boxing and red-black tree overhead during
char class compilation. Boundaries are now collected into a growable
int[], sorted once with Arrays.sort(), and deduplicated in-place.

Spec: docs/superpowers/specs/2026-03-15-codebase-cleanup-design.md §2.1"
```

---

### Task 9: Long bitmask for small-NFA signatures (spec 2.2)

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`

- [ ] **Step 1: Add computeSignatureLong() method**

Add below the existing `computeSignature()` method:

```java
/**
 * Fast-path signature computation for NFAs with <= 62 states (+ 2 look bits).
 * Returns a long bitmask instead of allocating a BitSet.
 */
private static long computeSignatureLong(NFA nfa, int representative) {
    int lookBase = nfa.stateCount();
    long sig = 0L;
    for (int i = 0; i < nfa.stateCount(); i++) {
        State state = nfa.state(i);
        switch (state) {
            case State.CharRange cr -> {
                if (representative >= cr.start() && representative <= cr.end()) {
                    sig |= 1L << resolveTarget(nfa, cr.next());
                }
            }
            case State.Sparse sp -> {
                for (Transition t : sp.transitions()) {
                    if (representative >= t.start() && representative <= t.end()) {
                        sig |= 1L << resolveTarget(nfa, t.next());
                        break;
                    }
                }
            }
            default -> {}
        }
    }
    if (representative == '\n') sig |= 1L << lookBase;
    if (representative == '\r') sig |= 1L << (lookBase + 1);
    return sig;
}
```

- [ ] **Step 2: Add long-based fast path in build()**

In the `build()` method, replace the merge loop (lines 42-56) with a dual-path version:

```java
// Merge: compute behavior signature per region, group by signature.
int[] regionClassMap = new int[regionCount];
int nextClassId = 0;
int lookBase = nfa.stateCount();

if (lookBase + 2 <= 64) {
    // Fast path: long bitmask signatures (no allocation per region)
    HashMap<Long, Integer> signatureToClass = new HashMap<>();
    for (int r = 0; r < regionCount; r++) {
        long sig = computeSignatureLong(nfa, sortedBounds[r]);
        Integer existingClass = signatureToClass.get(sig);
        if (existingClass != null) {
            regionClassMap[r] = existingClass;
        } else {
            regionClassMap[r] = nextClassId;
            signatureToClass.put(sig, nextClassId);
            nextClassId++;
        }
    }
} else {
    // Slow path: BitSet signatures for large NFAs
    HashMap<BitSet, Integer> signatureToClass = new HashMap<>();
    for (int r = 0; r < regionCount; r++) {
        BitSet sig = computeSignature(nfa, sortedBounds[r]);
        Integer existingClass = signatureToClass.get(sig);
        if (existingClass != null) {
            regionClassMap[r] = existingClass;
        } else {
            regionClassMap[r] = nextClassId;
            signatureToClass.put(sig, nextClassId);
            nextClassId++;
        }
    }
}
```

- [ ] **Step 3: Run full test suite**

```bash
./mvnw test
```

Expected: All 2,183 tests pass.

- [ ] **Step 4: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java
git commit -m "refactor: use long bitmask for small-NFA signature computation

For NFAs with <= 62 states (the common case), compute equivalence class
signatures as long bitmasks instead of allocating BitSet objects. Falls
back to BitSet for larger NFAs.

Spec: docs/superpowers/specs/2026-03-15-codebase-cleanup-design.md §2.2"
```

---

### Task 10: Eliminate flatMap + extract row-dedup helper (spec 2.3 + 2.4)

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`

These two changes are combined because 2.4 (extract helper) is a natural part of implementing 2.3 (eliminate flatMap).

- [ ] **Step 1: Add a private helper to classify a char given sorted bounds and class mapping**

```java
/**
 * Binary search for the class of a given char code point in sorted boundaries.
 * Returns the index of the region containing {@code codePoint}, which is then
 * used as a class ID (for unmerged) or mapped through regionClassMap (for merged).
 */
private static int findRegion(int[] sortedBounds, int codePoint) {
    int lo = 0;
    int hi = sortedBounds.length - 1;
    while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (sortedBounds[mid] <= codePoint) {
            lo = mid;
        } else {
            hi = mid - 1;
        }
    }
    return lo;
}
```

- [ ] **Step 2: Refactor build() to compute rows directly**

Replace the flatMap allocation + row extraction in `build()` (lines 64-90) with inline row computation and dedup. Compute one row at a time to avoid allocating all 256 rows upfront:

```java
// Compute and dedup rows directly — no 64KB flatMap staging buffer
Map<ByteArrayKey, Integer> rowIndex = new HashMap<>();
List<byte[]> uniqueRows = new ArrayList<>();
int[] highIndex = new int[256];
for (int hi = 0; hi < 256; hi++) {
    byte[] row = new byte[256];
    for (int lo = 0; lo < 256; lo++) {
        int codePoint = (hi << 8) | lo;
        int region = findRegion(sortedBounds, codePoint);
        if (region < regionCount) {
            row[lo] = (byte) regionClassMap[region];
        }
    }
    ByteArrayKey key = new ByteArrayKey(row);
    Integer existing = rowIndex.get(key);
    if (existing != null) {
        highIndex[hi] = existing;
    } else {
        int idx = uniqueRows.size();
        uniqueRows.add(row);
        rowIndex.put(key, idx);
        highIndex[hi] = idx;
    }
}
```

Then use `uniqueRows.toArray(byte[][]::new)` and `highIndex` in the constructor call:

```java
return new CharClasses(uniqueRows.toArray(byte[][]::new), highIndex, classCount,
        classFlags);
```

Note: the `deduplicateRows` helper is no longer needed since the dedup is inline (matching the existing code pattern). Remove it.

- [ ] **Step 3: Refactor buildUnmerged() similarly**

Replace the flatMap allocation + row extraction in `buildUnmerged()` (lines 201-227) with the same inline pattern:

```java
Map<ByteArrayKey, Integer> rowIndex = new HashMap<>();
List<byte[]> uniqueRows = new ArrayList<>();
int[] highIndex = new int[256];
for (int hi = 0; hi < 256; hi++) {
    byte[] row = new byte[256];
    for (int lo = 0; lo < 256; lo++) {
        int codePoint = (hi << 8) | lo;
        int region = findRegion(sortedBounds, codePoint);
        if (region < classCount) {
            row[lo] = (byte) region;
        }
    }
    ByteArrayKey key = new ByteArrayKey(row);
    Integer existing = rowIndex.get(key);
    if (existing != null) {
        highIndex[hi] = existing;
    } else {
        int idx = uniqueRows.size();
        uniqueRows.add(row);
        rowIndex.put(key, idx);
        highIndex[hi] = idx;
    }
}
```

Then use `uniqueRows.toArray(byte[][]::new)` and `highIndex` in the constructor call.

- [ ] **Step 4: Run full test suite**

```bash
./mvnw test
```

Expected: All 2,183 tests pass.

- [ ] **Step 5: Spot-check CompileBenchmark for regression**

The binary-search approach (256 lookups per row) may be slower than the flat-fill for patterns with many boundaries. Run a quick compile benchmark to verify:

```bash
java -jar regex-bench/target/benchmarks.jar CompileBenchmark -f 1 -wi 3 -i 5
```

Compare with Phase 2 baseline. If any compile benchmark regresses by more than 2x, revert to the flatMap approach and keep only the code dedup (row-dedup inline pattern).

- [ ] **Step 6: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java
git commit -m "refactor: eliminate 64KB flatMap buffer, compute rows directly

Compute char class rows via binary search on sorted boundaries instead
of staging through a 64KB byte[] intermediate. Dedup logic remains
inline in both build() and buildUnmerged().

Spec: docs/superpowers/specs/2026-03-15-codebase-cleanup-design.md §2.3/2.4"
```

---

### Task 11: Modernize Collections.unmodifiable* (spec 2.5)

**Files:**
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`

- [ ] **Step 1: Replace Collections.unmodifiableList with List.copyOf**

In `Regex.java` `toCaptures()` method (line 388), change:

```java
// Old:
return new Captures(overall, Collections.unmodifiableList(groups), namedGroups);

// New:
return new Captures(overall, List.copyOf(groups), namedGroups);
```

- [ ] **Step 2: Replace Collections.unmodifiableMap with Map.copyOf**

In `Regex.java` `buildNamedGroupMap()` method (line 400), change:

```java
// Old:
return Collections.unmodifiableMap(map);

// New:
return Map.copyOf(map);
```

- [ ] **Step 3: Clean up unused imports**

Remove `import java.util.Collections;` if no longer used anywhere in the file. Check — `Collections.emptyMap()` is used at line 92. So keep `Collections` but note this import is still needed.

- [ ] **Step 4: Run full test suite**

```bash
./mvnw test
```

Expected: All 2,183 tests pass.

- [ ] **Step 5: Commit**

```bash
git add regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "refactor: modernize to List.copyOf/Map.copyOf

Replace Collections.unmodifiableList/Map with Java 10+ List.copyOf
and Map.copyOf for truly immutable collections without wrapper overhead.

Spec: docs/superpowers/specs/2026-03-15-codebase-cleanup-design.md §2.5"
```

---

### Task 12: Phase 2 Post-Benchmarks

**Files:**
- None modified

- [ ] **Step 1: Rebuild the benchmark jar**

```bash
./mvnw -P bench package -DskipTests
```

- [ ] **Step 2: Run compile benchmarks**

```bash
java -jar regex-bench/target/benchmarks.jar CompileBenchmark -f 1 -wi 3 -i 5 | tee /tmp/phase2-post.txt
```

- [ ] **Step 3: Compare baseline vs post-phase**

Compare `/tmp/phase2-baseline.txt` and `/tmp/phase2-post.txt`. Check:
- No benchmark regressed by more than 2x
- Note any improvements, especially for the Unicode-heavy patterns (`compileUnicodeWord`, `compileWordBoundary`)

If any regression exceeds 2x, stop and investigate before proceeding to Phase 3.

---

## Chunk 3: Phase 3 — Code Quality

### Task 13: Arrays.copyOf in OnePassBuilder (spec 3.1)

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/OnePassBuilder.java`

- [ ] **Step 1: Replace grow() and growLong()**

In `OnePassBuilder.java`, replace lines 380-390:

```java
private static int[] grow(int[] arr) {
    return Arrays.copyOf(arr, arr.length * 2);
}

private static long[] growLong(long[] arr) {
    return Arrays.copyOf(arr, arr.length * 2);
}
```

Add `import java.util.Arrays;` if not already present.

- [ ] **Step 2: Run full test suite**

```bash
./mvnw test
```

Expected: All 2,183 tests pass.

- [ ] **Step 3: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/OnePassBuilder.java
git commit -m "style: use Arrays.copyOf in OnePassBuilder.grow()

Replace manual new-array + System.arraycopy with the standard
Arrays.copyOf idiom for array growth.

Spec: docs/superpowers/specs/2026-03-15-codebase-cleanup-design.md §3.1"
```

---

### Task 14: Match[] in Captures (spec 3.2)

**Files:**
- Modify: `regex/src/main/java/lol/ohai/regex/Captures.java`
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`

- [ ] **Step 1: Refactor Captures to use Match[]**

Replace `Captures.java`:

```java
package lol.ohai.regex;

import java.util.Map;
import java.util.Optional;

/**
 * Capture group results from a regex match.
 *
 * <p>Group 0 is always the overall match. Named groups can be accessed by name via
 * {@link #group(String)}. Unnamed groups are accessed by index via {@link #group(int)}.</p>
 */
public final class Captures {
    private final Match overall;
    private final Match[] groups;  // nulls for unmatched groups
    private final Map<String, Integer> namedGroups;

    Captures(Match overall, Match[] groups, Map<String, Integer> namedGroups) {
        this.overall = overall;
        this.groups = groups;
        this.namedGroups = namedGroups;
    }

    /** Returns the overall match (group 0). */
    public Match overall() {
        return overall;
    }

    /**
     * Returns the match for the given group index.
     *
     * @param index the group index (0 = overall match)
     * @return the match, or empty if the group did not participate
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public Optional<Match> group(int index) {
        return Optional.ofNullable(groups[index]);
    }

    /**
     * Returns the match for the given named group.
     *
     * @param name the group name
     * @return the match, or empty if the group did not participate
     * @throws IllegalArgumentException if no group with that name exists
     */
    public Optional<Match> group(String name) {
        Integer index = namedGroups.get(name);
        if (index == null) {
            throw new IllegalArgumentException("no group named: " + name);
        }
        return Optional.ofNullable(groups[index]);
    }

    /** Returns the number of capture groups (including group 0). */
    public int groupCount() {
        return groups.length;
    }

    /** Returns an unmodifiable map of group names to their indices. */
    public Map<String, Integer> namedGroups() {
        return namedGroups;
    }
}
```

- [ ] **Step 2: Update Regex.toCaptures() to build Match[]**

In `Regex.java`, change `toCaptures()`:

```java
private Captures toCaptures(CharSequence text,
                            lol.ohai.regex.automata.util.Captures caps) {
    int groupCount = caps.groupCount();
    Match[] groups = new Match[groupCount];
    Match overall = null;
    for (int i = 0; i < groupCount; i++) {
        if (caps.isMatched(i)) {
            Match m = toMatch(text, caps, i);
            groups[i] = m;
            if (i == 0) {
                overall = m;
            }
        }
    }
    return new Captures(overall, groups, namedGroups);
}
```

Remove the `java.util.ArrayList` import (no longer used). Keep `java.util.List` — it's still used in `buildNamedGroupMap()` and other places.

- [ ] **Step 3: Run full test suite**

```bash
./mvnw test
```

Expected: All 2,183 tests pass.

- [ ] **Step 4: Commit**

```bash
git add regex/src/main/java/lol/ohai/regex/Captures.java regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "refactor: use Match[] instead of List<Optional<Match>> in Captures

Store groups as a Match[] with nulls for unmatched groups. The group()
accessor wraps with Optional.ofNullable() on demand, deferring
Optional allocation to access time.

Spec: docs/superpowers/specs/2026-03-15-codebase-cleanup-design.md §3.2"
```

---

### Task 15: Document PrefilterOnly unused cache parameter (spec 3.3)

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java`

- [ ] **Step 1: Add annotation and comment**

In `Strategy.java`, add annotation to the `PrefilterOnly` record:

```java
// PrefilterOnly needs no engine cache — parameter required by Strategy interface contract.
@SuppressWarnings("unused")
record PrefilterOnly(Prefilter prefilter) implements Strategy {
```

The `@SuppressWarnings` applies to the record's method implementations that receive but ignore the `cache` parameter.

- [ ] **Step 2: Run full test suite**

```bash
./mvnw test
```

Expected: All 2,183 tests pass.

- [ ] **Step 3: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java
git commit -m "docs: annotate PrefilterOnly unused cache parameter

Add @SuppressWarnings and comment explaining why the cache parameter
is unused in PrefilterOnly (interface contract requirement).

Spec: docs/superpowers/specs/2026-03-15-codebase-cleanup-design.md §3.3"
```

---

### Task 16: Final Verification

**Files:**
- None modified

- [ ] **Step 1: Run full test suite one final time**

```bash
./mvnw test
```

Expected: All 2,183 tests pass.

- [ ] **Step 2: Review git log for the full series**

```bash
git log --oneline -15
```

Verify all commits are present and in the expected order.
