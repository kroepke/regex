# Upstream Architecture Comparison — Investigation Prompt

Use this prompt to start a fresh Claude Code session for an unbiased deep-dive comparison between our Java regex engine and the upstream Rust regex crate.

---

## Prompt

I need a comprehensive, unbiased architectural comparison between our Java regex engine and the upstream Rust regex crate (in `upstream/regex/` as a git submodule). We've been building this engine for weeks and I suspect we may have missed structural patterns from upstream, used wrong abstractions, or gotten off the rails in our optimization approach. I need fresh eyes.

### Background

This is a Java 21 port of the Rust regex crate. We've reached stage-14 with these engines: PikeVM, lazy DFA, bounded backtracker, one-pass DFA, and a new dense (pre-compiled) DFA with acceleration states. We beat JDK on 5 of 8 benchmarks but are still significantly behind on charClass (2.8x), multiline (4.3x), alternation (2.3x), and unicodeWord (1.9x).

### What to Compare

For each of these areas, read BOTH the upstream Rust source AND our Java implementation side by side. Don't skim — read function bodies, trace call chains, understand the data flow. Report structural differences, not just surface API differences.

#### 1. Meta Strategy Search Dispatch

**Upstream:** `upstream/regex/regex-automata/src/meta/strategy.rs` — the `Core` strategy's `search`, `search_half`, `search_slots` methods. How does it dispatch to engines? What's the decision tree?

**Ours:** `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java` — the `Core` record's `search`, `searchCaptures`, `isMatch`, `dfaSearchImpl` methods.

**Key questions:**
- Does upstream have a `search_half` (half-match: end position only) that we're missing? How does the meta strategy use it?
- How does upstream's three-phase search work? Is it forward DFA → reverse DFA → captures, like ours? Or is there a combined search?
- What does upstream's `dfa/regex.rs` do? Is there a `Regex` type in the DFA module that wraps forward+reverse DFA into a single search call?
- How much per-match overhead does upstream have between finding match-end and finding match-start?

#### 2. DFA Search Architecture

**Upstream full DFA:** `upstream/regex/regex-automata/src/dfa/search.rs` (search), `upstream/regex/regex-automata/src/dfa/dense.rs` (data structure)

**Upstream lazy DFA:** `upstream/regex/regex-automata/src/hybrid/search.rs` (search), `upstream/regex/regex-automata/src/hybrid/dfa.rs` (data structure)

**Upstream DFA regex wrapper:** `upstream/regex/regex-automata/src/dfa/regex.rs` — THIS IS IMPORTANT. There's a `Regex` type in the DFA module that wraps forward+reverse DFA. Read it thoroughly.

**Ours:** `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFA.java` (our dense DFA), `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java` (our lazy DFA)

**Key questions:**
- Does upstream's `dfa/regex.rs` combine forward+reverse search into a single method that avoids allocating Input objects between phases?
- How does upstream's DFA regex handle the match-start finding? Does it call the reverse DFA inline or through the strategy?
- What's the overhead per match in upstream's three-phase vs ours?

#### 3. Prefilter Integration

**Upstream:** `upstream/regex/regex-automata/src/meta/strategy.rs` — search methods that use prefilters. Also `upstream/regex/regex-automata/src/util/prefilter/` — the prefilter types.

**Ours:** `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java` — `prefilterLoop`, `isMatchPrefilter`. Also `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Prefilter.java`.

**Key questions:**
- How does upstream integrate prefilters at the strategy level vs the DFA level?
- Does upstream's lazy DFA's prefilter-at-start-state actually matter for performance, or is the strategy-level prefilter sufficient? (We tried DFA-internal prefilter and found it redundant — was that correct?)

#### 4. Acceleration States

**Upstream:** `upstream/regex/regex-automata/src/dfa/accel.rs` (detection), `upstream/regex/regex-automata/src/dfa/search.rs:150-172` (integration in full DFA search)

**Ours:** `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFABuilder.java` (detection in `analyzeAcceleration`), `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/dense/DenseDFA.java` (scan in `searchFwd`)

**Key questions:**
- Is our `boolean[128]` escape table equivalent to upstream's `memchr`-based approach? Or is upstream doing something fundamentally different?
- Does upstream accelerate the start state? (We don't.) Would that help?
- How does upstream's acceleration interact with the unrolled loop? Is it checked inline or as a separate scan?

#### 5. Match Iteration (findAll / Searcher)

**Upstream:** `upstream/regex/regex-automata/src/util/iter.rs` — match iteration. `upstream/regex/regex-automata/src/meta/regex.rs` — the top-level `find_iter`.

**Ours:** `regex/src/main/java/lol/ohai/regex/Regex.java` — `Searcher` inner class, `BaseFindIterator`, `findAll`.

**Key questions:**
- How does upstream handle the empty-match-at-same-position skip? Is it the same as ours?
- Does upstream's iteration avoid per-match allocation more aggressively than ours?
- Is there a structural difference in how upstream iterates vs how our `Searcher.find()` works?

#### 6. CharClasses / Alphabet Mapping

**Upstream:** `upstream/regex/regex-automata/src/util/alphabet.rs` — byte classes.

**Ours:** `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/CharClasses.java`, `CharClassBuilder.java`

**Key questions:**
- Upstream works on bytes (UTF-8), we work on chars (UTF-16). Does this fundamental difference affect DFA state count or transition table size?
- How many equivalence classes does upstream produce for patterns like `[a-zA-Z]+` vs how many we produce?
- Is our two-level classify (high byte → row → low byte) equivalent to upstream's byte class mapping? Any overhead difference?

### Our Benchmark Results vs JDK (for context)

| Benchmark | Our engine | JDK | Ratio |
|-----------|-----------|-----|-------|
| literal `Sherlock Holmes` | 5,170 | 2,717 | **1.9x faster** |
| literalMiss `ZQZQZQZQ` | 5,162 | 3,244 | **1.6x faster** |
| charClass `[a-zA-Z]+` | 100 | 291 | 2.9x slower |
| alternation `Sherlock\|Watson\|...` | 49 | 107 | 2.2x slower |
| captures `(\d{4})-(\d{2})` | 22,114 | 16,015 | **1.4x faster** |
| unicodeWord `\w+` | 17,519 | 33,430 | 1.9x slower |
| multiline `(?m)^.+$` | 238 | 1,027 | 4.3x slower |
| wordRepeat `\w{50}` | 96,102 | 11,619 | **8.3x faster** |

The benchmarks use the Sherlock Holmes text (~567KB). Numbers are ops/s (SearchBenchmark: count all matches in the full text per iteration).

### Key Design Documents

Read these for context on decisions we've made:
- `docs/architecture/stage-progression.md` — full history of all stages with benchmark numbers
- `docs/architecture/lazy-dfa-gaps.md` — deferred features tracker
- `docs/superpowers/specs/2026-03-16-dense-dfa-engine-design.md` — dense DFA design
- `docs/superpowers/specs/2026-03-16-acceleration-states-design.md` — acceleration states design
- `docs/superpowers/specs/2026-03-16-dfa-jit-headroom-design.md` — JIT analysis (includes Phase 2 prefilter-at-start findings: REVERTED as redundant)

### Optimization Attempts That Failed or Were Reverted

1. **DFA-internal prefilter-at-start** — tried adding prefilter.find() inside searchFwdLong. Caused JIT cliff (2.3x regression from method size). Reverted. Then rebuilt in dense DFA but found it redundant with Strategy-level prefiltering (patterns that need acceleration have no literal prefix).

2. **MATCH_FLAG-based match detection in dense DFA** — switched from range-based (`sid >= minMatchState`) to flag-based (`sid < 0`). Caused -8% charClass regression. Reverted to range-based after confirming upstream uses range checks in the full DFA.

3. **JIT headroom for lazy DFA** — extracted cold paths from searchFwdLong (613→472 bytes). Gave +14% on rawUnicodeWord but didn't unlock more classify inlining as hoped (C2 budget limited by IR node count, not bytecode size alone).

### What I Want From This Analysis

1. **Structural gaps:** What architectural patterns from upstream are we missing? Not micro-optimizations, but structural choices that affect how engines compose and how per-match overhead scales.

2. **Wrong abstractions:** Are there places where our Java abstractions diverge from upstream in ways that add overhead? (e.g., our Input object, our Captures allocation, our Strategy dispatch pattern)

3. **UTF-8 vs UTF-16 impact:** How much does the byte-vs-char difference affect us? Does upstream's byte-oriented design give it inherent advantages we can't match?

4. **Specific recommendations:** For each gap found, estimate the impact and effort. What should we tackle next?

Be brutally honest. If our approach is sound and the gaps are just "Java is slower than Rust for this workload," say that. If we've made architectural mistakes, point them out clearly with upstream file:line references.
