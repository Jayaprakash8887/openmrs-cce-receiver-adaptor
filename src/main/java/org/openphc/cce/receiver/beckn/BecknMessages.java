package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Builds the answer bodies of the Sygnal endpoint: {@code Ack} and {@code Nack}, the same shape as
 * cce-beckn-service (docs/sygnal-integration.md §3.4).
 */
@Component
@ConditionalOnBecknEnabled
public class BecknMessages {

    private final ObjectMapper mapper;

    public BecknMessages(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** The body of a 200 answer. */
    public ObjectNode ack(String messageId) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode m = root.putObject("message");
        m.put("status", "ACK");
        m.put("messageId", Json.uuidOrRandom(messageId));
        return root;
    }

    /** The body of a 4xx or 5xx answer. */
    public ObjectNode nack(String messageId, String code, String message) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode m = root.putObject("message");
        m.put("status", "NACK");
        m.put("messageId", Json.uuidOrRandom(messageId));
        ObjectNode error = m.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return root;
    }
}
