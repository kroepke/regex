package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.automata.util.SparseSet;
import java.util.*;

public final class DFACache {
    public static final int UNKNOWN = 0;
    public static final int MATCH_FLAG = 0x8000_0000;

    public static int dead(int stride) { return stride; }
    public static int quit(int stride) { return stride * 2; }

    private final int stride;
    private int[] transTable;
    private final List<StateContent> states;
    private final Map<StateContent, Integer> stateMap;

    int startUnanchored = UNKNOWN;
    int startAnchored = UNKNOWN;

    int[] startStates;  // [Start.COUNT * 2] slots: [0..4] unanchored, [5..9] anchored

    int closureLookNeed;  // accumulates lookNeed during epsilon closure, reset by caller

    final SparseSet nfaStateSet;
    int[] closureStack;  // may grow during epsilon closure

    private int clearCount;
    long charsSearched;
    int statesCreated;

    private final int maxStates;
    private Captures scratchCaptures;

    // Acceleration: per-state boolean[128] ASCII escape tables.
    // escapeTable[c] == true means char c leaves the self-loop at that state.
    // null entry = not analyzed or not accelerable.
    private boolean[][] accelTables;

    public DFACache(CharClasses charClasses, int capacityBytes) {
        this(charClasses, capacityBytes, 0);
    }

    public DFACache(CharClasses charClasses, int capacityBytes, int nfaStateCount) {
        this.stride = charClasses.stride();
        int bytesPerState = stride * 4;
        this.maxStates = Math.max(4, capacityBytes / bytesPerState);
        int initialCapacity = Math.min(maxStates, 64);
        this.transTable = new int[initialCapacity * stride];
        this.states = new ArrayList<>();
        this.stateMap = new HashMap<>();
        this.nfaStateSet = new SparseSet(Math.max(1, nfaStateCount));
        this.closureStack = new int[Math.max(1, nfaStateCount) * 2];
        this.startStates = new int[Start.COUNT * 2];
        this.accelTables = new boolean[initialCapacity][];
        initSentinels();
    }

    private void initSentinels() {
        states.add(new StateContent(new int[0], false));
        int deadId = dead(stride);
        ensureCapacity(deadId + stride);
        Arrays.fill(transTable, deadId, deadId + stride, deadId);
        states.add(new StateContent(new int[0], false));
        int quitId = quit(stride);
        ensureCapacity(quitId + stride);
        Arrays.fill(transTable, quitId, quitId + stride, quitId);
        states.add(new StateContent(new int[0], false));
    }

    public int nextState(int stateId, int classId) {
        return transTable[stateId + classId];
    }

    public void setTransition(int stateId, int classId, int nextStateId) {
        transTable[stateId + classId] = nextStateId;
    }

    public int allocateState(StateContent content) {
        Integer existing = stateMap.get(content);
        if (existing != null) return existing;
        int rawId = states.size() * stride;
        int sid = content.isMatch() ? (rawId | MATCH_FLAG) : rawId;
        ensureCapacity(rawId + stride);
        states.add(content);
        stateMap.put(content, sid);
        statesCreated++;
        return sid;
    }

    public StateContent getState(int rawStateId) {
        return states.get(rawStateId / stride);
    }

    /** Returns a reusable Captures(1) instance, lazily created and cleared. */
    public Captures scratchCaptures() {
        if (scratchCaptures == null) {
            scratchCaptures = new Captures(1);
        } else {
            scratchCaptures.clear();
        }
        return scratchCaptures;
    }

    /** Returns the ASCII escape table for this state, or null if not accelerable. */
    public boolean[] accelTable(int stateId) {
        int idx = stateId / stride;
        return idx < accelTables.length ? accelTables[idx] : null;
    }

    /**
     * Analyze a state's cached transitions to determine if it's accelerable.
     * A state is accelerable if all known (non-UNKNOWN) transitions except 1-3
     * equivalence classes loop back to itself. Builds a boolean[128] escape table
     * for ASCII chars.
     */
    public void analyzeAcceleration(int stateId, int classCount, CharClasses charClasses) {
        int idx = stateId / stride;
        if (idx >= accelTables.length) return;
        if (accelTables[idx] != null) return; // already analyzed

        int rawSid = stateId & ~MATCH_FLAG;
        int dead = dead(stride);
        int quit = quit(stride);

        // Count non-self-loop, non-UNKNOWN classes
        int escapeCount = 0;
        int unknownCount = 0;
        for (int c = 0; c < classCount; c++) {
            int target = transTable[rawSid + c];
            if (target == UNKNOWN) {
                unknownCount++;
            } else {
                int rawTarget = target & ~MATCH_FLAG;
                if (rawTarget != rawSid) {
                    escapeCount++;
                }
            }
        }

        // Don't accelerate if too many escapes or too many unknowns
        // (unknowns mean the state isn't fully explored yet)
        if (escapeCount > 3 || unknownCount > classCount / 2) return;
        if (escapeCount == 0 && unknownCount == 0) return; // pure self-loop with nowhere to go

        // Build boolean[128] escape table for ASCII chars
        boolean[] table = new boolean[128];
        boolean anyEscape = false;
        for (int c = 0; c < 128; c++) {
            int classId = charClasses.classify((char) c);
            int target = transTable[rawSid + classId];
            if (target == UNKNOWN) {
                // Unknown transition — can't accelerate past this char
                table[c] = true;
                anyEscape = true;
            } else {
                int rawTarget = target & ~MATCH_FLAG;
                if (rawTarget != rawSid) {
                    table[c] = true;
                    anyEscape = true;
                }
            }
        }

        if (anyEscape) {
            accelTables[idx] = table;
        }
    }

    public int stateCount() { return states.size(); }
    public boolean isFull() { return states.size() >= maxStates; }
    public int clearCount() { return clearCount; }
    public int stride() { return stride; }

    public int getStartState(Start start, boolean anchored) {
        int idx = anchored ? Start.COUNT + start.ordinal() : start.ordinal();
        return startStates[idx];
    }

    public void setStartState(Start start, boolean anchored, int sid) {
        int idx = anchored ? Start.COUNT + start.ordinal() : start.ordinal();
        startStates[idx] = sid;
    }

    public void clear(StateContent preserve) {
        Arrays.fill(transTable, 0);
        states.clear();
        stateMap.clear();
        initSentinels();
        if (preserve != null) {
            allocateState(preserve);
        }
        startUnanchored = UNKNOWN;
        startAnchored = UNKNOWN;
        Arrays.fill(startStates, UNKNOWN);
        clearCount++;
        charsSearched = 0;
        statesCreated = 0;
        Arrays.fill(accelTables, null);
    }

    private void ensureCapacity(int needed) {
        if (needed > transTable.length) {
            int newSize = Math.max(transTable.length * 2, needed);
            transTable = Arrays.copyOf(transTable, newSize);
            int stateSlots = newSize / stride;
            if (stateSlots > accelTables.length) {
                accelTables = Arrays.copyOf(accelTables, stateSlots);
            }
        }
    }
}
