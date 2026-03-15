# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a guided reimplementation of the [Rust regex crate](https://github.com/rust-lang/regex) in modern Java (baseline: Java 21). The goal is feature parity with the Rust original, adapted for idiomatic Java use. Performance and correctness are paramount.

The upstream Rust source lives in `upstream/regex/` as a git submodule — **read-only reference, never modify it**.

## Upstream Architecture (Reference)

The Rust regex crate has a layered architecture that our Java port mirrors:

### Compilation Pipeline
```
Pattern String
  → AST  (faithful parse tree, round-trippable)
  → HIR  (simplified IR: classes flattened, Unicode resolved, flags applied)
  → NFA  (Thompson NFA, byte-oriented)
  → Engine selection (meta layer picks best strategy)
     ├── PikeVM         (NFA simulation, supports all features)
     ├── BoundedBacktracker (NFA + backtracking, for captures)
     ├── One-pass DFA   (single-pass, captures, limited patterns)
     ├── Lazy/Hybrid DFA (builds states on demand)
     └── Full DFA       (pre-compiled, fastest search)
```

### Upstream Crate → Java Module Mapping
| Rust Crate | Purpose | Key upstream path |
|---|---|---|
| `regex-syntax` | Parser: pattern → AST → HIR | `upstream/regex/regex-syntax/src/` |
| `regex-automata` | Engines: NFA compiler, PikeVM, DFA, hybrid DFA, meta engine | `upstream/regex/regex-automata/src/` |
| `regex` | Public API wrapper around meta engine | `upstream/regex/src/` |
| `regex-lite` | Lightweight alternative (PikeVM only, limited Unicode) | `upstream/regex/regex-lite/src/` |

### Key Design Principles from Upstream
- **Linear-time worst case**: O(m × n) where m = pattern size, n = input length
- **Safe for untrusted patterns**: nest limits, no catastrophic backtracking
- **Thread-safe**: pool-based scratch space, no shared mutable state during search
- **Meta engine**: automatically selects the best engine based on pattern characteristics
- **Prefilter optimization**: literal prefixes/suffixes used to skip ahead in input

## Build & Test

The project uses Maven with a wrapper script (`./mvnw`). Always use the wrapper for reproducible builds.

```bash
# Build
./mvnw compile

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest="SomeTest"

# Run a single test method
./mvnw test -Dtest="SomeTest#someMethod"

# Run tests in a single module
./mvnw test -pl regex-syntax

# Package
./mvnw package
```

## Development Guidelines

### Porting from Rust
- Use `upstream/regex/` as the authoritative reference for behavior and algorithms
- Translate Rust idioms to Java equivalents (e.g., `enum` variants → sealed interfaces/records, `Result` → exceptions or `Optional`, `&[u8]` → `byte[]`)
- Preserve the upstream's module boundaries — they exist for good architectural reasons
- Keep upstream doc comments as reference comments where they clarify non-obvious algorithm choices
- When upstream uses Rust-specific optimizations (e.g., SIMD via `memchr`), find the Java equivalent (e.g., `java.lang.foreign` or `Vector API`) or note it as a future optimization

### Upstream Behavioral Fidelity — MANDATORY

**Every engine-level change MUST be verified against upstream Rust source.** Do not guess, infer, or assume how upstream works — read the actual code. Violations of this rule have caused repeated correctness bugs and performance regressions.

**Concrete rules:**

1. **Read before writing.** Before implementing or modifying any search path, strategy, or engine interaction: open the corresponding upstream Rust file and read the relevant function. Cite the file path and line range in your commit message or PR description. "I believe upstream does X" is not acceptable — "upstream does X at `regex-automata/src/meta/strategy.rs:706-730`" is.

2. **Never assume engine equivalence without verification.** Our DFA implements leftmost-first semantics (matching upstream), and three-phase DFA-only search is active. However, edge cases exist (empty matches, zero-width assertions, char class overflow for Unicode patterns). When modifying DFA or search paths, always verify against the full upstream test suite. See `docs/architecture/dfa-match-semantics-gap.md`.

3. **Check `docs/architecture/` before optimizing.** Known semantic gaps are documented there. If your optimization depends on an assumption about engine behavior, check whether that assumption is listed as a known gap. If it is, the optimization is unsafe until the gap is closed.

4. **Run the full test suite (2,154 tests) after every search-path change.** Not just the module you modified — the full reactor. Upstream TOML suite failures indicate behavioral divergence from Rust. Zero failures is the only acceptable result.

5. **Run benchmarks after every search-path change.** Compare against the immediately preceding commit. Any benchmark that changes by more than 2x (in either direction) requires investigation and explanation before the change is committed. Use: `./mvnw -P bench package -DskipTests && java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5`

6. **When a subagent modifies `Strategy.java`, `Regex.java`, or any DFA/PikeVM interaction**, the review checkpoint MUST verify: (a) which upstream function this corresponds to, (b) that the search semantics match upstream, (c) that all tests pass, (d) that benchmarks show no regression.

### Java Conventions
- Target Java 21 as baseline (sealed classes, records, pattern matching, virtual threads are all available)
- Use `sealed interface` + `record` for AST/HIR node types (mirrors Rust enums)
- Prefer value objects and immutability; mutable state only where performance requires it
- Use `java.lang.foreign` (Panama FFI) or `jdk.incubator.vector` (Vector API) only if there's a clear performance case

### Debugging and Diagnostics — MANDATORY RULES
- **Never use `System.out.println` for debugging.** Use the **java-debugger skill** (DebugInspector) to inspect program state at breakpoints. This is non-negotiable. The tool is at: `java /home/kroepke/.claude/plugins/cache/kroepke-marketplace/java-debugger/0.2.0/skills/java-debugger/scripts/DebugInspector.java`
- **Never write ad-hoc Java files to /tmp for debugging.** Instead, write JUnit tests in the appropriate Maven module (`regex-automata/src/test/` for engine-level, `regex/src/test/` for API-level). Use `./mvnw test -Dtest="ClassName#methodName"` to run them. These tests get correct classpath, dependencies, and can become regression tests.
- When investigating a bug, write a focused test that reproduces it, use DebugInspector to inspect state at breakpoints, check expectations with assertions, and keep the test if the bug was real.
- For tracing DFA state transitions or NFA behavior, write a test that constructs the engine directly (not through the public API) and use DebugInspector with `--eval` to inspect intermediate state.
- **Any test with `System.out.println` is a bug.** Tests must use assertions, not console output.

### Performance
- Benchmark before and after significant changes (JMH)
- The upstream's test data in `upstream/regex/testdata/` contains comprehensive test cases — use these
- Thread safety via thread-local pools for engine scratch space, matching upstream's approach

### Stage Tagging

Tag sensible stopping points with `stage-N-description` tags. A stage is a commit where a coherent feature is complete, all tests pass, and benchmarks can be meaningfully compared. See `docs/architecture/stage-progression.md` for the full history.

**When to tag:** After completing a feature that changes search paths, adds an engine, or modifies strategy selection — any change that could affect benchmark results. The tag goes on the final commit of the feature (after all fixes and doc updates).

**How to tag:**
```bash
git tag stage-N-short-description <commit-hash>
git push --tags
```
Then add an entry to `docs/architecture/stage-progression.md` describing: what changed, which engines/strategies are active, test count, and key benchmark numbers.

## Architecture Gaps

Features intentionally deferred from initial implementations. **Check these before adding new engines or optimizations** — the gap may already be documented with design notes. Ignoring these has caused correctness bugs and performance regressions.

- **DFA char class overflow (resolved)** — `docs/architecture/dfa-match-semantics-gap.md`: Equivalence class merging keeps class counts under 256 for Unicode patterns. Patterns with `\b` still use quit-on-non-ASCII.
- **Lazy DFA gaps** — `docs/architecture/lazy-dfa-gaps.md`: overlapping mode, loop unrolling, per-pattern start states
