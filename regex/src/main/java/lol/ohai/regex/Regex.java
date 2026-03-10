package lol.ohai.regex;

import lol.ohai.regex.automata.nfa.thompson.BuildError;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.pikevm.Cache;
import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.Translator;

import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A compiled regular expression. Thread-safe: can be shared across threads.
 *
 * <p>Compilation pipeline: pattern → AST → HIR → NFA → PikeVM.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * Regex re = Regex.compile("(?P<year>\\d{4})-(?P<month>\\d{2})");
 * re.isMatch("2026-03");               // true
 * re.find("date: 2026-03");            // Optional<Match>
 * re.findAll("2026-03 and 2027-04");   // Stream<Match>
 * re.captures("2026-03")               // Optional<Captures>
 *     .get().group("year");            // Optional<Match> "2026"
 * }</pre>
 */
public final class Regex {
    private final String pattern;
    private final PikeVM pikeVM;
    private final NFA nfa;
    private final Map<String, Integer> namedGroups;
    private final ThreadLocal<Cache> cachePool;

    private Regex(String pattern, PikeVM pikeVM) {
        this.pattern = pattern;
        this.pikeVM = pikeVM;
        this.nfa = pikeVM.nfa();
        this.namedGroups = buildNamedGroupMap(nfa);
        this.cachePool = ThreadLocal.withInitial(pikeVM::createCache);
    }

    /**
     * Compiles the given pattern into a Regex.
     *
     * @param pattern the regex pattern
     * @return the compiled Regex
     * @throws PatternSyntaxException if the pattern is invalid
     */
    public static Regex compile(String pattern) throws PatternSyntaxException {
        return new RegexBuilder().build(pattern);
    }

    /**
     * Returns a new {@link RegexBuilder} for configuring compilation options.
     */
    public static RegexBuilder builder() {
        return new RegexBuilder();
    }

    // Package-private: used by RegexBuilder
    static Regex create(String pattern, int nestLimit) throws PatternSyntaxException {
        try {
            Ast ast = Parser.parse(pattern, nestLimit);
            Hir hir = Translator.translate(pattern, ast);
            NFA nfa = Compiler.compile(hir);
            PikeVM pikeVM = new PikeVM(nfa);
            return new Regex(pattern, pikeVM);
        } catch (lol.ohai.regex.syntax.ast.Error | lol.ohai.regex.syntax.hir.Error e) {
            throw new PatternSyntaxException(pattern, e);
        } catch (BuildError e) {
            throw new PatternSyntaxException(pattern, e);
        }
    }

    /** Returns the original pattern string. */
    public String pattern() {
        return pattern;
    }

    /**
     * Returns true if the pattern matches anywhere in the input.
     */
    public boolean isMatch(CharSequence text) {
        Cache cache = cachePool.get();
        Input input = Input.of(text);
        return pikeVM.isMatch(input, cache);
    }

    /**
     * Finds the first match in the input.
     *
     * @return the match, or empty if no match
     */
    public Optional<Match> find(CharSequence text) {
        Cache cache = cachePool.get();
        Input input = Input.of(text);
        lol.ohai.regex.automata.util.Captures caps = pikeVM.search(input, cache);
        if (caps == null) {
            return Optional.empty();
        }
        return Optional.of(toMatch(text, caps, 0));
    }

    /**
     * Returns a stream of all non-overlapping matches in the input.
     */
    public Stream<Match> findAll(CharSequence text) {
        Iterator<Match> iter = new MatchIterator(text);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iter,
                        Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    /**
     * Finds the first match with all capture groups populated.
     *
     * @return the captures, or empty if no match
     */
    public Optional<Captures> captures(CharSequence text) {
        Cache cache = cachePool.get();
        Input input = Input.of(text);
        lol.ohai.regex.automata.util.Captures caps = pikeVM.searchCaptures(input, cache);
        if (caps == null) {
            return Optional.empty();
        }
        return Optional.of(toCaptures(text, caps));
    }

    /**
     * Returns a stream of all non-overlapping capture results.
     */
    public Stream<Captures> capturesAll(CharSequence text) {
        Iterator<Captures> iter = new CapturesIterator(text);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iter,
                        Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    @Override
    public String toString() {
        return pattern;
    }

    // -- Internal helpers --

    private Match toMatch(CharSequence text,
                          lol.ohai.regex.automata.util.Captures caps, int group) {
        int start = caps.start(group);
        int end = caps.end(group);
        return new Match(start, end, text.subSequence(start, end).toString());
    }

    private Captures toCaptures(CharSequence text,
                                lol.ohai.regex.automata.util.Captures caps) {
        int groupCount = caps.groupCount();
        List<Optional<Match>> groups = new ArrayList<>(groupCount);
        Match overall = null;
        for (int i = 0; i < groupCount; i++) {
            if (caps.isMatched(i)) {
                Match m = toMatch(text, caps, i);
                groups.add(Optional.of(m));
                if (i == 0) {
                    overall = m;
                }
            } else {
                groups.add(Optional.empty());
            }
        }
        return new Captures(overall, Collections.unmodifiableList(groups), namedGroups);
    }

    private static Map<String, Integer> buildNamedGroupMap(NFA nfa) {
        List<String> names = nfa.groupNames();
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name != null) {
                map.put(name, i);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    /**
     * Base class for iterating over successive non-overlapping matches.
     * Handles the tricky case of zero-width matches (must advance by at least one char).
     */
    private abstract class BaseFindIterator<T> implements Iterator<T> {
        final CharSequence text;
        private int searchCharStart = 0;
        private int lastMatchCharEnd = -1; // -1 = no match yet
        private T nextResult;
        private boolean done = false;

        BaseFindIterator(CharSequence text) {
            this.text = text;
        }

        abstract lol.ohai.regex.automata.util.Captures doSearch(Input input, Cache cache);
        abstract T toResult(CharSequence text, lol.ohai.regex.automata.util.Captures caps);

        @Override
        public boolean hasNext() {
            if (nextResult != null) return true;
            if (done) return false;
            advance();
            return nextResult != null;
        }

        @Override
        public T next() {
            if (!hasNext()) throw new NoSuchElementException();
            T result = nextResult;
            nextResult = null;
            return result;
        }

        private void advance() {
            Cache cache = cachePool.get();

            while (!done) {
                if (searchCharStart > text.length()) {
                    done = true;
                    return;
                }

                Input input = Input.of(text, searchCharStart, text.length());
                lol.ohai.regex.automata.util.Captures caps = doSearch(input, cache);

                if (caps == null) {
                    done = true;
                    return;
                }

                int charStart = caps.start(0);
                int charEnd = caps.end(0);

                // After a non-empty match ending at P, if we find an empty match also
                // at P, skip it and advance by one codepoint. This matches the upstream
                // Rust regex crate's iteration semantics.
                if (charStart == charEnd && charEnd == lastMatchCharEnd) {
                    if (charEnd < text.length()) {
                        searchCharStart = charEnd + Character.charCount(
                                Character.codePointAt(text, charEnd));
                    } else {
                        searchCharStart = charEnd + 1; // past end → done on next iteration
                    }
                    continue;
                }

                lastMatchCharEnd = charEnd;
                searchCharStart = charEnd;

                // For empty matches, set searchCharStart to charEnd so the next search
                // starts from the same position. The duplicate detection above will then
                // skip and advance if the same empty match is found again.

                nextResult = toResult(text, caps);
                return;
            }
        }
    }

    private final class MatchIterator extends BaseFindIterator<Match> {
        MatchIterator(CharSequence text) { super(text); }

        @Override
        lol.ohai.regex.automata.util.Captures doSearch(Input input, Cache cache) {
            return pikeVM.search(input, cache);
        }

        @Override
        Match toResult(CharSequence text, lol.ohai.regex.automata.util.Captures caps) {
            return toMatch(text, caps, 0);
        }
    }

    private final class CapturesIterator extends BaseFindIterator<Captures> {
        CapturesIterator(CharSequence text) { super(text); }

        @Override
        lol.ohai.regex.automata.util.Captures doSearch(Input input, Cache cache) {
            return pikeVM.searchCaptures(input, cache);
        }

        @Override
        Captures toResult(CharSequence text, lol.ohai.regex.automata.util.Captures caps) {
            return toCaptures(text, caps);
        }
    }
}
