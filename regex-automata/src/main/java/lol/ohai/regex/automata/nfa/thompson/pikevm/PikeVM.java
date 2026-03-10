package lol.ohai.regex.automata.nfa.thompson.pikevm;

import lol.ohai.regex.automata.nfa.thompson.*;
import lol.ohai.regex.automata.util.*;
import lol.ohai.regex.syntax.hir.LookKind;
import lol.ohai.regex.syntax.hir.unicode.PerlClassTables;

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
     *
     * <p>When the NFA is in UTF-8 mode and can match the empty string, this method
     * filters out empty matches that split a UTF-8 codepoint, matching the upstream
     * behavior of {@code empty::skip_splits_fwd}.</p>
     */
    public Captures search(Input input, Cache cache) {
        int[] slots = new int[2]; // only group 0
        Arrays.fill(slots, -1);
        int result = searchSlots(input, cache, slots);
        if (result < 0) {
            return null;
        }
        // Filter empty matches that split UTF-8 codepoints
        if (slots[0] == slots[1] && !input.isCharBoundary(slots[0])) {
            return skipSplitsFwd(input, cache, slots, 1);
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
        // Filter empty matches that split UTF-8 codepoints
        if (slots[0] == slots[1] && !input.isCharBoundary(slots[0])) {
            return skipSplitsFwd(input, cache, slots, groupCount);
        }
        Captures caps = new Captures(groupCount);
        for (int i = 0; i < slots.length; i++) {
            caps.set(i, slots[i]);
        }
        return caps;
    }

    /**
     * Skip empty matches that split UTF-8 codepoints by advancing the search start.
     * For anchored searches, if the first match splits a codepoint, there is no valid match.
     * Mirrors the upstream {@code empty::skip_splits_fwd}.
     */
    private Captures skipSplitsFwd(Input input, Cache cache, int[] slots, int groupCount) {
        if (input.isAnchored()) {
            return null; // anchored search at non-boundary -> no match
        }
        int matchOffset = slots[0];
        int newStart = input.start();
        while (!input.isCharBoundary(matchOffset)) {
            newStart++;
            if (newStart > input.end()) {
                return null;
            }
            Input adjusted = input.withBounds(newStart, input.end(), input.isAnchored());
            Arrays.fill(slots, -1);
            int result = searchSlots(adjusted, cache, slots);
            if (result < 0) {
                return null;
            }
            matchOffset = slots[0];
        }
        Captures caps = new Captures(groupCount);
        for (int i = 0; i < Math.min(slots.length, groupCount * 2); i++) {
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
            case START_LINE_CRLF -> at == 0
                    || haystack[at - 1] == '\n'
                    || (haystack[at - 1] == '\r'
                        && (at >= len || haystack[at] != '\n'));
            case END_LINE_CRLF -> at == len
                    || haystack[at] == '\r'
                    || (haystack[at] == '\n'
                        && (at == 0 || haystack[at - 1] != '\r'));
            case WORD_BOUNDARY_ASCII ->
                    isWordByte(haystack, at) != isWordByte(haystack, at - 1);
            case WORD_BOUNDARY_ASCII_NEGATE ->
                    isWordByte(haystack, at) == isWordByte(haystack, at - 1);
            case WORD_START_ASCII -> !isWordByte(haystack, at - 1) && isWordByte(haystack, at);
            case WORD_END_ASCII -> isWordByte(haystack, at - 1) && !isWordByte(haystack, at);
            case WORD_START_HALF_ASCII -> !isWordByte(haystack, at - 1);
            case WORD_END_HALF_ASCII -> !isWordByte(haystack, at);
            case WORD_BOUNDARY_UNICODE -> {
                boolean wordBefore = isWordCharRev(haystack, at);
                boolean wordAfter = isWordCharFwd(haystack, at);
                yield wordBefore != wordAfter;
            }
            case WORD_BOUNDARY_UNICODE_NEGATE -> {
                // Unlike ASCII, we need to ensure we're on a valid UTF-8 boundary.
                // Inside invalid UTF-8 sequences, neither \b nor \B matches.
                boolean validBefore = at == 0 || isValidUtf8BoundaryRev(haystack, at);
                boolean validAfter = at == len || isValidUtf8BoundaryFwd(haystack, at);
                if (!validBefore || !validAfter) {
                    yield false;
                }
                boolean wordBefore = isWordCharRev(haystack, at);
                boolean wordAfter = isWordCharFwd(haystack, at);
                yield wordBefore == wordAfter;
            }
            case WORD_START_UNICODE -> !isWordCharRev(haystack, at) && isWordCharFwd(haystack, at);
            case WORD_END_UNICODE -> isWordCharRev(haystack, at) && !isWordCharFwd(haystack, at);
            case WORD_START_HALF_UNICODE -> {
                // Must be on a valid UTF-8 boundary (before side)
                if (at > 0 && !isValidUtf8BoundaryRev(haystack, at)) {
                    yield false;
                }
                yield !isWordCharRev(haystack, at);
            }
            case WORD_END_HALF_UNICODE -> {
                // Must be on a valid UTF-8 boundary (after side)
                if (at < len && !isValidUtf8BoundaryFwd(haystack, at)) {
                    yield false;
                }
                yield !isWordCharFwd(haystack, at);
            }
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

    /**
     * Checks if a codepoint is a Unicode word character per UTS#18 Annex C.
     * A word character is: Alphabetic, Join_Control, Decimal_Number, Mark, or Connector_Punctuation.
     */
    private static boolean isUnicodeWordChar(int cp) {
        // ASCII fast path
        if (cp < 0x80) {
            return (cp >= 'a' && cp <= 'z')
                    || (cp >= 'A' && cp <= 'Z')
                    || (cp >= '0' && cp <= '9')
                    || cp == '_';
        }
        // Binary search the PERL_WORD table (sorted ranges [start, end])
        // This matches the upstream Rust regex crate's behavior exactly.
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
     * Decode the UTF-8 codepoint starting at {@code at} (forward) and check if it's a word char.
     * Returns false if at is out of bounds or the sequence is invalid.
     */
    private static boolean isWordCharFwd(byte[] haystack, int at) {
        if (at < 0 || at >= haystack.length) {
            return false;
        }
        int cp = decodeUtf8Fwd(haystack, at);
        return cp >= 0 && isUnicodeWordChar(cp);
    }

    /**
     * Decode the UTF-8 codepoint ending just before {@code at} (reverse) and check if it's a word char.
     * Returns false if at is out of bounds or the sequence is invalid.
     */
    private static boolean isWordCharRev(byte[] haystack, int at) {
        if (at <= 0 || at > haystack.length) {
            return false;
        }
        int cp = decodeUtf8Rev(haystack, at);
        return cp >= 0 && isUnicodeWordChar(cp);
    }

    /**
     * Returns true if the UTF-8 sequence starting at {@code at} is valid (i.e., {@code at} is the
     * start of a valid UTF-8 codepoint or at the end of the haystack).
     */
    private static boolean isValidUtf8BoundaryFwd(byte[] haystack, int at) {
        if (at >= haystack.length) return true;
        return decodeUtf8Fwd(haystack, at) >= 0;
    }

    /**
     * Returns true if the UTF-8 sequence ending just before {@code at} is valid.
     */
    private static boolean isValidUtf8BoundaryRev(byte[] haystack, int at) {
        if (at <= 0) return true;
        return decodeUtf8Rev(haystack, at) >= 0;
    }

    /**
     * Decode a single UTF-8 codepoint starting at position {@code at}.
     * Returns the codepoint, or -1 if the sequence is invalid or incomplete.
     */
    private static int decodeUtf8Fwd(byte[] haystack, int at) {
        if (at >= haystack.length) return -1;
        int b0 = haystack[at] & 0xFF;
        if (b0 < 0x80) {
            return b0;
        }
        if (b0 < 0xC2 || b0 > 0xF4) return -1;
        if (b0 < 0xE0) {
            if (at + 1 >= haystack.length) return -1;
            int b1 = haystack[at + 1] & 0xFF;
            if ((b1 & 0xC0) != 0x80) return -1;
            return ((b0 & 0x1F) << 6) | (b1 & 0x3F);
        }
        if (b0 < 0xF0) {
            if (at + 2 >= haystack.length) return -1;
            int b1 = haystack[at + 1] & 0xFF;
            int b2 = haystack[at + 2] & 0xFF;
            if ((b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80) return -1;
            int cp = ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
            if (cp < 0x800 || (cp >= 0xD800 && cp <= 0xDFFF)) return -1;
            return cp;
        }
        // 4-byte
        if (at + 3 >= haystack.length) return -1;
        int b1 = haystack[at + 1] & 0xFF;
        int b2 = haystack[at + 2] & 0xFF;
        int b3 = haystack[at + 3] & 0xFF;
        if ((b1 & 0xC0) != 0x80 || (b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80) return -1;
        int cp = ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
        if (cp < 0x10000 || cp > 0x10FFFF) return -1;
        return cp;
    }

    /**
     * Decode a single UTF-8 codepoint ending just before position {@code at} (reverse decode).
     * Returns the codepoint, or -1 if the sequence is invalid or incomplete.
     */
    private static int decodeUtf8Rev(byte[] haystack, int at) {
        if (at <= 0) return -1;
        int b = haystack[at - 1] & 0xFF;
        if (b < 0x80) {
            return b;
        }
        // Continuation byte: walk back to find the lead byte
        if ((b & 0xC0) != 0x80) return -1;
        int end = at;
        int start = at - 1;
        // Walk back up to 3 more bytes to find the lead byte
        for (int i = 0; i < 3 && start > 0; i++) {
            start--;
            int lead = haystack[start] & 0xFF;
            if ((lead & 0xC0) != 0x80) {
                // Found potential lead byte; decode forward from here
                int cp = decodeUtf8Fwd(haystack, start);
                if (cp < 0) return -1;
                // Verify the decoded sequence ends at 'at'
                int expectedLen = utf8Len(lead);
                if (start + expectedLen == end) {
                    return cp;
                }
                return -1;
            }
        }
        return -1;
    }

    /**
     * Returns the expected UTF-8 sequence length from the lead byte.
     */
    private static int utf8Len(int leadByte) {
        if (leadByte < 0x80) return 1;
        if (leadByte < 0xE0) return 2;
        if (leadByte < 0xF0) return 3;
        return 4;
    }
}
