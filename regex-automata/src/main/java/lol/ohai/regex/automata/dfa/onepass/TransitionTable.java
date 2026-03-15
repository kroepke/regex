package lol.ohai.regex.automata.dfa.onepass;

import java.util.Arrays;

/**
 * Dense transition table for the one-pass DFA.
 *
 * <p>Each transition entry is a packed {@code long}:
 * <pre>
 * Bits 63–43: State ID (21 bits, use >>> to extract)
 * Bit  42:    match_wins flag
 * Bits 41–0:  Epsilons (look assertions + slot updates)
 * </pre>
 *
 * <p>Each state row also stores a PatternEpsilons entry at offset
 * {@code alphabetLen} within the row:
 * <pre>
 * Bits 63–42: Pattern ID (22 bits, 0x3FFFFF = no pattern sentinel)
 * Bits 41–0:  Epsilons
 * </pre>
 *
 * <p>The table is stride-aligned: stride is the next power of 2 that is
 * {@code >= alphabetLen + 1} (the +1 accounts for the pattern epsilons column).
 */
public final class TransitionTable {

    /** Dead state ID — always state 0, all transitions lead back to dead. */
    public static final int DEAD = 0;

    /** Bit position of the state ID field in a transition entry. */
    public static final int STATE_ID_SHIFT = 43;

    /** Bit mask for the match_wins flag. */
    public static final long MATCH_WINS_BIT = 1L << 42;

    /** Mask for the epsilons portion (lower 42 bits). */
    public static final long EPSILONS_MASK = (1L << 42) - 1;

    /** Maximum state ID (21 bits). */
    public static final int MAX_STATE_ID = (1 << 21) - 1;

    /** Sentinel value for "no pattern" (22 bits all set). */
    public static final int NO_PATTERN = (1 << 22) - 1;

    private final int alphabetLen;
    private final int stride;
    private final int strideBits;
    private long[] table;
    private int stateCount;

    /**
     * Creates a new transition table.
     *
     * @param alphabetLen the number of equivalence classes (charClasses.classCount()),
     *                    not including the pattern epsilons column
     */
    public TransitionTable(int alphabetLen) {
        this.alphabetLen = alphabetLen;
        // Stride must be next power of 2 >= alphabetLen + 1
        // (the +1 is for the pattern epsilons column at offset alphabetLen)
        int minStride = alphabetLen + 1;
        this.strideBits = 32 - Integer.numberOfLeadingZeros(minStride - 1);
        this.stride = 1 << strideBits;
        this.table = new long[0];
        this.stateCount = 0;
    }

    // --- Transition encoding/decoding ---

    /**
     * Encodes a transition entry from its components.
     */
    public static long encode(int stateId, boolean matchWins, long epsilons) {
        long result = ((long) stateId << STATE_ID_SHIFT);
        if (matchWins) {
            result |= MATCH_WINS_BIT;
        }
        result |= (epsilons & EPSILONS_MASK);
        return result;
    }

    /**
     * Extracts the state ID from a transition entry.
     */
    public static int stateId(long trans) {
        return (int) (trans >>> STATE_ID_SHIFT);
    }

    /**
     * Returns {@code true} if the match_wins flag is set.
     */
    public static boolean matchWins(long trans) {
        return (trans & MATCH_WINS_BIT) != 0;
    }

    /**
     * Extracts the epsilons from a transition entry.
     */
    public static long epsilons(long trans) {
        return trans & EPSILONS_MASK;
    }

    // --- Pattern epsilons encoding/decoding ---

    /**
     * Encodes a pattern epsilons entry.
     *
     * <pre>
     * Bits 63–42: Pattern ID (22 bits)
     * Bits 41–0:  Epsilons
     * </pre>
     */
    public static long encodePatternEpsilons(int patternId, long epsilons) {
        return ((long) patternId << 42) | (epsilons & EPSILONS_MASK);
    }

    /**
     * Extracts the pattern ID from a pattern epsilons entry.
     */
    public static int patternId(long patEps) {
        return (int) (patEps >>> 42);
    }

    /**
     * Returns {@code true} if the pattern epsilons entry has a valid pattern
     * (i.e., the pattern ID is not the {@link #NO_PATTERN} sentinel).
     */
    public static boolean hasPattern(long patEps) {
        return patternId(patEps) != NO_PATTERN;
    }

    // --- Table operations ---

    /**
     * Allocates a new state row and returns its state ID.
     * The new row is zeroed (all transitions to DEAD, no pattern).
     *
     * @return the state ID of the new state (stride-aligned index)
     */
    public int addState() {
        int id = stateCount * stride;
        stateCount++;
        int requiredLen = stateCount * stride;
        if (requiredLen > table.length) {
            table = Arrays.copyOf(table, Math.max(requiredLen, table.length * 2));
        }
        // Initialize pattern epsilons to NO_PATTERN
        table[id + alphabetLen] = encodePatternEpsilons(NO_PATTERN, Epsilons.EMPTY);
        return id;
    }

    /**
     * Gets the transition entry for the given state and equivalence class.
     */
    public long get(int stateId, int classId) {
        return table[stateId + classId];
    }

    /**
     * Sets the transition entry for the given state and equivalence class.
     */
    public void set(int stateId, int classId, long trans) {
        table[stateId + classId] = trans;
    }

    /**
     * Gets the pattern epsilons entry for the given state.
     */
    public long getPatternEpsilons(int stateId) {
        return table[stateId + alphabetLen];
    }

    /**
     * Sets the pattern epsilons entry for the given state.
     */
    public void setPatternEpsilons(int stateId, long patEps) {
        table[stateId + alphabetLen] = patEps;
    }

    /**
     * Swaps all data between two states (transitions + pattern epsilons).
     */
    public void swapStates(int a, int b) {
        for (int i = 0; i <= alphabetLen; i++) {
            long tmp = table[a + i];
            table[a + i] = table[b + i];
            table[b + i] = tmp;
        }
    }

    /**
     * Remaps all transitions that point to {@code oldId} so they point to
     * {@code newId} instead.
     */
    public void remapState(int oldId, int newId) {
        int len = stateCount * stride;
        for (int i = 0; i < len; i++) {
            // Skip pattern epsilons columns (they don't contain state IDs)
            int col = i & (stride - 1);
            if (col == alphabetLen) {
                continue;
            }
            if (col >= alphabetLen) {
                // padding columns
                continue;
            }
            long trans = table[i];
            if (stateId(trans) == oldId) {
                table[i] = encode(newId, matchWins(trans), epsilons(trans));
            }
        }
    }

    /**
     * Shrinks the backing array to exactly fit the current state count.
     */
    public void shrink() {
        int requiredLen = stateCount * stride;
        if (table.length > requiredLen) {
            table = Arrays.copyOf(table, requiredLen);
        }
    }

    /**
     * Returns the raw backing array. The caller must not modify it.
     */
    public long[] rawTable() {
        return table;
    }

    /**
     * Returns the number of states in the table.
     */
    public int stateCount() {
        return stateCount;
    }

    /**
     * Returns the stride (row width, power of 2).
     */
    public int stride() {
        return stride;
    }

    /**
     * Returns the alphabet length (number of equivalence classes, not
     * including the pattern epsilons column).
     */
    public int alphabetLen() {
        return alphabetLen;
    }
}
