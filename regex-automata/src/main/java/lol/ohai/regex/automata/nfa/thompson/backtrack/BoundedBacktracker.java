package lol.ohai.regex.automata.nfa.thompson.backtrack;

import lol.ohai.regex.automata.nfa.thompson.*;
import lol.ohai.regex.automata.util.*;
import lol.ohai.regex.syntax.hir.LookKind;
import lol.ohai.regex.syntax.hir.unicode.PerlClassTables;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * A bounded backtracking NFA engine.
 *
 * <p>This implements classical NFA backtracking with an explicit stack and a
 * visited bitset to bound the search. The visited bitset tracks (stateId, offset)
 * pairs that have already been explored, guaranteeing O(m * n) worst-case time
 * where m = NFA state count and n = input length.</p>
 *
 * <p>The backtracker is useful for patterns that need capture groups and where
 * the input is small enough to fit within the visited capacity budget.</p>
 *
 * <p>Thread-safe: BoundedBacktracker is immutable. {@link Cache} is per-search
 * mutable state.</p>
 */
public final class BoundedBacktracker {

    /** Default visited capacity in bytes (256 KiB). */
    static final int DEFAULT_VISITED_CAPACITY_BYTES = 256 * 1024;

    private final NFA nfa;
    private final int maxHaystackLen;

    public BoundedBacktracker(NFA nfa) {
        this.nfa = nfa;
        int stateCount = nfa.stateCount();
        if (stateCount == 0) {
            this.maxHaystackLen = 0;
        } else {
            // Each bit in the visited set represents one (stateId, offset) pair.
            // Total bits available = capacity_bytes * 8
            // We need stateCount bits per offset position, so:
            // max positions = total_bits / stateCount
            // max haystack len = max positions - 1 (positions are 0..=len)
            this.maxHaystackLen = (DEFAULT_VISITED_CAPACITY_BYTES * 8 / stateCount) - 1;
        }
    }

    /**
     * Returns the underlying NFA.
     */
    public NFA nfa() {
        return nfa;
    }

    /**
     * Returns the maximum haystack length (in chars) that this backtracker
     * will accept. Inputs longer than this are rejected (search returns null).
     */
    public int maxHaystackLen() {
        return maxHaystackLen;
    }

    /**
     * Creates a new cache for this backtracker.
     */
    public Cache createCache() {
        return new Cache(nfa);
    }

    /**
     * Find the first match with just group 0 (overall match span).
     * Supports both anchored and unanchored search based on input configuration.
     * Returns null if no match is found or if the input exceeds the size limit.
     */
    public Captures search(Input input, Cache cache) {
        if (input.end() - input.start() > maxHaystackLen) {
            return null;
        }
        int groupCount = nfa.groupCount();
        int slotCount = groupCount * 2;
        int[] slots = new int[slotCount];
        Arrays.fill(slots, -1);

        if (input.isAnchored()) {
            if (backtrack(cache, input, input.start(), nfa.startAnchored(), slots)) {
                return slotsToCaptures(slots, 1);
            }
            return null;
        }

        // Unanchored: try each start position
        for (int startAt = input.start(); startAt <= input.end(); startAt++) {
            Arrays.fill(slots, -1);
            cache.clearVisited();
            if (backtrack(cache, input, startAt, nfa.startUnanchored(), slots)) {
                return slotsToCaptures(slots, 1);
            }
        }
        return null;
    }

    /**
     * Find the first match with all capture groups.
     * Uses anchored search semantics (must match at input start position).
     * Returns null if no match is found or if the input exceeds the size limit.
     */
    public Captures searchCaptures(Input input, Cache cache) {
        if (input.end() - input.start() > maxHaystackLen) {
            return null;
        }
        int groupCount = nfa.groupCount();
        int slotCount = groupCount * 2;
        int[] slots = new int[slotCount];
        Arrays.fill(slots, -1);

        if (input.isAnchored()) {
            if (backtrack(cache, input, input.start(), nfa.startAnchored(), slots)) {
                return slotsToCaptures(slots, groupCount);
            }
            return null;
        }

        // Unanchored: try each start position
        for (int startAt = input.start(); startAt <= input.end(); startAt++) {
            Arrays.fill(slots, -1);
            cache.clearVisited();
            if (backtrack(cache, input, startAt, nfa.startUnanchored(), slots)) {
                return slotsToCaptures(slots, groupCount);
            }
        }
        return null;
    }

    private static Captures slotsToCaptures(int[] slots, int groupCount) {
        Captures caps = new Captures(groupCount);
        int copyLen = Math.min(slots.length, groupCount * 2);
        for (int i = 0; i < copyLen; i++) {
            caps.set(i, slots[i]);
        }
        return caps;
    }

    /**
     * Core backtracking loop using an explicit stack.
     *
     * <p>The stack holds two types of frames:</p>
     * <ul>
     *   <li>{@code Step} — process NFA state {@code sid} at position {@code at}</li>
     *   <li>{@code RestoreCapture} — undo a capture slot assignment on backtrack</li>
     * </ul>
     *
     * @return true if a match was found (slots are populated), false otherwise
     */
    private boolean backtrack(Cache cache, Input input, int startAt, int startId, int[] slots) {
        char[] haystack = input.haystack();
        int end = input.end();
        List<Frame> stack = cache.stack;
        stack.clear();
        // Don't clear visited here - caller manages it for unanchored search

        stack.add(new Frame.Step(startId, startAt));

        while (!stack.isEmpty()) {
            Frame frame = stack.removeLast();
            switch (frame) {
                case Frame.RestoreCapture rc -> {
                    slots[rc.slot] = rc.prevValue;
                }
                case Frame.Step step -> {
                    int sid = step.stateId;
                    int at = step.at;

                    // Check visited bitset
                    if (!cache.visit(sid, at, input.start(), input.end())) {
                        continue;
                    }

                    // Process the state via a loop for tail-call optimization
                    if (stepLoop(cache, stack, haystack, end, sid, at, slots)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Process a single NFA state, potentially following epsilon transitions
     * in a loop (tail-call optimization). Returns true if a match is found.
     */
    private boolean stepLoop(Cache cache, List<Frame> stack, char[] haystack, int end, int sid, int at, int[] slots) {
        while (true) {
            State state = nfa.state(sid);
            switch (state) {
                case State.Match m -> {
                    return true;
                }
                case State.Fail f -> {
                    return false;
                }
                case State.CharRange cr -> {
                    if (at < end) {
                        int c = haystack[at];
                        if (c >= cr.start() && c <= cr.end()) {
                            // Tail-call: advance position and follow next state
                            at += 1;
                            sid = cr.next();
                            // Check visited for the new (sid, at)
                            if (!cache.visit(sid, at, 0, end)) {
                                return false;
                            }
                            continue;
                        }
                    }
                    return false;
                }
                case State.Sparse sp -> {
                    if (at < end) {
                        int c = haystack[at];
                        int nextSid = -1;
                        for (Transition t : sp.transitions()) {
                            if (c >= t.start() && c <= t.end()) {
                                nextSid = t.next();
                                break;
                            }
                        }
                        if (nextSid >= 0) {
                            at += 1;
                            sid = nextSid;
                            if (!cache.visit(sid, at, 0, end)) {
                                return false;
                            }
                            continue;
                        }
                    }
                    return false;
                }
                case State.Look l -> {
                    if (!checkLook(l.look(), haystack, at)) {
                        return false;
                    }
                    sid = l.next();
                    // No need to re-check visited for Look - position unchanged,
                    // but state changed. Let the loop continue.
                    if (!cache.visit(sid, at, 0, end)) {
                        return false;
                    }
                    continue;
                }
                case State.Union u -> {
                    int[] alts = u.alternates();
                    if (alts.length == 0) {
                        return false;
                    }
                    // Push alternates in reverse order (lowest priority first)
                    // so that the highest priority alternate is tried first via tail-call
                    for (int i = alts.length - 1; i >= 1; i--) {
                        stack.add(new Frame.Step(alts[i], at));
                    }
                    sid = alts[0];
                    // Don't check visited here - the loop iteration will handle it
                    // via the state dispatch. But we do need to check for the new sid.
                    if (!cache.visit(sid, at, 0, end)) {
                        return false;
                    }
                    continue;
                }
                case State.BinaryUnion bu -> {
                    stack.add(new Frame.Step(bu.alt2(), at));
                    sid = bu.alt1();
                    if (!cache.visit(sid, at, 0, end)) {
                        return false;
                    }
                    continue;
                }
                case State.Capture c -> {
                    int slot = c.slotIndex();
                    if (slot < slots.length) {
                        // Save old value for restoration on backtrack
                        stack.add(new Frame.RestoreCapture(slot, slots[slot]));
                        slots[slot] = at;
                    }
                    sid = c.next();
                    if (!cache.visit(sid, at, 0, end)) {
                        return false;
                    }
                    continue;
                }
            }
        }
    }

    /**
     * Check a look-around assertion at the given char position in the haystack.
     * Mirrors the PikeVM's checkLook() implementation.
     */
    private boolean checkLook(LookKind look, char[] haystack, int at) {
        int len = haystack.length;
        return switch (look) {
            case START_TEXT -> at == 0;
            case END_TEXT -> at == len;
            case START_LINE -> at == 0 || (at > 0 && haystack[at - 1] == '\n');
            case END_LINE -> at == len || haystack[at] == '\n';
            case START_LINE_CRLF -> at == 0
                    || haystack[at - 1] == '\n'
                    || (haystack[at - 1] == '\r'
                        && (at >= len || haystack[at] != '\n'));
            case END_LINE_CRLF -> at == len
                    || haystack[at] == '\r'
                    || (haystack[at] == '\n'
                        && (at == 0 || haystack[at - 1] != '\r'));
            case WORD_BOUNDARY_ASCII ->
                    isWordChar(haystack, at) != isWordChar(haystack, at - 1);
            case WORD_BOUNDARY_ASCII_NEGATE ->
                    isWordChar(haystack, at) == isWordChar(haystack, at - 1);
            case WORD_START_ASCII -> !isWordChar(haystack, at - 1) && isWordChar(haystack, at);
            case WORD_END_ASCII -> isWordChar(haystack, at - 1) && !isWordChar(haystack, at);
            case WORD_START_HALF_ASCII -> !isWordChar(haystack, at - 1);
            case WORD_END_HALF_ASCII -> !isWordChar(haystack, at);
            case WORD_BOUNDARY_UNICODE -> {
                boolean wordBefore = at > 0 && isUnicodeWordChar(Character.codePointBefore(haystack, at));
                boolean wordAfter = at < len && isUnicodeWordChar(Character.codePointAt(haystack, at));
                yield wordBefore != wordAfter;
            }
            case WORD_BOUNDARY_UNICODE_NEGATE -> {
                boolean wordBefore = at > 0 && isUnicodeWordChar(Character.codePointBefore(haystack, at));
                boolean wordAfter = at < len && isUnicodeWordChar(Character.codePointAt(haystack, at));
                yield wordBefore == wordAfter;
            }
            case WORD_START_UNICODE -> {
                boolean wordBefore = at > 0 && isUnicodeWordChar(Character.codePointBefore(haystack, at));
                boolean wordAfter = at < len && isUnicodeWordChar(Character.codePointAt(haystack, at));
                yield !wordBefore && wordAfter;
            }
            case WORD_END_UNICODE -> {
                boolean wordBefore = at > 0 && isUnicodeWordChar(Character.codePointBefore(haystack, at));
                boolean wordAfter = at < len && isUnicodeWordChar(Character.codePointAt(haystack, at));
                yield wordBefore && !wordAfter;
            }
            case WORD_START_HALF_UNICODE -> !(at > 0 && isUnicodeWordChar(Character.codePointBefore(haystack, at)));
            case WORD_END_HALF_UNICODE -> !(at < len && isUnicodeWordChar(Character.codePointAt(haystack, at)));
        };
    }

    private static boolean isWordChar(char[] haystack, int pos) {
        if (pos < 0 || pos >= haystack.length) {
            return false;
        }
        char c = haystack[pos];
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_';
    }

    private static boolean isUnicodeWordChar(int cp) {
        if (cp < 0x80) {
            return (cp >= 'a' && cp <= 'z')
                    || (cp >= 'A' && cp <= 'Z')
                    || (cp >= '0' && cp <= '9')
                    || cp == '_';
        }
        int[][] table = PerlClassTables.PERL_WORD;
        int lo = 0, hi = table.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int[] range = table[mid];
            if (cp < range[0]) {
                hi = mid - 1;
            } else if (cp > range[1]) {
                lo = mid + 1;
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * Stack frame types for the explicit backtracking stack.
     */
    sealed interface Frame {
        record Step(int stateId, int at) implements Frame {}
        record RestoreCapture(int slot, int prevValue) implements Frame {}
    }

    /**
     * Mutable scratch space for a single backtracking search.
     * Not thread-safe; use one cache per thread.
     */
    public static final class Cache {
        final List<Frame> stack;
        private final BitSet visited;
        private final int stateCount;

        Cache(NFA nfa) {
            this.stack = new ArrayList<>();
            this.stateCount = nfa.stateCount();
            // Pre-size the BitSet; it will grow as needed
            this.visited = new BitSet();
        }

        /**
         * Attempt to mark (stateId, at) as visited.
         * Returns true if this is the first visit (not yet seen), false if already visited.
         */
        boolean visit(int stateId, int at, int searchStart, int searchEnd) {
            // Offset relative to the search - at can range from searchStart to searchEnd inclusive
            int offset = at; // Use absolute position for simplicity
            int bit = offset * stateCount + stateId;
            if (visited.get(bit)) {
                return false;
            }
            visited.set(bit);
            return true;
        }

        /**
         * Clears the visited bitset for a new search starting position.
         */
        void clearVisited() {
            visited.clear();
        }
    }
}
