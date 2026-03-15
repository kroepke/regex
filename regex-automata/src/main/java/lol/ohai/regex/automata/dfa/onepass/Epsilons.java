package lol.ohai.regex.automata.dfa.onepass;

/**
 * Packs look-around assertions and capture slot updates into the lower 42 bits
 * of a {@code long}.
 *
 * <pre>
 * Bits 41–18: Capture slot bitset (24 bits → max 12 explicit capture groups)
 * Bits 17–0:  Look assertion bits (18 bits, matches LookKind ordinals)
 * </pre>
 *
 * All methods are static and operate on raw {@code long} values.
 */
public final class Epsilons {

    /** The empty epsilon value: no assertions, no slot updates. */
    public static final long EMPTY = 0L;

    /** Maximum number of explicit capture slots (24 bits). */
    public static final int MAX_SLOTS = 24;

    private static final long LOOKS_MASK = (1L << 18) - 1;
    private static final int SLOTS_SHIFT = 18;
    private static final long SLOTS_MASK = ((1L << MAX_SLOTS) - 1) << SLOTS_SHIFT;

    private Epsilons() {}

    /**
     * Extracts the look-around assertion bits (lower 18 bits).
     */
    public static int looks(long eps) {
        return (int) (eps & LOOKS_MASK);
    }

    /**
     * Extracts the capture slot bitset (bits 41–18) as an int.
     */
    public static int slots(long eps) {
        return (int) ((eps >>> SLOTS_SHIFT) & ((1 << MAX_SLOTS) - 1));
    }

    /**
     * Returns a new epsilon value with the given look assertion bit set.
     *
     * @param eps     the current epsilon value
     * @param lookBit the look assertion bit index (0–17)
     * @return the updated epsilon value
     */
    public static long withLook(long eps, int lookBit) {
        return eps | (1L << lookBit);
    }

    /**
     * Returns a new epsilon value with the given capture slot bit set.
     *
     * @param eps       the current epsilon value
     * @param slotIndex the slot index (0–23)
     * @return the updated epsilon value
     */
    public static long withSlot(long eps, int slotIndex) {
        return eps | (1L << (slotIndex + SLOTS_SHIFT));
    }

    /**
     * Returns {@code true} if no look-around assertions are set.
     */
    public static boolean looksEmpty(long eps) {
        return (eps & LOOKS_MASK) == 0;
    }

    /**
     * For each set bit in the slot bitset, writes {@code position} to
     * {@code slotsOut[bitIndex]}, for bit indices less than {@code slotsLen}.
     *
     * @param eps      the epsilon value
     * @param position the input position to record
     * @param slotsOut the output slot array
     * @param slotsLen the number of active slots in slotsOut
     */
    public static void applySlots(long eps, int position, int[] slotsOut, int slotsLen) {
        int slotBits = slots(eps);
        while (slotBits != 0) {
            int bit = Integer.numberOfTrailingZeros(slotBits);
            if (bit < slotsLen) {
                slotsOut[bit] = position;
            }
            slotBits &= slotBits - 1; // clear lowest set bit
        }
    }
}
