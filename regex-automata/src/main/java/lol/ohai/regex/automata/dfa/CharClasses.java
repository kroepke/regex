package lol.ohai.regex.automata.dfa;

public final class CharClasses {
    private final byte[][] rows;
    private final int[] highIndex;
    private final int classCount;
    private final int stride;
    private final int strideShift;

    CharClasses(byte[][] rows, int[] highIndex, int classCount) {
        this.rows = rows;
        this.highIndex = highIndex;
        this.classCount = classCount;
        int alphabetSize = classCount + 1; // +1 for EOI
        this.stride = Integer.highestOneBit(alphabetSize - 1) << 1;
        this.strideShift = Integer.numberOfTrailingZeros(this.stride);
    }

    public int classify(char c) {
        return rows[highIndex[c >>> 8]][c & 0xFF] & 0xFF;
    }

    public int classCount() { return classCount; }
    public int eoiClass() { return classCount; }
    public int stride() { return stride; }
    public int strideShift() { return strideShift; }

    public static CharClasses identity() {
        byte[] singleRow = new byte[256];
        byte[][] rows = { singleRow };
        int[] highIndex = new int[256];
        return new CharClasses(rows, highIndex, 1);
    }
}
