# DFA JIT Headroom: Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract cold paths from `searchFwdLong()` and `searchRevLong()` to reduce bytecode size, enabling C2 to inline more `classify()` call sites in the 4× unrolled inner loop.

**Architecture:** Extract two cold-path blocks (slow-path dispatch + right-edge transition) from each search method into private helpers. The hot inner loop stays untouched. Validate via `-XX:+PrintInlining` analysis that `classify` inline count increases, then confirm with benchmarks.

**Tech Stack:** Java 21, JMH benchmarks, JUnit 5, `-XX:+PrintInlining` / `-XX:+LogCompilation` JIT diagnostics

---

## Chunk 1: JIT Baseline + Cold Path Extraction

### Task 1: Record JIT Baseline

**Files:**
- None modified

- [ ] **Step 1: Build the benchmark jar**

```bash
./mvnw -P bench package -DskipTests
```

- [ ] **Step 2: Record baseline JIT inlining behavior**

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation \
  -XX:LogFile=/tmp/jit-baseline.xml \
  -jar regex-bench/target/benchmarks.jar "RawEngineBenchmark.rawCharClassOhai" \
  -f 1 -wi 2 -i 1 -t 1
```

- [ ] **Step 3: Parse baseline inline counts**

Write and run a script to count `classify` inline successes inside the last C2 compilation of `searchFwdLong`:

```bash
python3 -c "
import re
with open('/tmp/jit-baseline.xml') as f:
    content = f.read()
# Find all task blocks for searchFwdLong
tasks = re.findall(r'<task[^>]*searchFwdLong[^>]*>.*?</task>', content, re.DOTALL)
methods = {}
last = tasks[-1]
for m in re.finditer(r\"<method id='(\d+)'[^>]*name='([^']+)'[^>]*bytes='(\d+)'\", last):
    methods[m.group(1)] = (m.group(2), m.group(3))
lines = last.split('\n')
inline_count = 0
reject_count = 0
for i, line in enumerate(lines):
    call_match = re.search(r\"<call method='(\d+)'\", line)
    if call_match:
        mid = call_match.group(1)
        name, bytez = methods.get(mid, ('unknown', '?'))
        if name == 'classify':
            for j in range(i+1, min(i+3, len(lines))):
                if 'inline_success' in lines[j]:
                    inline_count += 1
                    break
                elif 'inline_fail' in lines[j]:
                    reject_count += 1
                    break
print(f'classify: {inline_count} inlined, {reject_count} rejected, {inline_count + reject_count} total')
"
```

Expected baseline: `classify: 2 inlined, 3 rejected, 5 total` (or similar — the exact numbers may vary by JVM version, but 2/5 inlined is the measured baseline).

Record this number — it's the gate criterion.

- [ ] **Step 4: Record baseline benchmarks**

```bash
java -jar regex-bench/target/benchmarks.jar \
  "SearchBenchmark|RawEngineBenchmark" -f 1 -wi 3 -i 5 \
  | tee /tmp/jit-headroom-baseline.txt
```

- [ ] **Step 5: Run full test suite to confirm green baseline**

```bash
./mvnw test
```

Expected: All tests pass.

---

### Task 2: Extract slow-path dispatch from searchFwdLong

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java`

- [ ] **Step 1: Add the handleSlowTransition helper method**

Add this method to `LazyDFA.java` (after the `searchFwdLong` method, before `searchRev`):

```java
/**
 * Handle slow-path state transitions in forward search: UNKNOWN (compute
 * new state), dead (stop), or quit (give up to PikeVM).
 *
 * <p>Returns a packed long: low 32 bits = new state ID (or dead/quit
 * sentinel), high 32 bits = updated lastMatchEnd (-1 if unchanged).</p>
 */
private long handleSlowTransition(DFACache cache, int sid, int classId,
                                   int nextSid, char inputChar, int pos,
                                   long charsSearched) {
    int stride = charClasses.stride();
    int dead = DFACache.dead(stride);
    int quit = DFACache.quit(stride);
    int lastMatchEnd = -1;
    // nextSid is passed from the caller (already computed by cache.nextState)

    if (nextSid == DFACache.UNKNOWN) {
        cache.charsSearched = charsSearched;
        nextSid = computeNextState(cache, sid, classId, inputChar);
        if (nextSid == quit) {
            return packSlowResult(quit, -1);
        }
        cache.setTransition(sid, classId, nextSid);
        if (nextSid < 0) {
            lastMatchEnd = pos;
            nextSid = nextSid & 0x7FFF_FFFF;
        }
        return packSlowResult(nextSid, lastMatchEnd);
    }
    if (nextSid == dead) {
        return packSlowResult(dead, -1);
    }
    // nextSid == quit
    cache.charsSearched = charsSearched;
    return packSlowResult(quit, -1);
}

private static long packSlowResult(int sid, int lastMatchEnd) {
    return ((long) lastMatchEnd << 32) | (sid & 0xFFFF_FFFFL);
}

private static int slowResultSid(long result) {
    return (int) result;
}

private static int slowResultLastMatch(long result) {
    return (int) (result >>> 32);
}
```

- [ ] **Step 2: Replace the slow-path block in searchFwdLong**

In `searchFwdLong()`, replace lines 164-187 (the slow path block):

**Old code (to replace):**
```java
            // Slow path: UNKNOWN, DEAD, or QUIT
            if (nextSid == DFACache.UNKNOWN) {
                cache.charsSearched = charsSearched;
                nextSid = computeNextState(cache, sid, classId, haystack[pos]);
                if (nextSid == quit) {
                    return SearchResult.gaveUp(pos);
                }
                cache.setTransition(sid, classId, nextSid);
                sid = nextSid;
                if (sid < 0) {
                    lastMatchEnd = pos;
                    sid = sid & 0x7FFF_FFFF;
                }
                pos++;
                charsSearched++;
                continue;
            }
            if (nextSid == dead) {
                sid = dead;
                break;
            }
            // nextSid == quit
            cache.charsSearched = charsSearched;
            return SearchResult.gaveUp(pos);
```

**New code:**
```java
            // Slow path: delegate to helper (pass nextSid to avoid redundant cache lookup)
            long slowResult = handleSlowTransition(cache, sid, classId,
                    nextSid, haystack[pos], pos, charsSearched);
            int newSid = slowResultSid(slowResult);
            int newMatch = slowResultLastMatch(slowResult);
            if (newMatch >= 0) lastMatchEnd = newMatch;
            if (newSid == dead) { sid = dead; break; }
            if (newSid == quit) { return SearchResult.gaveUp(pos); }
            sid = newSid;
            pos++;
            charsSearched++;
            continue;
```

- [ ] **Step 3: Run full test suite**

```bash
./mvnw test
```

Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java
git commit -m "refactor: extract slow-path dispatch from searchFwdLong

Move UNKNOWN/dead/quit handling into handleSlowTransition() helper.
Returns packed long (sid + lastMatchEnd) to avoid mutable state.
Goal: reduce searchFwdLong bytecode size for better C2 inlining.

Spec: docs/superpowers/specs/2026-03-16-dfa-jit-headroom-design.md §Phase1A"
```

---

### Task 3: Extract right-edge transition from searchFwdLong

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java`

- [ ] **Step 1: Add the handleRightEdge helper method**

Add after `handleSlowTransition`:

```java
/**
 * Process the right-edge transition after the forward search main loop.
 * Handles EOI class or the character just past the search span for
 * correct look-ahead context ($ and \b assertions).
 *
 * @return updated lastMatchEnd (-1 if no match at edge)
 */
private int handleRightEdge(DFACache cache, int sid, char[] haystack,
                             int end, int lastMatchEnd, long charsSearched) {
    int stride = charClasses.stride();
    int dead = DFACache.dead(stride);
    int quit = DFACache.quit(stride);
    int rawSid = sid & 0x7FFF_FFFF;

    if (rawSid != dead && rawSid != quit) {
        cache.charsSearched = charsSearched;
        int rightEdgeSid;
        if (end < haystack.length) {
            int classId = charClasses.classify(haystack[end]);
            if (charClasses.hasQuitClasses() && charClasses.isQuitClass(classId)) {
                return -2; // sentinel: gaveUp at end
            }
            rightEdgeSid = computeNextState(cache, rawSid, classId, haystack[end]);
        } else {
            rightEdgeSid = computeNextState(cache, rawSid, charClasses.eoiClass());
        }
        if (rightEdgeSid < 0) {
            return end; // match at right edge
        }
    } else {
        cache.charsSearched = charsSearched;
    }
    return lastMatchEnd;
}
```

Note: returns `-2` as a sentinel for "gaveUp at end" — the caller checks for this special value to return `SearchResult.gaveUp(end)`.

- [ ] **Step 2: Replace the right-edge block in searchFwdLong**

In `searchFwdLong()`, replace lines 190-213 (the right-edge block after the main loop):

**Old code (to replace):**
```java
        // Right-edge transition for forward search. When the search span
        // ends at the haystack boundary, use the EOI class. Otherwise,
        // transition on the actual character after the span to get correct
        // look-ahead context (e.g., $ and word boundary assertions).
        // Ref: upstream/regex/regex-automata/src/hybrid/search.rs:693-726
        int rawSid = sid & 0x7FFF_FFFF;
        if (rawSid != dead && rawSid != quit) {
            int rightEdgeSid;
            cache.charsSearched = charsSearched;
            if (end < haystack.length) {
                int classId = charClasses.classify(haystack[end]);
                if (charClasses.hasQuitClasses() && charClasses.isQuitClass(classId)) {
                    return SearchResult.gaveUp(end);
                }
                rightEdgeSid = computeNextState(cache, rawSid, classId, haystack[end]);
            } else {
                rightEdgeSid = computeNextState(cache, rawSid, charClasses.eoiClass());
            }
            if (rightEdgeSid < 0) {
                lastMatchEnd = end;
            }
        } else {
            cache.charsSearched = charsSearched;
        }
```

**New code:**
```java
        // Right-edge transition
        lastMatchEnd = handleRightEdge(cache, sid, haystack, end, lastMatchEnd, charsSearched);
        if (lastMatchEnd == -2) return SearchResult.gaveUp(end);
```

- [ ] **Step 3: Run full test suite**

```bash
./mvnw test
```

Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java
git commit -m "refactor: extract right-edge transition from searchFwdLong

Move post-loop EOI/boundary handling into handleRightEdge() helper.
Further reduces searchFwdLong bytecode size for C2 inlining headroom.

Spec: docs/superpowers/specs/2026-03-16-dfa-jit-headroom-design.md §Phase1B"
```

---

### Task 4: Mirror extractions in searchRevLong

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java`

- [ ] **Step 1: Add handleSlowTransitionRev helper**

Same logic as `handleSlowTransition` but for reverse search — the only difference is that `lastMatchStart = pos + 1` (not `pos`) when a match-flagged state is computed:

```java
/**
 * Handle slow-path state transitions in reverse search.
 * Same as handleSlowTransition but match position is pos + 1.
 */
private long handleSlowTransitionRev(DFACache cache, int sid, int classId,
                                      int nextSid, char inputChar, int pos,
                                      long charsSearched) {
    int stride = charClasses.stride();
    int dead = DFACache.dead(stride);
    int quit = DFACache.quit(stride);
    int lastMatchStart = -1;
    // nextSid is passed from the caller (already computed by cache.nextState)

    if (nextSid == DFACache.UNKNOWN) {
        cache.charsSearched = charsSearched;
        nextSid = computeNextState(cache, sid, classId, inputChar);
        if (nextSid == quit) {
            return packSlowResult(quit, -1);
        }
        cache.setTransition(sid, classId, nextSid);
        if (nextSid < 0) {
            lastMatchStart = pos + 1;
            nextSid = nextSid & 0x7FFF_FFFF;
        }
        return packSlowResult(nextSid, lastMatchStart);
    }
    if (nextSid == dead) {
        return packSlowResult(dead, -1);
    }
    cache.charsSearched = charsSearched;
    return packSlowResult(quit, -1);
}
```

- [ ] **Step 2: Add handleLeftEdge helper**

```java
/**
 * Process the left-edge transition after the reverse search main loop.
 * Handles start-of-text EOI or the character before the search span
 * for correct look-behind context.
 *
 * @return updated lastMatchStart (-1 if no match at edge, -2 if gaveUp)
 */
private int handleLeftEdge(DFACache cache, int sid, char[] haystack,
                            int start, int lastMatchStart, long charsSearched) {
    int stride = charClasses.stride();
    int dead = DFACache.dead(stride);
    int quit = DFACache.quit(stride);
    int rawSid = sid & 0x7FFF_FFFF;

    if (rawSid != dead && rawSid != quit) {
        cache.charsSearched = charsSearched;
        int leftEdgeSid;
        if (start > 0) {
            int classId = charClasses.classify(haystack[start - 1]);
            if (charClasses.hasQuitClasses() && charClasses.isQuitClass(classId)) {
                return -2; // sentinel: gaveUp at start
            }
            leftEdgeSid = computeNextState(cache, rawSid, classId, haystack[start - 1]);
        } else {
            leftEdgeSid = computeNextState(cache, rawSid, charClasses.eoiClass());
        }
        if (leftEdgeSid < 0) {
            return start; // match at left edge
        }
    } else {
        cache.charsSearched = charsSearched;
    }
    return lastMatchStart;
}
```

- [ ] **Step 3: Replace the slow-path block in searchRevLong**

In `searchRevLong()`, replace the slow-path block (UNKNOWN/dead/quit handling inside the main while loop) with a call to `handleSlowTransitionRev`:

**Old code (to replace):**
```java
            if (nextSid == DFACache.UNKNOWN) {
                cache.charsSearched = charsSearched;
                nextSid = computeNextState(cache, sid, classId, haystack[pos]);
                if (nextSid == quit) {
                    return SearchResult.gaveUp(pos);
                }
                cache.setTransition(sid, classId, nextSid);
                sid = nextSid;
                if (sid < 0) {
                    lastMatchStart = pos + 1;
                    sid = sid & 0x7FFF_FFFF;
                }
                pos--;
                charsSearched++;
                continue;
            }
            if (nextSid == dead) {
                sid = dead;
                break;
            }
            cache.charsSearched = charsSearched;
            return SearchResult.gaveUp(pos);
```

**New code:**
```java
            // Slow path: delegate to helper (pass nextSid to avoid redundant cache lookup)
            long slowResult = handleSlowTransitionRev(cache, sid, classId,
                    nextSid, haystack[pos], pos, charsSearched);
            int newSid = slowResultSid(slowResult);
            int newMatch = slowResultLastMatch(slowResult);
            if (newMatch >= 0) lastMatchStart = newMatch;
            if (newSid == dead) { sid = dead; break; }
            if (newSid == quit) { return SearchResult.gaveUp(pos); }
            sid = newSid;
            pos--;
            charsSearched++;
            continue;
```

- [ ] **Step 4: Replace the left-edge block in searchRevLong**

Replace the post-loop left-edge block with a call to `handleLeftEdge`:

**Old code (to replace):**
```java
        // Left-edge transition for reverse search. When the search span
        // starts at position 0 (start of text), use the EOI class. Otherwise,
        // transition on the actual character before the span to get correct
        // look-behind context (e.g., word boundary assertions).
        // Ref: upstream/regex/regex-automata/src/hybrid/search.rs:737-754
        int rawSid = sid & 0x7FFF_FFFF;
        if (rawSid != dead && rawSid != quit) {
            int leftEdgeSid;
            cache.charsSearched = charsSearched;
            if (start > 0) {
                int classId = charClasses.classify(haystack[start - 1]);
                if (charClasses.hasQuitClasses() && charClasses.isQuitClass(classId)) {
                    return SearchResult.gaveUp(start);
                }
                leftEdgeSid = computeNextState(cache, rawSid, classId, haystack[start - 1]);
            } else {
                leftEdgeSid = computeNextState(cache, rawSid, charClasses.eoiClass());
            }
            if (leftEdgeSid < 0) {
                lastMatchStart = start;
            }
        } else {
            cache.charsSearched = charsSearched;
        }
```

**New code:**
```java
        // Left-edge transition
        lastMatchStart = handleLeftEdge(cache, sid, haystack, start, lastMatchStart, charsSearched);
        if (lastMatchStart == -2) return SearchResult.gaveUp(start);
```

- [ ] **Step 5: Run full test suite**

```bash
./mvnw test
```

Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/lazy/LazyDFA.java
git commit -m "refactor: extract cold paths from searchRevLong

Mirror the searchFwdLong extractions: handleSlowTransitionRev() and
handleLeftEdge() reduce searchRevLong bytecode size.

Spec: docs/superpowers/specs/2026-03-16-dfa-jit-headroom-design.md §Phase1C"
```

---

## Chunk 2: JIT Validation + Benchmarks

### Task 5: JIT Validation — Measure Post-Extraction Inline Counts

**Files:**
- None modified

- [ ] **Step 1: Rebuild the benchmark jar**

```bash
./mvnw -P bench package -DskipTests
```

- [ ] **Step 2: Record post-extraction JIT inlining behavior**

```bash
java -XX:+UnlockDiagnosticVMOptions -XX:+LogCompilation \
  -XX:LogFile=/tmp/jit-post.xml \
  -jar regex-bench/target/benchmarks.jar "RawEngineBenchmark.rawCharClassOhai" \
  -f 1 -wi 2 -i 1 -t 1
```

- [ ] **Step 3: Parse post-extraction inline counts**

Run the same parsing script as Task 1 Step 3, but on `/tmp/jit-post.xml`:

```bash
python3 -c "
import re
with open('/tmp/jit-post.xml') as f:
    content = f.read()
tasks = re.findall(r'<task[^>]*searchFwdLong[^>]*>.*?</task>', content, re.DOTALL)
methods = {}
last = tasks[-1]
for m in re.finditer(r\"<method id='(\d+)'[^>]*name='([^']+)'[^>]*bytes='(\d+)'\", last):
    methods[m.group(1)] = (m.group(2), m.group(3))
lines = last.split('\n')
inline_count = 0
reject_count = 0
for i, line in enumerate(lines):
    call_match = re.search(r\"<call method='(\d+)'\", line)
    if call_match:
        mid = call_match.group(1)
        name, bytez = methods.get(mid, ('unknown', '?'))
        if name == 'classify':
            for j in range(i+1, min(i+3, len(lines))):
                if 'inline_success' in lines[j]:
                    inline_count += 1
                    break
                elif 'inline_fail' in lines[j]:
                    reject_count += 1
                    break
print(f'classify: {inline_count} inlined, {reject_count} rejected, {inline_count + reject_count} total')
"
```

- [ ] **Step 4: Also check the searchFwdLong bytecode size**

Look for the bytecode size in the LogCompilation output:

```bash
grep "searchFwdLong" /tmp/jit-post.xml | grep "bytes=" | head -3
```

The `bytes='NNN'` attribute shows the new bytecode size. Expected: ~385-420 bytes (down from 613).

- [ ] **Step 5: Compare and evaluate**

| Metric | Baseline | Post-extraction | Gate |
|--------|----------|-----------------|------|
| `classify` inlined | 2/5 (expected) | ? | Must increase |
| searchFwdLong bytecode | 613b | ? | Must decrease |

**Decision:**
- If classify inline count **increased**: PASS — proceed to Task 6 (benchmarks)
- If classify inline count **unchanged**: FAIL — stop. The theory was wrong. Document findings, do NOT proceed to benchmarks or Phase 2.

---

### Task 6: Post-Extraction Benchmarks

**Files:**
- None modified

Only run this task if Task 5 passed the JIT gate.

- [ ] **Step 1: Run post-extraction benchmarks**

```bash
java -jar regex-bench/target/benchmarks.jar \
  "SearchBenchmark|RawEngineBenchmark" -f 1 -wi 3 -i 5 \
  | tee /tmp/jit-headroom-post.txt
```

- [ ] **Step 2: Compare baseline vs post-extraction**

Compare `/tmp/jit-headroom-baseline.txt` and `/tmp/jit-headroom-post.txt`.

Check:
- No benchmark regresses by more than 2×
- charClass and unicodeWord benchmarks should show improvement (more classify inlining in the hot loop)
- If any RawEngine benchmark regresses: the extracted helpers add too much call overhead — investigate

- [ ] **Step 3: Record results and commit the findings**

```bash
git add docs/superpowers/specs/2026-03-16-dfa-jit-headroom-design.md
git commit -m "docs: record Phase 1 JIT validation results

classify inline count: baseline X/5 → post Y/5
searchFwdLong bytecode: 613b → Nb
Benchmark delta: [summary]

Phase 2 gate: [PASS/FAIL]"
```

If PASS: Phase 2 (prefilter-at-start) will be planned in a separate session.
If FAIL: Document why and close the spec.
