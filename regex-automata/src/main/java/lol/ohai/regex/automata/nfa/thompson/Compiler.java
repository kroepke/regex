package lol.ohai.regex.automata.nfa.thompson;

import lol.ohai.regex.syntax.hir.ClassUnicode;
import lol.ohai.regex.syntax.hir.Hir;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles an HIR into a Thompson NFA.
 *
 * <p>The compiler walks the HIR tree and produces NFA states using the
 * {@link Builder}. Each HIR node is compiled into a fragment represented
 * by a {@link ThompsonRef} (start state, end state). Fragments are then
 * wired together via {@link Builder#patch(int, int)}.</p>
 *
 * <p>The resulting NFA has two start states: one for anchored searches
 * (the pattern must match at the current position) and one for unanchored
 * searches (the pattern may match anywhere in the input). The unanchored
 * start state uses a loop that tries the pattern at every char position.</p>
 */
public final class Compiler {

    private final Builder builder = new Builder();
    private int nextCaptureIndex = 1; // 0 is reserved for the implicit group

    private Compiler() {}

    /**
     * Compiles the given HIR into an NFA.
     *
     * @param hir the high-level intermediate representation to compile
     * @return the compiled Thompson NFA
     * @throws BuildError if the NFA cannot be built (e.g., too many states)
     */
    public static NFA compile(Hir hir) throws BuildError {
        Compiler c = new Compiler();
        return c.compileInternal(hir);
    }

    private NFA compileInternal(Hir hir) throws BuildError {
        int groupCount = countGroups(hir) + 1; // +1 for implicit group 0
        int captureSlotCount = groupCount * 2;
        List<String> groupNames = new ArrayList<>();
        collectGroupNames(hir, groupNames);
        // Pad group names to correct size (index 0 = implicit group, no name)
        while (groupNames.size() < groupCount) {
            groupNames.add(null);
        }

        // Build capture start for implicit group 0 (slot 0 = start)
        int captureStart = builder.add(new State.Capture(0, 0, 0));

        // Compile the HIR body
        ThompsonRef body = compileNode(hir);

        // Wire capture start -> body
        builder.patch(captureStart, body.start());

        // Add capture end for group 0 (slot 1 = end)
        int captureEnd = builder.add(new State.Capture(0, 0, 1));
        builder.patch(body.end(), captureEnd);

        // Add Match state
        int match = builder.add(new State.Match(0));
        builder.patch(captureEnd, match);

        // Set up start states
        // Anchored: captureStart
        builder.setStartAnchored(captureStart);

        // Unanchored: a loop that tries the match at every char position.
        // BinaryUnion(captureStart, skipState)
        // skipState = CharRange(0x0000-0xFFFF, -> loops back to BinaryUnion)
        //
        // Covers all 16-bit char values including surrogates (0xD800-0xDFFF).
        // The PikeVM loop advances by at++ through both halves of a surrogate pair.
        int skipState = builder.add(new State.CharRange(0x0000, 0xFFFF, 0));
        int startUnanchored = builder.add(new State.BinaryUnion(captureStart, skipState));
        builder.patch(skipState, startUnanchored); // loop back
        builder.setStartUnanchored(startUnanchored);

        builder.setGroupInfo(groupCount, captureSlotCount, groupNames);

        return builder.build();
    }

    // ---- HIR node compilation ----

    private ThompsonRef compileNode(Hir hir) throws BuildError {
        return switch (hir) {
            case Hir.Empty e -> compileEmpty();
            case Hir.Literal l -> compileLiteral(l);
            case Hir.Class c -> compileClass(c);
            case Hir.Look l -> compileLook(l);
            case Hir.Repetition r -> compileRepetition(r);
            case Hir.Capture c -> compileCapture(c);
            case Hir.Concat c -> compileConcat(c);
            case Hir.Alternation a -> compileAlternation(a);
        };
    }

    /**
     * Empty: a single pass-through state. We use a Union with a single
     * alternative that gets patched to point to whatever comes next.
     */
    private ThompsonRef compileEmpty() {
        // Use a single-element Union as an epsilon/pass-through state.
        // The single alternative will be patched to point to whatever comes next.
        int id = builder.add(new State.Union(new int[]{0}));
        return new ThompsonRef(id, id);
    }

    /**
     * Literal: chain of CharRange states, one per char in the UTF-16 encoding.
     */
    private ThompsonRef compileLiteral(Hir.Literal lit) {
        char[] chars = lit.chars();
        if (chars.length == 0) {
            return compileEmpty();
        }

        int first = builder.add(new State.CharRange(chars[0], chars[0], 0));
        int prev = first;
        for (int i = 1; i < chars.length; i++) {
            int cur = builder.add(new State.CharRange(chars[i], chars[i], 0));
            builder.patch(prev, cur);
            prev = cur;
        }
        return new ThompsonRef(first, prev);
    }

    /**
     * Unicode character class: for each codepoint range, generate UTF-16 char-unit
     * sequences using {@link Utf16Sequences}, then connect all alternatives
     * with a Union state.
     */
    private ThompsonRef compileClass(Hir.Class cls) {
        List<ClassUnicode.ClassUnicodeRange> ranges = cls.unicode().ranges();
        if (ranges.isEmpty()) {
            // Empty class: use Fail state
            int id = builder.add(new State.Fail());
            return new ThompsonRef(id, id);
        }

        List<ThompsonRef> alternatives = new ArrayList<>();
        for (ClassUnicode.ClassUnicodeRange range : ranges) {
            List<int[][]> seqs = Utf16Sequences.compile(range.start(), range.end());
            for (int[][] seq : seqs) {
                ThompsonRef ref = compileCharSequence(seq);
                alternatives.add(ref);
            }
        }

        if (alternatives.size() == 1) {
            return alternatives.getFirst();
        }

        return buildUnion(alternatives);
    }

    /**
     * Compiles a single char-unit range sequence (1-2 char ranges) into a chain
     * of CharRange states.
     */
    private ThompsonRef compileCharSequence(int[][] seq) {
        int first = builder.add(new State.CharRange(seq[0][0], seq[0][1], 0));
        int prev = first;
        for (int i = 1; i < seq.length; i++) {
            int cur = builder.add(new State.CharRange(seq[i][0], seq[i][1], 0));
            builder.patch(prev, cur);
            prev = cur;
        }
        return new ThompsonRef(first, prev);
    }

    /**
     * Look-around assertion: single Look state.
     */
    private ThompsonRef compileLook(Hir.Look look) {
        int id = builder.add(new State.Look(look.look(), 0));
        return new ThompsonRef(id, id);
    }

    /**
     * Repetition compilation:
     * <ul>
     *   <li>{@code *} (0, MAX, greedy): Union(body->loop, empty) greedy; Union(empty, body->loop) lazy</li>
     *   <li>{@code +} (1, MAX, greedy): body -> Union(loop_back, empty)</li>
     *   <li>{@code ?} (0, 1, greedy): Union(body, empty) greedy; Union(empty, body) lazy</li>
     *   <li>{@code {n,m}}: unroll first n copies, then m-n optional copies</li>
     * </ul>
     */
    private ThompsonRef compileRepetition(Hir.Repetition rep) throws BuildError {
        int min = rep.min();
        int max = rep.max();
        boolean greedy = rep.greedy();

        // Special case: {0,0} matches empty
        if (max == 0) {
            return compileEmpty();
        }

        // Special case: {1,1} is just the sub-expression
        if (min == 1 && max == 1) {
            return compileNode(rep.sub());
        }

        // Star: {0, MAX}
        if (min == 0 && max == Hir.Repetition.UNBOUNDED) {
            return compileStar(rep.sub(), greedy);
        }

        // Plus: {1, MAX}
        if (min == 1 && max == Hir.Repetition.UNBOUNDED) {
            return compilePlus(rep.sub(), greedy);
        }

        // Question: {0, 1}
        if (min == 0 && max == 1) {
            return compileQuestion(rep.sub(), greedy);
        }

        // General case: {n, m}
        // Unroll n required copies, then (m - n) optional copies.
        // If max is UNBOUNDED: unroll (n-1) required, then compile sub+
        if (max == Hir.Repetition.UNBOUNDED) {
            // {n, inf}: (n-1) required copies + sub+
            ThompsonRef result = null;
            for (int i = 0; i < min - 1; i++) {
                ThompsonRef copy = compileNode(rep.sub());
                result = chainRefs(result, copy);
            }
            ThompsonRef plus = compilePlus(rep.sub(), greedy);
            result = chainRefs(result, plus);
            return result;
        }

        // {n, m} where m is finite
        ThompsonRef result = null;

        // Required copies
        for (int i = 0; i < min; i++) {
            ThompsonRef copy = compileNode(rep.sub());
            result = chainRefs(result, copy);
        }

        // Optional copies (m - n)
        for (int i = 0; i < max - min; i++) {
            ThompsonRef optional = compileQuestion(rep.sub(), greedy);
            result = chainRefs(result, optional);
        }

        if (result == null) {
            return compileEmpty();
        }
        return result;
    }

    /**
     * Compiles a* (greedy) or a*? (lazy).
     *
     * <p>When the sub-expression can match the empty string, we compile x* as
     * (x+)? instead. This is necessary for correct leftmost-first match semantics.
     * Without this transformation, the NFA's epsilon closure computes an incorrect
     * preference order. See: https://github.com/rust-lang/regex/issues/779
     */
    private ThompsonRef compileStar(Hir sub, boolean greedy) throws BuildError {
        // When the sub-expression can match the empty string, compile x* as (x+)?
        // to preserve correct leftmost-first preference ordering.
        if (canMatchEmpty(sub)) {
            ThompsonRef plus = compilePlus(sub, greedy);
            return compileQuestion(new Hir.Empty(), greedy, plus);
        }

        ThompsonRef body = compileNode(sub);
        return compileStarClean(sub, greedy, body);
    }

    /**
     * Like compileQuestion but wraps an already-compiled ThompsonRef in a ? construct.
     */
    private ThompsonRef compileQuestion(Hir unused, boolean greedy, ThompsonRef body) {
        int endEpsilon = builder.add(new State.Union(new int[]{0})); // exit placeholder

        // Wire body end -> endEpsilon
        builder.patch(body.end(), endEpsilon);

        int splitId;
        if (greedy) {
            splitId = builder.add(new State.BinaryUnion(body.start(), endEpsilon));
        } else {
            splitId = builder.add(new State.BinaryUnion(endEpsilon, body.start()));
        }

        return new ThompsonRef(splitId, endEpsilon);
    }

    private ThompsonRef compileStarClean(Hir sub, boolean greedy, ThompsonRef body) {
        int endEpsilon = builder.add(new State.Union(new int[]{0})); // exit placeholder

        int splitId;
        if (greedy) {
            splitId = builder.add(new State.BinaryUnion(body.start(), endEpsilon));
        } else {
            splitId = builder.add(new State.BinaryUnion(endEpsilon, body.start()));
        }

        // Wire body end back to split (the loop)
        builder.patch(body.end(), splitId);

        return new ThompsonRef(splitId, endEpsilon);
    }

    /**
     * Compiles a+ (greedy) or a+? (lazy).
     * Equivalent to: body followed by body* -- but sharing the body.
     * Structure: body -> split(loop back to body.start, exit)
     */
    private ThompsonRef compilePlus(Hir sub, boolean greedy) throws BuildError {
        ThompsonRef body = compileNode(sub);
        int endEpsilon = builder.add(new State.Union(new int[]{0})); // exit placeholder

        int splitId;
        if (greedy) {
            splitId = builder.add(new State.BinaryUnion(body.start(), endEpsilon));
        } else {
            splitId = builder.add(new State.BinaryUnion(endEpsilon, body.start()));
        }

        // Wire body end -> split
        builder.patch(body.end(), splitId);

        return new ThompsonRef(body.start(), endEpsilon);
    }

    /**
     * Compiles a? (greedy) or a?? (lazy).
     */
    private ThompsonRef compileQuestion(Hir sub, boolean greedy) throws BuildError {
        ThompsonRef body = compileNode(sub);
        int endEpsilon = builder.add(new State.Union(new int[]{0})); // exit placeholder

        // Wire body end -> endEpsilon
        builder.patch(body.end(), endEpsilon);

        int splitId;
        if (greedy) {
            splitId = builder.add(new State.BinaryUnion(body.start(), endEpsilon));
        } else {
            splitId = builder.add(new State.BinaryUnion(endEpsilon, body.start()));
        }

        return new ThompsonRef(splitId, endEpsilon);
    }

    /**
     * Capture group: CaptureStart -> sub -> CaptureEnd.
     */
    private ThompsonRef compileCapture(Hir.Capture cap) throws BuildError {
        int groupIndex = cap.index();
        int startSlot = groupIndex * 2;
        int endSlot = groupIndex * 2 + 1;

        int captureStart = builder.add(new State.Capture(0, groupIndex, startSlot));
        ThompsonRef body = compileNode(cap.sub());
        builder.patch(captureStart, body.start());

        int captureEnd = builder.add(new State.Capture(0, groupIndex, endSlot));
        builder.patch(body.end(), captureEnd);

        return new ThompsonRef(captureStart, captureEnd);
    }

    /**
     * Concatenation: chain sub-expressions end-to-end.
     */
    private ThompsonRef compileConcat(Hir.Concat concat) throws BuildError {
        List<Hir> subs = concat.subs();
        if (subs.isEmpty()) {
            return compileEmpty();
        }

        ThompsonRef result = compileNode(subs.getFirst());
        for (int i = 1; i < subs.size(); i++) {
            ThompsonRef next = compileNode(subs.get(i));
            builder.patch(result.end(), next.start());
            result = new ThompsonRef(result.start(), next.end());
        }
        return result;
    }

    /**
     * Alternation: Union state pointing to all alternatives.
     * All alternative ends are patched to a common exit placeholder.
     */
    private ThompsonRef compileAlternation(Hir.Alternation alt) throws BuildError {
        List<Hir> subs = alt.subs();
        if (subs.isEmpty()) {
            return compileEmpty();
        }
        if (subs.size() == 1) {
            return compileNode(subs.getFirst());
        }

        List<ThompsonRef> alternatives = new ArrayList<>();
        for (Hir sub : subs) {
            alternatives.add(compileNode(sub));
        }

        return buildUnion(alternatives);
    }

    // ---- Helpers ----

    /**
     * Builds a Union state from a list of compiled alternatives.
     * All alternative ends are patched to a common exit epsilon state.
     */
    private ThompsonRef buildUnion(List<ThompsonRef> alternatives) {
        int endEpsilon = builder.add(new State.Union(new int[]{0})); // exit placeholder

        // Patch all ends to the common exit
        for (ThompsonRef ref : alternatives) {
            builder.patch(ref.end(), endEpsilon);
        }

        if (alternatives.size() == 2) {
            int splitId = builder.add(new State.BinaryUnion(
                    alternatives.get(0).start(),
                    alternatives.get(1).start()));
            return new ThompsonRef(splitId, endEpsilon);
        }

        int[] alts = new int[alternatives.size()];
        for (int i = 0; i < alternatives.size(); i++) {
            alts[i] = alternatives.get(i).start();
        }
        int unionId = builder.add(new State.Union(alts));
        return new ThompsonRef(unionId, endEpsilon);
    }

    /**
     * Chains two ThompsonRef fragments together. If {@code first} is null,
     * returns {@code second}.
     */
    private ThompsonRef chainRefs(ThompsonRef first, ThompsonRef second) {
        if (first == null) {
            return second;
        }
        builder.patch(first.end(), second.start());
        return new ThompsonRef(first.start(), second.end());
    }

    /**
     * Counts the number of capture groups in the HIR (excluding the implicit group 0).
     */
    private static int countGroups(Hir hir) {
        return switch (hir) {
            case Hir.Empty e -> 0;
            case Hir.Literal l -> 0;
            case Hir.Class c -> 0;
            case Hir.Look l -> 0;
            case Hir.Repetition r -> countGroups(r.sub());
            case Hir.Capture c -> 1 + countGroups(c.sub());
            case Hir.Concat c -> c.subs().stream().mapToInt(Compiler::countGroups).sum();
            case Hir.Alternation a -> a.subs().stream().mapToInt(Compiler::countGroups).sum();
        };
    }

    /**
     * Returns true if the given HIR can match the empty string.
     * This is used by the compiler to decide whether x* should be compiled as
     * (x+)? to preserve correct leftmost-first preference ordering.
     */
    private static boolean canMatchEmpty(Hir hir) {
        return switch (hir) {
            case Hir.Empty e -> true;
            case Hir.Literal l -> l.chars().length == 0;
            case Hir.Class c -> false; // class requires at least one codepoint
            case Hir.Look l -> true; // zero-width assertions match empty
            case Hir.Repetition r -> r.min() == 0 || canMatchEmpty(r.sub());
            case Hir.Capture c -> canMatchEmpty(c.sub());
            case Hir.Concat c -> c.subs().stream().allMatch(Compiler::canMatchEmpty);
            case Hir.Alternation a -> a.subs().stream().anyMatch(Compiler::canMatchEmpty);
        };
    }

    /**
     * Collects capture group names from the HIR in index order.
     * Index 0 (implicit group) is added by the caller.
     */
    private static void collectGroupNames(Hir hir, List<String> names) {
        switch (hir) {
            case Hir.Capture c -> {
                // Ensure the list is large enough
                while (names.size() <= c.index()) {
                    names.add(null);
                }
                names.set(c.index(), c.name());
                collectGroupNames(c.sub(), names);
            }
            case Hir.Repetition r -> collectGroupNames(r.sub(), names);
            case Hir.Concat c -> c.subs().forEach(s -> collectGroupNames(s, names));
            case Hir.Alternation a -> a.subs().forEach(s -> collectGroupNames(s, names));
            default -> {}
        }
    }
}
