package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.receiver.config.BecknConfig;
import org.openphc.cce.receiver.config.BecknProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Handles {@code POST /sygnal/signal/emit}: a CCE care alert (docs/sygnal-integration.md §4).
 *
 * <p>The {@code message} of the request is a {@code sygnal:Signal} as the CCE profile of Sygnal (v3.0)
 * extends it: the care-alert fields ({@code patientId}, {@code stepStatus}, {@code slaStatus},
 * {@code fhirVersion}, {@code resource}) sit directly on the Signal, and so does CCE's own level of severity,
 * {@code cceSeverity} (Sygnal's {@code severity} is not sent). It is checked, answered synchronously, and its OpenMRS notification is raised afterwards on
 * {@link BecknConfig#CARE_ALERT_EXECUTOR}. Sygnal has no callback: the HTTP answer is the whole
 * result.
 * <ul>
 *   <li>200 {@code Ack} with the request's {@code messageId}: the alert is accepted. A repeat of an
 *       alert already notified is accepted too, and not notified twice.</li>
 *   <li>400 {@code Nack}: the request is wrong (action, receiver, sender, type, a missing field).
 *       Sending it again unchanged will not help.</li>
 *   <li>5xx {@code Nack}: a problem here (the notification queue is full, or an error). CCE may send
 *       it again later.</li>
 * </ul>
 * Nothing is written into OpenMRS except the notification.
 */
@Service
@ConditionalOnBecknEnabled
public class SignalEmitService {

    public static final String ACTION = "sygnal/signal/emit";

    private static final Logger LOG = LoggerFactory.getLogger(SignalEmitService.class);

    private final ObjectMapper mapper;
    private final BecknMessages messages;
    private final BecknMetrics metrics;
    private final CareAlertNotifier careAlerts;
    private final Executor executor;
    private final String selfId;
    private final String cceId;

    public SignalEmitService(ObjectMapper mapper, BecknMessages messages, BecknMetrics metrics,
                             CareAlertNotifier careAlerts, BecknProperties props,
                             @Qualifier(BecknConfig.CARE_ALERT_EXECUTOR) Executor executor) {
        this.mapper = mapper;
        this.messages = messages;
        this.metrics = metrics;
        this.careAlerts = careAlerts;
        this.executor = executor;
        this.selfId = props.self().id();
        this.cceId = props.cce().id();
    }

    public ResponseEntity<JsonNode> handle(String rawBody) {
        JsonNode body = readObject(rawBody);
        JsonNode context = body == null ? null : body.path("context");
        try {
            if (body == null) {
                throw BecknException.badRequest(ErrorCodes.SCH_INVALID_JSON, "The body is not a JSON object");
            }
            checkContext(context);
            checkSignal(body.path("message"));
            return accept(body);
        } catch (BecknException e) {
            return nack(context, e);
        } catch (RuntimeException e) {
            LOG.error("The '{}' request could not be handled", ACTION, e);
            return nack(context, new BecknException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.NET_INTERNAL_ERROR,
                    "The request could not be handled. Send it again later."));
        }
    }

    private void checkContext(JsonNode context) {
        if (!context.isObject()) {
            throw BecknException.badRequest(ErrorCodes.CTX_MISSING_FIELD, "context is missing");
        }
        String action = Json.text(context, "action");
        if (action.isBlank()) {
            throw BecknException.badRequest(ErrorCodes.CTX_MISSING_FIELD, "context.action is missing");
        }
        if (!ACTION.equals(action)) {
            throw BecknException.badRequest(ErrorCodes.CTX_ACTION_MISMATCH, "context.action is '"
                    + BecknMessageLog.clean(action) + "', but this endpoint takes '" + ACTION + "'");
        }
        String receiverId = Json.text(context, "receiverId");
        if (receiverId.isBlank()) {
            throw BecknException.badRequest(ErrorCodes.CTX_MISSING_FIELD, "context.receiverId is missing");
        }
        if (!selfId.equals(receiverId)) {
            throw BecknException.badRequest(ErrorCodes.CTX_INVALID_FIELD, "context.receiverId is '"
                    + BecknMessageLog.clean(receiverId) + "', but OpenMRS is '" + selfId + "'");
        }
        String senderId = Json.text(context, "senderId");
        if (senderId.isBlank()) {
            throw BecknException.badRequest(ErrorCodes.CTX_MISSING_FIELD, "context.senderId is missing");
        }
        if (!cceId.equals(senderId)) {
            throw BecknException.badRequest(ErrorCodes.CTX_INVALID_FIELD, "context.senderId is '"
                    + BecknMessageLog.clean(senderId) + "', but care alerts are taken only from '" + cceId + "'");
        }
        if (Json.text(context, "messageId").isBlank()) {
            throw BecknException.badRequest(ErrorCodes.CTX_MISSING_FIELD, "context.messageId is missing");
        }
    }

    /**
     * {@code message} must be a {@code sygnal:Signal} with an id, a patient and a FHIR resource, all
     * directly on it. Any other type is refused, the {@code cce:CceCareAlert} of profile v2.0 included.
     * A Signal of profile v1.0, with the alert in a {@code signalAttributes} bag, has no patient of its
     * own and is refused for that: the bag is not read. Fields this service does not know are ignored.
     */
    private static void checkSignal(JsonNode signal) {
        if (!signal.isObject()) {
            throw BecknException.badRequest(ErrorCodes.SCH_REQUIRED_FIELD_MISSING, "message is missing");
        }
        if (!CareAlertNotifier.isSignal(signal)) {
            throw BecknException.badRequest(ErrorCodes.SCH_TYPE_NOT_SUPPORTED,
                    "message.@type must be " + CareAlertNotifier.SIGNAL_TYPE);
        }
        if (Json.text(signal, "id").isBlank()) {
            throw BecknException.badRequest(ErrorCodes.SCH_REQUIRED_FIELD_MISSING, "message.id is missing");
        }
        if (Json.text(signal, "patientId").isBlank()) {
            throw BecknException.badRequest(ErrorCodes.SCH_REQUIRED_FIELD_MISSING, "message.patientId is missing");
        }
        if (Json.text(signal.path("resource"), "resourceType").isBlank()) {
            throw BecknException.badRequest(ErrorCodes.SCH_REQUIRED_FIELD_MISSING,
                    "message.resource must be a FHIR resource with a resourceType");
        }
    }

    /** Hands the notification to the executor, then answers {@code Ack}. */
    private ResponseEntity<JsonNode> accept(JsonNode request) {
        JsonNode context = request.path("context");
        JsonNode signal = request.path("message");
        String status = Json.text(signal, "stepStatus");
        String sla = Json.text(signal, "slaStatus");
        BecknMessageLog line = log(context)
                .signalId(Json.text(signal, "id"))
                .resourceType(Json.text(signal.path("resource"), "resourceType"))
                .code(CareAlertNotifier.cceSeverity(signal) + (status.isBlank() ? "" : "/" + status)
                        + (sla.isBlank() ? "" : "/" + sla));
        try {
            executor.execute(() -> metrics.careAlert(careAlerts.notify(request) ? "notified" : "not-notified"));
        } catch (RejectedExecutionException e) {
            metrics.careAlert("dropped");
            throw new BecknException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.NET_INTERNAL_ERROR,
                    "The notification queue is full. Send the alert again later.");
        }
        line.result("ACK").write();
        metrics.received(Json.text(context, "senderId"), ACTION, "ack");
        return ResponseEntity.ok(messages.ack(Json.text(context, "messageId")));
    }

    private ResponseEntity<JsonNode> nack(JsonNode context, BecknException e) {
        metrics.received(Json.text(context, "senderId"), Json.text(context, "action"), "nack");
        log(context).result("NACK").code(e.code()).error(e.getMessage()).write();
        return ResponseEntity.status(e.status())
                .body(messages.nack(Json.text(context, "messageId"), e.code(), e.getMessage()));
    }

    private static BecknMessageLog log(JsonNode context) {
        return BecknMessageLog.in(ACTION)
                .peer(Json.text(context, "senderId"))
                .transactionId(Json.text(context, "transactionId"))
                .messageId(Json.text(context, "messageId"));
    }

    private JsonNode readObject(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(rawBody);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }
}
