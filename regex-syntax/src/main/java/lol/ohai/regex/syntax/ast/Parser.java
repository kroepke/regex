package lol.ohai.regex.syntax.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * A regular expression parser.
 *
 * <p>Parses a pattern string into an abstract syntax tree (AST).
 * The parser uses a stack-based approach for groups and character classes,
 * with true recursion limited by a nest limit.
 */
public final class Parser {

    private static final int DEFAULT_NEST_LIMIT = 250;

    // --- Public API ---

    public static Ast parse(String pattern) throws Error {
        return parse(pattern, DEFAULT_NEST_LIMIT);
    }

    public static Ast parse(String pattern, int nestLimit) throws Error {
        ParserState state = new ParserState(pattern, nestLimit);
        return state.parse();
    }

    // --- Internal parser state ---

    private static final class ParserState {
        private final String pattern;
        private final int nestLimit;
        private int offset;
        private int line;
        private int column;
        private int captureIndex;
        private final List<GroupState> stackGroup = new ArrayList<>();
        private final List<ClassState> stackClass = new ArrayList<>();
        private final List<String> captureNames = new ArrayList<>();
        private final List<Span> captureNameSpans = new ArrayList<>();

        ParserState(String pattern, int nestLimit) {
            this.pattern = pattern;
            this.nestLimit = nestLimit;
            this.offset = 0;
            this.line = 1;
            this.column = 1;
            this.captureIndex = 0;
        }

        // --- Position helpers ---

        Position pos() {
            return Position.of(offset, line, column);
        }

        Span span() {
            return Span.splat(pos());
        }

        Span spanChar() {
            Position start = pos();
            char c = charAt();
            Position end;
            if (c == '\n') {
                end = Position.of(offset + 1, line + 1, 1);
            } else {
                end = Position.of(offset + Character.charCount(c), line, column + 1);
            }
            return Span.of(start, end);
        }

        boolean isEof() {
            return offset >= pattern.length();
        }

        char charAt() {
            return pattern.charAt(offset);
        }

        boolean bump() {
            if (isEof()) return false;
            char c = charAt();
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
            offset += Character.charCount(c);
            return offset < pattern.length();
        }

        boolean bumpIf(String prefix) {
            if (pattern.startsWith(prefix, offset)) {
                for (int i = 0; i < prefix.length(); i++) {
                    bump();
                }
                return true;
            }
            return false;
        }

        char peek() {
            if (isEof()) return 0;
            int next = offset + Character.charCount(pattern.charAt(offset));
            if (next >= pattern.length()) return 0;
            return pattern.charAt(next);
        }

        Error error(Span span, ErrorKind kind) {
            return new Error(kind, pattern, span);
        }

        int nextCaptureIndex(Span span) throws Error {
            captureIndex++;
            if (captureIndex < 0) {
                throw error(span, new ErrorKind.CaptureLimitExceeded());
            }
            return captureIndex;
        }

        void addCaptureName(String name, Span nameSpan) throws Error {
            // Binary search for insertion point
            int idx = java.util.Collections.binarySearch(captureNames, name);
            if (idx >= 0) {
                throw error(nameSpan, new ErrorKind.GroupNameDuplicate(captureNameSpans.get(idx)));
            }
            int insertIdx = -(idx + 1);
            captureNames.add(insertIdx, name);
            captureNameSpans.add(insertIdx, nameSpan);
        }

        // --- Main parse loop ---

        Ast parse() throws Error {
            MutableConcat concat = new MutableConcat(span());
            while (!isEof()) {
                switch (charAt()) {
                    case '(' -> concat = pushGroup(concat);
                    case ')' -> concat = popGroup(concat);
                    case '|' -> concat = pushAlternate(concat);
                    case '[' -> {
                        var cls = parseSetClass();
                        concat.asts.add(new Ast.ClassBracketed(cls.span(), cls.negated(), cls.kind()));
                    }
                    case '?' -> concat = parseUncountedRepetition(concat, RepetitionKind.ZERO_OR_ONE);
                    case '*' -> concat = parseUncountedRepetition(concat, RepetitionKind.ZERO_OR_MORE);
                    case '+' -> concat = parseUncountedRepetition(concat, RepetitionKind.ONE_OR_MORE);
                    case '{' -> concat = parseCountedRepetition(concat);
                    default -> concat.asts.add(parsePrimitive().intoAst());
                }
            }
            Ast ast = popGroupEnd(concat);
            checkNestLimit(ast, 0);
            return ast;
        }

        // --- Primitive parsing ---

        private Primitive parsePrimitive() throws Error {
            char c = charAt();
            return switch (c) {
                case '\\' -> parseEscape();
                case '.' -> {
                    Span s = spanChar();
                    bump();
                    yield new Primitive.Dot(s);
                }
                case '^' -> {
                    Span s = spanChar();
                    bump();
                    yield new Primitive.Assert(s, AssertionKind.START_LINE);
                }
                case '$' -> {
                    Span s = spanChar();
                    bump();
                    yield new Primitive.Assert(s, AssertionKind.END_LINE);
                }
                default -> {
                    Span s = spanChar();
                    bump();
                    yield new Primitive.Lit(s, LiteralKind.VERBATIM, c);
                }
            };
        }

        private Primitive parseEscape() throws Error {
            Position start = pos();
            if (!bump()) {
                throw error(Span.of(start, pos()), new ErrorKind.EscapeUnexpectedEof());
            }
            char c = charAt();
            // Octal: digits 0-7 when octal mode would be on (we don't support octal, treat as backreference error)
            if (c >= '0' && c <= '9') {
                throw error(Span.of(start, spanChar().end()), new ErrorKind.UnsupportedBackreference());
            }
            // Hex
            if (c == 'x' || c == 'u' || c == 'U') {
                Ast.Literal lit = parseHex();
                return new Primitive.Lit(
                        Span.of(start, lit.span().end()),
                        lit.kind(),
                        lit.c()
                );
            }
            // Unicode class
            if (c == 'p' || c == 'P') {
                var cls = parseUnicodeClass();
                return new Primitive.UnicodeClass(
                        Span.of(start, cls.span().end()),
                        cls.negated(),
                        cls.kind()
                );
            }
            // Perl class
            if (c == 'd' || c == 'D' || c == 's' || c == 'S' || c == 'w' || c == 'W') {
                var cls = parsePerlClass();
                return new Primitive.PerlClass(
                        Span.of(start, cls.span().end()),
                        cls.kind(),
                        cls.negated()
                );
            }

            // Single char escapes
            bump();
            Span sp = Span.of(start, pos());
            if (isMetaCharacter(c)) {
                return new Primitive.Lit(sp, LiteralKind.META, c);
            }
            if (isEscapeableCharacter(c)) {
                return new Primitive.Lit(sp, LiteralKind.SUPERFLUOUS, c);
            }
            return switch (c) {
                case 'a' -> new Primitive.Lit(sp, new LiteralKind.Special(SpecialLiteralKind.BELL), '\u0007');
                case 'f' -> new Primitive.Lit(sp, new LiteralKind.Special(SpecialLiteralKind.FORM_FEED), '\f');
                case 't' -> new Primitive.Lit(sp, new LiteralKind.Special(SpecialLiteralKind.TAB), '\t');
                case 'n' -> new Primitive.Lit(sp, new LiteralKind.Special(SpecialLiteralKind.LINE_FEED), '\n');
                case 'r' -> new Primitive.Lit(sp, new LiteralKind.Special(SpecialLiteralKind.CARRIAGE_RETURN), '\r');
                case 'v' -> new Primitive.Lit(sp, new LiteralKind.Special(SpecialLiteralKind.VERTICAL_TAB), '\u000B');
                case 'A' -> new Primitive.Assert(sp, AssertionKind.START_TEXT);
                case 'z' -> new Primitive.Assert(sp, AssertionKind.END_TEXT);
                case 'b' -> {
                    var wb = new Primitive.Assert(sp, AssertionKind.WORD_BOUNDARY);
                    if (!isEof() && charAt() == '{') {
                        var kind = maybeParseSpecialWordBoundary(start);
                        if (kind != null) {
                            yield new Primitive.Assert(Span.of(start, pos()), kind);
                        }
                    }
                    yield wb;
                }
                case 'B' -> new Primitive.Assert(sp, AssertionKind.NOT_WORD_BOUNDARY);
                case '<' -> new Primitive.Assert(sp, AssertionKind.WORD_BOUNDARY_START_ANGLE);
                case '>' -> new Primitive.Assert(sp, AssertionKind.WORD_BOUNDARY_END_ANGLE);
                default -> throw error(sp, new ErrorKind.EscapeUnrecognized());
            };
        }

        private AssertionKind maybeParseSpecialWordBoundary(Position wbStart) throws Error {
            // Save position to backtrack
            int savedOffset = offset;
            int savedLine = line;
            int savedColumn = column;

            assert charAt() == '{';
            if (!bump()) {
                throw error(Span.of(wbStart, pos()), new ErrorKind.SpecialWordOrRepetitionUnexpectedEof());
            }

            // Check if first char is valid for special word boundary
            if (!isValidSpecialWordBoundaryChar(charAt())) {
                offset = savedOffset;
                line = savedLine;
                column = savedColumn;
                return null;
            }

            StringBuilder sb = new StringBuilder();
            Position startContents = pos();
            while (!isEof() && isValidSpecialWordBoundaryChar(charAt())) {
                sb.append(charAt());
                bump();
            }

            if (isEof() || charAt() != '}') {
                throw error(Span.of(Span.of(wbStart, pos()).start(), pos()),
                        new ErrorKind.SpecialWordBoundaryUnclosed());
            }
            Position end = pos();
            bump();

            return switch (sb.toString()) {
                case "start" -> AssertionKind.WORD_BOUNDARY_START;
                case "end" -> AssertionKind.WORD_BOUNDARY_END;
                case "start-half" -> AssertionKind.WORD_BOUNDARY_START_HALF;
                case "end-half" -> AssertionKind.WORD_BOUNDARY_END_HALF;
                default -> throw error(Span.of(startContents, end),
                        new ErrorKind.SpecialWordBoundaryUnrecognized());
            };
        }

        private boolean isValidSpecialWordBoundaryChar(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '-';
        }

        // --- Hex parsing ---

        private Ast.Literal parseHex() throws Error {
            char prefix = charAt();
            HexLiteralKind hexKind = switch (prefix) {
                case 'x' -> HexLiteralKind.X;
                case 'u' -> HexLiteralKind.UNICODE_SHORT;
                default -> HexLiteralKind.UNICODE_LONG;
            };
            if (!bump()) {
                throw error(span(), new ErrorKind.EscapeUnexpectedEof());
            }
            if (charAt() == '{') {
                return parseHexBrace(hexKind);
            } else {
                return parseHexDigits(hexKind);
            }
        }

        private Ast.Literal parseHexDigits(HexLiteralKind kind) throws Error {
            StringBuilder sb = new StringBuilder();
            Position start = pos();
            for (int i = 0; i < kind.digits(); i++) {
                if (i > 0 && !bump()) {
                    throw error(span(), new ErrorKind.EscapeUnexpectedEof());
                }
                if (!isHex(charAt())) {
                    throw error(spanChar(), new ErrorKind.EscapeHexInvalidDigit());
                }
                sb.append(charAt());
            }
            bump();
            Position end = pos();
            int codePoint = Integer.parseInt(sb.toString(), 16);
            if (!Character.isValidCodePoint(codePoint) || Character.isSurrogate((char) codePoint)) {
                throw error(Span.of(start, end), new ErrorKind.EscapeHexInvalid());
            }
            return new Ast.Literal(Span.of(start, end), new LiteralKind.HexFixed(kind), (char) codePoint);
        }

        private Ast.Literal parseHexBrace(HexLiteralKind kind) throws Error {
            Position bracePos = pos();
            Position start = spanChar().end();
            StringBuilder sb = new StringBuilder();
            while (bump() && charAt() != '}') {
                if (!isHex(charAt())) {
                    throw error(spanChar(), new ErrorKind.EscapeHexInvalidDigit());
                }
                sb.append(charAt());
            }
            if (isEof()) {
                throw error(Span.of(bracePos, pos()), new ErrorKind.EscapeUnexpectedEof());
            }
            assert charAt() == '}';
            bump();

            if (sb.isEmpty()) {
                throw error(Span.of(bracePos, pos()), new ErrorKind.EscapeHexEmpty());
            }
            int codePoint = Integer.parseInt(sb.toString(), 16);
            if (!Character.isValidCodePoint(codePoint) ||
                    (codePoint <= 0xFFFF && Character.isSurrogate((char) codePoint))) {
                throw error(Span.of(start, pos()), new ErrorKind.EscapeHexInvalid());
            }
            char c = (char) codePoint; // For BMP characters
            if (codePoint > 0xFFFF) {
                // For supplementary characters, store codepoint. For now, limit to BMP.
                c = (char) codePoint;
            }
            return new Ast.Literal(Span.of(start, pos()), new LiteralKind.HexBrace(kind), c);
        }

        // --- Unicode class ---

        private Ast.ClassUnicode parseUnicodeClass() throws Error {
            boolean negated = charAt() == 'P';
            if (!bump()) {
                throw error(span(), new ErrorKind.EscapeUnexpectedEof());
            }
            Position start;
            ClassUnicodeKind kind;
            if (charAt() == '{') {
                start = spanChar().end();
                StringBuilder sb = new StringBuilder();
                while (bump() && charAt() != '}') {
                    sb.append(charAt());
                }
                if (isEof()) {
                    throw error(span(), new ErrorKind.EscapeUnexpectedEof());
                }
                assert charAt() == '}';
                bump();
                String name = sb.toString();
                int neIdx = name.indexOf("!=");
                int colonIdx = name.indexOf(':');
                int eqIdx = name.indexOf('=');
                if (neIdx >= 0) {
                    kind = new ClassUnicodeKind.NamedValue(
                            ClassUnicodeOpKind.NOT_EQUAL,
                            name.substring(0, neIdx),
                            name.substring(neIdx + 2));
                } else if (colonIdx >= 0) {
                    kind = new ClassUnicodeKind.NamedValue(
                            ClassUnicodeOpKind.COLON,
                            name.substring(0, colonIdx),
                            name.substring(colonIdx + 1));
                } else if (eqIdx >= 0) {
                    kind = new ClassUnicodeKind.NamedValue(
                            ClassUnicodeOpKind.EQUAL,
                            name.substring(0, eqIdx),
                            name.substring(eqIdx + 1));
                } else {
                    kind = new ClassUnicodeKind.Named(name);
                }
            } else {
                start = pos();
                char c = charAt();
                if (c == '\\') {
                    throw error(spanChar(), new ErrorKind.UnicodeClassInvalid());
                }
                bump();
                kind = new ClassUnicodeKind.OneLetter(c);
            }
            return new Ast.ClassUnicode(Span.of(start, pos()), negated, kind);
        }

        // --- Perl class ---

        private Ast.ClassPerl parsePerlClass() {
            char c = charAt();
            Span sp = spanChar();
            bump();
            return switch (c) {
                case 'd' -> new Ast.ClassPerl(sp, ClassPerlKind.DIGIT, false);
                case 'D' -> new Ast.ClassPerl(sp, ClassPerlKind.DIGIT, true);
                case 's' -> new Ast.ClassPerl(sp, ClassPerlKind.SPACE, false);
                case 'S' -> new Ast.ClassPerl(sp, ClassPerlKind.SPACE, true);
                case 'w' -> new Ast.ClassPerl(sp, ClassPerlKind.WORD, false);
                case 'W' -> new Ast.ClassPerl(sp, ClassPerlKind.WORD, true);
                default -> throw new AssertionError("expected perl class but got: " + c);
            };
        }

        // --- Repetition ---

        private MutableConcat parseUncountedRepetition(MutableConcat concat, RepetitionKind kind) throws Error {
            Position opStart = pos();
            if (concat.asts.isEmpty()) {
                throw error(span(), new ErrorKind.RepetitionMissing());
            }
            Ast ast = concat.asts.removeLast();
            if (ast instanceof Ast.Empty || ast instanceof Ast.Flags) {
                throw error(span(), new ErrorKind.RepetitionMissing());
            }
            boolean greedy = true;
            if (bump() && charAt() == '?') {
                greedy = false;
                bump();
            }
            concat.asts.add(new Ast.Repetition(
                    ast.span().withEnd(pos()),
                    new RepetitionOp(Span.of(opStart, pos()), kind),
                    greedy,
                    ast
            ));
            return concat;
        }

        private MutableConcat parseCountedRepetition(MutableConcat concat) throws Error {
            Position start = pos();
            if (concat.asts.isEmpty()) {
                throw error(span(), new ErrorKind.RepetitionMissing());
            }
            Ast ast = concat.asts.removeLast();
            if (ast instanceof Ast.Empty || ast instanceof Ast.Flags) {
                throw error(span(), new ErrorKind.RepetitionMissing());
            }
            if (!bump()) {
                throw error(Span.of(start, pos()), new ErrorKind.RepetitionCountUnclosed());
            }
            int countStart = parseDecimalOr(new ErrorKind.RepetitionCountDecimalEmpty());
            if (isEof()) {
                throw error(Span.of(start, pos()), new ErrorKind.RepetitionCountUnclosed());
            }

            RepetitionRange range;
            if (charAt() == ',') {
                if (!bump()) {
                    throw error(Span.of(start, pos()), new ErrorKind.RepetitionCountUnclosed());
                }
                if (charAt() != '}') {
                    int countEnd = parseDecimalOr(new ErrorKind.RepetitionCountDecimalEmpty());
                    range = new RepetitionRange.Bounded(countStart, countEnd);
                } else {
                    range = new RepetitionRange.AtLeast(countStart);
                }
            } else {
                range = new RepetitionRange.Exactly(countStart);
            }

            if (isEof() || charAt() != '}') {
                throw error(Span.of(start, pos()), new ErrorKind.RepetitionCountUnclosed());
            }

            boolean greedy = true;
            if (bump() && charAt() == '?') {
                greedy = false;
                bump();
            }

            Span opSpan = Span.of(start, pos());
            if (!range.isValid()) {
                throw error(opSpan, new ErrorKind.RepetitionCountInvalid());
            }
            concat.asts.add(new Ast.Repetition(
                    ast.span().withEnd(pos()),
                    new RepetitionOp(opSpan, new RepetitionKind.Range(range)),
                    greedy,
                    ast
            ));
            return concat;
        }

        private int parseDecimalOr(ErrorKind emptyError) throws Error {
            Position start = pos();
            StringBuilder sb = new StringBuilder();
            while (!isEof() && charAt() >= '0' && charAt() <= '9') {
                sb.append(charAt());
                bump();
            }
            if (sb.isEmpty()) {
                throw error(Span.of(start, pos()), emptyError);
            }
            try {
                return Integer.parseInt(sb.toString());
            } catch (NumberFormatException e) {
                throw error(Span.of(start, pos()), new ErrorKind.DecimalInvalid());
            }
        }

        // --- Group parsing ---

        private MutableConcat pushGroup(MutableConcat concat) throws Error {
            assert charAt() == '(';
            Span openSpan = spanChar();
            bump();

            // Check for look-around (unsupported)
            if (isLookaroundPrefix()) {
                throw error(Span.of(openSpan.start(), span().end()), new ErrorKind.UnsupportedLookAround());
            }

            Span innerSpan = span();
            boolean startsWithP = true;
            if (bumpIf("?P<") || (startsWithP = false) == false && bumpIf("?<")) {
                // Named capture
                int idx = nextCaptureIndex(openSpan);
                String name = parseCaptureNameString(idx);
                stackGroup.add(new GroupState.Group(concat, openSpan,
                        new GroupKind.CaptureName(startsWithP, name, idx)));
                return new MutableConcat(span());
            } else if (bumpIf("?")) {
                if (isEof()) {
                    throw error(openSpan, new ErrorKind.GroupUnclosed());
                }
                FlagItems flags = parseFlags();
                char end = charAt();
                bump();
                if (end == ')') {
                    if (flags.isEmpty()) {
                        throw error(innerSpan, new ErrorKind.RepetitionMissing());
                    }
                    concat.asts.add(new Ast.Flags(Span.of(openSpan.start(), pos()), flags));
                    return concat;
                } else {
                    assert end == ':';
                    stackGroup.add(new GroupState.Group(concat, openSpan,
                            new GroupKind.NonCapturing(flags)));
                    return new MutableConcat(span());
                }
            } else {
                int idx = nextCaptureIndex(openSpan);
                stackGroup.add(new GroupState.Group(concat, openSpan,
                        new GroupKind.CaptureIndex(idx)));
                return new MutableConcat(span());
            }
        }

        private boolean isLookaroundPrefix() {
            return bumpIf("?=") || bumpIf("?!") || bumpIf("?<=") || bumpIf("?<!");
        }

        private MutableConcat popGroup(MutableConcat groupConcat) throws Error {
            assert charAt() == ')';

            // Pop alternation if present
            MutableAlternation alt = null;
            if (!stackGroup.isEmpty() && stackGroup.getLast() instanceof GroupState.Alt a) {
                alt = a.alt;
                stackGroup.removeLast();
            }

            if (stackGroup.isEmpty() || !(stackGroup.getLast() instanceof GroupState.Group gs)) {
                throw error(spanChar(), new ErrorKind.GroupUnopened());
            }
            stackGroup.removeLast();

            groupConcat.spanEnd = pos();
            bump();
            Span groupSpan = Span.of(gs.openSpan.start(), pos());

            Ast inner;
            if (alt != null) {
                alt.spanEnd = groupConcat.spanEnd;
                alt.asts.add(groupConcat.intoAst());
                inner = alt.intoAst();
            } else {
                inner = groupConcat.intoAst();
            }

            gs.priorConcat.asts.add(new Ast.Group(groupSpan, gs.kind, inner));
            return gs.priorConcat;
        }

        private MutableConcat pushAlternate(MutableConcat concat) throws Error {
            assert charAt() == '|';
            concat.spanEnd = pos();

            // Check if top of stack is already an alternation
            if (!stackGroup.isEmpty() && stackGroup.getLast() instanceof GroupState.Alt a) {
                a.alt.asts.add(concat.intoAst());
            } else {
                MutableAlternation newAlt = new MutableAlternation(concat.spanStart);
                newAlt.asts.add(concat.intoAst());
                stackGroup.add(new GroupState.Alt(newAlt));
            }
            bump();
            return new MutableConcat(span());
        }

        private Ast popGroupEnd(MutableConcat concat) throws Error {
            concat.spanEnd = pos();
            if (stackGroup.isEmpty()) {
                return concat.intoAst();
            }
            var last = stackGroup.removeLast();
            if (last instanceof GroupState.Alt a) {
                a.alt.spanEnd = pos();
                a.alt.asts.add(concat.intoAst());
                if (!stackGroup.isEmpty()) {
                    var next = stackGroup.removeLast();
                    if (next instanceof GroupState.Group gs) {
                        throw error(gs.openSpan, new ErrorKind.GroupUnclosed());
                    }
                }
                return a.alt.intoAst();
            } else if (last instanceof GroupState.Group gs) {
                throw error(gs.openSpan, new ErrorKind.GroupUnclosed());
            }
            throw new AssertionError("unreachable");
        }

        // --- Capture name ---

        private String parseCaptureNameString(int captureIndex) throws Error {
            if (isEof()) {
                throw error(span(), new ErrorKind.GroupNameUnexpectedEof());
            }
            Position start = pos();
            while (!isEof() && charAt() != '>') {
                if (!isCaptureChar(charAt(), pos().offset() == start.offset())) {
                    throw error(spanChar(), new ErrorKind.GroupNameInvalid());
                }
                if (!bump()) break;
            }
            Position end = pos();
            if (isEof()) {
                throw error(span(), new ErrorKind.GroupNameUnexpectedEof());
            }
            assert charAt() == '>';
            bump();
            String name = pattern.substring(start.offset(), end.offset());
            if (name.isEmpty()) {
                throw error(Span.of(start, start), new ErrorKind.GroupNameEmpty());
            }
            Span nameSpan = Span.of(start, end);
            addCaptureName(name, nameSpan);
            return name;
        }

        // --- Flags ---

        private FlagItems parseFlags() throws Error {
            FlagItems flags = new FlagItems(span());
            Span lastNegation = null;
            while (charAt() != ':' && charAt() != ')') {
                if (charAt() == '-') {
                    lastNegation = spanChar();
                    FlagsItem item = new FlagsItem(spanChar(), FlagsItemKind.NEGATION);
                    int dup = flags.addItem(item);
                    if (dup >= 0) {
                        throw error(spanChar(), new ErrorKind.FlagRepeatedNegation(flags.items().get(dup).span()));
                    }
                } else {
                    lastNegation = null;
                    Flag flag = parseFlag();
                    FlagsItem item = new FlagsItem(spanChar(), new FlagsItemKind.FlagKind(flag));
                    int dup = flags.addItem(item);
                    if (dup >= 0) {
                        throw error(spanChar(), new ErrorKind.FlagDuplicate(flags.items().get(dup).span()));
                    }
                }
                if (!bump()) {
                    throw error(span(), new ErrorKind.FlagUnexpectedEof());
                }
            }
            if (lastNegation != null) {
                throw error(lastNegation, new ErrorKind.FlagDanglingNegation());
            }
            return flags;
        }

        private Flag parseFlag() throws Error {
            return switch (charAt()) {
                case 'i' -> Flag.CASE_INSENSITIVE;
                case 'm' -> Flag.MULTI_LINE;
                case 's' -> Flag.DOT_MATCHES_NEW_LINE;
                case 'U' -> Flag.SWAP_GREED;
                case 'u' -> Flag.UNICODE;
                case 'R' -> Flag.CRLF;
                case 'x' -> Flag.IGNORE_WHITESPACE;
                default -> throw error(spanChar(), new ErrorKind.FlagUnrecognized());
            };
        }

        // --- Character class parsing ---

        private Ast.ClassBracketed parseSetClass() throws Error {
            assert charAt() == '[';
            ClassSetUnion union = new ClassSetUnion(span());

            // We use a loop like the upstream Rust code
            while (true) {
                if (isEof()) {
                    throw unclosedClassError();
                }
                char c = charAt();
                if (c == '[') {
                    // If we've already opened a bracket, try ASCII class
                    if (!stackClass.isEmpty()) {
                        var ascii = maybeParseAsciiClass();
                        if (ascii != null) {
                            union.push(ascii);
                            continue;
                        }
                    }
                    union = pushClassOpen(union);
                } else if (c == ']') {
                    var result = popClass(union);
                    if (result instanceof PopClassResult.Nested n) {
                        union = n.union;
                    } else if (result instanceof PopClassResult.Done d) {
                        return d.cls;
                    }
                } else if (c == '&' && peek() == '&') {
                    bumpIf("&&");
                    union = pushClassOp(ClassSetBinaryOpKind.INTERSECTION, union);
                } else if (c == '-' && peek() == '-') {
                    bumpIf("--");
                    union = pushClassOp(ClassSetBinaryOpKind.DIFFERENCE, union);
                } else if (c == '~' && peek() == '~') {
                    bumpIf("~~");
                    union = pushClassOp(ClassSetBinaryOpKind.SYMMETRIC_DIFFERENCE, union);
                } else {
                    union.push(parseSetClassRange());
                }
            }
        }

        private ClassSetUnion pushClassOpen(ClassSetUnion parentUnion) throws Error {
            assert charAt() == '[';
            var openResult = parseSetClassOpen();
            stackClass.add(new ClassState.Open(parentUnion, openResult.set));
            return openResult.union;
        }

        private sealed interface PopClassResult {
            record Nested(ClassSetUnion union) implements PopClassResult {}
            record Done(Ast.ClassBracketed cls) implements PopClassResult {}
        }

        private PopClassResult popClass(ClassSetUnion nestedUnion) throws Error {
            assert charAt() == ']';
            ClassSet item = new ClassSet.Item(nestedUnion.intoItem());
            ClassSet prevSet = popClassOp(item);
            if (stackClass.isEmpty()) {
                throw new AssertionError("unexpected empty character class stack");
            }
            var state = stackClass.removeLast();
            if (!(state instanceof ClassState.Open openState)) {
                throw new AssertionError("unexpected ClassState.Op");
            }
            bump();
            Span clsSpan = Span.of(openState.set.span().start(), pos());
            var cls = new Ast.ClassBracketed(clsSpan, openState.set.negated(), prevSet);
            if (stackClass.isEmpty()) {
                return new PopClassResult.Done(cls);
            } else {
                openState.parentUnion.push(new ClassSetItem.Bracketed(clsSpan, openState.set.negated(), prevSet));
                return new PopClassResult.Nested(openState.parentUnion);
            }
        }

        private ClassSetUnion pushClassOp(ClassSetBinaryOpKind nextKind, ClassSetUnion nextUnion) {
            ClassSet item = new ClassSet.Item(nextUnion.intoItem());
            ClassSet newLhs = popClassOp(item);
            stackClass.add(new ClassState.Op(nextKind, newLhs));
            return new ClassSetUnion(span());
        }

        private ClassSet popClassOp(ClassSet rhs) {
            if (stackClass.isEmpty()) return rhs;
            var last = stackClass.getLast();
            if (last instanceof ClassState.Op opState) {
                stackClass.removeLast();
                Span sp = Span.of(opState.lhs.span().start(), rhs.span().end());
                return new ClassSet.BinaryOp(sp, opState.kind, opState.lhs, rhs);
            }
            return rhs;
        }

        private Error unclosedClassError() {
            for (int i = stackClass.size() - 1; i >= 0; i--) {
                if (stackClass.get(i) instanceof ClassState.Open openState) {
                    return error(openState.set.span(), new ErrorKind.ClassUnclosed());
                }
            }
            throw new AssertionError("no open character class found");
        }

        private record SetClassOpenResult(Ast.ClassBracketed set, ClassSetUnion union) {}

        private SetClassOpenResult parseSetClassOpen() throws Error {
            assert charAt() == '[';
            Position start = pos();
            if (!bump()) {
                throw error(Span.of(start, pos()), new ErrorKind.ClassUnclosed());
            }

            boolean negated = false;
            if (charAt() == '^') {
                negated = true;
                if (!bump()) {
                    throw error(Span.of(start, pos()), new ErrorKind.ClassUnclosed());
                }
            }

            ClassSetUnion union = new ClassSetUnion(span());
            // Accept any number of '-' as literal '-'
            while (charAt() == '-') {
                union.push(new ClassSetItem.Literal(spanChar(), LiteralKind.VERBATIM, '-'));
                if (!bump()) {
                    throw error(Span.of(start, start), new ErrorKind.ClassUnclosed());
                }
            }
            // If ']' is the first char, treat as literal
            if (union.items().isEmpty() && charAt() == ']') {
                union.push(new ClassSetItem.Literal(spanChar(), LiteralKind.VERBATIM, ']'));
                if (!bump()) {
                    throw error(Span.of(start, pos()), new ErrorKind.ClassUnclosed());
                }
            }

            var set = new Ast.ClassBracketed(
                    Span.of(start, pos()), negated,
                    ClassSet.union(new ClassSetUnion(Span.of(union.span().start(), union.span().start())))
            );
            return new SetClassOpenResult(set, union);
        }

        private ClassSetItem parseSetClassRange() throws Error {
            Primitive prim1 = parseSetClassItem();
            if (isEof()) {
                throw unclosedClassError();
            }
            // Check for range
            if (charAt() != '-' || peek() == ']' || peek() == '-') {
                return prim1.intoClassSetItem(this);
            }
            // Parse range
            if (!bump()) {
                throw unclosedClassError();
            }
            Primitive prim2 = parseSetClassItem();
            var startLit = prim1.intoClassLiteral(this);
            var endLit = prim2.intoClassLiteral(this);
            Span rangeSpan = Span.of(prim1.span().start(), prim2.span().end());
            if (startLit.c() > endLit.c()) {
                throw error(rangeSpan, new ErrorKind.ClassRangeInvalid());
            }
            return new ClassSetItem.Range(rangeSpan, startLit, endLit);
        }

        private Primitive parseSetClassItem() throws Error {
            if (charAt() == '\\') {
                return parseEscape();
            } else {
                char c = charAt();
                Span s = spanChar();
                bump();
                return new Primitive.Lit(s, LiteralKind.VERBATIM, c);
            }
        }

        private ClassSetItem.Ascii maybeParseAsciiClass() {
            assert charAt() == '[';
            int savedOffset = offset;
            int savedLine = line;
            int savedColumn = column;
            Position start = pos();

            if (!bump() || charAt() != ':') {
                offset = savedOffset; line = savedLine; column = savedColumn;
                return null;
            }
            if (!bump()) {
                offset = savedOffset; line = savedLine; column = savedColumn;
                return null;
            }
            boolean negated = false;
            if (charAt() == '^') {
                negated = true;
                if (!bump()) {
                    offset = savedOffset; line = savedLine; column = savedColumn;
                    return null;
                }
            }
            int nameStart = offset;
            while (charAt() != ':' && bump()) {}
            if (isEof()) {
                offset = savedOffset; line = savedLine; column = savedColumn;
                return null;
            }
            String name = pattern.substring(nameStart, offset);
            if (!bumpIf(":]")) {
                offset = savedOffset; line = savedLine; column = savedColumn;
                return null;
            }
            ClassAsciiKind kind = ClassAsciiKind.fromName(name);
            if (kind == null) {
                offset = savedOffset; line = savedLine; column = savedColumn;
                return null;
            }
            return new ClassSetItem.Ascii(Span.of(start, pos()), kind, negated);
        }

        // --- Nest limit check ---

        private void checkNestLimit(Ast ast, int depth) throws Error {
            int newDepth = depth;
            boolean increments = switch (ast) {
                case Ast.ClassBracketed ignored -> true;
                case Ast.Repetition ignored -> true;
                case Ast.Group ignored -> true;
                case Ast.Alternation ignored -> true;
                case Ast.Concat ignored -> true;
                default -> false;
            };
            if (increments) {
                newDepth = depth + 1;
                if (newDepth > nestLimit) {
                    throw error(ast.span(), new ErrorKind.NestLimitExceeded(nestLimit));
                }
            }
            switch (ast) {
                case Ast.Repetition r -> checkNestLimit(r.ast(), newDepth);
                case Ast.Group g -> checkNestLimit(g.ast(), newDepth);
                case Ast.Alternation a -> {
                    for (Ast child : a.asts()) checkNestLimit(child, newDepth);
                }
                case Ast.Concat c -> {
                    for (Ast child : c.asts()) checkNestLimit(child, newDepth);
                }
                case Ast.ClassBracketed cb -> checkClassSetNestLimit(cb.kind(), newDepth);
                default -> {}
            }
        }

        private void checkClassSetNestLimit(ClassSet set, int depth) throws Error {
            switch (set) {
                case ClassSet.Item item -> checkClassSetItemNestLimit(item.item(), depth);
                case ClassSet.BinaryOp op -> {
                    int newDepth = depth + 1;
                    if (newDepth > nestLimit) {
                        throw error(op.span(), new ErrorKind.NestLimitExceeded(nestLimit));
                    }
                    checkClassSetNestLimit(op.lhs(), newDepth);
                    checkClassSetNestLimit(op.rhs(), newDepth);
                }
            }
        }

        private void checkClassSetItemNestLimit(ClassSetItem item, int depth) throws Error {
            switch (item) {
                case ClassSetItem.Bracketed b -> {
                    int newDepth = depth + 1;
                    if (newDepth > nestLimit) {
                        throw error(b.span(), new ErrorKind.NestLimitExceeded(nestLimit));
                    }
                    checkClassSetNestLimit(b.kind(), newDepth);
                }
                case ClassSetItem.Union u -> {
                    int newDepth = depth + 1;
                    if (newDepth > nestLimit) {
                        throw error(u.span(), new ErrorKind.NestLimitExceeded(nestLimit));
                    }
                    for (ClassSetItem child : u.items()) {
                        checkClassSetItemNestLimit(child, newDepth);
                    }
                }
                default -> {}
            }
        }

        // --- Utility ---

        static boolean isMetaCharacter(char c) {
            return switch (c) {
                case '\\', '.', '+', '*', '?', '(', ')', '|', '[', ']', '{', '}', '^', '$', '#', '&', '-', '~' -> true;
                default -> false;
            };
        }

        static boolean isEscapeableCharacter(char c) {
            if (isMetaCharacter(c)) return true;
            if (c > 127) return false; // non-ASCII not escapeable
            return switch (c) {
                case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> false;
                case 'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M' -> false;
                case 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z' -> false;
                case 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm' -> false;
                case 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z' -> false;
                case '<', '>' -> false;
                default -> true;
            };
        }

        static boolean isHex(char c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        static boolean isCaptureChar(char c, boolean first) {
            if (first) {
                return c == '_' || Character.isAlphabetic(c);
            }
            return c == '_' || c == '.' || c == '[' || c == ']' || Character.isLetterOrDigit(c);
        }
    }

    // --- Internal mutable state types ---

    private sealed interface GroupState {
        record Group(MutableConcat priorConcat, Span openSpan, GroupKind kind) implements GroupState {}
        record Alt(MutableAlternation alt) implements GroupState {}
    }

    private sealed interface ClassState {
        record Open(ClassSetUnion parentUnion, Ast.ClassBracketed set) implements ClassState {}
        record Op(ClassSetBinaryOpKind kind, ClassSet lhs) implements ClassState {}
    }

    private static final class MutableConcat {
        Position spanStart;
        Position spanEnd;
        final List<Ast> asts = new ArrayList<>();

        MutableConcat(Span span) {
            this.spanStart = span.start();
            this.spanEnd = span.end();
        }

        Ast intoAst() {
            Span sp = Span.of(spanStart, spanEnd);
            return switch (asts.size()) {
                case 0 -> new Ast.Empty(sp);
                case 1 -> asts.getFirst();
                default -> new Ast.Concat(sp, List.copyOf(asts));
            };
        }
    }

    private static final class MutableAlternation {
        Position spanStart;
        Position spanEnd;
        final List<Ast> asts = new ArrayList<>();

        MutableAlternation(Position spanStart) {
            this.spanStart = spanStart;
            this.spanEnd = spanStart;
        }

        Ast intoAst() {
            Span sp = Span.of(spanStart, spanEnd);
            return switch (asts.size()) {
                case 0 -> new Ast.Empty(sp);
                case 1 -> asts.getFirst();
                default -> new Ast.Alternation(sp, List.copyOf(asts));
            };
        }
    }

    // --- Primitive type used as intermediate state during parsing ---

    private sealed interface Primitive {
        Span span();

        record Lit(Span span, LiteralKind kind, char c) implements Primitive {}
        record Assert(Span span, AssertionKind kind) implements Primitive {}
        record Dot(Span span) implements Primitive {}
        record PerlClass(Span span, ClassPerlKind kind, boolean negated) implements Primitive {}
        record UnicodeClass(Span span, boolean negated, ClassUnicodeKind kind) implements Primitive {}

        default Ast intoAst() {
            return switch (this) {
                case Lit l -> new Ast.Literal(l.span, l.kind, l.c);
                case Assert a -> new Ast.Assertion(a.span, a.kind);
                case Dot d -> new Ast.Dot(d.span);
                case PerlClass p -> new Ast.ClassPerl(p.span, p.kind, p.negated);
                case UnicodeClass u -> new Ast.ClassUnicode(u.span, u.negated, u.kind);
            };
        }

        default ClassSetItem intoClassSetItem(ParserState p) throws Error {
            return switch (this) {
                case Lit l -> new ClassSetItem.Literal(l.span, l.kind, l.c);
                case PerlClass pc -> new ClassSetItem.Perl(pc.span, pc.kind, pc.negated);
                case UnicodeClass uc -> new ClassSetItem.Unicode(uc.span, uc.negated, uc.kind);
                default -> throw p.error(span(), new ErrorKind.ClassEscapeInvalid());
            };
        }

        default ClassSetItem.Literal intoClassLiteral(ParserState p) throws Error {
            if (this instanceof Lit l) {
                return new ClassSetItem.Literal(l.span, l.kind, l.c);
            }
            throw p.error(span(), new ErrorKind.ClassRangeLiteral());
        }
    }
}
