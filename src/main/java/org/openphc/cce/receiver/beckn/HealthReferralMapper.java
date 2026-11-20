package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.config.BecknProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Reads the health-referral flow from Spice ({@code select}, {@code init}, {@code confirm},
 * {@code status}; docs/beckn-integration.md §4A) and, for {@code confirm}, turns the referral into the
 * FHIR transaction {@code Bundle} the existing write path takes: the same {@code Patient} +
 * {@code ServiceRequest} pair the {@code update} path receives as FHIR.
 *
 * <ul>
 *   <li>{@code Patient} — the participant with {@code participantRole} {@code PATIENT}: name from
 *       {@code descriptor.name}, one identifier per {@code healthIds[]} entry (type from
 *       {@code receiver.beckn.health-id-types}), {@code gender}, {@code dateOfBirth}.</li>
 *   <li>{@code ServiceRequest} — a referral (category SNOMED 3457005) whose first identifier is the
 *       coordination id, so the order's accession number is the coordination id and a repeated
 *       {@code confirm} is caught as a duplicate. {@code referralReason} becomes the order
 *       instructions; the referrer ({@code FIELD_WORKER} or another non-patient participant) the
 *       orderer.</li>
 * </ul>
 */
@Component
@ConditionalOnBecknEnabled
public class HealthReferralMapper {

    /** The identifier system of the coordination id on the {@code ServiceRequest}. */
    public static final String COORDINATION_ID_SYSTEM = "urn:beckn:health-referral/coordination-id";
    /** Ends in {@code /category}: the write path names the order concept after this identifier. */
    public static final String CATEGORY_SYSTEM = "urn:beckn:health-referral/category";

    static final String DEFAULT_BIRTH_DATE = "1970-01-01";
    private static final int MAX_FHIR_ID = 64;

    private final ObjectMapper mapper;
    private final BecknProperties props;
    private final BecknMetrics metrics;

    public HealthReferralMapper(ObjectMapper mapper, BecknProperties props, BecknMetrics metrics) {
        this.mapper = mapper;
        this.props = props;
        this.metrics = metrics;
    }

    /** The FHIR side of a confirmed referral. */
    public record Fhir(String bundleJson, String patientId, String serviceRequestId) {
    }

    /** Checks the sender and the referral id. A broken rule throws {@link BecknException} BAD_REQUEST. */
    public HealthReferral read(JsonNode body) {
        JsonNode context = body.path("context");
        JsonNode contract = body.path("message").path("contract");
        String bapId = Json.text(context, "bapId");
        if (!props.spice().bapId().equals(bapId)) {
            metrics.unknownPeer(bapId);
            throw BecknException.badRequest(bapId.isBlank()
                    ? "context.bapId is missing"
                    : "context.bapId '" + BecknMessageLog.clean(bapId) + "' is not a known referral sender");
        }
        if (!contract.isObject()) {
            throw BecknException.badRequest("message.contract is missing");
        }
        String contractId = Json.text(contract, "id");
        String coordinationId = Json.text(contract.path("contractAttributes"), "coordinationId");
        if (coordinationId.isBlank()) {
            coordinationId = contractId;
        }
        if (coordinationId.isBlank()) {
            throw BecknException.badRequest("message.contract.contractAttributes.coordinationId is missing");
        }
        return new HealthReferral(
                coordinationId,
                contractId.isBlank() ? coordinationId : contractId,
                Json.text(context, "transactionId"),
                Json.text(context, "messageId"),
                bapId,
                Json.text(context, "networkId"),
                contract);
    }

    /** The {@code Patient} and {@code ServiceRequest} of a referral, as a transaction Bundle. */
    public Fhir toFhir(HealthReferral referral, String authoredOn) {
        JsonNode participants = referral.contract().path("participants");
        JsonNode patientParticipant = participantWithRole(participants, "PATIENT");
        if (patientParticipant == null) {
            throw BecknException.badRequest("message.contract.participants has no participant with participantRole PATIENT");
        }
        ObjectNode patient = patient(patientParticipant);
        ObjectNode serviceRequest = serviceRequest(referral, patient, referrer(participants), authoredOn);
        String bundle = TransactionBundle.of(mapper, List.of(patient, serviceRequest)).toString();
        return new Fhir(bundle, Json.text(patient, "id"), referral.coordinationId());
    }

    /** The first {@code healthIds[]} entry of the {@code PATIENT} participant, or null. */
    public JsonNode patientHealthId(HealthReferral referral) {
        JsonNode p = participantWithRole(referral.contract().path("participants"), "PATIENT");
        JsonNode ids = p == null ? null : p.path("participantAttributes").path("healthIds");
        if (ids != null && ids.isArray()) {
            for (JsonNode id : ids) {
                if (!Json.text(id, "value").isBlank()) {
                    return id;
                }
            }
        }
        return null;
    }

    // ---- Patient ----

    private ObjectNode patient(JsonNode participant) {
        JsonNode attrs = participant.path("participantAttributes");
        String name = Json.text(participant.path("descriptor"), "name").trim();
        if (name.isBlank()) {
            throw BecknException.badRequest("the PATIENT participant has no descriptor.name");
        }
        List<JsonNode> healthIds = healthIds(attrs);
        if (healthIds.isEmpty()) {
            throw BecknException.badRequest("the PATIENT participant has no participantAttributes.healthIds");
        }

        ObjectNode patient = mapper.createObjectNode();
        patient.put("resourceType", "Patient");
        patient.put("id", fhirId(Json.text(healthIds.get(0), "value"), Json.text(participant, "id")));

        ArrayNode identifiers = patient.putArray("identifier");
        for (JsonNode id : healthIds) {
            String system = Json.text(id, "system");
            ObjectNode identifier = identifiers.addObject();
            if (!system.isBlank()) {
                identifier.put("system", system);
                identifier.putObject("type").put("text", props.identifierTypeFor(system));
            }
            identifier.put("value", Json.text(id, "value"));
        }

        patient.putArray("name").add(humanName(name));
        patient.put("gender", gender(Json.text(attrs, "gender")));
        String birthDate = Json.text(attrs, "dateOfBirth");
        patient.put("birthDate", birthDate.isBlank() ? DEFAULT_BIRTH_DATE : birthDate);
        return patient;
    }

    /** "Rajesh Kumar Dhoni" → given [Rajesh, Kumar], family Dhoni. One word: given only. */
    private ObjectNode humanName(String full) {
        String[] parts = full.split("\\s+");
        ObjectNode name = mapper.createObjectNode();
        ArrayNode given = name.putArray("given");
        if (parts.length == 1) {
            given.add(parts[0]);
            return name;
        }
        for (int i = 0; i < parts.length - 1; i++) {
            given.add(parts[i]);
        }
        name.put("family", parts[parts.length - 1]);
        name.put("text", full);
        return name;
    }

    /** HealthParticipant gender (MALE, FEMALE, OTHER, PREFER_NOT_TO_SAY) as FHIR gender. */
    static String gender(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "MALE" -> "male";
            case "FEMALE" -> "female";
            case "OTHER" -> "other";
            default -> "unknown";
        };
    }

    // ---- ServiceRequest ----

    private ObjectNode serviceRequest(HealthReferral referral, ObjectNode patient, JsonNode referrer,
                                      String authoredOn) {
        JsonNode attrs = referral.contract().path("contractAttributes");
        JsonNode criteria = attrs.path("targetCriteria");
        String service = codedDisplay(criteria.path("serviceCategory"));
        if (service.isBlank()) {
            service = Json.text(attrs, "healthServiceType");
        }

        ObjectNode sr = mapper.createObjectNode();
        sr.put("resourceType", "ServiceRequest");
        sr.put("id", referral.coordinationId());
        sr.put("status", "active");
        sr.put("intent", "order");
        sr.put("priority", priority(Json.text(attrs, "clinicalUrgencyTier")));

        ObjectNode category = sr.putArray("category").addObject();
        ObjectNode coding = category.putArray("coding").addObject();
        coding.put("system", "http://snomed.info/sct");
        coding.put("code", "3457005");
        coding.put("display", "Patient referral");
        category.put("text", "Referral");

        // The first identifier is the coordination id: the write path uses it as the accession number.
        ArrayNode identifiers = sr.putArray("identifier");
        identifiers.addObject().put("system", COORDINATION_ID_SYSTEM).put("value", referral.coordinationId());
        identifiers.addObject().put("system", CATEGORY_SYSTEM).put("value", service.isBlank() ? "Referral" : service);

        ObjectNode subject = sr.putObject("subject");
        subject.put("reference", "Patient/" + Json.text(patient, "id"));
        JsonNode firstId = patient.path("identifier").path(0);
        ObjectNode subjectId = subject.putObject("identifier");
        if (!Json.text(firstId, "system").isBlank()) {
            subjectId.put("system", Json.text(firstId, "system"));
        }
        subjectId.put("value", Json.text(firstId, "value"));

        if (authoredOn != null && !authoredOn.isBlank()) {
            sr.put("authoredOn", authoredOn);
        }

        if (referrer != null) {
            String name = Json.text(referrer.path("descriptor"), "name");
            List<JsonNode> ids = healthIds(referrer.path("participantAttributes"));
            String id = fhirId(ids.isEmpty() ? "" : Json.text(ids.get(0), "value"), Json.text(referrer, "id"));
            ObjectNode performer = sr.putArray("performer").addObject();
            performer.put("reference", "Practitioner/" + id);
            if (!name.isBlank()) {
                performer.put("display", name);
            }
        }

        String reason = Json.text(attrs, "referralReason");
        sr.put("patientInstruction", reason.isBlank()
                ? "Referral" + (service.isBlank() ? "" : " for " + service)
                : reason);
        String details = details(attrs, criteria, service, referrer);
        if (!details.isBlank()) {
            sr.putArray("note").addObject().put("text", details);
        }
        return sr;
    }

    /** HealthReferral clinicalUrgencyTier (ROUTINE, URGENT, EMERGENCY) as FHIR priority. */
    static String priority(String tier) {
        return switch (tier.toUpperCase(Locale.ROOT)) {
            case "URGENT" -> "urgent";
            case "EMERGENCY" -> "stat";
            default -> "routine";
        };
    }

    private static String details(JsonNode attrs, JsonNode criteria, String service, JsonNode referrer) {
        StringJoiner text = new StringJoiner("; ");
        if (!service.isBlank()) text.add("Service: " + service);
        String procedures = joined(criteria.path("procedureNeeds"));
        if (!procedures.isBlank()) text.add("Procedures: " + procedures);
        String equipment = joined(criteria.path("equipmentNeeds"));
        if (!equipment.isBlank()) text.add("Equipment: " + equipment);
        String modality = Json.text(criteria, "consultationModality");
        if (!modality.isBlank()) text.add("Modality: " + modality);
        String urgency = Json.text(attrs, "clinicalUrgencyTier");
        if (!urgency.isBlank()) text.add("Urgency: " + urgency);
        String scheme = codedDisplay(attrs.path("schemeRef"));
        if (!scheme.isBlank()) text.add("Scheme: " + scheme);
        if (referrer != null) {
            String name = Json.text(referrer.path("descriptor"), "name");
            String role = Json.text(referrer.path("participantAttributes"), "participantRole");
            if (!name.isBlank()) text.add("Referred by: " + name + (role.isBlank() ? "" : " (" + role + ")"));
        }
        return text.toString();
    }

    // ---- helpers ----

    private static JsonNode participantWithRole(JsonNode participants, String role) {
        if (participants.isArray()) {
            for (JsonNode p : participants) {
                if (role.equalsIgnoreCase(Json.text(p.path("participantAttributes"), "participantRole"))) {
                    return p;
                }
            }
        }
        return null;
    }

    /** The referrer: a FIELD_WORKER, else the first participant that is not the patient. */
    private static JsonNode referrer(JsonNode participants) {
        JsonNode fieldWorker = participantWithRole(participants, "FIELD_WORKER");
        if (fieldWorker != null || !participants.isArray()) {
            return fieldWorker;
        }
        for (JsonNode p : participants) {
            if (!"PATIENT".equalsIgnoreCase(Json.text(p.path("participantAttributes"), "participantRole"))) {
                return p;
            }
        }
        return null;
    }

    private static List<JsonNode> healthIds(JsonNode attrs) {
        List<JsonNode> ids = new ArrayList<>();
        JsonNode array = attrs.path("healthIds");
        if (array.isArray()) {
            for (JsonNode id : array) {
                if (!Json.text(id, "value").isBlank()) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /** A FHIR id ([A-Za-z0-9-.], at most 64): the value, else the fallback, with other characters as '-'. */
    static String fhirId(String value, String fallback) {
        String v = value == null || value.isBlank() ? fallback : value;
        String id = v == null ? "" : v.trim().replaceAll("[^A-Za-z0-9.\\-]", "-");
        if (id.isBlank()) {
            id = "unknown";
        }
        return id.length() > MAX_FHIR_ID ? id.substring(0, MAX_FHIR_ID) : id;
    }

    private static String codedDisplay(JsonNode coded) {
        String display = Json.text(coded, "display");
        return display.isBlank() ? Json.text(coded, "code") : display;
    }

    private static String joined(JsonNode array) {
        StringJoiner j = new StringJoiner(", ");
        if (array.isArray()) {
            array.forEach(v -> {
                if (v.isTextual() && !v.asText().isBlank()) j.add(v.asText());
            });
        }
        return j.toString();
    }
}
