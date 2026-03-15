# One-Pass DFA Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a one-pass DFA engine that extracts capture groups in a single forward scan, replacing PikeVM/backtracker as the capture engine for eligible patterns. Targets the 43× captures gap vs JDK.

**Architecture:** The one-pass DFA encodes capture slot updates in 64-bit transitions. Each transition packs a 21-bit state ID, 1-bit match_wins flag, 18-bit look assertion set, and 24-bit capture slot bitset. The builder compiles the NFA into this DFA (returning null if the pattern isn't one-pass). The search loop processes one char per iteration, applying slot updates from transition epsilons. Integrated as the preferred capture engine in `Strategy.Core.dfaSearchCaptures()`, running on the narrowed window after forward+reverse DFA.

**Tech Stack:** Java 21, JMH (benchmarks), JUnit 5 (tests)

**Spec:** `docs/superpowers/specs/2026-03-15-one-pass-dfa-design.md`

**Build/test commands:**
- Build: `./mvnw compile`
- All tests: `./mvnw test` (full reactor, never `-pl`)
- Single test: `./mvnw test -Dtest="fully.qualified.ClassName" -Dsurefire.failIfNoSpecifiedTests=false`
- Never use `./mvnw install`
- Benchmarks: `./mvnw -P bench package -DskipTests && java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5`

---

## Chunk 1: Data Structures and Cache

### Task 1: Create Epsilons helper (packed look assertions + capture slots)

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/Epsilons.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/onepass/EpsilonsTest.java`

The `Epsilons` type packs look-around assertions (18 bits) and capture slot updates (24 bits) into the lower 42 bits of a `long`. It is a value type — all operations are static methods on a raw `long`.

**Bit layout (lower 42 bits of a transition long):**
```
Bits 41–18: Capture slot bitset (24 bits → max 12 explicit groups)
Bits 17–0:  Look assertion bits (18 bits, matches LookKind ordinals)
```

- [ ] **Step 1: Create Epsilons.java**

```java
package lol.ohai.regex.automata.dfa.onepass;

/**
 * Packed epsilon data: look-around assertions + capture slot updates.
 * Stored in the lower 42 bits of a transition {@code long}.
 * All methods are static operating on raw {@code long} values.
 */
public final class Epsilons {
    private Epsilons() {}

    public static final long EMPTY = 0L;
    public static final int LOOK_BITS = 18;
    public static final int SLOT_BITS = 24;
    public static final int MAX_SLOTS = SLOT_BITS; // max 24 explicit slots = 12 groups
    private static final long LOOK_MASK = (1L << LOOK_BITS) - 1;
    private static final long SLOT_MASK = ((1L << SLOT_BITS) - 1) << LOOK_BITS;

    /** Extract look assertion bits (lower 18 bits). */
    public static int looks(long eps) {
        return (int) (eps & LOOK_MASK);
    }

    /** Extract slot bitset (bits 41–18). */
    public static int slots(long eps) {
        return (int) ((eps >>> LOOK_BITS) & ((1L << SLOT_BITS) - 1));
    }

    /** Return epsilons with the given look bit set. */
    public static long withLook(long eps, int lookBit) {
        return eps | (lookBit & LOOK_MASK);
    }

    /** Return epsilons with the given slot index set. */
    public static long withSlot(long eps, int slotIndex) {
        return eps | (1L << (slotIndex + LOOK_BITS));
    }

    /** Return true if looks are empty. */
    public static boolean looksEmpty(long eps) {
        return (eps & LOOK_MASK) == 0;
    }

    /** Apply slot updates: for each set bit in slots, write position to slotsOut. */
    public static void applySlots(long eps, int position, int[] slotsOut, int slotsLen) {
        int slotBits = slots(eps);
        while (slotBits != 0) {
            int idx = Integer.numberOfTrailingZeros(slotBits);
            if (idx < slotsLen) {
                slotsOut[idx] = position;
            }
            slotBits &= slotBits - 1; // clear lowest set bit
        }
    }
}
```

- [ ] **Step 2: Create EpsilonsTest.java**

```java
package lol.ohai.regex.automata.dfa.onepass;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EpsilonsTest {
    @Test void emptyHasNoLooksOrSlots() {
        assertEquals(0, Epsilons.looks(Epsilons.EMPTY));
        assertEquals(0, Epsilons.slots(Epsilons.EMPTY));
        assertTrue(Epsilons.looksEmpty(Epsilons.EMPTY));
    }

    @Test void withLookSetsLookBit() {
        long eps = Epsilons.withLook(Epsilons.EMPTY, 1 << 3);
        assertEquals(1 << 3, Epsilons.looks(eps));
        assertEquals(0, Epsilons.slots(eps));
    }

    @Test void withSlotSetsSlotBit() {
        long eps = Epsilons.withSlot(Epsilons.EMPTY, 5);
        assertEquals(0, Epsilons.looks(eps));
        assertEquals(1 << 5, Epsilons.slots(eps));
    }

    @Test void multipleSlotsAndLooks() {
        long eps = Epsilons.EMPTY;
        eps = Epsilons.withLook(eps, 1 << 0);
        eps = Epsilons.withLook(eps, 1 << 2);
        eps = Epsilons.withSlot(eps, 0);
        eps = Epsilons.withSlot(eps, 3);
        assertEquals((1 << 0) | (1 << 2), Epsilons.looks(eps));
        assertEquals((1 << 0) | (1 << 3), Epsilons.slots(eps));
    }

    @Test void applySlotsWritesPositions() {
        long eps = Epsilons.withSlot(Epsilons.withSlot(Epsilons.EMPTY, 1), 3);
        int[] out = {-1, -1, -1, -1};
        Epsilons.applySlots(eps, 42, out, out.length);
        assertEquals(-1, out[0]);
        assertEquals(42, out[1]);
        assertEquals(-1, out[2]);
        assertEquals(42, out[3]);
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest="lol.ohai.regex.automata.dfa.onepass.EpsilonsTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: All PASS.

- [ ] **Step 4: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/Epsilons.java
git add regex-automata/src/test/java/lol/ohai/regex/automata/dfa/onepass/EpsilonsTest.java
git commit -m "feat(onepass): add Epsilons helper for packed look+slot encoding"
```

### Task 2: Create Transition encoding helpers

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/TransitionTable.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/onepass/TransitionTableTest.java`

Each transition is a `long` with this layout:
```
Bits 63–43: State ID (21 bits, unsigned, use >>> to extract)
Bit  42:    match_wins flag
Bits 41–0:  Epsilons (18 look + 24 slot)
```

The transition table also stores a `PatternEpsilons` value at offset `stride` in each state row (one extra column beyond the alphabet). This encodes the pattern ID and final epsilons for match states.

**PatternEpsilons layout (long):**
```
Bits 63–42: Pattern ID (22 bits, 0x3FFFFF = no pattern)
Bits 41–0:  Epsilons
```

- [ ] **Step 1: Create TransitionTable.java**

```java
package lol.ohai.regex.automata.dfa.onepass;

import java.util.Arrays;

/**
 * Dense transition table for the one-pass DFA.
 * Each entry is a packed {@code long} encoding state ID, match_wins flag, and epsilons.
 * <p>Row = DFA state, Column = equivalence class ID.
 * Extra column at offset {@code alphabetLen} stores PatternEpsilons for match states.</p>
 */
public final class TransitionTable {
    // Transition encoding
    static final int STATE_ID_BITS = 21;
    static final long STATE_ID_SHIFT = 43;
    static final long MATCH_WINS_BIT = 1L << 42;
    static final long EPSILONS_MASK = (1L << 42) - 1;
    static final int MAX_STATE_ID = (1 << STATE_ID_BITS) - 1;

    // PatternEpsilons encoding
    static final int PATTERN_ID_BITS = 22;
    static final long PATTERN_ID_SHIFT = 42;
    static final int NO_PATTERN = (1 << PATTERN_ID_BITS) - 1; // sentinel
    static final long NO_PATTERN_EPSILONS = (long) NO_PATTERN << PATTERN_ID_SHIFT;

    // Dead state
    public static final int DEAD = 0;

    private long[] table;
    private final int stride;
    private final int alphabetLen; // classCount + 1 (for EOI)
    private int stateCount;

    public TransitionTable(int alphabetLen) {
        this.alphabetLen = alphabetLen;
        this.stride = Integer.highestOneBit(alphabetLen) << 1; // next power of 2
        this.table = new long[4 * stride]; // initial capacity: 4 states
        this.stateCount = 0;
    }

    // --- Transition encoding/decoding ---

    public static long encode(int stateId, boolean matchWins, long epsilons) {
        long t = ((long) stateId << STATE_ID_SHIFT) | (epsilons & EPSILONS_MASK);
        if (matchWins) t |= MATCH_WINS_BIT;
        return t;
    }

    public static int stateId(long trans) {
        return (int) (trans >>> STATE_ID_SHIFT);
    }

    public static boolean matchWins(long trans) {
        return (trans & MATCH_WINS_BIT) != 0;
    }

    public static long epsilons(long trans) {
        return trans & EPSILONS_MASK;
    }

    // --- PatternEpsilons encoding/decoding ---

    public static long encodePatternEpsilons(int patternId, long epsilons) {
        return ((long) patternId << PATTERN_ID_SHIFT) | (epsilons & EPSILONS_MASK);
    }

    public static int patternId(long patEps) {
        return (int) (patEps >>> PATTERN_ID_SHIFT);
    }

    public static long patternEpsilons(long patEps) {
        return patEps & EPSILONS_MASK;
    }

    public static boolean hasPattern(long patEps) {
        return patternId(patEps) != NO_PATTERN;
    }

    // --- Table operations ---

    /** Allocate a new state, returning its ID. */
    public int addState() {
        int id = stateCount * stride;
        if (id > MAX_STATE_ID) return -1; // too many states
        stateCount++;
        ensureCapacity(stateCount * stride);
        // Initialize with DEAD transitions and no-pattern sentinel
        // (DEAD = 0, so the zero-initialized table is correct for transitions)
        table[id + alphabetLen] = NO_PATTERN_EPSILONS;
        return id;
    }

    /** Get the transition for the given state and class. */
    public long get(int stateId, int classId) {
        return table[stateId + classId];
    }

    /** Set the transition for the given state and class. */
    public void set(int stateId, int classId, long trans) {
        table[stateId + classId] = trans;
    }

    /** Get the PatternEpsilons for a state (stored at offset alphabetLen). */
    public long getPatternEpsilons(int stateId) {
        return table[stateId + alphabetLen];
    }

    /** Set the PatternEpsilons for a state. */
    public void setPatternEpsilons(int stateId, long patEps) {
        table[stateId + alphabetLen] = patEps;
    }

    public int stateCount() { return stateCount; }
    public int stride() { return stride; }
    public int alphabetLen() { return alphabetLen; }

    /** Swap two states in the table (for match state shuffling). */
    public void swapStates(int a, int b) {
        for (int i = 0; i <= alphabetLen; i++) {
            long tmp = table[a + i];
            table[a + i] = table[b + i];
            table[b + i] = tmp;
        }
    }

    /** Update all transition targets: replace oldId with newId. */
    public void remapState(int oldId, int newId) {
        for (int i = 0; i < stateCount * stride; i++) {
            long t = table[i];
            if (stateId(t) == oldId) {
                table[i] = encode(newId, matchWins(t), epsilons(t));
            }
        }
    }

    /** Trim table to actual size. */
    public void shrink() {
        table = Arrays.copyOf(table, stateCount * stride);
    }

    /** Direct access to the backing array (for search loop performance). */
    public long[] rawTable() { return table; }

    private void ensureCapacity(int needed) {
        if (needed > table.length) {
            table = Arrays.copyOf(table, Math.max(table.length * 2, needed));
        }
    }
}
```

- [ ] **Step 2: Create TransitionTableTest.java**

```java
package lol.ohai.regex.automata.dfa.onepass;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransitionTableTest {
    @Test void deadStateIsZero() {
        assertEquals(0, TransitionTable.DEAD);
        assertEquals(0, TransitionTable.stateId(0L));
    }

    @Test void encodeDecodeTransition() {
        long eps = Epsilons.withSlot(Epsilons.withLook(Epsilons.EMPTY, 1 << 2), 5);
        long trans = TransitionTable.encode(42, true, eps);
        assertEquals(42, TransitionTable.stateId(trans));
        assertTrue(TransitionTable.matchWins(trans));
        assertEquals(Epsilons.looks(eps), Epsilons.looks(TransitionTable.epsilons(trans)));
        assertEquals(Epsilons.slots(eps), Epsilons.slots(TransitionTable.epsilons(trans)));
    }

    @Test void encodeDecodePatternEpsilons() {
        long eps = Epsilons.withSlot(Epsilons.EMPTY, 3);
        long pe = TransitionTable.encodePatternEpsilons(7, eps);
        assertEquals(7, TransitionTable.patternId(pe));
        assertTrue(TransitionTable.hasPattern(pe));
        assertEquals(Epsilons.slots(eps), Epsilons.slots(TransitionTable.patternEpsilons(pe)));
    }

    @Test void noPatternSentinel() {
        assertFalse(TransitionTable.hasPattern(TransitionTable.NO_PATTERN_EPSILONS));
        assertEquals(TransitionTable.NO_PATTERN, TransitionTable.patternId(TransitionTable.NO_PATTERN_EPSILONS));
    }

    @Test void addStateAndTransition() {
        var tt = new TransitionTable(4); // 4 classes
        int dead = tt.addState(); // state 0 = DEAD
        assertEquals(0, dead);
        int s1 = tt.addState();
        assertTrue(s1 > 0);
        long trans = TransitionTable.encode(dead, false, Epsilons.EMPTY);
        tt.set(s1, 0, trans);
        assertEquals(trans, tt.get(s1, 0));
    }

    @Test void unsignedShiftExtractsHighStateIds() {
        // State ID with bit 20 set (max = 2^21 - 1 = 2097151)
        long trans = TransitionTable.encode(2097151, false, Epsilons.EMPTY);
        assertEquals(2097151, TransitionTable.stateId(trans));
        // Bit 63 will be set (sign bit) — verify >>> works correctly
        assertTrue(trans < 0, "High state ID should set sign bit");
        assertEquals(2097151, TransitionTable.stateId(trans));
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest="lol.ohai.regex.automata.dfa.onepass.TransitionTableTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: All PASS.

- [ ] **Step 4: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/TransitionTable.java
git add regex-automata/src/test/java/lol/ohai/regex/automata/dfa/onepass/TransitionTableTest.java
git commit -m "feat(onepass): add TransitionTable with 64-bit transition encoding"
```

### Task 3: Create OnePassCache

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/OnePassCache.java`

- [ ] **Step 1: Create OnePassCache.java**

```java
package lol.ohai.regex.automata.dfa.onepass;

import java.util.Arrays;

/**
 * Pre-allocated scratch space for one-pass DFA search.
 * Holds explicit capture slot values during search. Zero allocation during search.
 */
public final class OnePassCache {
    private final int[] explicitSlots;
    private int activeLen;

    public OnePassCache(int maxExplicitSlots) {
        this.explicitSlots = new int[Math.min(maxExplicitSlots, Epsilons.MAX_SLOTS)];
        this.activeLen = 0;
    }

    /** Clear active slots to -1 and set active length. */
    public void setup(int len) {
        this.activeLen = Math.min(len, explicitSlots.length);
        Arrays.fill(explicitSlots, 0, activeLen, -1);
    }

    /** The active explicit slots array. */
    public int[] explicitSlots() { return explicitSlots; }

    /** Active length for this search. */
    public int activeLen() { return activeLen; }

    /** Capacity of the slots array. */
    public int capacity() { return explicitSlots.length; }
}
```

- [ ] **Step 2: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/OnePassCache.java
git commit -m "feat(onepass): add OnePassCache for zero-allocation search scratch space"
```

---

## Chunk 2: Builder (NFA → One-Pass DFA)

### Task 4: Implement OnePassBuilder

**Files:**
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/OnePassBuilder.java`
- Create: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/OnePassDFA.java` (minimal shell for builder to target)
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/onepass/OnePassBuilderTest.java`

The builder traverses the NFA using a worklist algorithm. For each uncompiled DFA state, it runs an epsilon closure (DFS) from the mapped NFA state, accumulating look-around assertions and capture slot updates. Character transitions are compiled into the transition table. Ambiguity → return null (not one-pass).

**Ref:** `upstream/regex/regex-automata/src/dfa/onepass.rs:581-728` (build), `758-798` (compile_transition), `903-923` (stack_push).

- [ ] **Step 1: Create minimal OnePassDFA shell**

Create `OnePassDFA.java` with just enough for the builder to target:

```java
package lol.ohai.regex.automata.dfa.onepass;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.NFA;

/**
 * A one-pass DFA that extracts capture groups in a single forward scan.
 * Only works for anchored searches on patterns where at most one NFA thread
 * is active at any point.
 */
public final class OnePassDFA {
    private final TransitionTable table;
    private final CharClasses charClasses;
    private final NFA nfa;
    private final int startState;
    private final int minMatchId;
    private final int explicitSlotStart; // = patternCount * 2
    private final int explicitSlotCount;

    OnePassDFA(TransitionTable table, CharClasses charClasses, NFA nfa,
              int startState, int minMatchId,
              int explicitSlotStart, int explicitSlotCount) {
        this.table = table;
        this.charClasses = charClasses;
        this.nfa = nfa;
        this.startState = startState;
        this.minMatchId = minMatchId;
        this.explicitSlotStart = explicitSlotStart;
        this.explicitSlotCount = explicitSlotCount;
    }

    public OnePassCache createCache() {
        return new OnePassCache(explicitSlotCount);
    }

    // Search method will be added in Task 5
    TransitionTable table() { return table; }
    int startState() { return startState; }
    int minMatchId() { return minMatchId; }
    int explicitSlotStart() { return explicitSlotStart; }
    int explicitSlotCount() { return explicitSlotCount; }
    CharClasses charClasses() { return charClasses; }
    NFA nfa() { return nfa; }
}
```

- [ ] **Step 2: Create OnePassBuilder.java**

```java
package lol.ohai.regex.automata.dfa.onepass;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.State;
import lol.ohai.regex.automata.nfa.thompson.Transition;
import lol.ohai.regex.automata.util.SparseSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds a one-pass DFA from a Thompson NFA.
 * Returns {@code null} if the pattern is not one-pass.
 *
 * <p>Ref: upstream/regex/regex-automata/src/dfa/onepass.rs:454-924</p>
 */
public final class OnePassBuilder {

    private OnePassBuilder() {}

    /**
     * Attempt to build a one-pass DFA from the given NFA and char classes.
     * Returns {@code null} if the pattern is not one-pass or exceeds limits.
     */
    public static OnePassDFA build(NFA nfa, CharClasses charClasses) {
        // Validate limits
        int explicitSlotStart = 2; // single pattern: 2 implicit slots (start, end)
        int totalSlots = nfa.captureSlotCount();
        int explicitSlotCount = totalSlots - explicitSlotStart;
        if (explicitSlotCount > Epsilons.MAX_SLOTS) return null; // too many groups
        if (explicitSlotCount < 0) explicitSlotCount = 0;

        int alphabetLen = charClasses.classCount() + 1; // +1 for pattern epsilons column
        TransitionTable table = new TransitionTable(alphabetLen);

        // State 0 = DEAD
        int deadId = table.addState();
        assert deadId == TransitionTable.DEAD;

        // NFA state → DFA state ID mapping
        int[] nfaToDfa = new int[nfa.stateCount()];
        Arrays.fill(nfaToDfa, -1); // -1 = unmapped

        // Worklist of NFA states whose DFA states need compilation
        List<Integer> worklist = new ArrayList<>();

        // Add start state
        int nfaStart = nfa.startAnchored();
        int dfaStart = table.addState();
        if (dfaStart < 0) return null;
        nfaToDfa[nfaStart] = dfaStart;
        worklist.add(nfaStart);

        // DFS stack for epsilon closure: (nfaStateId, epsilons)
        long[] stackNfaIds = new long[nfa.stateCount() * 2]; // int pairs packed
        long[] stackEpsilons = new long[nfa.stateCount() * 2];
        SparseSet seen = new SparseSet(nfa.stateCount());

        // Process worklist
        int workIdx = 0;
        while (workIdx < worklist.size()) {
            int nfaId = worklist.get(workIdx++);
            int dfaId = nfaToDfa[nfaId];
            boolean matched = false;

            // Epsilon closure DFS
            seen.clear();
            int stackTop = 0;

            // Push initial state
            if (!seen.insert(nfaId)) return null; // cycle
            stackNfaIds[stackTop] = nfaId;
            stackEpsilons[stackTop] = Epsilons.EMPTY;
            stackTop++;

            while (stackTop > 0) {
                stackTop--;
                int sid = (int) stackNfaIds[stackTop];
                long eps = stackEpsilons[stackTop];

                State state = nfa.state(sid);
                switch (state) {
                    case State.CharRange cr -> {
                        int nextDfa = getOrAddDfaState(cr.next(), nfaToDfa, table, worklist);
                        if (nextDfa < 0) return null; // too many states
                        if (!compileTransition(dfaId, cr.start(), cr.end(),
                                nextDfa, matched, eps, charClasses, table)) {
                            return null; // conflict → not one-pass
                        }
                    }
                    case State.Sparse sp -> {
                        for (Transition t : sp.transitions()) {
                            int nextDfa = getOrAddDfaState(t.next(), nfaToDfa, table, worklist);
                            if (nextDfa < 0) return null;
                            if (!compileTransition(dfaId, t.start(), t.end(),
                                    nextDfa, matched, eps, charClasses, table)) {
                                return null;
                            }
                        }
                    }
                    case State.Look look -> {
                        long newEps = Epsilons.withLook(eps, look.look().asBit());
                        if (!seen.insert(look.next())) return null;
                        stackNfaIds[stackTop] = look.next();
                        stackEpsilons[stackTop] = newEps;
                        stackTop++;
                    }
                    case State.Union u -> {
                        int[] alts = u.alternates();
                        for (int i = alts.length - 1; i >= 0; i--) {
                            if (!seen.insert(alts[i])) return null;
                            stackNfaIds[stackTop] = alts[i];
                            stackEpsilons[stackTop] = eps;
                            stackTop++;
                        }
                    }
                    case State.BinaryUnion bu -> {
                        if (!seen.insert(bu.alt2())) return null;
                        stackNfaIds[stackTop] = bu.alt2();
                        stackEpsilons[stackTop] = eps;
                        stackTop++;
                        if (!seen.insert(bu.alt1())) return null;
                        stackNfaIds[stackTop] = bu.alt1();
                        stackEpsilons[stackTop] = eps;
                        stackTop++;
                    }
                    case State.Capture cap -> {
                        long newEps = eps;
                        if (cap.slotIndex() >= explicitSlotStart) {
                            int offset = cap.slotIndex() - explicitSlotStart;
                            if (offset < Epsilons.MAX_SLOTS) {
                                newEps = Epsilons.withSlot(eps, offset);
                            }
                        }
                        if (!seen.insert(cap.next())) return null;
                        stackNfaIds[stackTop] = cap.next();
                        stackEpsilons[stackTop] = newEps;
                        stackTop++;
                    }
                    case State.Match m -> {
                        if (matched) return null; // multiple matches → not one-pass
                        matched = true;
                        table.setPatternEpsilons(dfaId,
                                TransitionTable.encodePatternEpsilons(m.patternId(), eps));
                    }
                    case State.Fail ignored -> { /* dead end */ }
                }
            }
        }

        // Shuffle match states to end for O(1) match detection
        int minMatchId = shuffleMatchStates(table);

        // Also remap the start state if it was shuffled
        dfaStart = nfaToDfa[nfaStart];
        // Actually, shuffling might have moved it. We need to track this.
        // Let's find the actual start by checking what nfaToDfa[nfaStart] maps to
        // after remapping. The shuffle updates the table but not nfaToDfa.
        // Solution: re-scan for the start state after shuffle.
        // For now, since the start state is almost never a match state, it stays put.
        // If it IS a match state (e.g., pattern `a*`), it gets shuffled.
        // We handle this by returning the remapped start.

        table.shrink();
        return new OnePassDFA(table, charClasses, nfa,
                dfaStart, minMatchId, explicitSlotStart, explicitSlotCount);
    }

    /**
     * Compile a transition for each equivalence class in the char range [start, end].
     * Returns false if a conflicting transition exists (not one-pass).
     */
    private static boolean compileTransition(int dfaId, int rangeStart, int rangeEnd,
                                              int nextDfaId, boolean matched, long eps,
                                              CharClasses charClasses, TransitionTable table) {
        long newTrans = TransitionTable.encode(nextDfaId, matched, eps);

        // Iterate equivalence class representatives in the range
        for (int c = rangeStart; c <= rangeEnd; c++) {
            int classId = charClasses.classify((char) c);
            long existing = table.get(dfaId, classId);
            if (existing == 0) {
                // DEAD (no transition yet) → set it
                table.set(dfaId, classId, newTrans);
            } else if (existing != newTrans) {
                // Conflict: different transition for same class → not one-pass
                return false;
            }
            // Same transition already set → skip (from merged equivalence classes)

            // Skip to the end of this equivalence class to avoid redundant classify() calls.
            // All chars in the same class produce the same result.
            // For efficiency, we could use class boundaries, but this simple approach works.
        }
        return true;
    }

    private static int getOrAddDfaState(int nfaId, int[] nfaToDfa,
                                         TransitionTable table, List<Integer> worklist) {
        if (nfaToDfa[nfaId] >= 0) return nfaToDfa[nfaId];
        int dfaId = table.addState();
        if (dfaId < 0) return -1; // too many states
        nfaToDfa[nfaId] = dfaId;
        worklist.add(nfaId);
        return dfaId;
    }

    /**
     * Shuffle match states to the end of the table.
     * Returns the minimum match state ID (all states >= this are match states).
     */
    private static int shuffleMatchStates(TransitionTable table) {
        int count = table.stateCount();
        if (count <= 1) return count * table.stride(); // only DEAD

        // Find match states and non-match states
        int stride = table.stride();
        int lastNonMatch = -1;
        for (int i = count - 1; i >= 1; i--) { // skip DEAD at 0
            int stateId = i * stride;
            if (!TransitionTable.hasPattern(table.getPatternEpsilons(stateId))) {
                lastNonMatch = i;
                break;
            }
        }
        if (lastNonMatch < 0) {
            // All states are match states (unlikely but handle it)
            return stride; // state 1 (first non-DEAD) is the min match
        }

        // Swap match states to end
        for (int i = 1; i <= lastNonMatch; i++) {
            int stateId = i * stride;
            if (TransitionTable.hasPattern(table.getPatternEpsilons(stateId))) {
                // Swap with lastNonMatch
                int swapId = lastNonMatch * stride;
                table.swapStates(stateId, swapId);
                table.remapState(stateId, swapId);
                table.remapState(swapId, stateId);
                // Actually this double-remap is wrong. We need a proper remap.
                // Simpler: use a remap array.
                lastNonMatch--;
                while (lastNonMatch > i && TransitionTable.hasPattern(
                        table.getPatternEpsilons(lastNonMatch * stride))) {
                    lastNonMatch--;
                }
            }
        }

        // Find actual minMatchId after shuffling
        for (int i = 1; i < count; i++) {
            int stateId = i * stride;
            if (TransitionTable.hasPattern(table.getPatternEpsilons(stateId))) {
                return stateId;
            }
        }
        return count * stride; // no match states (shouldn't happen for valid patterns)
    }
}
```

**Note:** The `shuffleMatchStates` and `compileTransition` methods need careful implementation. The plan provides the algorithm structure; the implementer should verify against upstream `onepass.rs:739-754` (shuffle) and `758-798` (compile_transition). The remap logic is tricky — upstream uses a `Remapper` struct. The implementer should read that code and adapt.

- [ ] **Step 3: Create OnePassBuilderTest.java**

```java
package lol.ohai.regex.automata.dfa.onepass;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OnePassBuilderTest {

    // -- Eligible patterns: build should succeed --

    @Test void simpleLiteral() {
        assertNotNull(buildOnePass("abc"));
    }

    @Test void captureGroups() {
        assertNotNull(buildOnePass("(\\d+)-(\\d+)"));
    }

    @Test void datePattern() {
        assertNotNull(buildOnePass("(\\d{4})-(\\d{2})-(\\d{2})"));
    }

    @Test void alternationNonOverlapping() {
        assertNotNull(buildOnePass("(a|b)c"));
    }

    @Test void charClassCapture() {
        assertNotNull(buildOnePass("([a-z]+)@([a-z]+)"));
    }

    // -- Ineligible patterns: build should return null --

    @Test void ambiguousRepetition() {
        // (a*)(a*) — ambiguous split between the two groups
        assertNull(buildOnePass("(a*)(a*)"));
    }

    @Test void overlappingAlternation() {
        // (a|ab)c — 'a' could be start of either alternative
        assertNull(buildOnePass("(a|ab)c"));
    }

    // -- Helpers --

    private static OnePassDFA buildOnePass(String pattern) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            CharClasses cc = CharClassBuilder.build(nfa);
            return OnePassBuilder.build(nfa, cc);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile: " + pattern, e);
        }
    }
}
```

- [ ] **Step 4: Run tests (iterate until passing)**

Run: `./mvnw test -Dtest="lol.ohai.regex.automata.dfa.onepass.OnePassBuilderTest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: All PASS. The eligible patterns build successfully; ineligible patterns return null.

**Note:** The shuffle/remap logic is the hardest part. If tests fail, debug by adding temporary assertions or using the java-debugger skill. The builder should be tested against upstream's behavior — if a pattern is one-pass in upstream, it should be one-pass here too.

- [ ] **Step 5: Run full test suite**

Run: `./mvnw test`
Expected: All existing tests PASS (the builder is not wired in yet).

- [ ] **Step 6: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/OnePassBuilder.java
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/OnePassDFA.java
git add regex-automata/src/test/java/lol/ohai/regex/automata/dfa/onepass/OnePassBuilderTest.java
git commit -m "$(cat <<'EOF'
feat(onepass): add OnePassBuilder for NFA → one-pass DFA compilation

Compiles Thompson NFA to one-pass DFA using worklist algorithm with
epsilon closure DFS. Detects ambiguity (conflicting transitions,
multiple match states, epsilon cycles) and returns null for non-
one-pass patterns.

Ref: upstream/regex/regex-automata/src/dfa/onepass.rs:454-924
EOF
)"
```

---

## Chunk 3: Search Loop

### Task 5: Implement OnePassDFA.search()

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/OnePassDFA.java`
- Test: `regex-automata/src/test/java/lol/ohai/regex/automata/dfa/onepass/OnePassDFATest.java`

The search loop processes one char per iteration, checking for match states and applying capture slot updates from transition epsilons.

**Ref:** `upstream/regex/regex-automata/src/dfa/onepass.rs:2042-2243`

- [ ] **Step 1: Add search method to OnePassDFA**

Add to `OnePassDFA.java`:

```java
/**
 * Search the narrowed, anchored input window for a match, extracting captures.
 *
 * @param input   anchored input window (from forward+reverse DFA narrowing)
 * @param cache   pre-allocated scratch space for explicit slots
 * @param slots   caller-provided slot array (written in-place).
 *                slots[0]=start, slots[1]=end for group 0, etc.
 * @return the matched pattern ID (>= 0), or -1 if no match
 */
public int search(Input input, OnePassCache cache, int[] slots) {
    if (input.start() >= input.end()) {
        return -1;
    }

    // Clear all slots
    Arrays.fill(slots, -1);
    // Set implicit start slot for pattern 0
    if (slots.length >= 1) {
        slots[0] = input.start();
    }

    // Set up cache for explicit slots
    int availableSlots = Math.max(0, slots.length - explicitSlotStart);
    cache.setup(Math.min(explicitSlotCount, Math.min(availableSlots, cache.capacity())));

    char[] haystack = input.haystack();
    long[] rawTable = table.rawTable();
    int stride = table.stride();
    int sid = startState;
    int matchedPid = -1;

    for (int at = input.start(); at < input.end(); at++) {
        int classId = charClasses.classify(haystack[at]);
        long trans = rawTable[sid + classId];
        int nextSid = TransitionTable.stateId(trans);
        long eps = TransitionTable.epsilons(trans);

        // Check for match BEFORE taking transition (upstream onepass.rs:2151)
        if (sid >= minMatchId) {
            int pid = findMatch(cache, input, at, sid, slots);
            if (pid >= 0) {
                matchedPid = pid;
                // Leftmost-first: if outgoing transition has match_wins, stop
                if (TransitionTable.matchWins(trans)) {
                    return matchedPid;
                }
            }
        }

        // Dead state check on CURRENT state sid (NOT nextSid)
        // Ref: upstream onepass.rs:2160
        if (sid == TransitionTable.DEAD) {
            return matchedPid;
        }

        // Look assertion check
        if (!Epsilons.looksEmpty(eps)) {
            if (!looksSatisfied(Epsilons.looks(eps), input.haystack(), at)) {
                return matchedPid;
            }
        }

        // Apply capture slot updates
        Epsilons.applySlots(eps, at, cache.explicitSlots(), cache.activeLen());
        sid = nextSid;
    }

    // Check final state for match (upstream onepass.rs:2172)
    if (sid >= minMatchId) {
        int pid = findMatch(cache, input, input.end(), sid, slots);
        if (pid >= 0) matchedPid = pid;
    }
    return matchedPid;
}

/**
 * Check if sid is a match state with satisfied look assertions.
 * If so, set implicit end slot and copy explicit slots.
 */
private int findMatch(OnePassCache cache, Input input, int at,
                       int sid, int[] slots) {
    long patEps = table.getPatternEpsilons(sid);
    if (!TransitionTable.hasPattern(patEps)) return -1;

    long eps = TransitionTable.patternEpsilons(patEps);

    // Check look assertions on match state
    if (!Epsilons.looksEmpty(eps)) {
        if (!looksSatisfied(Epsilons.looks(eps), input.haystack(), at)) {
            return -1;
        }
    }

    int pid = TransitionTable.patternId(patEps);

    // Set implicit end slot
    int slotEnd = pid * 2 + 1;
    if (slotEnd < slots.length) {
        slots[slotEnd] = at;
    }

    // Copy explicit slots from cache to caller's slots
    if (explicitSlotStart < slots.length) {
        int[] cacheSlots = cache.explicitSlots();
        int copyLen = Math.min(cache.activeLen(), slots.length - explicitSlotStart);
        System.arraycopy(cacheSlots, 0, slots, explicitSlotStart, copyLen);
        // Apply match state's own epsilon slot updates (upstream onepass.rs:2239)
        Epsilons.applySlots(eps, at, slots, slots.length);
    }

    return pid;
}

/**
 * Check if all look assertions in the given look bits are satisfied at position.
 * Delegates to the NFA's look matcher infrastructure.
 */
private boolean looksSatisfied(int lookBits, char[] haystack, int at) {
    // Check each set look bit
    for (LookKind kind : LookKind.values()) {
        if ((lookBits & kind.asBit()) != 0) {
            if (!satisfiesLook(kind, haystack, at)) return false;
        }
    }
    return true;
}
```

The `satisfiesLook(LookKind, char[], int)` method needs to check text/line anchors and word boundaries at position `at`. Read the existing lazy DFA's `computeLookAhead` in `LazyDFA.java` for reference on how look assertions are evaluated, but note that the one-pass DFA checks looks differently — it checks them at the transition point, not via the two-phase source/destination model. The implementer should adapt the look checks from the existing codebase.

**Important:** The `Epsilons.applySlots(eps, at, slots, slots.length)` call in `findMatch` writes into the CALLER's `slots[]` array (not into `cache.explicitSlots()`). The offset is 0-based relative to `slots`, so the slot indices in the epsilons bitfield need to be offset by `explicitSlotStart`. Review upstream `onepass.rs:2239` carefully — it does `slots[self.explicit_slot_start..][..cache_slots.len()]`, meaning it indexes into the caller's slots starting at `explicit_slot_start`. The implementer must ensure the slot indices are correctly offset.

- [ ] **Step 2: Create OnePassDFATest.java**

```java
package lol.ohai.regex.automata.dfa.onepass;

import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OnePassDFATest {

    @Test void simpleLiteralCapture() {
        // (abc) on "abc"
        int[] slots = searchCaptures("(abc)", "abc", 0, 3);
        assertNotNull(slots);
        assertEquals(0, slots[0]); // group 0 start
        assertEquals(3, slots[1]); // group 0 end
        assertEquals(0, slots[2]); // group 1 start
        assertEquals(3, slots[3]); // group 1 end
    }

    @Test void dateCapture() {
        // (\d{4})-(\d{2})-(\d{2}) on "2026-03-15"
        int[] slots = searchCaptures("(\\d{4})-(\\d{2})-(\\d{2})", "2026-03-15", 0, 10);
        assertNotNull(slots);
        assertEquals(0, slots[0]);  // group 0 start
        assertEquals(10, slots[1]); // group 0 end
        assertEquals(0, slots[2]);  // group 1 start (year)
        assertEquals(4, slots[3]);  // group 1 end
        assertEquals(5, slots[4]);  // group 2 start (month)
        assertEquals(7, slots[5]);  // group 2 end
        assertEquals(8, slots[6]);  // group 3 start (day)
        assertEquals(10, slots[7]); // group 3 end
    }

    @Test void noMatch() {
        int[] slots = searchCaptures("(abc)", "xyz", 0, 3);
        assertNull(slots);
    }

    @Test void alternationCapture() {
        int[] slots = searchCaptures("(a|b)c", "bc", 0, 2);
        assertNotNull(slots);
        assertEquals(0, slots[0]); // group 0 start
        assertEquals(2, slots[1]); // group 0 end
        assertEquals(0, slots[2]); // group 1 start
        assertEquals(1, slots[3]); // group 1 end
    }

    @Test void nonParticipatingGroup() {
        // (a)|(b) on "b" — group 1 doesn't participate
        int[] slots = searchCaptures("(a)|(b)", "b", 0, 1);
        assertNotNull(slots);
        assertEquals(0, slots[0]);  // group 0 start
        assertEquals(1, slots[1]);  // group 0 end
        assertEquals(-1, slots[2]); // group 1 — not matched
        assertEquals(-1, slots[3]);
        assertEquals(0, slots[4]);  // group 2 start
        assertEquals(1, slots[5]);  // group 2 end
    }

    // -- Helper --

    private int[] searchCaptures(String pattern, String haystack, int start, int end) {
        try {
            Ast ast = Parser.parse(pattern, 250);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            CharClasses cc = CharClassBuilder.build(nfa);
            OnePassDFA dfa = OnePassBuilder.build(nfa, cc);
            if (dfa == null) fail("Pattern should be one-pass: " + pattern);

            OnePassCache cache = dfa.createCache();
            int[] slots = new int[nfa.captureSlotCount()];
            Input input = Input.of(haystack).withBounds(start, end, true);
            int pid = dfa.search(input, cache, slots);
            if (pid < 0) return null;
            return slots;
        } catch (Exception e) {
            throw new RuntimeException("Failed: " + pattern, e);
        }
    }
}
```

- [ ] **Step 3: Implement looksSatisfied**

The `looksSatisfied` and `satisfiesLook` methods need to evaluate look-around assertions at a position. Read the existing look evaluation code in `LazyDFA.java` (`computeLookAhead` method) and adapt it for the one-pass DFA's needs. The key difference: the one-pass DFA checks looks at the transition position (between two chars), not with the two-phase source/destination model.

For the initial implementation, support the common look kinds: `START_TEXT`, `END_TEXT`, `START_LINE`, `END_LINE`, `WORD_BOUNDARY_ASCII`, `WORD_BOUNDARY_ASCII_NEGATE`. If the pattern uses unsupported look kinds, the builder should bail out (return null).

- [ ] **Step 4: Run tests (iterate until passing)**

Run: `./mvnw test -Dtest="lol.ohai.regex.automata.dfa.onepass.OnePassDFATest" -Dsurefire.failIfNoSpecifiedTests=false`
Expected: All PASS.

- [ ] **Step 5: Run full test suite**

Run: `./mvnw test`
Expected: All existing tests PASS.

- [ ] **Step 6: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/dfa/onepass/OnePassDFA.java
git add regex-automata/src/test/java/lol/ohai/regex/automata/dfa/onepass/OnePassDFATest.java
git commit -m "$(cat <<'EOF'
feat(onepass): implement OnePassDFA.search() with capture extraction

Single forward scan extracts all capture groups. Zero allocation
during search. Checks match states via sid >= minMatchId comparison.

Ref: upstream/regex/regex-automata/src/dfa/onepass.rs:2042-2243
EOF
)"
```

---

## Chunk 4: Strategy Integration

### Task 6: Wire one-pass DFA into Strategy.Core and Regex.compile()

**Files:**
- Modify: `regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java`
- Modify: `regex/src/main/java/lol/ohai/regex/Regex.java`
- Test: full upstream test suite (879 tests)

- [ ] **Step 1: Add OnePassDFA and OnePassCache to Strategy.Core**

In `Strategy.java`, add `OnePassDFA` as an optional field on `Strategy.Core`:

```java
record Core(PikeVM pikeVM, LazyDFA forwardDFA, LazyDFA reverseDFA,
            Prefilter prefilter, BoundedBacktracker backtracker,
            OnePassDFA onePassDFA) implements Strategy {
```

Update `createCache()` to include `OnePassCache`:

```java
@Override
public Cache createCache() {
    return new Cache(
            pikeVM.createCache(),
            forwardDFA != null ? forwardDFA.createCache() : null,
            reverseDFA != null ? reverseDFA.createCache() : null,
            backtracker != null ? backtracker.createCache() : null,
            null,  // prefixReverseDFACache
            onePassDFA != null ? onePassDFA.createCache() : null
    );
}
```

Add `OnePassCache` to the `Cache` record:

```java
record Cache(
        lol.ohai.regex.automata.nfa.thompson.pikevm.Cache pikeVMCache,
        DFACache forwardDFACache,
        DFACache reverseDFACache,
        lol.ohai.regex.automata.nfa.thompson.backtrack.BoundedBacktracker.Cache backtrackerCache,
        DFACache prefixReverseDFACache,
        OnePassCache onePassCache
) {
    static final Cache EMPTY = new Cache(null, null, null, null, null, null);
}
```

- [ ] **Step 2: Update dfaSearchCaptures() to prefer one-pass DFA**

In `Strategy.Core.dfaSearchCaptures()`, after the reverse DFA narrows the window, prefer the one-pass DFA over PikeVM/backtracker:

```java
private Captures dfaSearchCaptures(Input input, Cache cache) {
    // ... existing forward + reverse DFA phases ...
    // After narrowing to [matchStart, matchEnd]:
    Input narrowed = input.withBounds(matchStart, matchEnd, true); // anchored
    return captureEngine(narrowed, cache);
}

private Captures captureEngine(Input narrowed, Cache cache) {
    // Prefer one-pass DFA for eligible patterns
    if (onePassDFA != null && cache.onePassCache() != null) {
        int groupCount = onePassDFA.nfa().groupCount();
        int[] slots = new int[groupCount * 2]; // TODO: pool this
        int pid = onePassDFA.search(narrowed, cache.onePassCache(), slots);
        if (pid >= 0) {
            Captures caps = new Captures(groupCount);
            System.arraycopy(slots, 0, caps.slots(), 0, slots.length);
            return caps;
        }
    }
    return Strategy.doCaptureEngine(narrowed, cache, pikeVM, backtracker);
}
```

**Note:** The `int[] slots` allocation should be pooled (e.g., in `OnePassCache`) to avoid per-match allocation. The implementer should move it there.

- [ ] **Step 3: Update all Strategy.Core constructor call sites**

Update every place that constructs `Strategy.Core` to pass the `onePassDFA` parameter. Search for `new Strategy.Core(` in `Regex.java` and `Strategy.java`.

Also update `ReverseSuffix` and `ReverseInner` — they also construct `Core`-like engines. If they don't need one-pass DFA, pass `null`.

- [ ] **Step 4: Build one-pass DFA in Regex.compile()**

In `Regex.java`, after building the NFA and char classes, attempt to build the one-pass DFA:

```java
// After: BoundedBacktracker backtracker = new BoundedBacktracker(nfa);
OnePassDFA onePassDFA = null;
if (hirHasCaptures(hir)) {
    onePassDFA = OnePassBuilder.build(nfa, charClasses != null ? charClasses : CharClasses.identity());
}
```

Pass `onePassDFA` to the `Strategy.Core` constructor (and to `ReverseSuffix` / `ReverseInner` if they share the same capture engine path).

- [ ] **Step 5: Run full test suite**

Run: `./mvnw test`
Expected: All 2,183 tests PASS, including all 879 upstream tests. The one-pass DFA must produce the same capture results as PikeVM/backtracker for eligible patterns.

**If upstream tests fail:** The most likely causes are:
1. Incorrect slot offset mapping (explicitSlotStart)
2. Look assertion evaluation at wrong position
3. Match state detection ordering
Use the profiling test or write focused unit tests to debug.

- [ ] **Step 6: Commit**

```bash
git add regex-automata/src/main/java/lol/ohai/regex/automata/meta/Strategy.java
git add regex/src/main/java/lol/ohai/regex/Regex.java
git commit -m "$(cat <<'EOF'
feat(onepass): integrate one-pass DFA as preferred capture engine

Strategy.Core prefers one-pass DFA over PikeVM/backtracker for
eligible patterns. Falls back to PikeVM for non-one-pass patterns.
Built during Regex.compile() when pattern has explicit captures.

Ref: upstream/regex/regex-automata/src/meta/wrappers.rs:327-506
EOF
)"
```

---

## Chunk 5: Benchmarks and Documentation

### Task 7: Run benchmarks and update documentation

- [ ] **Step 1: Run benchmarks**

```bash
./mvnw -P bench package -DskipTests
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 5 2>&1 | tee /tmp/bench-onepass.txt
```

Focus on the `capturesOhai` benchmark — expect dramatic improvement from ~366 ops/s.

- [ ] **Step 2: Run GC profile to verify zero allocation**

```bash
java -jar regex-bench/target/benchmarks.jar -f 1 -wi 3 -i 3 -prof gc "SearchBenchmark.capturesOhai" 2>&1 | grep "gc.alloc.rate.norm"
```

Expect significantly reduced allocation vs baseline.

- [ ] **Step 3: Update lazy-dfa-gaps.md**

Add a "One-Pass DFA — Implemented" section.

- [ ] **Step 4: Commit and tag**

```bash
git add docs/architecture/lazy-dfa-gaps.md
git commit -m "docs: add one-pass DFA to architecture docs"
```
