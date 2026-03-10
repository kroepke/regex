package lol.ohai.regex.syntax.ast;

/**
 * The available ASCII character classes.
 */
public enum ClassAsciiKind {
    ALNUM("alnum"),
    ALPHA("alpha"),
    ASCII("ascii"),
    BLANK("blank"),
    CNTRL("cntrl"),
    DIGIT("digit"),
    GRAPH("graph"),
    LOWER("lower"),
    PRINT("print"),
    PUNCT("punct"),
    SPACE("space"),
    UPPER("upper"),
    WORD("word"),
    XDIGIT("xdigit");

    private final String name;

    ClassAsciiKind(String name) {
        this.name = name;
    }

    /** Return the lowercase name of this class. */
    public String className() {
        return name;
    }

    /** Return the ClassAsciiKind for the given lowercase name, or null if not found. */
    public static ClassAsciiKind fromName(String name) {
        return switch (name) {
            case "alnum" -> ALNUM;
            case "alpha" -> ALPHA;
            case "ascii" -> ASCII;
            case "blank" -> BLANK;
            case "cntrl" -> CNTRL;
            case "digit" -> DIGIT;
            case "graph" -> GRAPH;
            case "lower" -> LOWER;
            case "print" -> PRINT;
            case "punct" -> PUNCT;
            case "space" -> SPACE;
            case "upper" -> UPPER;
            case "word" -> WORD;
            case "xdigit" -> XDIGIT;
            default -> null;
        };
    }
}
