# Suffix/Inner Literal Prefilter Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `ReverseSuffix` and `ReverseInner` strategy variants that extract suffix/inner literals to skip the haystack via `indexOf`, then reverse-DFA + forward-DFA + PikeVM to verify matches — closing the 33x captures benchmark gap.

**Architecture:** Two new `Strategy` sealed interface variants. `ReverseSuffix` extracts a suffix literal, finds it with `indexOf`, reverse-DFA backward for match start, forward-DFA + PikeVM to verify. `ReverseInner` does the same for inner literals but uses a separate prefix-only reverse DFA. Strategy selection in `Regex.create()` prefers existing prefix prefilter → ReverseSuffix → ReverseInner → Core.

**Tech Stack:** Java 21, existing `LazyDFA.searchRev()`, `String.indexOf()` JIT intrinsic, `BoundedBacktracker`

**Spec:** `docs/superpowers/specs/2026-03-12-suffix-inner-prefilter-design.md`

**Regression prevention (HARD REQUIREMENT):**
- Run `./mvnw test` (full suite, all modules) after **every task**, not just the modified module
- Cross-reference upstream Rust code before implementing each search loop — verify against `upstream/regex/regex-automata/src/meta/strategy.rs` (ReverseSuffix at lines 1115-1491) and `upstream/regex/regex-automata/src/meta/reverse_inner.rs`
- Any new test failure is **stop-and-fix-immediately** — do not defer
- When in doubt about semantics, read the upstream source

---

## File Structure

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralSeq.java` | Add `exact` flag to `Single` and `Alternation` |
| Modify | `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralExtractor.java` | Add `extractSuffixes()`, `extractInner()`, `InnerLiteral` record |
| Modify | `regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralExtractorTest.java` | Tests for suffix/inner extraction + exactness |
| Modify | `regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralSeqTest.java` | Update `Single`/`Alternation` constructor calls (3-arg) |
| Modify | `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java` | Add `ReverseSuffix`, `ReverseInner`, update `Cache`, extract `captureEngine` |
| Create | `regex-automata/src/test/java/lol/ohai/regex/automata/meta/ReverseSuffixTest.java` | Unit tests for ReverseSuffix strategy |
| Create | `regex-automata/src/test/java/lol/ohai/regex/automata/meta/ReverseInnerTest.java` | Unit tests for ReverseInner strategy |
| Modify | `regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyTest.java` | Update `Cache` constructor calls (5th arg) |
| Modify | `regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyLazyDFATest.java` | Update `Cache` constructor calls (5th arg) |
| Modify | `regex/src/main/java/lol/ohai/regex/Regex.java` | Strategy selection: suffix/inner prefilter wiring |

---

## Chunk 1: Literal Extraction Changes

### Task 1: Add Exactness Tracking to LiteralSeq

**Files:**
- Modify: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralSeq.java`
- Modify: `regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralExtractorTest.java`
- Modify: `regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralSeqTest.java`

**Context:** `LiteralSeq` is a sealed interface with three variants: `None`, `Single(char[] literal, boolean entirePattern)`, and `Alternation(List<char[]> literals, boolean entirePattern)`. We need to add an `exact` boolean to `Single` and `Alternation`. A literal is "exact" if it marks a definite pattern boundary (prefix position 0, or final position in a concat). Inexact literals appear somewhere inside the match.

- [ ] **Step 1: Write failing tests for exactness**

In `LiteralExtractorTest.java`, add tests that check the `exact()` method on extracted literals. These will fail because `exact()` doesn't exist yet.

```java
@Test
void prefixLiteralsAreExact() {
    // A prefix literal from position 0 of a concat is exact
    Hir hir = new Hir.Concat(List.of(
            new Hir.Literal("hello".toCharArray()),
            new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                    new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))))
    ));
    LiteralSeq result = LiteralExtractor.extractPrefixes(hir);
    assertInstanceOf(LiteralSeq.Single.class, result);
    LiteralSeq.Single single = (LiteralSeq.Single) result;
    assertTrue(single.exact());
    assertFalse(single.coversEntirePattern());
}

@Test
void entirePatternLiteralsAreExact() {
    Hir hir = new Hir.Literal("hello".toCharArray());
    LiteralSeq result = LiteralExtractor.extractPrefixes(hir);
    assertInstanceOf(LiteralSeq.Single.class, result);
    LiteralSeq.Single single = (LiteralSeq.Single) result;
    assertTrue(single.exact());
    assertTrue(single.coversEntirePattern());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test`
Expected: Compilation failure — `exact()` method not found on `LiteralSeq.Single`

- [ ] **Step 3: Update LiteralSeq to add exact flag**

In `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralSeq.java`:

1. Add `exact()` default method to the interface (returns `false` for `None`):

```java
/** Whether this literal marks a definite pattern boundary. */
default boolean exact() {
    return false;
}
```

2. Change `Single` record from `Single(char[] literal, boolean entirePattern)` to `Single(char[] literal, boolean exact, boolean entirePattern)`:

```java
record Single(char[] literal, boolean exact, boolean entirePattern) implements LiteralSeq {
    public Single {
        Objects.requireNonNull(literal);
    }

    @Override
    public boolean exact() {
        return exact;
    }

    @Override
    public boolean coversEntirePattern() {
        return exact && entirePattern;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Single s
                && Arrays.equals(literal, s.literal)
                && exact == s.exact
                && entirePattern == s.entirePattern;
    }

    @Override
    public int hashCode() {
        int h = 31 * Arrays.hashCode(literal) + Boolean.hashCode(exact);
        return 31 * h + Boolean.hashCode(entirePattern);
    }
}
```

3. Change `Alternation` record similarly:

```java
record Alternation(List<char[]> literals, boolean exact, boolean entirePattern) implements LiteralSeq {
    public Alternation {
        Objects.requireNonNull(literals);
        literals = List.copyOf(literals);
    }

    @Override
    public boolean exact() {
        return exact;
    }

    @Override
    public boolean coversEntirePattern() {
        return exact && entirePattern;
    }
}
```

- [ ] **Step 4: Update LiteralExtractor construction sites**

In `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralExtractor.java`, update all three sites:

Line 18: `new LiteralSeq.Single(lit.chars(), true)` → `new LiteralSeq.Single(lit.chars(), true, true)`

Line 51: `new LiteralSeq.Single(merged, allLiteral)` → `new LiteralSeq.Single(merged, true, allLiteral)`

Line 77: `new LiteralSeq.Alternation(literals, allEntire)` → `new LiteralSeq.Alternation(literals, true, allEntire)`

- [ ] **Step 5: Update existing test constructor calls**

In `LiteralSeqTest.java`, update all 4 direct constructor calls to use 3 args (insert `true` for `exact` as the second parameter):

- `new LiteralSeq.Single("hello".toCharArray(), true)` → `new LiteralSeq.Single("hello".toCharArray(), true, true)`
- `new LiteralSeq.Single("hello".toCharArray(), false)` → `new LiteralSeq.Single("hello".toCharArray(), true, false)`
- `new LiteralSeq.Alternation(..., true)` → `new LiteralSeq.Alternation(..., true, true)`
- `new LiteralSeq.Alternation(..., false)` → `new LiteralSeq.Alternation(..., true, false)`

In `LiteralExtractorTest.java`, check if any tests directly construct `LiteralSeq` records (they likely don't — they call `extractPrefixes()` and assert on the result). If any do, update them too.

- [ ] **Step 6: Run full test suite**

Run: `./mvnw test`
Expected: ALL tests pass. The `coversEntirePattern()` semantics are preserved because for prefixes `exact` is always `true`, so `exact && entirePattern == entirePattern`.

- [ ] **Step 7: Commit**

```bash
git add regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralSeq.java \
        regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralExtractor.java \
        regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralExtractorTest.java \
        regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralSeqTest.java
git commit -m "feat: add exactness tracking to LiteralSeq for suffix/inner prefilters"
```

---

### Task 2: Add Suffix Literal Extraction

**Files:**
- Modify: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralExtractor.java`
- Modify: `regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralExtractorTest.java`

**Context:** `extractSuffixes()` mirrors `extractPrefixes()` but iterates concat children from the end. Suffix literals from the final position of a concat are exact. The upstream reference is `regex-syntax/src/hir/literal.rs` with `ExtractKind::Suffix`.

- [ ] **Step 1: Write failing tests for suffix extraction**

Add to `LiteralExtractorTest.java`:

```java
// --- Suffix extraction tests ---

@Test
void suffixFromPureLiteral() {
    Hir hir = new Hir.Literal("hello".toCharArray());
    LiteralSeq result = LiteralExtractor.extractSuffixes(hir);
    assertInstanceOf(LiteralSeq.Single.class, result);
    LiteralSeq.Single single = (LiteralSeq.Single) result;
    assertArrayEquals("hello".toCharArray(), single.literal());
    assertTrue(single.exact());
    assertTrue(single.coversEntirePattern());
}

@Test
void suffixFromConcatTrailingLiteral() {
    // \w+Holmes → suffix "Holmes", exact
    Hir hir = new Hir.Concat(List.of(
            new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                    new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z'))))),
            new Hir.Literal("Holmes".toCharArray())
    ));
    LiteralSeq result = LiteralExtractor.extractSuffixes(hir);
    assertInstanceOf(LiteralSeq.Single.class, result);
    LiteralSeq.Single single = (LiteralSeq.Single) result;
    assertArrayEquals("Holmes".toCharArray(), single.literal());
    assertTrue(single.exact());
    assertFalse(single.coversEntirePattern());
}

@Test
void suffixFromConcatMultipleTrailingLiterals() {
    // \w+ foo bar → suffix "foobar", exact (merged trailing literals)
    Hir hir = new Hir.Concat(List.of(
            new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                    new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z'))))),
            new Hir.Literal("foo".toCharArray()),
            new Hir.Literal("bar".toCharArray())
    ));
    LiteralSeq result = LiteralExtractor.extractSuffixes(hir);
    assertInstanceOf(LiteralSeq.Single.class, result);
    LiteralSeq.Single single = (LiteralSeq.Single) result;
    assertArrayEquals("foobar".toCharArray(), single.literal());
    assertTrue(single.exact());
    assertFalse(single.coversEntirePattern());
}

@Test
void suffixFromConcatNoTrailingLiteral() {
    // hello\w+ → no suffix (ends with repetition)
    Hir hir = new Hir.Concat(List.of(
            new Hir.Literal("hello".toCharArray()),
            new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                    new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))))
    ));
    LiteralSeq result = LiteralExtractor.extractSuffixes(hir);
    assertInstanceOf(LiteralSeq.None.class, result);
}

@Test
void suffixFromAlternation() {
    // \w+foo|\w+bar → suffix alternation ["foo", "bar"]
    Hir hir = new Hir.Alternation(List.of(
            new Hir.Concat(List.of(
                    new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                            new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z'))))),
                    new Hir.Literal("foo".toCharArray())
            )),
            new Hir.Concat(List.of(
                    new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                            new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z'))))),
                    new Hir.Literal("bar".toCharArray())
            ))
    ));
    LiteralSeq result = LiteralExtractor.extractSuffixes(hir);
    assertInstanceOf(LiteralSeq.Alternation.class, result);
    LiteralSeq.Alternation alt = (LiteralSeq.Alternation) result;
    assertEquals(2, alt.literals().size());
    assertArrayEquals("foo".toCharArray(), alt.literals().get(0));
    assertArrayEquals("bar".toCharArray(), alt.literals().get(1));
    assertTrue(alt.exact());
}

@Test
void suffixThroughCapture() {
    // \w+(Holmes) → suffix "Holmes", exact
    Hir hir = new Hir.Concat(List.of(
            new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                    new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z'))))),
            new Hir.Capture(1, null,
                    new Hir.Literal("Holmes".toCharArray()))
    ));
    LiteralSeq result = LiteralExtractor.extractSuffixes(hir);
    assertInstanceOf(LiteralSeq.Single.class, result);
    assertArrayEquals("Holmes".toCharArray(), ((LiteralSeq.Single) result).literal());
}

@Test
void suffixFromRepetition() {
    // a+ → no suffix (repetition, not a fixed literal)
    Hir hir = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
            new Hir.Literal("a".toCharArray()));
    LiteralSeq result = LiteralExtractor.extractSuffixes(hir);
    assertInstanceOf(LiteralSeq.None.class, result);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test`
Expected: Compilation failure — `extractSuffixes` method not found

- [ ] **Step 3: Implement extractSuffixes**

In `LiteralExtractor.java`, add:

```java
public static LiteralSeq extractSuffixes(Hir hir) {
    return switch (hir) {
        case Hir.Literal lit -> new LiteralSeq.Single(lit.chars(), true, true);
        case Hir.Capture cap -> extractSuffixes(cap.sub());
        case Hir.Concat concat -> extractSuffixFromConcat(concat.subs());
        case Hir.Alternation alt -> extractSuffixFromAlternation(alt.subs());
        case Hir.Empty ignored -> new LiteralSeq.None();
        case Hir.Class ignored -> new LiteralSeq.None();
        case Hir.Look ignored -> new LiteralSeq.None();
        case Hir.Repetition ignored -> new LiteralSeq.None();
    };
}

private static LiteralSeq extractSuffixFromConcat(List<Hir> subs) {
    if (subs.isEmpty()) {
        return new LiteralSeq.None();
    }
    List<char[]> parts = new ArrayList<>();
    boolean allLiteral = true;
    // Iterate from end
    for (int i = subs.size() - 1; i >= 0; i--) {
        Hir unwrapped = unwrapCaptures(subs.get(i));
        if (unwrapped instanceof Hir.Literal lit) {
            parts.add(0, lit.chars()); // prepend to maintain order
        } else {
            allLiteral = false;
            break;
        }
    }
    if (parts.isEmpty()) {
        return new LiteralSeq.None();
    }
    char[] merged = mergeCharArrays(parts);
    return new LiteralSeq.Single(merged, true, allLiteral);
}

private static LiteralSeq extractSuffixFromAlternation(List<Hir> subs) {
    if (subs.isEmpty()) {
        return new LiteralSeq.None();
    }
    List<char[]> literals = new ArrayList<>();
    boolean allEntire = true;
    for (Hir sub : subs) {
        LiteralSeq extracted = extractSuffixes(sub);
        switch (extracted) {
            case LiteralSeq.Single single -> {
                literals.add(single.literal());
                if (!single.coversEntirePattern()) {
                    allEntire = false;
                }
            }
            case LiteralSeq.None ignored -> {
                return new LiteralSeq.None();
            }
            case LiteralSeq.Alternation ignored -> {
                return new LiteralSeq.None();
            }
        }
    }
    return new LiteralSeq.Alternation(literals, true, allEntire);
}
```

- [ ] **Step 4: Run full test suite**

Run: `./mvnw test`
Expected: ALL tests pass

- [ ] **Step 5: Commit**

```bash
git add regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralExtractor.java \
        regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralExtractorTest.java
git commit -m "feat: add suffix literal extraction to LiteralExtractor"
```

---

### Task 3: Add Inner Literal Extraction

**Files:**
- Modify: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralExtractor.java`
- Modify: `regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralExtractorTest.java`

**Context:** `extractInner()` scans each position in a top-level `Concat` (skipping position 0), extracts a literal from each, and returns the longest candidate. All inner literals are inexact. It also returns the prefix HIR (everything before the inner literal) because `ReverseInner` needs to compile a separate reverse NFA/DFA from it. The upstream reference is `upstream/regex/regex-automata/src/meta/reverse_inner.rs`.

- [ ] **Step 1: Write failing tests for inner extraction**

Add to `LiteralExtractorTest.java`:

```java
// --- Inner literal extraction tests ---

@Test
void innerFromConcatWithMiddleLiteral() {
    // \w+Holmes\w+ → inner "Holmes", inexact, prefixHir = \w+
    Hir wordRep = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
            new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))));
    Hir hir = new Hir.Concat(List.of(
            wordRep,
            new Hir.Literal("Holmes".toCharArray()),
            wordRep
    ));
    LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
    assertNotNull(result);
    assertInstanceOf(LiteralSeq.Single.class, result.literal());
    LiteralSeq.Single single = (LiteralSeq.Single) result.literal();
    assertArrayEquals("Holmes".toCharArray(), single.literal());
    assertFalse(single.exact(), "inner literals must be inexact");
    assertNotNull(result.prefixHir());
}

@Test
void innerSelectsLongest() {
    // \w+ab\w+Holmes\w+ → inner "Holmes" (longer than "ab")
    Hir wordRep = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
            new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))));
    Hir hir = new Hir.Concat(List.of(
            wordRep,
            new Hir.Literal("ab".toCharArray()),
            wordRep,
            new Hir.Literal("Holmes".toCharArray()),
            wordRep
    ));
    LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
    assertNotNull(result);
    LiteralSeq.Single single = (LiteralSeq.Single) result.literal();
    assertArrayEquals("Holmes".toCharArray(), single.literal());
}

@Test
void innerReturnsNullForPureLiteral() {
    // "hello" → no inner (position 0 is prefix territory, nothing else)
    Hir hir = new Hir.Literal("hello".toCharArray());
    LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
    assertNull(result);
}

@Test
void innerReturnsNullForNonConcat() {
    // \w+ → no inner (not a concat)
    Hir hir = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
            new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))));
    LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
    assertNull(result);
}

@Test
void innerSkipsPosition0() {
    // helloWorld\w+ → no inner (position 0 is "helloWorld", nothing at inner positions)
    Hir hir = new Hir.Concat(List.of(
            new Hir.Literal("helloWorld".toCharArray()),
            new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                    new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))))
    ));
    LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
    assertNull(result);
}

@Test
void innerThroughCapture() {
    // \w+(Holmes)\w+ → inner "Holmes", inexact
    Hir wordRep = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
            new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))));
    Hir hir = new Hir.Concat(List.of(
            wordRep,
            new Hir.Capture(1, null,
                    new Hir.Literal("Holmes".toCharArray())),
            wordRep
    ));
    LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
    assertNotNull(result);
    LiteralSeq.Single single = (LiteralSeq.Single) result.literal();
    assertArrayEquals("Holmes".toCharArray(), single.literal());
    assertFalse(single.exact());
}

@Test
void innerDoesNotPickAdjacentLeadingLiterals() {
    // Concat([Literal("hel"), Literal("lo"), Repetition, Literal("World")])
    // Should return "World" (not "lo" which is adjacent to the leading literal)
    Hir hir = new Hir.Concat(List.of(
            new Hir.Literal("hel".toCharArray()),
            new Hir.Literal("lo".toCharArray()),
            new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
                    new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z'))))),
            new Hir.Literal("World".toCharArray())
    ));
    LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
    assertNotNull(result);
    LiteralSeq.Single single = (LiteralSeq.Single) result.literal();
    // "World" (5 chars) is longer than "lo" (2 chars), so it wins
    assertArrayEquals("World".toCharArray(), single.literal());
}

@Test
void innerPrefixHirIsCorrect() {
    // \w+Holmes\w+ → prefixHir should be the \w+ repetition wrapped in a Concat
    Hir wordRep = new Hir.Repetition(1, Hir.Repetition.UNBOUNDED, true,
            new Hir.Class(new ClassUnicode(List.of(new ClassUnicode.ClassUnicodeRange('a', 'z')))));
    Hir hir = new Hir.Concat(List.of(
            wordRep,
            new Hir.Literal("Holmes".toCharArray()),
            wordRep
    ));
    LiteralExtractor.InnerLiteral result = LiteralExtractor.extractInner(hir);
    assertNotNull(result);
    // prefixHir is the sub-expression(s) before the inner literal
    assertInstanceOf(Hir.class, result.prefixHir());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test`
Expected: Compilation failure — `extractInner` method and `InnerLiteral` record not found

- [ ] **Step 3: Implement InnerLiteral record and extractInner**

In `LiteralExtractor.java`, add the record and method:

```java
/**
 * Result of inner literal extraction. Contains the extracted literal
 * and the HIR of the prefix portion (everything before the inner literal).
 * The prefix HIR is used to compile a separate reverse NFA/DFA for
 * the ReverseInner strategy.
 */
public record InnerLiteral(LiteralSeq literal, Hir prefixHir) {}

/**
 * Extracts the best inner literal from a top-level Concat.
 *
 * <p>Scans each position in the concat (skipping position 0, which is prefix
 * territory). For each position, tries to extract a literal. Returns the
 * longest candidate, along with the prefix HIR (everything before it).</p>
 *
 * <p>The "longest literal" heuristic minimizes false positive hits from
 * {@code indexOf}. The upstream Rust crate uses
 * {@code optimize_for_prefix_by_preference()} which ranks candidates by
 * byte frequency — this is a known simplification that could be refined
 * in the future.</p>
 *
 * @return the inner literal and prefix HIR, or null if no inner literal found
 */
public static InnerLiteral extractInner(Hir hir) {
    if (!(hir instanceof Hir.Concat concat)) {
        return null;
    }
    List<Hir> subs = concat.subs();
    if (subs.size() < 2) {
        return null;
    }

    int bestPos = -1;
    LiteralSeq bestLiteral = null;
    int bestLen = 0;

    // Skip position 0 (prefix territory)
    for (int i = 1; i < subs.size(); i++) {
        Hir sub = unwrapCaptures(subs.get(i));
        if (sub instanceof Hir.Literal lit) {
            if (lit.chars().length > bestLen) {
                bestPos = i;
                bestLiteral = new LiteralSeq.Single(lit.chars(), false, false);
                bestLen = lit.chars().length;
            }
        }
    }

    if (bestLiteral == null) {
        return null;
    }

    // Build prefix HIR: everything before the inner literal position
    Hir prefixHir;
    if (bestPos == 1) {
        prefixHir = subs.get(0);
    } else {
        prefixHir = new Hir.Concat(subs.subList(0, bestPos));
    }

    return new InnerLiteral(bestLiteral, prefixHir);
}
```

- [ ] **Step 4: Run full test suite**

Run: `./mvnw test`
Expected: ALL tests pass

- [ ] **Step 5: Commit**

```bash
git add regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/LiteralExtractor.java \
        regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/LiteralExtractorTest.java
git commit -m "feat: add inner literal extraction with longest-literal heuristic"
```

---

## Chunk 2: Strategy Infrastructure and ReverseSuffix

### Task 4: Update Strategy.Cache and Extract captureEngine

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyTest.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyLazyDFATest.java`

**Context:** The `Cache` record currently has 4 fields. We need a 5th (`prefixReverseDFACache`) for `ReverseInner`. We also need to extract `captureEngine()` from `Core` into a static method on `Strategy` so the new variants can share it. The `permits` clause must be updated for the new variants.

- [ ] **Step 1: Update the permits clause and Cache record**

In `Strategy.java`:

1. Update the sealed interface declaration:

```java
public sealed interface Strategy permits Strategy.Core, Strategy.PrefilterOnly,
        Strategy.ReverseSuffix, Strategy.ReverseInner {
```

2. Update the `Cache` record to add the 5th field:

```java
record Cache(
        lol.ohai.regex.automata.nfa.thompson.pikevm.Cache pikeVMCache,
        DFACache forwardDFACache,
        DFACache reverseDFACache,
        lol.ohai.regex.automata.nfa.thompson.backtrack.BoundedBacktracker.Cache backtrackerCache,
        DFACache prefixReverseDFACache
) {
    static final Cache EMPTY = new Cache(null, null, null, null, null);
}
```

3. Update `Core.createCache()` to pass `null` for the 5th field:

```java
@Override
public Cache createCache() {
    return new Cache(
            pikeVM.createCache(),
            forwardDFA != null ? forwardDFA.createCache() : null,
            reverseDFA != null ? reverseDFA.createCache() : null,
            backtracker != null ? backtracker.createCache() : null,
            null  // prefixReverseDFACache — not used by Core
    );
}
```

- [ ] **Step 2: Extract captureEngine as a package-private static method**

Move the logic from `Core.captureEngine()` into a static method on `Strategy`:

```java
/**
 * Selects the best capture engine for the given narrowed window.
 * Prefers the bounded backtracker for small windows (faster than PikeVM
 * for captures); falls back to PikeVM for larger windows or when the
 * backtracker is unavailable.
 */
static Captures doCaptureEngine(Input narrowed, Cache cache,
                                PikeVM pikeVM, BoundedBacktracker backtracker) {
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

Then update `Core.captureEngine()` to delegate:

```java
private Captures captureEngine(Input narrowed, Cache cache) {
    return Strategy.doCaptureEngine(narrowed, cache, pikeVM, backtracker);
}
```

- [ ] **Step 3: Add stub records for ReverseSuffix and ReverseInner**

Add minimal stubs so the `permits` clause compiles (real implementation in Tasks 5 and 7):

```java
record ReverseSuffix(PikeVM pikeVM, LazyDFA forwardDFA, LazyDFA reverseDFA,
                     Prefilter suffixPrefilter, BoundedBacktracker backtracker) implements Strategy {
    @Override public Cache createCache() { throw new UnsupportedOperationException("TODO"); }
    @Override public boolean isMatch(Input input, Cache cache) { throw new UnsupportedOperationException("TODO"); }
    @Override public Captures search(Input input, Cache cache) { throw new UnsupportedOperationException("TODO"); }
    @Override public Captures searchCaptures(Input input, Cache cache) { throw new UnsupportedOperationException("TODO"); }
}

record ReverseInner(PikeVM pikeVM, LazyDFA forwardDFA, LazyDFA prefixReverseDFA,
                    Prefilter innerPrefilter, BoundedBacktracker backtracker) implements Strategy {
    @Override public Cache createCache() { throw new UnsupportedOperationException("TODO"); }
    @Override public boolean isMatch(Input input, Cache cache) { throw new UnsupportedOperationException("TODO"); }
    @Override public Captures search(Input input, Cache cache) { throw new UnsupportedOperationException("TODO"); }
    @Override public Captures searchCaptures(Input input, Cache cache) { throw new UnsupportedOperationException("TODO"); }
}
```

- [ ] **Step 4: Update test files for new Cache constructor**

In `StrategyTest.java` and `StrategyLazyDFATest.java`, every `new Strategy.Cache(...)` call needs a 5th `null` argument. Search for all `new Strategy.Cache(` or `new Cache(` constructions and add the trailing `null`.

- [ ] **Step 5: Run full test suite**

Run: `./mvnw test`
Expected: ALL tests pass

- [ ] **Step 6: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyTest.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyLazyDFATest.java
git commit -m "feat: update Strategy.Cache for prefix-reverse DFA, extract captureEngine, add variant stubs"
```

---

### Task 5: Implement Strategy.ReverseSuffix

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java`
- Create: `regex-automata/src/test/java/lol/ohai/regex/automata/meta/ReverseSuffixTest.java`

**Context:** `ReverseSuffix` finds a suffix literal via `indexOf`, reverse-DFA backward to find match start, forward-DFA + PikeVM to verify. Anti-quadratic watermark updates `minStart` on every NoMatch iteration.

**IMPORTANT:** Before implementing, read the upstream Rust implementation at `upstream/regex/regex-automata/src/meta/strategy.rs` lines 1115-1491 to verify the search loop semantics. Cross-reference:
- `try_search_half_start()` (lines 1211-1242) — the reverse search loop
- `try_search_half_fwd()` (lines 1340-1378) — the forward verification
- `is_match()` (lines 1417-1434) — the existence shortcut

- [ ] **Step 1: Write failing tests**

Create `regex-automata/src/test/java/lol/ohai/regex/automata/meta/ReverseSuffixTest.java`:

```java
package lol.ohai.regex.automata.meta;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.backtrack.BoundedBacktracker;
import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.LiteralExtractor;
import lol.ohai.regex.syntax.hir.LiteralSeq;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReverseSuffixTest {

    /**
     * Helper: build a ReverseSuffix strategy from a pattern, extracting
     * suffix literals and compiling forward + reverse DFAs.
     */
    private record Setup(Strategy.ReverseSuffix strategy, Strategy.Cache cache) {}

    private Setup build(String pattern) {
        Ast ast = Parser.parse(pattern, 100);
        Hir hir = Translator.translate(pattern, ast);

        LiteralSeq suffixes = LiteralExtractor.extractSuffixes(hir);
        Prefilter suffixPrefilter = buildPrefilter(suffixes);
        assertNotNull(suffixPrefilter, "pattern must have extractable suffix");

        NFA nfa = Compiler.compile(hir);
        boolean quitNonAscii = nfa.lookSetAny().containsUnicodeWord();
        CharClasses cc = CharClassBuilder.build(nfa, quitNonAscii);
        PikeVM pikeVM = new PikeVM(nfa);
        LazyDFA forwardDFA = LazyDFA.create(nfa, cc);
        assertNotNull(forwardDFA, "pattern must support forward DFA");

        NFA reverseNfa = Compiler.compileReverse(hir);
        CharClasses revCc = CharClassBuilder.build(reverseNfa, quitNonAscii);
        LazyDFA reverseDFA = LazyDFA.create(reverseNfa, revCc);
        assertNotNull(reverseDFA, "pattern must support reverse DFA");

        BoundedBacktracker bt = new BoundedBacktracker(nfa);
        Strategy.ReverseSuffix strategy = new Strategy.ReverseSuffix(
                pikeVM, forwardDFA, reverseDFA, suffixPrefilter, bt);
        Strategy.Cache cache = strategy.createCache();
        return new Setup(strategy, cache);
    }

    private static Prefilter buildPrefilter(LiteralSeq seq) {
        return switch (seq) {
            case LiteralSeq.None ignored -> null;
            case LiteralSeq.Single single -> new SingleLiteral(single.literal());
            case LiteralSeq.Alternation alt -> new MultiLiteral(
                    alt.literals().toArray(char[][]::new));
        };
    }

    @Test
    void simpleSuffixMatch() {
        Setup s = build("\\w+Holmes");
        Input input = Input.of("Sherlock Holmes");
        Captures caps = s.strategy.search(input, s.cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(15, caps.end(0));
    }

    @Test
    void suffixNoMatch() {
        Setup s = build("\\w+Holmes");
        Input input = Input.of("no match here");
        Captures caps = s.strategy.search(input, s.cache);
        assertNull(caps);
    }

    @Test
    void suffixIsMatch() {
        Setup s = build("\\w+Holmes");
        assertTrue(s.strategy.isMatch(Input.of("Sherlock Holmes"), s.cache));
        assertFalse(s.strategy.isMatch(Input.of("no match"), s.cache));
    }

    @Test
    void suffixCaptures() {
        Setup s = build("(\\w+)Holmes");
        Input input = Input.of("SherlockHolmes");
        Captures caps = s.strategy.searchCaptures(input, s.cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(14, caps.end(0));
    }

    @Test
    void suffixMultipleMatches() {
        // Verify the strategy returns the first (leftmost) match
        Setup s = build("\\w+Holmes");
        Input input = Input.of("xHolmes yHolmes");
        Captures caps = s.strategy.search(input, s.cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(7, caps.end(0));
    }

    @Test
    void anchoredFallsBack() {
        Setup s = build("\\w+Holmes");
        Input input = Input.anchored("Sherlock Holmes");
        // Anchored should still work (falls back to PikeVM)
        Captures caps = s.strategy.search(input, s.cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(15, caps.end(0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test`
Expected: FAIL — `UnsupportedOperationException("TODO")` from stub methods

- [ ] **Step 3: Implement ReverseSuffix**

Replace the stub `ReverseSuffix` record in `Strategy.java` with the full implementation. Follow the spec pseudocode from Section 4, cross-referencing upstream `strategy.rs` lines 1115-1491:

```java
record ReverseSuffix(PikeVM pikeVM, LazyDFA forwardDFA, LazyDFA reverseDFA,
                     Prefilter suffixPrefilter, BoundedBacktracker backtracker) implements Strategy {

    @Override
    public Cache createCache() {
        return new Cache(
                pikeVM.createCache(),
                forwardDFA.createCache(),
                reverseDFA.createCache(),
                backtracker != null ? backtracker.createCache() : null,
                null  // prefixReverseDFACache — not used by ReverseSuffix
        );
    }

    @Override
    public boolean isMatch(Input input, Cache cache) {
        if (input.isAnchored()) {
            return pikeVM.isMatch(input, cache.pikeVMCache());
        }
        int start = input.start();
        int end = input.end();
        String haystackStr = input.haystackStr();
        int minStart = input.start();

        while (start < end) {
            int suffixPos = suffixPrefilter.find(haystackStr, start, end);
            if (suffixPos < 0) return false;

            int reverseFrom = suffixPos + suffixPrefilter.matchLength();
            Input reverseInput = input.withBounds(minStart, reverseFrom, false);
            SearchResult revResult = reverseDFA.searchRev(reverseInput, cache.reverseDFACache());

            switch (revResult) {
                case SearchResult.NoMatch n -> {
                    minStart = reverseFrom;
                    start = suffixPos + 1;
                    continue;
                }
                case SearchResult.GaveUp g -> {
                    return pikeVM.isMatch(input.withBounds(start, end, false), cache.pikeVMCache());
                }
                case SearchResult.Match m -> {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Captures search(Input input, Cache cache) {
        if (input.isAnchored()) {
            return pikeVM.search(input, cache.pikeVMCache());
        }
        int start = input.start();
        int end = input.end();
        String haystackStr = input.haystackStr();
        int minStart = input.start();

        while (start < end) {
            int suffixPos = suffixPrefilter.find(haystackStr, start, end);
            if (suffixPos < 0) return null;

            int reverseFrom = suffixPos + suffixPrefilter.matchLength();
            Input reverseInput = input.withBounds(minStart, reverseFrom, false);
            SearchResult revResult = reverseDFA.searchRev(reverseInput, cache.reverseDFACache());

            switch (revResult) {
                case SearchResult.NoMatch n -> {
                    minStart = reverseFrom;
                    start = suffixPos + 1;
                    continue;
                }
                case SearchResult.GaveUp g -> {
                    return pikeVM.search(input.withBounds(start, end, false), cache.pikeVMCache());
                }
                case SearchResult.Match m -> {
                    int matchStart = m.offset();
                    Input fwdInput = input.withBounds(matchStart, end, false);
                    SearchResult fwdResult = forwardDFA.searchFwd(fwdInput, cache.forwardDFACache());

                    switch (fwdResult) {
                        case SearchResult.Match fm -> {
                            Input narrowed = input.withBounds(matchStart, fm.offset(), false);
                            return pikeVM.search(narrowed, cache.pikeVMCache());
                        }
                        case SearchResult.GaveUp g2 -> {
                            return pikeVM.search(fwdInput, cache.pikeVMCache());
                        }
                        case SearchResult.NoMatch n2 -> {
                            start = suffixPos + 1;
                            continue;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public Captures searchCaptures(Input input, Cache cache) {
        if (input.isAnchored()) {
            return pikeVM.searchCaptures(input, cache.pikeVMCache());
        }
        int start = input.start();
        int end = input.end();
        String haystackStr = input.haystackStr();
        int minStart = input.start();

        while (start < end) {
            int suffixPos = suffixPrefilter.find(haystackStr, start, end);
            if (suffixPos < 0) return null;

            int reverseFrom = suffixPos + suffixPrefilter.matchLength();
            Input reverseInput = input.withBounds(minStart, reverseFrom, false);
            SearchResult revResult = reverseDFA.searchRev(reverseInput, cache.reverseDFACache());

            switch (revResult) {
                case SearchResult.NoMatch n -> {
                    minStart = reverseFrom;
                    start = suffixPos + 1;
                    continue;
                }
                case SearchResult.GaveUp g -> {
                    return pikeVM.searchCaptures(input.withBounds(start, end, false), cache.pikeVMCache());
                }
                case SearchResult.Match m -> {
                    int matchStart = m.offset();
                    Input fwdInput = input.withBounds(matchStart, end, false);
                    SearchResult fwdResult = forwardDFA.searchFwd(fwdInput, cache.forwardDFACache());

                    switch (fwdResult) {
                        case SearchResult.Match fm -> {
                            Input narrowed = input.withBounds(matchStart, fm.offset(), false);
                            return Strategy.doCaptureEngine(narrowed, cache, pikeVM, backtracker);
                        }
                        case SearchResult.GaveUp g2 -> {
                            return pikeVM.searchCaptures(fwdInput, cache.pikeVMCache());
                        }
                        case SearchResult.NoMatch n2 -> {
                            start = suffixPos + 1;
                            continue;
                        }
                    }
                }
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run full test suite**

Run: `./mvnw test`
Expected: ALL tests pass (including new ReverseSuffixTest)

- [ ] **Step 5: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/meta/ReverseSuffixTest.java
git commit -m "feat: implement Strategy.ReverseSuffix with anti-quadratic watermark"
```

---

### Task 6: Wire ReverseSuffix into Regex.create()

**Files:**
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`

**Context:** Strategy selection order: PrefilterOnly → Core w/ prefix → ReverseSuffix → ReverseInner → Core w/o prefilter. ReverseSuffix activates when: no prefix prefilter, suffix extracted, both DFAs available.

- [ ] **Step 1: Update strategy selection in Regex.create()**

In `Regex.java`, modify the `else` block (lines 91-107) to try ReverseSuffix before falling through to Core:

```java
} else {
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

    BoundedBacktracker backtracker = new BoundedBacktracker(nfa);

    // Try ReverseSuffix: no prefix prefilter, but suffix available + both DFAs
    if (prefilter == null && forwardDFA != null && reverseDFA != null) {
        LiteralSeq suffixes = LiteralExtractor.extractSuffixes(hir);
        Prefilter suffixPrefilter = buildPrefilter(suffixes);
        if (suffixPrefilter != null) {
            strategy = new Strategy.ReverseSuffix(
                    pikeVM, forwardDFA, reverseDFA, suffixPrefilter, backtracker);
            namedGroups = buildNamedGroupMap(nfa);
            return new Regex(pattern, strategy, namedGroups);
        }
    }

    // Fall through to Core
    strategy = new Strategy.Core(pikeVM, forwardDFA, reverseDFA, prefilter, backtracker);
    namedGroups = buildNamedGroupMap(nfa);
}
```

You'll also need to add the import for `LiteralExtractor` (already imported) and note that `LiteralSeq` is already imported.

- [ ] **Step 2: Run full test suite**

Run: `./mvnw test`
Expected: ALL tests pass. Patterns like `\w+Holmes` now use ReverseSuffix. The upstream TOML suite (`UpstreamSuiteTest`) is the critical gate — any regression here means the search loop is wrong.

**If any UpstreamSuiteTest fails:** Stop. Read the failing test case. Cross-reference with the upstream Rust implementation. Fix before proceeding.

- [ ] **Step 3: Commit**

```bash
git add regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "feat: wire ReverseSuffix strategy into Regex.create() selection"
```

---

## Chunk 3: ReverseInner, Final Wiring, and Validation

### Task 7: Implement Strategy.ReverseInner

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java`
- Create: `regex-automata/src/test/java/lol/ohai/regex/automata/meta/ReverseInnerTest.java`

**Context:** `ReverseInner` is structurally similar to `ReverseSuffix` but uses a **separate prefix-only reverse DFA** (not the full-pattern reverse DFA), and has dual watermarks with quadratic-abort. The upstream reference is `upstream/regex/regex-automata/src/meta/reverse_inner.rs` and `strategy.rs` lines 1493-1700.

**IMPORTANT:** Before implementing, read the upstream code to verify:
- `reverse_inner.rs` `extract()` (lines 53-109) — how the prefix HIR is determined
- `strategy.rs` `ReverseInner::try_search_full()` (lines 1618-1700) — the search loop with dual watermarks
- The quadratic-abort behavior when `litmatch.start < min_pre_start`

- [ ] **Step 1: Write failing tests**

Create `regex-automata/src/test/java/lol/ohai/regex/automata/meta/ReverseInnerTest.java`:

```java
package lol.ohai.regex.automata.meta;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.backtrack.BoundedBacktracker;
import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.LiteralExtractor;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReverseInnerTest {

    private record Setup(Strategy.ReverseInner strategy, Strategy.Cache cache) {}

    private Setup build(String pattern) {
        Ast ast = Parser.parse(pattern, 100);
        Hir hir = Translator.translate(pattern, ast);

        LiteralExtractor.InnerLiteral inner = LiteralExtractor.extractInner(hir);
        assertNotNull(inner, "pattern must have extractable inner literal");

        Prefilter innerPrefilter = buildPrefilter(inner.literal());
        assertNotNull(innerPrefilter, "inner literal must produce a prefilter");

        NFA nfa = Compiler.compile(hir);
        boolean quitNonAscii = nfa.lookSetAny().containsUnicodeWord();
        CharClasses cc = CharClassBuilder.build(nfa, quitNonAscii);
        PikeVM pikeVM = new PikeVM(nfa);
        LazyDFA forwardDFA = LazyDFA.create(nfa, cc);
        assertNotNull(forwardDFA, "pattern must support forward DFA");

        // Compile separate prefix-only reverse DFA
        NFA prefixRevNfa = Compiler.compileReverse(inner.prefixHir());
        CharClasses prefixRevCc = CharClassBuilder.build(prefixRevNfa, quitNonAscii);
        LazyDFA prefixReverseDFA = LazyDFA.create(prefixRevNfa, prefixRevCc);
        assertNotNull(prefixReverseDFA, "prefix reverse DFA must be available");

        BoundedBacktracker bt = new BoundedBacktracker(nfa);
        Strategy.ReverseInner strategy = new Strategy.ReverseInner(
                pikeVM, forwardDFA, prefixReverseDFA, innerPrefilter, bt);
        Strategy.Cache cache = strategy.createCache();
        return new Setup(strategy, cache);
    }

    private static Prefilter buildPrefilter(lol.ohai.regex.syntax.hir.LiteralSeq seq) {
        return switch (seq) {
            case lol.ohai.regex.syntax.hir.LiteralSeq.None ignored -> null;
            case lol.ohai.regex.syntax.hir.LiteralSeq.Single single ->
                    new SingleLiteral(single.literal());
            case lol.ohai.regex.syntax.hir.LiteralSeq.Alternation alt ->
                    new MultiLiteral(alt.literals().toArray(char[][]::new));
        };
    }

    @Test
    void simpleInnerMatch() {
        // \w+Holmes\w+ with inner "Holmes"
        Setup s = build("\\w+Holmes\\w+");
        Input input = Input.of("xxxHolmesyyy");
        Captures caps = s.strategy.search(input, s.cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(12, caps.end(0));
    }

    @Test
    void innerNoMatch() {
        Setup s = build("\\w+Holmes\\w+");
        Input input = Input.of("no match here");
        Captures caps = s.strategy.search(input, s.cache);
        assertNull(caps);
    }

    @Test
    void innerIsMatch() {
        Setup s = build("\\w+Holmes\\w+");
        assertTrue(s.strategy.isMatch(Input.of("xxxHolmesyyy"), s.cache));
        assertFalse(s.strategy.isMatch(Input.of("no match"), s.cache));
    }

    @Test
    void innerCaptures() {
        Setup s = build("(\\w+)Holmes(\\w+)");
        Input input = Input.of("xxxHolmesyyy");
        Captures caps = s.strategy.searchCaptures(input, s.cache);
        assertNotNull(caps);
        assertEquals(0, caps.start(0));
        assertEquals(12, caps.end(0));
    }

    @Test
    void innerAnchoredFallsBack() {
        Setup s = build("\\w+Holmes\\w+");
        Input input = Input.anchored("xxxHolmesyyy");
        Captures caps = s.strategy.search(input, s.cache);
        assertNotNull(caps);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test`
Expected: FAIL — `UnsupportedOperationException("TODO")` from stub

- [ ] **Step 3: Implement ReverseInner**

Replace the stub `ReverseInner` record in `Strategy.java`:

```java
record ReverseInner(PikeVM pikeVM, LazyDFA forwardDFA, LazyDFA prefixReverseDFA,
                    Prefilter innerPrefilter, BoundedBacktracker backtracker) implements Strategy {

    @Override
    public Cache createCache() {
        return new Cache(
                pikeVM.createCache(),
                forwardDFA.createCache(),
                null,  // reverseDFACache — not used by ReverseInner
                backtracker != null ? backtracker.createCache() : null,
                prefixReverseDFA.createCache()  // prefix-only reverse DFA
        );
    }

    @Override
    public boolean isMatch(Input input, Cache cache) {
        if (input.isAnchored()) {
            return pikeVM.isMatch(input, cache.pikeVMCache());
        }
        int start = input.start();
        int end = input.end();
        String haystackStr = input.haystackStr();
        int minStart = input.start();
        int minPreStart = input.start();

        while (start < end) {
            int innerPos = innerPrefilter.find(haystackStr, start, end);
            if (innerPos < 0) return false;

            // Quadratic-abort
            if (innerPos < minPreStart) {
                return pikeVM.isMatch(input.withBounds(start, end, false), cache.pikeVMCache());
            }

            Input reverseInput = input.withBounds(minStart, innerPos, false);
            SearchResult revResult = prefixReverseDFA.searchRev(reverseInput, cache.prefixReverseDFACache());

            switch (revResult) {
                case SearchResult.NoMatch n -> {
                    minStart = innerPos;
                    minPreStart = innerPos + 1;
                    start = innerPos + 1;
                    continue;
                }
                case SearchResult.GaveUp g -> {
                    return pikeVM.isMatch(input.withBounds(start, end, false), cache.pikeVMCache());
                }
                case SearchResult.Match m -> {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Captures search(Input input, Cache cache) {
        if (input.isAnchored()) {
            return pikeVM.search(input, cache.pikeVMCache());
        }
        int start = input.start();
        int end = input.end();
        String haystackStr = input.haystackStr();
        int minStart = input.start();
        int minPreStart = input.start();

        while (start < end) {
            int innerPos = innerPrefilter.find(haystackStr, start, end);
            if (innerPos < 0) return null;

            // Quadratic-abort
            if (innerPos < minPreStart) {
                return pikeVM.search(input.withBounds(start, end, false), cache.pikeVMCache());
            }

            Input reverseInput = input.withBounds(minStart, innerPos, false);
            SearchResult revResult = prefixReverseDFA.searchRev(reverseInput, cache.prefixReverseDFACache());

            switch (revResult) {
                case SearchResult.NoMatch n -> {
                    minStart = innerPos;
                    minPreStart = innerPos + 1;
                    start = innerPos + 1;
                    continue;
                }
                case SearchResult.GaveUp g -> {
                    return pikeVM.search(input.withBounds(start, end, false), cache.pikeVMCache());
                }
                case SearchResult.Match m -> {
                    int matchStart = m.offset();
                    Input fwdInput = input.withBounds(matchStart, end, false);
                    SearchResult fwdResult = forwardDFA.searchFwd(fwdInput, cache.forwardDFACache());

                    switch (fwdResult) {
                        case SearchResult.Match fm -> {
                            Input narrowed = input.withBounds(matchStart, fm.offset(), false);
                            return pikeVM.search(narrowed, cache.pikeVMCache());
                        }
                        case SearchResult.GaveUp g2 -> {
                            return pikeVM.search(fwdInput, cache.pikeVMCache());
                        }
                        case SearchResult.NoMatch n2 -> {
                            start = innerPos + 1;
                            continue;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public Captures searchCaptures(Input input, Cache cache) {
        if (input.isAnchored()) {
            return pikeVM.searchCaptures(input, cache.pikeVMCache());
        }
        int start = input.start();
        int end = input.end();
        String haystackStr = input.haystackStr();
        int minStart = input.start();
        int minPreStart = input.start();

        while (start < end) {
            int innerPos = innerPrefilter.find(haystackStr, start, end);
            if (innerPos < 0) return null;

            // Quadratic-abort
            if (innerPos < minPreStart) {
                return pikeVM.searchCaptures(input.withBounds(start, end, false), cache.pikeVMCache());
            }

            Input reverseInput = input.withBounds(minStart, innerPos, false);
            SearchResult revResult = prefixReverseDFA.searchRev(reverseInput, cache.prefixReverseDFACache());

            switch (revResult) {
                case SearchResult.NoMatch n -> {
                    minStart = innerPos;
                    minPreStart = innerPos + 1;
                    start = innerPos + 1;
                    continue;
                }
                case SearchResult.GaveUp g -> {
                    return pikeVM.searchCaptures(input.withBounds(start, end, false), cache.pikeVMCache());
                }
                case SearchResult.Match m -> {
                    int matchStart = m.offset();
                    Input fwdInput = input.withBounds(matchStart, end, false);
                    SearchResult fwdResult = forwardDFA.searchFwd(fwdInput, cache.forwardDFACache());

                    switch (fwdResult) {
                        case SearchResult.Match fm -> {
                            Input narrowed = input.withBounds(matchStart, fm.offset(), false);
                            return Strategy.doCaptureEngine(narrowed, cache, pikeVM, backtracker);
                        }
                        case SearchResult.GaveUp g2 -> {
                            return pikeVM.searchCaptures(fwdInput, cache.pikeVMCache());
                        }
                        case SearchResult.NoMatch n2 -> {
                            start = innerPos + 1;
                            continue;
                        }
                    }
                }
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: Run full test suite**

Run: `./mvnw test`
Expected: ALL tests pass

- [ ] **Step 5: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java \
        regex-automata/src/test/java/lol/ohai/regex/automata/meta/ReverseInnerTest.java
git commit -m "feat: implement Strategy.ReverseInner with dual watermarks and quadratic-abort"
```

---

### Task 8: Wire ReverseInner into Regex.create()

**Files:**
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`

**Context:** After the ReverseSuffix check (added in Task 6), try ReverseInner: extract inner literal + prefix HIR, compile prefix-reverse DFA, build strategy.

- [ ] **Step 1: Add ReverseInner selection after ReverseSuffix**

In `Regex.java`, after the ReverseSuffix block added in Task 6, add:

```java
    // Try ReverseInner: no prefix/suffix prefilter, but inner literal available.
    // Note: no reverseDFA != null check here — ReverseInner uses its own
    // prefixReverseDFA compiled from the prefix HIR, not the full-pattern reverseDFA.
    if (prefilter == null && forwardDFA != null) {
        LiteralExtractor.InnerLiteral innerLiteral = LiteralExtractor.extractInner(hir);
        if (innerLiteral != null) {
            Prefilter innerPrefilter = buildPrefilter(innerLiteral.literal());
            if (innerPrefilter != null) {
                try {
                    NFA prefixRevNfa = Compiler.compileReverse(innerLiteral.prefixHir());
                    CharClasses prefixRevCc = CharClassBuilder.build(prefixRevNfa, quitNonAscii);
                    LazyDFA prefixReverseDFA = LazyDFA.create(prefixRevNfa, prefixRevCc);
                    if (prefixReverseDFA != null) {
                        strategy = new Strategy.ReverseInner(
                                pikeVM, forwardDFA, prefixReverseDFA, innerPrefilter, backtracker);
                        namedGroups = buildNamedGroupMap(nfa);
                        return new Regex(pattern, strategy, namedGroups);
                    }
                } catch (BuildError ignored) {
                    // Prefix HIR compilation failed — fall through to Core
                }
            }
        }
    }
```

Note: The `try/catch` around `Compiler.compileReverse()` is needed because the prefix HIR may not compile (e.g., if it contains look-assertions that the reverse compiler can't handle). In that case, we silently fall through to Core.

- [ ] **Step 2: Run full test suite**

Run: `./mvnw test`
Expected: ALL tests pass. **Pay special attention to UpstreamSuiteTest** — patterns with inner literals will now use ReverseInner. Any regression here means stop and fix.

- [ ] **Step 3: Commit**

```bash
git add regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "feat: wire ReverseInner strategy into Regex.create() selection"
```

---

### Task 9: Full Regression Validation

**Files:** None modified — this is a validation-only task.

**Context:** Run the full test suite one final time with all changes integrated. This is the critical gate before benchmarking.

- [ ] **Step 1: Run full test suite**

Run: `./mvnw test`
Expected: ALL 1,954+ tests pass across all modules.

- [ ] **Step 2: If any test fails, diagnose and fix**

For each failure:
1. Read the failing test case
2. Determine which pattern triggered which strategy
3. Cross-reference with upstream Rust code
4. Fix the issue
5. Re-run full suite
6. Commit the fix

- [ ] **Step 3: Commit if any fixes were needed**

```bash
git commit -m "fix: correct suffix/inner prefilter regressions"
```

---

### Task 10: Benchmarks and Documentation

**Files:**
- Modify: `BENCHMARKS.md`
- Modify: `docs/architecture/lazy-dfa-gaps.md`

**Context:** Run JMH benchmarks to measure the impact of suffix/inner prefilters. Update documentation with results.

- [ ] **Step 1: Build benchmarks**

Run: `./mvnw -P bench package -DskipTests`

- [ ] **Step 2: Run benchmarks**

Run: `java -jar regex-bench/target/benchmarks.jar`

Record results, focusing on:
- `captures` benchmark (`\w+\s+Holmes` on 900KB) — should improve dramatically (was 33.6x slower)
- `literal`, `charClass`, `unicodeWord`, `alternation` — should not regress
- `pathological` — should not regress

- [ ] **Step 3: Update BENCHMARKS.md**

Add a new results section dated 2026-03-13 with the new numbers. Move previous results to a collapsible section. Update the analysis noting suffix/inner prefilter impact.

- [ ] **Step 4: Update lazy-dfa-gaps.md**

Add a new section documenting the suffix/inner prefilter strategies as implemented.

- [ ] **Step 5: Commit**

```bash
git add BENCHMARKS.md docs/architecture/lazy-dfa-gaps.md
git commit -m "docs: update benchmarks and gaps after suffix/inner prefilter implementation"
```
