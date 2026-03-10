# UTF-16 Char-Unit NFA Migration

Migrate the NFA, Compiler, and PikeVM from byte-unit (UTF-8) operation to char-unit (UTF-16) operation, eliminating the per-search `CharSequence → byte[]` transcoding overhead.

## Motivation

Benchmarks show the UTF-8 transcoding dominates search cost — literal search on a ~900KB haystack is ~4700x slower than `java.util.regex`. Every search call encodes the entire `CharSequence` to a `byte[]` and builds a `byte → char` offset mapping array. Since Java strings are natively UTF-16, the engine should operate on `char` units directly.

## Design Decisions

**Char-unit, not codepoint-unit.** Transitions consume one `char` (0x0000–0xFFFF). BMP codepoints are single transitions. Supplementary codepoints are surrogate pair chains (high surrogate → low surrogate). This parallels the upstream Rust crate's byte-unit approach: small alphabet, simple PikeVM loop (`at++`), and future DFA-friendly (65K alphabet vs 1.1M for codepoint-unit).

**In-place migration, not abstraction.** The byte-oriented code is replaced, not wrapped in an abstraction layer. If a future bytes API is needed, a separate byte-oriented engine path can be built alongside the char-oriented one — the PikeVM algorithm is encoding-independent. The old UTF-8 code is preserved in git history.

**Dense state deferred.** `State.Dense` (256-entry byte transition table) is removed. It can be re-added as an optimization, particularly for the common ASCII case where a 128-entry table indexed by char value would be effective.

## NFA State Changes

| Before | After | Notes |
|---|---|---|
| `State.ByteRange(int start, int end, int next)` | `State.CharRange(int start, int end, int next)` | Range values are char units |
| `State.Sparse(Transition[])` | `State.Sparse(Transition[])` | `Transition.start/end` become char-unit; Javadoc updated |
| `State.Dense(int[])` | Removed | Re-add later as ASCII optimization |
| All epsilon states | Unchanged | Union, BinaryUnion, Capture, Look, Match, Fail |

`Transition.java` — field types (`int start, int end, int next`) are already wide enough for char values. Javadoc updated from "byte range" to "char range." The `& 0xFF` masking in PikeVM's `nexts()` method is removed for both `CharRange` and `Sparse` branches.

When `State.Dense` is removed from the sealed interface, its branches in `nexts()` and `epsilonClosureExplore()` are deleted (the compiler enforces exhaustive switches).

## HIR Changes

| Before | After | Notes |
|---|---|---|
| `Hir.Literal(byte[])` | `Hir.Literal(char[])` | Translator produces chars directly |
| `Hir.ClassB` | Removed | Byte-oriented class, not needed for char-unit |
| `ClassBytes` / `ClassBytesRange` | Removed | Same reason |
| `ClassUnicode` / `ClassUnicodeRange` | Unchanged | Already codepoint-based |

**Translator changes:** `translateLiteral` replaces `charToUtf8()` with `Character.toChars(cp)` — this returns a 1-element array for BMP codepoints and a 2-element surrogate pair for supplementary codepoints. The `Ast.Literal` node currently holds a `char`, which cannot represent supplementary codepoints directly; these arrive as escape sequences (e.g., `\u{1D6C3}`) parsed into codepoints. The Translator converts them to `char[]` via `Character.toChars()`.

**Compiler impact:** `Hir.ClassB` removal means `compileClassBytes` and its `compileNode` switch arm are deleted. `canMatchEmpty` updates from `l.bytes().length` to `l.chars().length`.

## Compiler Changes

**`compileLiteral`** — chains `CharRange` states, one per `char`. For BMP codepoints: one state. For supplementary codepoints: two states (high surrogate, low surrogate).

**`compileClass`** — replaces `Utf8Sequences` with `Utf16Sequences` (or inline logic). Splits codepoint ranges into:
- BMP ranges → single `CharRange` transitions
- Supplementary ranges → two-transition surrogate pair chains (high surrogate range → low surrogate range)

Much simpler than the UTF-8 case: at most a BMP/supplementary split vs dozens of byte sequences.

**`Utf8Sequences`** — removed, replaced by `Utf16Sequences`.

**Unanchored start state** — `ByteRange(0x00, 0xFF)` loop becomes `CharRange(0x0000, 0xFFFF)` loop.

## PikeVM Changes

**Search loop** — `haystack` changes from `byte[]` to `char[]`. Loop still advances by 1 unit. Byte reads `haystack[at] & 0xFF` become char reads `haystack[at]`.

**`nexts()`** — `ByteRange` matching becomes `CharRange` matching. Same comparison logic, wider values.

**Look-around assertions** — all manual UTF-8 decode logic (`decodeUtf8Fwd`, `decodeUtf8Rev`, `isValidUtf8BoundaryFwd/Rev`, `utf8Len`) replaced by `Character.codePointAt()` and `Character.codePointBefore()`. ~170 lines of UTF-8 handling collapse to ~30 lines.

**`skipSplitsFwd`** — filters empty matches that split surrogate pairs instead of UTF-8 codepoints. Check: `Character.isHighSurrogate(haystack[at])`. When advancing past a split, the step size must account for surrogate pairs: advance by 2 if the current position is a high surrogate, by 1 otherwise.

**ASCII assertions** — line-oriented assertions (`START_LINE`, `END_LINE`, CRLF variants) compare against `'\n'` and `'\r'`. These comparisons are value-equivalent between `byte` and `char` for ASCII values, so they require only a type change (`byte[]` → `char[]`), no semantic change.

**Cache / ActiveStates / SparseSet** — unchanged (encoding-independent).

## Input Changes

**Before:** Encodes `CharSequence` to `byte[]`, builds `int[] byteToChar` mapping, stores byte-unit bounds.

**After:** Holds `char[]` (from `String.toCharArray()` or direct `CharSequence` access) and char-unit bounds. No encoding, no offset mapping. `toCharOffset()` removed. `isCharBoundary()` becomes a surrogate pair check.

This eliminates the dominant per-search allocation and computation cost.

## Public API Impact

None. The `Regex` API surface (`compile`, `find`, `findAll`, `captures`, `isMatch`) is unchanged. Match offsets are already reported in char units to users — the internal byte-to-char conversion was hidden inside `Input`. Removing it is invisible to consumers.

## Test Strategy

**Upstream TOML suite** — match spans in the test data are byte offsets (the upstream Rust crate is byte-oriented). Both test harnesses (`PikeVMSuiteTest`, `UpstreamSuiteTest`) will convert expected byte-offset spans to char-offset spans before comparison. This conversion is added to the test helpers — encode the haystack to UTF-8, build a byte-to-char map, convert each expected span. `UpstreamSuiteTest` already does this; `PikeVMSuiteTest` needs the same logic added. `Input.withByteBounds` is removed; test cases with `bounds` convert byte bounds to char bounds using the same mapping, or skip if the bounds land mid-codepoint.

**Unit tests** — `CompilerTest`, `BuilderTest`, `PikeVMTest` updated to use `CharRange` and char values. Mechanical changes.

**HIR tests** — `Hir.Literal` assertions updated from `byte[]` to `char[]`.

**Benchmarks** — re-run after migration to measure improvement. This is the primary success metric.

**Test count** — should remain the same. No tests newly skipped or removed.

## Future Bytes API

When a byte-oriented API is needed (for `byte[]` input like log files, network buffers), it would be a **separate engine path**:
- Revive `Utf8Sequences` and the byte-oriented compiler from git history
- Add a `BytePikeVM` (or equivalent) operating on `byte[]`
- The char-unit PikeVM is untouched
- The meta engine selects the appropriate path based on input type

The PikeVM algorithm is encoding-independent — only the input type, transition semantics, and assertion logic differ.
