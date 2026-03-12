package lol.ohai.regex.syntax.hir;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts literal prefix sequences from an HIR tree.
 *
 * <p>Used by the meta engine to build prefilters that skip ahead in the
 * haystack before running the full regex engine.</p>
 */
public final class LiteralExtractor {

    private LiteralExtractor() {}

    public static LiteralSeq extractPrefixes(Hir hir) {
        return switch (hir) {
            case Hir.Literal lit -> new LiteralSeq.Single(lit.chars(), true, true);
            case Hir.Capture cap -> {
                LiteralSeq inner = extractPrefixes(cap.sub());
                yield inner;
            }
            case Hir.Concat concat -> extractFromConcat(concat.subs());
            case Hir.Alternation alt -> extractFromAlternation(alt.subs());
            case Hir.Empty ignored -> new LiteralSeq.None();
            case Hir.Class ignored -> new LiteralSeq.None();
            case Hir.Look ignored -> new LiteralSeq.None();
            case Hir.Repetition ignored -> new LiteralSeq.None();
        };
    }

    private static LiteralSeq extractFromConcat(List<Hir> subs) {
        if (subs.isEmpty()) {
            return new LiteralSeq.None();
        }
        List<char[]> parts = new ArrayList<>();
        boolean allLiteral = true;
        for (Hir sub : subs) {
            Hir unwrapped = unwrapCaptures(sub);
            if (unwrapped instanceof Hir.Literal lit) {
                parts.add(lit.chars());
            } else {
                allLiteral = false;
                break;
            }
        }
        if (parts.isEmpty()) {
            return new LiteralSeq.None();
        }
        char[] merged = mergeCharArrays(parts);
        return new LiteralSeq.Single(merged, true, allLiteral);
    }

    private static LiteralSeq extractFromAlternation(List<Hir> subs) {
        if (subs.isEmpty()) {
            return new LiteralSeq.None();
        }
        List<char[]> literals = new ArrayList<>();
        boolean allEntire = true;
        for (Hir sub : subs) {
            LiteralSeq extracted = extractPrefixes(sub);
            switch (extracted) {
                case LiteralSeq.Single single -> {
                    literals.add(single.literal());
                    if (!single.coversEntirePattern()) {
                        allEntire = false;
                    }
                }
                case LiteralSeq.None ignored -> {
                    return new LiteralSeq.None();
                }
                case LiteralSeq.Alternation ignored -> {
                    return new LiteralSeq.None();
                }
            }
        }
        return new LiteralSeq.Alternation(literals, true, allEntire);
    }

    private static Hir unwrapCaptures(Hir hir) {
        while (hir instanceof Hir.Capture cap) {
            hir = cap.sub();
        }
        return hir;
    }

    private static char[] mergeCharArrays(List<char[]> parts) {
        int totalLen = 0;
        for (char[] part : parts) {
            totalLen += part.length;
        }
        char[] result = new char[totalLen];
        int offset = 0;
        for (char[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }
}
