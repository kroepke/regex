package lol.ohai.regex.automata.dfa.lazy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StateContentTest {

    @Test
    void equalContentIsEqual() {
        var a = new StateContent(new int[]{1, 3, 5}, true);
        var b = new StateContent(new int[]{1, 3, 5}, true);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentNfaStatesAreNotEqual() {
        var a = new StateContent(new int[]{1, 3, 5}, true);
        var b = new StateContent(new int[]{1, 3, 7}, true);
        assertNotEquals(a, b);
    }

    @Test
    void differentMatchFlagIsNotEqual() {
        var a = new StateContent(new int[]{1, 3}, true);
        var b = new StateContent(new int[]{1, 3}, false);
        assertNotEquals(a, b);
    }

    @Test
    void emptyStateContent() {
        var empty = new StateContent(new int[]{}, false);
        assertFalse(empty.isMatch());
        assertEquals(0, empty.nfaStates().length);
    }

    @Test void sameNfaStatesDifferentLookHaveAreNotEqual() {
        var a = new StateContent(new int[]{1, 2}, false, false, false, 0x01, 0x01);
        var b = new StateContent(new int[]{1, 2}, false, false, false, 0x02, 0x02);
        assertNotEquals(a, b);
    }

    @Test void sameNfaStatesDifferentIsFromWordAreNotEqual() {
        var a = new StateContent(new int[]{1, 2}, false, true, false, 0, 0);
        var b = new StateContent(new int[]{1, 2}, false, false, false, 0, 0);
        assertNotEquals(a, b);
    }

    @Test void lookHaveClearedWhenLookNeedEmpty() {
        var sc = new StateContent(new int[]{1}, false, false, false, 0xFF, 0);
        assertEquals(0, sc.lookHave(), "lookHave should be 0 when lookNeed is 0");
    }

    @Test void lookNeedNotPartOfEquality() {
        var a = new StateContent(new int[]{1, 2}, false, false, false, 0x01, 0x01);
        var b = new StateContent(new int[]{1, 2}, false, false, false, 0x01, 0xFF);
        assertEquals(a, b, "lookNeed should not affect equality");
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test void backwardCompatibleConstructor() {
        var sc = new StateContent(new int[]{1, 2}, true);
        assertTrue(sc.isMatch());
        assertFalse(sc.isFromWord());
        assertFalse(sc.isHalfCrlf());
        assertEquals(0, sc.lookHave());
        assertEquals(0, sc.lookNeed());
    }
}
