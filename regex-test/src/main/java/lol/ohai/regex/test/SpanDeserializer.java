package lol.ohai.regex.test;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;

/**
 * Custom Jackson deserializer for {@link Span} from a two-element TOML array {@code [start, end]}.
 *
 * <p>The upstream regex test suite serializes spans as arrays (e.g. {@code bounds = [1, 4]}),
 * not as objects, so the default Jackson struct deserialization does not work.
 */
class SpanDeserializer extends StdDeserializer<Span> {

    SpanDeserializer() {
        super(Span.class);
    }

    @Override
    public Span deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (p.currentToken() != JsonToken.START_ARRAY) {
            throw ctxt.wrongTokenException(p, Span.class, JsonToken.START_ARRAY,
                    "expected array [start, end] for Span");
        }
        p.nextToken();
        int start = p.getIntValue();
        p.nextToken();
        int end = p.getIntValue();
        p.nextToken(); // consume END_ARRAY
        return new Span(start, end);
    }
}
