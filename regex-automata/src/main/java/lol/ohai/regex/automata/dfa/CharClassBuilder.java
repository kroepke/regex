package lol.ohai.regex.automata.dfa;

import lol.ohai.regex.automata.dfa.lazy.LookSet;
import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.State;
import lol.ohai.regex.automata.nfa.thompson.Transition;
import lol.ohai.regex.syntax.hir.LookKind;
import java.util.*;

public final class CharClassBuilder {
    private CharClassBuilder() {}

    public static CharClasses build(NFA nfa) {
        return build(nfa, false);
    }

    /**
     * Build character equivalence classes with region merging when possible.
     *
     * <p>When {@code quitNonAscii} is true (for Unicode word boundary patterns),
     * delegates to {@link #buildUnmerged} which handles the quit-char path
     * correctly. The quit path already keeps class counts under 256 by
     * collapsing non-ASCII boundaries.</p>
     *
     * <p>When {@code quitNonAscii} is false, computes behavior signatures
     * per boundary region and merges regions with identical NFA transition
     * targets. This reduces ~1,400 classes for Unicode {@code \w+} to ~55.</p>
     */
    public static CharClasses build(NFA nfa, boolean quitNonAscii) {
        // Quit path or word boundary: delegate to existing logic.
        // Word boundary patterns need precise word-char/non-word-char class
        // separation that the merge would collapse.
        if (quitNonAscii || nfa.lookSetAny().containsWord()) {
            return buildUnmerged(nfa, quitNonAscii);
        }

        int[] sortedBounds = collectBoundaries(nfa, false);
        int regionCount = sortedBounds.length - 1;

        // Merge: compute behavior signature per region, group by signature.
        HashMap<BitSet, Integer> signatureToClass = new HashMap<>();
        int[] regionClassMap = new int[regionCount];
        int nextClassId = 0;

        for (int r = 0; r < regionCount; r++) {
            BitSet sig = computeSignature(nfa, sortedBounds[r]);
            Integer existingClass = signatureToClass.get(sig);
            if (existingClass != null) {
                regionClassMap[r] = existingClass;
            } else {
                regionClassMap[r] = nextClassId;
                signatureToClass.put(sig, nextClassId);
                nextClassId++;
            }
        }

        int classCount = nextClassId;
        if (classCount > 256) {
            // Safety net: fall back to quit-on-non-ASCII
            return buildUnmerged(nfa, false);
        }

        byte[] flatMap = new byte[65536];
        for (int r = 0; r < regionCount; r++) {
            int from = sortedBounds[r];
            int to = Math.min(sortedBounds[r + 1], 65536);
            byte cls = (byte) regionClassMap[r];
            for (int c = from; c < to; c++) {
                flatMap[c] = cls;
            }
        }

        Map<ByteArrayKey, Integer> rowIndex = new HashMap<>();
        List<byte[]> uniqueRows = new ArrayList<>();
        int[] highIndex = new int[256];
        for (int hi = 0; hi < 256; hi++) {
            byte[] row = new byte[256];
            System.arraycopy(flatMap, hi * 256, row, 0, 256);
            ByteArrayKey key = new ByteArrayKey(row);
            Integer existing = rowIndex.get(key);
            if (existing != null) {
                highIndex[hi] = existing;
            } else {
                int idx = uniqueRows.size();
                uniqueRows.add(row);
                rowIndex.put(key, idx);
                highIndex[hi] = idx;
            }
        }

        // Metadata: first-seen representative per merged class.
        int[] classRep = new int[classCount];
        Arrays.fill(classRep, -1);
        for (int r = 0; r < regionCount; r++) {
            int cls = regionClassMap[r];
            if (classRep[cls] < 0) {
                classRep[cls] = sortedBounds[r];
            }
        }

        byte[] classFlags = new byte[classCount];
        for (int cls = 0; cls < classCount; cls++) {
            int rep = classRep[cls];
            if (rep >= 0 && rep < 65536) {
                char c = (char) rep;
                if (isWordChar(c))  classFlags[cls] |= CharClasses.FLAG_WORD;
                if (c == '\n')      classFlags[cls] |= CharClasses.FLAG_LF;
                if (c == '\r')      classFlags[cls] |= CharClasses.FLAG_CR;
            }
        }

        return new CharClasses(uniqueRows.toArray(byte[][]::new), highIndex, classCount, classFlags);
    }

    /**
     * Compute the behavior signature for a representative character: the set
     * of NFA transition targets that match it, plus isolation bits for \n/\r.
     */
    private static BitSet computeSignature(NFA nfa, int representative) {
        int lookBase = nfa.stateCount();
        BitSet sig = new BitSet(lookBase + 2);
        for (int i = 0; i < nfa.stateCount(); i++) {
            State state = nfa.state(i);
            switch (state) {
                case State.CharRange cr -> {
                    if (representative >= cr.start() && representative <= cr.end()) {
                        sig.set(resolveTarget(nfa, cr.next()));
                    }
                }
                case State.Sparse sp -> {
                    for (Transition t : sp.transitions()) {
                        if (representative >= t.start() && representative <= t.end()) {
                            sig.set(resolveTarget(nfa, t.next()));
                            break;
                        }
                    }
                }
                default -> {}
            }
        }
        if (representative == '\n') sig.set(lookBase);
        if (representative == '\r') sig.set(lookBase + 1);
        return sig;
    }

    /**
     * Follow a chain of surrogate-pair CharRange states to the ultimate target.
     * Only resolves through CharRange states whose range falls in the surrogate
     * zone (0xD800-0xDFFF). This collapses intermediate surrogate states
     * without affecting normal char sequences (like 'c' → 'a' → 't' in "cat").
     */
    private static int resolveTarget(NFA nfa, int stateId) {
        int limit = 4; // surrogate pairs are at most 2 chars deep
        while (limit-- > 0 && stateId < nfa.stateCount()) {
            State s = nfa.state(stateId);
            if (s instanceof State.CharRange cr
                    && cr.start() >= 0xD800 && cr.end() <= 0xDFFF) {
                stateId = cr.next();
            } else {
                break;
            }
        }
        return stateId;
    }

    /**
     * Build character equivalence classes for the DFA from the NFA's character
     * ranges. Returns {@code null} if the pattern requires more than 256
     * equivalence classes (the byte-based class ID limit).
     *
     * <p>When {@code quitNonAscii} is false but the class count exceeds 256
     * (common for Unicode character classes like {@code \w}), this method
     * automatically retries with quit-on-non-ASCII enabled. The DFA will then
     * handle ASCII portions and give up on non-ASCII characters, falling back
     * to PikeVM. This mirrors the upstream Rust crate's byte-based alphabet
     * where the 256-class limit is inherent
     * (upstream/regex/regex-automata/src/util/alphabet.rs).</p>
     */
    // Renamed from build(NFA, boolean). Kept for A/B benchmarking.
    public static CharClasses buildUnmerged(NFA nfa, boolean quitNonAscii) {
        int[] sortedBounds = collectBoundaries(nfa, quitNonAscii);

        if (sortedBounds.length - 1 > 256 && !quitNonAscii) {
            // Too many classes — retry with quit-on-non-ASCII to collapse
            // all non-ASCII characters into quit classes.
            sortedBounds = collectBoundaries(nfa, true);
            quitNonAscii = true;
        }
        int classCount = sortedBounds.length - 1;

        if (classCount > 256) {
            return null;
        }

        byte[] flatMap = new byte[65536];
        for (int cls = 0; cls < sortedBounds.length - 1; cls++) {
            int from = sortedBounds[cls];
            int to = Math.min(sortedBounds[cls + 1], 65536);
            for (int c = from; c < to; c++) {
                flatMap[c] = (byte) cls;
            }
        }

        Map<ByteArrayKey, Integer> rowIndex = new HashMap<>();
        List<byte[]> uniqueRows = new ArrayList<>();
        int[] highIndex = new int[256];

        for (int hi = 0; hi < 256; hi++) {
            byte[] row = new byte[256];
            System.arraycopy(flatMap, hi * 256, row, 0, 256);
            ByteArrayKey key = new ByteArrayKey(row);
            Integer existing = rowIndex.get(key);
            if (existing != null) {
                highIndex[hi] = existing;
            } else {
                int idx = uniqueRows.size();
                uniqueRows.add(row);
                rowIndex.put(key, idx);
                highIndex[hi] = idx;
            }
        }

        byte[] classFlags = new byte[classCount];
        for (int cls = 0; cls < classCount && cls < sortedBounds.length - 1; cls++) {
            int representative = sortedBounds[cls];
            if (representative < 65536) {
                char c = (char) representative;
                if (isWordChar(c))  classFlags[cls] |= CharClasses.FLAG_WORD;
                if (c == '\n')      classFlags[cls] |= CharClasses.FLAG_LF;
                if (c == '\r')      classFlags[cls] |= CharClasses.FLAG_CR;
            }
        }
        if (quitNonAscii) {
            for (int cls = 0; cls < classCount && cls < sortedBounds.length - 1; cls++) {
                int representative = sortedBounds[cls];
                if (representative >= 128 && representative < 65536) {
                    classFlags[cls] |= CharClasses.FLAG_QUIT;
                }
            }
        }

        return new CharClasses(uniqueRows.toArray(byte[][]::new), highIndex, classCount, classFlags);
    }

    /**
     * Collect character class boundaries from the NFA's character ranges.
     * When {@code quitNonAscii} is true, boundaries above 128 are collapsed
     * (all non-ASCII characters become a single quit class).
     *
     * <p>Returns a sorted, deduplicated {@code int[]} of boundary values.</p>
     */
    private static int[] collectBoundaries(NFA nfa, boolean quitNonAscii) {
        int capacity = 64;
        int[] buf = new int[capacity];
        int size = 0;

        // Helper: append a value to buf, growing if needed.
        // (Inline growth logic below to avoid lambda overhead.)

        buf[size++] = 0;
        buf[size++] = 0x10000;
        if (quitNonAscii) {
            buf[size++] = 128;
        }

        for (int i = 0; i < nfa.stateCount(); i++) {
            State state = nfa.state(i);
            switch (state) {
                case State.CharRange cr -> {
                    if (size + 2 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                    buf[size++] = cr.start();
                    buf[size++] = cr.end() + 1;
                }
                case State.Sparse sp -> {
                    for (Transition t : sp.transitions()) {
                        if (size + 2 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                        buf[size++] = t.start();
                        buf[size++] = t.end() + 1;
                    }
                }
                default -> {}
            }
        }

        // Force boundaries for characters that look-assertions depend on.
        LookSet lookSetAny = nfa.lookSetAny();
        if (!lookSetAny.isEmpty()) {
            if (size + 4 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            buf[size++] = (int) '\n';
            buf[size++] = (int) '\n' + 1;
            buf[size++] = (int) '\r';
            buf[size++] = (int) '\r' + 1;

            if (lookSetAny.containsWord()) {
                if (size + 8 > buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
                buf[size++] = (int) '0';
                buf[size++] = (int) '9' + 1;
                buf[size++] = (int) 'A';
                buf[size++] = (int) 'Z' + 1;
                buf[size++] = (int) '_';
                buf[size++] = (int) '_' + 1;
                buf[size++] = (int) 'a';
                buf[size++] = (int) 'z' + 1;
            }
        }

        Arrays.sort(buf, 0, size);

        // Deduplicate in-place; also filter out [129, 0x10000) if quitNonAscii.
        int out = 0;
        int prev = -1;
        for (int i = 0; i < size; i++) {
            int v = buf[i];
            if (v == prev) continue;
            if (quitNonAscii && v >= 129 && v < 0x10000) continue;
            buf[out++] = v;
            prev = v;
        }

        return Arrays.copyOf(buf, out);
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_';
    }

    private record ByteArrayKey(byte[] data) {
        @Override public boolean equals(Object o) {
            return o instanceof ByteArrayKey k && Arrays.equals(data, k.data);
        }
        @Override public int hashCode() { return Arrays.hashCode(data); }
    }
}
