package lol.ohai.regex.automata.dfa.lazy;

import java.util.Arrays;

public final class StateContent {
    private final int[] nfaStates;
    private final boolean isMatch;
    private final int hashCode;

    public StateContent(int[] nfaStates, boolean isMatch) {
        this.nfaStates = nfaStates;
        this.isMatch = isMatch;
        this.hashCode = Arrays.hashCode(nfaStates) * 31 + Boolean.hashCode(isMatch);
    }

    /** Returns the sorted NFA state IDs. MUST NOT be mutated. */
    public int[] nfaStates() { return nfaStates; }
    public boolean isMatch() { return isMatch; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StateContent sc)) return false;
        return isMatch == sc.isMatch && Arrays.equals(nfaStates, sc.nfaStates);
    }

    @Override
    public int hashCode() { return hashCode; }
}
