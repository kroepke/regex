package lol.ohai.regex.automata.nfa.thompson.pikevm;

import lol.ohai.regex.automata.nfa.thompson.NFA;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable scratch space for PikeVM search. Not thread-safe.
 * Create one per thread or per search.
 *
 * <p>This mirrors the upstream Rust implementation's {@code Cache} struct.
 * It holds two sets of active states (current and next) and a stack for
 * epsilon closure computation.</p>
 */
public final class Cache {
    ActiveStates curr;
    ActiveStates next;

    /**
     * Stack for epsilon closure computation. Uses an explicit stack rather than
     * recursion to avoid stack overflow on deeply nested patterns.
     */
    final List<FollowEpsilon> stack;

    /**
     * Scratch slots used to hold the "current" slot values during epsilon closure.
     * These are mutated in place and restored via {@link FollowEpsilon.RestoreCapture} frames.
     */
    int[] scratchSlots;

    public Cache(NFA nfa) {
        int stateCount = nfa.stateCount();
        int slotCount = nfa.groupCount() * 2;
        this.curr = new ActiveStates(stateCount, slotCount);
        this.next = new ActiveStates(stateCount, slotCount);
        this.stack = new ArrayList<>();
        this.scratchSlots = new int[slotCount];
    }

    /**
     * Prepares the cache for a new search.
     */
    void setupSearch() {
        curr.clear();
        next.clear();
        stack.clear();
    }
}
