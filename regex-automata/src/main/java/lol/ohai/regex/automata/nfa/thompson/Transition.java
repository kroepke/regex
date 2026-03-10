package lol.ohai.regex.automata.nfa.thompson;

/**
 * A single char range transition in a {@link State.Sparse} state.
 *
 * @param start the inclusive lower bound of the char range
 * @param end   the inclusive upper bound of the char range
 * @param next  the target state ID
 */
public record Transition(int start, int end, int next) {}
