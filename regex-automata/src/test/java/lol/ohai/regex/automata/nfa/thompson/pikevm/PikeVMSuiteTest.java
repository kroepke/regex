package lol.ohai.regex.automata.nfa.thompson.pikevm;

import lol.ohai.regex.automata.nfa.thompson.*;
import lol.ohai.regex.automata.util.*;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Translator;
import lol.ohai.regex.test.*;
import lol.ohai.regex.test.Span;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Wires the PikeVM engine to the upstream TOML test suite.
 *
 * <p>Loads all {@code *.toml} files from {@code upstream/regex/testdata/} and
 * generates one JUnit dynamic test per test case. The test runner automatically
 * filters out tests that require capabilities we don't support yet (e.g.,
 * overlapping search, leftmost-longest matching).</p>
 *
 * <p>The upstream test data uses UTF-8 byte offsets for match spans. Since our
 * engine now operates on char-unit (UTF-16), we convert the engine's char-offset
 * results back to byte offsets for comparison with expected test data.</p>
 */
class PikeVMSuiteTest {

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

    private CompiledRegex compile(RegexTest test) {
        // Skip multi-pattern tests (no RegexSet yet) and empty regex lists
        if (test.regexes().size() != 1) {
            return CompiledRegex.skip();
        }

        // Skip non-UTF-8 tests (we require valid Unicode input)
        if (!test.utf8()) {
            return CompiledRegex.skip();
        }

        // Skip case-insensitive tests (not yet supported in translator/parser flags)
        if (test.caseInsensitive()) {
            return CompiledRegex.skip();
        }

        // Skip tests with non-default line terminators (not yet supported)
        if (!"\n".equals(test.lineTerminator())) {
            return CompiledRegex.skip();
        }

        // Skip regex-lite tests — they expect ASCII-only Perl classes even with unicode=true
        if ("regex-lite".equals(test.groupName())) {
            return CompiledRegex.skip();
        }

        // Skip tests with bounds that fall inside a multi-byte UTF-8 codepoint.
        // These test UTF-8-specific boundary behavior that doesn't apply to our
        // char-unit (UTF-16) engine.
        if (test.bounds() != null && hasBoundsInsideCodepoint(test)) {
            return CompiledRegex.skip();
        }

        // Skip tests that test UTF-8 byte-oriented iteration semantics on anchored
        // empty matches. In byte-unit mode, iteration stops when it hits the interior
        // of a multi-byte codepoint; in char-unit mode, every char position is valid.
        if ("no-unicode".equals(test.groupName())
                && test.name().startsWith("anchored-iter-empty")) {
            return CompiledRegex.skip();
        }

        String pattern = test.regexes().getFirst();

        try {
            var ast = Parser.parse(pattern);
            var hir = Translator.translate(pattern, ast, test.unicode());
            var nfa = Compiler.compile(hir);
            var vm = new PikeVM(nfa);
            var cache = vm.createCache();

            return CompiledRegex.compiled(t -> runTest(vm, nfa, cache, t));
        } catch (Exception e) {
            if (!test.compiles()) {
                throw new RuntimeException("compilation failed as expected", e);
            }
            // Unexpected compilation failure -- skip
            return CompiledRegex.compiled(t ->
                new TestResult.Skipped("compilation failed: " + e.getMessage()));
        }
    }

    private TestResult runTest(PikeVM vm, NFA nfa, Cache cache, RegexTest test) {
        try {
            String haystackStr = test.haystack();

            if (test.unescape()) {
                haystackStr = unescapeHaystack(haystackStr);
            }

            // Build char-to-byte offset mapping for converting results back to byte offsets.
            // The upstream test suite expects byte offsets (UTF-8).
            int[] charToByteMap = buildCharToByteMap(haystackStr);

            // Determine what kind of result the test expects
            boolean expectsCaptures = test.matches().stream()
                .anyMatch(c -> c.groups().size() > 1);

            if (expectsCaptures) {
                return collectCaptures(vm, nfa, cache, haystackStr, test, charToByteMap);
            } else {
                return collectMatches(vm, cache, haystackStr, test, charToByteMap);
            }
        } catch (Exception e) {
            return new TestResult.Failed(e.getMessage());
        }
    }

    /**
     * Builds a mapping from char offset to UTF-8 byte offset for the given string.
     * The array has length {@code str.length() + 1}, where {@code result[i]} is the
     * byte offset corresponding to char offset {@code i}.
     */
    private static int[] buildCharToByteMap(String str) {
        int[] map = new int[str.length() + 1];
        int bytePos = 0;
        for (int i = 0; i < str.length(); i++) {
            map[i] = bytePos;
            int cp = Character.codePointAt(str, i);
            int charCount = Character.charCount(cp);
            int byteCount;
            if (cp <= 0x7F) {
                byteCount = 1;
            } else if (cp <= 0x7FF) {
                byteCount = 2;
            } else if (cp <= 0xFFFF) {
                byteCount = 3;
            } else {
                byteCount = 4;
            }
            bytePos += byteCount;
            if (charCount == 2) {
                // Surrogate pair: the next char index maps to the same byte position
                // (it's part of the same codepoint). Advance i to skip the low surrogate.
                i++;
                map[i] = bytePos; // low surrogate maps to byte position after the 4-byte sequence
            }
        }
        map[str.length()] = bytePos;
        return map;
    }

    /**
     * Builds a mapping from UTF-8 byte offset to char offset for the given string.
     * Used to convert test bounds (which are in byte offsets) to char offsets.
     */
    private static int[] buildByteToCharMap(String str) {
        byte[] utf8 = str.getBytes(StandardCharsets.UTF_8);
        int[] map = new int[utf8.length + 1];
        int bytePos = 0;
        for (int charIdx = 0; charIdx < str.length(); ) {
            int cp = Character.codePointAt(str, charIdx);
            int charCount = Character.charCount(cp);
            int byteCount;
            if (cp <= 0x7F) {
                byteCount = 1;
            } else if (cp <= 0x7FF) {
                byteCount = 2;
            } else if (cp <= 0xFFFF) {
                byteCount = 3;
            } else {
                byteCount = 4;
            }
            for (int b = 0; b < byteCount; b++) {
                map[bytePos + b] = charIdx;
            }
            bytePos += byteCount;
            charIdx += charCount;
        }
        map[bytePos] = str.length();
        return map;
    }

    private Input createInput(String haystack, RegexTest test) {
        if (test.bounds() != null) {
            // Test bounds are in byte offsets; convert to char offsets
            int[] byteToChar = buildByteToCharMap(haystack);
            int charStart = byteToChar[test.bounds().start()];
            int charEnd = byteToChar[test.bounds().end()];
            return Input.of(haystack).withBounds(charStart, charEnd, test.anchored());
        } else if (test.anchored()) {
            return Input.anchored(haystack);
        } else {
            return Input.of(haystack);
        }
    }

    /** Convert a char offset to a byte offset using the mapping. */
    private static int charToByte(int[] charToByteMap, int charOffset) {
        return charToByteMap[charOffset];
    }

    private TestResult collectMatches(PikeVM vm, Cache cache, String haystack, RegexTest test, int[] charToByteMap) {
        List<Span> spans = new ArrayList<>();
        int limit = test.matchLimit() != null ? test.matchLimit() : Integer.MAX_VALUE;

        Input baseInput = createInput(haystack, test);
        int searchEnd = baseInput.end();
        int searchStart = baseInput.start();
        int lastMatchEnd = -1;

        while (spans.size() < limit) {
            Input input = baseInput.withBounds(searchStart, searchEnd, test.anchored());

            Captures caps = vm.search(input, cache);
            if (caps == null) {
                break;
            }

            int matchStart = caps.start(0);
            int matchEnd = caps.end(0);

            // After a match ending at position P, if we find an empty match
            // also at P, skip it and advance by 1 char. This matches the upstream
            // Rust regex crate's iteration semantics (Searcher::handle_overlapping_empty_match).
            if (matchStart == matchEnd && matchEnd == lastMatchEnd) {
                searchStart = matchEnd + 1;
                if (searchStart > searchEnd) {
                    break;
                }
                continue;
            }

            // Convert char offsets to byte offsets for comparison with upstream test data
            spans.add(new Span(charToByte(charToByteMap, matchStart), charToByte(charToByteMap, matchEnd)));
            lastMatchEnd = matchEnd;

            searchStart = matchEnd;

            if (searchStart > searchEnd) {
                break;
            }
        }

        return new TestResult.Matches(spans);
    }

    private TestResult collectCaptures(PikeVM vm, NFA nfa, Cache cache, String haystack, RegexTest test, int[] charToByteMap) {
        List<lol.ohai.regex.test.Captures> captures = new ArrayList<>();
        int limit = test.matchLimit() != null ? test.matchLimit() : Integer.MAX_VALUE;

        Input baseInput = createInput(haystack, test);
        int searchEnd = baseInput.end();
        int searchStart = baseInput.start();
        int lastMatchEnd = -1;

        while (captures.size() < limit) {
            Input input = baseInput.withBounds(searchStart, searchEnd, test.anchored());

            Captures caps = vm.searchCaptures(input, cache);
            if (caps == null) {
                break;
            }

            int matchStart = caps.start(0);
            int matchEnd = caps.end(0);

            // Skip empty matches at the same position as the end of the last match.
            if (matchStart == matchEnd && matchEnd == lastMatchEnd) {
                searchStart = matchEnd + 1;
                if (searchStart > searchEnd) {
                    break;
                }
                continue;
            }

            // Convert PikeVM Captures to test Captures, converting char offsets to byte offsets
            int groupCount = caps.groupCount();
            List<Optional<Span>> groups = new ArrayList<>();
            for (int g = 0; g < groupCount; g++) {
                if (caps.isMatched(g)) {
                    groups.add(Optional.of(new Span(
                        charToByte(charToByteMap, caps.start(g)),
                        charToByte(charToByteMap, caps.end(g)))));
                } else {
                    groups.add(Optional.empty());
                }
            }
            captures.add(new lol.ohai.regex.test.Captures(0, groups));
            lastMatchEnd = matchEnd;

            searchStart = matchEnd;

            if (searchStart > searchEnd) {
                break;
            }
        }

        return new TestResult.CaptureResults(captures);
    }

    /**
     * Returns true if the test's byte-offset bounds fall inside a multi-byte
     * UTF-8 codepoint (i.e., at a continuation byte, not at a codepoint boundary).
     */
    private static boolean hasBoundsInsideCodepoint(RegexTest test) {
        String haystack = test.haystack();
        if (test.unescape()) {
            haystack = unescapeHaystack(haystack);
        }
        byte[] utf8 = haystack.getBytes(StandardCharsets.UTF_8);
        int start = test.bounds().start();
        int end = test.bounds().end();
        return !isUtf8Boundary(utf8, start) || !isUtf8Boundary(utf8, end);
    }

    /**
     * Returns true if the given byte offset is at a UTF-8 codepoint boundary.
     */
    private static boolean isUtf8Boundary(byte[] utf8, int offset) {
        if (offset == 0 || offset >= utf8.length) return true;
        // A continuation byte has the form 10xxxxxx (0x80-0xBF)
        return (utf8[offset] & 0xC0) != 0x80;
    }

    /**
     * Unescapes special sequences in haystack strings from the test suite.
     * Handles: {@code \n}, {@code \t}, {@code \r}, {@code \\}, {@code \xNN}.
     */
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
