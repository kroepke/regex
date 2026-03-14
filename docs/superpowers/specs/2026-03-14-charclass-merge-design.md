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

**Removes:** The auto-quit-on-non-ASCII retry logic becomes unnecessary (keep as safety net).

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
  → flatMap
```

For `\w+`: ~1,400 regions → ~10 signatures → 10 classes → fits in byte.

### Merge step detail

For each boundary region `[sortedBounds[i], sortedBounds[i+1])`:

1. Pick a representative character: `rep = sortedBounds[i]`
2. Compute its **behavior signature**: iterate all NFA states. For each `CharRange(start, end)` or `Sparse` transition, check if `start <= rep && rep <= end`. Collect matching NFA state IDs into a sorted `int[]` or `BitSet`.
3. Two regions with identical signatures get the same class ID.

**Why one representative suffices:** Boundary regions are defined BY the NFA transition endpoints. A region `[lo, hi)` is guaranteed to be entirely inside or entirely outside each NFA transition. So any character in the region has the same signature.

**Complexity:** O(R × S) where R = number of regions (~1,400 for `\w+`) and S = number of NFA char-consuming states (~700 for `\w+`). This is ~1M operations — fast, and one-time at compilation.

### Implementation sketch

```java
// After collecting sortedBounds:
int regionCount = sortedBounds.length - 1;

// Step 1: Compute signature for each region
Map<SignatureKey, Integer> signatureToClass = new HashMap<>();
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
```

### Signature key

The signature for a character is the set of NFA state IDs whose transitions contain it. Options:

- **`BitSet`**: Good for NFA state counts up to ~1000. Equality/hashCode via `BitSet.equals()`.
- **Sorted `int[]`**: Good for sparse signatures. Wrapped in a record for equals/hashCode.
- **`long` bitmask**: If NFA state count ≤ 64, a single long suffices. Fast but limited.

Recommendation: Use `BitSet` wrapped in a key type with proper `equals`/`hashCode`. NFA state counts are typically 10-1000, well within `BitSet`'s range.

## Expected results

### Class counts after merging

| Pattern | Boundary regions | Merged classes | Status |
|---|---|---|---|
| `\w+` | ~1,400 | ~10 | Fixed (was: overflow → quit) |
| `\d+` | ~60 | ~4 | Fixed (was: marginal) |
| `[a-zA-Z]+` | ~6 | ~6 | Unchanged (already under 256) |
| `\p{Greek}` | ~200 | ~4 | Fixed |
| `Sherlock Holmes` | ~20 | ~20 | Unchanged |

### Performance impact

- **`unicodeWord` (`\w+`)**: Major improvement. DFA handles full Unicode without quit fallback. Expected: from ~18 ops/s to ~15,000+ ops/s (matching Stage 4's DFA-only speed, now with correct edge handling).
- **Other benchmarks**: No regression expected. Fewer classes → smaller stride → slightly faster DFA transitions. Class merging is build-time only, doesn't affect search.
- **Compilation time**: Negligible increase from merge step (O(regions × states) ≈ 1M ops).

### Testing

1. All 2,154 existing tests must pass unchanged
2. New JUnit test in `regex-automata/src/test/java/.../dfa/CharClassesTest.java`:
   - Verify `\w+` produces non-null `CharClasses` without quit fallback
   - Verify class count is well under 256 (e.g., ≤ 30)
   - Verify specific Unicode chars classify correctly (U+2961 ≠ word class)
3. Benchmark comparison: `./mvnw -P bench package -DskipTests && java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5`

## What gets removed

The auto-quit-on-non-ASCII retry in `CharClassBuilder.build()` (lines 34-40 in current code) becomes unnecessary because the merge step keeps class counts under 256 for all practical patterns. Keep the null return as a safety net for truly pathological patterns (e.g., a pattern with >256 genuinely distinct character behaviors), but it should never fire in practice.

## References

- Upstream `ByteClasses` construction: `upstream/regex/regex-automata/src/util/alphabet.rs:661-738`
- Upstream comment on non-minimal classes: `alphabet.rs:673-682` — "this does not compute the minimal set of equivalence classes" (acceptable because byte alphabet ≤ 256)
- Our `CharClassBuilder`: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`
- DFA match semantics gap doc: `docs/architecture/dfa-match-semantics-gap.md` (char class overflow section)
