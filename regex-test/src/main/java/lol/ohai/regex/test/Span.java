package lol.ohai.regex.test;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * A contiguous range of bytes [start, end), where start is inclusive and end is exclusive.
 * Mirrors the upstream {@code Span} type in regex-test/lib.rs.
 *
 * <p>In TOML test files, spans are serialized as two-element arrays {@code [start, end]}.
 */
@JsonDeserialize(using = SpanDeserializer.class)
public record Span(int start, int end) {
    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span: [" + start + ", " + end + ")");
        }
    }
}
