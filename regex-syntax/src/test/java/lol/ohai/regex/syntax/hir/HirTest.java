package lol.ohai.regex.syntax.hir;

import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.ClassUnicode.ClassUnicodeRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the AST to HIR translator.
 */
class HirTest {

    // --- Helpers ---

    private static Hir t(String pattern) throws Exception {
        Ast ast = Parser.parse(pattern);
        return Translator.translate(pattern, ast);
    }

    private static char[] chars(String s) {
        return s.toCharArray();
    }

    // --- Literals ---

    @Test
    void translateLiteral() throws Exception {
        Hir hir = t("a");
        assertEquals(new Hir.Literal(chars("a")), hir);
    }

    @Test
    void translateMultiCharLiteral() throws Exception {
        // Euro sign is a single BMP char
        Hir hir = t("\u20AC");
        assertEquals(new Hir.Literal(new char[]{'\u20AC'}), hir);
    }

    // --- Dot ---

    @Test
    void translateDot() throws Exception {
        Hir hir = t(".");
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        // Should contain everything except \n (U+000A)
        List<ClassUnicodeRange> ranges = cls.unicode().ranges();
        assertEquals(2, ranges.size());
        assertEquals(new ClassUnicodeRange(0x00, 0x09), ranges.get(0));
        assertEquals(new ClassUnicodeRange(0x0B, 0x10FFFF), ranges.get(1));
    }

    @Test
    void translateDotMatchesNewline() throws Exception {
        Hir hir = t("(?s).");
        // (?s) sets the flag, then "." is the actual pattern
        // The result should be Concat or just a class depending on implementation
        // Since (?s) produces Empty and "." produces Class, the concat simplifies
        // Actually "(?s)." parses as Concat([Flags, Dot])
        // The Flags node becomes Empty and is filtered, leaving just the Dot
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        List<ClassUnicodeRange> ranges = cls.unicode().ranges();
        assertEquals(1, ranges.size());
        assertEquals(new ClassUnicodeRange(0x00, 0x10FFFF), ranges.get(0));
    }

    // --- Concat ---

    @Test
    void translateConcat() throws Exception {
        Hir hir = t("ab");
        assertInstanceOf(Hir.Concat.class, hir);
        Hir.Concat concat = (Hir.Concat) hir;
        assertEquals(2, concat.subs().size());
        assertEquals(new Hir.Literal(chars("a")), concat.subs().get(0));
        assertEquals(new Hir.Literal(chars("b")), concat.subs().get(1));
    }

    // --- Alternation ---

    @Test
    void translateAlternation() throws Exception {
        Hir hir = t("a|b");
        assertInstanceOf(Hir.Alternation.class, hir);
        Hir.Alternation alt = (Hir.Alternation) hir;
        assertEquals(2, alt.subs().size());
        assertEquals(new Hir.Literal(chars("a")), alt.subs().get(0));
        assertEquals(new Hir.Literal(chars("b")), alt.subs().get(1));
    }

    // --- Capture groups ---

    @Test
    void translateCapture() throws Exception {
        Hir hir = t("(a)");
        assertInstanceOf(Hir.Capture.class, hir);
        Hir.Capture cap = (Hir.Capture) hir;
        assertEquals(1, cap.index());
        assertNull(cap.name());
        assertEquals(new Hir.Literal(chars("a")), cap.sub());
    }

    @Test
    void translateNamedCapture() throws Exception {
        Hir hir = t("(?P<foo>a)");
        assertInstanceOf(Hir.Capture.class, hir);
        Hir.Capture cap = (Hir.Capture) hir;
        assertEquals(1, cap.index());
        assertEquals("foo", cap.name());
        assertEquals(new Hir.Literal(chars("a")), cap.sub());
    }

    @Test
    void translateNonCapturing() throws Exception {
        Hir hir = t("(?:a)");
        // Non-capturing group is transparent
        assertEquals(new Hir.Literal(chars("a")), hir);
    }

    // --- Repetition ---

    @Test
    void translateRepetitionStar() throws Exception {
        Hir hir = t("a*");
        assertInstanceOf(Hir.Repetition.class, hir);
        Hir.Repetition rep = (Hir.Repetition) hir;
        assertEquals(0, rep.min());
        assertEquals(Hir.Repetition.UNBOUNDED, rep.max());
        assertTrue(rep.greedy());
        assertEquals(new Hir.Literal(chars("a")), rep.sub());
    }

    @Test
    void translateRepetitionPlus() throws Exception {
        Hir hir = t("a+");
        assertInstanceOf(Hir.Repetition.class, hir);
        Hir.Repetition rep = (Hir.Repetition) hir;
        assertEquals(1, rep.min());
        assertEquals(Hir.Repetition.UNBOUNDED, rep.max());
        assertTrue(rep.greedy());
    }

    @Test
    void translateRepetitionQuestion() throws Exception {
        Hir hir = t("a?");
        assertInstanceOf(Hir.Repetition.class, hir);
        Hir.Repetition rep = (Hir.Repetition) hir;
        assertEquals(0, rep.min());
        assertEquals(1, rep.max());
        assertTrue(rep.greedy());
    }

    @Test
    void translateRepetitionLazy() throws Exception {
        Hir hir = t("a*?");
        assertInstanceOf(Hir.Repetition.class, hir);
        Hir.Repetition rep = (Hir.Repetition) hir;
        assertEquals(0, rep.min());
        assertEquals(Hir.Repetition.UNBOUNDED, rep.max());
        assertFalse(rep.greedy());
    }

    @Test
    void translateRepetitionBounded() throws Exception {
        Hir hir = t("a{2,5}");
        assertInstanceOf(Hir.Repetition.class, hir);
        Hir.Repetition rep = (Hir.Repetition) hir;
        assertEquals(2, rep.min());
        assertEquals(5, rep.max());
        assertTrue(rep.greedy());
    }

    @Test
    void translateRepetitionExact() throws Exception {
        Hir hir = t("a{3}");
        assertInstanceOf(Hir.Repetition.class, hir);
        Hir.Repetition rep = (Hir.Repetition) hir;
        assertEquals(3, rep.min());
        assertEquals(3, rep.max());
    }

    @Test
    void translateRepetitionAtLeast() throws Exception {
        Hir hir = t("a{2,}");
        assertInstanceOf(Hir.Repetition.class, hir);
        Hir.Repetition rep = (Hir.Repetition) hir;
        assertEquals(2, rep.min());
        assertEquals(Hir.Repetition.UNBOUNDED, rep.max());
    }

    @Test
    void translateSwapGreed() throws Exception {
        Hir hir = t("(?U)a*");
        // (?U) + a* in a concat, flags consumed
        assertInstanceOf(Hir.Repetition.class, hir);
        Hir.Repetition rep = (Hir.Repetition) hir;
        assertEquals(0, rep.min());
        assertEquals(Hir.Repetition.UNBOUNDED, rep.max());
        assertFalse(rep.greedy());
    }

    // --- Case insensitive ---

    @Test
    void translateCaseInsensitiveLiteral() throws Exception {
        Hir hir = t("(?i)a");
        // Should become a class with [A-A, a-a]
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        List<ClassUnicodeRange> ranges = cls.unicode().ranges();
        assertEquals(2, ranges.size());
        assertEquals(new ClassUnicodeRange('A', 'A'), ranges.get(0));
        assertEquals(new ClassUnicodeRange('a', 'a'), ranges.get(1));
    }

    @Test
    void translateCaseInsensitiveNonLetter() throws Exception {
        Hir hir = t("(?i)1");
        // '1' is not a letter, so it stays as a literal
        assertEquals(new Hir.Literal(chars("1")), hir);
    }

    // --- Perl character classes (ASCII mode) ---

    @Test
    void translatePerlDigit() throws Exception {
        Hir hir = t("(?-u)\\d");
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        assertEquals(List.of(new ClassUnicodeRange('0', '9')), cls.unicode().ranges());
    }

    @Test
    void translatePerlWord() throws Exception {
        Hir hir = t("(?-u)\\w");
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        assertEquals(List.of(
                new ClassUnicodeRange('0', '9'),
                new ClassUnicodeRange('A', 'Z'),
                new ClassUnicodeRange('_', '_'),
                new ClassUnicodeRange('a', 'z')
        ), cls.unicode().ranges());
    }

    @Test
    void translatePerlSpace() throws Exception {
        Hir hir = t("(?-u)\\s");
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        assertEquals(List.of(
                new ClassUnicodeRange('\t', '\r'),
                new ClassUnicodeRange(' ', ' ')
        ), cls.unicode().ranges());
    }

    @Test
    void translateNegatedPerlClass() throws Exception {
        Hir hir = t("(?-u)\\D");
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        // Everything except 0-9
        List<ClassUnicodeRange> ranges = cls.unicode().ranges();
        assertEquals(2, ranges.size());
        assertEquals(new ClassUnicodeRange(0x00, '0' - 1), ranges.get(0));
        assertEquals(new ClassUnicodeRange('9' + 1, 0x10FFFF), ranges.get(1));
    }

    @Test
    void translatePerlDigitUnicodeMode() throws Exception {
        // Unicode mode is the default — \d should produce a Unicode digit class
        Hir hir = t("\\d");
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        // Unicode \d includes many ranges beyond ASCII 0-9
        List<ClassUnicodeRange> ranges = cls.unicode().ranges();
        assertTrue(ranges.size() > 1, "Unicode \\d should have multiple ranges");
        // First range should include ASCII digits
        assertEquals('0', ranges.get(0).start());
        assertEquals('9', ranges.get(0).end());
    }

    // --- Character classes ---

    @Test
    void translateCharClass() throws Exception {
        Hir hir = t("[abc]");
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        assertEquals(List.of(new ClassUnicodeRange('a', 'c')), cls.unicode().ranges());
    }

    @Test
    void translateCharClassRange() throws Exception {
        Hir hir = t("[a-z]");
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        assertEquals(List.of(new ClassUnicodeRange('a', 'z')), cls.unicode().ranges());
    }

    @Test
    void translateNegatedCharClass() throws Exception {
        Hir hir = t("[^a]");
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        List<ClassUnicodeRange> ranges = cls.unicode().ranges();
        assertEquals(2, ranges.size());
        assertEquals(new ClassUnicodeRange(0x00, 'a' - 1), ranges.get(0));
        assertEquals(new ClassUnicodeRange('a' + 1, 0x10FFFF), ranges.get(1));
    }

    // --- Assertions ---

    @Test
    void translateStartText() throws Exception {
        Hir hir = t("^");
        assertEquals(new Hir.Look(LookKind.START_TEXT), hir);
    }

    @Test
    void translateEndText() throws Exception {
        Hir hir = t("$");
        assertEquals(new Hir.Look(LookKind.END_TEXT), hir);
    }

    @Test
    void translateMultilineStart() throws Exception {
        Hir hir = t("(?m)^");
        assertEquals(new Hir.Look(LookKind.START_LINE), hir);
    }

    @Test
    void translateMultilineEnd() throws Exception {
        Hir hir = t("(?m)$");
        assertEquals(new Hir.Look(LookKind.END_LINE), hir);
    }

    @Test
    void translateWordBoundary() throws Exception {
        // Default is Unicode mode, so word boundary is Unicode
        Hir hir = t("\\b");
        assertEquals(new Hir.Look(LookKind.WORD_BOUNDARY_UNICODE), hir);
    }

    @Test
    void translateWordBoundaryAscii() throws Exception {
        Hir hir = t("(?-u)\\b");
        assertEquals(new Hir.Look(LookKind.WORD_BOUNDARY_ASCII), hir);
    }

    @Test
    void translateStartTextExplicit() throws Exception {
        Hir hir = t("\\A");
        assertEquals(new Hir.Look(LookKind.START_TEXT), hir);
    }

    @Test
    void translateEndTextExplicit() throws Exception {
        Hir hir = t("\\z");
        assertEquals(new Hir.Look(LookKind.END_TEXT), hir);
    }

    // --- ASCII classes ---

    @Test
    void translateAsciiClassAlpha() throws Exception {
        Hir hir = t("[[:alpha:]]");
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        assertEquals(List.of(
                new ClassUnicodeRange('A', 'Z'),
                new ClassUnicodeRange('a', 'z')
        ), cls.unicode().ranges());
    }

    @Test
    void translateAsciiClassDigit() throws Exception {
        Hir hir = t("[[:digit:]]");
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        assertEquals(List.of(new ClassUnicodeRange('0', '9')), cls.unicode().ranges());
    }

    // --- Empty ---

    @Test
    void translateEmpty() throws Exception {
        Hir hir = t("");
        assertEquals(new Hir.Empty(), hir);
    }

    // --- Flags ---

    @Test
    void translateFlagsOnly() throws Exception {
        // A standalone flags group just produces Empty (flags are consumed)
        Hir hir = t("(?i)");
        assertEquals(new Hir.Empty(), hir);
    }

    // --- Scoped flags ---

    @Test
    void translateScopedFlags() throws Exception {
        // (?i:a) should produce case-insensitive 'a' as a class
        Hir hir = t("(?i:a)");
        assertInstanceOf(Hir.Class.class, hir);
        Hir.Class cls = (Hir.Class) hir;
        List<ClassUnicodeRange> ranges = cls.unicode().ranges();
        assertEquals(2, ranges.size());
        assertEquals(new ClassUnicodeRange('A', 'A'), ranges.get(0));
        assertEquals(new ClassUnicodeRange('a', 'a'), ranges.get(1));
    }

    @Test
    void translateScopedFlagsDoNotLeak() throws Exception {
        // (?i:a)b — the 'b' should NOT be case insensitive
        Hir hir = t("(?i:a)b");
        assertInstanceOf(Hir.Concat.class, hir);
        Hir.Concat concat = (Hir.Concat) hir;
        assertEquals(2, concat.subs().size());
        // First should be a class (case insensitive a)
        assertInstanceOf(Hir.Class.class, concat.subs().get(0));
        // Second should be a plain literal 'b'
        assertEquals(new Hir.Literal(chars("b")), concat.subs().get(1));
    }

    // --- Unicode property classes (should error for now) ---

    @Test
    void translateUnicodePropertyErrors() {
        assertThrows(Error.class, () -> t("\\pL"));
    }

    // --- Complex patterns ---

    @Test
    void translateComplexPattern() throws Exception {
        // "(?i:ab)+|cd"
        Hir hir = t("(?i:ab)+|cd");
        assertInstanceOf(Hir.Alternation.class, hir);
        Hir.Alternation alt = (Hir.Alternation) hir;
        assertEquals(2, alt.subs().size());

        // First alt: repetition of case-insensitive "ab"
        assertInstanceOf(Hir.Repetition.class, alt.subs().get(0));
        Hir.Repetition rep = (Hir.Repetition) alt.subs().get(0);
        assertEquals(1, rep.min());
        assertEquals(Hir.Repetition.UNBOUNDED, rep.max());

        // Second alt: concat of "cd"
        assertInstanceOf(Hir.Concat.class, alt.subs().get(1));
    }
}
