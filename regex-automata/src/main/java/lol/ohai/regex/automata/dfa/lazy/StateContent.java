package lol.ohai.regex.automata.dfa.lazy;

import java.util.Arrays;

public final class StateContent {
    private final int[] nfaStates;
    private final boolean isMatch;
    private final boolean isFromWord;
    private final boolean isHalfCrlf;
    private final int lookHave;
    private final int lookNeed;
    private final int hashCode;

    public StateContent(int[] nfaStates, boolean isMatch,
                        boolean isFromWord, boolean isHalfCrlf,
                        int lookHave, int lookNeed) {
        this.nfaStates = nfaStates;
        this.isMatch = isMatch;
        this.isFromWord = isFromWord;
        this.isHalfCrlf = isHalfCrlf;
        this.lookHave = lookNeed == 0 ? 0 : lookHave;  // clear lookHave when no assertions needed
        this.lookNeed = lookNeed;
        this.hashCode = computeHash();
    }

    public StateContent(int[] nfaStates, boolean isMatch) {
        this(nfaStates, isMatch, false, false, 0, 0);
    }

    private int computeHash() {
        int h = Arrays.hashCode(nfaStates);
        h = h * 31 + Boolean.hashCode(isMatch);
        h = h * 31 + Boolean.hashCode(isFromWord);
        h = h * 31 + Boolean.hashCode(isHalfCrlf);
        h = h * 31 + lookHave;
        return h;
    }

    /** Returns the sorted NFA state IDs. MUST NOT be mutated. */
    public int[] nfaStates() { return nfaStates; }
    public boolean isMatch() { return isMatch; }
    public boolean isFromWord() { return isFromWord; }
    public boolean isHalfCrlf() { return isHalfCrlf; }
    public int lookHave() { return lookHave; }
    public int lookNeed() { return lookNeed; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StateContent sc)) return false;
        return isMatch == sc.isMatch
            && isFromWord == sc.isFromWord
            && isHalfCrlf == sc.isHalfCrlf
            && lookHave == sc.lookHave
            && Arrays.equals(nfaStates, sc.nfaStates);
    }

    @Override
    public int hashCode() { return hashCode; }
}
