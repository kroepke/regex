package lol.ohai.regex;

import lol.ohai.regex.automata.meta.MultiLiteral;
import lol.ohai.regex.automata.meta.Prefilter;
import lol.ohai.regex.automata.meta.SingleLiteral;
import lol.ohai.regex.automata.meta.Strategy;
import lol.ohai.regex.automata.dfa.CharClassBuilder;
import lol.ohai.regex.automata.dfa.CharClasses;
import lol.ohai.regex.automata.dfa.lazy.LazyDFA;
import lol.ohai.regex.automata.nfa.thompson.BuildError;
import lol.ohai.regex.automata.nfa.thompson.Compiler;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.pikevm.PikeVM;
import lol.ohai.regex.automata.util.Input;
import lol.ohai.regex.syntax.ast.Ast;
import lol.ohai.regex.syntax.ast.Parser;
import lol.ohai.regex.syntax.hir.Hir;
import lol.ohai.regex.syntax.hir.LiteralExtractor;
import lol.ohai.regex.syntax.hir.LiteralSeq;
import lol.ohai.regex.syntax.hir.Translator;

import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * A compiled regular expression. Thread-safe: can be shared across threads.
 *
 * <p>Compilation pipeline: pattern → AST → HIR → Strategy (prefilter + engine).</p>
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
    private final Strategy strategy;
    private final Map<String, Integer> namedGroups;
    private final ThreadLocal<Strategy.Cache> cachePool;

    private Regex(String pattern, Strategy strategy, Map<String, Integer> namedGroups) {
        this.pattern = pattern;
        this.strategy = strategy;
        this.namedGroups = namedGroups;
        this.cachePool = ThreadLocal.withInitial(strategy::createCache);
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

            // Extract prefix literals for prefilter
            LiteralSeq prefixes = LiteralExtractor.extractPrefixes(hir);
            Prefilter prefilter = buildPrefilter(prefixes);

            // Select strategy
            Strategy strategy;
            Map<String, Integer> namedGroups;

            if (prefilter != null && prefilter.isExact()
                    && prefixes.coversEntirePattern() && !hirHasCaptures(hir)) {
                strategy = new Strategy.PrefilterOnly(prefilter);
                namedGroups = Collections.emptyMap();
            } else {
                NFA nfa = Compiler.compile(hir);
                CharClasses charClasses = CharClassBuilder.build(nfa);
                PikeVM pikeVM = new PikeVM(nfa);
                LazyDFA lazyDFA = LazyDFA.create(nfa, charClasses);
                strategy = new Strategy.Core(pikeVM, lazyDFA, prefilter);
                namedGroups = buildNamedGroupMap(nfa);
            }

            return new Regex(pattern, strategy, namedGroups);
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
        Strategy.Cache cache = cachePool.get();
        Input input = Input.of(text);
        return strategy.isMatch(input, cache);
    }

    /**
     * Finds the first match in the input.
     *
     * @return the match, or empty if no match
     */
    public Optional<Match> find(CharSequence text) {
        Strategy.Cache cache = cachePool.get();
        Input input = Input.of(text);
        lol.ohai.regex.automata.util.Captures caps = strategy.search(input, cache);
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
        Strategy.Cache cache = cachePool.get();
        Input input = Input.of(text);
        lol.ohai.regex.automata.util.Captures caps = strategy.searchCaptures(input, cache);
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

    private static Prefilter buildPrefilter(LiteralSeq prefixes) {
        return switch (prefixes) {
            case LiteralSeq.None ignored -> null;
            case LiteralSeq.Single single -> new SingleLiteral(single.literal());
            case LiteralSeq.Alternation alt -> new MultiLiteral(
                    alt.literals().toArray(char[][]::new));
        };
    }

    private static boolean hirHasCaptures(Hir hir) {
        return switch (hir) {
            case Hir.Capture ignored -> true;
            case Hir.Concat concat -> concat.subs().stream().anyMatch(Regex::hirHasCaptures);
            case Hir.Alternation alt -> alt.subs().stream().anyMatch(Regex::hirHasCaptures);
            case Hir.Repetition rep -> hirHasCaptures(rep.sub());
            default -> false;
        };
    }

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
        private final Input baseInput;
        private int searchCharStart = 0;
        private int lastMatchCharEnd = -1; // -1 = no match yet
        private T nextResult;
        private boolean done = false;

        BaseFindIterator(CharSequence text) {
            this.text = text;
            this.baseInput = Input.of(text);
        }

        abstract lol.ohai.regex.automata.util.Captures doSearch(
                Input input, Strategy.Cache cache);
        abstract T toResult(CharSequence text,
                            lol.ohai.regex.automata.util.Captures caps);

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
            Strategy.Cache cache = cachePool.get();

            while (!done) {
                if (searchCharStart > text.length()) {
                    done = true;
                    return;
                }

                Input input = baseInput.withBounds(searchCharStart, text.length(), false);
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

                nextResult = toResult(text, caps);
                return;
            }
        }
    }

    private final class MatchIterator extends BaseFindIterator<Match> {
        MatchIterator(CharSequence text) { super(text); }

        @Override
        lol.ohai.regex.automata.util.Captures doSearch(
                Input input, Strategy.Cache cache) {
            return strategy.search(input, cache);
        }

        @Override
        Match toResult(CharSequence text,
                       lol.ohai.regex.automata.util.Captures caps) {
            return toMatch(text, caps, 0);
        }
    }

    private final class CapturesIterator extends BaseFindIterator<Captures> {
        CapturesIterator(CharSequence text) { super(text); }

        @Override
        lol.ohai.regex.automata.util.Captures doSearch(
                Input input, Strategy.Cache cache) {
            return strategy.searchCaptures(input, cache);
        }

        @Override
        Captures toResult(CharSequence text,
                          lol.ohai.regex.automata.util.Captures caps) {
            return toCaptures(text, caps);
        }
    }
}
