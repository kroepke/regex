package lol.ohai.regex.automata.dfa.onepass;

import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.hir.LookKind;

import java.util.Arrays;

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
 * <p>Ref: upstream/regex/regex-automata/src/dfa/onepass.rs:2042-2243
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

    /** Creates a new search cache for use with this one-pass DFA. */
    public OnePassCache createCache() {
        return new OnePassCache(explicitSlotCount);
    }

    /** Number of capture groups (including group 0). */
    public int groupCount() {
        return nfa.groupCount();
    }

    /**
     * Searches the anchored input window for a match, extracting capture slots.
     *
     * <p>The input MUST be anchored (typically the narrowed window from
     * forward+reverse DFA). The search processes one char per iteration,
     * applying capture slot updates from transition epsilons.</p>
     *
     * @param input anchored input window
     * @param cache pre-allocated scratch space for explicit slots
     * @param slots caller-provided slot array, written in-place.
     *              Layout: [group0Start, group0End, group1Start, group1End, ...]
     * @return the matched pattern ID (>= 0), or -1 if no match
     */
    public int search(Input input, OnePassCache cache, int[] slots) {
        // Early return for zero-length input (upstream onepass.rs:2085-2087)
        if (input.start() >= input.end()) {
            return -1;
        }

        // Clear all slots
        Arrays.fill(slots, -1);

        // Set implicit start slot for pattern 0 (upstream onepass.rs:2122-2128)
        if (slots.length >= 1) {
            slots[0] = input.start();
        }

        // Set up cache for explicit slots (three-way min: upstream onepass.rs:2104-2110)
        int availableSlots = Math.max(0, slots.length - explicitSlotStart);
        cache.setup(Math.min(Epsilons.MAX_SLOTS,
                Math.min(availableSlots, cache.capacity())));

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

            // Check for match BEFORE consuming transition (upstream onepass.rs:2151)
            if (sid >= minMatchId) {
                int pid = findMatch(cache, input, at, sid, slots);
                if (pid >= 0) {
                    matchedPid = pid;
                    // Leftmost-first: match_wins on outgoing transition → stop
                    if (TransitionTable.matchWins(trans)) {
                        return matchedPid;
                    }
                }
            }

            // Dead state check on CURRENT state (upstream onepass.rs:2160)
            if (sid == TransitionTable.DEAD) {
                return matchedPid;
            }

            // Look assertion check
            if (!Epsilons.looksEmpty(eps)) {
                if (!looksSatisfied(Epsilons.looks(eps), haystack, at)) {
                    return matchedPid;
                }
            }

            // Apply capture slot updates from transition epsilons
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
     * Checks if {@code sid} is a match state with satisfied look assertions.
     * If so, sets the implicit end slot, copies explicit slots from cache,
     * and applies the match state's own epsilon slot updates.
     *
     * <p>Ref: upstream onepass.rs:2194-2243
     */
    private int findMatch(OnePassCache cache, Input input, int at,
                           int sid, int[] slots) {
        long patEps = table.getPatternEpsilons(sid);
        if (!TransitionTable.hasPattern(patEps)) return -1;

        // Extract epsilons from the patEps entry (lower 42 bits, same as transitions)
        long eps = TransitionTable.epsilons(patEps);

        // Check look assertions on match state's epsilons
        if (!Epsilons.looksEmpty(eps)) {
            if (!looksSatisfied(Epsilons.looks(eps), input.haystack(), at)) {
                return -1;
            }
        }

        int pid = TransitionTable.patternId(patEps);

        // Set implicit end slot (upstream onepass.rs:2221-2223)
        int slotEnd = pid * 2 + 1;
        if (slotEnd < slots.length) {
            slots[slotEnd] = at;
        }

        // Copy explicit slots from cache to caller's slots (upstream onepass.rs:2228-2238)
        if (explicitSlotStart < slots.length) {
            int[] cacheSlots = cache.explicitSlots();
            int copyLen = Math.min(cache.activeLen(), slots.length - explicitSlotStart);
            System.arraycopy(cacheSlots, 0, slots, explicitSlotStart, copyLen);

            // Apply match state's own epsilon slot updates into caller's slots
            // starting at explicitSlotStart (NOT into cache, NOT at index 0).
            // This handles groups that close at the match state.
            // Upstream onepass.rs:2239: slots[self.explicit_slot_start..][..len]
            applyExplicitSlots(eps, at, slots);
        }

        return pid;
    }

    /**
     * Checks if all look assertions in the given look bits are satisfied at
     * the given position in the haystack.
     *
     * <p>Supports: START_LINE, END_LINE, START_TEXT, END_TEXT,
     * WORD_BOUNDARY_ASCII, WORD_BOUNDARY_ASCII_NEGATE.
     */
    private static boolean looksSatisfied(int lookBits, char[] haystack, int at) {
        for (LookKind kind : LookKind.values()) {
            if ((lookBits & (1 << kind.ordinal())) == 0) continue;
            if (!checkLook(kind, haystack, at)) return false;
        }
        return true;
    }

    private static boolean checkLook(LookKind kind, char[] haystack, int at) {
        return switch (kind) {
            case START_TEXT -> at == 0;
            case END_TEXT -> at == haystack.length;
            case START_LINE -> at == 0 || haystack[at - 1] == '\n';
            case END_LINE -> at == haystack.length || haystack[at] == '\n';
            case WORD_BOUNDARY_ASCII -> {
                boolean prevWord = at > 0 && isAsciiWord(haystack[at - 1]);
                boolean nextWord = at < haystack.length && isAsciiWord(haystack[at]);
                yield prevWord != nextWord;
            }
            case WORD_BOUNDARY_ASCII_NEGATE -> {
                boolean prevWord = at > 0 && isAsciiWord(haystack[at - 1]);
                boolean nextWord = at < haystack.length && isAsciiWord(haystack[at]);
                yield prevWord == nextWord;
            }
            // For unsupported look kinds, return false (the builder should have
            // bailed for patterns using these, but be safe)
            default -> false;
        };
    }

    /**
     * Apply explicit slot updates from epsilons into the caller's slots array,
     * offset by explicitSlotStart. Slot bit N in the epsilons corresponds to
     * slots[explicitSlotStart + N].
     */
    private void applyExplicitSlots(long eps, int position, int[] slots) {
        int slotBits = Epsilons.slots(eps);
        while (slotBits != 0) {
            int bit = Integer.numberOfTrailingZeros(slotBits);
            int idx = explicitSlotStart + bit;
            if (idx < slots.length) {
                slots[idx] = position;
            }
            slotBits &= slotBits - 1;
        }
    }

    private static boolean isAsciiWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }

    // Package-private accessors for builder/tests
    TransitionTable table() { return table; }
    CharClasses charClasses() { return charClasses; }
    NFA nfa() { return nfa; }
    int startState() { return startState; }
    int minMatchId() { return minMatchId; }
    int explicitSlotStart() { return explicitSlotStart; }
    int explicitSlotCount() { return explicitSlotCount; }
}
