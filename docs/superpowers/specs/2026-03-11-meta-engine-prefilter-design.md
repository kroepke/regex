# Meta Engine + Literal Prefilter

Introduce a meta engine abstraction (`Strategy`) that selects the best search approach based on pattern characteristics, and add literal prefix prefilter extraction to skip regex engine work on literal-heavy patterns.

## Motivation

The PikeVM is O(m x n) per search — it explores every NFA state at every character position. For simple literal patterns like `Sherlock Holmes` on a 900KB haystack, this is ~233x slower than `java.util.regex`. The upstream Rust regex crate solves this with a meta engine that selects from multiple strategies, with literal prefilters as the first and most impactful optimization.

This design achieves two goals:
1. **API stability** — introduce the engine abstraction so future engines (lazy DFA, bounded backtracker, etc.) slot in without changing `Regex.java`
2. **Immediate performance win** — prefix literal extraction with `String.indexOf()` should close most of the gap for literal-heavy patterns

## Scope

- Meta engine abstraction (`Strategy` sealed interface)
- Prefix literal extraction from HIR
- `indexOf`-based prefilter for single and multi-literal patterns
- Integration with `Regex.java` (internal change, no public API change)

**Out of scope:** suffix/inner literal extraction (requires reverse engine), new engines (lazy DFA, bounded backtracker, one-pass DFA), Aho-Corasick multi-pattern search, SIMD/Vector API acceleration.

## Design Decisions

**Sealed interface, not open interface.** Java's `sealed interface` gives exhaustive `switch` — the compiler enforces every strategy is handled. The upstream Rust crate uses `dyn Strategy` (trait objects) to avoid monomorphization code bloat; Java already uses virtual dispatch for all interfaces, so there's no equivalent trade-off. Sealed is strictly better for a fixed set of known strategies.

**Prefix literals only.** Suffix and inner literal prefilters require a reverse engine to determine match start position. Without one, they provide limited benefit. Prefix extraction is self-contained: find the prefix, run the forward engine from that position.

**Extract from HIR, not NFA.** HIR nodes (`Literal`, `Concat`, `Alternation`) directly expose literal structure. NFA states obscure it — a literal becomes a chain of `CharRange` states. Extraction at the HIR level is simpler and can inform whether to skip NFA compilation entirely.

**`String.indexOf()` for literal scanning.** No external dependencies. The JVM JIT optimizes `String.indexOf` as an intrinsic. For multi-literal alternations, multiple `indexOf` calls (one per alternative) are used. Aho-Corasick can be added later for patterns with many alternatives.

**`PrefilterOnly` skips NFA compilation.** For patterns that are entirely literal (or alternations of same-length literals) with no capture groups, no NFA, no PikeVM — just `indexOf`. Saves both compilation and search time. Patterns with named capture groups (e.g., `(?P<word>hello)`) or alternations of different-length literals (e.g., `cat|elephant`) are excluded from `PrefilterOnly` and routed to `Core` with a prefilter instead — this ensures correct `Captures` construction and match length reporting.

## Compilation Pipeline

**Current:**
```
Pattern -> AST -> HIR -> NFA -> PikeVM -> Regex
```

**New:**
```
Pattern -> AST -> HIR -> LiteralExtractor(HIR) -> prefixes
                      -> NFA -> PikeVM (skipped if PrefilterOnly)
                      -> Strategy.build(PikeVM, prefixes) -> Strategy
                      -> Regex(Strategy)
```

## Strategy Interface

```java
// lol.ohai.regex.automata.meta.Strategy

public sealed interface Strategy permits Strategy.Core, Strategy.PrefilterOnly {

    Cache createCache();
    boolean isMatch(Input input, Cache cache);
    Captures search(Input input, Cache cache);
    Captures searchCaptures(Input input, Cache cache);

    record Core(PikeVM pikeVM, @Nullable Prefilter prefilter) implements Strategy {
        // If prefilter present: scan for prefix, run PikeVM from candidate position.
        // If prefilter miss: no match, skip PikeVM entirely.
        // If prefilter absent: pure PikeVM (same as today).
        // Cache: delegates to pikeVM.createCache()
    }

    record PrefilterOnly(Prefilter prefilter) implements Strategy {
        // Pure literal match. No regex engine.
        // Only used when: prefilter.isExact() == true, no capture groups, entire pattern is literal.
        // isMatch: prefilter.find() != -1
        // search: prefilter.find() -> build Captures from position + prefilter.matchLength()
        // searchCaptures: same as search (group 0 only, no sub-groups possible)
        // Cache: no-op (stateless search), createCache() returns a sentinel empty cache
    }
}

```

**Future engines** (lazy DFA, bounded backtracker) are added as fields inside `Core`. The `Core.search()` method tries them in priority order, falling back to PikeVM. No new `Strategy` variant needed for most engines. New strategy variants (like `ReverseSuffix`) are added to the sealed interface when needed.

## Prefilter Interface

```java
// lol.ohai.regex.automata.meta.Prefilter

public interface Prefilter {
    /** Find next occurrence starting at 'from'. Returns start index or -1. */
    int find(char[] haystack, int from, int to);

    /** Whether this prefilter reports exact match boundaries. */
    boolean isExact();

    /** Match length when isExact() is true. */
    int matchLength();
}
```

**Implementations:**

- `SingleLiteral(char[] needle)` — scans with `String.indexOf()`. `isExact() = true`.
- `MultiLiteral(char[][] needles)` — calls `indexOf` per needle, returns earliest. `isExact()` = true only if all needles are the same length.

## Core Prefilter Search Loop

When `Core` has a prefilter:

1. Initialize `start = input.start()`, `end = input.end()`
2. If `input.isAnchored()`: skip prefilter, run PikeVM directly at `start` (only one position is valid for anchored search)
3. `prefilter.find(haystack, start, end)` to find next candidate position
4. If -1: no match possible, return immediately
5. If found at position p: run PikeVM with input bounds starting from p
6. If PikeVM doesn't match at p (false positive): set `start = p + 1`, goto step 3
7. Loop until match or haystack exhausted

This loop is critical for correctness — the prefilter finds where the prefix *might* start, but the full regex may not match there (e.g., prefix `Sher` for pattern `Sherlock` but haystack has `Sherman`).

## Literal Prefix Extractor

```java
// lol.ohai.regex.syntax.hir.LiteralExtractor

public final class LiteralExtractor {
    public static LiteralSeq extractPrefixes(Hir hir);
}
```

**`LiteralSeq`** represents the result:

```java
public sealed interface LiteralSeq {
    /** Whether this literal sequence covers the entire pattern (no regex needed). */
    default boolean coversEntirePattern() { return false; }

    record None() implements LiteralSeq {}

    record Single(char[] literal, boolean entirePattern) implements LiteralSeq {
        @Override public boolean coversEntirePattern() { return entirePattern; }
    }

    record Alternation(List<char[]> literals, boolean entirePattern) implements LiteralSeq {
        @Override public boolean coversEntirePattern() { return entirePattern; }
    }
}
```

**Extraction rules:**

| HIR Node | Result |
|---|---|
| `Literal(chars)` | `Single(chars, true)` |
| `Concat([Literal(a), ...])` | `Single(a, false)` — first element if literal |
| `Concat([Literal(a), Literal(b), ...])` | `Single(a++b, ...)` — merge adjacent leading literals |
| `Alternation([Literal(a), Literal(b), ...])` | `Alternation([a, b, ...], true)` — all branches literal |
| `Alternation([Concat([Literal(a), ...]), ...])` | `Alternation([a, ...], false)` — prefix per branch |
| `Capture(child)` | Recurse into child (transparent) |
| `Repetition`, `Class`, `Look` at start | `None()` |
| `Empty` | `None()` |
| Alternation with any non-literal branch | `None()` (conservative) |

## Regex.java Integration

```java
// Regex.create() changes (signature unchanged from current code)

static Regex create(String pattern, int nestLimit) {
    Ast ast = Parser.parse(pattern, nestLimit);
    Hir hir = Translator.translate(pattern, ast);

    LiteralSeq prefixes = LiteralExtractor.extractPrefixes(hir);
    Prefilter prefilter = buildPrefilter(prefixes);

    Strategy strategy;
    if (prefilter != null && prefilter.isExact()
            && prefixes.coversEntirePattern() && !hirHasCaptures(hir)) {
        strategy = new Strategy.PrefilterOnly(prefilter);
    } else {
        NFA nfa = Compiler.compile(hir);
        PikeVM pikeVM = new PikeVM(nfa);
        strategy = new Strategy.Core(pikeVM, prefilter); // prefilter may be null
    }

    return new Regex(pattern, strategy);
}
```

**`PrefilterOnly` selection constraints:** The pattern must (a) be entirely covered by the prefilter, (b) have exact match length (`prefilter.isExact()`), and (c) contain no capture groups. Patterns with named groups like `(?P<word>hello)` or alternations of different-length literals like `cat|elephant` are routed to `Core` with a prefilter instead, ensuring correct `Captures` and match length.

**`Regex` fields change:**
- `PikeVM pikeVM` -> `Strategy strategy`
- `ThreadLocal<PikeVM.Cache>` -> `ThreadLocal<Cache>` (see Cache section below)
- All search methods delegate to `strategy` instead of `pikeVM`
- The inner iterator classes `MatchIterator` and `CapturesIterator` are updated to delegate to `strategy.search` and `strategy.searchCaptures` respectively, replacing the direct `pikeVM.*` calls

**Cache:** `Cache` is a new type in the `meta` package that wraps a `@Nullable PikeVM.Cache`. `Strategy.Core.createCache()` returns a `Cache` wrapping `pikeVM.createCache()`. `Strategy.PrefilterOnly.createCache()` returns a `Cache` with a null inner cache (stateless search). The `Regex` class holds `ThreadLocal<Cache>` and passes it through to strategy methods.

**Public API:** No changes. `compile()`, `find()`, `findAll()`, `captures()`, `capturesAll()`, `isMatch()` — identical signatures and semantics. `RegexBuilder` is also unchanged — it still prepends `(?i)` to the pattern string for case-insensitive mode.

## File Structure

**New files:**

| File | Module | Purpose |
|---|---|---|
| `regex-syntax/.../hir/LiteralExtractor.java` | regex-syntax | HIR prefix extraction |
| `regex-syntax/.../hir/LiteralSeq.java` | regex-syntax | Extraction result type |
| `regex-automata/.../meta/Strategy.java` | regex-automata | Sealed interface + Core, PrefilterOnly |
| `regex-automata/.../meta/Prefilter.java` | regex-automata | Prefilter interface |
| `regex-automata/.../meta/SingleLiteral.java` | regex-automata | indexOf single literal |
| `regex-automata/.../meta/MultiLiteral.java` | regex-automata | Multi-indexOf alternation |

**Modified files:**

| File | Change |
|---|---|
| `regex/.../Regex.java` | Hold Strategy instead of PikeVM |
| `regex-automata/module-info.java` | Export `lol.ohai.regex.automata.meta` |

**Test files:**

| File | Purpose |
|---|---|
| `regex-syntax/.../hir/LiteralExtractorTest.java` | Extraction from various HIR shapes |
| `regex-automata/.../meta/SingleLiteralTest.java` | Single literal find correctness |
| `regex-automata/.../meta/MultiLiteralTest.java` | Multi literal find correctness |
| `regex-automata/.../meta/StrategyTest.java` | Strategy selection and search integration |

**Existing test suites** (`PikeVMSuiteTest`, `UpstreamSuiteTest`) pass unchanged — they exercise the public API.

## Expected Benchmark Impact

| Benchmark | Current | Expected | Why |
|---|---|---|---|
| `literalOhai` | 13.7 | ~2,000+ | `PrefilterOnly` with `indexOf` |
| `alternationOhai` | 6.1 | ~50+ | `PrefilterOnly` with multi-`indexOf` |
| `charClassOhai` | 0.062 | 0.062 | No extractable prefix, pure PikeVM |
| `capturesOhai` | 57 | 57 | No extractable prefix |
| `unicodeWordOhai` | 12.1 | 12.1 | Prefix is `\w+`, not extractable |
| `compileLiteralOhai` | 1,089K | higher | `PrefilterOnly` skips `Compiler.compile()` and `new PikeVM()` — no NFA graph allocation |

## Future Extensions

- **Suffix/inner literals** — when a reverse engine is added
- **Aho-Corasick** — for alternations with many branches (>10)
- **Lazy DFA** — added as a field in `Core`, tried before PikeVM
- **Bounded backtracker** — added as a field in `Core`, for captures on small inputs
- **One-pass DFA** — added as a field in `Core`, for simple single-pass patterns
