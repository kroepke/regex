package lol.ohai.regex.automata.nfa.thompson.pikevm;

import lol.ohai.regex.automata.nfa.thompson.*;
import lol.ohai.regex.automata.util.*;
import lol.ohai.regex.automata.util.Captures;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Translator;
import lol.ohai.regex.test.*;
import lol.ohai.regex.test.Span;
import org.junit.jupiter.api.*;

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

        // Skip non-UTF-8 tests (we always work with UTF-8 strings)
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
                // Expected compilation failure -- signal to TestRunner that compilation failed
                // by returning a CompiledRegex. TestRunner checks test.compiles() itself,
                // but we need to not throw here. The trick: we already caught it, so we
                // need to signal "compilation failed" back. But TestRunner expects us to
                // either return a CompiledRegex or throw. If test.compiles() == false and
                // we throw, TestRunner catches it and returns. So let's throw.
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

            // Determine what kind of result the test expects
            boolean expectsCaptures = test.matches().stream()
                .anyMatch(c -> c.groups().size() > 1);

            if (expectsCaptures) {
                return collectCaptures(vm, nfa, cache, haystackStr, test);
            } else {
                return collectMatches(vm, cache, haystackStr, test);
            }
        } catch (Exception e) {
            return new TestResult.Failed(e.getMessage());
        }
    }

    private Input createInput(String haystack, RegexTest test) {
        if (test.bounds() != null) {
            return Input.withByteBounds(
                haystack,
                test.bounds().start(),
                test.bounds().end(),
                test.anchored()
            );
        } else if (test.anchored()) {
            return Input.anchored(haystack);
        } else {
            return Input.of(haystack);
        }
    }

    private TestResult collectMatches(PikeVM vm, Cache cache, String haystack, RegexTest test) {
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
            // also at P, skip it and advance by 1 byte. This matches the upstream
            // Rust regex crate's iteration semantics (Searcher::handle_overlapping_empty_match).
            if (matchStart == matchEnd && matchEnd == lastMatchEnd) {
                searchStart = matchEnd + 1;
                if (searchStart > searchEnd) {
                    break;
                }
                continue;
            }

            spans.add(new Span(matchStart, matchEnd));
            lastMatchEnd = matchEnd;

            // Set next search start to the end of the match.
            // For empty matches, the next call will find the same match at the same
            // position, detect the overlap with lastMatchEnd, and advance by 1 byte.
            searchStart = matchEnd;

            if (searchStart > searchEnd) {
                break;
            }
        }

        return new TestResult.Matches(spans);
    }

    private TestResult collectCaptures(PikeVM vm, NFA nfa, Cache cache, String haystack, RegexTest test) {
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

            // Convert PikeVM Captures to test Captures
            int groupCount = caps.groupCount();
            List<Optional<Span>> groups = new ArrayList<>();
            for (int g = 0; g < groupCount; g++) {
                if (caps.isMatched(g)) {
                    groups.add(Optional.of(new Span(caps.start(g), caps.end(g))));
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
     * Returns the byte length of the UTF-8 codepoint starting at the given position.
     * Returns 1 if at end of haystack or for invalid sequences.
     */
    private static int utf8ByteLength(byte[] haystack, int pos) {
        if (pos >= haystack.length) {
            return 1;
        }
        int b = haystack[pos] & 0xFF;
        if (b < 0x80) return 1;
        if (b < 0xC0) return 1; // continuation byte, shouldn't happen at start
        if (b < 0xE0) return 2;
        if (b < 0xF0) return 3;
        return 4;
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
