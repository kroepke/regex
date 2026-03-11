package lol.ohai.regex.automata.dfa;

import lol.ohai.regex.automata.nfa.thompson.NFA;
import lol.ohai.regex.automata.nfa.thompson.State;
import lol.ohai.regex.automata.nfa.thompson.Transition;
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

        return new CharClasses(uniqueRows.toArray(byte[][]::new), highIndex, classCount);
    }

    private record ByteArrayKey(byte[] data) {
        @Override public boolean equals(Object o) {
            return o instanceof ByteArrayKey k && Arrays.equals(data, k.data);
        }
        @Override public int hashCode() { return Arrays.hashCode(data); }
    }
}
