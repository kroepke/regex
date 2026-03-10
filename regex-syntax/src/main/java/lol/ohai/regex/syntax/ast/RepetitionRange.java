package lol.ohai.regex.syntax.ast;

/**
 * A range repetition operator.
 */
public sealed interface RepetitionRange {
    record Exactly(int n) implements RepetitionRange {}
    record AtLeast(int n) implements RepetitionRange {}
    record Bounded(int start, int end) implements RepetitionRange {}

    default boolean isValid() {
        if (this instanceof Bounded b) {
            return b.start() <= b.end();
        }
        return true;
    }
}
