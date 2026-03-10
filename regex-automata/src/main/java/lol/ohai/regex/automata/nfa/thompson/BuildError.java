package lol.ohai.regex.automata.nfa.thompson;

/**
 * An error that occurs during NFA construction.
 */
public final class BuildError extends Exception {

    /**
     * The kind of build error.
     */
    public enum Kind {
        /** The NFA has too many states. */
        TOO_MANY_STATES,
        /** The NFA has too many capture groups. */
        TOO_MANY_CAPTURES,
    }

    private final Kind kind;

    /**
     * Creates a new build error.
     *
     * @param kind    the kind of error
     * @param message a human-readable description
     */
    public BuildError(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * Returns the kind of this build error.
     */
    public Kind kind() {
        return kind;
    }
}
