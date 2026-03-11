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
}
