package lol.ohai.regex.syntax.hir;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts literal prefix and suffix sequences from an HIR tree.
 *
 * <p>Used by the meta engine to build prefilters that skip ahead in the
 * haystack before running the full regex engine.</p>
 */
public final class LiteralExtractor {

    private LiteralExtractor() {}

    /**
     * Result of inner literal extraction. Contains the extracted literal
     * and the HIR of the prefix portion (everything before the inner literal).
     * The prefix HIR is used to compile a separate reverse NFA/DFA for
     * the ReverseInner strategy.
     */
    public record InnerLiteral(LiteralSeq literal, Hir prefixHir) {}

    /**
     * Extracts the best inner literal from a top-level Concat.
     *
     * <p>Scans each position in the concat (skipping position 0, which is prefix
     * territory). For each position, tries to extract a literal. Returns the
     * longest candidate, along with the prefix HIR (everything before it).</p>
     *
     * <p>The "longest literal" heuristic minimizes false positive hits from
     * {@code indexOf}. The upstream Rust crate uses
     * {@code optimize_for_prefix_by_preference()} which ranks candidates by
     * byte frequency — this is a known simplification that could be refined
     * in the future.</p>
     *
     * @return the inner literal and prefix HIR, or null if no inner literal found
     */
    public static InnerLiteral extractInner(Hir hir) {
        if (!(hir instanceof Hir.Concat concat)) {
            return null;
        }
        List<Hir> subs = concat.subs();
        if (subs.size() < 2) {
            return null;
        }

        int bestPos = -1;
        LiteralSeq bestLiteral = null;
        int bestLen = 0;

        // Skip position 0 (prefix territory)
        for (int i = 1; i < subs.size(); i++) {
            Hir sub = unwrapCaptures(subs.get(i));
            if (sub instanceof Hir.Literal lit) {
                if (lit.chars().length > bestLen) {
                    bestPos = i;
                    bestLiteral = new LiteralSeq.Single(lit.chars(), false, false);
                    bestLen = lit.chars().length;
                }
            }
        }

        if (bestLiteral == null) {
            return null;
        }

        // Build prefix HIR: everything before the inner literal position
        Hir prefixHir;
        if (bestPos == 1) {
            prefixHir = subs.get(0);
        } else {
            prefixHir = new Hir.Concat(subs.subList(0, bestPos));
        }

        return new InnerLiteral(bestLiteral, prefixHir);
    }

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

    public static LiteralSeq extractSuffixes(Hir hir) {
        return switch (hir) {
            case Hir.Literal lit -> new LiteralSeq.Single(lit.chars(), true, true);
            case Hir.Capture cap -> extractSuffixes(cap.sub());
            case Hir.Concat concat -> extractSuffixFromConcat(concat.subs());
            case Hir.Alternation alt -> extractSuffixFromAlternation(alt.subs());
            case Hir.Empty ignored -> new LiteralSeq.None();
            case Hir.Class ignored -> new LiteralSeq.None();
            case Hir.Look ignored -> new LiteralSeq.None();
            case Hir.Repetition ignored -> new LiteralSeq.None();
        };
    }

    private static LiteralSeq extractSuffixFromConcat(List<Hir> subs) {
        if (subs.isEmpty()) {
            return new LiteralSeq.None();
        }
        List<char[]> parts = new ArrayList<>();
        boolean allLiteral = true;
        for (int i = subs.size() - 1; i >= 0; i--) {
            Hir unwrapped = unwrapCaptures(subs.get(i));
            if (unwrapped instanceof Hir.Literal lit) {
                parts.add(0, lit.chars());
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

    private static LiteralSeq extractSuffixFromAlternation(List<Hir> subs) {
        if (subs.isEmpty()) {
            return new LiteralSeq.None();
        }
        List<char[]> literals = new ArrayList<>();
        boolean allEntire = true;
        for (Hir sub : subs) {
            LiteralSeq extracted = extractSuffixes(sub);
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
