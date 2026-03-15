package lol.ohai.regex.automata.dfa.onepass;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.NFA;

/**
 * A one-pass DFA that supports anchored searches with capture group resolution.
 *
 * <p>A one-pass DFA can only be built from NFAs where there is never any
 * ambiguity about how to continue a search. When such ambiguity exists
 * (e.g., {@code (a*)(a*)}), the builder returns {@code null}.
 *
 * <p>Unlike a standard DFA, a one-pass DFA can track capture group positions
 * during search, making it the only DFA variant capable of reporting capture
 * spans. It only supports anchored searches.
 *
 * @see OnePassBuilder#build(NFA, CharClasses)
 */
public final class OnePassDFA {

    private final TransitionTable table;
    private final CharClasses charClasses;
    private final NFA nfa;
    private final int startState;
    private final int minMatchId;
    private final int explicitSlotStart;
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

    /**
     * Creates a new search cache for use with this one-pass DFA.
     */
    public OnePassCache createCache() {
        return new OnePassCache(explicitSlotCount);
    }

    TransitionTable table() {
        return table;
    }

    CharClasses charClasses() {
        return charClasses;
    }

    NFA nfa() {
        return nfa;
    }

    int startState() {
        return startState;
    }

    int minMatchId() {
        return minMatchId;
    }

    int explicitSlotStart() {
        return explicitSlotStart;
    }

    int explicitSlotCount() {
        return explicitSlotCount;
    }
}
