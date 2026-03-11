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
        TreeSet<Integer> boundaries = new TreeSet<>();
        boundaries.add(0);
        boundaries.add(0x10000);

        for (int i = 0; i < nfa.stateCount(); i++) {
            State state = nfa.state(i);
            switch (state) {
                case State.CharRange cr -> {
                    boundaries.add(cr.start());
                    boundaries.add(cr.end() + 1);
                }
                case State.Sparse sp -> {
                    for (Transition t : sp.transitions()) {
                        boundaries.add(t.start());
                        boundaries.add(t.end() + 1);
                    }
                }
                default -> {}
            }
        }

        // Force boundaries for characters that look-assertions depend on.
        // Without these, \n, \r, and word chars may collapse into a single
        // class whose representative doesn't reflect the actual char type.
        LookSet lookSetAny = nfa.lookSetAny();
        if (!lookSetAny.isEmpty()) {
            // Line terminators: needed for ^, $, START_LINE, END_LINE, etc.
            boundaries.add((int) '\n');
            boundaries.add((int) '\n' + 1);
            boundaries.add((int) '\r');
            boundaries.add((int) '\r' + 1);

            if (lookSetAny.containsWord()) {
                // Word char boundaries: [0-9A-Za-z_]
                // Force boundaries at the edges of each word-char range
                boundaries.add((int) '0');
                boundaries.add((int) '9' + 1);
                boundaries.add((int) 'A');
                boundaries.add((int) 'Z' + 1);
                boundaries.add((int) '_');
                boundaries.add((int) '_' + 1);
                boundaries.add((int) 'a');
                boundaries.add((int) 'z' + 1);
            }
        }

        int[] sortedBounds = boundaries.stream().mapToInt(Integer::intValue).toArray();
        int classCount = sortedBounds.length - 1;

        // Cap at 256 — byte storage limit
        if (classCount > 256) {
            classCount = 256;
        }

        byte[] flatMap = new byte[65536];
        for (int cls = 0; cls < sortedBounds.length - 1; cls++) {
            int from = sortedBounds[cls];
            int to = Math.min(sortedBounds[cls + 1], 65536);
            int mappedClass = Math.min(cls, classCount - 1);
            for (int c = from; c < to; c++) {
                flatMap[c] = (byte) mappedClass;
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

        boolean[] wordClass = new boolean[classCount];
        boolean[] lineLF = new boolean[classCount];
        boolean[] lineCR = new boolean[classCount];
        for (int cls = 0; cls < classCount && cls < sortedBounds.length - 1; cls++) {
            int representative = sortedBounds[cls]; // first char in this class
            if (representative < 65536) {
                char c = (char) representative;
                wordClass[cls] = isWordChar(c);
                lineLF[cls] = (c == '\n');
                lineCR[cls] = (c == '\r');
            }
        }

        return new CharClasses(uniqueRows.toArray(byte[][]::new), highIndex, classCount,
                wordClass, lineLF, lineCR);
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
