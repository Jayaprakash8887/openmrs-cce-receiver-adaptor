package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * The FHIR transaction {@code Bundle} handed to the write path, built the same way for every Beckn
 * referral: one entry per resource, in the given order, each with {@code fullUrl} {@code Type/id}
 * (so a reference such as {@code Patient/<id>} resolves to the resource written just before it).
 */
final class TransactionBundle {

    private TransactionBundle() {
    }

    static ObjectNode of(ObjectMapper mapper, List<JsonNode> resources) {
        ObjectNode bundle = mapper.createObjectNode();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "transaction");
        ArrayNode entries = bundle.putArray("entry");
        for (JsonNode resource : resources) {
            String type = Json.text(resource, "resourceType");
            String id = Json.text(resource, "id");
            ObjectNode entry = entries.addObject();
            if (!id.isBlank()) {
                entry.put("fullUrl", type + "/" + id);
            }
            entry.set("resource", resource.deepCopy());
            ObjectNode request = entry.putObject("request");
            request.put("method", "POST");
            request.put("url", type);
        }
        return bundle;
    }
}
