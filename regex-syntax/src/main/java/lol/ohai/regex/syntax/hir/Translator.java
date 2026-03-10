package lol.ohai.regex.syntax.hir;

import lol.ohai.regex.syntax.ast.*;
import lol.ohai.regex.syntax.hir.ClassUnicode.ClassUnicodeRange;
import lol.ohai.regex.syntax.hir.unicode.PerlClassTables;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates an AST into a high-level intermediate representation (HIR).
 *
 * <p>The translator walks the AST tree and produces a simplified, normalized
 * HIR. Unicode properties are resolved (currently stubbed out), character
 * classes are flattened into sorted non-overlapping ranges, flags are applied,
 * and literals are encoded as UTF-8 byte sequences.
 */
public final class Translator {

    private Translator() {}

    /**
     * Translate the given AST into an HIR.
     *
     * @param pattern the original pattern string (used for error reporting)
     * @param ast the parsed AST
     * @return the HIR
     * @throws Error if the AST cannot be translated
     */
    public static Hir translate(String pattern, Ast ast) throws Error {
        return new TranslatorImpl(pattern, new Flags()).translate(ast);
    }

    /**
     * Translate the given AST into an HIR with specific initial flags.
     *
     * @param pattern the original pattern string (used for error reporting)
     * @param ast the parsed AST
     * @param unicode whether to enable Unicode mode
     * @return the HIR
     * @throws Error if the AST cannot be translated
     */
    public static Hir translate(String pattern, Ast ast, boolean unicode) throws Error {
        Flags flags = new Flags();
        flags.unicode = unicode;
        return new TranslatorImpl(pattern, flags).translate(ast);
    }

    // --- Internal flag state ---

    /**
     * Tracks the current flag state during translation.
     */
    static final class Flags {
        boolean caseInsensitive;
        boolean multiLine;
        boolean dotMatchesNewLine;
        boolean swapGreed;
        boolean unicode = true; // unicode mode is on by default
        boolean crlf;

        Flags() {}

        Flags copy() {
            Flags f = new Flags();
            f.caseInsensitive = caseInsensitive;
            f.multiLine = multiLine;
            f.dotMatchesNewLine = dotMatchesNewLine;
            f.swapGreed = swapGreed;
            f.unicode = unicode;
            f.crlf = crlf;
            return f;
        }

        /**
         * Apply the given AST flags to this flag state.
         */
        void apply(FlagItems items) {
            boolean negated = false;
            for (FlagsItem item : items.items()) {
                switch (item.kind()) {
                    case FlagsItemKind.Negation() -> negated = true;
                    case FlagsItemKind.FlagKind(Flag flag) -> {
                        boolean value = !negated;
                        switch (flag) {
                            case CASE_INSENSITIVE -> caseInsensitive = value;
                            case MULTI_LINE -> multiLine = value;
                            case DOT_MATCHES_NEW_LINE -> dotMatchesNewLine = value;
                            case SWAP_GREED -> swapGreed = value;
                            case UNICODE -> unicode = value;
                            case CRLF -> crlf = value;
                            default -> {} // IGNORE_WHITESPACE handled at parse level
                        }
                    }
                }
            }
        }
    }

    // --- Translator implementation ---

    private static final class TranslatorImpl {
        private final String pattern;
        private Flags flags;

        TranslatorImpl(String pattern, Flags flags) {
            this.pattern = pattern;
            this.flags = flags;
        }

        Hir translate(Ast ast) throws Error {
            return switch (ast) {
                case Ast.Empty e -> new Hir.Empty();
                case Ast.Literal lit -> translateLiteral(lit);
                case Ast.Dot dot -> translateDot(dot);
                case Ast.Assertion a -> translateAssertion(a);
                case Ast.ClassPerl cp -> translateClassPerl(cp);
                case Ast.ClassUnicode cu -> translateClassUnicode(cu);
                case Ast.ClassBracketed cb -> translateClassBracketed(cb);
                case Ast.Repetition rep -> translateRepetition(rep);
                case Ast.Group grp -> translateGroup(grp);
                case Ast.Alternation alt -> translateAlternation(alt);
                case Ast.Concat concat -> translateConcat(concat);
                case Ast.Flags fl -> translateFlags(fl);
            };
        }

        // --- Literals ---

        private Hir translateLiteral(Ast.Literal lit) {
            char c = lit.c();
            if (flags.caseInsensitive && isAsciiLetter(c)) {
                // Case insensitive: expand to class
                ClassUnicode cls = new ClassUnicode(List.of(
                        new ClassUnicodeRange(c, c)
                ));
                cls.caseFoldSimple();
                return new Hir.Class(cls);
            }
            return new Hir.Literal(charToUtf8(c));
        }

        // --- Dot ---

        private Hir translateDot(Ast.Dot dot) {
            ClassUnicode cls;
            if (flags.dotMatchesNewLine) {
                // All codepoints
                cls = ClassUnicode.of(
                        new ClassUnicodeRange(ClassUnicode.MIN_CODEPOINT, ClassUnicode.MAX_CODEPOINT)
                );
            } else if (flags.crlf) {
                // All codepoints except \r and \n
                cls = ClassUnicode.of(
                        new ClassUnicodeRange(0x00, 0x09),
                        new ClassUnicodeRange(0x0B, 0x0C),
                        new ClassUnicodeRange(0x0E, ClassUnicode.MAX_CODEPOINT)
                );
            } else {
                // All codepoints except \n
                cls = ClassUnicode.of(
                        new ClassUnicodeRange(0x00, 0x09),
                        new ClassUnicodeRange(0x0B, ClassUnicode.MAX_CODEPOINT)
                );
            }
            return new Hir.Class(cls);
        }

        // --- Assertions ---

        private Hir translateAssertion(Ast.Assertion a) throws Error {
            LookKind kind = switch (a.kind()) {
                case START_LINE -> flags.multiLine
                        ? (flags.crlf ? LookKind.START_LINE_CRLF : LookKind.START_LINE)
                        : LookKind.START_TEXT;
                case END_LINE -> flags.multiLine
                        ? (flags.crlf ? LookKind.END_LINE_CRLF : LookKind.END_LINE)
                        : LookKind.END_TEXT;
                case START_TEXT -> LookKind.START_TEXT;
                case END_TEXT -> LookKind.END_TEXT;
                case WORD_BOUNDARY -> flags.unicode
                        ? LookKind.WORD_BOUNDARY_UNICODE
                        : LookKind.WORD_BOUNDARY_ASCII;
                case NOT_WORD_BOUNDARY -> flags.unicode
                        ? LookKind.WORD_BOUNDARY_UNICODE_NEGATE
                        : LookKind.WORD_BOUNDARY_ASCII_NEGATE;
                case WORD_BOUNDARY_START, WORD_BOUNDARY_START_ANGLE -> flags.unicode
                        ? LookKind.WORD_START_UNICODE
                        : LookKind.WORD_START_ASCII;
                case WORD_BOUNDARY_END, WORD_BOUNDARY_END_ANGLE -> flags.unicode
                        ? LookKind.WORD_END_UNICODE
                        : LookKind.WORD_END_ASCII;
                case WORD_BOUNDARY_START_HALF -> flags.unicode
                        ? LookKind.WORD_START_HALF_UNICODE
                        : LookKind.WORD_START_HALF_ASCII;
                case WORD_BOUNDARY_END_HALF -> flags.unicode
                        ? LookKind.WORD_END_HALF_UNICODE
                        : LookKind.WORD_END_HALF_ASCII;
            };
            return new Hir.Look(kind);
        }

        // --- Perl character classes ---

        private Hir translateClassPerl(Ast.ClassPerl cp) throws Error {
            ClassUnicode cls = flags.unicode
                    ? perlClassUnicode(cp.kind())
                    : perlClassAscii(cp.kind());
            if (cp.negated()) {
                cls.negate();
            }
            if (flags.caseInsensitive) {
                cls.caseFoldSimple();
            }
            return new Hir.Class(cls);
        }

        // --- Unicode character classes ---

        private Hir translateClassUnicode(Ast.ClassUnicode cu) throws Error {
            throw new Error(
                    Error.ErrorKind.UNICODE_PROPERTY_NOT_FOUND,
                    pattern, cu.span());
        }

        // --- Bracketed character classes ---

        private Hir translateClassBracketed(Ast.ClassBracketed cb) throws Error {
            ClassUnicode cls = translateClassSet(cb.kind());
            if (cb.negated()) {
                cls.negate();
            }
            if (flags.caseInsensitive) {
                cls.caseFoldSimple();
            }
            return new Hir.Class(cls);
        }

        private ClassUnicode translateClassSet(ClassSet set) throws Error {
            return switch (set) {
                case ClassSet.Item item -> translateClassSetItem(item.item());
                case ClassSet.BinaryOp binop -> translateClassSetBinaryOp(binop);
            };
        }

        private ClassUnicode translateClassSetItem(ClassSetItem item) throws Error {
            return switch (item) {
                case ClassSetItem.Empty e -> new ClassUnicode();
                case ClassSetItem.Literal lit -> {
                    char c = lit.c();
                    yield new ClassUnicode(List.of(new ClassUnicodeRange(c, c)));
                }
                case ClassSetItem.Range range -> {
                    int start = range.start().c();
                    int end = range.end().c();
                    yield new ClassUnicode(List.of(new ClassUnicodeRange(start, end)));
                }
                case ClassSetItem.Ascii ascii -> {
                    ClassUnicode cls = asciiClass(ascii.kind());
                    if (ascii.negated()) {
                        cls.negate();
                    }
                    yield cls;
                }
                case ClassSetItem.Unicode unicode ->
                        throw new Error(
                                Error.ErrorKind.UNICODE_PROPERTY_NOT_FOUND,
                                pattern, unicode.span());
                case ClassSetItem.Perl perl -> {
                    ClassUnicode cls = flags.unicode
                            ? perlClassUnicode(perl.kind())
                            : perlClassAscii(perl.kind());
                    if (perl.negated()) {
                        cls.negate();
                    }
                    yield cls;
                }
                case ClassSetItem.Bracketed bracketed -> {
                    ClassUnicode cls = translateClassSet(bracketed.kind());
                    if (bracketed.negated()) {
                        cls.negate();
                    }
                    yield cls;
                }
                case ClassSetItem.Union union -> {
                    ClassUnicode result = new ClassUnicode();
                    for (ClassSetItem sub : union.items()) {
                        result.union(translateClassSetItem(sub));
                    }
                    yield result;
                }
            };
        }

        private ClassUnicode translateClassSetBinaryOp(ClassSet.BinaryOp binop) throws Error {
            ClassUnicode lhs = translateClassSet(binop.lhs());
            ClassUnicode rhs = translateClassSet(binop.rhs());
            switch (binop.kind()) {
                case INTERSECTION -> lhs.intersect(rhs);
                case DIFFERENCE -> lhs.difference(rhs);
                case SYMMETRIC_DIFFERENCE -> lhs.symmetricDifference(rhs);
            }
            return lhs;
        }

        // --- Repetition ---

        private Hir translateRepetition(Ast.Repetition rep) throws Error {
            int min, max;
            switch (rep.op().kind()) {
                case RepetitionKind.ZeroOrOne() -> { min = 0; max = 1; }
                case RepetitionKind.ZeroOrMore() -> { min = 0; max = Hir.Repetition.UNBOUNDED; }
                case RepetitionKind.OneOrMore() -> { min = 1; max = Hir.Repetition.UNBOUNDED; }
                case RepetitionKind.Range(RepetitionRange range) -> {
                    switch (range) {
                        case RepetitionRange.Exactly(int n) -> { min = n; max = n; }
                        case RepetitionRange.AtLeast(int n) -> { min = n; max = Hir.Repetition.UNBOUNDED; }
                        case RepetitionRange.Bounded(int s, int e) -> { min = s; max = e; }
                    }
                }
            }
            boolean greedy = rep.greedy();
            if (flags.swapGreed) {
                greedy = !greedy;
            }
            Hir sub = translate(rep.ast());
            return new Hir.Repetition(min, max, greedy, sub);
        }

        // --- Groups ---

        private Hir translateGroup(Ast.Group grp) throws Error {
            return switch (grp.kind()) {
                case GroupKind.CaptureIndex(int index) -> {
                    Hir sub = translate(grp.ast());
                    yield new Hir.Capture(index, null, sub);
                }
                case GroupKind.CaptureName(boolean startsWithP, String name, int index) -> {
                    Hir sub = translate(grp.ast());
                    yield new Hir.Capture(index, name, sub);
                }
                case GroupKind.NonCapturing(FlagItems flagItems) -> {
                    // Save flags, apply group flags, translate, restore
                    Flags saved = flags.copy();
                    flags.apply(flagItems);
                    Hir sub = translate(grp.ast());
                    flags = saved;
                    yield sub;
                }
            };
        }

        // --- Alternation ---

        private Hir translateAlternation(Ast.Alternation alt) throws Error {
            List<Hir> subs = new ArrayList<>(alt.asts().size());
            for (Ast a : alt.asts()) {
                subs.add(translate(a));
            }
            if (subs.size() == 1) return subs.getFirst();
            return new Hir.Alternation(subs);
        }

        // --- Concat ---

        private Hir translateConcat(Ast.Concat concat) throws Error {
            List<Hir> subs = new ArrayList<>(concat.asts().size());
            for (Ast a : concat.asts()) {
                Hir h = translate(a);
                if (!(h instanceof Hir.Empty)) {
                    subs.add(h);
                }
            }
            if (subs.isEmpty()) return new Hir.Empty();
            if (subs.size() == 1) return subs.getFirst();
            return new Hir.Concat(subs);
        }

        // --- Flags ---

        private Hir translateFlags(Ast.Flags fl) {
            flags.apply(fl.flags());
            return new Hir.Empty();
        }

        // --- ASCII Perl classes ---

        static ClassUnicode perlClassAscii(ClassPerlKind kind) {
            return switch (kind) {
                case DIGIT -> ClassUnicode.of(
                        new ClassUnicodeRange('0', '9')
                );
                case SPACE -> ClassUnicode.of(
                        new ClassUnicodeRange('\t', '\r'),
                        new ClassUnicodeRange(' ', ' ')
                );
                case WORD -> ClassUnicode.of(
                        new ClassUnicodeRange('0', '9'),
                        new ClassUnicodeRange('A', 'Z'),
                        new ClassUnicodeRange('_', '_'),
                        new ClassUnicodeRange('a', 'z')
                );
            };
        }

        // --- Unicode Perl classes ---

        static ClassUnicode perlClassUnicode(ClassPerlKind kind) {
            int[][] table = switch (kind) {
                case DIGIT -> PerlClassTables.DECIMAL_NUMBER;
                case SPACE -> PerlClassTables.WHITE_SPACE;
                case WORD -> PerlClassTables.PERL_WORD;
            };
            List<ClassUnicodeRange> ranges = new ArrayList<>(table.length);
            for (int[] range : table) {
                ranges.add(new ClassUnicodeRange(range[0], range[1]));
            }
            return new ClassUnicode(ranges);
        }

        // --- ASCII character classes ---

        static ClassUnicode asciiClass(ClassAsciiKind kind) {
            return switch (kind) {
                case ALNUM -> ClassUnicode.of(
                        new ClassUnicodeRange('0', '9'),
                        new ClassUnicodeRange('A', 'Z'),
                        new ClassUnicodeRange('a', 'z')
                );
                case ALPHA -> ClassUnicode.of(
                        new ClassUnicodeRange('A', 'Z'),
                        new ClassUnicodeRange('a', 'z')
                );
                case ASCII -> ClassUnicode.of(
                        new ClassUnicodeRange(0x00, 0x7F)
                );
                case BLANK -> ClassUnicode.of(
                        new ClassUnicodeRange('\t', '\t'),
                        new ClassUnicodeRange(' ', ' ')
                );
                case CNTRL -> ClassUnicode.of(
                        new ClassUnicodeRange(0x00, 0x1F),
                        new ClassUnicodeRange(0x7F, 0x7F)
                );
                case DIGIT -> ClassUnicode.of(
                        new ClassUnicodeRange('0', '9')
                );
                case GRAPH -> ClassUnicode.of(
                        new ClassUnicodeRange('!', '~')
                );
                case LOWER -> ClassUnicode.of(
                        new ClassUnicodeRange('a', 'z')
                );
                case PRINT -> ClassUnicode.of(
                        new ClassUnicodeRange(' ', '~')
                );
                case PUNCT -> ClassUnicode.of(
                        new ClassUnicodeRange('!', '/'),
                        new ClassUnicodeRange(':', '@'),
                        new ClassUnicodeRange('[', '`'),
                        new ClassUnicodeRange('{', '~')
                );
                case SPACE -> ClassUnicode.of(
                        new ClassUnicodeRange('\t', '\t'),
                        new ClassUnicodeRange('\n', '\n'),
                        new ClassUnicodeRange(0x0B, 0x0B),
                        new ClassUnicodeRange(0x0C, 0x0C),
                        new ClassUnicodeRange('\r', '\r'),
                        new ClassUnicodeRange(' ', ' ')
                );
                case UPPER -> ClassUnicode.of(
                        new ClassUnicodeRange('A', 'Z')
                );
                case WORD -> ClassUnicode.of(
                        new ClassUnicodeRange('0', '9'),
                        new ClassUnicodeRange('A', 'Z'),
                        new ClassUnicodeRange('_', '_'),
                        new ClassUnicodeRange('a', 'z')
                );
                case XDIGIT -> ClassUnicode.of(
                        new ClassUnicodeRange('0', '9'),
                        new ClassUnicodeRange('A', 'F'),
                        new ClassUnicodeRange('a', 'f')
                );
            };
        }

        // --- Utility methods ---

        private static boolean isAsciiLetter(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        }

        /**
         * Encode a single char (BMP codepoint) to UTF-8 bytes.
         */
        private static byte[] charToUtf8(char c) {
            return String.valueOf(c).getBytes(StandardCharsets.UTF_8);
        }
    }
}
