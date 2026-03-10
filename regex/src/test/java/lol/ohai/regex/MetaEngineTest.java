package lol.ohai.regex;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetaEngineTest {

    // -- PrefilterOnly patterns (entire pattern is a fixed-length literal, no captures) --

    @Test
    void pureLiteralUsesPrefilterOnly() {
        Regex re = Regex.compile("Sherlock Holmes");
        assertTrue(re.isMatch("Mr. Sherlock Holmes of Baker Street"));
        assertFalse(re.isMatch("Dr. Watson"));
    }

    @Test
    void pureLiteralFind() {
        Regex re = Regex.compile("hello");
        var m = re.find("say hello world");
        assertTrue(m.isPresent());
        assertEquals(4, m.get().start());
        assertEquals(9, m.get().end());
        assertEquals("hello", m.get().text());
    }

    @Test
    void pureLiteralFindAll() {
        Regex re = Regex.compile("ab");
        List<Match> matches = re.findAll("ab cd ab ef ab").toList();
        assertEquals(3, matches.size());
        assertEquals(0, matches.get(0).start());
        assertEquals(6, matches.get(1).start());
        assertEquals(12, matches.get(2).start());
    }

    @Test
    void pureLiteralNoMatch() {
        Regex re = Regex.compile("xyz");
        assertFalse(re.isMatch("hello world"));
        assertTrue(re.find("hello world").isEmpty());
    }

    @Test
    void pureLiteralCaptures() {
        // Pure literal → PrefilterOnly → captures should still work (group 0)
        Regex re = Regex.compile("hello");
        var caps = re.captures("say hello");
        assertTrue(caps.isPresent());
        assertEquals("hello", caps.get().overall().text());
        assertEquals(1, caps.get().groupCount());
    }

    // -- Patterns with captures go to Core (even if literal) --

    @Test
    void literalWithCaptureUsesCoreStrategy() {
        Regex re = Regex.compile("(?P<word>hello)");
        var caps = re.captures("say hello world");
        assertTrue(caps.isPresent());
        assertEquals("hello", caps.get().group("word").get().text());
    }

    // -- Alternation of same-length literals → PrefilterOnly --

    @Test
    void alternationOfSameLengthLiterals() {
        Regex re = Regex.compile("cat|dog");
        assertTrue(re.isMatch("I have a cat"));
        assertTrue(re.isMatch("I have a dog"));
        assertFalse(re.isMatch("I have a fish"));

        var m = re.find("I have a dog and a cat");
        assertTrue(m.isPresent());
        assertEquals("dog", m.get().text());
    }

    // -- Alternation of different-length literals → Core with prefilter --

    @Test
    void alternationOfDifferentLengthLiterals() {
        Regex re = Regex.compile("cat|elephant");
        assertTrue(re.isMatch("I see a cat"));
        assertTrue(re.isMatch("I see an elephant"));

        var matches = re.findAll("cat and elephant").toList();
        assertEquals(2, matches.size());
        assertEquals("cat", matches.get(0).text());
        assertEquals("elephant", matches.get(1).text());
    }

    // -- Core with prefilter (literal prefix + regex suffix) --

    @Test
    void regexWithLiteralPrefixUsesPrefilter() {
        Regex re = Regex.compile("hello\\w+");
        var m = re.find("say helloWorld");
        assertTrue(m.isPresent());
        assertEquals("helloWorld", m.get().text());
    }

    @Test
    void regexWithLiteralPrefixFindAll() {
        Regex re = Regex.compile("hello\\d+");
        List<Match> matches = re.findAll("hello123 world hello456").toList();
        assertEquals(2, matches.size());
        assertEquals("hello123", matches.get(0).text());
        assertEquals("hello456", matches.get(1).text());
    }

    // -- Core without prefilter (no literal prefix) --

    @Test
    void regexWithoutLiteralPrefix() {
        Regex re = Regex.compile("[A-Z][a-z]+");
        var m = re.find("say Hello World");
        assertTrue(m.isPresent());
        assertEquals("Hello", m.get().text());
    }

    // -- Edge cases --

    @Test
    void singleCharLiteral() {
        Regex re = Regex.compile("x");
        var m = re.find("abcxdef");
        assertTrue(m.isPresent());
        assertEquals(3, m.get().start());
    }

    @Test
    void emptyPatternStillWorks() {
        // Empty pattern matches at every position
        Regex re = Regex.compile("");
        assertTrue(re.isMatch("hello"));
        var m = re.find("hello");
        assertTrue(m.isPresent());
        assertEquals(0, m.get().start());
        assertEquals(0, m.get().end());
    }

    @Test
    void capturesAllWithPrefilterPattern() {
        Regex re = Regex.compile("(?P<name>[A-Z][a-z]+) Holmes");
        var caps = re.capturesAll("Sherlock Holmes and Mycroft Holmes").toList();
        assertEquals(2, caps.size());
        assertEquals("Sherlock", caps.get(0).group("name").get().text());
        assertEquals("Mycroft", caps.get(1).group("name").get().text());
    }
}
