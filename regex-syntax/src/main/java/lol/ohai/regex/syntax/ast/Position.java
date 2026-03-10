package lol.ohai.regex.syntax.ast;

/**
 * A position in a regex pattern string.
 *
 * @param offset the absolute offset (starting at 0)
 * @param line the line number (starting at 1)
 * @param column the column number (starting at 1)
 */
public record Position(int offset, int line, int column) {
    public static Position of(int offset, int line, int column) {
        return new Position(offset, line, column);
    }
}
