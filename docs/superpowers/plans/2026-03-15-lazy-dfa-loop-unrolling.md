# Lazy DFA Loop Unrolling Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve lazy DFA search throughput by unrolling the main transition loop 4× in both forward and reverse search, reducing per-char branch overhead.

**Architecture:** Add an inner unrolled loop (4 transitions per iteration) inside the existing outer dispatch loop in `LazyDFA.searchFwd()` and `searchRev()`. The inner loop handles only cached, non-special transitions. On break-out (match, UNKNOWN, dead, quit), control returns to the outer loop which re-classifies the triggering char and dispatches via the existing slow path. No semantic changes — pure optimization.

**Tech Stack:** Java 21, JMH (benchmarks), JUnit 5 (tests)

**Spec:** `docs/superpowers/specs/2026-03-14-lazy-dfa-loop-unrolling-design.md`

---

## Chunk 1: Benchmarks & Baseline

### Task 1: Add targeted benchmark patterns to SearchBenchmark

**Files:**
- Modify: `regex-bench/src/main/java/lol/ohai/regex/bench/SearchBenchmark.java`

- [ ] **Step 1: Add benchmark fields and setup**

Add three new pattern pairs to `SearchBenchmark.java`. Insert the fields after the existing `ohaiUnicodeWord`/`jdkUnicodeWord` fields (after line 45), and add compilation in `setup()` (after line 62):

```java
// Loop unrolling stress: high state-creation (worst case for unrolling)
private Regex ohaiWordRepeat;
private Pattern jdkWordRepeat;

// Loop unrolling stress: moderate transitions with look-around
private Regex ohaiMultiline;
private Pattern jdkMultiline;

// Loop unrolling stress: literal not found, mostly self-transitions (best case)
private Regex ohaiLiteralMiss;
private Pattern jdkLiteralMiss;
```

In `setup()`:
```java
ohaiWordRepeat = Regex.compile("\\w{50}");
jdkWordRepeat = Pattern.compile("\\w{50}", Pattern.UNICODE_CHARACTER_CLASS);

ohaiMultiline = Regex.compile("(?m)^.+$");
jdkMultiline = Pattern.compile("(?m)^.+$");

ohaiLiteralMiss = Regex.compile("ZQZQZQZQ");
jdkLiteralMiss = Pattern.compile("ZQZQZQZQ");
```

- [ ] **Step 2: Add benchmark methods**

Append after the existing `unicodeWordJdk` benchmark method (after line 140):

```java
// ---- Loop unrolling stress: \w{50} (high state creation) ----

@Benchmark
public void wordRepeatOhai(Blackhole bh) {
    ohaiWordRepeat.findAll(Haystacks.UNICODE_MIXED).forEach(bh::consume);
}

@Benchmark
public void wordRepeatJdk(Blackhole bh) {
    Matcher m = jdkWordRepeat.matcher(Haystacks.UNICODE_MIXED);
    while (m.find()) {
        bh.consume(m.start());
    }
}

// ---- Loop unrolling stress: (?m)^.+$ (multiline look-around) ----

@Benchmark
public void multilineOhai(Blackhole bh) {
    ohaiMultiline.findAll(Haystacks.SHERLOCK_EN).forEach(bh::consume);
}

@Benchmark
public void multilineJdk(Blackhole bh) {
    Matcher m = jdkMultiline.matcher(Haystacks.SHERLOCK_EN);
    while (m.find()) {
        bh.consume(m.start());
    }
}

// ---- Loop unrolling stress: ZQZQZQZQ (literal miss, self-transitions) ----

@Benchmark
public void literalMissOhai(Blackhole bh) {
    ohaiLiteralMiss.findAll(Haystacks.SHERLOCK_EN).forEach(bh::consume);
}

@Benchmark
public void literalMissJdk(Blackhole bh) {
    Matcher m = jdkLiteralMiss.matcher(Haystacks.SHERLOCK_EN);
    while (m.find()) {
        bh.consume(m.start());
    }
}
```

- [ ] **Step 3: Verify benchmarks compile**

Run:
```bash
./mvnw -P bench package -DskipTests
```
Expected: BUILD SUCCESS

- [ ] **Step 4: Run baseline benchmarks and save results**

Run:
```bash
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5 2>&1 | tee /tmp/bench-baseline.txt
```
Expected: All benchmarks complete. Save this output — it's the baseline for comparison after unrolling.

- [ ] **Step 5: Commit**

```bash
git add regex-bench/src/main/java/lol/ohai/regex/bench/SearchBenchmark.java
git commit -m "bench: add loop unrolling stress benchmarks (wordRepeat, multiline, literalMiss)"
```

---

## Chunk 2: Forward Search Unrolling

### Task 2: Add edge-case unit tests for forward search unrolling

**Files:**
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java`

These tests exercise the boundaries that the unrolled loop must handle correctly: haystacks shorter than 4 chars (tail-only), exactly 4 chars (one unrolled iteration), and patterns that hit special states at each of the 4 unrolled positions.

- [ ] **Step 1: Write tests**

Add the following tests after the existing `reverseSearchAtBoundary` test (after line 318), before the `// -- Helpers --` section:

```java
// -- Loop unrolling edge cases (forward) --

@Test
void fwdEmptyHaystack() {
    // 0 chars: inner loop never enters, outer tail handles it
    var result = search("a", "");
    assertInstanceOf(SearchResult.NoMatch.class, result);
}

@Test
void fwdOneCharMatch() {
    // 1 char: inner loop guard (pos + 3 < end) fails, tail processes
    var result = search("a", "a");
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(1, ((SearchResult.Match) result).offset());
}

@Test
void fwdTwoCharMatch() {
    var result = search("ab", "ab");
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(2, ((SearchResult.Match) result).offset());
}

@Test
void fwdThreeCharMatch() {
    var result = search("abc", "abc");
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(3, ((SearchResult.Match) result).offset());
}

@Test
void fwdExactlyFourCharMatch() {
    // 4 chars: inner loop runs one full iteration
    var result = search("abcd", "abcd");
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(4, ((SearchResult.Match) result).offset());
}

@Test
void fwdFiveCharMatch() {
    // 5 chars: one full unrolled iteration + 1 char in tail
    var result = search("abcde", "abcde");
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(5, ((SearchResult.Match) result).offset());
}

@Test
void fwdLongLiteralMatch() {
    // Many full unrolled iterations
    var result = search("abcdefgh", "xxabcdefghxx");
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(10, ((SearchResult.Match) result).offset());
}

@Test
void fwdMatchAtUnrollBoundary() {
    // Pattern that matches at position 4 (one full unrolled iteration)
    var result = search("e", "abcde");
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(5, ((SearchResult.Match) result).offset());
}

@Test
void fwdDeadStateInLongInput() {
    // No match in a long input — dead state reached after exhausting alternatives
    var result = search("ZQZQ", "abcdefghijklmnopqrstuvwxyz");
    assertInstanceOf(SearchResult.NoMatch.class, result);
}

@Test
void fwdMatchMidStreamAfterFullUnroll() {
    // 8-char haystack, match ends at pos 5: one full unrolled iteration (pos 0-3),
    // then break-out at step 1 of the second iteration when match state is hit.
    var result = search("[a-z]{5}", "abcdeXXX");
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(5, ((SearchResult.Match) result).offset());
}

@Test
void fwdMatchMidStreamAtStep2() {
    // 10-char haystack, match ends at pos 6: one full iteration (0-3),
    // then step 0 ok (pos 4), step 1 ok (pos 5), step 2 triggers match.
    var result = search("[a-z]{6}", "abcdefXXXX");
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(6, ((SearchResult.Match) result).offset());
}
```

- [ ] **Step 2: Run tests to confirm they pass (pre-unrolling baseline)**

Run:
```bash
./mvnw test -Dtest="LazyDFATest"
```
Expected: All tests PASS (these exercise the same behavior, just with specific sizes).

- [ ] **Step 3: Commit**

```bash
git add regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java
git commit -m "test: add edge-case tests for loop unrolling boundary conditions"
```

### Task 3: Implement forward search unrolling

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java:88-148`

- [ ] **Step 1: Replace the forward search loop body**

Replace the `while (pos < end)` loop body in `searchFwd()` (lines 102-148) with the unrolled version. The code before line 102 (variable setup, start state) and after line 148 (right-edge transition, return) are **unchanged**.

Replace lines 102-148 with:

```java
        while (pos < end) {
            // Inner unrolled loop: process 4 transitions per iteration.
            // The guard (pos + 3 < end) ensures all 4 haystack accesses are in bounds.
            // Only cached, non-special transitions (nextSid > quit) stay in the inner loop.
            // Any special state (match, UNKNOWN, dead, quit) breaks out to the outer loop.
            while (pos + 3 < end) {
                int s0 = cache.nextState(sid, charClasses.classify(haystack[pos]));
                if (s0 <= quit) { break; }

                int s1 = cache.nextState(s0, charClasses.classify(haystack[pos + 1]));
                if (s1 <= quit) { sid = s0; pos++; cache.charsSearched++; break; }

                int s2 = cache.nextState(s1, charClasses.classify(haystack[pos + 2]));
                if (s2 <= quit) { sid = s1; pos += 2; cache.charsSearched += 2; break; }

                int s3 = cache.nextState(s2, charClasses.classify(haystack[pos + 3]));
                if (s3 <= quit) { sid = s2; pos += 3; cache.charsSearched += 3; break; }

                sid = s3;
                pos += 4;
                cache.charsSearched += 4;
                continue;
            }

            // Outer dispatch: handle the char at pos (either a break-out from the inner
            // loop or a tail char when fewer than 4 remain). Re-classify and dispatch.
            if (pos >= end) break;

            int classId = charClasses.classify(haystack[pos]);
            int nextSid = cache.nextState(sid, classId);

            if (nextSid > quit) {
                sid = nextSid;
                pos++;
                cache.charsSearched++;
                continue;
            }

            if (nextSid < 0) {
                lastMatchEnd = pos;
                sid = nextSid & 0x7FFF_FFFF;
                pos++;
                cache.charsSearched++;
                continue;
            }

            // Slow path: UNKNOWN, DEAD, or QUIT
            if (nextSid == DFACache.UNKNOWN) {
                nextSid = computeNextState(cache, sid, classId, haystack[pos]);
                if (nextSid == quit) return new SearchResult.GaveUp(pos);
                cache.setTransition(sid, classId, nextSid);
                sid = nextSid;
                if (sid < 0) {
                    lastMatchEnd = pos;
                    sid = sid & 0x7FFF_FFFF;
                }
                pos++;
                cache.charsSearched++;
                continue;
            }
            if (nextSid == dead) {
                sid = dead;
                break;
            }
            // nextSid == quit
            return new SearchResult.GaveUp(pos);
        }
```

Key points:
- The inner `while (pos + 3 < end)` loop processes 4 chars per iteration
- On break-out: `sid` = last good state, `pos` = triggering char, `charsSearched` accounts for K successful steps
- After the inner loop, `if (pos >= end) break;` handles the case where the inner loop consumed all remaining chars
- The outer dispatch is the same code as the original loop body

- [ ] **Step 2: Run LazyDFA tests**

Run:
```bash
./mvnw test -Dtest="LazyDFATest"
```
Expected: All tests PASS

- [ ] **Step 3: Run full test suite**

Run:
```bash
./mvnw test
```
Expected: All 2,154 tests PASS. Zero failures.

- [ ] **Step 4: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java
git commit -m "$(cat <<'EOF'
feat: unroll forward DFA search loop 4x for throughput improvement

Ref: upstream/regex/regex-automata/src/hybrid/search.rs:110-221
EOF
)"
```

---

## Chunk 3: Reverse Search Unrolling

### Task 4: Add edge-case unit tests for reverse search unrolling

**Files:**
- Modify: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java`

- [ ] **Step 1: Write tests**

Add after the forward unrolling edge-case tests (before `// -- Helpers --`):

```java
// -- Loop unrolling edge cases (reverse) --

@Test
void revEmptySpan() {
    // 0-length span: start == end, pos = -1, outer loop never enters
    SearchResult result = searchReverse("a", "abc", 1, 1);
    assertInstanceOf(SearchResult.NoMatch.class, result);
}

@Test
void revOneCharMatch() {
    SearchResult result = searchReverse("a", "a", 0, 1);
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(0, ((SearchResult.Match) result).offset());
}

@Test
void revTwoCharMatch() {
    SearchResult result = searchReverse("ab", "ab", 0, 2);
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(0, ((SearchResult.Match) result).offset());
}

@Test
void revThreeCharMatch() {
    SearchResult result = searchReverse("abc", "abc", 0, 3);
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(0, ((SearchResult.Match) result).offset());
}

@Test
void revExactlyFourCharMatch() {
    SearchResult result = searchReverse("abcd", "abcd", 0, 4);
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(0, ((SearchResult.Match) result).offset());
}

@Test
void revFiveCharMatch() {
    SearchResult result = searchReverse("abcde", "abcde", 0, 5);
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(0, ((SearchResult.Match) result).offset());
}

@Test
void revLongLiteralMatch() {
    SearchResult result = searchReverse("abcdefgh", "xxabcdefghxx", 0, 10);
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(2, ((SearchResult.Match) result).offset());
}

@Test
void revNoMatchLongInput() {
    SearchResult result = searchReverse("ZQZQ", "abcdefghijklmnopqrstuvwxyz", 0, 26);
    assertInstanceOf(SearchResult.NoMatch.class, result);
}

@Test
void revMatchMidStreamAfterFullUnroll() {
    // 8-char span, match start at pos 3: reverse from pos 7, one full
    // unrolled iteration (7,6,5,4), then break-out when match state hit.
    SearchResult result = searchReverse("[a-z]{5}", "XXXabcde", 0, 8);
    assertInstanceOf(SearchResult.Match.class, result);
    assertEquals(3, ((SearchResult.Match) result).offset());
}
```

- [ ] **Step 2: Run tests to confirm they pass (pre-unrolling baseline)**

Run:
```bash
./mvnw test -Dtest="LazyDFATest"
```
Expected: All tests PASS.

- [ ] **Step 3: Commit**

```bash
git add regex-automata/src/test/java/lol/ohai/regex/automata/dfa/lazy/LazyDFATest.java
git commit -m "test: add reverse search edge-case tests for loop unrolling"
```

### Task 5: Implement reverse search unrolling

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java:205-242`

- [ ] **Step 1: Replace the reverse search loop body**

Replace the `while (pos >= start)` loop and its body in `searchRev()` (lines 205-242) with the unrolled version. Line 204 (`int pos = end - 1;`) and all code before it (variable setup, start state) are **unchanged**. Code after line 242 (left-edge transition, return) is also **unchanged**.

Replace lines 205-242 with:

```java
        while (pos >= start) {
            // Inner unrolled loop: process 4 transitions per iteration (reverse).
            while (pos >= start + 3) {
                int s0 = cache.nextState(sid, charClasses.classify(haystack[pos]));
                if (s0 <= quit) { break; }

                int s1 = cache.nextState(s0, charClasses.classify(haystack[pos - 1]));
                if (s1 <= quit) { sid = s0; pos--; cache.charsSearched++; break; }

                int s2 = cache.nextState(s1, charClasses.classify(haystack[pos - 2]));
                if (s2 <= quit) { sid = s1; pos -= 2; cache.charsSearched += 2; break; }

                int s3 = cache.nextState(s2, charClasses.classify(haystack[pos - 3]));
                if (s3 <= quit) { sid = s2; pos -= 3; cache.charsSearched += 3; break; }

                sid = s3;
                pos -= 4;
                cache.charsSearched += 4;
                continue;
            }

            // Outer dispatch: handle break-out or tail chars.
            if (pos < start) break;

            int classId = charClasses.classify(haystack[pos]);
            int nextSid = cache.nextState(sid, classId);

            if (nextSid > quit) {
                sid = nextSid;
                pos--;
                cache.charsSearched++;
                continue;
            }

            if (nextSid < 0) {
                lastMatchStart = pos + 1;
                sid = nextSid & 0x7FFF_FFFF;
                pos--;
                cache.charsSearched++;
                continue;
            }

            if (nextSid == DFACache.UNKNOWN) {
                nextSid = computeNextState(cache, sid, classId, haystack[pos]);
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
                sid = dead;
                break;
            }
            return new SearchResult.GaveUp(pos);
        }
```

- [ ] **Step 2: Run LazyDFA tests**

Run:
```bash
./mvnw test -Dtest="LazyDFATest"
```
Expected: All tests PASS.

- [ ] **Step 3: Run full test suite**

Run:
```bash
./mvnw test
```
Expected: All 2,154 tests PASS. Zero failures.

- [ ] **Step 4: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java
git commit -m "$(cat <<'EOF'
feat: unroll reverse DFA search loop 4x for throughput improvement

Ref: upstream/regex/regex-automata/src/hybrid/search.rs:339-397
EOF
)"
```

---

## Chunk 4: Benchmark & Finalize

### Task 6: Run benchmarks and compare against baseline

**Files:**
- None (measurement only)

- [ ] **Step 1: Build benchmark jar**

Run:
```bash
./mvnw -P bench package -DskipTests
```
Expected: BUILD SUCCESS

- [ ] **Step 2: Run benchmarks**

Run:
```bash
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5 2>&1 | tee /tmp/bench-unrolled.txt
```
Expected: All benchmarks complete.

- [ ] **Step 3: Compare results**

Compare `/tmp/bench-baseline.txt` with `/tmp/bench-unrolled.txt`:
- **Existing benchmarks** (literal, charClass, alternation, captures, unicodeWord): must not regress by more than 2×
- **New benchmarks**: `literalMiss` should show the most improvement, `wordRepeat` should be neutral or slight regression, `multiline` should show moderate improvement
- If any benchmark changes by >2× in either direction, investigate before proceeding

- [ ] **Step 4: If benchmarks look good, commit any remaining changes**

If the results are acceptable, no additional code changes are needed. If there is a regression, investigate and fix before proceeding (this may require going back to Task 3 or Task 5).

### Task 7: Update lazy-dfa-gaps.md

**Files:**
- Modify: `docs/architecture/lazy-dfa-gaps.md:57-65`

- [ ] **Step 1: Mark loop unrolling as done**

Replace the "Search Loop Unrolling" section (lines 57-65) with:

```markdown
## Search Loop Unrolling — Implemented

**Status: DONE** (2026-03-15)

Both `searchFwd()` and `searchRev()` now use 4× loop unrolling. An inner loop processes 4 transitions per iteration with a single `s <= quit` guard per step. On break-out (match, UNKNOWN, dead, quit), the outer loop re-classifies the triggering char and dispatches via the existing slow path. No semantic changes.

**Design spec:** `docs/superpowers/specs/2026-03-14-lazy-dfa-loop-unrolling-design.md`
```

- [ ] **Step 2: Commit**

```bash
git add docs/architecture/lazy-dfa-gaps.md
git commit -m "docs: mark loop unrolling as implemented in lazy-dfa-gaps"
```
