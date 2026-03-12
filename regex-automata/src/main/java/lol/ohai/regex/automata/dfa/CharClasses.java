package lol.ohai.regex.automata.dfa;

public final class CharClasses {
    private final byte[][] rows;
    private final int[] highIndex;
    private final int classCount;
    private final int stride;
    private final int strideShift;
    private final boolean[] wordClass;  // indexed by class ID, true if class representative is word char
    private final boolean[] lineLF;     // true if class representative is \n
    private final boolean[] lineCR;     // true if class representative is \r
    private final boolean[] quitClass;  // indexed by class ID, true if class should trigger DFA quit

    CharClasses(byte[][] rows, int[] highIndex, int classCount,
                boolean[] wordClass, boolean[] lineLF, boolean[] lineCR,
                boolean[] quitClass) {
        this.rows = rows;
        this.highIndex = highIndex;
        this.classCount = classCount;
        int alphabetSize = classCount + 1; // +1 for EOI
        this.stride = Integer.highestOneBit(alphabetSize - 1) << 1;
        this.strideShift = Integer.numberOfTrailingZeros(this.stride);
        this.wordClass = wordClass;
        this.lineLF = lineLF;
        this.lineCR = lineCR;
        this.quitClass = quitClass;
    }

    public int classify(char c) {
        return rows[highIndex[c >>> 8]][c & 0xFF] & 0xFF;
    }

    public int classCount() { return classCount; }
    public int eoiClass() { return classCount; }
    public int stride() { return stride; }
    public int strideShift() { return strideShift; }

    public boolean isWordClass(int classId) {
        return wordClass != null && classId < wordClass.length && wordClass[classId];
    }

    public boolean isLineLF(int classId) {
        return lineLF != null && classId < lineLF.length && lineLF[classId];
    }

    public boolean isLineCR(int classId) {
        return lineCR != null && classId < lineCR.length && lineCR[classId];
    }

    public boolean isQuitClass(int classId) {
        return quitClass != null && classId < quitClass.length && quitClass[classId];
    }

    public boolean hasQuitClasses() {
        return quitClass != null;
    }

    public static CharClasses identity() {
        byte[] singleRow = new byte[256];
        byte[][] rows = { singleRow };
        int[] highIndex = new int[256];
        return new CharClasses(rows, highIndex, 1, null, null, null, null);
    }
}
