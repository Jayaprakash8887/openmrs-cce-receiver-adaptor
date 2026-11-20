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
 * <p>CCE sends an alert as a {@code sygnal/signal/emit}: a {@code sygnal:Signal} whose
 * {@code signalAttributes} is a {@code cce:CceCareAlert} box with the patient's national id
 * ({@code patientId}), the CCE severity, the step's status, the SLA status (optional) and the FHIR
 * {@code CommunicationRequest} or {@code Task} the intelligence service built. The notification goes
 * through the same {@code /alert} endpoint and to the same recipients as "New referral received".
 *
 * <ul>
 *   <li>title — the alert text: {@code payload[0].contentString}, or {@code Task.description}</li>
 *   <li>body — the patient's national id (and whether OpenMRS knows the patient), the step status,
 *       the SLA status and the protocol step</li>
 *   <li>link — the patient's chart, when a patient has that national id in OpenMRS</li>
 *   <li>severity — LOW → INFO, MEDIUM / HIGH → WARNING, CRITICAL → CRITICAL</li>
 * </ul>
 *
 * A repeated alert (same delivery id) is not notified twice. Never throws: the signal has already
 * been acknowledged when this runs.
 */
@Component
@ConditionalOnBecknEnabled
public class CareAlertNotifier {

    public static final String ALERT_TYPE = "cce:CceCareAlert";
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

    /** Whether a {@code sygnal:Signal} ({@code message} of the request) carries a CCE care alert. */
    public static boolean isCareAlert(JsonNode signal) {
        return signal != null && ALERT_TYPE.equals(Json.text(signal.path("signalAttributes"), "@type"));
    }

    /**
     * Notifies the alert in {@code signal}, the {@code message} of a {@code sygnal/signal/emit}.
     * Returns false when nothing was sent (logged).
     */
    public boolean notify(JsonNode signal) {
        try {
            JsonNode alert = signal.path("signalAttributes");
            JsonNode resource = alert.path("resource");
            String patientId = Json.text(alert, "patientId");
            if (patientId.isBlank()) {
                LOG.warn("CCE alert {} has no patientId — not notified", deliveryId(signal, resource));
                return false;
            }

            List<String> to = recipients.resolveForReferral(resource);
            if (to.isEmpty()) {
                LOG.info("No notification recipients for CCE alert {}", deliveryId(signal, resource));
                return false;
            }

            String patientUuid = openMrs.searchByIdentifier("/patient", patientId);
            notifications.notify(
                    new NotificationPayload(
                            NOTIFICATION_TYPE,
                            severity(Json.text(alert, "severity")),
                            title(resource),
                            body(alert, resource, patientId, patientUuid != null),
                            patientUuid == null ? null : "/openmrs/spa/patient/" + patientUuid + "/chart"),
                    to,
                    "CceCareAlert:" + deliveryId(signal, resource));
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

    /** CCE severity (LOW, MEDIUM, HIGH, CRITICAL) as an OpenMRS notification severity. */
    static String severity(String cceSeverity) {
        return switch (cceSeverity.toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> "CRITICAL";
            case "MEDIUM", "HIGH" -> "WARNING";
            default -> "INFO";
        };
    }

    /**
     * The intelligence delivery id: the resource identifier, else the signal id ({@code message.id},
     * which CCE sets to the delivery id, as it does {@code context.messageId}).
     */
    static String deliveryId(JsonNode signal, JsonNode resource) {
        for (JsonNode id : resource.path("identifier")) {
            if (DELIVERY_ID_SYSTEM.equals(Json.text(id, "system")) && !Json.text(id, "value").isBlank()) {
                return Json.text(id, "value");
            }
        }
        return Json.text(signal, "id");
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
