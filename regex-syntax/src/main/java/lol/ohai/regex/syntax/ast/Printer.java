package lol.ohai.regex.syntax.ast;

/**
 * A printer for a regular expression abstract syntax tree.
 *
 * <p>Produces a regex pattern string from an AST, suitable for round-trip testing.
 */
public final class Printer {

    public static String print(Ast ast) {
        StringBuilder sb = new StringBuilder();
        printAst(ast, sb);
        return sb.toString();
    }

    private static void printAst(Ast ast, StringBuilder sb) {
        switch (ast) {
            case Ast.Empty ignored -> {}
            case Ast.Flags f -> printSetFlags(f, sb);
            case Ast.Literal lit -> printLiteral(lit.kind(), lit.c(), sb);
            case Ast.Dot ignored -> sb.append('.');
            case Ast.Assertion a -> printAssertion(a.kind(), sb);
            case Ast.ClassPerl cp -> printClassPerl(cp.kind(), cp.negated(), sb);
            case Ast.ClassUnicode cu -> printClassUnicode(cu.negated(), cu.kind(), sb);
            case Ast.ClassBracketed cb -> printClassBracketed(cb, sb);
            case Ast.Repetition r -> {
                printAst(r.ast(), sb);
                printRepetition(r.op().kind(), r.greedy(), sb);
            }
            case Ast.Group g -> printGroup(g, sb);
            case Ast.Alternation a -> {
                for (int i = 0; i < a.asts().size(); i++) {
                    if (i > 0) sb.append('|');
                    printAst(a.asts().get(i), sb);
                }
            }
            case Ast.Concat c -> {
                for (Ast child : c.asts()) {
                    printAst(child, sb);
                }
            }
        }
    }

    private static void printLiteral(LiteralKind kind, char c, StringBuilder sb) {
        switch (kind) {
            case LiteralKind.Verbatim ignored -> sb.append(c);
            case LiteralKind.Meta ignored -> sb.append('\\').append(c);
            case LiteralKind.Superfluous ignored -> sb.append('\\').append(c);
            case LiteralKind.Octal ignored -> sb.append('\\').append(Integer.toOctalString(c));
            case LiteralKind.HexFixed hf -> {
                switch (hf.hexKind()) {
                    case X -> sb.append(String.format("\\x%02X", (int) c));
                    case UNICODE_SHORT -> sb.append(String.format("\\u%04X", (int) c));
                    case UNICODE_LONG -> sb.append(String.format("\\U%08X", (int) c));
                }
            }
            case LiteralKind.HexBrace hb -> {
                switch (hb.hexKind()) {
                    case X -> sb.append(String.format("\\x{%X}", (int) c));
                    case UNICODE_SHORT -> sb.append(String.format("\\u{%X}", (int) c));
                    case UNICODE_LONG -> sb.append(String.format("\\U{%X}", (int) c));
                }
            }
            case LiteralKind.Special sp -> {
                switch (sp.specialKind()) {
                    case BELL -> sb.append("\\a");
                    case FORM_FEED -> sb.append("\\f");
                    case TAB -> sb.append("\\t");
                    case LINE_FEED -> sb.append("\\n");
                    case CARRIAGE_RETURN -> sb.append("\\r");
                    case VERTICAL_TAB -> sb.append("\\v");
                    case SPACE -> sb.append("\\ ");
                }
            }
        }
    }

    private static void printAssertion(AssertionKind kind, StringBuilder sb) {
        switch (kind) {
            case START_LINE -> sb.append('^');
            case END_LINE -> sb.append('$');
            case START_TEXT -> sb.append("\\A");
            case END_TEXT -> sb.append("\\z");
            case WORD_BOUNDARY -> sb.append("\\b");
            case NOT_WORD_BOUNDARY -> sb.append("\\B");
            case WORD_BOUNDARY_START -> sb.append("\\b{start}");
            case WORD_BOUNDARY_END -> sb.append("\\b{end}");
            case WORD_BOUNDARY_START_ANGLE -> sb.append("\\<");
            case WORD_BOUNDARY_END_ANGLE -> sb.append("\\>");
            case WORD_BOUNDARY_START_HALF -> sb.append("\\b{start-half}");
            case WORD_BOUNDARY_END_HALF -> sb.append("\\b{end-half}");
        }
    }

    private static void printRepetition(RepetitionKind kind, boolean greedy, StringBuilder sb) {
        switch (kind) {
            case RepetitionKind.ZeroOrOne ignored -> {
                sb.append('?');
                if (!greedy) sb.append('?');
            }
            case RepetitionKind.ZeroOrMore ignored -> {
                sb.append('*');
                if (!greedy) sb.append('?');
            }
            case RepetitionKind.OneOrMore ignored -> {
                sb.append('+');
                if (!greedy) sb.append('?');
            }
            case RepetitionKind.Range r -> {
                switch (r.range()) {
                    case RepetitionRange.Exactly e -> sb.append('{').append(e.n()).append('}');
                    case RepetitionRange.AtLeast a -> sb.append('{').append(a.n()).append(",}");
                    case RepetitionRange.Bounded b -> sb.append('{').append(b.start()).append(',').append(b.end()).append('}');
                }
                if (!greedy) sb.append('?');
            }
        }
    }

    private static void printGroup(Ast.Group g, StringBuilder sb) {
        switch (g.kind()) {
            case GroupKind.CaptureIndex ignored -> sb.append('(');
            case GroupKind.CaptureName cn -> {
                sb.append(cn.startsWithP() ? "(?P<" : "(?<");
                sb.append(cn.name());
                sb.append('>');
            }
            case GroupKind.NonCapturing nc -> {
                sb.append("(?");
                printFlagItems(nc.flags(), sb);
                sb.append(':');
            }
        }
        printAst(g.ast(), sb);
        sb.append(')');
    }

    private static void printSetFlags(Ast.Flags flags, StringBuilder sb) {
        sb.append("(?");
        printFlagItems(flags.flags(), sb);
        sb.append(')');
    }

    private static void printFlagItems(FlagItems flags, StringBuilder sb) {
        for (FlagsItem item : flags.items()) {
            switch (item.kind()) {
                case FlagsItemKind.Negation ignored -> sb.append('-');
                case FlagsItemKind.FlagKind fk -> {
                    switch (fk.flag()) {
                        case CASE_INSENSITIVE -> sb.append('i');
                        case MULTI_LINE -> sb.append('m');
                        case DOT_MATCHES_NEW_LINE -> sb.append('s');
                        case SWAP_GREED -> sb.append('U');
                        case UNICODE -> sb.append('u');
                        case CRLF -> sb.append('R');
                        case IGNORE_WHITESPACE -> sb.append('x');
                    }
                }
            }
        }
    }

    private static void printClassBracketed(Ast.ClassBracketed cb, StringBuilder sb) {
        sb.append(cb.negated() ? "[^" : "[");
        printClassSet(cb.kind(), sb);
        sb.append(']');
    }

    private static void printClassSet(ClassSet set, StringBuilder sb) {
        switch (set) {
            case ClassSet.Item item -> printClassSetItem(item.item(), sb);
            case ClassSet.BinaryOp op -> {
                printClassSet(op.lhs(), sb);
                switch (op.kind()) {
                    case INTERSECTION -> sb.append("&&");
                    case DIFFERENCE -> sb.append("--");
                    case SYMMETRIC_DIFFERENCE -> sb.append("~~");
                }
                printClassSet(op.rhs(), sb);
            }
        }
    }

    private static void printClassSetItem(ClassSetItem item, StringBuilder sb) {
        switch (item) {
            case ClassSetItem.Empty ignored -> {}
            case ClassSetItem.Literal lit -> printLiteral(lit.kind(), lit.c(), sb);
            case ClassSetItem.Range r -> {
                printLiteral(r.start().kind(), r.start().c(), sb);
                sb.append('-');
                printLiteral(r.end().kind(), r.end().c(), sb);
            }
            case ClassSetItem.Ascii a -> printClassAscii(a, sb);
            case ClassSetItem.Unicode u -> printClassUnicode(u.negated(), u.kind(), sb);
            case ClassSetItem.Perl p -> printClassPerl(p.kind(), p.negated(), sb);
            case ClassSetItem.Bracketed b -> {
                sb.append(b.negated() ? "[^" : "[");
                printClassSet(b.kind(), sb);
                sb.append(']');
            }
            case ClassSetItem.Union u -> {
                for (ClassSetItem child : u.items()) {
                    printClassSetItem(child, sb);
                }
            }
        }
    }

    private static void printClassPerl(ClassPerlKind kind, boolean negated, StringBuilder sb) {
        switch (kind) {
            case DIGIT -> sb.append(negated ? "\\D" : "\\d");
            case SPACE -> sb.append(negated ? "\\S" : "\\s");
            case WORD -> sb.append(negated ? "\\W" : "\\w");
        }
    }

    private static void printClassAscii(ClassSetItem.Ascii a, StringBuilder sb) {
        sb.append("[:");
        if (a.negated()) sb.append('^');
        sb.append(a.kind().className());
        sb.append(":]");
    }

    private static void printClassUnicode(boolean negated, ClassUnicodeKind kind, StringBuilder sb) {
        sb.append(negated ? "\\P" : "\\p");
        switch (kind) {
            case ClassUnicodeKind.OneLetter ol -> sb.append(ol.letter());
            case ClassUnicodeKind.Named n -> sb.append('{').append(n.name()).append('}');
            case ClassUnicodeKind.NamedValue nv -> {
                sb.append('{').append(nv.name());
                switch (nv.op()) {
                    case EQUAL -> sb.append('=');
                    case COLON -> sb.append(':');
                    case NOT_EQUAL -> sb.append("!=");
                }
                sb.append(nv.value()).append('}');
            }
        }
    }
}
