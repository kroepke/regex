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

<!-- TODO: Update these commands once the build system is set up -->
The project uses Maven. Update this section once the POM is configured.

```bash
# Build
mvn compile

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest="SomeTest"

# Run a single test method
mvn test -Dtest="SomeTest#someMethod"

# Package
mvn package
```

## Development Guidelines

### Porting from Rust
- Use `upstream/regex/` as the authoritative reference for behavior and algorithms
- Translate Rust idioms to Java equivalents (e.g., `enum` variants → sealed interfaces/records, `Result` → exceptions or `Optional`, `&[u8]` → `byte[]`)
- Preserve the upstream's module boundaries — they exist for good architectural reasons
- Keep upstream doc comments as reference comments where they clarify non-obvious algorithm choices
- When upstream uses Rust-specific optimizations (e.g., SIMD via `memchr`), find the Java equivalent (e.g., `java.lang.foreign` or `Vector API`) or note it as a future optimization

### Java Conventions
- Target Java 21 as baseline (sealed classes, records, pattern matching, virtual threads are all available)
- Use `sealed interface` + `record` for AST/HIR node types (mirrors Rust enums)
- Prefer value objects and immutability; mutable state only where performance requires it
- Use `java.lang.foreign` (Panama FFI) or `jdk.incubator.vector` (Vector API) only if there's a clear performance case

### Performance
- Benchmark before and after significant changes (JMH)
- The upstream's test data in `upstream/regex/testdata/` contains comprehensive test cases — use these
- Thread safety via thread-local pools for engine scratch space, matching upstream's approach
