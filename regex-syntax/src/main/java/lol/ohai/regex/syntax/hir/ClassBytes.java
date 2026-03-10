package lol.ohai.regex.syntax.hir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A set of non-overlapping, sorted byte ranges (0-255).
 *
 * <p>Used when Unicode mode is disabled and the engine operates on raw bytes.
 */
public final class ClassBytes {

    private final List<ClassBytesRange> ranges;

    /**
     * A single inclusive range of byte values.
     */
    public record ClassBytesRange(int start, int end) implements Comparable<ClassBytesRange> {
        public ClassBytesRange {
            if (start < 0 || start > 255 || end < 0 || end > 255) {
                throw new IllegalArgumentException("Byte range values must be 0-255");
            }
            if (start > end) {
                int tmp = start;
                start = end;
                end = tmp;
            }
        }

        @Override
        public int compareTo(ClassBytesRange other) {
            int c = Integer.compare(this.start, other.start);
            return c != 0 ? c : Integer.compare(this.end, other.end);
        }

        boolean isContiguous(ClassBytesRange other) {
            return Math.max(this.start, other.start) <= Math.min(this.end, other.end) + 1;
        }

        boolean isIntersectionEmpty(ClassBytesRange other) {
            return Math.max(this.start, other.start) > Math.min(this.end, other.end);
        }

        boolean isSubset(ClassBytesRange other) {
            return other.start <= this.start && this.start <= other.end
                    && other.start <= this.end && this.end <= other.end;
        }

        ClassBytesRange union(ClassBytesRange other) {
            if (!isContiguous(other)) return null;
            return new ClassBytesRange(
                    Math.min(this.start, other.start),
                    Math.max(this.end, other.end)
            );
        }

        ClassBytesRange intersect(ClassBytesRange other) {
            int lo = Math.max(this.start, other.start);
            int hi = Math.min(this.end, other.end);
            return lo <= hi ? new ClassBytesRange(lo, hi) : null;
        }

        ClassBytesRange[] difference(ClassBytesRange other) {
            if (this.isSubset(other)) {
                return new ClassBytesRange[]{null, null};
            }
            if (this.isIntersectionEmpty(other)) {
                return new ClassBytesRange[]{this, null};
            }
            boolean addLower = other.start > this.start;
            boolean addUpper = other.end < this.end;
            ClassBytesRange r0 = null, r1 = null;
            if (addLower) {
                r0 = new ClassBytesRange(this.start, other.start - 1);
            }
            if (addUpper) {
                ClassBytesRange upper = new ClassBytesRange(other.end + 1, this.end);
                if (r0 == null) {
                    r0 = upper;
                } else {
                    r1 = upper;
                }
            }
            return new ClassBytesRange[]{r0, r1};
        }
    }

    // --- Constructors ---

    public ClassBytes() {
        this.ranges = new ArrayList<>();
    }

    public ClassBytes(List<ClassBytesRange> ranges) {
        this.ranges = new ArrayList<>(ranges);
        canonicalize();
    }

    public static ClassBytes of(ClassBytesRange... ranges) {
        ClassBytes cls = new ClassBytes();
        Collections.addAll(cls.ranges, ranges);
        cls.canonicalize();
        return cls;
    }

    // --- Accessors ---

    public List<ClassBytesRange> ranges() {
        return Collections.unmodifiableList(ranges);
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    // --- Mutating operations ---

    public void push(ClassBytesRange range) {
        ranges.add(range);
        canonicalize();
    }

    public void union(ClassBytes other) {
        if (other.ranges.isEmpty()) return;
        ranges.addAll(other.ranges);
        canonicalize();
    }

    public void intersect(ClassBytes other) {
        if (ranges.isEmpty()) return;
        if (other.ranges.isEmpty()) {
            ranges.clear();
            return;
        }
        int drainEnd = ranges.size();
        int a = 0, b = 0;
        while (a < drainEnd && b < other.ranges.size()) {
            ClassBytesRange ab = ranges.get(a).intersect(other.ranges.get(b));
            if (ab != null) ranges.add(ab);
            if (ranges.get(a).end() < other.ranges.get(b).end()) {
                a++;
            } else {
                b++;
            }
        }
        ranges.subList(0, drainEnd).clear();
    }

    public void difference(ClassBytes other) {
        if (ranges.isEmpty() || other.ranges.isEmpty()) return;
        int drainEnd = ranges.size();
        int a = 0, b = 0;
        outer:
        while (a < drainEnd && b < other.ranges.size()) {
            if (other.ranges.get(b).end() < ranges.get(a).start()) {
                b++;
                continue;
            }
            if (ranges.get(a).end() < other.ranges.get(b).start()) {
                ranges.add(ranges.get(a));
                a++;
                continue;
            }
            ClassBytesRange range = ranges.get(a);
            while (b < other.ranges.size() && !range.isIntersectionEmpty(other.ranges.get(b))) {
                ClassBytesRange oldRange = range;
                ClassBytesRange[] diff = range.difference(other.ranges.get(b));
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
                if (other.ranges.get(b).end() > oldRange.end()) break;
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

    public void symmetricDifference(ClassBytes other) {
        ClassBytes intersection = copy();
        intersection.intersect(other);
        union(other);
        difference(intersection);
    }

    public void negate() {
        if (ranges.isEmpty()) {
            ranges.add(new ClassBytesRange(0, 255));
            return;
        }
        int drainEnd = ranges.size();
        if (ranges.getFirst().start() > 0) {
            ranges.add(new ClassBytesRange(0, ranges.getFirst().start() - 1));
        }
        for (int i = 1; i < drainEnd; i++) {
            ranges.add(new ClassBytesRange(
                    ranges.get(i - 1).end() + 1,
                    ranges.get(i).start() - 1));
        }
        if (ranges.get(drainEnd - 1).end() < 255) {
            ranges.add(new ClassBytesRange(ranges.get(drainEnd - 1).end() + 1, 255));
        }
        ranges.subList(0, drainEnd).clear();
    }

    public void caseFoldSimple() {
        int len = ranges.size();
        for (int i = 0; i < len; i++) {
            ClassBytesRange r = ranges.get(i);
            if (r.start() <= 'z' && r.end() >= 'a') {
                int lo = Math.max(r.start(), 'a');
                int hi = Math.min(r.end(), 'z');
                ranges.add(new ClassBytesRange(lo - 32, hi - 32));
            }
            if (r.start() <= 'Z' && r.end() >= 'A') {
                int lo = Math.max(r.start(), 'A');
                int hi = Math.min(r.end(), 'Z');
                ranges.add(new ClassBytesRange(lo + 32, hi + 32));
            }
        }
        canonicalize();
    }

    public ClassBytes copy() {
        return new ClassBytes(new ArrayList<>(ranges));
    }

    // --- Canonicalization ---

    private void canonicalize() {
        if (isCanonical()) return;
        Collections.sort(ranges);
        if (ranges.isEmpty()) return;
        int drainEnd = ranges.size();
        for (int i = 0; i < drainEnd; i++) {
            if (ranges.size() > drainEnd) {
                ClassBytesRange last = ranges.getLast();
                ClassBytesRange merged = last.union(ranges.get(i));
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
            ClassBytesRange a = ranges.get(i);
            ClassBytesRange b = ranges.get(i + 1);
            if (a.compareTo(b) >= 0) return false;
            if (a.isContiguous(b)) return false;
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ClassBytes cb && ranges.equals(cb.ranges);
    }

    @Override
    public int hashCode() {
        return ranges.hashCode();
    }

    @Override
    public String toString() {
        return "ClassBytes" + ranges;
    }
}
