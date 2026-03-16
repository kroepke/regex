package lol.ohai.regex.automata.dfa;

public final class CharClasses {
    static final byte FLAG_WORD = 1;
    static final byte FLAG_LF   = 2;
    static final byte FLAG_CR   = 4;
    static final byte FLAG_QUIT = 8;

    private final byte[][] rows;
    private final int[] highIndex;
    private final int classCount;
    private final int stride;
    private final int strideShift;
    private final byte[] classFlags;    // indexed by class ID, bit flags
    private final boolean hasQuitClasses;
    private final byte[] asciiTable;    // flat lookup for c < 128
    private final int[] classReps;      // representative char per class (for DenseDFA builder)

    CharClasses(byte[][] rows, int[] highIndex, int classCount, byte[] classFlags) {
        this(rows, highIndex, classCount, classFlags, null);
    }

    CharClasses(byte[][] rows, int[] highIndex, int classCount, byte[] classFlags, int[] classReps) {
        this.rows = rows;
        this.highIndex = highIndex;
        this.classCount = classCount;
        int alphabetSize = classCount + 1; // +1 for EOI
        this.stride = Integer.highestOneBit(alphabetSize - 1) << 1;
        this.strideShift = Integer.numberOfTrailingZeros(this.stride);
        this.classFlags = classFlags;
        boolean anyQuit = false;
        for (byte f : classFlags) {
            if ((f & FLAG_QUIT) != 0) { anyQuit = true; break; }
        }
        this.hasQuitClasses = anyQuit;
        this.asciiTable = new byte[128];
        System.arraycopy(rows[highIndex[0]], 0, asciiTable, 0, 128);
        this.classReps = classReps;
    }

    public int classify(char c) {
        if (c < 128) return asciiTable[c] & 0xFF;
        return rows[highIndex[c >>> 8]][c & 0xFF] & 0xFF;
    }

    /** Two-level classify, bypassing ASCII fast-path. Package-private for testing. */
    int classifyTwoLevel(char c) {
        return rows[highIndex[c >>> 8]][c & 0xFF] & 0xFF;
    }

    public int classCount() { return classCount; }
    public int eoiClass() { return classCount; }
    public int stride() { return stride; }
    public int strideShift() { return strideShift; }

    public boolean isWordClass(int classId) {
        return classId < classFlags.length && (classFlags[classId] & FLAG_WORD) != 0;
    }

    public boolean isLineLF(int classId) {
        return classId < classFlags.length && (classFlags[classId] & FLAG_LF) != 0;
    }

    public boolean isLineCR(int classId) {
        return classId < classFlags.length && (classFlags[classId] & FLAG_CR) != 0;
    }

    public boolean isQuitClass(int classId) {
        return classId < classFlags.length && (classFlags[classId] & FLAG_QUIT) != 0;
    }

    public boolean hasQuitClasses() {
        return hasQuitClasses;
    }

    /**
     * Returns a representative char for the given equivalence class, or -1
     * if no representative is available (e.g., for EOI class or when
     * class representatives were not provided at construction time).
     *
     * <p>Used by the DenseDFA builder to pass a concrete input char to
     * {@code computeNextState}, avoiding the broken class-based fallback
     * in {@code charInRange} for ranges that span multiple classes.</p>
     */
    public int classRepresentative(int classId) {
        if (classReps != null && classId >= 0 && classId < classReps.length) {
            return classReps[classId];
        }
        return -1;
    }

    public static CharClasses identity() {
        byte[] singleRow = new byte[256];
        byte[][] rows = { singleRow };
        int[] highIndex = new int[256];
        return new CharClasses(rows, highIndex, 1, new byte[1]);
    }
}
