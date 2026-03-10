package lol.ohai.regex.syntax.ast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ParserTest {

    @Test
    void parseLiteral() throws Error {
        Ast ast = Parser.parse("a");
        assertInstanceOf(Ast.Literal.class, ast);
        var lit = (Ast.Literal) ast;
        assertEquals('a', lit.c());
        assertInstanceOf(LiteralKind.Verbatim.class, lit.kind());
    }

    @Test
    void parseDot() throws Error {
        Ast ast = Parser.parse(".");
        assertInstanceOf(Ast.Dot.class, ast);
    }

    @Test
    void parseConcat() throws Error {
        Ast ast = Parser.parse("abc");
        assertInstanceOf(Ast.Concat.class, ast);
        var concat = (Ast.Concat) ast;
        assertEquals(3, concat.asts().size());
    }

    @Test
    void parseAlternation() throws Error {
        Ast ast = Parser.parse("a|b");
        assertInstanceOf(Ast.Alternation.class, ast);
        var alt = (Ast.Alternation) ast;
        assertEquals(2, alt.asts().size());
    }

    @Test
    void parseGroup() throws Error {
        Ast ast = Parser.parse("(a)");
        assertInstanceOf(Ast.Group.class, ast);
        var group = (Ast.Group) ast;
        assertInstanceOf(GroupKind.CaptureIndex.class, group.kind());
        assertEquals(1, ((GroupKind.CaptureIndex) group.kind()).index());
    }

    @Test
    void parseRepetitionStar() throws Error {
        Ast ast = Parser.parse("a*");
        assertInstanceOf(Ast.Repetition.class, ast);
        var rep = (Ast.Repetition) ast;
        assertInstanceOf(RepetitionKind.ZeroOrMore.class, rep.op().kind());
        assertTrue(rep.greedy());
    }

    @Test
    void parseRepetitionPlus() throws Error {
        Ast ast = Parser.parse("a+");
        assertInstanceOf(Ast.Repetition.class, ast);
        var rep = (Ast.Repetition) ast;
        assertInstanceOf(RepetitionKind.OneOrMore.class, rep.op().kind());
    }

    @Test
    void parseRepetitionQuestion() throws Error {
        Ast ast = Parser.parse("a?");
        assertInstanceOf(Ast.Repetition.class, ast);
        var rep = (Ast.Repetition) ast;
        assertInstanceOf(RepetitionKind.ZeroOrOne.class, rep.op().kind());
    }

    @Test
    void parseRepetitionBounded() throws Error {
        Ast ast = Parser.parse("a{3}");
        assertInstanceOf(Ast.Repetition.class, ast);
        var rep = (Ast.Repetition) ast;
        assertInstanceOf(RepetitionKind.Range.class, rep.op().kind());
        var range = ((RepetitionKind.Range) rep.op().kind()).range();
        assertInstanceOf(RepetitionRange.Exactly.class, range);
        assertEquals(3, ((RepetitionRange.Exactly) range).n());

        ast = Parser.parse("a{3,}");
        rep = (Ast.Repetition) ast;
        range = ((RepetitionKind.Range) rep.op().kind()).range();
        assertInstanceOf(RepetitionRange.AtLeast.class, range);

        ast = Parser.parse("a{3,5}");
        rep = (Ast.Repetition) ast;
        range = ((RepetitionKind.Range) rep.op().kind()).range();
        assertInstanceOf(RepetitionRange.Bounded.class, range);
        assertEquals(3, ((RepetitionRange.Bounded) range).start());
        assertEquals(5, ((RepetitionRange.Bounded) range).end());
    }

    @Test
    void parseLazyRepetition() throws Error {
        Ast ast = Parser.parse("a*?");
        assertInstanceOf(Ast.Repetition.class, ast);
        assertFalse(((Ast.Repetition) ast).greedy());

        ast = Parser.parse("a+?");
        assertFalse(((Ast.Repetition) ast).greedy());

        ast = Parser.parse("a??");
        assertFalse(((Ast.Repetition) ast).greedy());
    }

    @Test
    void parseCharClass() throws Error {
        Ast ast = Parser.parse("[abc]");
        assertInstanceOf(Ast.ClassBracketed.class, ast);
        var cls = (Ast.ClassBracketed) ast;
        assertFalse(cls.negated());
    }

    @Test
    void parseCharClassNegated() throws Error {
        Ast ast = Parser.parse("[^abc]");
        assertInstanceOf(Ast.ClassBracketed.class, ast);
        assertTrue(((Ast.ClassBracketed) ast).negated());
    }

    @Test
    void parseCharClassRange() throws Error {
        Ast ast = Parser.parse("[a-z]");
        assertInstanceOf(Ast.ClassBracketed.class, ast);
    }

    @Test
    void parsePerlClass() throws Error {
        Ast ast = Parser.parse("\\d");
        assertInstanceOf(Ast.ClassPerl.class, ast);
        var cp = (Ast.ClassPerl) ast;
        assertEquals(ClassPerlKind.DIGIT, cp.kind());
        assertFalse(cp.negated());

        ast = Parser.parse("\\D");
        cp = (Ast.ClassPerl) ast;
        assertEquals(ClassPerlKind.DIGIT, cp.kind());
        assertTrue(cp.negated());
    }

    @Test
    void parseUnicodeClass() throws Error {
        Ast ast = Parser.parse("\\pL");
        assertInstanceOf(Ast.ClassUnicode.class, ast);
        var cu = (Ast.ClassUnicode) ast;
        assertFalse(cu.negated());
        assertInstanceOf(ClassUnicodeKind.OneLetter.class, cu.kind());

        ast = Parser.parse("\\p{Greek}");
        cu = (Ast.ClassUnicode) ast;
        assertInstanceOf(ClassUnicodeKind.Named.class, cu.kind());
        assertEquals("Greek", ((ClassUnicodeKind.Named) cu.kind()).name());

        ast = Parser.parse("\\p{Script=Greek}");
        cu = (Ast.ClassUnicode) ast;
        assertInstanceOf(ClassUnicodeKind.NamedValue.class, cu.kind());
    }

    @Test
    void parseAssertions() throws Error {
        assertEquals(AssertionKind.START_LINE, ((Ast.Assertion) Parser.parse("^")).kind());
        assertEquals(AssertionKind.END_LINE, ((Ast.Assertion) Parser.parse("$")).kind());
        assertEquals(AssertionKind.START_TEXT, ((Ast.Assertion) Parser.parse("\\A")).kind());
        assertEquals(AssertionKind.END_TEXT, ((Ast.Assertion) Parser.parse("\\z")).kind());
        assertEquals(AssertionKind.WORD_BOUNDARY, ((Ast.Assertion) Parser.parse("\\b")).kind());
        assertEquals(AssertionKind.NOT_WORD_BOUNDARY, ((Ast.Assertion) Parser.parse("\\B")).kind());
    }

    @Test
    void parseEscapes() throws Error {
        Ast ast = Parser.parse("\\*");
        assertInstanceOf(Ast.Literal.class, ast);
        assertEquals('*', ((Ast.Literal) ast).c());
        assertInstanceOf(LiteralKind.Meta.class, ((Ast.Literal) ast).kind());

        ast = Parser.parse("\\n");
        assertInstanceOf(Ast.Literal.class, ast);
        assertEquals('\n', ((Ast.Literal) ast).c());
        assertInstanceOf(LiteralKind.Special.class, ((Ast.Literal) ast).kind());
    }

    @Test
    void parseHexEscapes() throws Error {
        Ast ast = Parser.parse("\\x61");
        assertInstanceOf(Ast.Literal.class, ast);
        assertEquals('a', ((Ast.Literal) ast).c());

        ast = Parser.parse("\\u0061");
        assertEquals('a', ((Ast.Literal) ast).c());

        ast = Parser.parse("\\U00000061");
        assertEquals('a', ((Ast.Literal) ast).c());

        ast = Parser.parse("\\x{61}");
        assertEquals('a', ((Ast.Literal) ast).c());
    }

    @Test
    void parseNamedGroup() throws Error {
        Ast ast = Parser.parse("(?P<name>abc)");
        assertInstanceOf(Ast.Group.class, ast);
        var group = (Ast.Group) ast;
        assertInstanceOf(GroupKind.CaptureName.class, group.kind());
        var cn = (GroupKind.CaptureName) group.kind();
        assertEquals("name", cn.name());
        assertTrue(cn.startsWithP());

        ast = Parser.parse("(?<name>abc)");
        group = (Ast.Group) ast;
        cn = (GroupKind.CaptureName) group.kind();
        assertEquals("name", cn.name());
        assertFalse(cn.startsWithP());
    }

    @Test
    void parseNonCapturingGroup() throws Error {
        Ast ast = Parser.parse("(?:abc)");
        assertInstanceOf(Ast.Group.class, ast);
        assertInstanceOf(GroupKind.NonCapturing.class, ((Ast.Group) ast).kind());
    }

    @Test
    void parseFlags() throws Error {
        Ast ast = Parser.parse("(?i)");
        assertInstanceOf(Ast.Flags.class, ast);
        var flags = (Ast.Flags) ast;
        assertEquals(1, flags.flags().items().size());

        ast = Parser.parse("(?i:abc)");
        assertInstanceOf(Ast.Group.class, ast);
        var group = (Ast.Group) ast;
        assertInstanceOf(GroupKind.NonCapturing.class, group.kind());
    }

    @Test
    void nestLimitEnforced() {
        // Nest limit 1 should allow "(a)" but not "((a))"
        assertDoesNotThrow(() -> Parser.parse("(a)", 1));
        var error = assertThrows(Error.class, () -> Parser.parse("((a))", 1));
        assertInstanceOf(ErrorKind.NestLimitExceeded.class, error.kind());
    }

    @Test
    void unclosedGroupError() {
        var error = assertThrows(Error.class, () -> Parser.parse("(abc"));
        assertInstanceOf(ErrorKind.GroupUnclosed.class, error.kind());
    }

    @Test
    void unclosedClassError() {
        var error = assertThrows(Error.class, () -> Parser.parse("[abc"));
        assertInstanceOf(ErrorKind.ClassUnclosed.class, error.kind());
    }

    @Test
    void emptyPattern() throws Error {
        Ast ast = Parser.parse("");
        assertInstanceOf(Ast.Empty.class, ast);
    }

    @Test
    void parseMultipleAlternation() throws Error {
        Ast ast = Parser.parse("a|b|c");
        assertInstanceOf(Ast.Alternation.class, ast);
        assertEquals(3, ((Ast.Alternation) ast).asts().size());
    }

    @Test
    void parseNestedGroups() throws Error {
        Ast ast = Parser.parse("(a(b)c)");
        assertInstanceOf(Ast.Group.class, ast);
        var g = (Ast.Group) ast;
        assertInstanceOf(Ast.Concat.class, g.ast());
    }

    @Test
    void parseAlternationInGroup() throws Error {
        Ast ast = Parser.parse("(a|b)");
        assertInstanceOf(Ast.Group.class, ast);
        assertInstanceOf(Ast.Alternation.class, ((Ast.Group) ast).ast());
    }

    @Test
    void parseClassWithPerl() throws Error {
        Ast ast = Parser.parse("[\\d]");
        assertInstanceOf(Ast.ClassBracketed.class, ast);
    }

    @Test
    void parseNestedClass() throws Error {
        Ast ast = Parser.parse("[a-z[0-9]]");
        assertInstanceOf(Ast.ClassBracketed.class, ast);
    }

    @Test
    void parseClassIntersection() throws Error {
        Ast ast = Parser.parse("[a-z&&m-n]");
        assertInstanceOf(Ast.ClassBracketed.class, ast);
    }

    @Test
    void parseAsciiClassInBrackets() throws Error {
        Ast ast = Parser.parse("[[:alpha:]]");
        assertInstanceOf(Ast.ClassBracketed.class, ast);
    }

    @Test
    void groupUnopened() {
        var error = assertThrows(Error.class, () -> Parser.parse("a)"));
        assertInstanceOf(ErrorKind.GroupUnopened.class, error.kind());
    }

    @Test
    void repetitionMissing() {
        var error = assertThrows(Error.class, () -> Parser.parse("*"));
        assertInstanceOf(ErrorKind.RepetitionMissing.class, error.kind());
    }

    @Test
    void invalidRepetitionRange() {
        var error = assertThrows(Error.class, () -> Parser.parse("a{5,3}"));
        assertInstanceOf(ErrorKind.RepetitionCountInvalid.class, error.kind());
    }

    @Test
    void parseNegatedAsciiClass() throws Error {
        Ast ast = Parser.parse("[[:^digit:]]");
        assertInstanceOf(Ast.ClassBracketed.class, ast);
    }

    @Test
    void parseFlagNegation() throws Error {
        Ast ast = Parser.parse("(?-i)");
        assertInstanceOf(Ast.Flags.class, ast);
        var f = (Ast.Flags) ast;
        assertEquals(2, f.flags().items().size()); // negation + flag
    }

    @Test
    void parseClassDifference() throws Error {
        Ast ast = Parser.parse("[a-z--m-n]");
        assertInstanceOf(Ast.ClassBracketed.class, ast);
    }

    @Test
    void parseEmptyAlternation() throws Error {
        // "|a" should parse as alternation with empty first branch
        Ast ast = Parser.parse("|a");
        assertInstanceOf(Ast.Alternation.class, ast);
        var alt = (Ast.Alternation) ast;
        assertEquals(2, alt.asts().size());
        assertInstanceOf(Ast.Empty.class, alt.asts().getFirst());
    }
}
