# DFA Match Semantics: LeftmostFirst (Resolved) + Char Class Overflow

**Status: RESOLVED (match semantics) / OPEN (char class overflow)**

## Match Semantics — Resolved (2026-03-14)

Our lazy DFA **implements leftmost-first** match semantics, matching upstream exactly:

- **`computeNextState` break-on-Match** (`LazyDFA.java:439`): when iterating source NFA states and a Match state is encountered, break immediately — no further char-consuming states from lower-priority alternatives are processed. This matches upstream's `determinize/mod.rs:284`.
- **NFA state ordering**: the Thompson compiler places first-alternative states before second-alternative states (via `BinaryUnion.alt1` explored first in DFS). Lazy quantifiers use reversed union ordering (exit before continue). This matches upstream's `compiler.rs:1106-1130`.
- **1-char match delay**: match states are delayed by one character, matching upstream's `search.rs:265-270`. Start states are never match states (`computeStartState` strips MATCH_FLAG).

Three-phase DFA-only search is active:
- `Core.dfaSearch()`: forward DFA → reverse DFA → return `[matchStart, matchEnd]` directly. Matches upstream `dfa/regex.rs:474-534`.
- `Core.dfaSearchCaptures()`: forward DFA → reverse DFA → capture engine on narrowed `[matchStart, matchEnd]`. Matches upstream `meta/strategy.rs:829-857`.
- Fallback to PikeVM only when DFA gives up (quit chars, cache exhaustion).

All 879/879 upstream TOML tests pass with DFA-only three-phase search.

### Bugs fixed to enable three-phase (2026-03-14)

| Bug | Root cause | Fix | Upstream ref |
|---|---|---|---|
| Edge transitions | DFA used EOI sentinel at span boundaries instead of actual char | Use `haystack[end]`/`haystack[start-1]` at span edges | `search.rs:693-758` (eoi_fwd/eoi_rev) |
| Cached dead path | `sid` not set to dead on cached dead transition | Set `sid=dead` before break | `search.rs:277-279` |
| Reverse start state | Reverse DFA used `Start.from(haystack, start)` instead of `Start.fromReverse(haystack, end)` | Added `Start.fromReverse` for reverse look-behind context | `start.rs:155-158` (from_input_reverse) |
| Char class overflow | Unicode `\w` produces >256 equiv classes, overflowing byte class IDs | Auto-quit-on-non-ASCII when classes exceed 256 | `alphabet.rs` (byte-based limit) |

### Previous misdiagnosis

Commit `6789c01` removed three-phase search based on the diagnosis that "the DFA uses leftmost-longest semantics." This was incorrect — the DFA was always leftmost-first. The 27 test failures that commit fixed were actually caused by:
1. Surrogate pair handling in start state computation (fixed in the same commit's LazyDFA.java change)
2. Edge transition context bugs (not using actual char at span boundaries)
3. Reverse DFA start state using wrong look-behind position

## Char Class Overflow — Open

**What:** Byte-based class IDs (`byte` in Java, 0-255) limit the DFA to 256 equivalence classes. Unicode character classes like `\w` produce ~1,400 boundary points, exceeding this limit.

**Current mitigation:** `CharClassBuilder.build()` detects overflow and automatically retries with `quitNonAscii=true`, collapsing all non-ASCII characters into quit classes. The DFA handles ASCII portions and gives up on non-ASCII, falling back to PikeVM.

**Impact:** For the `unicodeWord` benchmark (`\w+` on mixed ASCII/Unicode text), the DFA quits on every non-ASCII character, resulting in ~18 ops/s instead of the potential ~15,000+ ops/s with a correct DFA.

**Why upstream doesn't have this:** Upstream operates on UTF-8 bytes (0-255 alphabet). The `ByteClasses` type naturally limits to 257 classes (256 bytes + EOI). Our char-unit DFA has a 65,536-value alphabet.

**Fix:** Widen class IDs from `byte` to `short` (16-bit), allowing up to 65,536 classes. Requires changing `CharClasses` lookup tables and `DFACache` transition table layout. Medium complexity.
