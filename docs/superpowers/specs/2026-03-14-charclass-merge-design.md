# Design: Equivalence Class Merging in CharClassBuilder

**Date:** 2026-03-14
**Status:** Draft

## Problem

Our `CharClassBuilder` creates one equivalence class per boundary region from NFA transitions. For Unicode character classes like `\w`, the NFA has hundreds of char ranges, producing ~1,400 boundary regions → ~1,400 classes. Since class IDs are stored as `byte` (0-255), this overflows. The current mitigation auto-enables quit-on-non-ASCII, making the DFA give up on every non-ASCII character and falling back to PikeVM. This makes `\w+` on mixed Unicode text ~2,000x slower than JDK.

The upstream Rust crate avoids this because it operates on UTF-8 bytes (alphabet 0-255), so boundary regions can never exceed 256. Our char-unit DFA has a 65,536-value alphabet and needs an explicit merge step.

## Solution

Add a merge step to `CharClassBuilder.build()` that collapses boundary regions with identical NFA transition behavior into a single equivalence class. Characters that appear in exactly the same set of NFA transitions are interchangeable from the DFA's perspective — they can safely share a class ID.

### Scope

**Changes:** `CharClassBuilder.build()` only — add merge step between boundary collection and flatMap construction.

**No changes to:** `CharClasses` (lookup table structure), `DFACache` (transition table), `LazyDFA` (search loops), `Strategy` (search paths), `Regex` (compilation pipeline).

**Removes:** The auto-quit-on-non-ASCII retry logic for class overflow becomes unnecessary (keep null return as safety net).

**Does NOT remove:** The `quitNonAscii` flag for Unicode word boundary assertions (`\b`). That flag is driven by the look-assertion type (`nfa.lookSetAny().containsUnicodeWord()`), not by class count. Patterns with `\b` still need quit-on-non-ASCII for correct word boundary resolution — the merge step fixes the class overflow problem, not the Unicode word boundary DFA limitation.

## Algorithm

### Current flow (boundary-based, no merge)

```
NFA transitions → boundary points → sorted array → one class per region → flatMap
```

For `\w+`: ~1,400 regions → overflow → auto-quit fallback.

### New flow (boundary-based + merge)

```
NFA transitions → boundary points → sorted array → regions
  → compute behavior signature per region
  → group by signature → one class per group
  → flatMap + metadata arrays
```

For `\w+`: ~1,400 regions → ~10 signatures → 10 classes → fits in byte.

### Merge step detail

For each boundary region `[sortedBounds[i], sortedBounds[i+1])`:

1. Pick a representative character: `rep = sortedBounds[i]`
2. Compute its **behavior signature**: iterate all NFA states. For each `CharRange(start, end)`, check if `start <= rep && rep <= end` — if so, add `stateId` to the signature. For each `Sparse` state, iterate its transitions: for the transition `t` where `t.start <= rep && rep <= t.end`, add `t.next` to the signature (the target state, not the Sparse state ID — two chars hitting different transitions within the same Sparse state have different behavior).
3. Two regions with identical signatures get the same class ID.

**Class ID assignment order:** Class IDs MUST be assigned in ascending region order (first-seen-region gets the lowest ID). This preserves the invariant that `classify(rangeStart) <= classify(rangeEnd)` for every NFA transition range, which `LazyDFA.charInRange()` depends on. The implementation sketch below achieves this via `nextClassId++` in encounter order.

**Why one representative suffices:** Boundary regions are defined BY the NFA transition endpoints. A region `[lo, hi)` is guaranteed to be entirely inside or entirely outside each NFA transition. So any character in the region has the same signature.

**Complexity:** O(R × S) where R = number of regions (~1,400 for `\w+`) and S = number of NFA char-consuming states (~700 for `\w+`). This is ~1M operations — fast, and one-time at compilation.

### Implementation sketch

```java
// After collecting sortedBounds:
int regionCount = sortedBounds.length - 1;

// Step 1: Compute signature for each region, assign class IDs in region order
Map<SignatureKey, Integer> signatureToClass = new LinkedHashMap<>();
int[] regionClassMap = new int[regionCount];
int nextClassId = 0;

for (int r = 0; r < regionCount; r++) {
    int representative = sortedBounds[r];
    SignatureKey sig = computeSignature(nfa, representative);

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

// Step 2: Build flatMap using merged class IDs
byte[] flatMap = new byte[65536];
for (int r = 0; r < regionCount; r++) {
    int from = sortedBounds[r];
    int to = Math.min(sortedBounds[r + 1], 65536);
    byte cls = (byte) regionClassMap[r];
    for (int c = from; c < to; c++) {
        flatMap[c] = cls;
    }
}

// Step 3: Build metadata arrays indexed by merged class ID.
// Use the FIRST region mapped to each class as the representative.
// Since look-assertion boundaries (\n, \r, word-char ASCII ranges)
// are injected by collectBoundaries(), \n and \r always get their own
// boundary regions and thus their own class IDs — they never merge
// with non-\n/non-\r chars.
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
```

### Signature key

The signature for a character is the set of NFA transitions that match it. For `CharRange` states, this is the state ID. For `Sparse` states, this is the target state ID (`t.next`) of the matching transition — not the Sparse state ID itself, because different transitions within the same Sparse state lead to different next states and thus different DFA behavior.

Recommendation: Use `BitSet` wrapped in a key type with proper `equals`/`hashCode`. NFA state counts are typically 10-1000, well within `BitSet`'s range. For `Sparse` targets, include the `next` state ID in the BitSet.

### Invariants preserved

1. **`classify(rangeStart) <= classify(rangeEnd)`** for every NFA `CharRange`/`Sparse` transition. Guaranteed by assigning class IDs in ascending region order.
2. **`\n` and `\r` always get their own class.** Guaranteed by `collectBoundaries()` injecting explicit boundaries at `'\n'`, `'\n'+1`, `'\r'`, `'\r'+1` when look assertions are present.
3. **`quitNonAscii` remains independent of merge.** The quit flag is set by `nfa.lookSetAny().containsUnicodeWord()`, not by class count. `LazyDFA.create()` checks `containsUnicodeWord() && !hasQuitClasses()` to decide whether to bail. The merge step does not affect this — patterns with Unicode `\b` still use quit classes.

## Expected results

### Class counts after merging

| Pattern | Boundary regions | Merged classes | Explanation |
|---|---|---|---|
| `\w+` | ~1,400 | ~10 | All word-char regions share one signature (match the `\w` transition). Non-word regions share another. Look-assertion boundaries split `\n`, `\r`, `0-9`, `A-Z`, `_`, `a-z` into separate classes. |
| `\d+` | ~60 | ~4 | Digit regions merge, non-digit regions merge, plus ASCII boundary splits. |
| `[a-zA-Z]+` | ~6 | ~6 | Already under 256, merge is a no-op. |
| `\p{Greek}` | ~200 | ~4 | Greek-char regions merge, non-Greek merge. |
| `Sherlock Holmes` | ~20 | ~20 | Literal patterns have few regions, no merge needed. |

### Performance impact

- **`unicodeWord` (`\w+`)**: Major improvement. DFA handles full Unicode without quit fallback. Expected: from ~18 ops/s to thousands of ops/s.
- **Other benchmarks**: No regression expected. Fewer classes → smaller stride → slightly faster DFA transitions. Class merging is build-time only, doesn't affect search.
- **Compilation time**: Negligible increase from merge step (O(regions × states) ≈ 1M ops).

### Testing

1. All existing tests must pass unchanged (2,154 across all modules as of stage-6)
2. New JUnit test in `regex-automata/src/test/java/.../dfa/CharClassesTest.java`:
   - Verify `\w+` produces non-null `CharClasses` without quit fallback
   - Verify class count is well under 256 (e.g., ≤ 30)
   - Verify specific Unicode chars classify correctly (U+2961 must NOT be in word class)
   - Verify `\b\w+\b` still uses quit classes (merge doesn't affect Unicode word boundary handling)
3. Benchmark comparison: `./mvnw -P bench package -DskipTests && java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5`

## What gets removed

The auto-quit-on-non-ASCII **retry for class overflow** in `CharClassBuilder.build()` (the `if (boundaries.size() - 1 > 256 && !quitNonAscii)` block) becomes unnecessary because the merge step keeps class counts under 256 for all practical patterns. Keep the null return as a safety net for truly pathological patterns (e.g., a pattern with >256 genuinely distinct character behaviors), but it should never fire in practice.

The `quitNonAscii` parameter itself stays — it is still needed for patterns with Unicode word boundaries (`\b`), where the DFA must quit on non-ASCII characters for correct look-assertion resolution.

## References

- Upstream `ByteClasses` construction: `upstream/regex/regex-automata/src/util/alphabet.rs:661-738`
- Upstream comment on non-minimal classes: `alphabet.rs:673-682` — "this does not compute the minimal set of equivalence classes" (acceptable because byte alphabet ≤ 256)
- Our `CharClassBuilder`: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`
- Our `LazyDFA.charInRange()`: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java:582-586` (depends on monotonic class ID assignment)
- DFA match semantics gap doc: `docs/architecture/dfa-match-semantics-gap.md` (char class overflow section)
