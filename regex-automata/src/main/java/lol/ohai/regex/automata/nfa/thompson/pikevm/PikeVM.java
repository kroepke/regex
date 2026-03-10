package lol.ohai.regex.automata.nfa.thompson.pikevm;

import lol.ohai.regex.automata.nfa.thompson.*;
import lol.ohai.regex.automata.util.*;
import lol.ohai.regex.syntax.hir.LookKind;

import java.util.Arrays;
import java.util.List;

/**
 * Thompson/Pike NFA simulation engine.
 *
 * <p>This implements the classic Pike VM algorithm: at each byte position,
 * maintain a set of active NFA states (threads). For each byte, advance all
 * threads through byte-consuming transitions, then compute epsilon closures
 * for the next set of states.</p>
 *
 * <p>Thread-safe: PikeVM is immutable. {@link Cache} is per-search mutable state.</p>
 *
 * <p>Key properties:</p>
 * <ul>
 *   <li>O(m * n) time where m = NFA state count, n = input length</li>
 *   <li>Leftmost-first match semantics via state insertion order priority</li>
 *   <li>Full capture group support via per-thread slot tracking</li>
 * </ul>
 *
 * @see Cache
 */
public final class PikeVM {
    private final NFA nfa;

    public PikeVM(NFA nfa) {
        this.nfa = nfa;
    }

    /**
     * Creates a new cache for this PikeVM. Each search needs its own cache,
     * or caches can be reused between sequential searches.
     */
    public Cache createCache() {
        return new Cache(nfa);
    }

    /**
     * Returns the underlying NFA.
     */
    public NFA nfa() {
        return nfa;
    }

    /**
     * Check if the pattern matches anywhere in the input.
     *
     * <p>This may short-circuit as soon as a match is found, without
     * determining the full match extent.</p>
     */
    public boolean isMatch(Input input, Cache cache) {
        return searchSlots(input, cache, null) >= 0;
    }

    /**
     * Find the first match, returning a Captures with just the overall match span (group 0).
     * Returns null if no match is found.
     */
    public Captures search(Input input, Cache cache) {
        int[] slots = new int[2]; // only group 0
        Arrays.fill(slots, -1);
        int result = searchSlots(input, cache, slots);
        if (result < 0) {
            return null;
        }
        Captures caps = new Captures(1);
        caps.set(0, slots[0]);
        caps.set(1, slots[1]);
        return caps;
    }

    /**
     * Find the first match with all capture groups.
     * Returns null if no match is found.
     */
    public Captures searchCaptures(Input input, Cache cache) {
        int groupCount = nfa.groupCount();
        int[] slots = new int[groupCount * 2];
        Arrays.fill(slots, -1);
        int result = searchSlots(input, cache, slots);
        if (result < 0) {
            return null;
        }
        Captures caps = new Captures(groupCount);
        for (int i = 0; i < slots.length; i++) {
            caps.set(i, slots[i]);
        }
        return caps;
    }

    /**
     * Core search implementation. Writes match slots into the provided array.
     *
     * <p>The algorithm works as follows:</p>
     * <ol>
     *   <li>For each byte position from start to end (inclusive):</li>
     *   <li>  If not anchored and no match yet, add the anchored start state via epsilon closure</li>
     *   <li>  For each active state, check byte-consuming transitions and add successors to next</li>
     *   <li>  When a Match state is encountered, record the match</li>
     *   <li>  Swap current and next, clear next</li>
     * </ol>
     *
     * <p>Matches are "delayed by one byte" -- a Match state found in the current set
     * is reported during the nexts() step, not during epsilon closure. This mirrors
     * the upstream implementation and correctly handles look-behind assertions.</p>
     *
     * @param input the search input
     * @param cache the mutable scratch space
     * @param slots the slot array to write to (may be null for is-match queries)
     * @return the matched pattern ID (0 for single-pattern), or -1 if no match
     */
    private int searchSlots(Input input, Cache cache, int[] slots) {
        cache.setupSearch();

        byte[] haystack = input.haystack();
        int start = input.start();
        int end = input.end();
        boolean anchored = input.isAnchored();

        int startState = nfa.startAnchored();

        ActiveStates curr = cache.curr;
        ActiveStates next = cache.next;
        List<FollowEpsilon> stack = cache.stack;
        int[] scratchSlots = cache.scratchSlots;

        int matchPatternId = -1;

        int at = start;
        while (at <= end) {
            // If current set is empty, we can potentially quit early.
            if (curr.set.isEmpty()) {
                // We have a match, so we're done (leftmost-first).
                if (matchPatternId >= 0) {
                    break;
                }
                // If anchored and past start, no match is possible.
                if (anchored && at > start) {
                    break;
                }
            }

            // Add the start state via epsilon closure.
            // For leftmost-first: once we have a match, don't add new start states
            // (this prevents finding matches that start later than our current best).
            // For anchored: only add at the start position.
            if (matchPatternId < 0 && (!anchored || at == start)) {
                Arrays.fill(scratchSlots, -1);
                epsilonClosure(stack, scratchSlots, curr, haystack, at, startState);
            }

            // Process byte-consuming transitions and detect matches.
            int matchedInStep = nexts(stack, cache, curr, next, haystack, at, end, slots);
            if (matchedInStep >= 0) {
                matchPatternId = matchedInStep;
            }

            // Swap current and next
            ActiveStates tmp = curr;
            curr = next;
            next = tmp;
            next.clear();

            at++;
        }

        // Restore cache references after swaps
        cache.curr = curr;
        cache.next = next;

        return matchPatternId;
    }

    /**
     * Process all current active states, advancing through byte-consuming transitions.
     *
     * <p>For each active state in priority order:</p>
     * <ul>
     *   <li>Match states: record the match and stop (leftmost-first)</li>
     *   <li>ByteRange/Sparse/Dense: if the current byte matches, add successor via epsilon closure</li>
     *   <li>Other states: skip (should not be in the active set)</li>
     * </ul>
     *
     * @return pattern ID if a match state is encountered, -1 otherwise
     */
    private int nexts(
            List<FollowEpsilon> stack,
            Cache cache,
            ActiveStates curr,
            ActiveStates next,
            byte[] haystack,
            int at,
            int end,
            int[] slots
    ) {
        int matchedPattern = -1;
        SparseSet set = curr.set;
        int[] scratchSlots = cache.scratchSlots;

        for (int i = 0; i < set.size(); i++) {
            int sid = set.get(i);
            State state = nfa.state(sid);

            switch (state) {
                case State.Match m -> {
                    // Copy this state's slots to the output
                    if (slots != null) {
                        int offset = curr.slotOffset(sid);
                        int copyLen = Math.min(slots.length, curr.slotCount);
                        System.arraycopy(curr.slotTable, offset, slots, 0, copyLen);
                    }
                    matchedPattern = m.patternId();
                }
                case State.ByteRange br -> {
                    if (at < end) {
                        int b = haystack[at] & 0xFF;
                        if (b >= br.start() && b <= br.end()) {
                            curr.readSlots(sid, scratchSlots, 0);
                            epsilonClosure(stack, scratchSlots, next, haystack, at + 1, br.next());
                        }
                    }
                }
                case State.Sparse sp -> {
                    if (at < end) {
                        int b = haystack[at] & 0xFF;
                        for (Transition t : sp.transitions()) {
                            if (b >= t.start() && b <= t.end()) {
                                curr.readSlots(sid, scratchSlots, 0);
                                epsilonClosure(stack, scratchSlots, next, haystack, at + 1, t.next());
                                break;
                            }
                        }
                    }
                }
                case State.Dense d -> {
                    if (at < end) {
                        int b = haystack[at] & 0xFF;
                        int nextState = d.next()[b];
                        if (nextState >= 0) {
                            curr.readSlots(sid, scratchSlots, 0);
                            epsilonClosure(stack, scratchSlots, next, haystack, at + 1, nextState);
                        }
                    }
                }
                default -> {
                    // Epsilon states should not be in the active set.
                    // Fail states are dead ends.
                }
            }

            // Leftmost-first: stop processing once we find a match
            if (matchedPattern >= 0) {
                break;
            }
        }

        return matchedPattern;
    }

    /**
     * Compute the epsilon closure of the given state, adding reachable states to {@code next}.
     *
     * <p>This uses an explicit stack to avoid recursion. Capture slot values are
     * tracked in {@code currSlots} which is mutated in place. {@link FollowEpsilon.RestoreCapture}
     * frames on the stack ensure that slot values are restored when backtracking
     * through alternation branches.</p>
     */
    private void epsilonClosure(
            List<FollowEpsilon> stack,
            int[] currSlots,
            ActiveStates next,
            byte[] haystack,
            int at,
            int sid
    ) {
        stack.add(new FollowEpsilon.Explore(sid));
        while (!stack.isEmpty()) {
            FollowEpsilon frame = stack.removeLast();
            switch (frame) {
                case FollowEpsilon.RestoreCapture rc ->
                        currSlots[rc.slot()] = rc.offset();
                case FollowEpsilon.Explore e ->
                        epsilonClosureExplore(stack, currSlots, next, haystack, at, e.stateId());
            }
        }
    }

    /**
     * Explore epsilon transitions from a single state.
     *
     * <p>Uses a loop with tail-call optimization: when there's only one epsilon
     * transition to follow (e.g., Look, Capture), we set {@code sid} and continue
     * the loop instead of pushing to the stack.</p>
     */
    private void epsilonClosureExplore(
            List<FollowEpsilon> stack,
            int[] currSlots,
            ActiveStates next,
            byte[] haystack,
            int at,
            int sid
    ) {
        while (true) {
            // If already in the set, a higher-priority path already reached this state.
            if (!next.set.insert(sid)) {
                return;
            }

            State state = nfa.state(sid);
            switch (state) {
                case State.Fail f -> {
                    next.copySlots(sid, currSlots, 0);
                    return;
                }
                case State.Match m -> {
                    next.copySlots(sid, currSlots, 0);
                    return;
                }
                case State.ByteRange br -> {
                    next.copySlots(sid, currSlots, 0);
                    return;
                }
                case State.Sparse sp -> {
                    next.copySlots(sid, currSlots, 0);
                    return;
                }
                case State.Dense d -> {
                    next.copySlots(sid, currSlots, 0);
                    return;
                }
                case State.Look l -> {
                    if (!checkLook(l.look(), haystack, at)) {
                        return;
                    }
                    sid = l.next();
                }
                case State.Union u -> {
                    int[] alts = u.alternates();
                    if (alts.length == 0) {
                        return;
                    }
                    // Push alternates in reverse order so that the first alternate
                    // is explored first (it will be popped last from the main stack,
                    // but we tail-call it here).
                    for (int i = alts.length - 1; i >= 1; i--) {
                        stack.add(new FollowEpsilon.Explore(alts[i]));
                    }
                    sid = alts[0];
                }
                case State.BinaryUnion bu -> {
                    stack.add(new FollowEpsilon.Explore(bu.alt2()));
                    sid = bu.alt1();
                }
                case State.Capture c -> {
                    int slot = c.slotIndex();
                    if (slot < currSlots.length) {
                        // Save the old value for restoration after this branch
                        stack.add(new FollowEpsilon.RestoreCapture(slot, currSlots[slot]));
                        currSlots[slot] = at;
                    }
                    sid = c.next();
                }
            }
        }
    }

    /**
     * Check a look-around assertion at the given byte position in the haystack.
     *
     * @param look     the assertion kind
     * @param haystack the UTF-8 encoded input bytes
     * @param at       the current byte position
     * @return true if the assertion is satisfied
     */
    private boolean checkLook(LookKind look, byte[] haystack, int at) {
        int len = haystack.length;
        return switch (look) {
            case START_TEXT -> at == 0;
            case END_TEXT -> at == len;
            case START_LINE -> at == 0 || (at > 0 && haystack[at - 1] == '\n');
            case END_LINE -> at == len || haystack[at] == '\n';
            case WORD_BOUNDARY_UNICODE, WORD_BOUNDARY_ASCII ->
                    isWordByte(haystack, at) != isWordByte(haystack, at - 1);
            case WORD_BOUNDARY_UNICODE_NEGATE, WORD_BOUNDARY_ASCII_NEGATE ->
                    isWordByte(haystack, at) == isWordByte(haystack, at - 1);
            case WORD_START_UNICODE -> !isWordByte(haystack, at - 1) && isWordByte(haystack, at);
            case WORD_END_UNICODE -> isWordByte(haystack, at - 1) && !isWordByte(haystack, at);
            case WORD_START_HALF_UNICODE -> !isWordByte(haystack, at - 1);
            case WORD_END_HALF_UNICODE -> !isWordByte(haystack, at);
        };
    }

    /**
     * Returns true if the byte at the given position is an ASCII word character: {@code [0-9A-Za-z_]}.
     * Returns false if the position is out of bounds.
     */
    private static boolean isWordByte(byte[] haystack, int pos) {
        if (pos < 0 || pos >= haystack.length) {
            return false;
        }
        int b = haystack[pos] & 0xFF;
        return (b >= 'a' && b <= 'z')
                || (b >= 'A' && b <= 'Z')
                || (b >= '0' && b <= '9')
                || b == '_';
    }
}
