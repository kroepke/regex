# Suffix/Inner Literal Prefilter Design

## Goal

Close the captures benchmark gap (33.6x slower than JDK) by extracting suffix and inner literals from patterns and using them to skip most of the haystack before engaging the DFA/PikeVM engines. This builds on the existing prefix literal prefilter infrastructure and the reverse DFA.

## Architecture

Two new strategy variants join the existing `Strategy` sealed interface: `ReverseSuffix` and `ReverseInner`. Both use `String.indexOf()` to locate a literal candidate, then reverse-DFA backward to find match start, then forward-DFA + PikeVM to verify and extract the result. Strategy selection in `Regex.create()` prefers prefix prefilters (existing `Core`) when available; suffix/inner strategies activate only when no useful prefix exists but a suffix or inner literal can be extracted.

## Tech Stack

- Java 21 (sealed interfaces, records, pattern matching)
- Existing `LazyDFA.searchRev()` for reverse scanning
- Existing `String.indexOf()` JIT intrinsic for literal scanning
- Existing `BoundedBacktracker` for capture extraction on narrow windows

---

## 1. Literal Extraction Changes

### 1.1 LiteralSeq Exactness Tracking

**File:** `regex-syntax/.../hir/LiteralSeq.java`

Current state: `Single(char[] literal, boolean entirePattern)` and `Alternation(List<char[]> literals, boolean entirePattern)` with `coversEntirePattern()`.

Change: add an `exact` flag to both `Single` and `Alternation`. An exact literal marks a definite pattern boundary — the match truly starts or ends there. An inexact literal appears somewhere within the match but the position is not guaranteed.

- `Single(char[] literal, boolean exact, boolean entirePattern)`
- `Alternation(List<char[]> literals, boolean exact, boolean entirePattern)`
- `coversEntirePattern()` returns `exact && entirePattern` (preserves existing semantics)
- New `exact()` method on the interface, defaulting to `false` for `None`

Prefix literals from position 0 of a concat are exact. Suffix literals from the final position of a concat are exact. Inner literals are always inexact. After a variable-length element (repetition, class), subsequent literals become inexact.

**Migration:** All existing `extractPrefixes()` construction sites in `LiteralExtractor.java` pass `exact=true` (prefix literals from position 0 are always exact). Existing callers of `coversEntirePattern()` in `Regex.java` are unaffected since `coversEntirePattern()` returns `exact && entirePattern`, and for prefixes `exact` is always `true`. Construction sites to update:
- `LiteralExtractor.extractPrefixes()` line 18: `new LiteralSeq.Single(lit.chars(), true)` → `new LiteralSeq.Single(lit.chars(), true, true)`
- `LiteralExtractor.extractFromConcat()` line 51: `new LiteralSeq.Single(merged, allLiteral)` → `new LiteralSeq.Single(merged, true, allLiteral)`
- `LiteralExtractor.extractFromAlternation()` line 77: `new LiteralSeq.Alternation(literals, allEntire)` → `new LiteralSeq.Alternation(literals, true, allEntire)`
- `LiteralSeq.Single.equals()` and `hashCode()` (lines 38-47): must be updated to include the `exact` field in comparisons and hash computation

### 1.2 Suffix Extraction

**File:** `regex-syntax/.../hir/LiteralExtractor.java`

Add `extractSuffixes(Hir hir)` — mirrors `extractPrefixes` but iterates concat children from the end. Returns:
- `Single(literal, exact=true, entirePattern)` when the pattern ends with a fixed literal
- `Single(literal, exact=false, entirePattern=false)` when the last literal follows a variable-length element (shouldn't happen for trailing literals, but guards against edge cases)
- `Alternation(...)` when all alternation branches have extractable suffixes
- `None` when no suffix can be extracted

Implementation: reuse the existing helper structure. For a `Concat`, iterate `subs` in reverse, collecting trailing `Hir.Literal` nodes. For `Alternation`, recursively extract suffixes from each branch.

### 1.3 Inner Literal Extraction

**File:** `regex-syntax/.../hir/LiteralExtractor.java`

Add `extractInner(Hir hir)` — scans each position in a top-level `Concat` (skipping position 0, which is the prefix domain). For each position, attempts to extract a literal. Returns the longest candidate along with the prefix HIR (the concat of all sub-expressions before the inner literal position).

Return type: a new record `InnerLiteral(LiteralSeq literal, Hir prefixHir)`, or `null` if no inner literal can be extracted. The `prefixHir` is needed by `Regex.create()` to compile the separate prefix-reverse NFA/DFA for the `ReverseInner` strategy (see Section 2.2).

- All inner literals are inexact (they appear after variable-length elements)
- Skips position 0 (prefix territory, already handled by existing extraction)
- For each candidate position, extracts via the existing `extractPrefixes` logic applied to the sub-expression
- Selects the longest literal among all candidates
- `prefixHir` is the `Hir.Concat` of `subs[0..innerPos]` (everything before the selected inner literal)

**Heuristic note:** The "longest literal" heuristic minimizes false positive hits from `indexOf`. The upstream Rust crate uses `optimize_for_prefix_by_preference()` which ranks candidates by byte frequency (preferring rare bytes over common ones). Our longest-first heuristic is a known simplification — it may underperform for long-but-common literals like `the `. The upstream's frequency-based ranking is the specific future refinement target. Note this in the code.

---

## 2. New Strategy Variants

**File:** `regex-automata/.../meta/Strategy.java`

The `sealed interface Strategy permits ...` clause must be updated to include `Strategy.ReverseSuffix, Strategy.ReverseInner`.

### 2.1 Strategy.ReverseSuffix

```java
record ReverseSuffix(
    PikeVM pikeVM,
    LazyDFA forwardDFA,
    LazyDFA reverseDFA,
    Prefilter suffixPrefilter,
    BoundedBacktracker backtracker
) implements Strategy
```

**Search flow for `search()`:**
1. `suffixPrefilter.find()` locates suffix candidate at position `suffixPos`
2. Reverse DFA searches backward from `suffixPos + matchLength` toward `minStart` watermark
3. On `Match(startPos)`: forward DFA searches from `startPos` to find match end; PikeVM verifies on narrowed `[startPos, matchEnd]` window
4. On `GaveUp`: fall back to PikeVM on remaining `[start, end]` window
5. On `NoMatch`: advance past `suffixPos`, continue loop

**Anti-quadratic watermark:** Track `minStart`. On every `NoMatch` from the reverse DFA, advance `minStart = reverseFrom` (i.e., `suffixPos + matchLength`) past the current suffix hit. This matches the upstream Rust implementation (`strategy.rs` line 1239) where `min_start = litmatch.end` is reached only when the reverse DFA returns `None` (the `Some` arm returns immediately). Without this, repeated reverse-NoMatch results from non-matching suffix hits would cause the reverse DFA to re-scan `[0, suffixPos]` each time, producing O(n^2) behavior.

**`isMatch()`:** Same loop but return `true` on first reverse DFA `Match`. The forward DFA pass in `search()` is NOT needed for existence checking — the reverse DFA confirming a match start is sufficient. The forward DFA in `search()` exists to find the correct match *end position* under greedy/leftmost-first semantics. Example: pattern `/[a-z]+ing/` against `tingling` — the first suffix hit `ing` at position 1 plus a reverse match confirms `ting`, but the correct leftmost-first result is `tingling`. The forward pass resolves this. For `isMatch()`, we only need existence, so reverse confirmation alone suffices.

**`searchCaptures()`:** Same as `search()` but the final verification step uses `captureEngine()` (prefer `BoundedBacktracker` for small windows, fall back to PikeVM).

### 2.2 Strategy.ReverseInner

```java
record ReverseInner(
    PikeVM pikeVM,
    LazyDFA forwardDFA,
    LazyDFA prefixReverseDFA,
    Prefilter innerPrefilter,
    BoundedBacktracker backtracker
) implements Strategy
```

**Critical architectural difference from ReverseSuffix:** The reverse DFA here is NOT the full-pattern reverse DFA. It is a **separate reverse DFA compiled from the prefix portion of the pattern** — the HIR up to but not including the inner literal. This matches the upstream Rust implementation (`reverse_inner.rs` lines 53-109, `strategy.rs` lines 1584-1613).

Example: for pattern `\w+Holmes\w+` with inner literal `Holmes`:
- The prefix HIR is `\w+` (everything before `Holmes`)
- `prefixReverseDFA` is compiled from `\w+` reversed
- When the prefilter finds `Holmes` at position P, the reverse DFA scans backward from P looking for where `\w+` starts — not where the full pattern starts

Using the full-pattern reverse DFA would be incorrect: it would scan backward looking for the full pattern reversed, producing wrong match start positions.

**Implications for `extractInner()`:** The method must return both the inner literal AND the prefix HIR, so that `Regex.create()` can compile the separate prefix-reverse NFA/DFA.

Other differences from `ReverseSuffix`:
- Reverse scan origin is `innerPos` (start of inner literal) rather than end of suffix
- Tracks an additional `minPreStart` watermark: if the prefilter finds a hit before `minPreStart`, fall back to PikeVM on the remaining input (quadratic-abort, matching upstream's `RetryError::Quadratic` behavior). This is stricter than skip-and-continue because inner literals can appear many times within a single match window, and repeated skip-and-continues would accumulate into slow-but-linear behavior.

**`isMatch()`, `search()`, `searchCaptures()`:** Same structure as `ReverseSuffix` with dual watermarks and quadratic-abort.

### 2.3 Cache

`ReverseSuffix` uses the same `Cache` record shape as `Core` — all four engine caches (PikeVM, forward DFA, reverse DFA, backtracker).

`ReverseInner` needs a **fifth cache slot** for the prefix-reverse DFA, since it holds a separate `prefixReverseDFA` that is distinct from the full-pattern `reverseDFA` used by `ReverseSuffix` and `Core`. Add `DFACache prefixReverseDFACache` to the `Cache` record. The upstream adds a dedicated `revhybrid` cache entry for exactly this reason (`strategy.rs` lines 1751-1753). For strategies that don't use it (`Core`, `ReverseSuffix`, `PrefilterOnly`), this field is `null`.

### 2.4 Shared captureEngine() Logic

Both `ReverseSuffix` and `ReverseInner` need the same capture engine selection as `Core`: prefer `BoundedBacktracker` for small windows, fall back to PikeVM. Extract this as a `package-private static` method on the `Strategy` interface itself (not on `Core`, since static methods in inner records aren't accessible to sibling records), or duplicate in each variant (~8 lines). The existing `Prefilter.matchLength()` method is already available and used by the suffix prefilter loop to compute `reverseFrom = suffixPos + suffixPrefilter.matchLength()`.

---

## 3. Strategy Selection

**File:** `regex/.../Regex.java`

Selection order in `Regex.create()`:

```
1. PrefilterOnly  — exact prefix prefilter covers entire pattern, no captures
2. Core w/ prefix — prefix prefilter exists (stay with Core, battle-tested path)
3. ReverseSuffix  — suffix literal extracted, both DFAs available, no prefix prefilter
4. ReverseInner   — inner literal extracted, both DFAs available, no prefix/suffix
5. Core w/o prefilter — everything else
```

**Key decisions:**
- If a prefix prefilter exists, stay with `Core`. Prefix + forward DFA is the simplest path and already fast. ReverseSuffix/ReverseInner only activate when the prefix is not useful.
- `ReverseSuffix` requires forward DFA AND reverse DFA. Without both, fall to `Core`.
- `ReverseInner` requires forward DFA AND a separately compiled prefix-reverse DFA. `extractInner()` returns both the inner literal and the prefix HIR; `Regex.create()` compiles a reverse NFA/DFA from the prefix HIR. If compilation fails or the prefix is trivial, fall to `Core`.
- `ReverseSuffix` takes priority over `ReverseInner` because suffix literals are at the pattern boundary (more precise, fewer false positives).
- A pattern like `\w+\s+Holmes` has no useful prefix (starts with `\w+`) but has suffix `Holmes` → `ReverseSuffix`.
- A pattern like `\w+Holmes\w+` has no prefix or suffix but has inner literal `Holmes` → `ReverseInner`.

---

## 4. Detailed Search Loop — ReverseSuffix

```
search(input, cache):
    // Anchored searches bypass the prefilter — only one valid start position
    if input.isAnchored():
        return pikeVM.search(input, cache.pikeVMCache)

    start = input.start()
    end = input.end()
    minStart = input.start()

    while start < end:
        suffixPos = suffixPrefilter.find(haystack, start, end)
        if suffixPos < 0: return null

        reverseFrom = suffixPos + suffixPrefilter.matchLength()
        reverseInput = input.withBounds(minStart, reverseFrom, false)
        revResult = reverseDFA.searchRev(reverseInput, cache.reverseDFACache)

        match revResult:
            NoMatch →
                // Advance watermark past this suffix hit (upstream strategy.rs line 1239,
                // reached only on NoMatch since Match arm returns)
                minStart = reverseFrom
                start = suffixPos + 1; continue
            GaveUp  → return pikeVM.search(input.withBounds(start, end), cache.pikeVMCache)
            Match(matchStart) →
                fwdInput = input.withBounds(matchStart, end, false)
                fwdResult = forwardDFA.searchFwd(fwdInput, cache.forwardDFACache)

                match fwdResult:
                    Match(matchEnd) →
                        narrowed = input.withBounds(matchStart, matchEnd, false)
                        return pikeVM.search(narrowed, cache.pikeVMCache)
                    GaveUp →
                        return pikeVM.search(fwdInput, cache.pikeVMCache)
                    NoMatch →
                        start = suffixPos + 1; continue

    return null
```

## 5. Detailed Search Loop — ReverseInner

```
search(input, cache):
    // Anchored searches bypass the prefilter — only one valid start position
    if input.isAnchored():
        return pikeVM.search(input, cache.pikeVMCache)

    start = input.start()
    end = input.end()
    minStart = input.start()
    minPreStart = input.start()

    while start < end:
        innerPos = innerPrefilter.find(haystack, start, end)
        if innerPos < 0: return null

        // Quadratic-abort: if prefilter hit is before the watermark,
        // fall back to PikeVM on remaining input (matches upstream RetryError::Quadratic)
        if innerPos < minPreStart:
            return pikeVM.search(input.withBounds(start, end), cache.pikeVMCache)

        reverseInput = input.withBounds(minStart, innerPos, false)
        revResult = prefixReverseDFA.searchRev(reverseInput, cache.prefixReverseDFACache)

        match revResult:
            NoMatch →
                // Advance watermarks past this inner hit (only on NoMatch;
                // Match arm returns, GaveUp arm falls back)
                minStart = innerPos
                minPreStart = innerPos + 1
                start = innerPos + 1; continue
            GaveUp  → return pikeVM.search(input.withBounds(start, end), cache.pikeVMCache)
            Match(matchStart) →
                fwdInput = input.withBounds(matchStart, end, false)
                fwdResult = forwardDFA.searchFwd(fwdInput, cache.forwardDFACache)

                match fwdResult:
                    Match(matchEnd) →
                        narrowed = input.withBounds(matchStart, matchEnd, false)
                        return pikeVM.search(narrowed, cache.pikeVMCache)
                    GaveUp →
                        return pikeVM.search(fwdInput, cache.pikeVMCache)
                    NoMatch →
                        start = innerPos + 1; continue

    return null
```

The dual watermark (`minStart` for the reverse DFA, `minPreStart` for the prefilter) prevents both engines from re-scanning already-covered territory. The quadratic-abort (falling back to PikeVM instead of skip-and-continue) matches upstream's `RetryError::Quadratic` behavior — it's more conservative but avoids accumulating many skip iterations that would be slow-but-linear.

---

## 6. Testing Strategy

### 6.1 Regression Prevention Protocol

This is a hard requirement based on the regressions introduced during the search throughput round:

- **Full test suite (`./mvnw test`) after every task**, not just the module being modified
- **Cross-reference upstream Rust code** before implementing each search loop — verify semantics against `upstream/regex/regex-automata/src/meta/strategy.rs` (ReverseSuffix at lines 1115-1491) and `upstream/regex/regex-automata/src/meta/reverse_inner.rs`
- **Upstream TOML suite (`UpstreamSuiteTest`)** is the primary correctness gate — any new failure is stop-and-fix-immediately
- **No guessing** — when in doubt about search semantics, read the upstream source

### 6.2 Unit Tests

**LiteralExtractorTest (new tests):**
- `extractSuffixes`: pure literal, literal suffix after variable-length, alternation suffixes, nested captures, no-suffix patterns. Verify `exact` flag correctness.
- `extractInner`: concat with inner literal, multiple candidates (verify longest wins), no inner candidates, captures wrapping inner literals. All results must be inexact.

**ReverseSuffixTest:**
- Construct strategy directly with real engines
- Simple suffix match (`\w+Holmes` on text containing Holmes)
- No match (suffix not present)
- Multiple matches (verify anti-quadratic watermark — no overlapping scans)
- GaveUp fallback (quit chars trigger PikeVM fallback)
- Captures path (verify backtracker preferred for small windows)
- isMatch path

**ReverseInnerTest:**
- Same structure as ReverseSuffixTest, plus:
- Inner literal selection (verify longest chosen)
- Both watermarks working correctly
- Pattern with no prefix or suffix but clear inner literal

### 6.3 Integration

- `UpstreamSuiteTest` (1,954 tests) — exercised automatically through `Regex.compile()` for any pattern that triggers the new strategies
- Full suite run after every task completion

### 6.4 Benchmarks

- Re-run JMH benchmarks after integration
- Captures benchmark (`\w+\s+Holmes`) should show significant improvement (ReverseSuffix with `Holmes` as suffix)
- Verify no regressions in other benchmarks (literal, charClass, unicodeWord, alternation)

---

## 7. Expected Impact

**Captures benchmark (`\w+\s+Holmes` on 900KB haystack):**
- Current: 513 ops/s (33.6x slower than JDK's 17,250)
- Expected: `indexOf("Holmes")` skips ~99.9% of haystack, reverse DFA + PikeVM only run on tiny windows. Should bring us within 2-5x of JDK.

**Other benchmarks:**
- Patterns with no extractable suffix/inner (literal, charClass, unicodeWord) are unaffected — they continue using `Core` or `PrefilterOnly`
- Alternation patterns may benefit if individual branches have extractable inner literals

**No impact on:**
- Compilation cost (no new engines, just strategy selection logic)
- Pathological pattern safety (all strategies maintain linear-time guarantees)
