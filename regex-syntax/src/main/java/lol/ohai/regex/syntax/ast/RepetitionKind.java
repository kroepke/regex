package lol.ohai.regex.syntax.ast;

/**
 * The kind of a repetition operator.
 */
public sealed interface RepetitionKind {
    record ZeroOrOne() implements RepetitionKind {}
    record ZeroOrMore() implements RepetitionKind {}
    record OneOrMore() implements RepetitionKind {}
    record Range(RepetitionRange range) implements RepetitionKind {}

    RepetitionKind ZERO_OR_ONE = new ZeroOrOne();
    RepetitionKind ZERO_OR_MORE = new ZeroOrMore();
    RepetitionKind ONE_OR_MORE = new OneOrMore();
}
