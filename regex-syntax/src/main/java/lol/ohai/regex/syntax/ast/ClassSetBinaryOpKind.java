package lol.ohai.regex.syntax.ast;

/**
 * The type of a character class set operation.
 */
public enum ClassSetBinaryOpKind {
    /** The intersection of two sets ({@code &&}). */
    INTERSECTION,
    /** The difference of two sets ({@code --}). */
    DIFFERENCE,
    /** The symmetric difference of two sets ({@code ~~}). */
    SYMMETRIC_DIFFERENCE
}
