package lol.ohai.regex.syntax.hir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A set of non-overlapping, sorted Unicode codepoint ranges.
 *
 * <p>This is the primary representation for character classes in the HIR.
 * Ranges are maintained in canonical form: sorted by start, non-overlapping,
 * and non-adjacent.
 *
 * <p>The interval operations (union, intersect, difference, negate, etc.) are
 * ported from the Rust regex crate's {@code interval.rs}.
 */
public final class ClassUnicode {

    private final List<ClassUnicodeRange> ranges;

    /**
     * A single inclusive range of Unicode codepoints.
     */
    public record ClassUnicodeRange(int start, int end) implements Comparable<ClassUnicodeRange> {
        public ClassUnicodeRange {
            if (start > end) {
                int tmp = start;
                start = end;
                end = tmp;
            }
        }

        @Override
        public int compareTo(ClassUnicodeRange other) {
            int c = Integer.compare(this.start, other.start);
            return c != 0 ? c : Integer.compare(this.end, other.end);
        }

        /**
         * Returns true if this range and the other are contiguous (overlapping or adjacent).
         */
        boolean isContiguous(ClassUnicodeRange other) {
            return Math.max(this.start, other.start)
                    <= Math.min((long) this.end, (long) other.end) + 1;
        }

        /**
         * Returns true if the intersection of this range and the other is empty.
         */
        boolean isIntersectionEmpty(ClassUnicodeRange other) {
            return Math.max(this.start, other.start) > Math.min(this.end, other.end);
        }

        /**
         * Returns true if this range is a subset of the other range.
         */
        boolean isSubset(ClassUnicodeRange other) {
            return other.start <= this.start && this.start <= other.end
                    && other.start <= this.end && this.end <= other.end;
        }

        /**
         * Union this range with the other if they are contiguous.
         * Returns null if they are not contiguous.
         */
        ClassUnicodeRange union(ClassUnicodeRange other) {
            if (!isContiguous(other)) {
                return null;
            }
            return new ClassUnicodeRange(
                    Math.min(this.start, other.start),
                    Math.max(this.end, other.end)
            );
        }

        /**
         * Intersect this range with the other.
         * Returns null if the intersection is empty.
         */
        ClassUnicodeRange intersect(ClassUnicodeRange other) {
            int lo = Math.max(this.start, other.start);
            int hi = Math.min(this.end, other.end);
            return lo <= hi ? new ClassUnicodeRange(lo, hi) : null;
        }

        /**
         * Subtract the other range from this range.
         * Returns up to two result ranges (left remainder, right remainder).
         * Either or both may be null.
         */
        ClassUnicodeRange[] difference(ClassUnicodeRange other) {
            if (this.isSubset(other)) {
                return new ClassUnicodeRange[]{null, null};
            }
            if (this.isIntersectionEmpty(other)) {
                return new ClassUnicodeRange[]{this, null};
            }
            boolean addLower = other.start > this.start;
            boolean addUpper = other.end < this.end;
            ClassUnicodeRange r0 = null, r1 = null;
            if (addLower) {
                r0 = new ClassUnicodeRange(this.start, decrementCodepoint(other.start));
            }
            if (addUpper) {
                ClassUnicodeRange upper = new ClassUnicodeRange(incrementCodepoint(other.end), this.end);
                if (r0 == null) {
                    r0 = upper;
                } else {
                    r1 = upper;
                }
            }
            return new ClassUnicodeRange[]{r0, r1};
        }
    }

    // --- Static helpers ---

    static final int MIN_CODEPOINT = 0;
    static final int MAX_CODEPOINT = 0x10FFFF;

    static int incrementCodepoint(int cp) {
        if (cp == 0xD7FF) return 0xE000;
        return cp + 1;
    }

    static int decrementCodepoint(int cp) {
        if (cp == 0xE000) return 0xD7FF;
        return cp - 1;
    }

    // --- Constructors ---

    public ClassUnicode() {
        this.ranges = new ArrayList<>();
    }

    public ClassUnicode(List<ClassUnicodeRange> ranges) {
        this.ranges = new ArrayList<>(ranges);
        canonicalize();
    }

    /**
     * Create a ClassUnicode from a varargs of ranges.
     */
    public static ClassUnicode of(ClassUnicodeRange... ranges) {
        ClassUnicode cls = new ClassUnicode();
        Collections.addAll(cls.ranges, ranges);
        cls.canonicalize();
        return cls;
    }

    // --- Accessors ---

    /**
     * Returns an unmodifiable view of the ranges.
     */
    public List<ClassUnicodeRange> ranges() {
        return Collections.unmodifiableList(ranges);
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    // --- Mutating operations ---

    /**
     * Add a single range to this set.
     */
    public void push(ClassUnicodeRange range) {
        ranges.add(range);
        canonicalize();
    }

    /**
     * Union this set with the given set, in place.
     */
    public void union(ClassUnicode other) {
        if (other.ranges.isEmpty()) return;
        ranges.addAll(other.ranges);
        canonicalize();
    }

    /**
     * Intersect this set with the given set, in place.
     */
    public void intersect(ClassUnicode other) {
        if (ranges.isEmpty()) return;
        if (other.ranges.isEmpty()) {
            ranges.clear();
            return;
        }

        int drainEnd = ranges.size();
        int a = 0, b = 0;
        int aEnd = drainEnd, bEnd = other.ranges.size();

        while (a < aEnd && b < bEnd) {
            ClassUnicodeRange ab = ranges.get(a).intersect(other.ranges.get(b));
            if (ab != null) {
                ranges.add(ab);
            }
            if (ranges.get(a).end() < other.ranges.get(b).end()) {
                a++;
            } else {
                b++;
            }
        }
        ranges.subList(0, drainEnd).clear();
    }

    /**
     * Subtract the given set from this set, in place.
     */
    public void difference(ClassUnicode other) {
        if (ranges.isEmpty() || other.ranges.isEmpty()) return;

        int drainEnd = ranges.size();
        int a = 0, b = 0;
        int bEnd = other.ranges.size();

        outer:
        while (a < drainEnd && b < bEnd) {
            if (other.ranges.get(b).end() < ranges.get(a).start()) {
                b++;
                continue;
            }
            if (ranges.get(a).end() < other.ranges.get(b).start()) {
                ranges.add(ranges.get(a));
                a++;
                continue;
            }

            ClassUnicodeRange range = ranges.get(a);
            while (b < bEnd && !range.isIntersectionEmpty(other.ranges.get(b))) {
                ClassUnicodeRange oldRange = range;
                ClassUnicodeRange[] diff = range.difference(other.ranges.get(b));
                if (diff[0] == null && diff[1] == null) {
                    a++;
                    continue outer;
                }
                if (diff[1] == null) {
                    range = diff[0];
                } else {
                    ranges.add(diff[0]);
                    range = diff[1];
                }
                if (other.ranges.get(b).end() > oldRange.end()) {
                    break;
                }
                b++;
            }
            ranges.add(range);
            a++;
        }
        while (a < drainEnd) {
            ranges.add(ranges.get(a));
            a++;
        }
        ranges.subList(0, drainEnd).clear();
    }

    /**
     * Compute the symmetric difference with the given set, in place.
     */
    public void symmetricDifference(ClassUnicode other) {
        ClassUnicode intersection = copy();
        intersection.intersect(other);
        union(other);
        difference(intersection);
    }

    /**
     * Negate this set: all codepoints in the set are removed, and all codepoints
     * not in the set are added.
     */
    public void negate() {
        if (ranges.isEmpty()) {
            ranges.add(new ClassUnicodeRange(MIN_CODEPOINT, MAX_CODEPOINT));
            return;
        }

        int drainEnd = ranges.size();

        if (ranges.getFirst().start() > MIN_CODEPOINT) {
            int upper = decrementCodepoint(ranges.getFirst().start());
            ranges.add(new ClassUnicodeRange(MIN_CODEPOINT, upper));
        }
        for (int i = 1; i < drainEnd; i++) {
            int lower = incrementCodepoint(ranges.get(i - 1).end());
            int upper = decrementCodepoint(ranges.get(i).start());
            ranges.add(new ClassUnicodeRange(lower, upper));
        }
        if (ranges.get(drainEnd - 1).end() < MAX_CODEPOINT) {
            int lower = incrementCodepoint(ranges.get(drainEnd - 1).end());
            ranges.add(new ClassUnicodeRange(lower, MAX_CODEPOINT));
        }
        ranges.subList(0, drainEnd).clear();
    }

    /**
     * Apply simple ASCII case folding: for each range that overlaps with
     * {@code A-Z} or {@code a-z}, add the corresponding opposite-case range.
     *
     * <p>Full Unicode case folding will be added in a later task.
     */
    public void caseFoldSimple() {
        int len = ranges.size();
        for (int i = 0; i < len; i++) {
            ClassUnicodeRange r = ranges.get(i);
            // Check overlap with a-z
            if (r.start() <= 'z' && r.end() >= 'a') {
                int lo = Math.max(r.start(), 'a');
                int hi = Math.min(r.end(), 'z');
                ranges.add(new ClassUnicodeRange(lo - 32, hi - 32));
            }
            // Check overlap with A-Z
            if (r.start() <= 'Z' && r.end() >= 'A') {
                int lo = Math.max(r.start(), 'A');
                int hi = Math.min(r.end(), 'Z');
                ranges.add(new ClassUnicodeRange(lo + 32, hi + 32));
            }
        }
        canonicalize();
    }

    /**
     * Create an independent copy of this set.
     */
    public ClassUnicode copy() {
        return new ClassUnicode(new ArrayList<>(ranges));
    }

    // --- Canonicalization ---

    private void canonicalize() {
        if (isCanonical()) return;
        Collections.sort(ranges);
        if (ranges.isEmpty()) return;

        int drainEnd = ranges.size();
        for (int i = 0; i < drainEnd; i++) {
            if (ranges.size() > drainEnd) {
                // Try to merge with the last added range
                ClassUnicodeRange last = ranges.getLast();
                ClassUnicodeRange merged = last.union(ranges.get(i));
                if (merged != null) {
                    ranges.set(ranges.size() - 1, merged);
                    continue;
                }
            }
            ranges.add(ranges.get(i));
        }
        ranges.subList(0, drainEnd).clear();
    }

    private boolean isCanonical() {
        for (int i = 0; i + 1 < ranges.size(); i++) {
            ClassUnicodeRange a = ranges.get(i);
            ClassUnicodeRange b = ranges.get(i + 1);
            if (a.compareTo(b) >= 0) return false;
            if (a.isContiguous(b)) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClassUnicode cu && ranges.equals(cu.ranges);
    }

    @Override
    public int hashCode() {
        return ranges.hashCode();
    }

    @Override
    public String toString() {
        return "ClassUnicode" + ranges;
    }
}
