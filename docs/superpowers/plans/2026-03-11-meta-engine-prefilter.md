# Meta Engine + Literal Prefilter Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a meta engine abstraction (`Strategy`) and literal prefix prefilter to dramatically speed up literal-heavy pattern searches.

**Architecture:** The `Strategy` sealed interface replaces direct PikeVM usage in `Regex.java`. A `LiteralExtractor` walks the HIR tree to extract prefix literals. When the entire pattern is a fixed-length literal, `PrefilterOnly` handles search via `indexOf` without any NFA. Otherwise, `Core` uses the prefilter to skip ahead in the haystack before running PikeVM.

**Tech Stack:** Java 21 (sealed interfaces, records, pattern matching), JMH for benchmarks.

**Spec:** `docs/superpowers/specs/2026-03-11-meta-engine-prefilter-design.md`

---

## Chunk 1: Foundation Types

### Task 1: LiteralSeq type

**Files:**
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralSeq.java`
- Test: `regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralSeqTest.java`

- [ ] **Step 1: Write the test**

```java
package lol.ohai.regex.syntax.hir;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LiteralSeqTest {

    @Test
    void noneDoesNotCoverEntirePattern() {
        var none = new LiteralSeq.None();
        assertFalse(none.coversEntirePattern());
    }

    @Test
    void singleCoversEntirePatternWhenFlagged() {
        var single = new LiteralSeq.Single("hello".toCharArray(), true);
        assertTrue(single.coversEntirePattern());
    }

    @Test
    void singleDoesNotCoverEntirePatternWhenNotFlagged() {
        var single = new LiteralSeq.Single("hello".toCharArray(), false);
        assertFalse(single.coversEntirePattern());
    }

    @Test
    void alternationCoversEntirePattern() {
        var alt = new LiteralSeq.Alternation(
                List.of("cat".toCharArray(), "dog".toCharArray()), true);
        assertTrue(alt.coversEntirePattern());
    }

    @Test
    void alternationDoesNotCoverEntirePattern() {
        var alt = new LiteralSeq.Alternation(
                List.of("cat".toCharArray(), "dog".toCharArray()), false);
        assertFalse(alt.coversEntirePattern());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl regex-syntax -Dtest="LiteralSeqTest" -q`
Expected: FAIL — `LiteralSeq` class does not exist yet.

- [ ] **Step 3: Implement LiteralSeq**

```java
package lol.ohai.regex.syntax.hir;

import java.util.List;
import java.util.Objects;
import java.util.Arrays;

/**
 * Result of literal prefix extraction from an HIR tree.
 *
 * <p>Used by the meta engine to decide whether a prefilter can be used
 * and whether the regex engine can be skipped entirely.</p>
 */
public sealed interface LiteralSeq {

    /**
     * Whether this literal sequence covers the entire pattern,
     * meaning no regex engine is needed for matching.
     */
    default boolean coversEntirePattern() {
        return false;
    }

    /** No useful literals could be extracted. */
    record None() implements LiteralSeq {}

    /** A single literal prefix. */
    record Single(char[] literal, boolean entirePattern) implements LiteralSeq {
        public Single {
            Objects.requireNonNull(literal);
        }

        @Override
        public boolean coversEntirePattern() {
            return entirePattern;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Single s
                    && Arrays.equals(literal, s.literal)
                    && entirePattern == s.entirePattern;
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(literal) + Boolean.hashCode(entirePattern);
        }
    }

    /** Multiple alternative literal prefixes. */
    record Alternation(List<char[]> literals, boolean entirePattern) implements LiteralSeq {
        public Alternation {
            Objects.requireNonNull(literals);
            literals = List.copyOf(literals);
        }

        @Override
        public boolean coversEntirePattern() {
            return entirePattern;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl regex-syntax -Dtest="LiteralSeqTest" -q`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralSeq.java \
        regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralSeqTest.java
git commit -m "add LiteralSeq type for prefix extraction results"
```

---

### Task 2: LiteralExtractor

**Files:**
- Create: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralExtractor.java`
- Test: `regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralExtractorTest.java`

- [ ] **Step 1: Write the tests**

```java
package lol.ohai.regex.syntax.hir;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LiteralExtractorTest {

    // -- Single literals --

    @Test
    void extractsLiteralAsEntirePattern() {
        // HIR for "hello"
        var hir = new Hir.Literal("hello".toCharArray());
        var result = LiteralExtractor.extractPrefixes(hir);

        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("hello".toCharArray(), single.literal());
        assertTrue(single.coversEntirePattern());
    }

    @Test
    void extractsSingleCharLiteral() {
        var hir = new Hir.Literal("x".toCharArray());
        var result = LiteralExtractor.extractPrefixes(hir);

        assertInstanceOf(LiteralSeq.Single.class, result);
        assertTrue(result.coversEntirePattern());
    }

    // -- Concat --

    @Test
    void extractsPrefixFromConcat() {
        // HIR for "hello\d+" → Concat([Literal("hello"), Repetition(...)])
        var hir = new Hir.Concat(List.of(
                new Hir.Literal("hello".toCharArray()),
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(
                                new ClassUnicode.ClassUnicodeRange('0', '9')))))
        ));
        var result = LiteralExtractor.extractPrefixes(hir);

        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("hello".toCharArray(), single.literal());
        assertFalse(single.coversEntirePattern());
    }

    @Test
    void mergesAdjacentLiteralsInConcat() {
        // HIR for "abc" when parsed as Concat([Literal("ab"), Literal("c")])
        var hir = new Hir.Concat(List.of(
                new Hir.Literal("ab".toCharArray()),
                new Hir.Literal("c".toCharArray())
        ));
        var result = LiteralExtractor.extractPrefixes(hir);

        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("abc".toCharArray(), single.literal());
        assertTrue(single.coversEntirePattern());
    }

    @Test
    void mergesLeadingLiteralsInConcatWithTrailingNonLiteral() {
        // HIR for "abc\d" → Concat([Literal("ab"), Literal("c"), Class(...)])
        var hir = new Hir.Concat(List.of(
                new Hir.Literal("ab".toCharArray()),
                new Hir.Literal("c".toCharArray()),
                new Hir.Class(new ClassUnicode(List.of(
                        new ClassUnicode.ClassUnicodeRange('0', '9'))))
        ));
        var result = LiteralExtractor.extractPrefixes(hir);

        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("abc".toCharArray(), single.literal());
        assertFalse(single.coversEntirePattern());
    }

    @Test
    void concatStartingWithNonLiteralReturnsNone() {
        // HIR for "\d+hello" → Concat([Repetition(...), Literal("hello")])
        var hir = new Hir.Concat(List.of(
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(
                                new ClassUnicode.ClassUnicodeRange('0', '9'))))),
                new Hir.Literal("hello".toCharArray())
        ));
        var result = LiteralExtractor.extractPrefixes(hir);

        assertInstanceOf(LiteralSeq.None.class, result);
    }

    // -- Capture (transparent) --

    @Test
    void extractsThroughCapture() {
        // HIR for "(?P<word>hello)" → Capture(Literal("hello"))
        var hir = new Hir.Capture(1, "word", new Hir.Literal("hello".toCharArray()));
        var result = LiteralExtractor.extractPrefixes(hir);

        assertInstanceOf(LiteralSeq.Single.class, result);
        var single = (LiteralSeq.Single) result;
        assertArrayEquals("hello".toCharArray(), single.literal());
        assertTrue(single.coversEntirePattern());
    }

    // -- Alternation --

    @Test
    void extractsAlternationOfLiterals() {
        // HIR for "cat|dog|fish"
        var hir = new Hir.Alternation(List.of(
                new Hir.Literal("cat".toCharArray()),
                new Hir.Literal("dog".toCharArray()),
                new Hir.Literal("fish".toCharArray())
        ));
        var result = LiteralExtractor.extractPrefixes(hir);

        assertInstanceOf(LiteralSeq.Alternation.class, result);
        var alt = (LiteralSeq.Alternation) result;
        assertEquals(3, alt.literals().size());
        assertTrue(alt.coversEntirePattern());
    }

    @Test
    void extractsPrefixesFromAlternationOfConcats() {
        // HIR for "hello\d|world\d"
        var digit = new Hir.Class(new ClassUnicode(List.of(
                new ClassUnicode.ClassUnicodeRange('0', '9'))));
        var hir = new Hir.Alternation(List.of(
                new Hir.Concat(List.of(
                        new Hir.Literal("hello".toCharArray()), digit)),
                new Hir.Concat(List.of(
                        new Hir.Literal("world".toCharArray()), digit))
        ));
        var result = LiteralExtractor.extractPrefixes(hir);

        assertInstanceOf(LiteralSeq.Alternation.class, result);
        var alt = (LiteralSeq.Alternation) result;
        assertEquals(2, alt.literals().size());
        assertFalse(alt.coversEntirePattern());
    }

    @Test
    void alternationWithNonLiteralBranchReturnsNone() {
        // HIR for "hello|\d+"
        var hir = new Hir.Alternation(List.of(
                new Hir.Literal("hello".toCharArray()),
                new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                        new Hir.Class(new ClassUnicode(List.of(
                                new ClassUnicode.ClassUnicodeRange('0', '9')))))
        ));
        var result = LiteralExtractor.extractPrefixes(hir);

        assertInstanceOf(LiteralSeq.None.class, result);
    }

    // -- Non-extractable patterns --

    @Test
    void emptyReturnsNone() {
        var result = LiteralExtractor.extractPrefixes(new Hir.Empty());
        assertInstanceOf(LiteralSeq.None.class, result);
    }

    @Test
    void classReturnsNone() {
        var hir = new Hir.Class(new ClassUnicode(List.of(
                new ClassUnicode.ClassUnicodeRange('a', 'z'))));
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.None.class, result);
    }

    @Test
    void lookReturnsNone() {
        var hir = new Hir.Look(LookKind.START);
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.None.class, result);
    }

    @Test
    void repetitionReturnsNone() {
        var hir = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                new Hir.Literal("a".toCharArray()));
        var result = LiteralExtractor.extractPrefixes(hir);
        assertInstanceOf(LiteralSeq.None.class, result);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl regex-syntax -Dtest="LiteralExtractorTest" -q`
Expected: FAIL — `LiteralExtractor` class does not exist yet.

- [ ] **Step 3: Implement LiteralExtractor**

```java
package lol.ohai.regex.syntax.hir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Extracts literal prefix sequences from an HIR tree.
 *
 * <p>Used by the meta engine to build prefilters that skip ahead in the
 * haystack before running the full regex engine.</p>
 */
public final class LiteralExtractor {

    private LiteralExtractor() {}

    /**
     * Extract prefix literal sequences from an HIR tree.
     *
     * @param hir the HIR to extract from
     * @return the extracted literal sequence, or {@link LiteralSeq.None} if no useful prefix
     */
    public static LiteralSeq extractPrefixes(Hir hir) {
        return switch (hir) {
            case Hir.Literal lit -> new LiteralSeq.Single(lit.chars(), true);

            case Hir.Capture cap -> {
                LiteralSeq inner = extractPrefixes(cap.sub());
                yield inner;
            }

            case Hir.Concat concat -> extractFromConcat(concat.subs());

            case Hir.Alternation alt -> extractFromAlternation(alt.subs());

            case Hir.Empty ignored -> new LiteralSeq.None();
            case Hir.Class ignored -> new LiteralSeq.None();
            case Hir.Look ignored -> new LiteralSeq.None();
            case Hir.Repetition ignored -> new LiteralSeq.None();
        };
    }

    private static LiteralSeq extractFromConcat(List<Hir> subs) {
        if (subs.isEmpty()) {
            return new LiteralSeq.None();
        }

        // Collect leading literals, merging adjacent ones
        List<char[]> parts = new ArrayList<>();
        boolean allLiteral = true;

        for (Hir sub : subs) {
            // Unwrap captures (transparent)
            Hir unwrapped = unwrapCaptures(sub);

            if (unwrapped instanceof Hir.Literal lit) {
                parts.add(lit.chars());
            } else {
                allLiteral = false;
                break;
            }
        }

        if (parts.isEmpty()) {
            return new LiteralSeq.None();
        }

        char[] merged = mergeCharArrays(parts);
        return new LiteralSeq.Single(merged, allLiteral);
    }

    private static LiteralSeq extractFromAlternation(List<Hir> subs) {
        if (subs.isEmpty()) {
            return new LiteralSeq.None();
        }

        List<char[]> literals = new ArrayList<>();
        boolean allEntire = true;

        for (Hir sub : subs) {
            LiteralSeq extracted = extractPrefixes(sub);
            switch (extracted) {
                case LiteralSeq.Single single -> {
                    literals.add(single.literal());
                    if (!single.coversEntirePattern()) {
                        allEntire = false;
                    }
                }
                case LiteralSeq.None ignored -> {
                    // One branch has no extractable prefix → give up
                    return new LiteralSeq.None();
                }
                case LiteralSeq.Alternation ignored -> {
                    // Nested alternation — too complex, give up
                    return new LiteralSeq.None();
                }
            }
        }

        return new LiteralSeq.Alternation(literals, allEntire);
    }

    private static Hir unwrapCaptures(Hir hir) {
        while (hir instanceof Hir.Capture cap) {
            hir = cap.sub();
        }
        return hir;
    }

    private static char[] mergeCharArrays(List<char[]> parts) {
        int totalLen = 0;
        for (char[] part : parts) {
            totalLen += part.length;
        }
        char[] result = new char[totalLen];
        int offset = 0;
        for (char[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl regex-syntax -Dtest="LiteralExtractorTest" -q`
Expected: PASS (14 tests)

- [ ] **Step 5: Commit**

```bash
git add regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralExtractor.java \
        regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralExtractorTest.java
git commit -m "add LiteralExtractor for HIR prefix literal extraction"
```

---

## Chunk 2: Prefilter Implementations

### Task 3: Prefilter interface and SingleLiteral

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Prefilter.java`
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/SingleLiteral.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/meta/SingleLiteralTest.java`

- [ ] **Step 1: Write the test**

```java
package lol.ohai.regex.automata.meta;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SingleLiteralTest {

    @Test
    void findsLiteralAtStart() {
        var pf = new SingleLiteral("hello".toCharArray());
        String hay = "hello world";
        assertEquals(0, pf.find(hay, 0, hay.length()));
    }

    @Test
    void findsLiteralInMiddle() {
        var pf = new SingleLiteral("world".toCharArray());
        String hay = "hello world";
        assertEquals(6, pf.find(hay, 0, hay.length()));
    }

    @Test
    void returnsMinusOneWhenNotFound() {
        var pf = new SingleLiteral("xyz".toCharArray());
        String hay = "hello world";
        assertEquals(-1, pf.find(hay, 0, hay.length()));
    }

    @Test
    void respectsFromBound() {
        var pf = new SingleLiteral("hello".toCharArray());
        String hay = "hello hello";
        assertEquals(6, pf.find(hay, 1, hay.length()));
    }

    @Test
    void respectsToBound() {
        var pf = new SingleLiteral("world".toCharArray());
        String hay = "hello world";
        // "world" starts at 6, needs positions 6..10 — but to=8 truncates
        assertEquals(-1, pf.find(hay, 0, 8));
    }

    @Test
    void findsSingleCharLiteral() {
        var pf = new SingleLiteral("x".toCharArray());
        String hay = "abcxdef";
        assertEquals(3, pf.find(hay, 0, hay.length()));
    }

    @Test
    void isExactReturnsTrue() {
        var pf = new SingleLiteral("test".toCharArray());
        assertTrue(pf.isExact());
        assertEquals(4, pf.matchLength());
    }

    @Test
    void findsAtExactEnd() {
        var pf = new SingleLiteral("end".toCharArray());
        String hay = "the end";
        assertEquals(4, pf.find(hay, 0, hay.length()));
    }

    @Test
    void emptyNeedleFindsAtFrom() {
        var pf = new SingleLiteral(new char[0]);
        String hay = "hello";
        assertEquals(0, pf.find(hay, 0, hay.length()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl regex-automata -Dtest="SingleLiteralTest" -q`
Expected: FAIL — classes do not exist yet.

- [ ] **Step 3: Create Prefilter interface**

```java
package lol.ohai.regex.automata.meta;

/**
 * A prefilter that quickly scans a haystack for candidate match positions.
 *
 * <p>Prefilters are used by the meta engine to skip regions of the haystack
 * that cannot possibly match, before running the full regex engine.</p>
 *
 * <p>A prefilter may have false positives (report positions that don't lead
 * to matches) but must never have false negatives (miss positions that do).</p>
 *
 * <p>The {@code find} method takes a {@link String} haystack to leverage
 * {@link String#indexOf} JIT intrinsics without per-call allocation.
 * Callers should construct the String once per search and reuse it.</p>
 */
public interface Prefilter {

    /**
     * Find the next occurrence of this prefilter's pattern in the haystack,
     * starting the search at position {@code from} (inclusive) and ending
     * at position {@code to} (exclusive).
     *
     * @param haystack the string to search
     * @param from     start position (inclusive)
     * @param to       end position (exclusive)
     * @return the start index of the match, or -1 if not found
     */
    int find(String haystack, int from, int to);

    /**
     * Whether this prefilter reports exact match boundaries.
     * When true, the match spans from the returned position to
     * position + {@link #matchLength()}.
     */
    boolean isExact();

    /**
     * The length of the match when {@link #isExact()} is true.
     * Undefined when {@code isExact()} is false.
     */
    int matchLength();
}
```

- [ ] **Step 4: Implement SingleLiteral**

```java
package lol.ohai.regex.automata.meta;

import java.util.Objects;

/**
 * A prefilter that searches for a single literal string using
 * {@link String#indexOf(String, int)}.
 *
 * <p>The haystack is passed as a {@link String} to avoid per-call allocation.
 * The caller (Strategy/Input) constructs the String once per search.</p>
 */
public final class SingleLiteral implements Prefilter {
    private final String needle;
    private final int needleLen;

    public SingleLiteral(char[] needle) {
        Objects.requireNonNull(needle);
        this.needle = new String(needle);
        this.needleLen = needle.length;
    }

    @Override
    public int find(String haystack, int from, int to) {
        int pos = haystack.indexOf(needle, from);
        if (pos < 0 || pos + needleLen > to) {
            return -1;
        }
        return pos;
    }

    @Override
    public boolean isExact() {
        return true;
    }

    @Override
    public int matchLength() {
        return needleLen;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -pl regex-automata -Dtest="SingleLiteralTest" -q`
Expected: PASS (9 tests)

- [ ] **Step 6: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Prefilter.java \
        regex-automata/src/main/java/lol/ohai/regex/automata/meta/SingleLiteral.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/meta/SingleLiteralTest.java
git commit -m "add Prefilter interface and SingleLiteral implementation"
```

---

### Task 4: MultiLiteral prefilter

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/MultiLiteral.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/meta/MultiLiteralTest.java`

- [ ] **Step 1: Write the test**

```java
package lol.ohai.regex.automata.meta;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultiLiteralTest {

    @Test
    void findsEarliestOfMultiple() {
        var pf = new MultiLiteral(new char[][] {
                "world".toCharArray(), "hello".toCharArray()
        });
        String hay = "hello world";
        assertEquals(0, pf.find(hay, 0, hay.length()));
    }

    @Test
    void findsSecondNeedleWhenFirstAbsent() {
        var pf = new MultiLiteral(new char[][] {
                "xyz".toCharArray(), "world".toCharArray()
        });
        String hay = "hello world";
        assertEquals(6, pf.find(hay, 0, hay.length()));
    }

    @Test
    void returnsMinusOneWhenNoneFound() {
        var pf = new MultiLiteral(new char[][] {
                "xyz".toCharArray(), "abc".toCharArray()
        });
        String hay = "hello world";
        assertEquals(-1, pf.find(hay, 0, hay.length()));
    }

    @Test
    void respectsFromBound() {
        var pf = new MultiLiteral(new char[][] {
                "hello".toCharArray(), "world".toCharArray()
        });
        String hay = "hello world hello";
        // Start past first "hello"
        assertEquals(6, pf.find(hay, 1, hay.length()));
    }

    @Test
    void isExactWhenAllSameLength() {
        var pf = new MultiLiteral(new char[][] {
                "cat".toCharArray(), "dog".toCharArray()
        });
        assertTrue(pf.isExact());
        assertEquals(3, pf.matchLength());
    }

    @Test
    void notExactWhenDifferentLengths() {
        var pf = new MultiLiteral(new char[][] {
                "cat".toCharArray(), "elephant".toCharArray()
        });
        assertFalse(pf.isExact());
    }

    @Test
    void singleNeedleBehavesLikeSingleLiteral() {
        var pf = new MultiLiteral(new char[][] {
                "hello".toCharArray()
        });
        String hay = "say hello";
        assertEquals(4, pf.find(hay, 0, hay.length()));
        assertTrue(pf.isExact());
        assertEquals(5, pf.matchLength());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl regex-automata -Dtest="MultiLiteralTest" -q`
Expected: FAIL — `MultiLiteral` class does not exist yet.

- [ ] **Step 3: Implement MultiLiteral**

```java
package lol.ohai.regex.automata.meta;

import java.util.Objects;

/**
 * A prefilter that searches for the earliest occurrence of any of several
 * literal strings. Uses multiple {@link String#indexOf} calls internally.
 *
 * <p>For a small number of needles (typical for regex alternations), this
 * is efficient. For large numbers (>10), consider Aho-Corasick.</p>
 */
public final class MultiLiteral implements Prefilter {
    private final String[] needles;
    private final boolean exact;
    private final int matchLength;

    public MultiLiteral(char[][] needles) {
        Objects.requireNonNull(needles);
        if (needles.length == 0) {
            throw new IllegalArgumentException("needles must not be empty");
        }
        this.needles = new String[needles.length];
        for (int i = 0; i < needles.length; i++) {
            this.needles[i] = new String(needles[i]);
        }

        // Check if all needles have the same length
        int firstLen = needles[0].length;
        boolean allSame = true;
        for (int i = 1; i < needles.length; i++) {
            if (needles[i].length != firstLen) {
                allSame = false;
                break;
            }
        }
        this.exact = allSame;
        this.matchLength = allSame ? firstLen : -1;
    }

    @Override
    public int find(String haystack, int from, int to) {
        int best = -1;
        for (String needle : needles) {
            int pos = haystack.indexOf(needle, from);
            if (pos >= 0 && pos + needle.length() <= to) {
                if (best == -1 || pos < best) {
                    best = pos;
                }
            }
        }
        return best;
    }

    @Override
    public boolean isExact() {
        return exact;
    }

    @Override
    public int matchLength() {
        return matchLength;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl regex-automata -Dtest="MultiLiteralTest" -q`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/MultiLiteral.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/meta/MultiLiteralTest.java
git commit -m "add MultiLiteral prefilter for alternation patterns"
```

---

## Chunk 3: Strategy and Meta Engine

### Task 5: Strategy sealed interface with Core and PrefilterOnly

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/util/Input.java`
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyTest.java`
- Modify: `regex-automata/src/main/java/module-info.java`

**Important:** Before implementing Strategy, add a `haystackStr()` method to `Input.java`. The prefilter needs a `String` view of the haystack for `String.indexOf()` JIT intrinsics, and this must be constructed once per `Input` (not per prefilter call) to avoid repeated allocation.

Add the following field and method to `Input.java`:

```java
// New field (lazily initialized)
private String haystackStr;

// New method
/** Returns a String view of the full haystack. Constructed lazily, cached. */
public String haystackStr() {
    if (haystackStr == null) {
        haystackStr = new String(haystack);
    }
    return haystackStr;
}
```

Also update `withBounds()` to share the cached String:

```java
public Input withBounds(int newStart, int newEnd, boolean newAnchored) {
    Input in = new Input(haystack, newStart, newEnd, newAnchored);
    in.haystackStr = this.haystackStr; // share cached String
    return in;
}
```

- [ ] **Step 1: Write the test**

```java
package lol.ohai.regex.automata.meta;

import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StrategyTest {

    // -- PrefilterOnly tests --

    @Test
    void prefilterOnlyFindsLiteral() {
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.PrefilterOnly(pf);
        var cache = strategy.createCache();
        var input = Input.of("say hello world");

        var caps = strategy.search(input, cache);
        assertNotNull(caps);
        assertEquals(4, caps.start(0));
        assertEquals(9, caps.end(0));
    }

    @Test
    void prefilterOnlyReturnsNullOnNoMatch() {
        var pf = new SingleLiteral("xyz".toCharArray());
        var strategy = new Strategy.PrefilterOnly(pf);
        var cache = strategy.createCache();
        var input = Input.of("hello world");

        assertNull(strategy.search(input, cache));
    }

    @Test
    void prefilterOnlyIsMatch() {
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.PrefilterOnly(pf);
        var cache = strategy.createCache();

        assertTrue(strategy.isMatch(Input.of("say hello"), cache));
        assertFalse(strategy.isMatch(Input.of("say goodbye"), cache));
    }

    @Test
    void prefilterOnlySearchCapturesReturnsGroupZeroOnly() {
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.PrefilterOnly(pf);
        var cache = strategy.createCache();

        var caps = strategy.searchCaptures(Input.of("say hello"), cache);
        assertNotNull(caps);
        assertEquals(1, caps.groupCount());
        assertEquals(4, caps.start(0));
        assertEquals(9, caps.end(0));
    }

    @Test
    void prefilterOnlyRespectsInputBounds() {
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.PrefilterOnly(pf);
        var cache = strategy.createCache();

        // Search only in "world" portion
        var input = Input.of("hello world", 6, 11);
        assertNull(strategy.search(input, cache));
    }

    // -- Core with prefilter tests --

    @Test
    void coreWithPrefilterFindsMatch() {
        // Pattern: "hello\w+" — has prefix "hello" but also needs regex
        var pikeVM = compilePikeVM("hello\\w+");
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.Core(pikeVM, pf);
        var cache = strategy.createCache();

        var caps = strategy.search(Input.of("say helloWorld"), cache);
        assertNotNull(caps);
        assertEquals(4, caps.start(0));
        assertEquals(14, caps.end(0));
    }

    @Test
    void coreWithPrefilterSkipsFalsePositive() {
        // Pattern: "hello world" — prefix "hello" but full match requires " world"
        var pikeVM = compilePikeVM("hello world");
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.Core(pikeVM, pf);
        var cache = strategy.createCache();

        // "hello there hello world" — first "hello" is false positive
        var caps = strategy.search(Input.of("hello there hello world"), cache);
        assertNotNull(caps);
        assertEquals(12, caps.start(0));
        assertEquals(23, caps.end(0));
    }

    @Test
    void coreWithPrefilterReturnsNullOnNoMatch() {
        var pikeVM = compilePikeVM("hello world");
        var pf = new SingleLiteral("hello".toCharArray());
        var strategy = new Strategy.Core(pikeVM, pf);
        var cache = strategy.createCache();

        assertNull(strategy.search(Input.of("hello there"), cache));
    }

    @Test
    void coreWithoutPrefilterFallsThroughToPikeVM() {
        var pikeVM = compilePikeVM("[a-z]+");
        var strategy = new Strategy.Core(pikeVM, null);
        var cache = strategy.createCache();

        var caps = strategy.search(Input.of("123 abc"), cache);
        assertNotNull(caps);
        assertEquals(4, caps.start(0));
        assertEquals(7, caps.end(0));
    }

    @Test
    void coreSearchCapturesPopulatesGroups() {
        var pikeVM = compilePikeVM("(?P<word>[a-z]+)");
        var strategy = new Strategy.Core(pikeVM, null);
        var cache = strategy.createCache();

        var caps = strategy.searchCaptures(Input.of("123 abc"), cache);
        assertNotNull(caps);
        assertEquals(2, caps.groupCount());
        assertEquals(4, caps.start(1));
        assertEquals(7, caps.end(1));
    }

    // -- Helper --

    private static PikeVM compilePikeVM(String pattern) {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        NFA nfa = Compiler.compile(hir);
        return new PikeVM(nfa);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl regex-automata -Dtest="StrategyTest" -q`
Expected: FAIL — `Strategy` class does not exist yet.

- [ ] **Step 3: Implement Strategy**

```java
package lol.ohai.regex.automata.meta;

import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.automata.util.Input;

/**
 * Meta engine strategy. Selects the best search approach based on pattern
 * characteristics and available engines.
 *
 * <p>{@code PrefilterOnly} handles patterns that are entirely fixed-length
 * literals — no regex engine needed, just {@code indexOf}.</p>
 *
 * <p>{@code Core} composes the PikeVM (and future engines) with an optional
 * prefilter. The prefilter scans for prefix candidates, then the engine
 * confirms matches.</p>
 */
public sealed interface Strategy permits Strategy.Core, Strategy.PrefilterOnly {

    /**
     * Create a cache for this strategy's search state.
     */
    Cache createCache();

    /**
     * Check if the pattern matches anywhere in the input.
     */
    boolean isMatch(Input input, Cache cache);

    /**
     * Find the first match. Returns Captures with group 0 populated,
     * or null if no match.
     */
    Captures search(Input input, Cache cache);

    /**
     * Find the first match with all capture groups populated.
     * Returns null if no match.
     */
    Captures searchCaptures(Input input, Cache cache);

    /**
     * Core strategy: PikeVM (and future engines) + optional prefilter.
     */
    record Core(PikeVM pikeVM, Prefilter prefilter) implements Strategy {

        @Override
        public Cache createCache() {
            return new Cache(pikeVM.createCache());
        }

        @Override
        public boolean isMatch(Input input, Cache cache) {
            if (prefilter == null || input.isAnchored()) {
                return pikeVM.isMatch(input, cache.pikeVMCache());
            }
            return prefilterLoop(input, cache,
                    (in, c) -> pikeVM.isMatch(in, c.pikeVMCache())
                            ? new Captures(1) : null) != null;
        }

        @Override
        public Captures search(Input input, Cache cache) {
            if (prefilter == null || input.isAnchored()) {
                return pikeVM.search(input, cache.pikeVMCache());
            }
            return prefilterLoop(input, cache,
                    (in, c) -> pikeVM.search(in, c.pikeVMCache()));
        }

        @Override
        public Captures searchCaptures(Input input, Cache cache) {
            if (prefilter == null || input.isAnchored()) {
                return pikeVM.searchCaptures(input, cache.pikeVMCache());
            }
            return prefilterLoop(input, cache,
                    (in, c) -> pikeVM.searchCaptures(in, c.pikeVMCache()));
        }

        /**
         * Shared prefilter scan loop. Finds prefix candidates, then delegates
         * to the given searcher function. Retries on false positives.
         */
        private Captures prefilterLoop(Input input, Cache cache,
                java.util.function.BiFunction<Input, Cache, Captures> searcher) {
            int start = input.start();
            int end = input.end();
            String haystackStr = input.haystackStr();

            while (start < end) {
                int pos = prefilter.find(haystackStr, start, end);
                if (pos < 0) {
                    return null;
                }
                Input candidateInput = input.withBounds(pos, end, false);
                Captures caps = searcher.apply(candidateInput, cache);
                if (caps != null) {
                    return caps;
                }
                start = pos + 1;
            }
            return null;
        }
    }

    /**
     * Pure prefilter strategy for patterns that are entirely fixed-length literals
     * with no capture groups. No regex engine involved.
     */
    record PrefilterOnly(Prefilter prefilter) implements Strategy {

        public PrefilterOnly {
            if (!prefilter.isExact()) {
                throw new IllegalArgumentException(
                        "PrefilterOnly requires an exact prefilter");
            }
        }

        @Override
        public Cache createCache() {
            return Cache.EMPTY;
        }

        @Override
        public boolean isMatch(Input input, Cache cache) {
            return prefilter.find(input.haystackStr(), input.start(), input.end()) >= 0;
        }

        @Override
        public Captures search(Input input, Cache cache) {
            int pos = prefilter.find(input.haystackStr(), input.start(), input.end());
            if (pos < 0) {
                return null;
            }
            Captures caps = new Captures(1);
            caps.set(0, pos);
            caps.set(1, pos + prefilter.matchLength());
            return caps;
        }

        @Override
        public Captures searchCaptures(Input input, Cache cache) {
            // PrefilterOnly has no capture groups beyond group 0
            return search(input, cache);
        }
    }

    /**
     * Per-search mutable state. Wraps the PikeVM cache (if present).
     */
    record Cache(lol.ohai.regex.automata.nfa.thompson.pikevm.Cache pikeVMCache) {
        static final Cache EMPTY = new Cache(null);
    }
}
```

- [ ] **Step 4: Update module-info.java to export meta package**

Add `exports lol.ohai.regex.automata.meta;` to `regex-automata/src/main/java/module-info.java`:

```java
module lol.ohai.regex.automata {
    requires lol.ohai.regex.syntax;
    exports lol.ohai.regex.automata.nfa.thompson;
    exports lol.ohai.regex.automata.nfa.thompson.pikevm;
    exports lol.ohai.regex.automata.util;
    exports lol.ohai.regex.automata.meta;
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -pl regex-automata -Dtest="StrategyTest" -q`
Expected: PASS (10 tests)

- [ ] **Step 6: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/util/Input.java \
        regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java \
        regex-automata/src/main/java/module-info.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyTest.java
git commit -m "add Strategy sealed interface with Core and PrefilterOnly"
```

---

## Chunk 4: Regex.java Integration

### Task 6: Wire Strategy into Regex.java

**Files:**
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`

This is the core integration task. `Regex.java` switches from holding a `PikeVM` directly to holding a `Strategy`. The public API is unchanged.

- [ ] **Step 1: Run existing tests to establish baseline**

Run: `./mvnw test -pl regex -q`
Expected: PASS — all existing tests pass before modification.

- [ ] **Step 2: Modify Regex.java**

Replace the internals of `Regex.java`. The key changes:
1. Fields: `PikeVM pikeVM` → `Strategy strategy`, `ThreadLocal<Cache>` → `ThreadLocal<Strategy.Cache>`
2. Constructor: takes `Strategy` + `Map<String, Integer>` instead of `PikeVM`
3. `create()`: extract prefixes, build prefilter, select strategy
4. Search methods: delegate to `strategy` instead of `pikeVM`
5. Iterators: `doSearch` delegates to `strategy`

```java
package lol.ohai.regex;

import lol.ohai.regex.automata.meta.MultiLiteral;
import lol.ohai.regex.automata.meta.Prefilter;
import lol.ohai.regex.automata.meta.SingleLiteral;
import lol.ohai.regex.automata.meta.Strategy;
import lol.ohai.regex.automata.nfa.thompson.BuildError;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.LiteralExtractor;
import lol.ohai.regex.syntax.hir.LiteralSeq;
import lol.ohai.regex.syntax.hir.Translator;

import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A compiled regular expression. Thread-safe: can be shared across threads.
 *
 * <p>Compilation pipeline: pattern → AST → HIR → Strategy (prefilter + engine).</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Regex re = Regex.compile("(?P<year>\\d{4})-(?P<month>\\d{2})");
 * re.isMatch("2026-03");               // true
 * re.find("date: 2026-03");            // Optional<Match>
 * re.findAll("2026-03 and 2027-04");   // Stream<Match>
 * re.captures("2026-03")               // Optional<Captures>
 *     .get().group("year");            // Optional<Match> "2026"
 * }</pre>
 */
public final class Regex {
    private final String pattern;
    private final Strategy strategy;
    private final Map<String, Integer> namedGroups;
    private final ThreadLocal<Strategy.Cache> cachePool;

    private Regex(String pattern, Strategy strategy, Map<String, Integer> namedGroups) {
        this.pattern = pattern;
        this.strategy = strategy;
        this.namedGroups = namedGroups;
        this.cachePool = ThreadLocal.withInitial(strategy::createCache);
    }

    /**
     * Compiles the given pattern into a Regex.
     *
     * @param pattern the regex pattern
     * @return the compiled Regex
     * @throws PatternSyntaxException if the pattern is invalid
     */
    public static Regex compile(String pattern) throws PatternSyntaxException {
        return new RegexBuilder().build(pattern);
    }

    /**
     * Returns a new {@link RegexBuilder} for configuring compilation options.
     */
    public static RegexBuilder builder() {
        return new RegexBuilder();
    }

    // Package-private: used by RegexBuilder
    static Regex create(String pattern, int nestLimit) throws PatternSyntaxException {
        try {
            Ast ast = Parser.parse(pattern, nestLimit);
            Hir hir = Translator.translate(pattern, ast);

            // Extract prefix literals for prefilter
            LiteralSeq prefixes = LiteralExtractor.extractPrefixes(hir);
            Prefilter prefilter = buildPrefilter(prefixes);

            // Select strategy
            Strategy strategy;
            Map<String, Integer> namedGroups;

            if (prefilter != null && prefilter.isExact()
                    && prefixes.coversEntirePattern() && !hirHasCaptures(hir)) {
                strategy = new Strategy.PrefilterOnly(prefilter);
                namedGroups = Collections.emptyMap();
            } else {
                NFA nfa = Compiler.compile(hir);
                PikeVM pikeVM = new PikeVM(nfa);
                strategy = new Strategy.Core(pikeVM, prefilter);
                namedGroups = buildNamedGroupMap(nfa);
            }

            return new Regex(pattern, strategy, namedGroups);
        } catch (lol.ohai.regex.syntax.ast.Error | lol.ohai.regex.syntax.hir.Error e) {
            throw new PatternSyntaxException(pattern, e);
        } catch (BuildError e) {
            throw new PatternSyntaxException(pattern, e);
        }
    }

    /** Returns the original pattern string. */
    public String pattern() {
        return pattern;
    }

    /**
     * Returns true if the pattern matches anywhere in the input.
     */
    public boolean isMatch(CharSequence text) {
        Strategy.Cache cache = cachePool.get();
        Input input = Input.of(text);
        return strategy.isMatch(input, cache);
    }

    /**
     * Finds the first match in the input.
     *
     * @return the match, or empty if no match
     */
    public Optional<Match> find(CharSequence text) {
        Strategy.Cache cache = cachePool.get();
        Input input = Input.of(text);
        lol.ohai.regex.automata.util.Captures caps = strategy.search(input, cache);
        if (caps == null) {
            return Optional.empty();
        }
        return Optional.of(toMatch(text, caps, 0));
    }

    /**
     * Returns a stream of all non-overlapping matches in the input.
     */
    public Stream<Match> findAll(CharSequence text) {
        Iterator<Match> iter = new MatchIterator(text);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iter,
                        Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    /**
     * Finds the first match with all capture groups populated.
     *
     * @return the captures, or empty if no match
     */
    public Optional<Captures> captures(CharSequence text) {
        Strategy.Cache cache = cachePool.get();
        Input input = Input.of(text);
        lol.ohai.regex.automata.util.Captures caps = strategy.searchCaptures(input, cache);
        if (caps == null) {
            return Optional.empty();
        }
        return Optional.of(toCaptures(text, caps));
    }

    /**
     * Returns a stream of all non-overlapping capture results.
     */
    public Stream<Captures> capturesAll(CharSequence text) {
        Iterator<Captures> iter = new CapturesIterator(text);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iter,
                        Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    @Override
    public String toString() {
        return pattern;
    }

    // -- Internal helpers --

    private static Prefilter buildPrefilter(LiteralSeq prefixes) {
        return switch (prefixes) {
            case LiteralSeq.None ignored -> null;
            case LiteralSeq.Single single -> new SingleLiteral(single.literal());
            case LiteralSeq.Alternation alt -> new MultiLiteral(
                    alt.literals().toArray(char[][]::new));
        };
    }

    private static boolean hirHasCaptures(Hir hir) {
        return switch (hir) {
            case Hir.Capture ignored -> true;
            case Hir.Concat concat -> concat.subs().stream().anyMatch(Regex::hirHasCaptures);
            case Hir.Alternation alt -> alt.subs().stream().anyMatch(Regex::hirHasCaptures);
            case Hir.Repetition rep -> hirHasCaptures(rep.sub());
            default -> false;
        };
    }

    private Match toMatch(CharSequence text,
                          lol.ohai.regex.automata.util.Captures caps, int group) {
        int start = caps.start(group);
        int end = caps.end(group);
        return new Match(start, end, text.subSequence(start, end).toString());
    }

    private Captures toCaptures(CharSequence text,
                                lol.ohai.regex.automata.util.Captures caps) {
        int groupCount = caps.groupCount();
        List<Optional<Match>> groups = new ArrayList<>(groupCount);
        Match overall = null;
        for (int i = 0; i < groupCount; i++) {
            if (caps.isMatched(i)) {
                Match m = toMatch(text, caps, i);
                groups.add(Optional.of(m));
                if (i == 0) {
                    overall = m;
                }
            } else {
                groups.add(Optional.empty());
            }
        }
        return new Captures(overall, Collections.unmodifiableList(groups), namedGroups);
    }

    private static Map<String, Integer> buildNamedGroupMap(NFA nfa) {
        List<String> names = nfa.groupNames();
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name != null) {
                map.put(name, i);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Base class for iterating over successive non-overlapping matches.
     * Handles the tricky case of zero-width matches (must advance by at least one char).
     */
    private abstract class BaseFindIterator<T> implements Iterator<T> {
        final CharSequence text;
        private int searchCharStart = 0;
        private int lastMatchCharEnd = -1; // -1 = no match yet
        private T nextResult;
        private boolean done = false;

        BaseFindIterator(CharSequence text) {
            this.text = text;
        }

        abstract lol.ohai.regex.automata.util.Captures doSearch(
                Input input, Strategy.Cache cache);
        abstract T toResult(CharSequence text,
                            lol.ohai.regex.automata.util.Captures caps);

        @Override
        public boolean hasNext() {
            if (nextResult != null) return true;
            if (done) return false;
            advance();
            return nextResult != null;
        }

        @Override
        public T next() {
            if (!hasNext()) throw new NoSuchElementException();
            T result = nextResult;
            nextResult = null;
            return result;
        }

        private void advance() {
            Strategy.Cache cache = cachePool.get();

            while (!done) {
                if (searchCharStart > text.length()) {
                    done = true;
                    return;
                }

                Input input = Input.of(text, searchCharStart, text.length());
                lol.ohai.regex.automata.util.Captures caps = doSearch(input, cache);

                if (caps == null) {
                    done = true;
                    return;
                }

                int charStart = caps.start(0);
                int charEnd = caps.end(0);

                // After a non-empty match ending at P, if we find an empty match also
                // at P, skip it and advance by one codepoint. This matches the upstream
                // Rust regex crate's iteration semantics.
                if (charStart == charEnd && charEnd == lastMatchCharEnd) {
                    if (charEnd < text.length()) {
                        searchCharStart = charEnd + Character.charCount(
                                Character.codePointAt(text, charEnd));
                    } else {
                        searchCharStart = charEnd + 1; // past end → done on next iteration
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

    private final class MatchIterator extends BaseFindIterator<Match> {
        MatchIterator(CharSequence text) { super(text); }

        @Override
        lol.ohai.regex.automata.util.Captures doSearch(
                Input input, Strategy.Cache cache) {
            return strategy.search(input, cache);
        }

        @Override
        Match toResult(CharSequence text,
                       lol.ohai.regex.automata.util.Captures caps) {
            return toMatch(text, caps, 0);
        }
    }

    private final class CapturesIterator extends BaseFindIterator<Captures> {
        CapturesIterator(CharSequence text) { super(text); }

        @Override
        lol.ohai.regex.automata.util.Captures doSearch(
                Input input, Strategy.Cache cache) {
            return strategy.searchCaptures(input, cache);
        }

        @Override
        Captures toResult(CharSequence text,
                          lol.ohai.regex.automata.util.Captures caps) {
            return toCaptures(text, caps);
        }
    }
}
```

- [ ] **Step 3: Run all tests to verify nothing broke**

Run: `./mvnw test -q`
Expected: PASS — all existing tests (including `UpstreamSuiteTest`, `PikeVMSuiteTest`) pass unchanged. The public API has not changed.

- [ ] **Step 4: Commit**

```bash
git add regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "wire Strategy meta engine into Regex, replacing direct PikeVM usage"
```

---

## Chunk 5: Integration Tests and Verification

### Task 7: Strategy selection integration tests

**Files:**
- Create: `regex/src/test/java/lol/ohai/regex/MetaEngineTest.java`

These tests verify the correct strategy is selected and the public API works end-to-end with both strategies.

- [ ] **Step 1: Write the test**

```java
package lol.ohai.regex;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetaEngineTest {

    // -- PrefilterOnly patterns (entire pattern is a fixed-length literal, no captures) --

    @Test
    void pureLiteralUsesPrefilterOnly() {
        Regex re = Regex.compile("Sherlock Holmes");
        assertTrue(re.isMatch("Mr. Sherlock Holmes of Baker Street"));
        assertFalse(re.isMatch("Dr. Watson"));
    }

    @Test
    void pureLiteralFind() {
        Regex re = Regex.compile("hello");
        var m = re.find("say hello world");
        assertTrue(m.isPresent());
        assertEquals(4, m.get().start());
        assertEquals(9, m.get().end());
        assertEquals("hello", m.get().text());
    }

    @Test
    void pureLiteralFindAll() {
        Regex re = Regex.compile("ab");
        List<Match> matches = re.findAll("ab cd ab ef ab").toList();
        assertEquals(3, matches.size());
        assertEquals(0, matches.get(0).start());
        assertEquals(6, matches.get(1).start());
        assertEquals(12, matches.get(2).start());
    }

    @Test
    void pureLiteralNoMatch() {
        Regex re = Regex.compile("xyz");
        assertFalse(re.isMatch("hello world"));
        assertTrue(re.find("hello world").isEmpty());
    }

    @Test
    void pureLiteralCaptures() {
        // Pure literal → PrefilterOnly → captures should still work (group 0)
        Regex re = Regex.compile("hello");
        var caps = re.captures("say hello");
        assertTrue(caps.isPresent());
        assertEquals("hello", caps.get().overall().text());
        assertEquals(1, caps.get().groupCount());
    }

    // -- Patterns with captures go to Core (even if literal) --

    @Test
    void literalWithCaptureUsesCoreStrategy() {
        Regex re = Regex.compile("(?P<word>hello)");
        var caps = re.captures("say hello world");
        assertTrue(caps.isPresent());
        assertEquals("hello", caps.get().group("word").get().text());
    }

    // -- Alternation of same-length literals → PrefilterOnly --

    @Test
    void alternationOfSameLengthLiterals() {
        Regex re = Regex.compile("cat|dog");
        assertTrue(re.isMatch("I have a cat"));
        assertTrue(re.isMatch("I have a dog"));
        assertFalse(re.isMatch("I have a fish"));

        var m = re.find("I have a dog and a cat");
        assertTrue(m.isPresent());
        assertEquals("dog", m.get().text());
    }

    // -- Alternation of different-length literals → Core with prefilter --

    @Test
    void alternationOfDifferentLengthLiterals() {
        Regex re = Regex.compile("cat|elephant");
        assertTrue(re.isMatch("I see a cat"));
        assertTrue(re.isMatch("I see an elephant"));

        var matches = re.findAll("cat and elephant").toList();
        assertEquals(2, matches.size());
        assertEquals("cat", matches.get(0).text());
        assertEquals("elephant", matches.get(1).text());
    }

    // -- Core with prefilter (literal prefix + regex suffix) --

    @Test
    void regexWithLiteralPrefixUsesPrefilter() {
        Regex re = Regex.compile("hello\\w+");
        var m = re.find("say helloWorld");
        assertTrue(m.isPresent());
        assertEquals("helloWorld", m.get().text());
    }

    @Test
    void regexWithLiteralPrefixFindAll() {
        Regex re = Regex.compile("hello\\d+");
        List<Match> matches = re.findAll("hello123 world hello456").toList();
        assertEquals(2, matches.size());
        assertEquals("hello123", matches.get(0).text());
        assertEquals("hello456", matches.get(1).text());
    }

    // -- Core without prefilter (no literal prefix) --

    @Test
    void regexWithoutLiteralPrefix() {
        Regex re = Regex.compile("[A-Z][a-z]+");
        var m = re.find("say Hello World");
        assertTrue(m.isPresent());
        assertEquals("Hello", m.get().text());
    }

    // -- Edge cases --

    @Test
    void singleCharLiteral() {
        Regex re = Regex.compile("x");
        var m = re.find("abcxdef");
        assertTrue(m.isPresent());
        assertEquals(3, m.get().start());
    }

    @Test
    void emptyPatternStillWorks() {
        // Empty pattern matches at every position
        Regex re = Regex.compile("");
        assertTrue(re.isMatch("hello"));
        var m = re.find("hello");
        assertTrue(m.isPresent());
        assertEquals(0, m.get().start());
        assertEquals(0, m.get().end());
    }

    @Test
    void capturesAllWithPrefilterPattern() {
        Regex re = Regex.compile("(?P<name>[A-Z][a-z]+) Holmes");
        var caps = re.capturesAll("Sherlock Holmes and Mycroft Holmes").toList();
        assertEquals(2, caps.size());
        assertEquals("Sherlock", caps.get(0).group("name").get().text());
        assertEquals("Mycroft", caps.get(1).group("name").get().text());
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `./mvnw test -pl regex -Dtest="MetaEngineTest" -q`
Expected: PASS

- [ ] **Step 3: Run the full test suite**

Run: `./mvnw test -q`
Expected: PASS — all 1,954+ tests pass.

- [ ] **Step 4: Commit**

```bash
git add regex/src/test/java/lol/ohai/regex/MetaEngineTest.java
git commit -m "add MetaEngineTest for strategy selection and prefilter integration"
```

---

### Task 8: Run benchmarks and verify improvement

**Files:**
- None (verification only)

- [ ] **Step 1: Build benchmark JAR**

Run: `./mvnw -P bench package -DskipTests -q`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run search benchmarks**

Run: `java -jar regex-bench/target/benchmarks.jar "SearchBenchmark" -f 1 -wi 2 -i 3`
Expected: `literalOhai` should show dramatic improvement (from ~14 ops/s to hundreds or thousands). `alternationOhai` should also improve significantly. Other benchmarks (`charClass`, `captures`, `unicodeWord`) should be unchanged.

- [ ] **Step 3: Run compile benchmarks**

Run: `java -jar regex-bench/target/benchmarks.jar "CompileBenchmark.simpleOhai" -f 1 -wi 2 -i 3`
Expected: `simpleOhai` may show improvement for pure-literal patterns that now skip NFA compilation.

- [ ] **Step 4: Update BENCHMARKS.md with new results**

Update the results table in `BENCHMARKS.md` with the new numbers. Add a new section header for the post-meta-engine results.

- [ ] **Step 5: Commit**

```bash
git add BENCHMARKS.md
git commit -m "update benchmark results after meta engine + prefilter"
```
