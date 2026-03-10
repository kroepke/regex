package lol.ohai.regex.test;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Custom Jackson deserializer for {@link Captures} that handles the four TOML match formats
 * used in the upstream regex test suite.
 *
 * <p>The four formats (from regex-test/lib.rs):
 * <ol>
 *   <li>Simple span: {@code [start, end]} — pattern id=0, single group (the overall match)</li>
 *   <li>Span with id: {@code {id = N, span = [start, end]}} — single group with explicit pattern id</li>
 *   <li>Capture groups: {@code [[s0,e0], [s1,e1], []]} — multiple groups, id=0, {@code []} = non-participating</li>
 *   <li>Full captures: {@code {id = N, spans = [[s0,e0], [s1,e1], []]}} — groups with explicit pattern id</li>
 * </ol>
 */
class CapturesDeserializer extends StdDeserializer<Captures> {

    CapturesDeserializer() {
        super(Captures.class);
    }

    @Override
    public Captures deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();

        if (token == JsonToken.START_ARRAY) {
            return deserializeArray(p, ctxt);
        } else if (token == JsonToken.START_OBJECT) {
            return deserializeObject(p, ctxt);
        } else {
            throw ctxt.wrongTokenException(p, Captures.class, JsonToken.START_ARRAY,
                    "expected array or object for Captures");
        }
    }

    /**
     * Handles array formats:
     * <ul>
     *   <li>Format 1: {@code [start, end]} — two integers → simple span, id=0</li>
     *   <li>Format 3: {@code [[s0,e0], [s1,e1], []]} — arrays of arrays → capture groups, id=0</li>
     * </ul>
     */
    private Captures deserializeArray(JsonParser p, DeserializationContext ctxt) throws IOException {
        // Peek at the first element to distinguish format 1 vs format 3
        JsonToken firstToken = p.nextToken();

        if (firstToken == JsonToken.END_ARRAY) {
            // Empty array — this shouldn't happen at the top Captures level but handle gracefully
            return new Captures(0, List.of());
        }

        if (firstToken == JsonToken.VALUE_NUMBER_INT) {
            // Format 1: [start, end] — two integers
            int start = p.getIntValue();
            p.nextToken(); // move to end
            int end = p.getIntValue();
            p.nextToken(); // consume END_ARRAY
            Span span = new Span(start, end);
            return new Captures(0, List.of(Optional.of(span)));
        } else if (firstToken == JsonToken.START_ARRAY) {
            // Format 3: [[s0,e0], [s1,e1], []] — list of MaybeSpan
            List<Optional<Span>> groups = new ArrayList<>();
            // First element is already an array (we just got START_ARRAY for it)
            groups.add(deserializeMaybeSpan(p, ctxt));

            // Read remaining elements
            JsonToken t;
            while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
                if (t != JsonToken.START_ARRAY) {
                    throw ctxt.wrongTokenException(p, Captures.class, JsonToken.START_ARRAY,
                            "expected array element in capture groups list");
                }
                groups.add(deserializeMaybeSpan(p, ctxt));
            }
            return new Captures(0, List.copyOf(groups));
        } else {
            throw ctxt.wrongTokenException(p, Captures.class, firstToken,
                    "expected number or array as first element of Captures array");
        }
    }

    /**
     * Handles object formats:
     * <ul>
     *   <li>Format 2: {@code {id: N, span: [start, end]}} — single span with explicit id</li>
     *   <li>Format 4: {@code {id: N, spans: [[s0,e0], ...]}} — capture groups with explicit id</li>
     * </ul>
     */
    private Captures deserializeObject(JsonParser p, DeserializationContext ctxt) throws IOException {
        int id = 0;
        List<Optional<Span>> groups = null;

        while (p.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = p.currentName();
            p.nextToken(); // move to value

            switch (fieldName) {
                case "id" -> id = p.getIntValue();
                case "span" -> {
                    // Format 2: span = [start, end]
                    expectToken(p, ctxt, JsonToken.START_ARRAY);
                    p.nextToken();
                    int start = p.getIntValue();
                    p.nextToken();
                    int end = p.getIntValue();
                    p.nextToken(); // END_ARRAY
                    groups = List.of(Optional.of(new Span(start, end)));
                }
                case "spans" -> {
                    // Format 4: spans = [[s0,e0], [s1,e1], []]
                    expectToken(p, ctxt, JsonToken.START_ARRAY);
                    List<Optional<Span>> spansGroups = new ArrayList<>();
                    JsonToken t;
                    while ((t = p.nextToken()) != JsonToken.END_ARRAY) {
                        if (t != JsonToken.START_ARRAY) {
                            throw ctxt.wrongTokenException(p, Captures.class, JsonToken.START_ARRAY,
                                    "expected array element in spans list");
                        }
                        spansGroups.add(deserializeMaybeSpan(p, ctxt));
                    }
                    groups = List.copyOf(spansGroups);
                }
                default -> ctxt.handleUnknownProperty(p, this, Captures.class, fieldName);
            }
        }

        if (groups == null) {
            throw ctxt.instantiationException(Captures.class, "Captures object missing 'span' or 'spans' field");
        }

        return new Captures(id, groups);
    }

    /**
     * Deserializes a MaybeSpan from the current position, where the parser has already
     * consumed the START_ARRAY token.
     *
     * <ul>
     *   <li>{@code []} → {@link Optional#empty()} (non-participating group)</li>
     *   <li>{@code [start, end]} → {@link Optional} of {@link Span}</li>
     * </ul>
     */
    private Optional<Span> deserializeMaybeSpan(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken t = p.nextToken();
        if (t == JsonToken.END_ARRAY) {
            // Empty array [] → non-participating group
            return Optional.empty();
        }
        // [start, end]
        if (t != JsonToken.VALUE_NUMBER_INT) {
            throw ctxt.wrongTokenException(p, Span.class, t, "expected integer in span array");
        }
        int start = p.getIntValue();
        p.nextToken(); // end value
        int end = p.getIntValue();
        p.nextToken(); // END_ARRAY
        return Optional.of(new Span(start, end));
    }

    private void expectToken(JsonParser p, DeserializationContext ctxt, JsonToken expected) throws IOException {
        if (p.currentToken() != expected) {
            throw ctxt.wrongTokenException(p, Captures.class, expected,
                    "unexpected token in Captures deserialization");
        }
    }
}
