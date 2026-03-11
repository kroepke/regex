package lol.ohai.regex.automata.nfa.thompson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lol.ohai.regex.automata.dfa.lazy.LookSet;

/**
 * Builder for constructing a Thompson {@link NFA}.
 *
 * <p>States are added one at a time via {@link #add(State)}, which returns
 * a state ID. After all states are added, call {@link #build()} to produce
 * the final NFA.</p>
 */
public final class Builder {
    private final List<State> states = new ArrayList<>();
    private int startAnchored = -1;
    private int startUnanchored = -1;
    private int captureSlotCount = 0;
    private int groupCount = 0;
    private final List<String> groupNames = new ArrayList<>();
    private boolean reverse = false;

    /**
     * Adds a state to the NFA and returns its state ID.
     *
     * @param state the state to add
     * @return the state ID (index in the states list)
     */
    public int add(State state) {
        int id = states.size();
        states.add(state);
        return id;
    }

    /**
     * Patches the state at {@code stateId} to point to {@code target}.
     *
     * <p>This is used during compilation to wire up forward references.
     * The patching behavior depends on the state type:</p>
     * <ul>
     *   <li>{@link State.CharRange} — replaces the {@code next} field</li>
     *   <li>{@link State.Look} — replaces the {@code next} field</li>
     *   <li>{@link State.Capture} — replaces the {@code next} field</li>
     *   <li>{@link State.Union} — sets the last alternate to {@code target}</li>
     *   <li>{@link State.BinaryUnion} — sets {@code alt2} to {@code target}</li>
     *   <li>Other state types — no-op</li>
     * </ul>
     *
     * @param stateId the ID of the state to patch
     * @param target  the new target state ID
     */
    public void patch(int stateId, int target) {
        State s = states.get(stateId);
        State patched = switch (s) {
            case State.CharRange r -> new State.CharRange(r.start(), r.end(), target);
            case State.Look l -> new State.Look(l.look(), target);
            case State.Capture c -> new State.Capture(target, c.groupIndex(), c.slotIndex());
            case State.Union u -> {
                int[] alts = u.alternates().clone();
                alts[alts.length - 1] = target;
                yield new State.Union(alts);
            }
            case State.BinaryUnion bu -> new State.BinaryUnion(bu.alt1(), target);
            case State.Sparse sp -> {
                // Patch last transition
                Transition[] ts = sp.transitions().clone();
                if (ts.length > 0) {
                    Transition last = ts[ts.length - 1];
                    ts[ts.length - 1] = new Transition(last.start(), last.end(), target);
                }
                yield new State.Sparse(ts);
            }
            default -> s; // Match, Fail — no-op
        };
        states.set(stateId, patched);
    }

    /**
     * Sets the start state ID for anchored searches.
     */
    public void setStartAnchored(int stateId) {
        this.startAnchored = stateId;
    }

    /**
     * Sets the start state ID for unanchored searches.
     */
    public void setStartUnanchored(int stateId) {
        this.startUnanchored = stateId;
    }

    /**
     * Sets the capture group information.
     *
     * @param groupCount       the number of capture groups (including group 0)
     * @param captureSlotCount the total number of capture slots (groupCount * 2)
     * @param groupNames       the capture group names (null for unnamed groups)
     */
    public void setGroupInfo(int groupCount, int captureSlotCount, List<String> groupNames) {
        this.groupCount = groupCount;
        this.captureSlotCount = captureSlotCount;
        this.groupNames.clear();
        this.groupNames.addAll(groupNames);
    }

    /**
     * Returns the current number of states added to the builder.
     */
    public int stateCount() {
        return states.size();
    }

    /**
     * Sets whether this NFA is being compiled for reverse search.
     *
     * @param reverse {@code true} if the NFA should be used for reverse search
     * @return this builder
     */
    public Builder reverse(boolean reverse) {
        this.reverse = reverse;
        return this;
    }

    /**
     * Builds and returns the NFA.
     *
     * @return the compiled NFA
     */
    public NFA build() {
        LookSet lookSetAny = LookSet.EMPTY;
        for (State s : states) {
            if (s instanceof State.Look look) {
                lookSetAny = lookSetAny.insert(look.look());
            }
        }
        return new NFA(
            states.toArray(new State[0]),
            startAnchored,
            startUnanchored,
            captureSlotCount,
            groupCount,
            Collections.unmodifiableList(new ArrayList<>(groupNames)),
            reverse,
            lookSetAny
        );
    }
}
