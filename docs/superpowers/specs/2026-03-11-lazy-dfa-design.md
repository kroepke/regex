# Lazy DFA Engine

Introduce a lazy (hybrid) DFA engine that builds DFA states on demand during search, providing O(1) per-character throughput for most patterns while maintaining O(m × n) worst-case guarantees.

## Motivation

After the meta engine + literal prefilter work, our literal search now beats the JDK (4,753 vs 3,168 ops/s). But patterns without extractable literal prefixes still rely on PikeVM, which is O(m × n) per character — exploring every NFA state at every position. The remaining benchmark gaps:

| Benchmark | ohai | JDK | Gap |
|---|---|---|---|
| charClass `[a-zA-Z]+` | 12.9 | 231 | 18x slower |
| alternation `Sherlock\|Watson\|Holmes\|Irene` | 44.5 | 105 | 2.4x slower |
| captures `(\d{4})-(\d{2})-(\d{2})` | 59.5 | 15,940 | 268x slower |
| unicodeWord `\w+` | 13.0 | 32,961 | 2,536x slower |

The lazy DFA addresses this by converting the NFA state set into a single DFA state — one table lookup per character instead of iterating all active NFA states. States are built on demand (lazy) to avoid the exponential blowup of full DFA construction.

In the upstream Rust regex crate, the lazy/hybrid DFA is the primary throughput engine. PikeVM is the last-resort fallback.

## Scope

**In scope:**
- Char equivalence classes (two-level table for UTF-16)
- Lazy DFA engine with on-demand powerset construction via epsilon closure
- Forward-only search, leftmost-first semantics
- Tagged state IDs (UNKNOWN, DEAD, QUIT, MATCH)
- Cache with clear-on-full + give-up heuristic
- Two-phase search: lazy DFA finds match end → PikeVM resolves start + captures
- Integration into `Strategy.Core` with per-search fallback to PikeVM
- Gap tracking document

**Out of scope** (see `docs/architecture/lazy-dfa-gaps.md`):
- Reverse DFA for finding match start without PikeVM
- Overlapping match mode
- Quit bytes for graceful degradation
- Per-pattern start states (multi-pattern matching)
- Loop unrolling optimizations in search loop
- Look-around encoding in DFA states (`look_have`/`look_need` — see below)
- Anchored DFA start state optimization beyond basic anchored/unanchored

**Look-assertion bailout (initial scope limitation):** The upstream encodes look-around context (`look_have`/`look_need` bitsets, word-char flags, CRLF state) in the DFA state content for correct handling of `^`, `$`, `\b`, `\B`, and `(?m)` anchors. This is complex and deferred. Instead, the lazy DFA **detects patterns containing look-around assertions** during construction and sets a flag. When this flag is set, `Strategy.Core` skips the lazy DFA entirely and uses PikeVM. This is safe because PikeVM handles all assertions correctly. The target benchmarks (`[a-zA-Z]+`, `\d{4}-\d{2}-\d{2}`, `\w+`, alternations) do not use look-assertions and will benefit from the lazy DFA. Patterns with `^`, `$`, `\b` fall back to PikeVM at no regression. Full look-assertion support is tracked in the gaps doc.

## Design Decisions

**Char-unit, not byte-unit.** Our entire pipeline (NFA, PikeVM, Input) operates on UTF-16 char units. The lazy DFA follows suit. Surrogate pairs are individual char transitions, same as PikeVM handles them today.

**Two-level equivalence class table.** A flat `char[]` map of size 65,536 costs 128KB per compiled regex. Instead, a two-level table (256-entry high-byte index → shared 256-entry rows) compresses to ~1-5KB for typical patterns. ASCII-only patterns need ~3 unique rows; Unicode-heavy patterns ~15-20. Lookup is still O(1): two array accesses.

**Pre-multiplied state IDs.** State IDs encode their transition table offset directly (ID = row index × stride). This eliminates a multiplication in the hot search loop. Stride is the next power of 2 ≥ (classCount + 1), so bit-masking works for alignment.

**MATCH flag in high bit.** Match states have bit 31 set on their state ID. A single `sid < 0` check in the hot loop detects matches without a separate lookup. The flag is cleared to get the real state ID for continued searching.

**Give-up, not unbounded retry.** After N cache clears (default 3), if `bytesSearched / statesCreated < MIN_BYTES_PER_STATE` (default 10), the lazy DFA returns `GaveUp`. `Strategy.Core` catches this and falls back to PikeVM for that specific search. This prevents the lazy DFA from being a net negative on pathological patterns.

**Two-phase search.** The forward lazy DFA only reports where a match ends, not where it starts. Finding the start requires either a reverse DFA (out of scope) or PikeVM. We run PikeVM on the narrowed window `[input.start(), matchEnd]` to get the start position and any capture groups. This is fast because the window is typically small.

**Separate CharClasses from LazyDFA.** Char equivalence classes are useful for a future full (pre-compiled) DFA too. Placing `CharClasses` in the `dfa` package and `LazyDFA` in `dfa.lazy` mirrors the upstream structure and allows reuse.

## Compilation Pipeline

**Current:**
```
Pattern → AST → HIR → LiteralExtractor(HIR) → prefixes
                    → NFA → PikeVM
                    → Strategy.build(PikeVM, prefixes) → Strategy
```

**New:**
```
Pattern → AST → HIR → LiteralExtractor(HIR) → prefixes
                    → NFA → CharClasses(NFA)
                          → PikeVM
                          → LazyDFA(NFA, CharClasses)
                    → Strategy.build(PikeVM, LazyDFA, prefixes) → Strategy
```

The `LazyDFA` is always constructed (it's cheap — just stores references to NFA + CharClasses and computes start states). The actual DFA state construction happens lazily during search.

## Char Equivalence Classes

```java
// lol.ohai.regex.automata.dfa.CharClasses

public final class CharClasses {
    private final byte[][] rows;       // shared rows, each 256 entries
    private final int[] highIndex;     // char high byte → row index, length 256
    private final int classCount;      // number of distinct equivalence classes

    /** Returns the equivalence class for the given char. O(1). */
    public int classify(char c) {
        return rows[highIndex[c >>> 8]][c & 0xFF] & 0xFF;
    }

    /** Number of equivalence classes (not counting EOI). */
    public int classCount() { return classCount; }

    /** Stride for transition table: next power of 2 ≥ classCount + 1 (EOI). */
    public int stride() { ... }

    /** log2(stride) for bit-shift operations. */
    public int strideShift() { ... }
}
```

**Row sharing:** During construction, rows with identical content get deduplicated. For ASCII patterns, high bytes 0x01-0xFF typically share a single "all same class" row.

### CharClassBuilder

```java
// lol.ohai.regex.automata.dfa.CharClassBuilder

public final class CharClassBuilder {
    /** Build equivalence classes from an NFA's transitions. */
    public static CharClasses build(NFA nfa);
}
```

**Algorithm:**
1. Collect all char range boundaries from `CharRange` and `Sparse` states in the NFA
2. Sort and deduplicate boundaries
3. Each interval between consecutive boundaries is one equivalence class
4. Chars before the first boundary and after the last boundary are also classes
5. Build the two-level table: for each of the 256 high bytes, compute the 256-entry row mapping low bytes to class IDs. Deduplicate identical rows.

## LazyDFA

```java
// lol.ohai.regex.automata.dfa.lazy.LazyDFA

public final class LazyDFA {
    private final NFA nfa;
    private final CharClasses charClasses;

    /**
     * Creates a LazyDFA, or returns null if the NFA contains look-assertions
     * (^, $, \b, \B, (?m)) which are not yet supported in the DFA.
     */
    public static LazyDFA create(NFA nfa, CharClasses charClasses);

    /** Create per-search mutable state. */
    public Cache createCache();

    /**
     * Forward search for the end position of the leftmost-first match.
     *
     * @return Match(endPos), NoMatch, or GaveUp(offset)
     */
    public SearchResult searchFwd(Input input, Cache cache);
}
```

`LazyDFA` is immutable and thread-safe. All mutable state lives in `Cache`.

## SearchResult

```java
// lol.ohai.regex.automata.dfa.lazy.SearchResult

public sealed interface SearchResult {
    record Match(int end) implements SearchResult {}
    record NoMatch() implements SearchResult {}
    record GaveUp(int offset) implements SearchResult {}
}
```

The `GaveUp` variant carries the offset where the DFA quit, which could be useful for diagnostics. `Strategy.Core` treats it as "fall back to PikeVM from the beginning."

## Cache

```java
// lol.ohai.regex.automata.dfa.lazy.Cache

public final class Cache {
    // Transition table — flat int[], indexed by (stateId + classId)
    int[] transTable;

    // State storage — maps state index to its content
    List<StateContent> states;

    // Deduplication — content → state ID
    Map<StateContent, Integer> stateMap;

    // Start states (computed once, cached)
    int startUnanchored;    // initialized to UNKNOWN
    int startAnchored;      // initialized to UNKNOWN

    // Reusable workspace for epsilon closure / determinization
    SparseSet nfaStateSet;
    int[] closureStack;

    // Give-up tracking
    int clearCount;
    long charsSearched;
    int statesCreated;

    // Capacity
    final int maxStates;    // cache capacity / (stride * 4 bytes)

    // Sentinel state IDs (pre-allocated in transTable)
    // ID 0 = UNKNOWN (all transitions point to UNKNOWN)
    // ID 1 = DEAD (all transitions point to DEAD)
    // ID 2 = QUIT (all transitions point to QUIT)
}
```

**Initialization:** `createCache()` allocates the transition table and pre-populates 3 sentinel state rows (UNKNOWN, DEAD, QUIT). Start states are computed lazily on first search.

**Cache capacity:** Default 2MB. With stride 64 (typical for ~50 equivalence classes), each state is 256 bytes in the transition table. 2MB ≈ 8,000 states.

**Cache clearing:** When `states.size() >= maxStates`:
1. Save current state content (needed to continue searching)
2. Clear `transTable`, `states`, `stateMap`
3. Re-initialize sentinel states (UNKNOWN, DEAD, QUIT)
4. Re-add the saved state (gets new ID)
5. Reset start states to UNKNOWN (will be recomputed on next access)
6. `clearCount++`, reset `charsSearched` and `statesCreated`

**Give-up check** (after `clearCount >= MIN_CLEAR_COUNT`, default 3):
```
if (charsSearched / statesCreated < MIN_CHARS_PER_STATE) → return GaveUp
```
`MIN_CHARS_PER_STATE` default: 10. If we're building a new state every ~1-2 chars, the lazy DFA is slower than PikeVM.

## State Content and Determinization

```java
// lol.ohai.regex.automata.dfa.lazy.StateContent

public record StateContent(int[] nfaStates, boolean isMatch) {
    // equals/hashCode based on array content, not identity
}
```

**Determinization** (computing the next DFA state):

When a transition from state S on class C is `UNKNOWN`:

1. Retrieve `StateContent` for S → get the NFA state set
2. For each NFA state in the set, follow char transitions matching class C
3. Compute epsilon closure of the resulting NFA states (using the reusable `SparseSet` and `closureStack`)
4. Sort the resulting NFA state set → build `StateContent`
5. Check `stateMap` for deduplication:
   - If found: reuse existing state ID
   - If not found: allocate new row in `transTable`, store in `states` and `stateMap`
6. If cache is full: clear cache (see above), then retry
7. Write the computed state ID into `transTable[S + C]`
8. Return the new state ID

**Epsilon closure** follows the same algorithm as PikeVM's `epsilonClosure()`:
- Stack-based DFS through `Union`, `BinaryUnion`, `Capture`, `Look` states
- Only char-consuming states (`CharRange`, `Sparse`) and `Match` are added to the result set
- `Look` states: since patterns with look-assertions bail out to PikeVM (see scope), `Look` states should not appear in the NFA for patterns reaching the lazy DFA. If a `Look` state is encountered, treat it as unsupported and propagate a give-up signal.
- `Capture` states: transparent — follow through to the next state. The lazy DFA doesn't track captures; PikeVM handles that in phase 2.
- Insertion order determines priority (leftmost-first semantics)

**Match detection:** If any NFA state in the epsilon closure is a `Match` state, the resulting `StateContent.isMatch` is true, and the DFA state ID gets the `MATCH` flag.

## Forward Search Loop

```java
public SearchResult searchFwd(Input input, Cache cache) {
    char[] haystack = input.haystack();
    int pos = input.start();
    int end = input.end();

    int sid = getStartState(input, cache);
    if (sid <= QUIT) return handleSpecial(sid);

    int lastMatchEnd = -1;

    while (pos < end) {
        int classId = charClasses.classify(haystack[pos]);
        int nextSid = cache.transTable[sid + classId];

        if (nextSid > QUIT) {
            // Common case: normal, non-special, non-match state. Just advance.
            sid = nextSid;
            pos++;
            continue;
        }

        if (nextSid < 0) {
            // Match state (high bit set)
            lastMatchEnd = pos + 1;
            sid = nextSid & 0x7FFF_FFFF;  // clear match flag
            pos++;
            continue;
        }

        // nextSid is UNKNOWN, DEAD, or QUIT
        if (nextSid == UNKNOWN) {
            // sid is the source state — computeNextState looks up its StateContent
            nextSid = computeNextState(cache, sid, classId);
            if (nextSid == QUIT) return new SearchResult.GaveUp(pos);
            // Write computed transition into table for future lookups
            cache.transTable[sid + classId] = nextSid;
            sid = nextSid;
            // Check if the newly computed state is a match
            if (sid < 0) {
                lastMatchEnd = pos + 1;
                sid = sid & 0x7FFF_FFFF;
            }
            pos++;
            continue;
        }
        if (nextSid == DEAD) break;
        if (nextSid == QUIT) return new SearchResult.GaveUp(pos);
    }

    // EOI transition
    int eoiClass = charClasses.classCount();
    int eoiSid = cache.transTable[sid + eoiClass];
    if (eoiSid == UNKNOWN) {
        eoiSid = computeNextState(cache, sid, eoiClass);
        cache.transTable[sid + eoiClass] = eoiSid;
    }
    if (eoiSid < 0) {
        lastMatchEnd = end;
    }

    if (lastMatchEnd >= 0) return new SearchResult.Match(lastMatchEnd);
    return new SearchResult.NoMatch();
}
```

**Hot path:** The `nextSid > QUIT` check is the common case — a normal cached transition with no special flags. Since QUIT is a small positive constant and match states are negative (bit 31 set), this single comparison catches the fast path. Everything else (match detection, unknown transitions, dead/quit) is the slow path.

**State tracking:** At the point where `nextSid == UNKNOWN`, `sid` holds the state ID we are transitioning FROM. `computeNextState` uses `sid` to look up the source `StateContent` and compute the epsilon closure for the target NFA states.

## Strategy.Core Integration

```java
record Core(PikeVM pikeVM, LazyDFA lazyDFA, Prefilter prefilter) implements Strategy {

    @Override
    public Cache createCache() {
        // lazyDFA may be null if pattern has look-assertions (bailout)
        return new Cache(pikeVM.createCache(),
                lazyDFA != null ? lazyDFA.createCache() : null);
    }

    @Override
    public boolean isMatch(Input input, Cache cache) {
        if (prefilter != null && !input.isAnchored()) {
            return prefilterIsMatch(input, cache);
        }
        if (lazyDFA == null) {
            // Look-assertion bailout: pattern uses ^, $, \b, etc.
            return pikeVM.isMatch(input, cache.pikeVMCache());
        }
        SearchResult result = lazyDFA.searchFwd(input, cache.lazyDFACache());
        return switch (result) {
            case SearchResult.Match m -> true;
            case SearchResult.NoMatch n -> false;
            case SearchResult.GaveUp g -> pikeVM.isMatch(input, cache.pikeVMCache());
        };
    }

    @Override
    public Captures search(Input input, Cache cache) {
        if (prefilter != null && !input.isAnchored()) {
            return prefilterSearch(input, cache);
        }
        if (lazyDFA == null) {
            return pikeVM.search(input, cache.pikeVMCache());
        }
        SearchResult result = lazyDFA.searchFwd(input, cache.lazyDFACache());
        return switch (result) {
            case SearchResult.Match m -> {
                // Phase 2: PikeVM on narrowed window to find start + captures.
                // Preserve anchored flag from original input.
                Input narrowed = input.withBounds(
                        input.start(), m.end(), input.isAnchored());
                yield pikeVM.search(narrowed, cache.pikeVMCache());
            }
            case SearchResult.NoMatch n -> null;
            // GaveUp: fall back to PikeVM from input.start() — the DFA may
            // have passed a match start without knowing it, so we can't resume
            // from the give-up offset.
            case SearchResult.GaveUp g -> pikeVM.search(input, cache.pikeVMCache());
        };
    }

    @Override
    public Captures searchCaptures(Input input, Cache cache) {
        if (prefilter != null && !input.isAnchored()) {
            return prefilterSearchCaptures(input, cache);
        }
        if (lazyDFA == null) {
            return pikeVM.searchCaptures(input, cache.pikeVMCache());
        }
        SearchResult result = lazyDFA.searchFwd(input, cache.lazyDFACache());
        return switch (result) {
            case SearchResult.Match m -> {
                Input narrowed = input.withBounds(
                        input.start(), m.end(), input.isAnchored());
                yield pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
            }
            case SearchResult.NoMatch n -> null;
            case SearchResult.GaveUp g ->
                pikeVM.searchCaptures(input, cache.pikeVMCache());
        };
    }
}
```

**Look-assertion bailout:** `LazyDFA` construction detects patterns with look-assertions (`^`, `$`, `\b`, `\B`, `(?m)`) and returns `null` instead of a `LazyDFA` instance. `Strategy.Core` stores this null and guards all search methods with `if (lazyDFA == null)` checks.

**Prefilter + lazy DFA:** The prefilter loop scans for prefix candidates using `indexOf`, then runs the lazy DFA from the candidate position instead of PikeVM. If the lazy DFA gives up at any point in the loop, the entire search falls back to PikeVM (using the existing prefilter loop with PikeVM, same as today).

**GaveUp fallback:** When the DFA gives up, PikeVM always restarts from `input.start()`, not from `GaveUp.offset`. The forward DFA only reports match ends; it may have passed a match start position without knowing it. The offset is retained for diagnostics only.

**Thread safety:** `Cache` instances are not thread-safe and must not be shared across threads. `Regex.java` manages this via `ThreadLocal<Strategy.Cache>` (existing pattern).

**Strategy.Cache update:**

```java
record Cache(
    lol.ohai.regex.automata.nfa.thompson.pikevm.Cache pikeVMCache,
    lol.ohai.regex.automata.dfa.lazy.Cache lazyDFACache
) {
    static Cache withPikeVMOnly(lol.ohai.regex.automata.nfa.thompson.pikevm.Cache c) {
        return new Cache(c, null);
    }
}
```

`PrefilterOnly` continues to use `Cache.EMPTY` (both fields null).

## Regex.java Changes

```java
static Regex create(String pattern, int nestLimit) throws PatternSyntaxException {
    try {
        Ast ast = Parser.parse(pattern, nestLimit);
        Hir hir = Translator.translate(pattern, ast);

        LiteralSeq prefixes = LiteralExtractor.extractPrefixes(hir);
        Prefilter prefilter = buildPrefilter(prefixes);

        Strategy strategy;
        Map<String, Integer> namedGroups;

        if (prefilter != null && prefilter.isExact()
                && prefixes.coversEntirePattern() && !hirHasCaptures(hir)) {
            strategy = new Strategy.PrefilterOnly(prefilter);
            namedGroups = Collections.emptyMap();
        } else {
            NFA nfa = Compiler.compile(hir);
            CharClasses charClasses = CharClassBuilder.build(nfa);
            PikeVM pikeVM = new PikeVM(nfa);
            // Returns null if pattern has look-assertions (bailout)
            LazyDFA lazyDFA = LazyDFA.create(nfa, charClasses);
            strategy = new Strategy.Core(pikeVM, lazyDFA, prefilter);
            namedGroups = buildNamedGroupMap(nfa);
        }

        return new Regex(pattern, strategy, namedGroups);
    } catch (...) { ... }
}
```

The `LazyDFA.create()` factory scans the NFA for look-assertion states. If found, it returns `null` and `Strategy.Core` falls back to PikeVM for all searches. Otherwise, construction is cheap (stores references + computes start state content lazily). Actual DFA states are built during the first search.

## File Structure

**New files:**

| File | Package | Purpose |
|---|---|---|
| `regex-automata/.../dfa/CharClasses.java` | `dfa` | Two-level char → class ID map |
| `regex-automata/.../dfa/CharClassBuilder.java` | `dfa` | Build equivalence classes from NFA |
| `regex-automata/.../dfa/lazy/LazyDFA.java` | `dfa.lazy` | Lazy DFA engine |
| `regex-automata/.../dfa/lazy/Cache.java` | `dfa.lazy` | Per-search mutable state |
| `regex-automata/.../dfa/lazy/StateContent.java` | `dfa.lazy` | DFA state = NFA state set |
| `regex-automata/.../dfa/lazy/SearchResult.java` | `dfa.lazy` | Match / NoMatch / GaveUp |

**Modified files:**

| File | Change |
|---|---|
| `Strategy.java` | `Core` gets `LazyDFA` field, search methods try lazy DFA first |
| `Strategy.java` (Cache) | Wraps both PikeVM.Cache and LazyDFA.Cache |
| `Regex.java` | `create()` builds CharClasses and LazyDFA |
| `module-info.java` | Export `dfa` and `dfa.lazy` packages |

**Test files:**

| File | Purpose |
|---|---|
| `CharClassesTest.java` | Equivalence class construction and lookup |
| `LazyDFATest.java` | Forward search correctness, cache clearing, give-up |
| `StrategyTest.java` | Updated: two-phase search, lazy DFA fallback |

**Existing test suites** (`UpstreamSuiteTest`, `PikeVMSuiteTest`, `MetaEngineTest`) pass unchanged — they exercise the public API.

## Expected Benchmark Impact

| Benchmark | Current | Expected | Why |
|---|---|---|---|
| literal | 4,753 | ~4,753 | Already uses PrefilterOnly, no change |
| charClass `[a-zA-Z]+` | 12.9 | ~200+ | Lazy DFA: O(1) per char instead of O(m) |
| alternation | 44.5 | ~80+ | Lazy DFA + MultiLiteral prefilter |
| captures | 59.5 | ~200+ | Lazy DFA finds end, PikeVM on narrow window |
| unicodeWord `\w+` | 13.0 | ~100+ | Lazy DFA handles Unicode classes efficiently |

The biggest wins should be `charClass` and `captures` — patterns where PikeVM's per-char cost dominates and the lazy DFA's cached transitions eliminate it.

## Constants and Defaults

| Constant | Default | Purpose |
|---|---|---|
| `CACHE_CAPACITY` | 2MB | Max memory for transition table + state storage |
| `MIN_CLEAR_COUNT` | 3 | Cache clears before checking give-up heuristic |
| `MIN_CHARS_PER_STATE` | 10 | Give up if ratio drops below this |
| `UNKNOWN` | 0 | Sentinel: transition not yet computed |
| `DEAD` | stride | Sentinel: no match possible |
| `QUIT` | stride × 2 | Sentinel: gave up, fall back |

Sentinel state IDs are pre-multiplied by stride so they can be used directly as transition table indices. Each sentinel state has a full row in the transition table (UNKNOWN's row is all UNKNOWN, DEAD's row is all DEAD, QUIT's row is all QUIT).

## Future Extensions

- **Reverse DFA** — find match start without PikeVM, enables suffix/inner literal prefilters
- **Overlapping match mode** — needed for certain regex operations
- **Quit bytes** — graceful degradation on specific byte values (e.g., non-ASCII for ASCII-only patterns)
- **Loop unrolling** — process 4 chars without branching, check for specials after
- **Per-pattern start states** — multi-pattern matching (regex set)
- **Look-behind in DFA states** — encode look-behind assertions in the DFA state content instead of delegating to PikeVM
