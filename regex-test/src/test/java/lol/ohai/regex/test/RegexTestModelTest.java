package lol.ohai.regex.test;

import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegexTestModelTest {

    private final TomlMapper mapper = new TomlMapper();

    @Test
    void deserializeSimpleTest() throws Exception {
        String toml = """
                [[test]]
                name = "basic"
                regex = "a"
                haystack = "a"
                matches = [[0, 1]]
                """;
        RegexTestFile file = mapper.readValue(toml, RegexTestFile.class);
        assertEquals(1, file.tests().size());
        RegexTest t = file.tests().getFirst();
        assertEquals("basic", t.name());
        assertEquals(List.of("a"), t.regexes());
        assertEquals("a", t.haystack());
        assertEquals(1, t.matches().size());
        assertEquals(new Span(0, 1), t.matches().getFirst().span());
    }

    @Test
    void deserializeWithDefaults() throws Exception {
        String toml = """
                [[test]]
                name = "defaults"
                regex = "a"
                haystack = "a"
                matches = []
                """;
        RegexTestFile file = mapper.readValue(toml, RegexTestFile.class);
        RegexTest t = file.tests().getFirst();
        assertTrue(t.compiles());
        assertFalse(t.anchored());
        assertTrue(t.unicode());
        assertTrue(t.utf8());
        assertEquals(MatchKind.LEFTMOST_FIRST, t.matchKind());
        assertEquals(SearchKind.LEFTMOST, t.searchKind());
    }

    @Test
    void deserializeCapturesFormat1SimpleSpan() throws Exception {
        String toml = """
                [[test]]
                name = "fmt1"
                regex = "a"
                haystack = "a"
                matches = [[0, 1]]
                """;
        RegexTestFile file = mapper.readValue(toml, RegexTestFile.class);
        Captures c = file.tests().getFirst().matches().getFirst();
        assertEquals(0, c.id());
        assertEquals(new Span(0, 1), c.span());
    }

    @Test
    void deserializeCapturesFormat2SpanWithId() throws Exception {
        String toml = """
                [[test]]
                name = "fmt2"
                regex = ["a", "b"]
                haystack = "a"
                matches = [
                  { id = 0, span = [0, 1] },
                  { id = 1, span = [0, 1] },
                ]
                match-kind = "all"
                search-kind = "overlapping"
                """;
        RegexTestFile file = mapper.readValue(toml, RegexTestFile.class);
        List<Captures> matches = file.tests().getFirst().matches();
        assertEquals(2, matches.size());
        assertEquals(0, matches.get(0).id());
        assertEquals(new Span(0, 1), matches.get(0).span());
        assertEquals(1, matches.get(1).id());
        assertEquals(new Span(0, 1), matches.get(1).span());
    }

    @Test
    void deserializeCapturesFormat3Groups() throws Exception {
        String toml = """
                [[test]]
                name = "fmt3"
                regex = "(a)(b)"
                haystack = "ab"
                matches = [[[0, 2], [0, 1], [1, 2]]]
                """;
        RegexTestFile file = mapper.readValue(toml, RegexTestFile.class);
        Captures c = file.tests().getFirst().matches().getFirst();
        assertEquals(0, c.id());
        assertEquals(3, c.groups().size());
        assertEquals(new Span(0, 2), c.groups().get(0).orElseThrow());
        assertEquals(new Span(0, 1), c.groups().get(1).orElseThrow());
        assertEquals(new Span(1, 2), c.groups().get(2).orElseThrow());
    }

    @Test
    void deserializeCapturesFormat3NonParticipatingGroup() throws Exception {
        String toml = """
                [[test]]
                name = "fmt3-nonparticipating"
                regex = "(a)|(b)"
                haystack = "a"
                matches = [[[0, 1], [0, 1], []]]
                """;
        RegexTestFile file = mapper.readValue(toml, RegexTestFile.class);
        Captures c = file.tests().getFirst().matches().getFirst();
        assertEquals(3, c.groups().size());
        assertTrue(c.groups().get(0).isPresent());
        assertTrue(c.groups().get(1).isPresent());
        assertTrue(c.groups().get(2).isEmpty());
    }

    @Test
    void deserializeCapturesFormat4SpansWithId() throws Exception {
        String toml = """
                [[test]]
                name = "fmt4"
                regex = ["(\\\\w+) (\\\\w+)", "(\\\\w+)"]
                haystack = "Bruce Springsteen"
                matches = [
                  { id = 0, spans = [[0, 17], [0, 5], [6, 17]] },
                  { id = 1, spans = [[0, 5]] },
                ]
                match-kind = "all"
                search-kind = "overlapping"
                unicode = false
                utf8 = false
                """;
        RegexTestFile file = mapper.readValue(toml, RegexTestFile.class);
        List<Captures> matches = file.tests().getFirst().matches();
        assertEquals(2, matches.size());
        Captures c0 = matches.get(0);
        assertEquals(0, c0.id());
        assertEquals(3, c0.groups().size());
        assertEquals(new Span(0, 17), c0.groups().get(0).orElseThrow());
        assertEquals(new Span(0, 5), c0.groups().get(1).orElseThrow());
        assertEquals(new Span(6, 17), c0.groups().get(2).orElseThrow());
        Captures c1 = matches.get(1);
        assertEquals(1, c1.id());
        assertEquals(1, c1.groups().size());
    }

    @Test
    void deserializeMultiPattern() throws Exception {
        String toml = """
                [[test]]
                name = "multi"
                regex = ["a", "b"]
                haystack = "ab"
                matches = [[0, 1]]
                """;
        RegexTestFile file = mapper.readValue(toml, RegexTestFile.class);
        assertEquals(List.of("a", "b"), file.tests().getFirst().regexes());
    }

    @Test
    void deserializeMatchKindAndSearchKind() throws Exception {
        String toml = """
                [[test]]
                name = "kinds"
                regex = "a"
                haystack = "a"
                matches = []
                match-kind = "leftmost-longest"
                search-kind = "earliest"
                """;
        RegexTestFile file = mapper.readValue(toml, RegexTestFile.class);
        assertEquals(MatchKind.LEFTMOST_LONGEST, file.tests().getFirst().matchKind());
        assertEquals(SearchKind.EARLIEST, file.tests().getFirst().searchKind());
    }

    @Test
    void deserializeBounds() throws Exception {
        String toml = """
                [[test]]
                name = "bounded"
                regex = ".c"
                haystack = "aabc"
                bounds = [1, 4]
                matches = []
                anchored = true
                """;
        RegexTestFile file = mapper.readValue(toml, RegexTestFile.class);
        RegexTest t = file.tests().getFirst();
        assertNotNull(t.bounds());
        assertEquals(new Span(1, 4), t.bounds());
    }

    @Test
    void deserializeNoMatchesField() throws Exception {
        // matches field absent entirely should default to empty list
        String toml = """
                [[test]]
                name = "no-matches-field"
                regex = "a"
                haystack = "z"
                """;
        RegexTestFile file = mapper.readValue(toml, RegexTestFile.class);
        RegexTest t = file.tests().getFirst();
        assertNotNull(t.matches());
        assertTrue(t.matches().isEmpty());
    }

    @Test
    void deserializeUpstreamMiscToml() throws Exception {
        // Smoke test: deserialize actual upstream file
        java.nio.file.Path path = java.nio.file.Path.of("../upstream/regex/testdata/misc.toml");
        RegexTestFile file = mapper.readValue(path.toFile(), RegexTestFile.class);
        assertFalse(file.tests().isEmpty(), "misc.toml should have tests");
    }

    @Test
    void deserializeUpstreamSetToml() throws Exception {
        // Smoke test: deserialize set.toml which has format 2 and 4 matches
        java.nio.file.Path path = java.nio.file.Path.of("../upstream/regex/testdata/set.toml");
        RegexTestFile file = mapper.readValue(path.toFile(), RegexTestFile.class);
        assertFalse(file.tests().isEmpty(), "set.toml should have tests");
    }
}
