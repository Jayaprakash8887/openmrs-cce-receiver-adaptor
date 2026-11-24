package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import org.openphc.cce.receiver.service.OpenMrsNotificationClient;
import org.openphc.cce.receiver.service.OpenMrsNotificationClient.NotificationPayload;
import org.openphc.cce.receiver.service.OpenMrsRestClient;
import org.openphc.cce.receiver.service.RecipientResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Turns a CCE care alert into an OpenMRS notification (docs/sygnal-integration.md §4).
 *
 * <p>CCE sends an alert as a {@code sygnal/signal/emit} whose {@code message} is a
 * {@code sygnal:Signal} with the care-alert fields directly on it (the CCE profile of Sygnal, v3.0):
 * the patient's national id ({@code patientId}), the CCE severity ({@code cceSeverity}),
 * the step's status, the SLA status
 * (optional) and the FHIR {@code CommunicationRequest} or {@code Task} the intelligence service
 * built. The notification goes through the same {@code /alert} endpoint and to the same recipients
 * as "New referral received".
 *
 * <ul>
 *   <li>title — the alert text: {@code payload[0].contentString}, or {@code Task.description}</li>
 *   <li>body — the patient's national id (and whether OpenMRS knows the patient), the step status,
 *       the SLA status and the protocol step</li>
 *   <li>link — the patient's chart, when a patient has that national id in OpenMRS</li>
 *   <li>severity — from CCE's level, {@code cceSeverity}: LOW → INFO, MEDIUM / HIGH → WARNING,
 *       CRITICAL → CRITICAL (OpenMRS notification levels)</li>
 * </ul>
 *
 * A repeated alert (same delivery id) is not notified twice. Never throws: the alert has already
 * been acknowledged when this runs.
 */
@Component
@ConditionalOnBecknEnabled
public class CareAlertNotifier {

    public static final String SIGNAL_TYPE = "sygnal:Signal";
    static final String NOTIFICATION_TYPE = "CCE_ALERT";
    static final String PROTOCOL_STEP_EXTENSION = "cce-protocol-step-id";
    static final String DELIVERY_ID_SYSTEM = "http://openphc.org/fhir/intelligence-delivery-id";
    private static final int MAX_TITLE = 120;

    private static final Logger LOG = LoggerFactory.getLogger(CareAlertNotifier.class);

    private final OpenMrsNotificationClient notifications;
    private final RecipientResolver recipients;
    private final OpenMrsRestClient openMrs;

    public CareAlertNotifier(OpenMrsNotificationClient notifications, RecipientResolver recipients,
                             OpenMrsRestClient openMrs) {
        this.notifications = notifications;
        this.recipients = recipients;
        this.openMrs = openMrs;
    }

    /** Whether the {@code message} of the request is a {@code sygnal:Signal}, the type of a care alert. */
    public static boolean isSignal(JsonNode message) {
        return message != null && SIGNAL_TYPE.equals(Json.text(message, "@type"));
    }

    /**
     * Notifies the alert of {@code request}, a whole {@code sygnal/signal/emit}: its {@code message} is
     * the Signal with the alert's fields, and {@code message.id} (= {@code context.messageId}) the
     * delivery id. Returns false when nothing was sent (logged).
     */
    public boolean notify(JsonNode request) {
        try {
            JsonNode alert = request.path("message");
            JsonNode resource = alert.path("resource");
            String deliveryId = deliveryId(request.path("context"), alert, resource);
            String patientId = Json.text(alert, "patientId");
            if (patientId.isBlank()) {
                LOG.warn("CCE alert {} has no patientId — not notified", deliveryId);
                return false;
            }

            List<String> to = recipients.resolveForReferral(resource);
            if (to.isEmpty()) {
                LOG.info("No notification recipients for CCE alert {}", deliveryId);
                return false;
            }

            String patientUuid = openMrs.searchByIdentifier("/patient", patientId);
            notifications.notify(
                    new NotificationPayload(
                            NOTIFICATION_TYPE,
                            severity(cceSeverity(alert)),
                            title(resource),
                            body(alert, resource, patientId, patientUuid != null),
                            patientUuid == null ? null : "/openmrs/spa/patient/" + patientUuid + "/chart"),
                    to,
                    "SygnalSignal:" + deliveryId);
            return true;
        } catch (RuntimeException e) {
            LOG.warn("CCE alert could not be notified: {}", e.toString());
            return false;
        }
    }

    /** The alert text from the protocol, cut to fit the notification title. */
    static String title(JsonNode resource) {
        String text = Json.text(resource.path("payload").path(0), "contentString");
        if (text.isBlank()) {
            text = Json.text(resource, "description");
        }
        if (text.isBlank()) {
            text = "Care coordination alert";
        }
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > MAX_TITLE ? text.substring(0, MAX_TITLE - 1) + "…" : text;
    }

    /** "Patient national-id 0123123123133 · Step status: not-started · SLA status: overdue · Step: ecg-order" */
    static String body(JsonNode alert, JsonNode resource, String patientId, boolean knownInOpenMrs) {
        StringJoiner body = new StringJoiner(" · ");
        body.add("Patient national-id " + patientId + (knownInOpenMrs ? "" : " (not registered in OpenMRS)"));
        String status = Json.text(alert, "stepStatus");
        if (!status.isBlank()) {
            body.add("Step status: " + status.toLowerCase(Locale.ROOT));
        }
        String sla = Json.text(alert, "slaStatus");
        if (!sla.isBlank()) {
            body.add("SLA status: " + sla.toLowerCase(Locale.ROOT));
        }
        String step = extension(resource, PROTOCOL_STEP_EXTENSION);
        if (!step.isBlank()) {
            body.add("Step: " + step);
        }
        return body.toString();
    }

    /**
     * CCE's level of severity: {@code cceSeverity} on the Signal (LOW, MEDIUM, HIGH, CRITICAL). Sygnal's
     * {@code Signal.severity} has only INFO, WARNING, CRITICAL, so CCE carries its level in a field of its own
     * and does not send {@code Signal.severity}; neither {@code severity} nor a {@code signalAttributes} bag
     * is read. Empty when the field is missing.
     */
    static String cceSeverity(JsonNode signal) {
        return Json.text(signal, "cceSeverity");
    }

    /** CCE severity (LOW, MEDIUM, HIGH, CRITICAL) as an OpenMRS notification severity. */
    static String severity(String cceSeverity) {
        return switch (cceSeverity.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> "CRITICAL";
            case "MEDIUM", "HIGH" -> "WARNING";
            default -> "INFO";
        };
    }

    /**
     * The intelligence delivery id: the resource identifier, else the Signal's id ({@code message.id}),
     * else {@code context.messageId}. CCE sets all three to the delivery id; a repeated alert carries
     * the same one.
     */
    static String deliveryId(JsonNode context, JsonNode signal, JsonNode resource) {
        for (JsonNode id : resource.path("identifier")) {
            if (DELIVERY_ID_SYSTEM.equals(Json.text(id, "system")) && !Json.text(id, "value").isBlank()) {
                return Json.text(id, "value");
            }
        }
        String signalId = Json.text(signal, "id");
        return signalId.isBlank() ? Json.text(context, "messageId") : signalId;
    }

    private static String extension(JsonNode resource, String urlSuffix) {
        for (JsonNode e : resource.path("extension")) {
            if (Json.text(e, "url").endsWith(urlSuffix)) {
                for (String field : new String[]{"valueString", "valueCode", "valueId"}) {
                    if (!Json.text(e, field).isBlank()) {
                        return Json.text(e, field);
                    }
                }
            }
        }
        return "";
    }
}
