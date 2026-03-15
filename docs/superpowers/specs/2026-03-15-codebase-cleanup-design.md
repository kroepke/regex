# Codebase Cleanup: Allocations, Collections, and Style

**Date:** 2026-03-15
**Status:** Draft
**Scope:** All `src/main/java` directories across the reactor

## Motivation

A comprehensive audit of the codebase identified allocation waste, suboptimal collection usage, missing `System.arraycopy` opportunities, and minor style issues. Findings span three impact zones: search hot-path, compilation path, and code quality. This spec organizes them into phased work with benchmark gates between each phase.

## Approach

**Risk-first ordering.** Hot-path changes land first (highest impact, most likely to interact with JIT), then compilation-path, then pure style. Each phase is a self-contained commit series with benchmark verification.

## Benchmark Protocol

Each phase follows this protocol:

1. **Baseline:** Run the relevant benchmark suite on the commit immediately before the phase starts. Record results.
2. **Changes:** One commit per logical change within the phase. Full test suite (2,183 tests) must pass after every commit.
3. **Post-phase:** Run the same benchmark suite after the final commit of the phase. Record results.
4. **Gate:** No benchmark may regress by more than 2x. Any regression requires investigation before proceeding. Improvements are noted in the commit message.
5. **Record:** Both baseline and post-phase results are recorded in the phase's final commit message for future reference.

| Phase | Benchmark Suites | Test Gate |
|-------|-----------------|-----------|
| Phase 1 (Search hot-path) | `SearchBenchmark`, `RawEngineBenchmark`, `PathologicalBenchmark` | 2,183 tests pass |
| Phase 2 (Compilation path) | `CompileBenchmark` | 2,183 tests pass |
| Phase 3 (Code quality) | None (tests only) | 2,183 tests pass |

Benchmark command: `./mvnw -P bench package -DskipTests && java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5`

To run a specific suite: append the class name, e.g., `... benchmarks.jar SearchBenchmark -f 1 -wi 3 -i 5`.

## Phase 1: Search Hot-Path

Changes that affect per-match or per-character operations during search. Measured by `SearchBenchmark`, `RawEngineBenchmark`, and `PathologicalBenchmark`.

### 1.1 — Lazy `Match.text()`

**File:** `regex/src/main/java/lol/ohai/regex/Match.java`, `Regex.java`

**Problem:** `Match` is a record with an eagerly-computed `String text` field. Every match in `findAll()` allocates a substring via `text.subSequence(start, end).toString()`, even if the caller only needs offsets.

**Change:**
- Convert `Match` from `record Match(int start, int end, String text)` to a final class storing `(int start, int end, CharSequence source)`.
- `text()` computes the substring lazily on first call and caches it.
- `start()` and `end()` remain zero-cost.
- `Regex.toMatch()` passes the original `CharSequence` instead of eagerly substringifying.

**Public API impact:** `Match` is no longer a record. Callers using `match.text()`, `match.start()`, `match.end()` are unaffected. Record destructuring (`match instanceof Match(int s, int e, String t)`) would break — acceptable for this young API.

**Expected effect:** Eliminates O(n) String allocations in `findAll()` loops for callers that only inspect offsets.

### 1.2 — Mutable `Input` bounds in `Searcher` and iterators

**File:** `regex-automata/src/main/java/lol/ohai/regex/automata/util/Input.java`, `Regex.java`

**Problem:** `Searcher.find()` (line 225) and `BaseFindIterator.advance()` (line 450) call `baseInput.withBounds()` every iteration, allocating a new `Input` shell per match.

**Change:**
- Add `void setBounds(int newStart, int newEnd, boolean newAnchored)` to `Input`.
- `Searcher.find()` and `BaseFindIterator.advance()` call `setBounds()` on a single reused `Input` instead of `withBounds()`.
- Keep `withBounds()` for external/immutable-copy use cases.

**Risk:** Low. `Input` is package-private. Mutation is confined to iteration loops that already own the instance.

**Expected effect:** Eliminates one `Input` allocation per match found during iteration.

### 1.3 — `CharClasses` metadata consolidation

**File:** `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClasses.java`

**Problem (a):** Four separate `boolean[]` arrays (`wordClass`, `lineLF`, `lineCR`, `quitClass`) with null checks on every access. These are queried during DFA state computation.

**Problem (b):** Constructor loop at line 29-31 (`asciiTable[c] = rows[highIndex[0]][c]`) is a manual array copy that should be `System.arraycopy`.

**Change:**
- Replace the four `boolean[]` arrays with a single `byte[] classFlags`, always non-null (allocated with length `classCount`).
- Define bit constants: `FLAG_WORD = 1`, `FLAG_LF = 2`, `FLAG_CR = 4`, `FLAG_QUIT = 8`.
- Accessor methods become: `return (classFlags[classId] & FLAG) != 0` with a single bounds check.
- `hasQuitClasses()` becomes a boolean field set at construction.
- Replace the ASCII table init loop with `System.arraycopy(rows[highIndex[0]], 0, asciiTable, 0, 128)`.
- Reduce constructor parameters from 7 to a cleaner signature (classFlags replaces 4 boolean[] params).

**Risk:** Low. Internal to `CharClasses`, all callers use accessor methods.

**Expected effect:** Reduces memory (4 arrays → 1 byte array), eliminates null checks on DFA state path. ASCII table init is cold-path but corrects a `System.arraycopy` omission.

### 1.4 — Unused pattern variables → `_` in `Strategy.java`

**File:** `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java`

**Problem:** ~30 switch pattern variables (`n`, `g`, `g2`, `n2`, `fm`) are bound but never read.

**Change:** Replace all with `_` (Java 21 unnamed patterns). For example:
- `case SearchResult.NoMatch n ->` becomes `case SearchResult.NoMatch _ ->`
- `case SearchResult.GaveUp g ->` becomes `case SearchResult.GaveUp _ ->`

**Risk:** None. Pure syntax change.

**Expected effect:** No runtime impact. Cleaner code, no static analysis warnings.

## Phase 2: Compilation Path

Changes that affect `Regex.compile()` time. Measured by `CompileBenchmark`.

### 2.1 — Replace `TreeSet<Integer>` with `int[]` + sort in `CharClassBuilder`

**File:** `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`

**Problem:** `collectBoundaries()` returns `TreeSet<Integer>` — a red-black tree with boxed `Integer` nodes. Callers immediately convert to `int[]` via `.stream().mapToInt().toArray()`. The tree's sorted-insertion property is wasted; we only need the sorted result once.

**Change:**
- Change `collectBoundaries()` to return `int[]`.
- Internally, collect boundaries into a growable `int[]` (simple doubling array or an `IntArrayBuilder` private helper).
- After collection, `Arrays.sort()` and deduplicate (compact adjacent duplicates in-place).
- Callers receive a sorted, deduplicated `int[]` directly.

**Expected effect:** Eliminates all `Integer` boxing and tree rebalancing during compilation. For Unicode patterns with hundreds of ranges, this removes hundreds of allocations.

### 2.2 — `long` bitmask for small-NFA signatures

**File:** `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`

**Problem:** `computeSignature()` creates a `BitSet` per boundary region. For the common case (NFA state count + 2 ≤ 64), a `long` bitmask does the same work with zero allocation.

**Change:**
- In the `build()` merge loop, check `nfa.stateCount() + 2 <= 64`.
- If true, compute signatures as `long` values via `computeSignatureLong()` and use `HashMap<Long, Integer>` (single autobox per unique signature, not per region).
- If false, fall back to the existing `BitSet` path.

**Expected effect:** Eliminates hundreds of `BitSet` allocations for typical patterns (<64 NFA states).

### 2.3 — Eliminate 65KB `flatMap` staging buffer

**File:** `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`

**Problem:** Both `build()` and `buildUnmerged()` allocate `byte[65536]` as a staging buffer, fill it, then extract 256 × 256-byte rows. The flatMap is an unnecessary intermediate.

**Change:**
- For each `hi` (0-255), compute the 256-byte row directly by classifying each `(hi << 8) | lo` against the sorted bounds + region class map.
- For the merged path (`build()`): use `regionClassMap` + binary search on `sortedBounds` to find the class for each char.
- For the unmerged path (`buildUnmerged()`): binary search on `sortedBounds` directly.
- Write into the 256-byte row and feed it into the existing dedup logic.

**Expected effect:** Removes one 64KB allocation per `Regex.compile()`.

### 2.4 — Extract shared row-dedup logic

**File:** `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClassBuilder.java`

**Problem:** Lines 74-90 and 210-227 are near-identical row-extraction and deduplication blocks.

**Change:** Extract a private helper:
```java
private static RowResult deduplicateRows(byte[][] rowData) {
    // shared logic: ByteArrayKey map, uniqueRows list, highIndex array
}
```
Both `build()` and `buildUnmerged()` call this after computing their rows (however they compute them — direct or via flatMap).

**Expected effect:** No runtime change. Eliminates code duplication.

### 2.5 — Modernize `Collections.unmodifiable*`

**File:** `regex/src/main/java/lol/ohai/regex/Regex.java`

**Problem:** `Collections.unmodifiableList(groups)` (line 388) and `Collections.unmodifiableMap(map)` (line 400) use wrapper-based immutability.

**Change:**
- `Collections.unmodifiableList(groups)` → `List.copyOf(groups)`
- `Collections.unmodifiableMap(map)` → `Map.copyOf(map)`
- In `buildNamedGroupMap()`, the intermediate `HashMap` is still needed for building, but the final result uses `Map.copyOf()`.

**Expected effect:** Marginal — fewer wrapper objects, modern Java 10+ idiom.

## Phase 3: Code Quality & Style

No benchmark impact expected. Verified by full test suite only.

### 3.1 — `Arrays.copyOf` in `OnePassBuilder.grow()` / `growLong()`

**File:** `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/OnePassBuilder.java`

**Problem:** Two methods manually allocate + `System.arraycopy` where `Arrays.copyOf` is the standard idiom.

**Change:**
```java
private static int[] grow(int[] arr) {
    return Arrays.copyOf(arr, arr.length * 2);
}
private static long[] growLong(long[] arr) {
    return Arrays.copyOf(arr, arr.length * 2);
}
```

### 3.2 — `List<Optional<Match>>` → `Match[]` in `Captures`

**Files:** `regex/src/main/java/lol/ohai/regex/Captures.java`, `Regex.java`

**Problem:** `Captures` stores `List<Optional<Match>>`, allocating an `Optional` wrapper per group at construction time.

**Change:**
- Store `Match[] groups` internally (nulls for unmatched groups).
- `group(int index)` returns `Optional.ofNullable(groups[index])` — wrapping deferred to access time.
- `groupCount()` returns `groups.length`.
- `Regex.toCaptures()` builds a `Match[]` directly instead of an `ArrayList<Optional<Match>>`.

**Public API:** `group()` still returns `Optional<Match>`. No change for callers.

### 3.3 — Document `PrefilterOnly` unused `cache` parameter

**File:** `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java`

**Problem:** Three `PrefilterOnly` methods have an unused `cache` parameter required by the `Strategy` interface.

**Change:** Add `@SuppressWarnings("unused")` to the record and a comment:
```java
// PrefilterOnly needs no engine cache — parameter required by Strategy interface contract.
```

## Summary

| Change | Phase | Files Modified | Risk |
|--------|-------|---------------|------|
| 1.1 Lazy Match.text() | 1 | Match.java, Regex.java | Medium (API change) |
| 1.2 Mutable Input bounds | 1 | Input.java, Regex.java | Low |
| 1.3 CharClasses flags consolidation | 1 | CharClasses.java, CharClassBuilder.java | Low |
| 1.4 Unnamed pattern variables | 1 | Strategy.java | None |
| 2.1 int[] boundaries | 2 | CharClassBuilder.java | Low |
| 2.2 long bitmask signatures | 2 | CharClassBuilder.java | Low |
| 2.3 Eliminate flatMap | 2 | CharClassBuilder.java | Low |
| 2.4 Extract row-dedup helper | 2 | CharClassBuilder.java | None |
| 2.5 Modernize unmodifiable collections | 2 | Regex.java | None |
| 3.1 Arrays.copyOf in OnePassBuilder | 3 | OnePassBuilder.java | None |
| 3.2 Match[] in Captures | 3 | Captures.java, Regex.java | Low |
| 3.3 Document unused cache param | 3 | Strategy.java | None |
