package lol.ohai.regex.automata.nfa.thompson;

/**
 * A single byte range transition in a {@link State.Sparse} state.
 *
 * @param start the inclusive lower bound of the byte range
 * @param end   the inclusive upper bound of the byte range
 * @param next  the target state ID
 */
public record Transition(int start, int end, int next) {}
