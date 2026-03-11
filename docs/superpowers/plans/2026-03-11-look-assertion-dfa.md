# Look-Assertion DFA Encoding — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable the lazy DFA to handle patterns with look-assertions (`^`, `$`, `\b`, `\B`, line anchors, word boundaries) instead of bailing out to PikeVM only.

**Architecture:** Encode look-behind context (`lookHave`, `lookNeed`, `isFromWord`, `isHalfCrlf`) in the DFA state key. The epsilon closure conditionally follows `State.Look` transitions based on which assertions are currently satisfied. Start states are multiplied by look-behind context (5 variants × anchored/unanchored = 10). When a pattern has no look-assertions, the fast path is identical to today with zero overhead.

**Tech Stack:** Java 21, Maven (`./mvnw`), JUnit 5

**Spec:** `docs/superpowers/specs/2026-03-11-look-assertion-dfa-design.md`

---

## File Structure

| File | Action | Purpose |
|---|---|---|
| `regex-syntax/.../hir/LookKind.java` | Modify | Add `asBit()` method |
| `regex-automata/.../dfa/lazy/LookSet.java` | Create | 32-bit bitset over LookKind |
| `regex-automata/.../dfa/lazy/Start.java` | Create | 5-variant enum for start look-behind context |
| `regex-automata/.../dfa/lazy/StateContent.java` | Modify | Add lookHave, lookNeed, isFromWord, isHalfCrlf |
| `regex-automata/.../nfa/thompson/NFA.java` | Modify | Add lookSetAny field |
| `regex-automata/.../nfa/thompson/Builder.java` | Modify | Compute lookSetAny during build |
| `regex-automata/.../dfa/CharClasses.java` | Modify | Add isWordClass, isLineLF, isLineCR arrays |
| `regex-automata/.../dfa/CharClassBuilder.java` | Modify | Populate look-context arrays |
| `regex-automata/.../dfa/lazy/DFACache.java` | Modify | Expand start states to 10 |
| `regex-automata/.../dfa/lazy/LazyDFA.java` | Modify | Thread lookHave through epsilon closure, computeNextState, start states |

All paths are under `regex-automata/src/main/java/lol/ohai/regex/automata/` unless otherwise noted. Test files are under `regex-automata/src/test/java/lol/ohai/regex/automata/`.

---

## Chunk 1: Foundation Types

### Task 1: LookSet bitset type

**Files:**
- Modify: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LookKind.java`
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LookSet.java`
- Create: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LookSetTest.java`

- [ ] **Step 1: Add `asBit()` to LookKind**

Add this method to `LookKind.java`:

```java
/** Returns the single-bit mask for this look kind: {@code 1 << ordinal()}. */
public int asBit() {
    return 1 << ordinal();
}
```

- [ ] **Step 2: Create LookSet**

Create `LookSet.java`:

```java
package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.syntax.hir.LookKind;

/**
 * Immutable 32-bit bitset over {@link LookKind} values.
 *
 * <p>Each {@code LookKind} maps to one bit via {@link LookKind#asBit()}.
 * All operations return new instances — {@code LookSet} is a value type.</p>
 */
public record LookSet(int bits) {
    public static final LookSet EMPTY = new LookSet(0);

    public static LookSet of(LookKind k) {
        return new LookSet(k.asBit());
    }

    public LookSet insert(LookKind k) {
        return new LookSet(bits | k.asBit());
    }

    public boolean contains(LookKind k) {
        return (bits & k.asBit()) != 0;
    }

    public LookSet union(LookSet other) {
        return new LookSet(bits | other.bits);
    }

    public LookSet intersect(LookSet other) {
        return new LookSet(bits & other.bits);
    }

    public LookSet subtract(LookSet other) {
        return new LookSet(bits & ~other.bits);
    }

    public boolean isEmpty() {
        return bits == 0;
    }

    /** Returns true if this set contains any word boundary assertion. */
    public boolean containsWord() {
        return contains(LookKind.WORD_BOUNDARY_ASCII)
                || contains(LookKind.WORD_BOUNDARY_ASCII_NEGATE)
                || contains(LookKind.WORD_BOUNDARY_UNICODE)
                || contains(LookKind.WORD_BOUNDARY_UNICODE_NEGATE)
                || contains(LookKind.WORD_START_ASCII)
                || contains(LookKind.WORD_END_ASCII)
                || contains(LookKind.WORD_START_HALF_ASCII)
                || contains(LookKind.WORD_END_HALF_ASCII)
                || contains(LookKind.WORD_START_UNICODE)
                || contains(LookKind.WORD_END_UNICODE)
                || contains(LookKind.WORD_START_HALF_UNICODE)
                || contains(LookKind.WORD_END_HALF_UNICODE);
    }

    /** Returns true if this set contains any CRLF-aware anchor. */
    public boolean containsCrlf() {
        return contains(LookKind.START_LINE_CRLF)
                || contains(LookKind.END_LINE_CRLF);
    }
}
```

- [ ] **Step 3: Write LookSet tests**

Create `LookSetTest.java`:

```java
package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.syntax.hir.LookKind;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LookSetTest {

    @Test void emptySetContainsNothing() {
        for (LookKind k : LookKind.values()) {
            assertFalse(LookSet.EMPTY.contains(k));
        }
        assertTrue(LookSet.EMPTY.isEmpty());
    }

    @Test void insertAndContains() {
        LookSet s = LookSet.EMPTY.insert(LookKind.START_TEXT);
        assertTrue(s.contains(LookKind.START_TEXT));
        assertFalse(s.contains(LookKind.END_TEXT));
        assertFalse(s.isEmpty());
    }

    @Test void insertMultiple() {
        LookSet s = LookSet.EMPTY
                .insert(LookKind.START_TEXT)
                .insert(LookKind.END_TEXT)
                .insert(LookKind.WORD_BOUNDARY_ASCII);
        assertTrue(s.contains(LookKind.START_TEXT));
        assertTrue(s.contains(LookKind.END_TEXT));
        assertTrue(s.contains(LookKind.WORD_BOUNDARY_ASCII));
        assertFalse(s.contains(LookKind.START_LINE));
    }

    @Test void union() {
        LookSet a = LookSet.of(LookKind.START_TEXT);
        LookSet b = LookSet.of(LookKind.END_TEXT);
        LookSet u = a.union(b);
        assertTrue(u.contains(LookKind.START_TEXT));
        assertTrue(u.contains(LookKind.END_TEXT));
    }

    @Test void intersect() {
        LookSet a = LookSet.EMPTY.insert(LookKind.START_TEXT).insert(LookKind.END_TEXT);
        LookSet b = LookSet.EMPTY.insert(LookKind.END_TEXT).insert(LookKind.START_LINE);
        LookSet i = a.intersect(b);
        assertFalse(i.contains(LookKind.START_TEXT));
        assertTrue(i.contains(LookKind.END_TEXT));
        assertFalse(i.contains(LookKind.START_LINE));
    }

    @Test void subtract() {
        LookSet a = LookSet.EMPTY.insert(LookKind.START_TEXT).insert(LookKind.END_TEXT);
        LookSet b = LookSet.of(LookKind.END_TEXT);
        LookSet s = a.subtract(b);
        assertTrue(s.contains(LookKind.START_TEXT));
        assertFalse(s.contains(LookKind.END_TEXT));
    }

    @Test void allOrdinalsDistinct() {
        // Verify each LookKind maps to a unique bit
        int combined = 0;
        for (LookKind k : LookKind.values()) {
            int bit = k.asBit();
            assertEquals(0, combined & bit, "Duplicate bit for " + k);
            combined |= bit;
        }
        // All 18 kinds fit in an int
        assertEquals(18, LookKind.values().length);
    }

    @Test void containsWord() {
        assertFalse(LookSet.EMPTY.containsWord());
        assertTrue(LookSet.of(LookKind.WORD_BOUNDARY_ASCII).containsWord());
        assertTrue(LookSet.of(LookKind.WORD_START_UNICODE).containsWord());
        assertFalse(LookSet.of(LookKind.START_TEXT).containsWord());
    }

    @Test void containsCrlf() {
        assertFalse(LookSet.EMPTY.containsCrlf());
        assertTrue(LookSet.of(LookKind.START_LINE_CRLF).containsCrlf());
        assertTrue(LookSet.of(LookKind.END_LINE_CRLF).containsCrlf());
        assertFalse(LookSet.of(LookKind.START_LINE).containsCrlf());
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw test`
Expected: All tests pass, including new LookSetTest.

- [ ] **Step 5: Commit**

```
feat: add LookSet bitset type and LookKind.asBit()
```

---

### Task 2: Start enum

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/Start.java`
- Create: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/StartTest.java`

- [ ] **Step 1: Create Start enum**

Create `Start.java`:

```java
package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.syntax.hir.LookKind;

/**
 * Look-behind context at a search start position.
 *
 * <p>Different start contexts produce different DFA start states because
 * look-behind assertions (e.g., {@code ^}, {@code \b}) may or may not be
 * satisfied depending on what precedes the search start position.</p>
 *
 * <p>The upstream Rust crate has a 6th variant {@code CustomLineTerminator}
 * for configurable line terminators. This will be added if/when the syntax
 * layer supports custom line terminators.</p>
 */
public enum Start {
    /** Position 0: start of input text. */
    TEXT,
    /** After {@code \n}: start of a new line (LF). */
    LINE_LF,
    /** After {@code \r}: possible start of CRLF pair. */
    LINE_CR,
    /** After a word character ({@code [0-9A-Za-z_]}). */
    WORD_BYTE,
    /** After a non-word character (or at a non-zero, non-line-terminator position). */
    NON_WORD_BYTE;

    /** Number of Start variants. Each × anchored/unanchored = one start state slot. */
    public static final int COUNT = values().length;

    /**
     * Classify the search start position to determine look-behind context.
     *
     * @param haystack the full haystack
     * @param pos      the search start position (0-based)
     * @return the Start variant for this position
     */
    public static Start from(char[] haystack, int pos) {
        if (pos == 0) return TEXT;
        char prev = haystack[pos - 1];
        if (prev == '\n') return LINE_LF;
        if (prev == '\r') return LINE_CR;
        if (isWordChar(prev)) return WORD_BYTE;
        return NON_WORD_BYTE;
    }

    /**
     * Returns the initial look-have set for this start variant.
     *
     * @param lookSetAny the pattern's full set of look-assertion kinds
     * @param reverse    true if this is a reverse NFA/DFA
     * @return the initial lookHave for a DFA start state with this context
     */
    public LookSet initialLookHave(LookSet lookSetAny, boolean reverse) {
        LookSet have = LookSet.EMPTY;
        switch (this) {
            case TEXT -> {
                if (lookSetAny.contains(LookKind.START_TEXT))
                    have = have.insert(LookKind.START_TEXT);
                if (lookSetAny.contains(LookKind.START_LINE))
                    have = have.insert(LookKind.START_LINE);
                if (lookSetAny.contains(LookKind.START_LINE_CRLF))
                    have = have.insert(LookKind.START_LINE_CRLF);
                if (lookSetAny.containsWord()) {
                    have = have.insert(LookKind.WORD_START_HALF_ASCII)
                               .insert(LookKind.WORD_START_HALF_UNICODE);
                }
            }
            case LINE_LF -> {
                if (lookSetAny.contains(LookKind.START_LINE))
                    have = have.insert(LookKind.START_LINE);
                if (lookSetAny.contains(LookKind.START_LINE_CRLF))
                    have = have.insert(LookKind.START_LINE_CRLF);
                if (lookSetAny.containsWord()) {
                    have = have.insert(LookKind.WORD_START_HALF_ASCII)
                               .insert(LookKind.WORD_START_HALF_UNICODE);
                }
            }
            case LINE_CR -> {
                if (lookSetAny.contains(LookKind.START_LINE_CRLF))
                    have = have.insert(LookKind.START_LINE_CRLF);
                // \r is not a word char, so WORD_START_HALF_* is set
                if (lookSetAny.containsWord()) {
                    have = have.insert(LookKind.WORD_START_HALF_ASCII)
                               .insert(LookKind.WORD_START_HALF_UNICODE);
                }
            }
            case WORD_BYTE -> {
                // After word char: no start-half assertions, isFromWord handled separately
            }
            case NON_WORD_BYTE -> {
                if (lookSetAny.containsWord()) {
                    have = have.insert(LookKind.WORD_START_HALF_ASCII)
                               .insert(LookKind.WORD_START_HALF_UNICODE);
                }
            }
        }
        return have;
    }

    /** Returns true if this start context has isFromWord = true. */
    public boolean isFromWord() {
        return this == WORD_BYTE;
    }

    /**
     * Returns true if this start context has isHalfCrlf = true.
     * Direction-dependent: LINE_CR in forward mode, LINE_LF in reverse mode.
     */
    public boolean isHalfCrlf(boolean reverse) {
        return reverse ? this == LINE_LF : this == LINE_CR;
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }
}
```

- [ ] **Step 2: Write Start tests**

Create `StartTest.java`:

```java
package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.syntax.hir.LookKind;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StartTest {

    @Test void position0IsText() {
        assertEquals(Start.TEXT, Start.from("hello".toCharArray(), 0));
    }

    @Test void afterNewlineIsLineLF() {
        assertEquals(Start.LINE_LF, Start.from("a\nb".toCharArray(), 2));
    }

    @Test void afterCarriageReturnIsLineCR() {
        assertEquals(Start.LINE_CR, Start.from("a\rb".toCharArray(), 2));
    }

    @Test void afterWordCharIsWordByte() {
        assertEquals(Start.WORD_BYTE, Start.from("ab".toCharArray(), 1));
        assertEquals(Start.WORD_BYTE, Start.from("a9".toCharArray(), 1));
        assertEquals(Start.WORD_BYTE, Start.from("a_".toCharArray(), 1));
    }

    @Test void afterNonWordCharIsNonWordByte() {
        assertEquals(Start.NON_WORD_BYTE, Start.from("a b".toCharArray(), 2));
        assertEquals(Start.NON_WORD_BYTE, Start.from("a.b".toCharArray(), 2));
    }

    @Test void textInitialLookHave() {
        LookSet any = LookSet.EMPTY
                .insert(LookKind.START_TEXT)
                .insert(LookKind.START_LINE)
                .insert(LookKind.WORD_BOUNDARY_ASCII);
        LookSet have = Start.TEXT.initialLookHave(any, false);
        assertTrue(have.contains(LookKind.START_TEXT));
        assertTrue(have.contains(LookKind.START_LINE));
        assertTrue(have.contains(LookKind.WORD_START_HALF_ASCII));
    }

    @Test void wordByteInitialLookHaveIsEmpty() {
        LookSet any = LookSet.of(LookKind.WORD_BOUNDARY_ASCII);
        LookSet have = Start.WORD_BYTE.initialLookHave(any, false);
        // WORD_BYTE has isFromWord=true but no assertions in lookHave
        assertFalse(have.contains(LookKind.WORD_START_HALF_ASCII));
    }

    @Test void isFromWord() {
        assertTrue(Start.WORD_BYTE.isFromWord());
        assertFalse(Start.TEXT.isFromWord());
        assertFalse(Start.LINE_LF.isFromWord());
        assertFalse(Start.NON_WORD_BYTE.isFromWord());
    }

    @Test void isHalfCrlfForward() {
        assertTrue(Start.LINE_CR.isHalfCrlf(false));
        assertFalse(Start.LINE_LF.isHalfCrlf(false));
        assertFalse(Start.TEXT.isHalfCrlf(false));
    }

    @Test void isHalfCrlfReverse() {
        assertTrue(Start.LINE_LF.isHalfCrlf(true));
        assertFalse(Start.LINE_CR.isHalfCrlf(true));
    }

    @Test void countIs5() {
        assertEquals(5, Start.COUNT);
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./mvnw test`
Expected: All tests pass, including new StartTest.

- [ ] **Step 4: Commit**

```
feat: add Start enum for DFA start state look-behind context
```

---

### Task 3: NFA lookSetAny metadata

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/NFA.java`
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Builder.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/BuilderTest.java`

- [ ] **Step 1: Add lookSetAny field to NFA**

Add to `NFA.java`:

```java
import lol.ohai.regex.automata.dfa.lazy.LookSet;
```

Add field: `private final LookSet lookSetAny;`

Add constructor parameter (after `reverse`): `LookSet lookSetAny`

Add accessor:
```java
/** Returns the union of all LookKind values present in State.Look nodes. */
public LookSet lookSetAny() {
    return lookSetAny;
}
```

- [ ] **Step 2: Compute lookSetAny in Builder.build()**

Add to `Builder.java`:

```java
import lol.ohai.regex.automata.dfa.lazy.LookSet;
import lol.ohai.regex.syntax.hir.LookKind;
```

In `build()`, before constructing the NFA, compute:

```java
LookSet lookSetAny = LookSet.EMPTY;
for (State s : states) {
    if (s instanceof State.Look look) {
        lookSetAny = lookSetAny.insert(look.look());
    }
}
```

Pass `lookSetAny` as the last parameter to the NFA constructor.

- [ ] **Step 3: Write test**

Add to `BuilderTest.java`:

```java
@Test void lookSetAnyTracksLookStates() {
    var builder = new Builder();
    int s0 = builder.add(new State.Look(LookKind.START_TEXT, 0));
    int s1 = builder.add(new State.Look(LookKind.WORD_BOUNDARY_ASCII, 0));
    int s2 = builder.add(new State.CharRange('a', 'z', 0));
    int s3 = builder.add(new State.Match(0));
    builder.setStartAnchored(s0);
    builder.setStartUnanchored(s0);
    builder.setGroupInfo(1, 2, List.of((String) null));
    NFA nfa = builder.build();

    assertTrue(nfa.lookSetAny().contains(LookKind.START_TEXT));
    assertTrue(nfa.lookSetAny().contains(LookKind.WORD_BOUNDARY_ASCII));
    assertFalse(nfa.lookSetAny().contains(LookKind.END_TEXT));
}

@Test void lookSetAnyEmptyWhenNoLookStates() {
    var builder = new Builder();
    builder.add(new State.CharRange('a', 'z', 0));
    builder.add(new State.Match(0));
    builder.setStartAnchored(0);
    builder.setStartUnanchored(0);
    builder.setGroupInfo(1, 2, List.of((String) null));
    NFA nfa = builder.build();

    assertTrue(nfa.lookSetAny().isEmpty());
}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw test`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```
feat: add NFA.lookSetAny() to track which look-assertion kinds are present
```

---

### Task 4: StateContent with look-behind context

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/StateContent.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/StateContentTest.java` (create if needed)

- [ ] **Step 1: Add fields to StateContent**

Update `StateContent.java` to add four new fields. The full constructor becomes:

```java
public StateContent(int[] nfaStates, boolean isMatch,
                    boolean isFromWord, boolean isHalfCrlf,
                    int lookHave, int lookNeed) {
    this.nfaStates = nfaStates;
    this.isMatch = isMatch;
    this.isFromWord = isFromWord;
    this.isHalfCrlf = isHalfCrlf;
    this.lookHave = lookNeed == 0 ? 0 : lookHave;  // clear lookHave when no assertions needed
    this.lookNeed = lookNeed;
    this.hashCode = computeHash();
}
```

Add a convenience constructor that delegates with zeros (for backward compatibility):

```java
public StateContent(int[] nfaStates, boolean isMatch) {
    this(nfaStates, isMatch, false, false, 0, 0);
}
```

Add accessors: `isFromWord()`, `isHalfCrlf()`, `lookHave()`, `lookNeed()`.

Update `equals()` to include `isFromWord`, `isHalfCrlf`, and `lookHave` (but NOT `lookNeed`).

Update `hashCode` computation to include the new identity fields.

- [ ] **Step 2: Write StateContent tests**

Create `StateContentTest.java`:

```java
package lol.ohai.regex.automata.dfa.lazy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StateContentTest {

    @Test void sameNfaStatesDifferentLookHaveAreNotEqual() {
        var a = new StateContent(new int[]{1, 2}, false, false, false, 0x01, 0x01);
        var b = new StateContent(new int[]{1, 2}, false, false, false, 0x02, 0x02);
        assertNotEquals(a, b);
    }

    @Test void sameNfaStatesDifferentIsFromWordAreNotEqual() {
        var a = new StateContent(new int[]{1, 2}, false, true, false, 0, 0);
        var b = new StateContent(new int[]{1, 2}, false, false, false, 0, 0);
        assertNotEquals(a, b);
    }

    @Test void lookHaveClearedWhenLookNeedEmpty() {
        var sc = new StateContent(new int[]{1}, false, false, false, 0xFF, 0);
        assertEquals(0, sc.lookHave(), "lookHave should be 0 when lookNeed is 0");
    }

    @Test void lookNeedNotPartOfEquality() {
        var a = new StateContent(new int[]{1, 2}, false, false, false, 0x01, 0x01);
        var b = new StateContent(new int[]{1, 2}, false, false, false, 0x01, 0xFF);
        assertEquals(a, b, "lookNeed should not affect equality");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test void backwardCompatibleConstructor() {
        var sc = new StateContent(new int[]{1, 2}, true);
        assertTrue(sc.isMatch());
        assertFalse(sc.isFromWord());
        assertFalse(sc.isHalfCrlf());
        assertEquals(0, sc.lookHave());
        assertEquals(0, sc.lookNeed());
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./mvnw test`
Expected: All tests pass. Existing code using the 2-arg constructor still works.

- [ ] **Step 4: Commit**

```
feat: add look-behind context fields to StateContent
```

---

### Task 5: CharClasses look-context arrays

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClasses.java`
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`

- [ ] **Step 1: Add flag arrays to CharClasses**

Add to `CharClasses.java`:

```java
private final boolean[] wordClass;  // indexed by class ID, true if class representative is word char
private final boolean[] lineLF;     // true if class representative is \n
private final boolean[] lineCR;     // true if class representative is \r
```

Add a second constructor that accepts these arrays (called by CharClassBuilder when look-context is needed):

```java
CharClasses(byte[][] rows, int[] highIndex, int classCount,
            boolean[] wordClass, boolean[] lineLF, boolean[] lineCR) {
    this(rows, highIndex, classCount);
    // copy into the final fields...
}
```

Actually, to keep it simple, change the existing constructor to always accept the arrays (nullable for the identity case):

```java
CharClasses(byte[][] rows, int[] highIndex, int classCount,
            boolean[] wordClass, boolean[] lineLF, boolean[] lineCR) {
    this.rows = rows;
    this.highIndex = highIndex;
    this.classCount = classCount;
    int alphabetSize = classCount + 1;
    this.stride = Integer.highestOneBit(alphabetSize - 1) << 1;
    this.strideShift = Integer.numberOfTrailingZeros(this.stride);
    this.wordClass = wordClass;
    this.lineLF = lineLF;
    this.lineCR = lineCR;
}
```

Add accessors:

```java
public boolean isWordClass(int classId) {
    return wordClass != null && classId < wordClass.length && wordClass[classId];
}
public boolean isLineLF(int classId) {
    return lineLF != null && classId < lineLF.length && lineLF[classId];
}
public boolean isLineCR(int classId) {
    return lineCR != null && classId < lineCR.length && lineCR[classId];
}
```

Update `identity()` to pass nulls.

Note: Steps 1 and 2 of this task must be applied together (single commit) because changing the `CharClasses` constructor signature will break `CharClassBuilder.build()` and `CharClasses.identity()` until they are updated to pass the new parameters.

- [ ] **Step 2: Build flag arrays in CharClassBuilder**

In `CharClassBuilder.build()`, after creating the `CharClasses`, compute the flag arrays. The approach: for each equivalence class, pick its first char (the boundary) and classify it.

After computing `flatMap` and the rows, add:

```java
boolean[] wordClass = new boolean[classCount];
boolean[] lineLF = new boolean[classCount];
boolean[] lineCR = new boolean[classCount];
for (int cls = 0; cls < classCount && cls < sortedBounds.length - 1; cls++) {
    int representative = sortedBounds[cls]; // first char in this class
    if (representative < 65536) {
        char c = (char) representative;
        wordClass[cls] = isWordChar(c);
        lineLF[cls] = (c == '\n');
        lineCR[cls] = (c == '\r');
    }
}
```

Pass these arrays to the `CharClasses` constructor.

Add a `isWordChar` helper (same as upstream's definition):

```java
private static boolean isWordChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
            || (c >= '0' && c <= '9') || c == '_';
}
```

Note: This uses ASCII word char classification. Unicode word char classification for look-assertions uses the same ASCII definition in the DFA (the upstream also uses `is_word_byte` which is ASCII-only in the DFA state context; Unicode word boundaries use the same ASCII approximation for the `isFromWord` flag).

- [ ] **Step 3: Run tests**

Run: `./mvnw test`
Expected: All tests pass. The new arrays don't affect existing behavior.

- [ ] **Step 4: Commit**

```
feat: add word-char and line-terminator flags to CharClasses
```

---

## Chunk 2: DFA Engine Changes

### Task 6: DFACache start state expansion

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/DFACache.java`

- [ ] **Step 1: Expand start state storage**

Replace the two start state fields with an array:

```java
// Replace:
//   int startUnanchored = UNKNOWN;
//   int startAnchored = UNKNOWN;
// With:
int[] startStates;  // [Start.COUNT * 2] slots: [0..4] unanchored, [5..9] anchored
```

Initialize in constructor: `this.startStates = new int[Start.COUNT * 2];` (all zeros = UNKNOWN).

Add methods:

```java
public int getStartState(Start start, boolean anchored) {
    int idx = anchored ? Start.COUNT + start.ordinal() : start.ordinal();
    return startStates[idx];
}

public void setStartState(Start start, boolean anchored, int sid) {
    int idx = anchored ? Start.COUNT + start.ordinal() : start.ordinal();
    startStates[idx] = sid;
}
```

Update `clear()` to reset: `Arrays.fill(startStates, UNKNOWN);`

Keep backward-compatible access via the old field names for now by making them delegate:

Actually, to minimize churn, keep `startUnanchored` and `startAnchored` as convenience fields that map to `Start.NON_WORD_BYTE` (the default when no look-assertions). LazyDFA will use the new array-based methods when look-assertions are present, and the old fields otherwise. But this adds complexity — better to just switch to the array everywhere and update LazyDFA in Task 7.

For now, add the `startStates` array, the `getStartState`/`setStartState` methods, and update `clear()`. Leave `startUnanchored`/`startAnchored` in place temporarily (LazyDFA still references them; Task 7 will switch).

- [ ] **Step 2: Run tests**

Run: `./mvnw test`
Expected: All tests pass. Old fields still work.

- [ ] **Step 3: Commit**

```
feat: expand DFACache start states to support look-behind context
```

---

### Task 7: LazyDFA epsilon closure and computeNextState with look-assertions

This is the largest task. It modifies the core DFA engine to thread `lookHave` through epsilon closure, compute look-ahead/look-behind during state transitions, and use the expanded start states.

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java`

- [ ] **Step 1: Remove the hasLookStates bail-out**

In `LazyDFA.create()`, remove:

```java
if (hasLookStates(nfa)) return null;
```

Also remove the `hasLookStates()` helper method at the bottom of the file.

Store `nfa.lookSetAny()` in a field for quick access:

```java
private final LookSet lookSetAny;
```

Set it in the constructor: `this.lookSetAny = nfa.lookSetAny();`

- [ ] **Step 2: Thread LookSet through epsilonClosure**

Change `epsilonClosure` signature to accept `LookSet lookHave`:

```java
private boolean epsilonClosure(DFACache cache, int startStateId, LookSet lookHave) {
```

Update the `State.Look` case:

```java
case State.Look look -> {
    if (lookHave.contains(look.look())) {
        stack = ensureStackCapacity(stack, stackTop);
        stack[stackTop++] = look.next();
    }
    // else: assertion not satisfied, don't follow
}
```

Update all callers of `epsilonClosure` to pass `LookSet.EMPTY` for now (will be replaced in subsequent steps). This ensures compilation works.

- [ ] **Step 3: Compute lookNeed when building StateContent**

In `computeNextState`, after collecting NFA states into the sorted array, compute `lookNeed`:

```java
int lookNeed = 0;
for (int nfaStateId : nfaStates) {
    State state = nfa.state(nfaStateId);
    if (state instanceof State.Look look) {
        lookNeed |= look.look().asBit();
    }
    // Also include Union/BinaryUnion states for re-computation correctness
}
```

Wait — per the spec's Section 4a, Union/BinaryUnion states must be included in the NFA state set. Currently, `epsilonClosure` adds them to `nfaStateSet` but they're no-ops in `computeNextState`. After this change, they need to be in the set so re-computation can re-traverse them. The current code already includes them (the `insert` in epsilon closure doesn't filter by type), so this should work. But `computeNextState`'s loop only processes CharRange, Sparse, and Match — Union/BinaryUnion fall through to the `default` case, which is correct.

For `lookNeed`, we also need to check states reachable through the collected NFA states (not just the states themselves). Actually, `lookNeed` should capture Look assertions reachable from the current NFA state set. The simplest approach: during `epsilonClosure`, whenever we encounter a `State.Look` that we do NOT follow (because `lookHave` doesn't contain it), record its kind in a separate set. This set becomes part of `lookNeed`.

Modify `epsilonClosure` to also collect unfollowed look assertions:

```java
// Add a field to DFACache for temporary lookNeed accumulation
int closureLookNeed;  // reset before each closure computation
```

In `epsilonClosure`, when a Look is NOT followed:

```java
case State.Look look -> {
    if (lookHave.contains(look.look())) {
        stack = ensureStackCapacity(stack, stackTop);
        stack[stackTop++] = look.next();
    } else {
        cache.closureLookNeed |= look.look().asBit();
    }
}
```

And when a Look IS followed, also record it (it's still "needed" for this state):

```java
case State.Look look -> {
    cache.closureLookNeed |= look.look().asBit();
    if (lookHave.contains(look.look())) {
        stack = ensureStackCapacity(stack, stackTop);
        stack[stackTop++] = look.next();
    }
}
```

Reset `cache.closureLookNeed = 0` at the start of `computeNextState` and `computeStartState`.

- [ ] **Step 4: Implement two-phase look computation in computeNextState**

This is the core change. After the existing char-transition loop (which iterates source NFA states and follows CharRange/Sparse transitions), add the two-phase look computation.

**Phase 1: Look-ahead on source state.** Before the char-transition loop, compute the updated lookHave based on the input unit:

```java
private LookSet computeLookAhead(StateContent source, int classId, boolean reverse) {
    LookSet have = new LookSet(source.lookHave());

    boolean isEoi = (classId == charClasses.eoiClass());
    boolean isLF = !isEoi && charClasses.isLineLF(classId);
    boolean isCR = !isEoi && charClasses.isLineCR(classId);
    boolean isWord = !isEoi && charClasses.isWordClass(classId);

    // END_LINE: current unit is line terminator
    if (isLF) {
        have = have.insert(LookKind.END_LINE);
    }

    // END_LINE_CRLF: complex CRLF handling (direction-dependent)
    // Upstream: \r fires when (!reverse || !isHalfCrlf), \n fires when (reverse || !isHalfCrlf)
    if (isCR && (!reverse || !source.isHalfCrlf())) {
        have = have.insert(LookKind.END_LINE_CRLF);
    }
    if (isLF && (reverse || !source.isHalfCrlf())) {
        have = have.insert(LookKind.END_LINE_CRLF);
    }

    // END_TEXT: at EOI
    if (isEoi) {
        have = have.insert(LookKind.END_TEXT)
                   .insert(LookKind.END_LINE)
                   .insert(LookKind.END_LINE_CRLF);
    }

    // START_LINE_CRLF: source isHalfCrlf and current is NOT the completing half
    if (source.isHalfCrlf()) {
        boolean completing = reverse ? isCR : isLF;
        if (!completing) {
            have = have.insert(LookKind.START_LINE_CRLF);
        }
    }

    // Word boundaries
    boolean fromWord = source.isFromWord();
    if (fromWord != isWord) {
        have = have.insert(LookKind.WORD_BOUNDARY_ASCII)
                   .insert(LookKind.WORD_BOUNDARY_UNICODE);
    } else {
        have = have.insert(LookKind.WORD_BOUNDARY_ASCII_NEGATE)
                   .insert(LookKind.WORD_BOUNDARY_UNICODE_NEGATE);
    }
    if (!fromWord && isWord) {
        have = have.insert(LookKind.WORD_START_ASCII)
                   .insert(LookKind.WORD_START_UNICODE);
    }
    if (fromWord && !isWord) {
        have = have.insert(LookKind.WORD_END_ASCII)
                   .insert(LookKind.WORD_END_UNICODE);
    }
    if (!isWord) {
        have = have.insert(LookKind.WORD_END_HALF_ASCII)
                   .insert(LookKind.WORD_END_HALF_UNICODE);
    }

    return have;
}
```

**In `computeNextState`**, the look-ahead computation and re-computation check must happen BEFORE the existing EOI early return. The current code has an early return for EOI (lines 291-299) that skips the char-transition loop. This early return must be moved AFTER the look-ahead re-computation, because EOI needs look-ahead assertions (`END_TEXT`, `END_LINE`, `END_LINE_CRLF`) to be resolved before checking for delayed matches.

Restructured flow:

```java
private int computeNextState(DFACache cache, int sourceSid, int classId) {
    int rawSourceId = sourceSid & 0x7FFF_FFFF;
    StateContent sourceContent = cache.getState(rawSourceId);

    cache.nfaStateSet.clear();
    cache.closureLookNeed = 0;
    boolean isMatch = false;

    // Phase 1: Look-ahead resolution on source state
    LookSet lookHave;
    int[] sourceNfaStates = sourceContent.nfaStates();
    if (!lookSetAny.isEmpty()) {
        lookHave = computeLookAhead(sourceContent, classId, nfa.isReverse());

        // Re-computation check
        LookSet newlyTrue = lookHave.subtract(new LookSet(sourceContent.lookHave()))
                                    .intersect(new LookSet(sourceContent.lookNeed()));
        if (!newlyTrue.isEmpty()) {
            // Re-run epsilon closure with updated lookHave
            for (int nfaStateId : sourceNfaStates) {
                epsilonClosure(cache, nfaStateId, lookHave);
            }
            // Use re-computed NFA states for the char-transition loop
            sourceNfaStates = collectSorted(cache);
            cache.nfaStateSet.clear();
            cache.closureLookNeed = 0;
        }
    } else {
        lookHave = LookSet.EMPTY;
    }

    // EOI handling (AFTER look-ahead re-computation)
    if (classId == charClasses.eoiClass()) {
        if (sourceContent.isMatch()) {
            return DFACache.dead(charClasses.stride()) | DFACache.MATCH_FLAG;
        }
        return DFACache.dead(charClasses.stride());
    }

    // Char-transition loop (existing code, using sourceNfaStates)
    for (int nfaStateId : sourceNfaStates) {
        // ... existing CharRange/Sparse/Match handling ...
        // Pass lookHave to epsilonClosure calls
    }
    // ... rest of the method (Phase 2, state allocation) ...
}
```

Pass `lookHave` to all `epsilonClosure` calls in the char-transition loop.

**Phase 2: Look-behind on destination state.** After collecting destination NFA states, compute the destination's look-behind context:

```java
boolean destIsFromWord = false;
boolean destIsHalfCrlf = false;
int destLookHave = 0;

if (!lookSetAny.isEmpty() && nfaStates.length > 0) {
    boolean isLF = classId != charClasses.eoiClass() && charClasses.isLineLF(classId);
    boolean isCR = classId != charClasses.eoiClass() && charClasses.isLineCR(classId);
    boolean isWord = classId != charClasses.eoiClass() && charClasses.isWordClass(classId);
    boolean reverse = nfa.isReverse();

    // START_LINE
    if (isLF) {
        destLookHave |= LookKind.START_LINE.asBit();
    }
    // START_LINE_CRLF
    if ((reverse && isCR) || (!reverse && isLF)) {
        destLookHave |= LookKind.START_LINE_CRLF.asBit();
    }
    // WORD_START_HALF
    if (!isWord) {
        destLookHave |= LookKind.WORD_START_HALF_ASCII.asBit()
                      | LookKind.WORD_START_HALF_UNICODE.asBit();
    }
    destIsFromWord = isWord;
    destIsHalfCrlf = reverse ? isLF : isCR;
}
```

Then create the StateContent with these values:

```java
StateContent content = new StateContent(nfaStates, destHasMatch,
        destIsFromWord, destIsHalfCrlf, destLookHave, cache.closureLookNeed);
```

- [ ] **Step 5: Update start state computation**

Replace `getOrComputeStartState` to use the expanded start states when look-assertions are present:

```java
private int getOrComputeStartState(Input input, DFACache cache) {
    if (lookSetAny.isEmpty()) {
        // Fast path: no look-assertions, use simple anchored/unanchored
        if (input.isAnchored()) {
            if (cache.startAnchored == DFACache.UNKNOWN) {
                cache.startAnchored = computeStartState(nfa.startAnchored(),
                        cache, LookSet.EMPTY, false, false);
            }
            return cache.startAnchored;
        } else {
            if (cache.startUnanchored == DFACache.UNKNOWN) {
                cache.startUnanchored = computeStartState(nfa.startUnanchored(),
                        cache, LookSet.EMPTY, false, false);
            }
            return cache.startUnanchored;
        }
    }

    // Look-assertion path: classify start position
    Start start = Start.from(input.haystack(), input.start());
    int existing = cache.getStartState(start, input.isAnchored());
    if (existing != DFACache.UNKNOWN) return existing;

    int nfaStartId = input.isAnchored() ? nfa.startAnchored() : nfa.startUnanchored();
    LookSet initialLookHave = start.initialLookHave(lookSetAny, nfa.isReverse());
    int sid = computeStartState(nfaStartId, cache, initialLookHave,
            start.isFromWord(), start.isHalfCrlf(nfa.isReverse()));
    cache.setStartState(start, input.isAnchored(), sid);
    return sid;
}
```

Update `computeStartState` to accept and use the look-behind context:

```java
private int computeStartState(int nfaStartId, DFACache cache,
                               LookSet lookHave, boolean isFromWord, boolean isHalfCrlf) {
    cache.nfaStateSet.clear();
    cache.closureLookNeed = 0;
    boolean hasMatch = epsilonClosure(cache, nfaStartId, lookHave);
    int[] nfaStates = collectSorted(cache);
    if (nfaStates.length == 0) return DFACache.dead(charClasses.stride());

    StateContent content = new StateContent(nfaStates, hasMatch,
            isFromWord, isHalfCrlf, lookHave.bits(), cache.closureLookNeed);
    int sid = cache.allocateState(content);
    return sid & 0x7FFF_FFFF;  // start state is never match-flagged (delay)
}
```

- [ ] **Step 6: Add closureLookNeed to DFACache**

Add to `DFACache.java`:

```java
int closureLookNeed;  // accumulates lookNeed during epsilon closure, reset by caller
```

- [ ] **Step 7: Write tests for look-assertion DFA search**

Note: `LazyDFATest.java` already has a `parseHir(String)` helper method. If it does not exist, add one:

```java
private static Hir parseHir(String pattern) throws Exception {
    Ast ast = Parser.parse(pattern, 250);
    return Translator.translate(pattern, ast);
}
```

Add to `LazyDFATest.java`:

```java
@Test void forwardSearchWithStartTextAnchor() throws Exception {
    // Pattern: ^abc — should match only at position 0
    Hir hir = parseHir("^abc");
    NFA nfa = Compiler.compile(hir);
    CharClasses classes = CharClassBuilder.build(nfa);
    LazyDFA dfa = LazyDFA.create(nfa, classes);
    assertNotNull(dfa, "DFA should not bail out for look-assertion patterns");

    DFACache cache = dfa.createCache();
    // Match at start
    SearchResult r1 = dfa.searchFwd(Input.of("abcdef"), cache);
    assertInstanceOf(SearchResult.Match.class, r1);
    assertEquals(3, ((SearchResult.Match) r1).offset());

    // No match when abc not at start
    cache = dfa.createCache();
    SearchResult r2 = dfa.searchFwd(Input.of("xabc"), cache);
    assertInstanceOf(SearchResult.NoMatch.class, r2);
}

@Test void forwardSearchWithWordBoundary() throws Exception {
    // Pattern: \bword\b
    Hir hir = parseHir("\\bword\\b");
    NFA nfa = Compiler.compile(hir);
    CharClasses classes = CharClassBuilder.build(nfa);
    LazyDFA dfa = LazyDFA.create(nfa, classes);
    assertNotNull(dfa);

    DFACache cache = dfa.createCache();
    SearchResult r = dfa.searchFwd(Input.of("a word here"), cache);
    assertInstanceOf(SearchResult.Match.class, r);
    assertEquals(6, ((SearchResult.Match) r).offset());  // "word" ends at 6
}

@Test void forwardSearchWithEndAnchor() throws Exception {
    // Pattern: abc$
    Hir hir = parseHir("abc$");
    NFA nfa = Compiler.compile(hir);
    CharClasses classes = CharClassBuilder.build(nfa);
    LazyDFA dfa = LazyDFA.create(nfa, classes);
    assertNotNull(dfa);

    DFACache cache = dfa.createCache();
    SearchResult r = dfa.searchFwd(Input.of("xyzabc"), cache);
    assertInstanceOf(SearchResult.Match.class, r);
    assertEquals(6, ((SearchResult.Match) r).offset());
}

@Test void noBailOutForLookAssertionPatterns() throws Exception {
    // Previously LazyDFA.create() returned null for these
    for (String pattern : List.of("^abc", "abc$", "\\bfoo\\b", "\\Binner\\B")) {
        Hir hir = parseHir(pattern);
        NFA nfa = Compiler.compile(hir);
        CharClasses classes = CharClassBuilder.build(nfa);
        LazyDFA dfa = LazyDFA.create(nfa, classes);
        assertNotNull(dfa, "DFA should not bail out for: " + pattern);
    }
}

@Test void lookAssertionFreePatternUnchanged() throws Exception {
    // Patterns without look-assertions should work exactly as before
    Hir hir = parseHir("[a-z]+");
    NFA nfa = Compiler.compile(hir);
    assertTrue(nfa.lookSetAny().isEmpty());
    CharClasses classes = CharClassBuilder.build(nfa);
    LazyDFA dfa = LazyDFA.create(nfa, classes);
    assertNotNull(dfa);

    DFACache cache = dfa.createCache();
    SearchResult r = dfa.searchFwd(Input.of("123 hello"), cache);
    assertInstanceOf(SearchResult.Match.class, r);
    assertEquals(9, ((SearchResult.Match) r).offset());
}
```

- [ ] **Step 8: Run tests**

Run: `./mvnw test`
Expected: All tests pass, including the new look-assertion DFA tests AND the full upstream TOML test suite.

- [ ] **Step 9: Commit**

```
feat: encode look-assertions in lazy DFA states

Remove the hasLookStates bail-out from LazyDFA.create(). Thread
LookSet lookHave through epsilon closure to conditionally follow
State.Look transitions. Implement two-phase look computation in
computeNextState: look-ahead resolution on source state, look-behind
on destination state. Expand start states to 10 (5 Start variants ×
anchored/unanchored) for patterns with look-assertions.
```

---

## Chunk 3: Integration and Cleanup

### Task 8: Update gap documentation

**Files:**
- Modify: `docs/architecture/lazy-dfa-gaps.md`

- [ ] **Step 1: Mark look-assertion encoding as completed**

Change the "Look-Around Encoding in DFA States" section from a gap to "Completed":

```markdown
## Look-Around Encoding in DFA States — Completed

**What:** Encode look-around assertion context in DFA states so the DFA can
evaluate `^`, `$`, `\b`, `\B`, and line anchors natively.

**Status:** Implemented. The lazy DFA now encodes look-behind context
(`lookHave`, `lookNeed`, `isFromWord`, `isHalfCrlf`) in the DFA state key.
Patterns with look-assertions no longer bail out to PikeVM.

**Design spec:** `docs/superpowers/specs/2026-03-11-look-assertion-dfa-design.md`
```

- [ ] **Step 2: Run tests**

Run: `./mvnw test`
Expected: All tests pass.

- [ ] **Step 3: Commit**

```
docs: mark look-assertion DFA encoding as completed
```

---

### Task 9: Run benchmarks and update results

**Files:**
- Modify: `BENCHMARKS.md`

- [ ] **Step 1: Build and run benchmarks**

```bash
./mvnw -P bench package -DskipTests
java -jar regex-bench/target/benchmarks.jar "SearchBenchmark" -f 1 -wi 3 -i 5
java -jar regex-bench/target/benchmarks.jar "PathologicalBenchmark" -f 1 -wi 3 -i 5
java -jar regex-bench/target/benchmarks.jar "CompileBenchmark" -f 1 -wi 3 -i 5
```

- [ ] **Step 2: Update BENCHMARKS.md**

Move current results to a collapsed "Previous results" section. Add new results with the post-look-assertion ratios. The `simple` (`^bc(d|e)*$`) and `complex` (`[a-zA-Z_][a-zA-Z0-9_]*\b`) benchmarks should now show DFA participation. The `backtrack` pattern `(a+)+b` should also potentially improve since it may have been bailing out.

Update the Analysis section to reflect that look-assertion encoding is now complete.

- [ ] **Step 3: Commit**

```
docs: update benchmark results after look-assertion DFA encoding
```
