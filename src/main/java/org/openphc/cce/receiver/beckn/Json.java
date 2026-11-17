package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.UUID;
import java.util.regex.Pattern;

/** Null-safe reading of Beckn JSON, and the id rule for Ack / Nack. */
public final class Json {

    // UUID.fromString() accepts short forms such as "1-1-1-1-1". The Beckn schema does not.
    private static final Pattern UUID_FORM =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private Json() {
    }

    /** The text of a field, or an empty string when the field is missing or not text. */
    public static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isTextual() ? v.asText() : "";
    }

    /** The given id when it is a UUID, otherwise a new one (as cce-beckn-service does). */
    public static String uuidOrRandom(String value) {
        return value != null && UUID_FORM.matcher(value).matches() ? value : UUID.randomUUID().toString();
    }
}
