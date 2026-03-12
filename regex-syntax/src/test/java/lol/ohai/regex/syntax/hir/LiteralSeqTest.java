package lol.ohai.regex.syntax.hir;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LiteralSeqTest {

    @Test
    void noneDoesNotCoverEntirePattern() {
        var none = new LiteralSeq.None();
        assertFalse(none.coversEntirePattern());
    }

    @Test
    void singleCoversEntirePatternWhenFlagged() {
        var single = new LiteralSeq.Single("hello".toCharArray(), true, true);
        assertTrue(single.coversEntirePattern());
    }

    @Test
    void singleDoesNotCoverEntirePatternWhenNotFlagged() {
        var single = new LiteralSeq.Single("hello".toCharArray(), true, false);
        assertFalse(single.coversEntirePattern());
    }

    @Test
    void alternationCoversEntirePattern() {
        var alt = new LiteralSeq.Alternation(
                List.of("cat".toCharArray(), "dog".toCharArray()), true, true);
        assertTrue(alt.coversEntirePattern());
    }

    @Test
    void alternationDoesNotCoverEntirePattern() {
        var alt = new LiteralSeq.Alternation(
                List.of("cat".toCharArray(), "dog".toCharArray()), true, false);
        assertFalse(alt.coversEntirePattern());
    }
}
