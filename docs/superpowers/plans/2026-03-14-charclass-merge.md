# CharClass Equivalence Merge Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge boundary regions with identical NFA transition behavior in `CharClassBuilder` so that Unicode patterns like `\w+` produce ≤256 equivalence classes, enabling DFA-only search without quit-on-non-ASCII fallback.

**Architecture:** Add a merge step between boundary collection and flatMap construction in `CharClassBuilder.build()`. Compute a behavior signature (BitSet of matching NFA state/transition targets) for each boundary region, group by signature, assign one class ID per group in ascending region order. The old `buildUnmerged()` method is kept alongside the new `build()` for A/B benchmarking. No changes to `CharClasses`, `DFACache`, `LazyDFA`, or search paths.

**Tech Stack:** Java 21, JUnit 5, JMH (benchmarks via `regex-bench`)

**Spec:** `docs/superpowers/specs/2026-03-14-charclass-merge-design.md`

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `regex-automata/src/main/java/.../dfa/CharClassBuilder.java` | Modify | Add merge step, keep old logic as `buildUnmerged()` for A/B comparison |
| `regex-automata/src/test/java/.../dfa/CharClassesTest.java` | Modify | Add tests for merge behavior, class count verification, Unicode correctness |
| `regex/src/main/java/.../Regex.java` | Modify | Update 3 call sites from `CharClassBuilder.build(nfa, quit)` to keep compiling after rename |

---

### Task 1: Rename current build to buildUnmerged for A/B comparison

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`

The existing `build(NFA, boolean)` becomes `buildUnmerged(NFA, boolean)`. The existing `build(NFA)` (which already delegates to `build(NFA, false)`) is updated to call `buildUnmerged`. `Regex.java` has 3 direct call sites of `build(nfa, quitNonAscii)` that must also be updated.

- [ ] **Step 1: Rename `build(NFA, boolean)` to `buildUnmerged(NFA, boolean)`**

Make it package-private (for test access). Update `build(NFA)` to delegate to `buildUnmerged`:

```java
public static CharClasses build(NFA nfa) {
    return buildUnmerged(nfa, false);
}

// Renamed from build(NFA, boolean). Kept for A/B benchmarking.
static CharClasses buildUnmerged(NFA nfa, boolean quitNonAscii) {
    // ... exact existing code unchanged ...
}
```

- [ ] **Step 2: Update Regex.java call sites**

Three places in `Regex.java` call `CharClassBuilder.build(nfa, quitNonAscii)` directly (lines 94, 101, 134). Update them to `CharClassBuilder.buildUnmerged(nfa, quitNonAscii)` temporarily. Task 3 will reintroduce `build(NFA, boolean)` with the merge step, and these call sites will be updated back.

- [ ] **Step 3: Run full test suite**

Run: `./mvnw test`
Expected: All 2,154 tests pass (pure rename, no behavior change).

- [ ] **Step 4: Commit**

```
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java \
       regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "refactor: rename CharClassBuilder.build to buildUnmerged for A/B comparison"
```

---

### Task 2: Write tests for the merge behavior

**Files:**
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/CharClassesTest.java`

- [ ] **Step 1: Add test that `\w+` produces non-null CharClasses with merged build**

This test calls `buildUnmerged` initially (which returns null for `\w+` due to overflow). After Task 3 introduces the new `build()`, it will be updated to call `build()` and pass.

```java
@Test
void mergedBuildHandlesUnicodeWordClass() {
    Ast ast = Parser.parse("\\w+", 250);
    Hir hir = Translator.translate("\\w+", ast);
    NFA nfa = Compiler.compile(hir);

    // Will call new build() after Task 3; for now, demonstrates the goal
    CharClasses cc = CharClassBuilder.build(nfa);
    assertNotNull(cc, "merged build must handle \\w+ without overflow");
    assertTrue(cc.classCount() <= 30,
            "merged \\w+ should have few classes, got: " + cc.classCount());
    assertFalse(cc.hasQuitClasses(),
            "merged \\w+ without \\b should not use quit classes");
}
```

- [ ] **Step 2: Add test that Unicode non-word chars classify differently from word chars**

```java
@Test
void mergedBuildClassifiesUnicodeCorrectly() {
    Ast ast = Parser.parse("\\w+", 250);
    Hir hir = Translator.translate("\\w+", ast);
    NFA nfa = Compiler.compile(hir);
    CharClasses cc = CharClassBuilder.build(nfa);
    assertNotNull(cc);

    int classA = cc.classify('a');
    int classZ = cc.classify('z');
    assertEquals(classA, classZ, "a and z are both word chars");

    // U+2961 (math symbol) must NOT be in the same class as word chars
    int classMath = cc.classify('\u2961');
    assertNotEquals(classA, classMath,
            "U+2961 (math symbol) must not share class with word chars");

    // Two non-word Unicode chars should share a class
    int classOther = cc.classify('\u2962');
    assertEquals(classMath, classOther,
            "adjacent non-word Unicode chars should share a class");
}
```

- [ ] **Step 3: Add test that `\b\w+\b` still uses quit classes**

```java
@Test
void mergedBuildPreservesQuitForWordBoundary() {
    Ast ast = Parser.parse("\\b\\w+\\b", 250);
    Hir hir = Translator.translate("\\b\\w+\\b", ast);
    NFA nfa = Compiler.compile(hir);
    boolean quitNonAscii = nfa.lookSetAny().containsUnicodeWord();

    assertTrue(quitNonAscii, "\\b should trigger quitNonAscii");
    CharClasses cc = CharClassBuilder.build(nfa, quitNonAscii);
    assertNotNull(cc);
    assertTrue(cc.hasQuitClasses(), "\\b pattern must use quit classes");
}
```

- [ ] **Step 4: Add test that `\n` and `\r` get correct metadata for line-assertion patterns**

```java
@Test
void mergedBuildPreservesLineFeedIsolation() {
    // For patterns with line assertions, \n must be in its own class
    // with lineLF=true, separate from other non-word chars like ' '
    Ast ast = Parser.parse("(?m)^\\w+$", 250);
    Hir hir = Translator.translate("(?m)^\\w+$", ast);
    NFA nfa = Compiler.compile(hir);
    CharClasses cc = CharClassBuilder.build(nfa);
    assertNotNull(cc);

    int classLF = cc.classify('\n');
    int classSpace = cc.classify(' ');
    assertTrue(cc.isLineLF(classLF), "\\n class must have lineLF flag");
    assertFalse(cc.isLineLF(classSpace), "space class must not have lineLF flag");
    assertNotEquals(classLF, classSpace,
            "\\n and space must be in different classes for line-assertion patterns");
}
```

- [ ] **Step 5: Add A/B comparison test (merged vs unmerged)**

```java
@Test
void mergedAndUnmergedAgreeOnSimplePatterns() {
    for (String pattern : List.of("[a-z]+", "abc", "[0-9a-fA-F]+", "Sherlock|Watson")) {
        Ast ast = Parser.parse(pattern, 250);
        Hir hir = Translator.translate(pattern, ast);
        NFA nfa = Compiler.compile(hir);

        CharClasses merged = CharClassBuilder.build(nfa);
        CharClasses unmerged = CharClassBuilder.buildUnmerged(nfa, false);
        assertNotNull(merged, "merged must succeed for: " + pattern);
        assertNotNull(unmerged, "unmerged must succeed for: " + pattern);

        // Chars in the same unmerged class must also be in the same merged class
        // (merged may group MORE chars together, but never split them)
        for (int c = 0; c < 128; c++) {
            for (int d = c + 1; d < 128; d++) {
                boolean mergedSame = merged.classify((char) c) == merged.classify((char) d);
                boolean unmergedSame = unmerged.classify((char) c) == unmerged.classify((char) d);
                if (unmergedSame) {
                    assertTrue(mergedSame,
                        "merged must not split chars that unmerged keeps together: " +
                        pattern + " chars " + c + "," + d);
                }
            }
        }
    }
}
```

- [ ] **Step 6: Add class count comparison test**

```java
@Test
void compareClassCountsMergedVsUnmerged() {
    Ast ast = Parser.parse("\\w+", 250);
    Hir hir = Translator.translate("\\w+", ast);
    NFA nfa = Compiler.compile(hir);

    CharClasses merged = CharClassBuilder.build(nfa);
    CharClasses unmerged = CharClassBuilder.buildUnmerged(nfa, false);

    assertNotNull(merged, "merged must not be null");
    assertNull(unmerged, "unmerged should overflow for \\w+");

    System.out.println("Merged class count for \\w+: " + merged.classCount());
    System.out.println("Merged stride for \\w+: " + merged.stride());
}
```

- [ ] **Step 7: Run tests — new merge tests should FAIL until Task 3**

Run: `./mvnw test -Dtest="CharClassesTest"`
Expected: `mergedBuildHandlesUnicodeWordClass` and related tests FAIL (build returns null for `\w+`). Others pass.

- [ ] **Step 8: Commit**

```
git add regex-automata/src/test/java/lol/ohai/regex/automata/dfa/CharClassesTest.java
git commit -m "test: add CharClasses merge tests (failing until merge is implemented)"
```

---

### Task 3: Implement the merge step

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`

- [ ] **Step 1: Add `computeSignature` helper method**

```java
/**
 * Compute the behavior signature for a representative character.
 * The signature is the set of NFA state/transition targets that match
 * this character. Two chars with the same signature are interchangeable
 * in the DFA.
 *
 * For CharRange states: add the state ID if the char is in range.
 * For Sparse states: add t.next (the target state ID) of the matching
 * transition — not the Sparse state ID, because different transitions
 * within the same Sparse state lead to different next states.
 */
private static BitSet computeSignature(NFA nfa, int representative) {
    BitSet sig = new BitSet(nfa.stateCount());
    for (int i = 0; i < nfa.stateCount(); i++) {
        State state = nfa.state(i);
        switch (state) {
            case State.CharRange cr -> {
                if (representative >= cr.start() && representative <= cr.end()) {
                    sig.set(i);
                }
            }
            case State.Sparse sp -> {
                for (Transition t : sp.transitions()) {
                    if (representative >= t.start() && representative <= t.end()) {
                        sig.set(t.next());
                        break;
                    }
                }
            }
            default -> {}
        }
    }
    return sig;
}
```

- [ ] **Step 2: Add the new `build(NFA, boolean)` with merge step**

Class IDs are assigned in ascending region order (`nextClassId++` in the loop) to preserve the `classify(rangeStart) <= classify(rangeEnd)` invariant that `LazyDFA.charInRange()` depends on. Uses `LinkedHashMap` for clarity (though the ordering comes from the loop, not the map).

```java
public static CharClasses build(NFA nfa, boolean quitNonAscii) {
    TreeSet<Integer> boundaries = collectBoundaries(nfa, quitNonAscii);
    int[] sortedBounds = boundaries.stream().mapToInt(Integer::intValue).toArray();
    int regionCount = sortedBounds.length - 1;

    // Merge regions with identical NFA transition behavior.
    // Class IDs assigned in ascending region order to preserve
    // classify(rangeStart) <= classify(rangeEnd) for charInRange().
    LinkedHashMap<BitSet, Integer> signatureToClass = new LinkedHashMap<>();
    int[] regionClassMap = new int[regionCount];
    int nextClassId = 0;

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

    int classCount = nextClassId;
    if (classCount > 256) {
        return null;  // safety net
    }

    // Build flatMap using merged class IDs
    byte[] flatMap = new byte[65536];
    for (int r = 0; r < regionCount; r++) {
        int from = sortedBounds[r];
        int to = Math.min(sortedBounds[r + 1], 65536);
        byte cls = (byte) regionClassMap[r];
        for (int c = from; c < to; c++) {
            flatMap[c] = cls;
        }
    }

    // Two-level row deduplication (unchanged)
    Map<ByteArrayKey, Integer> rowIndex = new HashMap<>();
    List<byte[]> uniqueRows = new ArrayList<>();
    int[] highIndex = new int[256];
    for (int hi = 0; hi < 256; hi++) {
        byte[] row = new byte[256];
        System.arraycopy(flatMap, hi * 256, row, 0, 256);
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

    // Build metadata arrays using first-seen representative per class
    int[] classRepresentative = new int[classCount];
    Arrays.fill(classRepresentative, -1);
    for (int r = 0; r < regionCount; r++) {
        int cls = regionClassMap[r];
        if (classRepresentative[cls] < 0) {
            classRepresentative[cls] = sortedBounds[r];
        }
    }

    boolean[] wordClass = new boolean[classCount];
    boolean[] lineLF = new boolean[classCount];
    boolean[] lineCR = new boolean[classCount];
    for (int cls = 0; cls < classCount; cls++) {
        int rep = classRepresentative[cls];
        if (rep >= 0 && rep < 65536) {
            char c = (char) rep;
            wordClass[cls] = isWordChar(c);
            lineLF[cls] = (c == '\n');
            lineCR[cls] = (c == '\r');
        }
    }

    boolean[] quitClassArr = null;
    if (quitNonAscii) {
        quitClassArr = new boolean[classCount];
        for (int cls = 0; cls < classCount; cls++) {
            int rep = classRepresentative[cls];
            if (rep >= 128 && rep < 65536) {
                quitClassArr[cls] = true;
            }
        }
    }

    return new CharClasses(uniqueRows.toArray(byte[][]::new), highIndex, classCount,
            wordClass, lineLF, lineCR, quitClassArr);
}
```

- [ ] **Step 3: Update `build(NFA)` to call new merged `build(NFA, boolean)`**

```java
public static CharClasses build(NFA nfa) {
    return build(nfa, false);
}
```

- [ ] **Step 4: Update Regex.java call sites back to `build()`**

Revert the 3 `buildUnmerged` calls in `Regex.java` (from Task 1) back to `CharClassBuilder.build(nfa, quitNonAscii)`, which now calls the new merged version.

- [ ] **Step 5: Run full test suite**

Run: `./mvnw test`
Expected: All 2,154 tests pass, including the new merge tests from Task 2.

- [ ] **Step 6: Commit**

```
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java \
       regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "feat: add equivalence class merging to CharClassBuilder

Merge boundary regions with identical NFA transition behavior so that
Unicode patterns like \w+ produce ≤256 equivalence classes. Compute a
BitSet signature of matching NFA state/transition targets per region,
group by signature, assign one class ID per group in ascending region
order (preserving charInRange monotonicity invariant).

The old buildUnmerged() is kept for A/B benchmarking comparison.

Ref: upstream/regex/regex-automata/src/util/alphabet.rs:673-682"
```

---

### Task 4: A/B benchmark comparison

- [ ] **Step 1: Build and run benchmarks**

Run: `./mvnw -P bench package -DskipTests && java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5 "Search"`

Record all numbers. Compare against Stage 6 numbers:
- `unicodeWord` should improve dramatically (from ~18 ops/s)
- Other benchmarks should be unchanged or slightly better

- [ ] **Step 2: Verify no regression > 2x in any benchmark**

Per CLAUDE.md rules, any >2x change requires investigation.

- [ ] **Step 3: Commit benchmark observations as a comment in the A/B test**

Update `compareClassCountsMergedVsUnmerged` test with actual observed class counts as comments.

---

### Task 5: Update docs, tag stage, push

**Files:**
- Modify: `docs/architecture/dfa-match-semantics-gap.md`
- Modify: `docs/architecture/stage-progression.md`

- [ ] **Step 1: Update gap doc — mark char class overflow as resolved**

Replace the "Char Class Overflow — Open" section with "Resolved" and describe the merge fix.

- [ ] **Step 2: Tag the release**

```bash
git tag stage-7-charclass-merge
git push --tags
```

- [ ] **Step 3: Add stage entry to progression doc**

Add `stage-7-charclass-merge` entry with description, class count stats, benchmark numbers, test count.

- [ ] **Step 4: Commit and push**

```bash
git add docs/architecture/dfa-match-semantics-gap.md docs/architecture/stage-progression.md
git commit -m "docs: add stage-7-charclass-merge to progression tracking"
git push
```
