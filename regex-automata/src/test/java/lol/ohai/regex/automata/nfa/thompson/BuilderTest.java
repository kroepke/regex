package lol.ohai.regex.automata.nfa.thompson;

import lol.ohai.regex.automata.dfa.lazy.LookSet;
import lol.ohai.regex.syntax.hir.LookKind;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BuilderTest {

    @Test
    void buildSimpleNfa() {
        // Build: start -> CharRange('a') -> Match(0)
        var b = new Builder();
        int match = b.add(new State.Match(0));
        int range = b.add(new State.CharRange('a', 'a', match));
        b.setStartAnchored(range);
        b.setStartUnanchored(range);
        NFA nfa = b.build();

        assertEquals(2, nfa.stateCount());
        assertEquals(range, nfa.startAnchored());
        assertEquals(range, nfa.startUnanchored());
        assertInstanceOf(State.CharRange.class, nfa.state(range));
        assertInstanceOf(State.Match.class, nfa.state(match));

        State.CharRange br = (State.CharRange) nfa.state(range);
        assertEquals('a', br.start());
        assertEquals('a', br.end());
        assertEquals(match, br.next());
    }

    @Test
    void buildWithUnion() {
        // Build alternation: start -> Union([branch_a, branch_b]) -> Match
        var b = new Builder();
        int match = b.add(new State.Match(0));
        int branchA = b.add(new State.CharRange('a', 'a', match));
        int branchB = b.add(new State.CharRange('b', 'b', match));
        int union = b.add(new State.Union(new int[]{branchA, branchB}));
        b.setStartAnchored(union);
        b.setStartUnanchored(union);
        NFA nfa = b.build();

        assertEquals(4, nfa.stateCount());
        assertInstanceOf(State.Union.class, nfa.state(union));

        State.Union u = (State.Union) nfa.state(union);
        assertArrayEquals(new int[]{branchA, branchB}, u.alternates());
    }

    @Test
    void buildWithCapture() {
        // Build: CaptureStart -> CharRange('x') -> CaptureEnd -> Match
        var b = new Builder();
        int match = b.add(new State.Match(0));
        int capEnd = b.add(new State.Capture(match, 0, 1));
        int range = b.add(new State.CharRange('x', 'x', capEnd));
        int capStart = b.add(new State.Capture(range, 0, 0));
        b.setStartAnchored(capStart);
        b.setStartUnanchored(capStart);
        b.setGroupInfo(1, 2, Arrays.asList((String) null));
        NFA nfa = b.build();

        assertEquals(4, nfa.stateCount());
        assertEquals(1, nfa.groupCount());
        assertEquals(2, nfa.captureSlotCount());
        assertInstanceOf(State.Capture.class, nfa.state(capStart));
        assertInstanceOf(State.Capture.class, nfa.state(capEnd));
    }

    @Test
    void patchCharRange() {
        var b = new Builder();
        int placeholder = b.add(new State.CharRange('a', 'a', -1));
        int match = b.add(new State.Match(0));
        b.patch(placeholder, match);
        NFA nfa = b.build();

        State.CharRange br = (State.CharRange) nfa.state(placeholder);
        assertEquals(match, br.next());
    }

    @Test
    void patchUnion() {
        var b = new Builder();
        int branchA = b.add(new State.CharRange('a', 'a', -1));
        int union = b.add(new State.Union(new int[]{branchA, -1}));
        int match = b.add(new State.Match(0));
        b.patch(union, match);
        NFA nfa = b.build();

        State.Union u = (State.Union) nfa.state(union);
        assertEquals(branchA, u.alternates()[0]);
        assertEquals(match, u.alternates()[1]);
    }

    @Test
    void buildWithLook() {
        var b = new Builder();
        int match = b.add(new State.Match(0));
        int look = b.add(new State.Look(LookKind.START_TEXT, match));
        b.setStartAnchored(look);
        b.setStartUnanchored(look);
        NFA nfa = b.build();

        assertEquals(2, nfa.stateCount());
        State.Look l = (State.Look) nfa.state(look);
        assertEquals(LookKind.START_TEXT, l.look());
        assertEquals(match, l.next());
    }

    @Test
    void buildWithBinaryUnion() {
        var b = new Builder();
        int match = b.add(new State.Match(0));
        int branchA = b.add(new State.CharRange('a', 'a', match));
        int branchB = b.add(new State.CharRange('b', 'b', match));
        int union = b.add(new State.BinaryUnion(branchA, branchB));
        b.setStartAnchored(union);
        b.setStartUnanchored(union);
        NFA nfa = b.build();

        State.BinaryUnion bu = (State.BinaryUnion) nfa.state(union);
        assertEquals(branchA, bu.alt1());
        assertEquals(branchB, bu.alt2());
    }

    @Test void lookSetAnyTracksLookStates() {
        var builder = new Builder();
        int s0 = builder.add(new State.Look(LookKind.START_TEXT, 0));
        int s1 = builder.add(new State.Look(LookKind.WORD_BOUNDARY_ASCII, 0));
        int s2 = builder.add(new State.CharRange('a', 'z', 0));
        int s3 = builder.add(new State.Match(0));
        builder.setStartAnchored(s0);
        builder.setStartUnanchored(s0);
        builder.setGroupInfo(1, 2, List.of((String) null));
        NFA nfa = builder.build();

        assertTrue(nfa.lookSetAny().contains(LookKind.START_TEXT));
        assertTrue(nfa.lookSetAny().contains(LookKind.WORD_BOUNDARY_ASCII));
        assertFalse(nfa.lookSetAny().contains(LookKind.END_TEXT));
    }

    @Test void lookSetAnyEmptyWhenNoLookStates() {
        var builder = new Builder();
        builder.add(new State.CharRange('a', 'z', 0));
        builder.add(new State.Match(0));
        builder.setStartAnchored(0);
        builder.setStartUnanchored(0);
        builder.setGroupInfo(1, 2, List.of((String) null));
        NFA nfa = builder.build();

        assertTrue(nfa.lookSetAny().isEmpty());
    }

    @Test
    void groupNames() {
        var b = new Builder();
        b.add(new State.Match(0));
        b.setStartAnchored(0);
        b.setStartUnanchored(0);
        b.setGroupInfo(3, 6, Arrays.asList(null, "first", "second"));
        NFA nfa = b.build();

        assertEquals(3, nfa.groupCount());
        assertEquals(6, nfa.captureSlotCount());
        assertNull(nfa.groupNames().get(0));
        assertEquals("first", nfa.groupNames().get(1));
        assertEquals("second", nfa.groupNames().get(2));
    }
}
