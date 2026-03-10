package lol.ohai.regex.syntax.ast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class PrinterTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "a", "abc", "a|b", "(a)", "(?:a)", "a*", "a+", "a?",
            "a{3}", "a{3,}", "a{3,5}", "[abc]", "[^abc]", "[a-z]",
            "\\d", "\\w", "\\s", ".", "^", "$", "\\b",
            "(?i:abc)", "(?P<name>abc)",
            "a*?", "a+?", "a??",
            "\\pL", "\\p{Greek}",
            "\\n", "\\r", "\\t", "\\x61",
            "\\.", "\\*", "\\+", "\\?", "\\(", "\\)", "\\[", "\\]",
            "a|b|c", "(a)(b)(c)", "(?:a|b)*"
    })
    void roundTrip(String pattern) throws Error {
        Ast ast = Parser.parse(pattern);
        String printed = Printer.print(ast);
        assertEquals(pattern, printed, "Round-trip failed for pattern: " + pattern);
    }

    @Test
    void roundTripAssertions() throws Error {
        assertRoundTrip("^");
        assertRoundTrip("$");
        assertRoundTrip("\\A");
        assertRoundTrip("\\z");
        assertRoundTrip("\\b");
        assertRoundTrip("\\B");
    }

    @Test
    void roundTripPerlClasses() throws Error {
        assertRoundTrip("\\d");
        assertRoundTrip("\\D");
        assertRoundTrip("\\s");
        assertRoundTrip("\\S");
        assertRoundTrip("\\w");
        assertRoundTrip("\\W");
    }

    @Test
    void roundTripUnicodeClasses() throws Error {
        assertRoundTrip("\\pL");
        assertRoundTrip("\\PL");
        assertRoundTrip("\\p{L}");
        assertRoundTrip("\\P{L}");
        assertRoundTrip("\\p{X=Y}");
        assertRoundTrip("\\P{X=Y}");
        assertRoundTrip("\\p{X:Y}");
        assertRoundTrip("\\P{X:Y}");
        assertRoundTrip("\\p{X!=Y}");
        assertRoundTrip("\\P{X!=Y}");
    }

    @Test
    void roundTripHexEscapes() throws Error {
        assertRoundTrip("\\x61");
        assertRoundTrip("\\x7F");
        assertRoundTrip("\\u0061");
        assertRoundTrip("\\U00000061");
        assertRoundTrip("\\x{61}");
        assertRoundTrip("\\x{7F}");
    }

    @Test
    void roundTripSpecialEscapes() throws Error {
        assertRoundTrip("\\a");
        assertRoundTrip("\\f");
        assertRoundTrip("\\t");
        assertRoundTrip("\\n");
        assertRoundTrip("\\r");
        assertRoundTrip("\\v");
    }

    @Test
    void roundTripRepetition() throws Error {
        assertRoundTrip("a?");
        assertRoundTrip("a??");
        assertRoundTrip("a*");
        assertRoundTrip("a*?");
        assertRoundTrip("a+");
        assertRoundTrip("a+?");
        assertRoundTrip("a{5}");
        assertRoundTrip("a{5}?");
        assertRoundTrip("a{5,}");
        assertRoundTrip("a{5,}?");
        assertRoundTrip("a{5,10}");
        assertRoundTrip("a{5,10}?");
    }

    @Test
    void roundTripFlags() throws Error {
        assertRoundTrip("(?i)");
        assertRoundTrip("(?-i)");
        assertRoundTrip("(?s-i)");
        assertRoundTrip("(?-si)");
        assertRoundTrip("(?siUmux)");
    }

    @Test
    void roundTripGroups() throws Error {
        assertRoundTrip("(?i:a)");
        assertRoundTrip("(?P<foo>a)");
        assertRoundTrip("(?<foo>a)");
        assertRoundTrip("(a)");
    }

    @Test
    void roundTripConcat() throws Error {
        assertRoundTrip("ab");
        assertRoundTrip("abcde");
        assertRoundTrip("a(bcd)ef");
    }

    @Test
    void roundTripAlternation() throws Error {
        assertRoundTrip("a|b");
        assertRoundTrip("a|b|c|d|e");
        assertRoundTrip("|a|b|c|d|e");
        assertRoundTrip("|a|b|c|d|e|");
        assertRoundTrip("a(b|c|d)|e|f");
    }

    @Test
    void roundTripClass() throws Error {
        assertRoundTrip("[abc]");
        assertRoundTrip("[a-z]");
        assertRoundTrip("[^a-z]");
        assertRoundTrip("[a-z0-9]");
        assertRoundTrip("[-a-z0-9]");
        assertRoundTrip("[a-z&&m-n]");
        assertRoundTrip("[a-z--m-n]");
        assertRoundTrip("[a-z~~m-n]");
        assertRoundTrip("[a-z[0-9]]");
        assertRoundTrip("[a-z[^0-9]]");
    }

    @Test
    void roundTripAsciiClasses() throws Error {
        assertRoundTrip("[[:alnum:]]");
        assertRoundTrip("[[:^alnum:]]");
        assertRoundTrip("[[:alpha:]]");
        assertRoundTrip("[[:^alpha:]]");
        assertRoundTrip("[[:ascii:]]");
        assertRoundTrip("[[:digit:]]");
        assertRoundTrip("[[:^digit:]]");
        assertRoundTrip("[[:lower:]]");
        assertRoundTrip("[[:upper:]]");
        assertRoundTrip("[[:word:]]");
        assertRoundTrip("[[:xdigit:]]");
    }

    @Test
    void roundTripMetaEscapes() throws Error {
        assertRoundTrip("\\.");
        assertRoundTrip("\\*");
        assertRoundTrip("\\+");
        assertRoundTrip("\\?");
        assertRoundTrip("\\(");
        assertRoundTrip("\\)");
        assertRoundTrip("\\[");
        assertRoundTrip("\\]");
        assertRoundTrip("\\{");
        assertRoundTrip("\\}");
        assertRoundTrip("\\^");
        assertRoundTrip("\\$");
        assertRoundTrip("\\|");
        assertRoundTrip("\\\\");
    }

    @Test
    void roundTripEmpty() throws Error {
        Ast ast = Parser.parse("");
        assertEquals("", Printer.print(ast));
    }

    private void assertRoundTrip(String pattern) throws Error {
        Ast ast = Parser.parse(pattern);
        String printed = Printer.print(ast);
        assertEquals(pattern, printed, "Round-trip failed for pattern: " + pattern);
    }
}
