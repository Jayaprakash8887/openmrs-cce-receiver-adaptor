package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Handles {@code POST /beckn/bap-webhook/{action}}: callbacks for messages the emitter sent
 * (docs/beckn-integration.md §5). {@code on_update} and {@code on_status} are logged and answered
 * {@code Ack}. Nothing is written into OpenMRS. Every other action is answered {@code NOT_SUPPORTED}.
 */
@Service
@ConditionalOnBecknEnabled
public class BecknCallbackService {

    private static final Set<String> SUPPORTED = Set.of("on_update", "on_status");

    private final BecknMessages messages;
    private final BecknMetrics metrics;

    public BecknCallbackService(BecknMessages messages, BecknMetrics metrics) {
        this.messages = messages;
        this.metrics = metrics;
    }

    public ResponseEntity<JsonNode> handle(String action, String rawBody) {
        JsonNode body;
        try {
            body = messages.readTree(rawBody);
        } catch (Exception e) {
            body = null;
        }
        if (body == null || !body.isObject()) {
            return nack(action, null, BecknException.badRequest("The body is not a JSON object"));
        }

        JsonNode context = body.path("context");
        String claimed = Json.text(context, "action");
        if (!claimed.isBlank() && !claimed.equals(action)) {
            return nack(action, body, BecknException.badRequest("context.action '" + BecknMessageLog.clean(claimed)
                    + "' does not match the request path '" + BecknMessageLog.clean(action) + "'"));
        }
        if (!SUPPORTED.contains(action)) {
            metrics.unsupported(action);
            return nack(action, body, BecknException.notSupported(action));
        }

        String peer = Json.text(context, "bppId");
        String contractId = Json.text(body.path("message").path("contract"), "id");
        JsonNode performance = body.path("message").path("contract").path("performance");
        if (performance.isArray() && !performance.isEmpty()) {
            for (JsonNode entry : performance) {
                logEntry(action, context, contractId, peer, entry);
            }
        } else {
            logEntry(action, context, contractId, peer, null);
        }
        metrics.received(peer, action, "ack");
        return ResponseEntity.ok(messages.ack(Json.text(context, "messageId")));
    }

    private void logEntry(String action, JsonNode context, String contractId, String peer, JsonNode entry) {
        String status = entry == null ? "" : Json.text(entry.path("status"), "code");
        JsonNode resource = entry == null ? null : entry.path("performanceAttributes").path("resource");
        String resourceType = Json.text(resource, "resourceType");
        if (!status.isBlank()) {
            metrics.callbackStatus(peer, action, status);
        }
        BecknMessageLog.in(action)
                .peer(peer)
                .contractId(contractId)
                .transactionId(Json.text(context, "transactionId"))
                .messageId(Json.text(context, "messageId"))
                .performanceId(entry == null ? null : Json.text(entry, "id"))
                .resourceType(resourceType)
                // The ServiceRequest id is an order number; other resource ids are not logged.
                .referralId("ServiceRequest".equals(resourceType) ? Json.text(resource, "id") : null)
                .result(status.isBlank() ? "ACK" : status)
                .write();
    }

    private ResponseEntity<JsonNode> nack(String action, JsonNode body, BecknException e) {
        JsonNode context = body == null ? null : body.path("context");
        String peer = Json.text(context, "bppId");
        String contractId = body == null ? "" : Json.text(body.path("message").path("contract"), "id");
        metrics.received(peer, action, "nack");
        BecknMessageLog.in(action)
                .peer(peer)
                .contractId(contractId)
                .transactionId(Json.text(context, "transactionId"))
                .messageId(Json.text(context, "messageId"))
                .result("NACK")
                .code(e.code())
                .error(e.getMessage())
                .write();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(messages.nack(Json.text(context, "messageId"),
                e.code(), e.getMessage()));
    }
}
