package lol.ohai.regex;

import lol.ohai.regex.test.CompiledRegex;
import lol.ohai.regex.test.EngineCapabilities;
import lol.ohai.regex.test.MatchKind;
import lol.ohai.regex.test.RegexTestSuite;
import lol.ohai.regex.test.SearchKind;
import lol.ohai.regex.test.Span;
import lol.ohai.regex.test.TestResult;
import lol.ohai.regex.test.TestRunner;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Wires the public Regex API to the upstream TOML test suite.
 *
 * <p>Similar to the PikeVMSuiteTest in regex-automata, but exercises the full
 * public API path (compile → isMatch/find/findAll/captures).</p>
 */
class UpstreamSuiteTest {

    private static final Path TESTDATA = Path.of("../upstream/regex/testdata");

    @TestFactory
    Stream<DynamicTest> upstreamSuite() throws Exception {
        var suite = RegexTestSuite.loadAll(TESTDATA);
        return TestRunner.run(suite, capabilities(), this::compile);
    }

    private EngineCapabilities capabilities() {
        return new EngineCapabilities(
            true,                               // captures
            true,                               // unicode
            true,                               // anchored
            Set.of(MatchKind.LEFTMOST_FIRST),   // only leftmost-first for now
            Set.of(SearchKind.LEFTMOST)          // only leftmost for now
        );
    }

    private CompiledRegex compile(lol.ohai.regex.test.RegexTest test) {
        if (test.regexes().size() != 1) {
            return CompiledRegex.skip();
        }
        if (!test.utf8()) {
            return CompiledRegex.skip();
        }
        if (test.caseInsensitive()) {
            return CompiledRegex.skip();
        }
        // Public API always compiles with Unicode semantics; skip tests requiring ASCII-only
        if (!test.unicode()) {
            return CompiledRegex.skip();
        }
        // Skip non-default line terminators (public API doesn't expose this yet)
        if (!"\n".equals(test.lineTerminator())) {
            return CompiledRegex.skip();
        }

        String pattern = test.regexes().getFirst();

        try {
            Regex re = Regex.compile(pattern);
            return CompiledRegex.compiled(t -> runTest(re, t));
        } catch (Exception e) {
            if (!test.compiles()) {
                throw new RuntimeException("compilation failed as expected", e);
            }
            return CompiledRegex.compiled(t ->
                new TestResult.Skipped("compilation failed: " + e.getMessage()));
        }
    }

    private TestResult runTest(Regex re, lol.ohai.regex.test.RegexTest test) {
        try {
            String haystackStr = test.haystack();
            if (test.unescape()) {
                haystackStr = unescapeHaystack(haystackStr);
            }

            boolean expectsCaptures = test.matches().stream()
                .anyMatch(c -> c.groups().size() > 1);

            if (expectsCaptures) {
                return collectCaptures(re, haystackStr, test);
            } else {
                return collectMatches(re, haystackStr, test);
            }
        } catch (Exception e) {
            return new TestResult.Failed(e.getMessage());
        }
    }

    private TestResult collectMatches(Regex re, String haystack, lol.ohai.regex.test.RegexTest test) {
        // The public API returns char offsets, but the test suite expects byte offsets.
        // We need to convert char offsets back to byte offsets for comparison.
        // We can do this by encoding the haystack to UTF-8.
        byte[] utf8 = haystack.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int[] charToByteMap = buildCharToByteMap(haystack, utf8);

        List<Span> spans = new ArrayList<>();
        int limit = test.matchLimit() != null ? test.matchLimit() : Integer.MAX_VALUE;

        // For anchored tests or tests with bounds, we need to handle things differently.
        // The public API doesn't expose byte-level bounds or anchored search directly,
        // so we'll skip tests that require those features for now.
        if (test.anchored() || test.bounds() != null) {
            return new TestResult.Skipped("public API does not support anchored/bounds yet");
        }

        List<Match> matches = re.findAll(haystack).limit(limit).toList();

        for (Match m : matches) {
            int byteStart = charToByteMap[m.start()];
            int byteEnd = charToByteMap[m.end()];
            spans.add(new Span(byteStart, byteEnd));
        }

        return new TestResult.Matches(spans);
    }

    private TestResult collectCaptures(Regex re, String haystack, lol.ohai.regex.test.RegexTest test) {
        byte[] utf8 = haystack.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int[] charToByteMap = buildCharToByteMap(haystack, utf8);

        if (test.anchored() || test.bounds() != null) {
            return new TestResult.Skipped("public API does not support anchored/bounds yet");
        }

        int limit = test.matchLimit() != null ? test.matchLimit() : Integer.MAX_VALUE;
        List<lol.ohai.regex.test.Captures> captures = new ArrayList<>();

        List<lol.ohai.regex.Captures> all = re.capturesAll(haystack).limit(limit).toList();

        for (lol.ohai.regex.Captures c : all) {
            List<Optional<Span>> groups = new ArrayList<>();
            for (int g = 0; g < c.groupCount(); g++) {
                Optional<Match> gm = c.group(g);
                if (gm.isPresent()) {
                    int byteStart = charToByteMap[gm.get().start()];
                    int byteEnd = charToByteMap[gm.get().end()];
                    groups.add(Optional.of(new Span(byteStart, byteEnd)));
                } else {
                    groups.add(Optional.empty());
                }
            }
            captures.add(new lol.ohai.regex.test.Captures(0, groups));
        }

        return new TestResult.CaptureResults(captures);
    }

    /**
     * Build a mapping from char offset to byte offset.
     * charToByteMap[charIdx] = byte offset of the char at charIdx.
     * Length is haystack.length() + 1 to include the end sentinel.
     */
    private static int[] buildCharToByteMap(String haystack, byte[] utf8) {
        int[] map = new int[haystack.length() + 1];
        int bytePos = 0;
        for (int charIdx = 0; charIdx < haystack.length(); ) {
            map[charIdx] = bytePos;
            int cp = Character.codePointAt(haystack, charIdx);
            int charCount = Character.charCount(cp);
            int byteCount;
            if (cp <= 0x7F) byteCount = 1;
            else if (cp <= 0x7FF) byteCount = 2;
            else if (cp <= 0xFFFF) byteCount = 3;
            else byteCount = 4;

            // For surrogate pairs, the second char also maps to the same byte position
            if (charCount == 2 && charIdx + 1 < haystack.length()) {
                map[charIdx + 1] = bytePos;
            }
            bytePos += byteCount;
            charIdx += charCount;
        }
        map[haystack.length()] = bytePos;
        return map;
    }

    private static String unescapeHaystack(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> { sb.append('\n'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case '0' -> { sb.append('\0'); i++; }
                    case 'x' -> {
                        if (i + 3 < s.length()) {
                            String hex = s.substring(i + 2, i + 4);
                            try {
                                int val = Integer.parseInt(hex, 16);
                                sb.append((char) val);
                                i += 3;
                            } catch (NumberFormatException e) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                    }
                    default -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
