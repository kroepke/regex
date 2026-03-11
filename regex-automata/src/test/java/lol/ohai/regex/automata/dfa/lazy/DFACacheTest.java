package lol.ohai.regex.automata.dfa.lazy;

import lol.ohai.regex.automata.dfa.CharClasses;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DFACacheTest {
    @Test
    void sentinelStatesPreAllocated() {
        var cc = CharClasses.identity();
        var cache = new DFACache(cc, 1024);
        assertEquals(3, cache.stateCount());
    }

    @Test
    void unknownStateTransitionsAreAllUnknown() {
        var cc = CharClasses.identity();
        var cache = new DFACache(cc, 1024);
        int stride = cc.stride();
        for (int c = 0; c < stride; c++) {
            assertEquals(DFACache.UNKNOWN, cache.nextState(DFACache.UNKNOWN, c));
        }
    }

    @Test
    void deadStateTransitionsAreAllDead() {
        var cc = CharClasses.identity();
        var cache = new DFACache(cc, 1024);
        int stride = cc.stride();
        int dead = DFACache.dead(stride);
        for (int c = 0; c < stride; c++) {
            assertEquals(dead, cache.nextState(dead, c));
        }
    }

    @Test
    void allocateStateReturnsPreMultipliedId() {
        var cc = CharClasses.identity();
        var cache = new DFACache(cc, 1024);
        int stride = cc.stride();
        var content = new StateContent(new int[]{1, 2}, false);
        int sid = cache.allocateState(content);
        assertEquals(stride * 3, sid);
    }

    @Test
    void allocateMatchStateHasHighBitSet() {
        var cc = CharClasses.identity();
        var cache = new DFACache(cc, 1024);
        var content = new StateContent(new int[]{1}, true);
        int sid = cache.allocateState(content);
        assertTrue(sid < 0, "match state ID should have high bit set (negative)");
        int realId = sid & 0x7FFF_FFFF;
        assertEquals(cc.stride() * 3, realId);
    }

    @Test
    void deduplicatesIdenticalStates() {
        var cc = CharClasses.identity();
        var cache = new DFACache(cc, 1024);
        var content = new StateContent(new int[]{1, 2}, false);
        int sid1 = cache.allocateState(content);
        var same = new StateContent(new int[]{1, 2}, false);
        int sid2 = cache.allocateState(same);
        assertEquals(sid1, sid2);
    }

    @Test
    void clearResetsStatesButKeepsSentinels() {
        var cc = CharClasses.identity();
        var cache = new DFACache(cc, 1024);
        cache.allocateState(new StateContent(new int[]{1}, false));
        assertEquals(4, cache.stateCount());
        cache.clear(null);
        assertEquals(3, cache.stateCount());
    }

    @Test
    void clearIncrementsCounter() {
        var cc = CharClasses.identity();
        var cache = new DFACache(cc, 1024);
        assertEquals(0, cache.clearCount());
        cache.clear(null);
        assertEquals(1, cache.clearCount());
        cache.clear(null);
        assertEquals(2, cache.clearCount());
    }

    @Test
    void clearWithPreservedState() {
        var cc = CharClasses.identity();
        var cache = new DFACache(cc, 1024);
        var content = new StateContent(new int[]{1, 2}, false);
        cache.allocateState(content);
        cache.clear(content);
        assertEquals(4, cache.stateCount());
    }

    @Test
    void getStateRoundTrips() {
        var cc = CharClasses.identity();
        var cache = new DFACache(cc, 1024);
        var content = new StateContent(new int[]{3, 7}, true);
        int sid = cache.allocateState(content);
        int rawId = sid & 0x7FFF_FFFF;
        assertEquals(content, cache.getState(rawId));
    }

    @Test
    void isFullTriggersAtCapacity() {
        var cc = CharClasses.identity();
        var cache = new DFACache(cc, 64);
        for (int i = 0; i < 5; i++) {
            assertFalse(cache.isFull());
            cache.allocateState(new StateContent(new int[]{i}, false));
        }
        assertTrue(cache.isFull());
    }
}
