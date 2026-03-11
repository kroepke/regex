# Reverse Lazy DFA Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reverse lazy DFA that finds match start positions without PikeVM, enabling pure O(n) non-capturing search.

**Architecture:** A separate reverse NFA is compiled from the same HIR with concatenations/literals reversed. A second LazyDFA instance wraps this reverse NFA and provides `searchRev()`. Strategy.Core coordinates forward DFA (finds end) → reverse DFA (finds start) → PikeVM (only for captures).

**Tech Stack:** Java 21, sealed interfaces, records, Maven (`./mvnw`)

**Spec:** `docs/superpowers/specs/2026-03-11-reverse-dfa-design.md`

---

## File Map

| Action | File | Responsibility |
|--------|------|---------------|
| Modify | `regex-automata/.../nfa/thompson/Compiler.java` | Add `compileReverse()` with reversed concat/literal/class/capture/look handling |
| Modify | `regex-automata/.../nfa/thompson/NFA.java` | Add `boolean reverse` field |
| Modify | `regex-automata/.../dfa/lazy/SearchResult.java` | Rename `Match.end` → `Match.offset` |
| Modify | `regex-automata/.../dfa/lazy/LazyDFA.java` | Add `searchRev()` method |
| Modify | `regex-automata/.../meta/Strategy.java` | Three-phase search, rename `lazyDFA` → `forwardDFA`, add `reverseDFA` |
| Modify | `regex/src/.../Regex.java` | Wire reverse NFA compilation |
| Modify | `regex-automata/.../nfa/thompson/CompilerTest.java` | Reverse NFA compilation tests |
| Modify | `regex-automata/.../dfa/lazy/LazyDFATest.java` | Reverse search tests |
| Modify | `regex-automata/.../meta/StrategyTest.java` | Three-phase integration tests |
| Modify | `regex-automata/.../meta/StrategyLazyDFATest.java` | Update for new Core constructor |

Base path prefix: `/home/kroepke/projects/regex`

---

## Chunk 1: NFA Compiler Reverse Mode

### Task 1: Rename SearchResult.Match.end to offset

This is a prerequisite rename that touches many files. Do it first so later tasks use the new name.

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/SearchResult.java`
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java` (references to `.end()`)
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java` (references to `m.end()`)
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyLazyDFATest.java`

- [ ] **Step 1: Rename the field in SearchResult.java**

Change `record Match(int end)` to `record Match(int offset)`.

```java
public sealed interface SearchResult {
    record Match(int offset) implements SearchResult {}
    record NoMatch() implements SearchResult {}
    record GaveUp(int offset) implements SearchResult {}
}
```

- [ ] **Step 2: Update all references from .end() to .offset()**

In `LazyDFA.java`: no direct references to `Match.end()` (it returns `SearchResult`, callers destructure).

In `Strategy.java` lines 128-130, 139-141: `m.end()` → `m.offset()`.

In `LazyDFATest.java`: all `assertInstanceOf(SearchResult.Match.class, ...)` followed by `.end()` → `.offset()`.

In `StrategyLazyDFATest.java`: same pattern.

- [ ] **Step 3: Run tests to verify rename is clean**

Run: `./mvnw test -pl regex-automata -q`
Expected: All tests pass (pure rename, no behavior change).

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "refactor: rename SearchResult.Match.end to offset for direction-neutral DFA search"
```

---

### Task 2: Add reverse flag to NFA

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/NFA.java`
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Compiler.java` (pass `false` to NFA constructor)

- [ ] **Step 1: Add reverse field to NFA**

Add `boolean reverse` as the last constructor parameter and field. Add accessor `public boolean isReverse()`.

In `NFA.java`, add field:
```java
private final boolean reverse;
```

Update constructor signature to accept `boolean reverse` as last param. Add:
```java
this.reverse = reverse;
```

Add accessor:
```java
public boolean isReverse() {
    return reverse;
}
```

- [ ] **Step 2: Update Builder to pass reverse flag**

The `Builder.build()` method constructs the NFA. It needs to accept and forward the `reverse` flag. Add a `boolean reverse` field to `Builder` with setter `Builder.reverse(boolean)`, default `false`. Pass it to the `NFA` constructor in `build()`.

Check `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Builder.java` for the `build()` method and update accordingly.

- [ ] **Step 3: Update Compiler to set reverse=false on builder**

In `Compiler.compileInternal()`, no change needed if Builder defaults to `false`. The existing `Compiler.compile()` path should produce `NFA.isReverse() == false`.

- [ ] **Step 4: Run tests**

Run: `./mvnw test -pl regex-automata -q`
Expected: All tests pass (existing code always creates forward NFAs).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: add reverse flag to NFA and Builder"
```

---

### Task 3: Add Compiler.compileReverse() with concat reversal

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/nfa/thompson/Compiler.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/nfa/thompson/CompilerTest.java`

- [ ] **Step 1: Write failing test for reverse concat ordering**

In `CompilerTest.java`, add a test that compiles a pattern in reverse mode and verifies the NFA structure represents reversed concatenation. The simplest way: compile `ab` in reverse, then run a PikeVM search backwards to verify it matches `ba` when fed in reverse order. But simpler: just verify the NFA is marked reverse and has valid states.

```java
@Test
void reverseCompilationProducesReverseNFA() {
    Hir hir = parse("ab");
    NFA nfa = Compiler.compileReverse(hir);
    assertTrue(nfa.isReverse());
    assertEquals(0, nfa.captureSlotCount());
    assertEquals(0, nfa.groupCount());
}
```

Add a helper `parse(String)` if not already present — it should parse the pattern string to HIR. Also add these imports to `CompilerTest.java`:

```java
import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Input;
```

```java
private static Hir parse(String pattern) {
    Ast ast = Parser.parse(pattern, 250);
    return Translator.translate(pattern, ast);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl regex-automata -Dtest="CompilerTest#reverseCompilationProducesReverseNFA" -q`
Expected: FAIL — `Compiler.compileReverse` does not exist.

- [ ] **Step 3: Implement compileReverse and reverse concat/capture/literal**

Add to `Compiler.java`:

```java
/**
 * Compiles the given HIR into a reverse NFA.
 * Concatenations and literals are reversed. Capture group slots are omitted.
 */
public static NFA compileReverse(Hir hir) throws BuildError {
    Compiler c = new Compiler();
    c.reverse = true;
    return c.compileInternal(hir);
}
```

Add instance field:
```java
private boolean reverse = false;
```

Modify `compileInternal()`:
- Set `builder.reverse(reverse)` before building
- When `reverse == true`, skip the group-0 capture wrapper. Replace the capture-start/body/capture-end/match chain with just body → match:

Add `import java.util.Collections;` to the imports in `Compiler.java`.

```java
if (reverse) {
    // Reverse NFA: no capture slots
    builder.setGroupInfo(0, 0, Collections.emptyList());
    ThompsonRef body = compileNode(hir);
    int match = builder.add(new State.Match(0));
    builder.patch(body.end(), match);
    builder.setStartAnchored(body.start());
    // Unanchored start: same skip loop as forward
    int skipState = builder.add(new State.CharRange(0x0000, 0xFFFF, 0));
    int startUnanchored = builder.add(new State.BinaryUnion(body.start(), skipState));
    builder.patch(skipState, startUnanchored);
    builder.setStartUnanchored(startUnanchored);
    return builder.build();
}
```

Modify `compileConcat()` — when `reverse`, iterate children in reverse:
```java
private ThompsonRef compileConcat(Hir.Concat concat) throws BuildError {
    List<Hir> subs = concat.subs();
    if (subs.isEmpty()) {
        return compileEmpty();
    }
    if (reverse) {
        ThompsonRef result = compileNode(subs.getLast());
        for (int i = subs.size() - 2; i >= 0; i--) {
            ThompsonRef next = compileNode(subs.get(i));
            builder.patch(result.end(), next.start());
            result = new ThompsonRef(result.start(), next.end());
        }
        return result;
    }
    // existing forward code
    ThompsonRef result = compileNode(subs.getFirst());
    for (int i = 1; i < subs.size(); i++) {
        ThompsonRef next = compileNode(subs.get(i));
        builder.patch(result.end(), next.start());
        result = new ThompsonRef(result.start(), next.end());
    }
    return result;
}
```

Modify `compileLiteral()` — when `reverse`, reverse the char array:
```java
private ThompsonRef compileLiteral(Hir.Literal lit) {
    char[] chars = lit.chars();
    if (chars.length == 0) {
        return compileEmpty();
    }
    if (reverse) {
        // Reverse the char sequence for backwards matching
        int first = builder.add(new State.CharRange(chars[chars.length - 1], chars[chars.length - 1], 0));
        int prev = first;
        for (int i = chars.length - 2; i >= 0; i--) {
            int cur = builder.add(new State.CharRange(chars[i], chars[i], 0));
            builder.patch(prev, cur);
            prev = cur;
        }
        return new ThompsonRef(first, prev);
    }
    // existing forward code
    int first = builder.add(new State.CharRange(chars[0], chars[0], 0));
    int prev = first;
    for (int i = 1; i < chars.length; i++) {
        int cur = builder.add(new State.CharRange(chars[i], chars[i], 0));
        builder.patch(prev, cur);
        prev = cur;
    }
    return new ThompsonRef(first, prev);
}
```

Modify `compileCapture()` — when `reverse`, skip capture slots, just compile the inner expression:
```java
private ThompsonRef compileCapture(Hir.Capture cap) throws BuildError {
    if (reverse) {
        return compileNode(cap.sub());
    }
    // existing forward code
    int groupIndex = cap.index();
    // ... rest unchanged
}
```

Modify `compileCharSequence()` — when `reverse`, reverse the sequence of char ranges within each UTF-16 encoding (for surrogate pairs):
```java
private ThompsonRef compileCharSequence(int[][] seq) {
    if (reverse) {
        int first = builder.add(new State.CharRange(seq[seq.length - 1][0], seq[seq.length - 1][1], 0));
        int prev = first;
        for (int i = seq.length - 2; i >= 0; i--) {
            int cur = builder.add(new State.CharRange(seq[i][0], seq[i][1], 0));
            builder.patch(prev, cur);
            prev = cur;
        }
        return new ThompsonRef(first, prev);
    }
    // existing forward code
    int first = builder.add(new State.CharRange(seq[0][0], seq[0][1], 0));
    int prev = first;
    for (int i = 1; i < seq.length; i++) {
        int cur = builder.add(new State.CharRange(seq[i][0], seq[i][1], 0));
        builder.patch(prev, cur);
        prev = cur;
    }
    return new ThompsonRef(first, prev);
}
```

Modify `compileLook()` — when `reverse`, flip assertion direction:
```java
private ThompsonRef compileLook(Hir.Look look) {
    LookKind kind = reverse ? flipLookKind(look.look()) : look.look();
    int id = builder.add(new State.Look(kind, 0));
    return new ThompsonRef(id, id);
}

private static LookKind flipLookKind(LookKind kind) {
    return switch (kind) {
        case START_LINE -> LookKind.END_LINE;
        case END_LINE -> LookKind.START_LINE;
        case START_LINE_CRLF -> LookKind.END_LINE_CRLF;
        case END_LINE_CRLF -> LookKind.START_LINE_CRLF;
        case START_TEXT -> LookKind.END_TEXT;
        case END_TEXT -> LookKind.START_TEXT;
        case WORD_START_ASCII -> LookKind.WORD_END_ASCII;
        case WORD_END_ASCII -> LookKind.WORD_START_ASCII;
        case WORD_START_HALF_ASCII -> LookKind.WORD_END_HALF_ASCII;
        case WORD_END_HALF_ASCII -> LookKind.WORD_START_HALF_ASCII;
        case WORD_START_UNICODE -> LookKind.WORD_END_UNICODE;
        case WORD_END_UNICODE -> LookKind.WORD_START_UNICODE;
        case WORD_START_HALF_UNICODE -> LookKind.WORD_END_HALF_UNICODE;
        case WORD_END_HALF_UNICODE -> LookKind.WORD_START_HALF_UNICODE;
        // Symmetric — no change
        case WORD_BOUNDARY_UNICODE -> LookKind.WORD_BOUNDARY_UNICODE;
        case WORD_BOUNDARY_UNICODE_NEGATE -> LookKind.WORD_BOUNDARY_UNICODE_NEGATE;
        case WORD_BOUNDARY_ASCII -> LookKind.WORD_BOUNDARY_ASCII;
        case WORD_BOUNDARY_ASCII_NEGATE -> LookKind.WORD_BOUNDARY_ASCII_NEGATE;
    };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -pl regex-automata -Dtest="CompilerTest#reverseCompilationProducesReverseNFA" -q`
Expected: PASS

- [ ] **Step 5: Write additional reverse compiler tests**

Add to `CompilerTest.java`:

```java
@Test
void reverseNFAMatchesReversedLiteral() {
    // "abc" reversed should match "cba" when run through PikeVM
    Hir hir = parse("abc");
    NFA nfa = Compiler.compileReverse(hir);
    PikeVM vm = new PikeVM(nfa);
    var cache = vm.createCache();

    // Reverse NFA expects chars in reverse order
    Input input = Input.of("cba");
    assertTrue(vm.isMatch(input, cache));

    // Forward order should NOT match
    Input fwd = Input.of("abc");
    assertFalse(vm.isMatch(fwd, cache));
}

@Test
void reverseNFACaptureGroupsOmitted() {
    Hir hir = parse("(a)(b)");
    NFA nfa = Compiler.compileReverse(hir);
    assertEquals(0, nfa.captureSlotCount());
    assertEquals(0, nfa.groupCount());
    assertTrue(nfa.isReverse());
}

@Test
void reverseNFACharClassMatchesBackwards() {
    // [a-c]d reversed: NFA expects d then [a-c]
    Hir hir = parse("[a-c]d");
    NFA nfa = Compiler.compileReverse(hir);
    PikeVM vm = new PikeVM(nfa);
    var cache = vm.createCache();

    Input input = Input.of("da");
    assertTrue(vm.isMatch(input, cache));

    Input wrong = Input.of("ad");
    assertFalse(vm.isMatch(wrong, cache));
}

@Test
void reverseNFAAlternation() {
    Hir hir = parse("ab|cd");
    NFA nfa = Compiler.compileReverse(hir);
    PikeVM vm = new PikeVM(nfa);
    var cache = vm.createCache();

    assertTrue(vm.isMatch(Input.of("ba"), cache));
    assertTrue(vm.isMatch(Input.of("dc"), cache));
    assertFalse(vm.isMatch(Input.of("ab"), cache));
}

@Test
void reverseNFARepetition() {
    Hir hir = parse("a+b");
    NFA nfa = Compiler.compileReverse(hir);
    PikeVM vm = new PikeVM(nfa);
    var cache = vm.createCache();

    // Reversed: b then a+
    assertTrue(vm.isMatch(Input.of("ba"), cache));
    assertTrue(vm.isMatch(Input.of("baaa"), cache));
    assertFalse(vm.isMatch(Input.of("ab"), cache));
}
```

- [ ] **Step 6: Run all tests**

Run: `./mvnw test -pl regex-automata -q`
Expected: All pass.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: add Compiler.compileReverse() with reversed concat, literal, class, capture, and look handling"
```

---

## Chunk 2: Reverse Search in LazyDFA

### Task 4: Add searchRev() to LazyDFA

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java`

- [ ] **Step 1: Write failing test for reverse search**

Add to `LazyDFATest.java`:

```java
@Test
void reverseSearchFindsMatchStart() {
    // Pattern "abc" in "xxabcxx" — forward DFA finds end=5, reverse DFA should find start=2
    // Reverse DFA uses a reverse NFA, so we search backwards from the end position
    SearchResult result = searchReverse("abc", "xxabcxx", 0, 5);
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(2, ((SearchResult.Match) result).offset());
}

@Test
void reverseSearchSingleChar() {
    SearchResult result = searchReverse("a", "xxax", 0, 3);
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(2, ((SearchResult.Match) result).offset());
}

@Test
void reverseSearchNoMatch() {
    SearchResult result = searchReverse("abc", "xxdefxx", 0, 7);
    assertInstanceOf(SearchResult.NoMatch.class, result);
}

@Test
void reverseSearchCharClass() {
    SearchResult result = searchReverse("[a-z]+", "xx hello xx", 0, 8);
    assertInstanceOf(SearchResult.Match.class, result);
    // "hello" starts at 3, reverse DFA anchored at end=8 finds start=3
    assertEquals(3, ((SearchResult.Match) result).offset());
}

@Test
void reverseSearchAtBoundary() {
    // Match at very start of input
    SearchResult result = searchReverse("abc", "abcxx", 0, 3);
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(0, ((SearchResult.Match) result).offset());
}
```

Add the `searchReverse` helper:
```java
private SearchResult searchReverse(String pattern, String haystack, int start, int end) {
    Hir hir = parseHir(pattern);
    NFA reverseNfa = Compiler.compileReverse(hir);
    CharClasses charClasses = CharClassBuilder.build(reverseNfa);
    LazyDFA dfa = LazyDFA.create(reverseNfa, charClasses);
    assertNotNull(dfa);
    DFACache cache = dfa.createCache();
    Input input = Input.of(haystack).withBounds(start, end, true);
    return dfa.searchRev(input, cache);
}
```

Note: The reverse search input is anchored (`true`) because it starts matching at the end boundary and walks left.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -pl regex-automata -Dtest="LazyDFATest#reverseSearchFindsMatchStart" -q`
Expected: FAIL — `searchRev` does not exist.

- [ ] **Step 3: Implement searchRev()**

Add to `LazyDFA.java`:

```java
/**
 * Reverse search for the start position of the leftmost-first match.
 *
 * <p>Searches backwards from {@code input.end() - 1} to {@code input.start()}.
 * Uses the same 1-char match delay as forward search. When a match-flagged
 * state is entered at position {@code pos}, the match start is {@code pos + 1}
 * (the char that triggered the delayed match was at {@code pos}, and the
 * match extends from {@code pos + 1} onward in forward coordinates).</p>
 *
 * @param input the search input (haystack + bounds + anchored flag).
 *              Typically anchored at the forward match's end position.
 * @param cache per-search mutable state
 * @return Match(startPos), NoMatch, or GaveUp(offset)
 */
public SearchResult searchRev(Input input, DFACache cache) {
    char[] haystack = input.haystack();
    int start = input.start();
    int end = input.end();
    int stride = charClasses.stride();
    int dead = DFACache.dead(stride);
    int quit = DFACache.quit(stride);

    int sid = getOrComputeStartState(input, cache);
    if (sid == dead) return new SearchResult.NoMatch();
    if (sid == quit) return new SearchResult.GaveUp(end);

    int lastMatchStart = -1;

    int pos = end - 1;
    while (pos >= start) {
        int classId = charClasses.classify(haystack[pos]);
        int nextSid = cache.nextState(sid, classId);

        if (nextSid > quit) {
            // Fast path: normal cached transition
            sid = nextSid;
            pos--;
            cache.charsSearched++;
            continue;
        }

        if (nextSid < 0) {
            // Match state (high bit set). With 1-char delay in reverse,
            // the match start is pos + 1.
            lastMatchStart = pos + 1;
            sid = nextSid & 0x7FFF_FFFF;
            pos--;
            cache.charsSearched++;
            continue;
        }

        // Slow path: UNKNOWN, DEAD, or QUIT
        if (nextSid == DFACache.UNKNOWN) {
            nextSid = computeNextState(cache, sid, classId);
            if (nextSid == quit) return new SearchResult.GaveUp(pos);
            cache.setTransition(sid, classId, nextSid);
            sid = nextSid;
            if (sid < 0) {
                lastMatchStart = pos + 1;
                sid = sid & 0x7FFF_FFFF;
            }
            pos--;
            cache.charsSearched++;
            continue;
        }
        if (nextSid == dead) {
            break;
        }
        // nextSid == quit
        return new SearchResult.GaveUp(pos);
    }

    // EOI: check for delayed match at left boundary
    int rawSid = sid & 0x7FFF_FFFF;
    if (rawSid != dead && rawSid != quit) {
        StateContent currentContent = cache.getState(rawSid);
        if (currentContent.isMatch()) {
            lastMatchStart = start;
        }
    }

    if (lastMatchStart >= 0) return new SearchResult.Match(lastMatchStart);
    return new SearchResult.NoMatch();
}
```

- [ ] **Step 4: Run reverse search tests**

Run: `./mvnw test -pl regex-automata -Dtest="LazyDFATest#reverseSearch*" -q`
Expected: All pass.

- [ ] **Step 5: Run all tests to check for regressions**

Run: `./mvnw test -pl regex-automata -q`
Expected: All pass.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add LazyDFA.searchRev() for reverse match start finding"
```

---

## Chunk 3: Strategy Integration and Wiring

### Task 5: Update Strategy.Core for three-phase search

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyTest.java`
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/meta/StrategyLazyDFATest.java`

- [ ] **Step 1: Write failing test for three-phase search**

First, add these imports and a helper to `StrategyTest.java`:

```java
import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
```

```java
private static Hir parseHir(String pattern) {
    Ast ast = Parser.parse(pattern, 250);
    return Translator.translate(pattern, ast);
}
```

Add to `StrategyTest.java`:

```java
@Test
void threePhaseSearchFindsCorrectStartAndEnd() {
    // Build a Core strategy with both forward and reverse DFA
    Hir hir = parseHir("[a-z]+");
    NFA fwdNfa = Compiler.compile(hir);
    NFA revNfa = Compiler.compileReverse(hir);
    CharClasses fwdClasses = CharClassBuilder.build(fwdNfa);
    CharClasses revClasses = CharClassBuilder.build(revNfa);
    PikeVM pikeVM = new PikeVM(fwdNfa);
    LazyDFA forwardDFA = LazyDFA.create(fwdNfa, fwdClasses);
    LazyDFA reverseDFA = LazyDFA.create(revNfa, revClasses);

    Strategy.Core strategy = new Strategy.Core(pikeVM, forwardDFA, reverseDFA, null);
    Strategy.Cache cache = strategy.createCache();

    Input input = Input.of("123 hello 456");
    Captures caps = strategy.search(input, cache);
    assertNotNull(caps);
    assertEquals(4, caps.start(0));  // "hello" starts at 4
    assertEquals(9, caps.end(0));    // "hello" ends at 9
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -pl regex-automata -Dtest="StrategyTest#threePhaseSearchFindsCorrectStartAndEnd" -q`
Expected: FAIL — `Core` constructor doesn't accept 4 args yet.

- [ ] **Step 3: Update Strategy.Core**

Change the `Core` record to:

```java
record Core(PikeVM pikeVM, LazyDFA forwardDFA, LazyDFA reverseDFA, Prefilter prefilter) implements Strategy {
```

Update `createCache()`:
```java
@Override
public Cache createCache() {
    return new Cache(
            pikeVM.createCache(),
            forwardDFA != null ? forwardDFA.createCache() : null,
            reverseDFA != null ? reverseDFA.createCache() : null
    );
}
```

Update `Cache` record:
```java
record Cache(
        lol.ohai.regex.automata.nfa.thompson.pikevm.Cache pikeVMCache,
        DFACache forwardDFACache,
        DFACache reverseDFACache
) {
    static final Cache EMPTY = new Cache(null, null, null);
}
```

Update all internal references from `lazyDFA` to `forwardDFA` and from `cache.lazyDFACache()` to `cache.forwardDFACache()`.

Replace `dfaTwoPhaseSearch()` with three-phase logic:

```java
private Captures dfaTwoPhaseSearch(Input input, Cache cache) {
    SearchResult fwdResult = forwardDFA.searchFwd(input, cache.forwardDFACache());
    return switch (fwdResult) {
        case SearchResult.Match m -> {
            int matchEnd = m.offset();
            // Try reverse DFA to find start
            if (reverseDFA != null) {
                Input revInput = input.withBounds(input.start(), matchEnd, true);
                SearchResult revResult = reverseDFA.searchRev(revInput, cache.reverseDFACache());
                yield switch (revResult) {
                    case SearchResult.Match rm -> {
                        Captures caps = new Captures(pikeVM.nfa().groupCount());
                        caps.set(0, rm.offset());
                        caps.set(1, matchEnd);
                        yield caps;
                    }
                    case SearchResult.NoMatch n -> {
                        // Reverse DFA found no match — shouldn't happen, fall back
                        Input narrowed = input.withBounds(input.start(), matchEnd, input.isAnchored());
                        yield pikeVM.search(narrowed, cache.pikeVMCache());
                    }
                    case SearchResult.GaveUp g -> {
                        Input narrowed = input.withBounds(input.start(), matchEnd, input.isAnchored());
                        yield pikeVM.search(narrowed, cache.pikeVMCache());
                    }
                };
            }
            // No reverse DFA — fall back to PikeVM
            Input narrowed = input.withBounds(input.start(), matchEnd, input.isAnchored());
            yield pikeVM.search(narrowed, cache.pikeVMCache());
        }
        case SearchResult.NoMatch n -> null;
        case SearchResult.GaveUp g -> pikeVM.search(input, cache.pikeVMCache());
    };
}
```

Replace `dfaTwoPhaseSearchCaptures()` with enhanced version that narrows PikeVM window:

```java
private Captures dfaTwoPhaseSearchCaptures(Input input, Cache cache) {
    SearchResult fwdResult = forwardDFA.searchFwd(input, cache.forwardDFACache());
    return switch (fwdResult) {
        case SearchResult.Match m -> {
            int matchEnd = m.offset();
            // Try reverse DFA to narrow window for PikeVM
            if (reverseDFA != null) {
                Input revInput = input.withBounds(input.start(), matchEnd, true);
                SearchResult revResult = reverseDFA.searchRev(revInput, cache.reverseDFACache());
                if (revResult instanceof SearchResult.Match rm) {
                    // Narrow PikeVM to [matchStart, matchEnd]
                    Input narrowed = input.withBounds(rm.offset(), matchEnd, true);
                    yield pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
                }
            }
            // Fall back: PikeVM on [input.start(), matchEnd]
            Input narrowed = input.withBounds(input.start(), matchEnd, input.isAnchored());
            yield pikeVM.searchCaptures(narrowed, cache.pikeVMCache());
        }
        case SearchResult.NoMatch n -> null;
        case SearchResult.GaveUp g -> pikeVM.searchCaptures(input, cache.pikeVMCache());
    };
}
```

- [ ] **Step 4: Fix all existing call sites that construct Core**

In `StrategyLazyDFATest.java` and `StrategyTest.java`, update all `new Strategy.Core(pikeVM, lazyDFA, prefilter)` to `new Strategy.Core(pikeVM, lazyDFA, null, prefilter)` (passing `null` for `reverseDFA` to preserve existing behavior).

Search for all `new Strategy.Core(` across the codebase and update each one.

Also update `Regex.java` line 95 temporarily: `new Strategy.Core(pikeVM, lazyDFA, null, prefilter)` — this will be properly wired in Task 6.

- [ ] **Step 5: Run tests**

Run: `./mvnw test -q`
Expected: All tests pass, including the new three-phase test.

- [ ] **Step 6: Write additional three-phase tests**

Add to `StrategyTest.java`:

```java
@Test
void threePhaseSearchWithPrefilter() {
    Hir hir = parseHir("hello+");
    NFA fwdNfa = Compiler.compile(hir);
    NFA revNfa = Compiler.compileReverse(hir);
    CharClasses fwdClasses = CharClassBuilder.build(fwdNfa);
    CharClasses revClasses = CharClassBuilder.build(revNfa);
    PikeVM pikeVM = new PikeVM(fwdNfa);
    LazyDFA forwardDFA = LazyDFA.create(fwdNfa, fwdClasses);
    LazyDFA reverseDFA = LazyDFA.create(revNfa, revClasses);
    Prefilter prefilter = new SingleLiteral("hello".toCharArray());

    Strategy.Core strategy = new Strategy.Core(pikeVM, forwardDFA, reverseDFA, prefilter);
    Strategy.Cache cache = strategy.createCache();

    Input input = Input.of("xxx hellooo yyy");
    Captures caps = strategy.search(input, cache);
    assertNotNull(caps);
    assertEquals(4, caps.start(0));
    assertEquals(11, caps.end(0));
}

@Test
void threePhaseSearchFallsBackWhenReverseDFANull() {
    // When reverseDFA is null, should fall back to PikeVM (existing behavior)
    Hir hir = parseHir("[a-z]+");
    NFA fwdNfa = Compiler.compile(hir);
    CharClasses fwdClasses = CharClassBuilder.build(fwdNfa);
    PikeVM pikeVM = new PikeVM(fwdNfa);
    LazyDFA forwardDFA = LazyDFA.create(fwdNfa, fwdClasses);

    Strategy.Core strategy = new Strategy.Core(pikeVM, forwardDFA, null, null);
    Strategy.Cache cache = strategy.createCache();

    Input input = Input.of("123 hello 456");
    Captures caps = strategy.search(input, cache);
    assertNotNull(caps);
    assertEquals(4, caps.start(0));
    assertEquals(9, caps.end(0));
}

@Test
void threePhaseSearchCapturesNarrowsWindow() {
    Hir hir = parseHir("(\\d+)-(\\d+)");
    NFA fwdNfa = Compiler.compile(hir);
    NFA revNfa = Compiler.compileReverse(hir);
    CharClasses fwdClasses = CharClassBuilder.build(fwdNfa);
    CharClasses revClasses = CharClassBuilder.build(revNfa);
    PikeVM pikeVM = new PikeVM(fwdNfa);
    LazyDFA forwardDFA = LazyDFA.create(fwdNfa, fwdClasses);
    LazyDFA reverseDFA = LazyDFA.create(revNfa, revClasses);

    Strategy.Core strategy = new Strategy.Core(pikeVM, forwardDFA, reverseDFA, null);
    Strategy.Cache cache = strategy.createCache();

    Input input = Input.of("xxx 123-456 yyy");
    Captures caps = strategy.searchCaptures(input, cache);
    assertNotNull(caps);
    assertEquals(4, caps.start(0));   // "123-456" starts at 4
    assertEquals(11, caps.end(0));    // ends at 11
    assertEquals(4, caps.start(1));   // group 1: "123"
    assertEquals(7, caps.end(1));
    assertEquals(8, caps.start(2));   // group 2: "456"
    assertEquals(11, caps.end(2));
}
```

- [ ] **Step 7: Run all tests**

Run: `./mvnw test -q`
Expected: All pass.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: add three-phase search to Strategy.Core using reverse lazy DFA"
```

---

### Task 6: Wire reverse DFA compilation in Regex.java

**Files:**
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`
- Modify: `regex/src/test/java/lol/ohai/regex/MetaEngineTest.java`

- [ ] **Step 1: Write failing test**

Add to `MetaEngineTest.java`:

```java
@Test
void reverseDFAFindsCorrectMatchSpan() {
    // This pattern uses char class — reverse DFA should help find start
    Regex re = Regex.compile("[a-z]+");
    Optional<Match> match = re.find("123 hello 456");
    assertTrue(match.isPresent());
    assertEquals(4, match.get().start());
    assertEquals(9, match.get().end());
    assertEquals("hello", match.get().value());
}
```

This test should already pass (PikeVM fallback finds correct results), but it validates the path. The real validation is that the wiring doesn't break anything.

- [ ] **Step 2: Wire reverse NFA compilation in Regex.create()**

Update `Regex.java` lines 91-95:

```java
NFA nfa = Compiler.compile(hir);
CharClasses charClasses = CharClassBuilder.build(nfa);
PikeVM pikeVM = new PikeVM(nfa);
LazyDFA forwardDFA = LazyDFA.create(nfa, charClasses);

// Build reverse DFA for start-position finding
NFA reverseNfa = Compiler.compileReverse(hir);
CharClasses reverseCharClasses = CharClassBuilder.build(reverseNfa);
LazyDFA reverseDFA = LazyDFA.create(reverseNfa, reverseCharClasses);

strategy = new Strategy.Core(pikeVM, forwardDFA, reverseDFA, prefilter);
```

- [ ] **Step 3: Run full test suite**

Run: `./mvnw test -q`
Expected: All 839+ upstream tests pass plus all unit tests.

- [ ] **Step 4: If any tests fail, debug and fix**

The most likely issue: off-by-one in reverse match position. If upstream suite tests fail, compare expected vs actual start positions. The reverse DFA reports inclusive start via `pos + 1` in the match-delay logic.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: wire reverse lazy DFA into Regex compilation pipeline"
```

---

### Task 7: Update benchmarks and gap documentation

**Files:**
- Modify: `docs/architecture/lazy-dfa-gaps.md`
- Modify: `BENCHMARKS.md`

- [ ] **Step 1: Update lazy-dfa-gaps.md**

Mark the "Reverse DFA" section as completed. Update the text to note it was implemented and reference the commit.

- [ ] **Step 2: Run benchmarks**

Run: `./mvnw package -pl regex-bench -q -DskipTests && java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5`

Record results, particularly for:
- `[a-zA-Z]+` pattern (was 26x slower, should improve dramatically)
- `\w+` pattern (still has look-assertion bail-out, no improvement expected)
- Pathological patterns (should not regress)

- [ ] **Step 3: Update BENCHMARKS.md with new results**

Add a section noting the reverse DFA improvement and updated numbers.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "docs: update benchmark results and gap tracking after reverse DFA"
```
