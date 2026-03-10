package lol.ohai.regex;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RegexTest {


    @Test
    void compileAndMatch() {
        Regex re = Regex.compile("\\d+");
        assertTrue(re.isMatch("abc123"));
        assertFalse(re.isMatch("abcdef"));
    }

    @Test
    void compileAndMatchLiteral() {
        Regex re = Regex.compile("hello");
        assertTrue(re.isMatch("say hello world"));
        assertFalse(re.isMatch("goodbye"));
    }

    @Test
    void find() {
        Regex re = Regex.compile("\\d+");
        Match m = re.find("abc123def").orElseThrow();
        assertEquals(3, m.start());
        assertEquals(6, m.end());
        assertEquals("123", m.text());
    }

    @Test
    void findNoMatch() {
        Regex re = Regex.compile("\\d+");
        assertTrue(re.find("abcdef").isEmpty());
    }

    @Test
    void captures() {
        Regex re = Regex.compile("(?P<year>\\d{4})-(?P<month>\\d{2})");
        Captures c = re.captures("2026-03").orElseThrow();
        assertEquals("2026-03", c.overall().text());
        assertEquals("2026", c.group("year").orElseThrow().text());
        assertEquals("03", c.group("month").orElseThrow().text());
        assertEquals(3, c.groupCount()); // group 0 + 2 named groups
    }

    @Test
    void capturesOptionalGroup() {
        Regex re = Regex.compile("(a)(b)?(c)");
        Captures c = re.captures("ac").orElseThrow();
        assertEquals("ac", c.overall().text());
        assertEquals("a", c.group(1).orElseThrow().text());
        assertTrue(c.group(2).isEmpty()); // (b)? did not participate
        assertEquals("c", c.group(3).orElseThrow().text());
    }

    @Test
    void findAll() {
        Regex re = Regex.compile("\\d+");
        List<Match> matches = re.findAll("a1b22c333").toList();
        assertEquals(3, matches.size());
        assertEquals("1", matches.get(0).text());
        assertEquals("22", matches.get(1).text());
        assertEquals("333", matches.get(2).text());
    }

    @Test
    void findAllNoMatches() {
        Regex re = Regex.compile("[0-9]+");
        List<Match> matches = re.findAll("abcdef").toList();
        assertTrue(matches.isEmpty());
    }

    @Test
    void findAllZeroWidth() {
        // Empty pattern matches at every position
        Regex re = Regex.compile("");
        List<Match> matches = re.findAll("ab").toList();
        // Should match at positions 0, 1, 2 (before 'a', before 'b', at end)
        assertEquals(3, matches.size());
        for (Match m : matches) {
            assertEquals(0, m.length());
        }
    }

    @Test
    void invalidPatternThrows() {
        PatternSyntaxException ex = assertThrows(PatternSyntaxException.class,
                () -> Regex.compile("[unclosed"));
        assertEquals("[unclosed", ex.pattern());
        assertTrue(ex.getMessage().contains("failed to compile regex"));
    }

    @Test
    void patternReturnsOriginal() {
        Regex re = Regex.compile("foo|bar");
        assertEquals("foo|bar", re.pattern());
    }

    @Test
    void toStringReturnsPattern() {
        Regex re = Regex.compile("abc");
        assertEquals("abc", re.toString());
    }

    @Test
    void builder() {
        Regex re = Regex.builder()
                .nestLimit(100)
                .build("\\w+");
        assertTrue(re.isMatch("hello"));
    }

    @Test
    void capturesAll() {
        Regex re = Regex.compile("(?-u)(\\d+)([a-z])");
        List<Captures> all = re.capturesAll("1a2b3c").toList();
        assertEquals(3, all.size());
        assertEquals("1", all.get(0).group(1).orElseThrow().text());
        assertEquals("a", all.get(0).group(2).orElseThrow().text());
        assertEquals("2", all.get(1).group(1).orElseThrow().text());
        assertEquals("b", all.get(1).group(2).orElseThrow().text());
    }

    @Test
    void namedGroupNotFound() {
        Regex re = Regex.compile("(?P<x>\\d+)");
        Captures c = re.captures("42").orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> c.group("nonexistent"));
    }

    @Test
    void wordCharMatch() {
        // Use ASCII word chars directly
        Regex re = Regex.compile("\\w+");
        Match m = re.find("hello").orElseThrow();
        assertEquals("hello", m.text());
        assertEquals(0, m.start());
        assertEquals(5, m.end());
    }

    @Test
    void matchLength() {
        Match m = new Match(3, 7, "test");
        assertEquals(4, m.length());
    }

    @Test
    void alternation() {
        Regex re = Regex.compile("cat|dog");
        assertTrue(re.isMatch("I have a cat"));
        assertTrue(re.isMatch("I have a dog"));
        assertFalse(re.isMatch("I have a bird"));
    }

    @Test
    void charOffsetWithMultibyteUtf8() {
        // Test that char offsets are correct when input contains multi-byte chars
        // é is 2 bytes in UTF-8 but 1 char in Java
        Regex re = Regex.compile("world");
        Match m = re.find("café world").orElseThrow();
        assertEquals("world", m.text());
        assertEquals(5, m.start());  // char offset, not byte offset
        assertEquals(10, m.end());
    }

    @Test
    void capturesNoMatch() {
        Regex re = Regex.compile("(?-u)(\\d+)");
        assertTrue(re.captures("abcdef").isEmpty());
    }

    @Test
    void isMatchNoMatch() {
        Regex re = Regex.compile("xyz");
        assertFalse(re.isMatch("abc"));
    }

    @Test
    void findAllWithAlternation() {
        Regex re = Regex.compile("a|b");
        List<Match> matches = re.findAll("axbxab").toList();
        assertEquals(4, matches.size());
        assertEquals("a", matches.get(0).text());
        assertEquals("b", matches.get(1).text());
        assertEquals("a", matches.get(2).text());
        assertEquals("b", matches.get(3).text());
    }
}
