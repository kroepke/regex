package lol.ohai.regex.automata.nfa.thompson;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Utf16SequencesTest {

    @Test
    void asciiRange() {
        var seqs = Utf16Sequences.compile(0x61, 0x7A);
        assertEquals(1, seqs.size());
        assertEquals(1, seqs.get(0).length);
        assertEquals(0x61, seqs.get(0)[0][0]);
        assertEquals(0x7A, seqs.get(0)[0][1]);
    }

    @Test
    void bmpRange() {
        var seqs = Utf16Sequences.compile(0xC0, 0xFF);
        assertEquals(1, seqs.size());
        assertEquals(1, seqs.get(0).length);
    }

    @Test
    void singleSupplementaryCodepoint() {
        // U+10000 = D800 DC00
        var seqs = Utf16Sequences.compile(0x10000, 0x10000);
        assertEquals(1, seqs.size());
        assertEquals(2, seqs.get(0).length);
        assertEquals(0xD800, seqs.get(0)[0][0]);
        assertEquals(0xD800, seqs.get(0)[0][1]);
        assertEquals(0xDC00, seqs.get(0)[1][0]);
        assertEquals(0xDC00, seqs.get(0)[1][1]);
    }

    @Test
    void supplementaryRangeSameHighSurrogate() {
        // U+10000-U+103FF share high surrogate D800
        var seqs = Utf16Sequences.compile(0x10000, 0x103FF);
        assertEquals(1, seqs.size());
        assertEquals(2, seqs.get(0).length);
        assertEquals(0xD800, seqs.get(0)[0][0]);
        assertEquals(0xD800, seqs.get(0)[0][1]);
        assertEquals(0xDC00, seqs.get(0)[1][0]);
        assertEquals(0xDFFF, seqs.get(0)[1][1]);
    }

    @Test
    void mixedBmpAndSupplementary() {
        var seqs = Utf16Sequences.compile(0xFFFE, 0x10001);
        assertTrue(seqs.size() >= 2, "Should have BMP and supplementary sequences");
    }

    @Test
    void fullBmpBelowSurrogates() {
        var seqs = Utf16Sequences.compile(0x0000, 0xD7FF);
        assertEquals(1, seqs.size());
        assertEquals(1, seqs.get(0).length);
    }

    @Test
    void skipsSurrogateRange() {
        // Range that spans surrogates: U+D000 to U+E000
        var seqs = Utf16Sequences.compile(0xD000, 0xE000);
        assertEquals(2, seqs.size());
        // First: 0xD000-0xD7FF
        assertEquals(0xD000, seqs.get(0)[0][0]);
        assertEquals(0xD7FF, seqs.get(0)[0][1]);
        // Second: 0xE000-0xE000
        assertEquals(0xE000, seqs.get(1)[0][0]);
        assertEquals(0xE000, seqs.get(1)[0][1]);
    }

    @Test
    void fullUnicodeRange() {
        // U+0000 to U+10FFFF (the "any codepoint" case)
        var seqs = Utf16Sequences.compile(0x0000, 0x10FFFF);
        // Should have: BMP below surrogates + BMP above surrogates + supplementary
        assertTrue(seqs.size() >= 2);
    }
}
