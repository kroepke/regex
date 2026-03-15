# One-Pass DFA Design Spec

## Goal

Add a one-pass DFA engine that extracts capture groups in a single forward scan, replacing PikeVM/bounded backtracker as the capture engine for eligible patterns. Targets the 43× captures gap vs JDK.

## Background

Currently, captures require three phases: forward DFA finds match end → reverse DFA finds match start → PikeVM/backtracker runs on the narrowed window to extract capture group slots. The PikeVM tracks multiple "threads" through the NFA, each carrying a copy of all capture slots — this is inherently expensive.

For patterns that are "one-pass" (at most one NFA thread is active at any point), we can encode capture slot updates directly in the DFA transitions. A single forward scan over the narrowed window produces both the match and all capture group values, with zero allocation during search.

**Upstream reference:** `upstream/regex/regex-automata/src/dfa/onepass.rs` (3,208 lines). The Java port adapts the design for UTF-16 char units (vs upstream's byte units) and our existing engine infrastructure.

## One-Pass Eligibility

A pattern is "one-pass" when, after consuming each input unit, at most one NFA path remains viable. This is determined at compile time by the builder — if any ambiguity is detected, the builder returns `null` and the strategy falls back to PikeVM/backtracker.

**Eligible:** `(\\d{4})-(\\d{2})-(\\d{2})`, `([a-z]+)@([a-z]+)`, `(a|b)c`, `\\w+\\s+\\w+`

**Not eligible:** `(a*)(a*)` (ambiguous split), `(.*)(.*)` (ambiguous greedy), `(a|ab)c` (overlapping alternatives on same input)

**Hard limits (matching upstream):**
- Max 16 explicit capture groups (32 slots encoded in a bitfield)
- Max 2^21 DFA states (limited by transition encoding)

## Architecture

### Transition Encoding

Each transition is a packed `long` (64 bits):

```
Bits 63–43: Next state ID (21 bits, up to 2M states)
Bit 42:     match_wins flag (for leftmost-first semantics)
Bits 41–10: Capture slot bitset (32 bits, up to 16 explicit groups)
Bits 9–0:   Look-around assertions (10 bits)
```

This is identical to upstream's layout. The transition table is a flat `long[]` array in row-major order, indexed as `table[stateId * stride + classId]`.

**Note on char units vs bytes:** Upstream operates on bytes and uses byte equivalence classes (alphabet ~256). We operate on UTF-16 chars. We reuse our existing `CharClasses.classify()` to map chars to equivalence class IDs, keeping the alphabet small. The transition table is `long[]` instead of the lazy DFA's `int[]`, but uses the same stride/classify infrastructure.

### State Layout

- State 0 = DEAD (sentinel, all transitions lead to DEAD)
- States are dense, allocated sequentially
- After compilation, match states are shuffled to the end of the table
- `minMatchId`: first match state ID — `sid >= minMatchId` means match state
- Pattern epsilons (pattern ID + final capture slot updates) stored at offset `stride` in each state's row (one extra slot beyond the alphabet)

### Cache (Per-Search Scratch Space)

```java
public final class OnePassCache {
    private final int[] explicitSlots;  // max 32 entries, pre-allocated
    private int explicitSlotLen;        // active length for current search

    public void clear(int len) {
        this.explicitSlotLen = len;
        Arrays.fill(explicitSlots, 0, len, -1);
    }
}
```

Zero allocation during search. The cache is created once per thread (via `Strategy.Cache`) and reused across searches.

### Compilation (Builder)

The builder constructs the one-pass DFA from the Thompson NFA using a worklist algorithm:

1. **Initialize:** Create DEAD state. Compute `explicitSlotStart = patternCount * 2` (implicit slots are handled in the search loop, not encoded in transitions).

2. **Add start state:** Map the NFA's anchored start state to a new DFA state. Push onto the uncompiled worklist.

3. **For each uncompiled DFA state:** Run epsilon closure (DFS) from the mapped NFA state, accumulating look-around assertions and capture slot updates in an `Epsilons` value (a packed `long`):

   - **CharRange/Sparse:** Compile a transition for each equivalence class in the range. If the class already has a non-DEAD transition that differs → **not one-pass** (return `null`).
   - **Look:** Accumulate the look kind into the epsilons' look bitfield. Continue DFS.
   - **Union/BinaryUnion:** Push each alternative onto the DFS stack with the current epsilons. (If two alternatives can match the same char class, `compile_transition` detects the conflict.)
   - **Capture:** If the slot index is explicit (≥ `explicitSlotStart`), set the corresponding bit in the epsilons' slot bitfield. Continue DFS.
   - **Match:** Record the pattern ID and final epsilons in the state's pattern-epsilons slot. If a match was already recorded for this DFA state → **not one-pass** (return `null`).
   - **Fail:** Dead end, continue.

4. **Cycle detection:** A `SparseSet` tracks NFA states visited during each epsilon closure. If a state is visited twice → **not one-pass** (return `null`).

5. **Shuffle match states** to the end of the table, set `minMatchId`.

**Ref:** `upstream/regex/regex-automata/src/dfa/onepass.rs:581-728` (build), `758-798` (compile_transition), `903-923` (stack_push with cycle detection).

### Search Loop

The search loop processes one char per iteration, applying capture slot updates from the transition's epsilons:

```java
public int search(Input input, OnePassCache cache, int[] slots) {
    // Early return for zero-length input (matches upstream onepass.rs:2085-2087)
    if (input.start() >= input.end()) {
        return -1;
    }

    // Clear slots, set implicit start slots
    Arrays.fill(slots, -1);
    for (int pid = 0; pid < patternCount; pid++) {
        int i = pid * 2;
        if (i < slots.length) slots[i] = input.start();
    }
    // Three-way min matching upstream (onepass.rs:2104-2110):
    // cap at SLOT_LIMIT, caller's available slots, and cache capacity
    int availableSlots = Math.max(0, slots.length - explicitSlotStart);
    cache.clear(Math.min(SLOT_LIMIT, Math.min(availableSlots, cache.capacity())));

    char[] haystack = input.haystack();
    int sid = startState;
    int matchedPid = -1;

    for (int at = input.start(); at < input.end(); at++) {
        long trans = transition(sid, charClasses.classify(haystack[at]));
        int nextSid = stateId(trans);     // uses >>> (unsigned right shift) for extraction
        long epsilons = epsilons(trans);

        // Check for match BEFORE consuming this char's transition
        if (sid >= minMatchId) {
            int pid = findMatch(cache, input, at, sid, slots);
            if (pid >= 0) {
                matchedPid = pid;
                // Two independent early-exit conditions (upstream onepass.rs:2152-2158):
                // 1. earliest mode (return first match found, not used in Strategy.Core currently)
                // 2. leftmost-first with match_wins flag on the outgoing transition
                if (matchWins(trans)) return matchedPid;
            }
        }

        // Dead state and look assertion check — uses sid (CURRENT state, NOT nextSid).
        // This prevents applying slot updates from transitions out of DEAD.
        // Ref: upstream onepass.rs:2160 checks `sid == DEAD`, not `next_sid`.
        if (sid == DEAD
                || (!looksEmpty(epsilons)
                    && !looksSatisfied(epsilons, input, at))) {
            return matchedPid;
        }

        // Apply capture slot updates from transition epsilons
        applySlots(epsilons, at, cache.explicitSlots());
        sid = nextSid;
    }

    // Check final state for match
    if (sid >= minMatchId) {
        int pid = findMatch(cache, input, input.end(), sid, slots);
        if (pid >= 0) matchedPid = pid;
    }
    return matchedPid;  // -1 if no match
}
```

**Bit extraction: all uses of `>>>` (unsigned right shift).** Java's `long` is signed, so extracting the 21-bit state ID from bits 63–43 must use `>>>` to avoid sign extension. All transition field extractors (`stateId()`, `matchWins()`, `epsilons()`) must use `>>>`, never `>>`.

**Key points:**
- `input.start() >= input.end()` early return handles zero-length windows (upstream `onepass.rs:2085`)
- Dead-state check uses `sid` (current state), not `nextSid` — prevents slot writes from dead-end transitions (upstream `onepass.rs:2160`)
- Match detection uses `sid >= minMatchId` (O(1) comparison, no unpacking)
- `findMatch()` verifies look-around assertions on the match state's pattern-epsilons, sets the implicit end slot, copies explicit slots from cache to caller's `slots[]`, AND applies the match state's own epsilon slot updates at position `at` into the caller's `slots[]` (not into cache). This handles capture groups that close at the match state. Ref: upstream `onepass.rs:2239`
- Slot updates (`applySlots`) iterate the bitset in the transition's epsilons, writing `at` to each active slot in the cache
- `explicitSlotLen` computation uses `Math.max(0, ...)` to handle the case where caller's `slots[]` is shorter than `explicitSlotStart` (mirrors upstream's `saturating_sub`, `onepass.rs:2107`)
- Return value is the matched pattern ID (-1 for no match), slots are written in-place

**Ref:** `upstream/regex/regex-automata/src/dfa/onepass.rs:2042-2183` (search_imp), `2194-2243` (find_match).

### Integration with Strategy

The one-pass DFA is used as a **capture engine** in `Strategy.Core.dfaSearchCaptures()`, replacing PikeVM/backtracker on the narrowed window:

```
Forward DFA → find matchEnd
Reverse DFA → find matchStart
One-pass DFA (anchored, on [matchStart, matchEnd]) → extract all capture slots
```

**Decision logic in `Regex.compile()`:**
1. After building the NFA, attempt `OnePassDFA.build(nfa, charClasses)`.
2. If the pattern is one-pass (build returns non-null), store it in the `Strategy.Core`.
3. In `dfaSearchCaptures()`, prefer the one-pass DFA over PikeVM/backtracker when available.
4. Fall back to PikeVM/backtracker for patterns that aren't one-pass.

**When to build:** Only when the pattern has explicit capture groups (otherwise, captures aren't needed and the three-phase DFA search suffices). This matches upstream's heuristic at `wrappers.rs:376-381`.

**Cache integration:** Add `OnePassCache` to `Strategy.Cache`. Since `Cache` is a record, add it as a component (it's immutable in structure, mutable in content — same as `DFACache`).

### File Structure

| File | Purpose |
|---|---|
| `regex-automata/.../dfa/onepass/OnePassDFA.java` | DFA struct, search loop, transition decoding |
| `regex-automata/.../dfa/onepass/OnePassCache.java` | Per-search scratch space for explicit slots |
| `regex-automata/.../dfa/onepass/OnePassBuilder.java` | NFA → one-pass DFA compilation |
| `regex-automata/.../dfa/onepass/Epsilons.java` | 42-bit packed look assertions + slot bitset |
| `regex-automata/.../meta/Strategy.java` | Integration: prefer one-pass DFA for captures |
| `regex/src/.../Regex.java` | Build one-pass DFA during compilation |

### Allocation Budget

- **Compilation:** One `long[]` transition table + `int[]` start states. Size proportional to NFA state count × stride. Bounded by configurable size limit.
- **Per-search:** Zero allocation. `OnePassCache` is pre-allocated (max 32 ints = 128 bytes). Caller provides `int[] slots` (reusable via `Captures` pooling from stage-8).
- **Strategy.Cache:** Needs to grow from a record to include `OnePassCache`. Convert to a class, or add as a record component alongside existing caches.

### Testing Strategy

1. **Unit tests for OnePassBuilder:** Verify eligible/ineligible patterns. Test that `build()` returns non-null for simple capture patterns and null for ambiguous ones.
2. **Unit tests for OnePassDFA.search():** Test capture extraction on patterns like `(\\d+)-(\\d+)`, `([a-z]+)\\s([a-z]+)`, `(a|b)(c|d)`.
3. **Full upstream test suite:** All 879 upstream tests must continue to pass (captures extracted via one-pass DFA should match PikeVM results).
4. **Benchmark:** The `capturesOhai` benchmark (date pattern `(\\d{4})-(\\d{2})-(\\d{2})`) should show dramatic improvement from ~366 ops/s.

### Risks and Mitigations

- **Char-unit vs byte-unit:** Upstream uses byte equivalence classes. We use char equivalence classes (via `CharClasses`). The compilation algorithm is the same — `classify()` maps chars to class IDs, transitions are indexed by class ID. The main difference is the alphabet size, which affects stride and table size. For most patterns, merged equivalence classes keep the alphabet small (~2-55 classes).

- **Strategy.Cache is a record:** Adding `OnePassCache` requires either converting `Cache` to a class or adding it as a nullable record component. Converting to a class is cleanest since we already added `scratchCaptures` to `DFACache` for mutability.

- **Pattern eligibility coverage:** Many common patterns with captures ARE one-pass (date extraction, email parsing, log parsing). Patterns with ambiguous repetitions (`(.*)(.*)`) fall back to PikeVM — no regression, just no improvement.
