# Look-Assertion Encoding in Lazy DFA States

Encode look-around assertions (`^`, `$`, `\b`, `\B`, line anchors, word boundaries) as part of the lazy DFA state key so that patterns containing look-assertions can use the DFA acceleration path instead of falling back to PikeVM only.

## Context

The lazy DFA currently bails out entirely for patterns containing look-assertions. `LazyDFA.create()` returns `null` when any `State.Look` exists in the NFA (line 50 of `LazyDFA.java`). `Strategy.Core` then falls back to PikeVM for the full search. This means common patterns like `^\w+`, `\bword\b`, and multiline patterns receive no DFA acceleration at all.

The upstream Rust regex crate handles look-assertions by encoding look-behind context as part of the DFA state key (`look_have` / `look_need` fields). This design implements the same approach for our Java lazy DFA.

## Goal

Remove the look-assertion bail-out from the lazy DFA so that patterns with `^`, `$`, `\A`, `\z`, `\b`, `\B`, line anchors, and word boundaries can use the DFA acceleration path.

## Design

### 1. New Types: LookSet and Start

**`LookSet`** — A 32-bit bitset over `LookKind` values. We have 18 look kinds, which fits in an `int`. Immutable record wrapping an `int`. Each `LookKind` maps to a bit via `ordinal()`.

Note: The `LookKind` enum ordinal order must remain stable. Reordering variants would silently break all `LookSet` semantics. Consider adding an `asBit()` method that returns `1 << ordinal()` to make the bit-mapping explicit.

```java
public record LookSet(int bits) {
    public static final LookSet EMPTY = new LookSet(0);

    public LookSet insert(LookKind k)    { return new LookSet(bits | (1 << k.ordinal())); }
    public boolean contains(LookKind k) { return (bits & (1 << k.ordinal())) != 0; }
    public LookSet union(LookSet other)  { return new LookSet(bits | other.bits); }
    public LookSet intersect(LookSet o)  { return new LookSet(bits & o.bits); }
    public LookSet subtract(LookSet o)   { return new LookSet(bits & ~o.bits); }
    public boolean isEmpty()             { return bits == 0; }
}
```

**`Start`** — Enum with 5 variants representing look-behind context at the search start position:

| Variant | Condition | Assertions satisfied | `isFromWord` |
|---|---|---|---|
| `TEXT` | Position 0 | `START_TEXT`, `START_LINE`, `START_LINE_CRLF`, `WORD_START_HALF_*` | false |
| `LINE_LF` | After `\n` | `START_LINE`, `START_LINE_CRLF`, `WORD_START_HALF_*` | false |
| `LINE_CR` | After `\r` | `START_LINE_CRLF`; sets `isHalfCrlf` | false |
| `WORD_BYTE` | After word char | — | true |
| `NON_WORD_BYTE` | After non-word char | `WORD_START_HALF_*` | false |

The upstream Rust crate has a 6th variant `CustomLineTerminator` for configurable line terminators. This will be added if/when the syntax layer supports custom line terminators.

Each variant has a method to compute the initial `lookHave` and `isFromWord` values.

A static factory `Start.from(char[] haystack, int pos)` classifies a position by examining the char immediately before `pos` (or recognizing position 0 as `TEXT`).

### 2. StateContent Changes

`StateContent` currently holds `int[] nfaStates` and `boolean isMatch`. Add four new fields:

| Field | Type | In equals/hashCode | Purpose |
|---|---|---|---|
| `isFromWord` | `boolean` | yes | Previous char was a word char |
| `isHalfCrlf` | `boolean` | yes | Inside a `\r\n` pair |
| `lookHave` | `int` (`LookSet` bits) | yes | Satisfied look-behind assertions |
| `lookNeed` | `int` (`LookSet` bits) | **no** | Assertions present in the NFA state set (optimization hint) |

`lookHave` and `lookNeed` together prevent state explosion:

- `lookNeed` is derived from the NFA states in the set (not part of the key) and represents which assertions are actually relevant to the current NFA state set.
- When `lookNeed` is empty (no assertions in the current NFA state set), `lookHave` is forced to 0. This prevents creating duplicate DFA states that differ only in look-behind context that no NFA state cares about — the common case when the current set has moved past all look nodes.

### 3. Epsilon Closure Changes

The current `epsilonClosure` skips `State.Look` transitions entirely. Change it to accept a `LookSet lookHave` parameter and conditionally follow `Look` transitions:

```java
private boolean epsilonClosure(DFACache cache, int startStateId, LookSet lookHave) {
    // ... same DFS stack loop ...
    case State.Look look -> {
        if (lookHave.contains(look.kind())) {
            stack[stackTop++] = look.next();  // assertion satisfied, follow
        }
        // Otherwise: assertion not satisfied — this NFA path is dead for this context
    }
    // ... all other cases unchanged ...
}
```

If `lookHave` is `EMPTY` and no look-assertions are present in the pattern, this degrades to the existing behavior with no overhead (the `contains` check never fires).

### 4. Computing Look-Around for Destination States

`computeNextState` gains a `computeLookHave` helper (which accepts a `boolean reverse` parameter, or uses `nfa.isReverse()`) that operates in two distinct phases.

Note on direction: CRLF handling is direction-dependent. In forward mode, `\r` starts a half-CRLF and `\n` may complete it. In reverse mode, `\n` starts a half-CRLF and `\r` may complete it. The `computeLookHave` logic must account for the search direction throughout.

**Phase 1: Look-ahead resolution on source state.** Before computing char transitions, determine which additional assertions beyond `sourceContent.lookHave()` are satisfied by the current input unit. These are "look-ahead" assertions that look at what is about to be consumed. The updated set is checked against the source state's `lookNeed` — if new assertions relevant to the NFA state set have become true, the epsilon closure is re-run before proceeding.

Key assertions resolved in Phase 1:

- `END_LINE` — when input unit is `\n` (the line terminator)
- `END_LINE_CRLF` — when input unit is `\r` (only if NOT `isHalfCrlf` in forward mode, or NOT `isHalfCrlf` in reverse mode for `\n`), or `\n` (only if NOT `isHalfCrlf` OR in reverse mode)
- `END_TEXT` — at EOI
- `START_LINE_CRLF` — when source `isHalfCrlf` and current byte is NOT the completing half of CR/LF (direction-dependent: forward checks for `\n`, reverse checks for `\r`)
- Word boundary assertions: `WORD_BOUNDARY_ASCII/UNICODE` when `isFromWord != currentIsWord`, `WORD_BOUNDARY_ASCII/UNICODE_NEGATE` when they are the same, `WORD_START_*` when `!isFromWord && currentIsWord`, `WORD_END_*` when `isFromWord && !currentIsWord`
- `WORD_END_HALF_ASCII/UNICODE` — when current unit is NOT a word byte (independent of `isFromWord`)

Re-computation optimization check: after assembling the updated look set, compute:

```java
LookSet newlyTrue = updatedLookHave.subtract(sourceContent.lookHave())
                                   .intersect(sourceContent.lookNeed());
if (!newlyTrue.isEmpty()) {
    // Re-run epsilon closure with the updated lookHave before collecting transitions
    recomputeEpsilonClosure(cache, updatedLookHave);
}
```

This avoids re-running epsilon closure when no new assertions are relevant — the common case when the NFA state set has no pending look nodes.

**Phase 2: Look-behind on destination state.** After collecting the destination NFA states via char transitions, set the destination state's look-behind context based on the consumed char. This context will be used when computing the *next* transition from the destination state.

- `START_LINE` — if consumed char is `\n` (the line terminator)
- `START_LINE_CRLF` — if consumed char is `\n` (forward) or `\r` (reverse)
- `WORD_START_HALF_ASCII/UNICODE` — if consumed char is NOT a word byte
- `isFromWord` — set true if consumed char IS a word byte
- `isHalfCrlf` — set true if consumed char is `\r` (forward) or `\n` (reverse)

Phase 2 sets context ONLY when the destination state is non-empty (has NFA states). Dead states must not have look-behind context assigned — assigning context to dead states would cause pathological cache behavior by creating distinct dead-state entries for each look context.

**Precomputed equivalence class flags:** Add to `CharClasses`:

- `boolean[] isWordClass` — indexed by class ID, true if the representative char is a word char (ASCII fast path + Unicode)
- `boolean[] isLineLF` — true if the representative char is `\n`
- `boolean[] isLineCR` — true if the representative char is `\r`

These are precomputed once during `CharClasses` construction and used in `computeLookHave` to avoid per-character classification during search.

### 4a. Union and BinaryUnion States in the NFA State Set

Once look-assertions are supported, Union and BinaryUnion NFA states must be included in the DFA state's NFA state set (in addition to char-consuming states and Match states). This is necessary for correctness when look-assertions appear inside repetitions, for example `(?:\b|%)+`.

Without Union/BinaryUnion states in the set, the epsilon closure re-computation in Phase 1 cannot re-traverse the branching structure to discover newly-reachable paths when additional assertions become true mid-search. Including these states ensures the re-computation has the full graph available to explore.

### 5. Start State Configuration

`DFACache` expands from 2 start states (anchored/unanchored) to 10 (5 `Start` variants × anchored/unanchored). The upstream Rust crate uses 12 (6 variants × 2) due to its `CustomLineTerminator` variant. Start states are computed lazily on first use, using the same pattern as today.

`initStartState` sets the initial `lookHave` and `isFromWord` based on the `Start` variant (see the table in Section 1).

The search loops (`searchFwd` / `searchRev`) classify the position using `Start.from(haystack, pos)` to select the right start state.

**Fast path:** If the NFA has no look-assertions at all (`nfa.lookSetAny().isEmpty()`), all 5 `Start` variants produce identical DFA states. Fall back to the existing 2-start-state path with zero overhead — no classification, no extra state slots, behavior identical to today.

### 6. NFA Metadata

`NFA` gains a `lookSetAny` field — a `LookSet` that is the union of all `LookKind` values present in `State.Look` nodes. Computed once in `Builder.build()` during NFA construction.

`lookSetAny` drives conditional behavior throughout the engine:

| Check | Effect |
|---|---|
| `lookSetAny.isEmpty()` | Skip all look-assertion logic; fast path identical to current code |
| Contains any word boundary kind | Track `isFromWord`; precompute word-char class flags |
| Contains `START_LINE_CRLF` or `END_LINE_CRLF` | Track `isHalfCrlf` |
| Non-empty | Use 10-start-state layout in `DFACache` (upstream: 12) |

`LazyDFA.create()` no longer bails out on look-assertions — remove the `hasLookStates` check entirely.

### 7. What Does Not Change

- **`Strategy.Core`** — no changes needed. The existing two-phase search handles non-null forward DFAs correctly regardless of whether look-assertions are encoded.
- **`Regex.java` compilation pipeline** — unchanged.
- **`PikeVM`** — unchanged; still used for start/capture resolution in the two-phase search.
- **`Prefilter`** — unchanged.

### 8. Impact

**Patterns that benefit:**

- `^bc(d|e)*$` — DFA handles `^` and `$` natively instead of bailing out to PikeVM for the full search
- `[a-zA-Z_][a-zA-Z0-9_]*\b` — DFA handles `\b` natively
- Any pattern with line anchors in multiline mode
- `\bword\b` and similar word-boundary patterns

**Patterns not affected:**

- Unicode character class patterns (`charClass`, `captures`, `unicodeWord` benchmarks) — their bottleneck is cache thrashing from large equivalence class spaces, not look-assertions. No regression, no improvement.
- Patterns without look-assertions — zero overhead; the `lookSetAny.isEmpty()` fast path produces behavior identical to today.

### 9. Testing Strategy

- **Existing upstream TOML test suite** (839+ cases) covers all look-assertion patterns comprehensively. All cases that currently pass must continue to pass. Many cases that previously exercised PikeVM-only will now exercise the DFA path, providing implicit coverage of the new encoding.
- **Unit tests for `LookSet`**: all bitset operations (`insert`, `contains`, `union`, `intersect`, `subtract`, `isEmpty`), boundary values, all 18 `LookKind` ordinals.
- **Unit tests for `Start.from()`**: classification at position 0 (`TEXT`), after `\n` (`LINE_LF`), after `\r` (`LINE_CR`), after a word char (`WORD_BYTE`), after a non-word char (`NON_WORD_BYTE`).
- **Unit tests for epsilon closure with look-assertions**: verify `State.Look` transitions are followed when the assertion is in `lookHave` and skipped when it is not.
- **Unit tests for start state selection** in `searchFwd` / `searchRev`: verify the correct `Start` variant is chosen at different positions.
- **Integration tests for mixed-assertion patterns**: `^\bword\b$`, `^line1\nline2$` in multiline mode, `\Binner\B`.
- **Give-up fallback**: verify patterns that cause DFA cache thrashing with look-assertions still fall back to PikeVM correctly without incorrect results.

## Out of Scope (Deferred)

- **Quit bytes** — separate optimization for Unicode patterns causing cache thrashing; orthogonal to look-assertion encoding
- **Three-phase search activation** — requires HIR-level lazy/greedy analysis; orthogonal to this change
- **Custom line terminators** — not currently supported in the syntax layer
