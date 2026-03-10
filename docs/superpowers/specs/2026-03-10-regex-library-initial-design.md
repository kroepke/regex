# Java Regex Library — Initial Design (through PikeVM)

A reimplementation of the Rust `regex` crate in modern Java (21+). This spec covers the initial layers needed for a working regex engine: project structure, test infrastructure, parser (AST + HIR), Thompson NFA, PikeVM, and the public API.

## Goals

- Feature parity with the Rust `regex` crate's PikeVM path, adapted for idiomatic Java
- Performance and correctness are paramount
- Zero external runtime dependencies for library modules (JDK only)
- Validated against the upstream's extensive TOML test suite

## Non-Goals (Deferred)

- DFA engines (full, lazy/hybrid, one-pass)
- Multi-pattern / RegexSet
- Prefilter optimizations (literal extraction, memchr)
- Byte-oriented API (`regex.bytes` equivalent)
- JMH benchmark harness
- `regex-lite` equivalent

## Maven Structure

```
lol.ohai.regex (parent POM)
├── regex-syntax/       → lol.ohai.regex.syntax
├── regex-automata/     → lol.ohai.regex.automata
├── regex/              → lol.ohai.regex
└── regex-test/         → lol.ohai.regex.test
```

All library modules (`regex-syntax`, `regex-automata`, `regex`) have zero external dependencies. They use `module-info.java` to declare explicit module boundaries.

`regex-test` is a Maven module with `<scope>test</scope>` dependency in other modules. It is never published. It depends on Jackson (TOML parsing) and JUnit 6. It does **not** have a `module-info.java` to avoid JPMS complications in test scope.

### JPMS Export Strategy

- **`regex-syntax`**: exports `lol.ohai.regex.syntax.ast` and `lol.ohai.regex.syntax.hir` (both are public — tooling may want just the parser, mirroring upstream's public `regex-syntax` crate)
- **`regex-automata`**: exports `lol.ohai.regex.automata` (engine types) and `lol.ohai.regex.automata.util` (search config types like `Input`, `MatchKind`). Internal packages (NFA internals, PikeVM internals) are not exported.
- **`regex`**: exports `lol.ohai.regex` (public API)

## Dependencies

| Dependency | Version | Scope |
|---|---|---|
| Java | 21+ | baseline |
| JUnit | 6.0.3 | test |
| Jackson dataformat-toml | ~2.20 | test (regex-test only) |

## Architectural Decision: UTF-8 NFA with Abstracted Input

Java strings are UTF-16, but the upstream NFA is byte-oriented (UTF-8). We adopt a **UTF-8 NFA** initially to enable direct porting from upstream, with the encoding bridge kept internal:

- The `Input` type handles `CharSequence` → UTF-8 `byte[]` encoding at the boundary
- The NFA and PikeVM operate on `byte[]` internally, matching upstream's architecture
- Match offsets are converted from byte positions back to char offsets at the API boundary
- Thread-local byte buffers amortize encoding allocation cost

This is an **internal implementation detail** — the public API operates on `CharSequence` and returns char-unit offsets. The NFA's alphabet abstraction (see `util/alphabet.rs` in upstream) is preserved, allowing a future switch to UTF-16-native NFA without changing any public API.

We accept this may not be the final architecture. If the encoding overhead proves too costly, we can switch to a UTF-16 NFA. The upstream test suite will validate correctness through either approach.

## Module Design

### `regex-test` — Shared Test Infrastructure

Loads the upstream TOML test suite and generates JUnit 6 `DynamicTest` instances for any engine.

**Key types:**
- `RegexTest` — single test case (pattern, haystack, expected matches, flags)
- `RegexTestSuite` — collection loaded from TOML files
- `TestRunner` — JUnit 6 `DynamicTest` factory; engines register by providing a match function
- `MatchKind` — `ALL`, `LEFTMOST_FIRST`, `LEFTMOST_LONGEST`
- `SearchKind` — `EARLIEST`, `LEFTMOST`, `OVERLAPPING`

**Capabilities filtering:** Each engine declares what it supports (e.g., captures, Unicode, anchored search). Tests requiring unsupported features are skipped, not failed.

**TOML format:** Matches upstream exactly — see `upstream/regex/regex-test/lib.rs` for the full field specification. Key fields: `name`, `regex`, `haystack`, `matches`, `compiles`, `anchored`, `unicode`, `utf8`, `match-kind`, `search-kind`, `unescape`, `bounds`, `match-limit`, `case-insensitive`, `line-terminator`.

**Note:** The test loader and model are built in step 2, but actual test execution against engines doesn't happen until step 6 (PikeVM). Steps 2-5 validate the infrastructure with unit tests only.

### `regex-syntax` — Parser Layer

**Package:** `lol.ohai.regex.syntax`

Two-stage compilation: Pattern → AST → HIR.

#### AST (Abstract Syntax Tree)

Faithful representation of the parsed pattern. Preserves structure for round-tripping (parse → print → parse produces identical AST).

**Modeled as:** `sealed interface Ast` with `record` variants:
- `Empty` — the empty regex
- `Literal`, `Dot`, `ClassBracketed`, `ClassPerl`, `ClassUnicode`
- `Repetition` (greedy/lazy, bounded/unbounded)
- `Group` (capturing, non-capturing, named, flag-setting groups like `(?i:...)`)
- `Concat`, `Alternation`
- `Assertion` (`^`, `$`, `\b`, `\B`, `\A`, `\z`)
- `Flags` — standalone flag directives like `(?i)`, distinct from flag-setting groups

**Parser:** Recursive descent, matching upstream's approach. Configurable `nestLimit` to protect against stack overflow from untrusted patterns.

**Printer:** AST → pattern string (for round-trip testing).

#### Error Types

- `ast.Error` — parse errors with span information (position in pattern where the error occurred)
- `hir.Error` — translation errors (e.g., unsupported Unicode property)

These are distinct from the public `PatternSyntaxException` in the `regex` module, which wraps them for end users.

#### HIR (High-level Intermediate Representation)

Simplified, normalized form consumed by the NFA compiler.

**Modeled as:** `sealed interface Hir` with `record` variants:
- `Empty` — identity element for `Concat`/`Alternation` simplification
- `Literal` (raw bytes)
- `Class` (character class — sorted, non-overlapping ranges)
- `Look` (assertions: start/end of line/input, word boundary)
- `Repetition` (min, max, greedy, sub-expression)
- `Capture` (index, optional name, sub-expression)
- `Concat`, `Alternation`

**Translator:** AST → HIR. Resolves flags, expands Unicode properties/classes to codepoint ranges, normalizes character classes, handles case folding.

#### Unicode Tables

Embedded data for Unicode general categories, scripts, properties, case folding.

**Porting strategy:** Mechanically port the upstream's pre-computed tables from `regex-syntax/src/unicode_tables/`. These are sorted arrays of codepoint ranges searched via binary search. The data representation (arrays of `int[]` ranges) translates directly to Java.

**Unicode version:** Match whatever version the upstream pins to (currently Unicode 15.1).

**Validation:** Spot-check tests for known codepoints (e.g., verify specific characters are in correct general categories, case folding maps are correct).

**Implementation note:** This is the largest data artifact in `regex-syntax`. The HIR translator and Unicode tables are largely independent — the translator can be developed and tested with ASCII-only patterns first, then Unicode table support added.

### `regex-automata` — Engine Layer

**Package:** `lol.ohai.regex.automata`

#### Thompson NFA

UTF-8 byte-oriented NFA compiled from HIR. Characters are decomposed into UTF-8 byte sequences.

**Key types:**
- `NFA` — the compiled automaton (states + transitions)
- `State` — sealed interface: `ByteRange`, `Sparse`, `Dense`, `Look`, `Capture`, `Match`, `Fail`, `Union` (epsilon transitions with ordered alternatives), `BinaryUnion` (optimized two-alternative union, avoids array allocation)
- `Compiler` — HIR → NFA compilation
- `BuildError` — NFA compilation errors (e.g., pattern too large)

**Design notes:**
- UTF-8 byte automaton (not codepoint-level), matching upstream
- Epsilon transitions modeled as `Union`/`BinaryUnion` states with ordered alternatives (priority for leftmost-first semantics)
- `BinaryUnion` is a common-case optimization for the frequent two-alternative case
- Capture states inserted around capturing groups

#### PikeVM

NFA simulation engine. Tracks all active states simultaneously (no backtracking). Supports capture groups.

**Key types:**
- `PikeVM` — configured engine instance (immutable, thread-safe)
- `PikeVM.Builder` — configuration (e.g., match kind)
- `Cache` — mutable scratch space for a single search (reusable, not thread-safe)

**Algorithm:** Thompson/Pike simulation — maintains a set of active NFA states, advancing all in lockstep through the input. Capture slots tracked per thread. Priority ordering gives leftmost-first semantics.

**Thread safety:** `PikeVM` is immutable and shareable. `Cache` is per-search mutable state, allocated from a thread-local pool.

#### Utilities

- `SparseSet` — fast integer set (used for NFA state tracking)
- `Captures` — internal capture group slot storage (array of start/end positions)
- `Input` — search configuration: wraps the haystack (as UTF-8 `byte[]`), search bounds, anchored flag, earliest flag. Handles `CharSequence` → UTF-8 encoding. This is the primary parameter to all engine search methods.
- `Look` / `LookSet` — assertion matching utilities

### `regex` — Public API

**Package:** `lol.ohai.regex`

Thin, ergonomic wrapper around the automata layer.

**Key types:**
- `Regex` — compiled regex, thread-safe, main entry point
- `RegexBuilder` — configuration (Unicode mode, case insensitive, nest limit, etc.)
- `Match` — a single match result (start, end in char units, matched text)
- `Captures` — wraps internal slot storage, adds name-based group lookup
- `PatternSyntaxException` — compilation error (extends `IllegalArgumentException`), wraps `ast.Error` / `hir.Error`

**API surface (initial):**
```java
Regex re = Regex.compile("(?P<year>\\d{4})-(?P<month>\\d{2})");
// boolean
re.isMatch("2026-03");
// first match
Optional<Match> m = re.find("date: 2026-03");
// all matches
Stream<Match> matches = re.findAll("2026-03 and 2027-04");
// captures
Optional<Captures> c = re.captures("2026-03");
c.get().group("year"); // "2026"
```

## Rust → Java Idiom Mapping

| Rust | Java |
|---|---|
| `enum` with data variants | `sealed interface` + `record` per variant |
| `Result<T, E>` | Checked exception (`PatternSyntaxException`) for compilation; `Optional` for search results |
| `&[u8]` / `&str` | `byte[]` / `CharSequence` |
| `Option<T>` | `Optional<T>` in public API; nullable in internals where performance matters |
| `impl Trait` | Interface |
| `Box<[T]>` (fixed-size heap slice) | `T[]` array (e.g., `int[]` for state ID lists in Union) |
| Crate visibility (`pub(crate)`) | Package-private |
| Module system | Java Platform Module System (`module-info.java`) |
| `Pool<T>` (thread-local cache) | `ThreadLocal<Cache>` or similar pool |

## Test Strategy

1. **Upstream TOML suite** is the primary correctness oracle — loaded by `regex-test`, run via JUnit 6 dynamic tests. Executed at both the `regex-automata` layer (PikeVM directly) and the `regex` API layer.
2. **AST round-trip tests**: parse → print → parse produces identical AST
3. **HIR property tests**: verify normalization (sorted ranges, flags resolved) on known patterns
4. **NFA structural tests**: verify state counts, transition structure for simple patterns
5. **PikeVM integration tests**: full pipeline (parse → HIR → NFA → PikeVM) against the TOML suite
6. **Compilation failure tests**: patterns with `compiles = false` in the test suite validate error handling
7. **Unicode table validation**: spot-check tests for known codepoints in correct categories and case folding
8. **Adversarial pattern tests**: nest limit enforcement, patterns that produce very large NFAs, resource exhaustion bounds
9. **Thread safety tests**: concurrent use of shared `Regex`/`PikeVM` with per-thread `Cache`

## Implementation Order

Each layer is buildable and testable independently:

1. **Project scaffolding** — parent POM, module POMs, package structure, `module-info.java`, Java 21 config
2. **`regex-test`** — TOML loader, test model, JUnit 6 dynamic test factory (infrastructure only — no engine tests yet)
3. **`regex-syntax` AST** — parser, AST types, printer, round-trip tests
4. **`regex-syntax` HIR** — translator, HIR types (ASCII-capable first)
5. **`regex-syntax` Unicode tables** — port upstream tables, wire into translator, validation tests
6. **`regex-automata` NFA** — Thompson NFA compiler, NFA types, structural tests
7. **`regex-automata` PikeVM** — PikeVM engine, wire up full pipeline, run TOML test suite
8. **`regex` API** — public API wrapper types, run TOML suite through public API
