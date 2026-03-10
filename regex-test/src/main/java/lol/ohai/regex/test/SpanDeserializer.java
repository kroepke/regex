package lol.ohai.regex.test;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/**
 * Custom Jackson deserializer for {@link Span}.
 *
 * <p>The upstream regex test suite serializes spans in two ways:
 * <ul>
 *   <li>As a two-element array: {@code bounds = [1, 4]}</li>
 *   <li>As an inline object: {@code bounds = { start = 2, end = 5 }}</li>
 * </ul>
 * This deserializer handles both forms.
 */
class SpanDeserializer extends StdDeserializer<Span> {

    SpanDeserializer() {
        super(Span.class);
    }

    @Override
    public Span deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() == JsonToken.START_ARRAY) {
            p.nextToken();
            int start = p.getIntValue();
            p.nextToken();
            int end = p.getIntValue();
            p.nextToken(); // consume END_ARRAY
            return new Span(start, end);
        } else if (p.currentToken() == JsonToken.START_OBJECT) {
            int start = 0;
            int end = 0;
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = p.currentName();
                p.nextToken();
                if ("start".equals(fieldName)) {
                    start = p.getIntValue();
                } else if ("end".equals(fieldName)) {
                    end = p.getIntValue();
                }
            }
            return new Span(start, end);
        } else {
            throw ctxt.wrongTokenException(p, Span.class, JsonToken.START_ARRAY,
                    "expected array [start, end] or object {start, end} for Span");
        }
    }
}
