# UTF-16 Char-Unit NFA Migration Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate the NFA, Compiler, and PikeVM from byte-unit (UTF-8) to char-unit (UTF-16) to eliminate per-search transcoding overhead.

**Architecture:** In-place replacement of byte-oriented internals. `ByteRange` → `CharRange`, `Utf8Sequences` → `Utf16Sequences`, `Input` holds `char[]` directly. PikeVM loop advances by one `char` unit. Supplementary codepoints are surrogate pair chains. `State.Dense` removed (re-add later as ASCII optimization).

**Tech Stack:** Java 21, Maven, JUnit 6

**Spec:** `docs/superpowers/specs/2026-03-10-utf16-nfa-migration-design.md`

---

## Chunk 1: HIR Layer Changes (regex-syntax)

### Task 1: Change `Hir.Literal` from `byte[]` to `char[]`

**Files:**
- Modify: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/Hir.java`
- Modify: `regex-syntax/src/test/java/lol/ohai/regex/syntax/hir/HirTest.java`

- [ ] **Step 1: Update `Hir.Literal` record**

In `Hir.java`, change:
```java
record Literal(byte[] bytes) implements Hir {
```
to:
```java
record Literal(char[] chars) implements Hir {
```
Update the `equals`, `hashCode`, and `toString` methods to use `chars` instead of `bytes`. Update the Javadoc from "UTF-8 encoded" to "UTF-16 char sequence".

- [ ] **Step 2: Remove `Hir.ClassB` record**

Delete the `ClassB` record from `Hir.java`:
```java
/** A byte-oriented character class. */
record ClassB(ClassBytes bytes) implements Hir {}
```

- [ ] **Step 3: Update HirTest assertions**

In `HirTest.java`, update any assertions that reference `Hir.Literal.bytes()` to use `Hir.Literal.chars()`. For example, where tests create `new Hir.Literal("a".getBytes(UTF_8))`, change to `new Hir.Literal(new char[]{'a'})`.

- [ ] **Step 4: Verify regex-syntax compiles (expect Translator and Compiler errors)**

Run: `./mvnw compile -pl regex-syntax 2>&1 | grep -c ERROR`
Expected: Compilation errors in `Translator.java` (still references `charToUtf8`, `ClassB`). These are fixed in Task 2.

### Task 2: Update Translator to produce `char[]` literals

**Files:**
- Modify: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/Translator.java`

- [ ] **Step 1: Replace `charToUtf8` with `codePointToChars`**

Replace the `charToUtf8` helper (line ~506):
```java
private static byte[] charToUtf8(char c) {
    return String.valueOf(c).getBytes(StandardCharsets.UTF_8);
}
```
with:
```java
private static char[] codePointToChars(int cp) {
    return Character.toChars(cp);
}
```
This takes a codepoint (`int`), not a `char`. `Character.toChars()` returns a 1-element array for BMP codepoints and a 2-element surrogate pair for supplementary codepoints. The `Ast.Literal` node holds a `char` which cannot represent supplementary codepoints directly — those arrive as escape sequences (e.g., `\u{1D6C3}`) parsed into codepoints by the parser. The Translator must widen to `int` before calling this helper.

- [ ] **Step 2: Update `translateLiteral` to produce `Hir.Literal(char[])`**

Change line ~141:
```java
return new Hir.Literal(charToUtf8(c));
```
to:
```java
return new Hir.Literal(codePointToChars((int) c));
```
The cast to `int` widens the `char` to a codepoint. For supplementary codepoints arriving from escape sequences, ensure the parser passes the full `int` codepoint value.

- [ ] **Step 3: Remove any `ClassB` production**

Search `Translator.java` for any code producing `Hir.ClassB` and remove it. The `translateDot` method may produce byte classes for the `(?-u)` (non-Unicode) dot case — change these to produce `Hir.Class` with Unicode ranges instead.

- [ ] **Step 4: Verify regex-syntax compiles and tests pass**

Run: `./mvnw test -pl regex-syntax`
Expected: All 162 tests pass.

- [ ] **Step 5: Commit**

```
git add regex-syntax/
git commit -m "change Hir.Literal from byte[] to char[], remove Hir.ClassB"
```

### Task 3: Delete `ClassBytes.java`

**Files:**
- Delete: `regex-syntax/src/main/java/lol/ohai/regex/syntax/hir/ClassBytes.java`

- [ ] **Step 1: Delete `ClassBytes.java`**

Remove the file. If any other file imports it, remove the import and the referencing code.

- [ ] **Step 2: Verify regex-syntax compiles and tests pass**

Run: `./mvnw test -pl regex-syntax`
Expected: All tests pass.

- [ ] **Step 3: Commit**

```
git add -A regex-syntax/
git commit -m "remove ClassBytes (byte-oriented character class)"
```

---

## Chunk 2: NFA State Changes (regex-automata)

### Task 4: Rename `State.ByteRange` to `State.CharRange` and remove `State.Dense`

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/State.java`
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Transition.java`

- [ ] **Step 1: Rename `ByteRange` to `CharRange`**

In `State.java`, rename the record and update Javadoc:
```java
record CharRange(int start, int end, int next) implements State {}
```
Javadoc: "Matches a single char unit in range [start, end] inclusive, then transitions to next."

- [ ] **Step 2: Remove `State.Dense`**

Delete the `Dense` record from `State.java`:
```java
record Dense(int[] next) implements State {}
```

- [ ] **Step 3: Update `Transition.java` and `State.Sparse` Javadoc**

Change "byte range" to "char range" in `Transition.java` Javadoc. Also update the `State.Sparse` Javadoc from "Multiple byte range transitions" to "Multiple char range transitions". Field types (`int start, int end, int next`) remain unchanged.

- [ ] **Step 4: Expect compilation errors in Builder, Compiler, PikeVM**

These are fixed in subsequent tasks. Do not try to compile yet.

### Task 5: Update `Builder.patch` for `CharRange`

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Builder.java`

- [ ] **Step 1: Update `patch` method**

Replace `State.ByteRange` with `State.CharRange` in the switch:
```java
case State.CharRange r -> new State.CharRange(r.start(), r.end(), target);
```
Remove the `Dense` case from the default comment.

- [ ] **Step 2: Update BuilderTest**

**File:** `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/BuilderTest.java`

Replace all `State.ByteRange` with `State.CharRange` throughout the test file. The test logic is identical — only the type name changes.

- [ ] **Step 3: Commit**

```
git add regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/State.java
git add regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Transition.java
git add regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Builder.java
git add regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/BuilderTest.java
git commit -m "rename State.ByteRange to State.CharRange, remove State.Dense"
```

---

## Chunk 3: Compiler Migration (regex-automata)

### Task 6: Create `Utf16Sequences` to replace `Utf8Sequences`

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Utf16Sequences.java`
- Create: `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/Utf16SequencesTest.java`
- Delete: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Utf8Sequences.java`
- Delete: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Utf8Sequence.java`

- [ ] **Step 1: Write tests for `Utf16Sequences`**

```java
class Utf16SequencesTest {
    @Test
    void asciiRange() {
        // [a-z] → single CharRange(0x61, 0x7A)
        var seqs = Utf16Sequences.compile(0x61, 0x7A);
        assertEquals(1, seqs.size());
        assertEquals(1, seqs.get(0).length); // 1 char unit
        assertEquals(0x61, seqs.get(0)[0][0]);
        assertEquals(0x7A, seqs.get(0)[0][1]);
    }

    @Test
    void bmpRange() {
        // [À-ÿ] → single CharRange(0xC0, 0xFF)
        var seqs = Utf16Sequences.compile(0xC0, 0xFF);
        assertEquals(1, seqs.size());
    }

    @Test
    void supplementaryRange() {
        // Single supplementary codepoint U+10000
        var seqs = Utf16Sequences.compile(0x10000, 0x10000);
        assertEquals(1, seqs.size());
        assertEquals(2, seqs.get(0).length); // 2 char units (surrogate pair)
        assertEquals(0xD800, seqs.get(0)[0][0]); // high surrogate
        assertEquals(0xD800, seqs.get(0)[0][1]);
        assertEquals(0xDC00, seqs.get(0)[1][0]); // low surrogate
        assertEquals(0xDC00, seqs.get(0)[1][1]);
    }

    @Test
    void mixedBmpAndSupplementary() {
        // Range spanning BMP and supplementary: U+FFFE to U+10001
        var seqs = Utf16Sequences.compile(0xFFFE, 0x10001);
        // Should produce: BMP part [0xFFFE-0xFFFF] + supplementary part [U+10000-U+10001]
        assertTrue(seqs.size() >= 2);
    }

    @Test
    void fullBmpRange() {
        // [U+0000-U+D7FF] — BMP below surrogates
        var seqs = Utf16Sequences.compile(0x0000, 0xD7FF);
        assertEquals(1, seqs.size());
        assertEquals(1, seqs.get(0).length);
    }

    @Test
    void skipsSurrogateRange() {
        // Range that spans surrogates: U+D000 to U+E000
        // Should produce: [0xD000-0xD7FF] + [0xE000-0xE000], skipping 0xD800-0xDFFF
        var seqs = Utf16Sequences.compile(0xD000, 0xE000);
        // BMP portions only, surrogates excluded
        assertEquals(2, seqs.size());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl regex-automata -Dtest="Utf16SequencesTest"`
Expected: FAIL (class does not exist)

- [ ] **Step 3: Implement `Utf16Sequences`**

The class provides a static method `compile(int startCp, int endCp)` that returns a `List<int[][]>`. Each `int[][]` is a sequence of char-unit ranges (length 1 for BMP, length 2 for supplementary).

Algorithm:
1. Split the input range around the surrogate gap (0xD800–0xDFFF) — surrogates are not valid codepoints
2. For BMP portions (0x0000–0xD7FF and 0xE000–0xFFFF): emit a single `int[][]` with one range `{{start, end}}`
3. For supplementary portions (0x10000–0x10FFFF): convert to surrogate pairs. Split into sub-ranges where the high surrogate is constant, producing `{{highStart, highEnd}, {lowStart, lowEnd}}` sequences

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -pl regex-automata -Dtest="Utf16SequencesTest"`
Expected: All pass.

- [ ] **Step 5: Delete `Utf8Sequences.java` and `Utf8Sequence.java`**

Remove both files.

- [ ] **Step 6: Commit**

```
git add regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Utf16Sequences.java
git add regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/Utf16SequencesTest.java
git rm regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Utf8Sequences.java
git rm regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Utf8Sequence.java
git commit -m "add Utf16Sequences, remove Utf8Sequences"
```

### Task 7: Migrate Compiler to char-unit

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Compiler.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/CompilerTest.java`

- [ ] **Step 1: Update `compileLiteral`**

Change from chaining `ByteRange` per UTF-8 byte to chaining `CharRange` per char:
```java
private ThompsonRef compileLiteral(Hir.Literal lit) {
    char[] chars = lit.chars();
    if (chars.length == 0) {
        return compileEmpty();
    }
    int first = builder.add(new State.CharRange(chars[0], chars[0], 0));
    int prev = first;
    for (int i = 1; i < chars.length; i++) {
        int cur = builder.add(new State.CharRange(chars[i], chars[i], 0));
        builder.patch(prev, cur);
        prev = cur;
    }
    return new ThompsonRef(first, prev);
}
```

- [ ] **Step 2: Update `compileClass` to use `Utf16Sequences`**

Replace the `Utf8Sequences` usage with `Utf16Sequences.compile()`. For each returned sequence, chain `CharRange` states:
```java
private ThompsonRef compileClass(Hir.Class cls) {
    List<ClassUnicode.ClassUnicodeRange> ranges = cls.unicode().ranges();
    if (ranges.isEmpty()) {
        int id = builder.add(new State.Fail());
        return new ThompsonRef(id, id);
    }
    List<ThompsonRef> alternatives = new ArrayList<>();
    for (ClassUnicode.ClassUnicodeRange range : ranges) {
        List<int[][]> seqs = Utf16Sequences.compile(range.start(), range.end());
        for (int[][] seq : seqs) {
            ThompsonRef ref = compileCharSequence(seq);
            alternatives.add(ref);
        }
    }
    if (alternatives.size() == 1) {
        return alternatives.getFirst();
    }
    return buildUnion(alternatives);
}
```

- [ ] **Step 3: Add `compileCharSequence` helper**

```java
private ThompsonRef compileCharSequence(int[][] seq) {
    int first = builder.add(new State.CharRange(seq[0][0], seq[0][1], 0));
    int prev = first;
    for (int i = 1; i < seq.length; i++) {
        int cur = builder.add(new State.CharRange(seq[i][0], seq[i][1], 0));
        builder.patch(prev, cur);
        prev = cur;
    }
    return new ThompsonRef(first, prev);
}
```

- [ ] **Step 4: Remove `compileClassBytes` and `compileUtf8Sequence`**

Delete both methods. Remove the `case Hir.ClassB` arm from `compileNode` and the `case Hir.ClassB c -> false` arm from `canMatchEmpty`. Update `canMatchEmpty` to reference `l.chars().length` instead of `l.bytes().length`. The sealed interface compiler will enforce exhaustiveness if any arm is missed.

- [ ] **Step 5: Update unanchored start state**

Change the skip-any-byte state from `ByteRange(0x00, 0xFF)` to `CharRange(0x0000, 0xFFFF)`:
```java
// Covers all 16-bit char values including surrogates (0xD800-0xDFFF).
// Surrogates are valid char units even though they are not valid codepoints.
// The PikeVM loop advances by at++ through both halves of a surrogate pair,
// so this correctly skips over supplementary codepoints as two char steps.
int skipState = builder.add(new State.CharRange(0x0000, 0xFFFF, 0));
```

- [ ] **Step 6: Update CompilerTest**

Replace all `State.ByteRange` references with `State.CharRange`. Update test expectations:
- `compileMultiByteCharClass` (Euro sign): now produces 1 `CharRange` state (single BMP char) instead of 3 `ByteRange` states. Adjust assertion.
- `compileUnicodeRange` (Cyrillic): now produces 1 `CharRange` state (BMP range) instead of multi-byte states. Adjust assertion.

- [ ] **Step 7: Verify regex-automata compiles (expect PikeVM errors)**

Run: `./mvnw compile -pl regex-automata`
Expected: Compilation errors only in PikeVM (still references `ByteRange`, `byte[]`, `& 0xFF`).

- [ ] **Step 8: Commit**

```
git add regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Compiler.java
git add regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/CompilerTest.java
git commit -m "migrate Compiler to char-unit NFA with Utf16Sequences"
```

---

## Chunk 4: Input and PikeVM Migration (regex-automata)

### Task 8: Rewrite `Input` to hold `char[]` directly

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/util/Input.java`

- [ ] **Step 1: Replace `Input` internals**

Remove: `byte[] haystack`, `int[] byteToChar`, UTF-8 encoding logic, `toCharOffset()`, `withByteBounds()`.

Replace with:
```java
public final class Input {
    private final char[] haystack;
    private final int start;
    private final int end;
    private final boolean anchored;

    private Input(char[] haystack, int start, int end, boolean anchored) {
        this.haystack = haystack;
        this.start = start;
        this.end = end;
        this.anchored = anchored;
    }

    public static Input of(CharSequence text) {
        char[] chars = text.toString().toCharArray();
        return new Input(chars, 0, chars.length, false);
    }

    public static Input of(CharSequence text, int start, int end) {
        char[] chars = text.toString().toCharArray();
        return new Input(chars, start, end, false);
    }

    public static Input anchored(CharSequence text) {
        char[] chars = text.toString().toCharArray();
        return new Input(chars, 0, chars.length, true);
    }

    public char[] haystack() { return haystack; }
    public int start() { return start; }
    public int end() { return end; }
    public boolean isAnchored() { return anchored; }

    public Input withBounds(int newStart, int newEnd, boolean newAnchored) {
        return new Input(haystack, newStart, newEnd, newAnchored);
    }

    /** Returns true if the position is NOT in the middle of a surrogate pair. */
    public boolean isCharBoundary(int pos) {
        if (pos <= 0 || pos >= haystack.length) return true;
        return !Character.isLowSurrogate(haystack[pos]);
    }
}
```

- [ ] **Step 2: Verify Input compiles**

Run: `./mvnw compile -pl regex-automata`
Expected: Compilation errors in PikeVM (still references byte-oriented Input methods). Input itself should compile cleanly.

### Task 9: Migrate PikeVM to char-unit

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/pikevm/PikeVM.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/pikevm/PikeVMTest.java`

- [ ] **Step 1: Update `searchSlots` to use `char[]`**

Change `byte[] haystack = input.haystack()` to `char[] haystack = input.haystack()`. The loop structure (`at++`, `at <= end`) remains the same.

- [ ] **Step 2: Update `nexts()` — replace `ByteRange` with `CharRange`, remove `& 0xFF`**

```java
case State.CharRange cr -> {
    if (at < end) {
        int c = haystack[at];
        if (c >= cr.start() && c <= cr.end()) {
            curr.readSlots(sid, scratchSlots, 0);
            epsilonClosure(stack, scratchSlots, next, haystack, at + 1, cr.next());
        }
    }
}
case State.Sparse sp -> {
    if (at < end) {
        int c = haystack[at];  // no & 0xFF
        for (Transition t : sp.transitions()) {
            if (c >= t.start() && c <= t.end()) {
                curr.readSlots(sid, scratchSlots, 0);
                epsilonClosure(stack, scratchSlots, next, haystack, at + 1, t.next());
                break;
            }
        }
    }
}
```

Remove the `State.Dense` case entirely.

- [ ] **Step 3: Update `epsilonClosureExplore` — remove `Dense` case, update `CharRange`**

Replace `State.ByteRange` with `State.CharRange` in the switch. Remove the `State.Dense` case.

- [ ] **Step 4: Replace look-around assertion logic**

Replace the entire `checkLook` method's `byte[]` parameter with `char[]`. Replace all UTF-8 decode helpers (`decodeUtf8Fwd`, `decodeUtf8Rev`, `isValidUtf8BoundaryFwd`, `isValidUtf8BoundaryRev`, `utf8Len`) with `Character.codePointAt(haystack, at)` and `Character.codePointBefore(haystack, at)`.

For word boundary assertions:
```java
case WORD_BOUNDARY_UNICODE -> {
    boolean wordBefore = at > 0 && isUnicodeWordChar(Character.codePointBefore(haystack, at));
    boolean wordAfter = at < len && isUnicodeWordChar(Character.codePointAt(haystack, at));
    yield wordBefore != wordAfter;
}
```

Remove `isWordCharFwd`, `isWordCharRev`, `isValidUtf8BoundaryFwd`, `isValidUtf8BoundaryRev`, `decodeUtf8Fwd`, `decodeUtf8Rev`, `utf8Len`. Keep `isUnicodeWordChar` (codepoint-level, encoding-independent) and `isWordByte` renamed to `isWordChar` for ASCII checks.

- [ ] **Step 5: Update `skipSplitsFwd` for surrogate pairs**

Replace the UTF-8 boundary check with a surrogate pair check. The purpose of `skipSplitsFwd` is to filter empty matches that split a surrogate pair. When an empty match starts at a position that is between a high and low surrogate, advance past it:
```java
// If the empty match splits a surrogate pair, skip forward.
// An empty match at position `at` splits a pair if haystack[at-1] is a high surrogate
// and haystack[at] is a low surrogate.
if (at > 0 && at < haystack.length
        && Character.isHighSurrogate(haystack[at - 1])
        && Character.isLowSurrogate(haystack[at])) {
    // Advance past the low surrogate to avoid splitting the pair
    newStart = at + 1;
}
```
When advancing past a split match, the step size is 1 (skip the low surrogate). The overall search loop may then re-try from after the complete surrogate pair.

- [ ] **Step 6: Update PikeVMTest**

Replace `State.ByteRange` references with `State.CharRange`. Update any direct byte-oriented test logic to char-oriented.

- [ ] **Step 7: Verify regex-automata compiles**

Run: `./mvnw compile -pl regex-automata`
Expected: No compilation errors.

- [ ] **Step 8: Commit**

```
git add regex-automata/
git commit -m "migrate Input and PikeVM to char-unit operation"
```

---

## Chunk 5: Test Harness and Public API Updates

### Task 10: Update `PikeVMSuiteTest` for char-offset comparison

**Files:**
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/pikevm/PikeVMSuiteTest.java`

- [ ] **Step 1: Add byte-to-char offset conversion helper**

Add a helper to convert expected byte-offset spans from the TOML test suite to char-offset spans:
```java
private static int[] buildByteToCharMap(String haystack) {
    byte[] utf8 = haystack.getBytes(StandardCharsets.UTF_8);
    int[] map = new int[utf8.length + 1];
    int charIdx = 0;
    int byteIdx = 0;
    while (charIdx < haystack.length()) {
        int cp = Character.codePointAt(haystack, charIdx);
        int charCount = Character.charCount(cp);
        int byteCount = cp <= 0x7F ? 1 : cp <= 0x7FF ? 2 : cp <= 0xFFFF ? 3 : 4;
        for (int b = 0; b < byteCount; b++) {
            map[byteIdx + b] = charIdx;
        }
        byteIdx += byteCount;
        charIdx += charCount;
    }
    map[byteIdx] = charIdx;
    return map;
}
```

- [ ] **Step 2: Convert expected spans before comparison**

In `collectMatches` and `collectCaptures`, convert the spans returned by the PikeVM (now char offsets) to byte offsets for comparison. Or, alternatively, convert the expected byte-offset spans to char offsets. The latter is cleaner:
```java
private static Span toCharSpan(Span byteSpan, int[] byteToChar) {
    return new Span(byteToChar[byteSpan.start()], byteToChar[byteSpan.end()]);
}
```
Apply this conversion to `test.matches()` expected values before comparing.

- [ ] **Step 3: Update `createInput` — remove `withByteBounds`**

Replace `Input.withByteBounds()` calls with char-offset bounds. Convert `test.bounds()` byte offsets to char offsets using the same map. If the byte bounds land mid-codepoint (i.e., the byte offset maps to the same char index as the previous byte), skip the test with `Assumptions.assumeTrue(false, "bounds land mid-codepoint")`.

- [ ] **Step 4: Run the PikeVM upstream suite**

Run: `./mvnw test -pl regex-automata -Dtest="PikeVMSuiteTest"`
Expected: All tests pass with the same count as before.

### Task 11: Simplify `Regex` public API and `UpstreamSuiteTest`

**Files:**
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`
- Modify: `regex/src/test/java/lol/ohai/regex/UpstreamSuiteTest.java`

- [ ] **Step 1: Remove `toCharOffset` calls from `Regex.java`**

The PikeVM now returns char offsets directly. Remove all `input.toCharOffset()` calls. Match construction simplifies:
```java
private Match createMatch(CharSequence text,
                          lol.ohai.regex.automata.util.Captures caps, int group) {
    int start = caps.start(group);
    int end = caps.end(group);
    return new Match(start, end, text.subSequence(start, end).toString());
}
```

The `findAll`/`capturesAll` iterator logic also simplifies — all offsets are already in char units.

- [ ] **Step 2: Update `UpstreamSuiteTest` offset conversion**

`UpstreamSuiteTest` already has `buildCharToByteMap`. Keep this but reverse the conversion direction: convert expected byte spans to char spans (like `PikeVMSuiteTest` in Task 10). Remove the current char-to-byte conversion of our output.

- [ ] **Step 3: Run all tests**

Run: `./mvnw test`
Expected: All ~1,946 tests pass.

- [ ] **Step 4: Commit**

```
git add regex-automata/ regex/
git commit -m "update test harnesses and Regex API for char-unit offsets"
```

---

## Chunk 6: Benchmark Validation

### Task 12: Re-run benchmarks and compare

**Files:**
- No code changes

- [ ] **Step 1: Rebuild benchmark JAR**

Run: `./mvnw -P bench package -DskipTests`

- [ ] **Step 2: Run search benchmarks**

```bash
java -jar regex-bench/target/benchmarks.jar "SearchBenchmark" -f 1 -wi 3 -i 5
```

Compare literal search throughput against pre-migration baseline (0.4 ops/s). Expected: significant improvement since UTF-8 transcoding is eliminated.

- [ ] **Step 3: Run compile benchmarks**

```bash
java -jar regex-bench/target/benchmarks.jar "CompileBenchmark" -f 1 -wi 3 -i 5
```

Compilation should also improve slightly — no UTF-8 encoding step in the Translator.

- [ ] **Step 4: Run pathological benchmarks**

```bash
java -jar regex-bench/target/benchmarks.jar "PathologicalBenchmark" -f 1 -wi 3 -i 5
```

Verify linear-time guarantees still hold.

- [ ] **Step 5: Commit benchmark results as a note**

Record baseline comparison in a commit message or MEMORY.md.
