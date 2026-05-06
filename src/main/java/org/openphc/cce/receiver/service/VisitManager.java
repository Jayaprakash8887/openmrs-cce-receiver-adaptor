package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.config.DiscoveredConfig;
import org.openphc.cce.receiver.config.ReferralProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class VisitManager {

    private static final Logger log = LoggerFactory.getLogger(VisitManager.class);
    private static final DateTimeFormatter OPENMRS_DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

    /** Per-request cache key prefix used to distinguish referral OPD visits from generic visits. */
    static final String REFERRAL_CACHE_PREFIX = "referral:";

    private final RestClient restClient;
    private final DiscoveredConfig discoveredConfig;
    private final ReferralProperties referralProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VisitManager(@Qualifier("openmrsRestClient") RestClient restClient,
                        DiscoveredConfig discoveredConfig,
                        ReferralProperties referralProperties) {
        this.restClient = restClient;
        this.discoveredConfig = discoveredConfig;
        this.referralProperties = referralProperties;
    }

    public ObjectNode ensureVisit(ObjectNode encounterNode, Map<String, String> perRequestVisitCache) {
        // Skip if visit/partOf already set
        if (!encounterNode.path("partOf").isMissingNode() ||
                encounterNode.has("visit")) {
            return encounterNode;
        }

        // Skip if no type[] (visit-type encounter)
        if (encounterNode.path("type").isMissingNode() || !encounterNode.path("type").isArray()) {
            return encounterNode;
        }

        String patientUuid = extractPatientUuid(encounterNode);
        if (patientUuid == null) {
            log.warn("Cannot link Visit — no patient UUID found in Encounter");
            return encounterNode;
        }

        // Check per-request cache
        String visitUuid = perRequestVisitCache.get(patientUuid);
        if (visitUuid == null) {
            visitUuid = findOrCreateVisit(patientUuid, encounterNode);
            if (visitUuid != null) {
                perRequestVisitCache.put(patientUuid, visitUuid);
            }
        }

        if (visitUuid != null) {
            encounterNode.put("visit", visitUuid);
            log.debug("Linked Encounter to Visit: {}", visitUuid);
        }

        return encounterNode;
    }

    public String ensureEncounterForOrder(ObjectNode orderNode, Map<String, String> perRequestVisitCache) {
        // Check if encounter already set
        JsonNode encounterRef = orderNode.path("encounter");
        if (!encounterRef.isMissingNode()) {
            String ref = encounterRef.path("reference").asText("");
            if (!ref.isBlank()) return null; // already has encounter
        }

        String patientUuid = extractPatientUuid(orderNode);
        if (patientUuid == null) {
            log.warn("Cannot create encounter for order — no patient UUID");
            return null;
        }

        // Find or create visit
        String visitUuid = perRequestVisitCache.get(patientUuid);
        if (visitUuid == null) {
            visitUuid = findOrCreateVisit(patientUuid, orderNode);
            if (visitUuid != null) {
                perRequestVisitCache.put(patientUuid, visitUuid);
            }
        }

        // Always create a NEW encounter (never reuse)
        return createEncounter(patientUuid, visitUuid);
    }

    private String findOrCreateVisit(String patientUuid, ObjectNode resourceNode) {
        // Search for active visit
        try {
            String response = restClient.get()
                    .uri("/visit?patient={uuid}&includeInactive=false&v=default", patientUuid)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                String uuid = results.get(0).path("uuid").asText(null);
                log.debug("Found active visit for patient {}: {}", patientUuid, uuid);
                return uuid;
            }
        } catch (Exception e) {
            log.warn("Failed to search visits for patient {}: {}", patientUuid, e.getMessage());
        }

        // Create new visit
        return createVisit(patientUuid, resourceNode);
    }

    private String createVisit(String patientUuid, ObjectNode resourceNode) {
        try {
            String startDatetime = extractStartDatetime(resourceNode);

            ObjectNode visitPayload = objectMapper.createObjectNode();
            visitPayload.put("patient", patientUuid);
            visitPayload.put("visitType", discoveredConfig.getVisitTypeUuid());
            visitPayload.put("startDatetime", startDatetime);

            if (discoveredConfig.getLocationUuid() != null) {
                visitPayload.put("location", discoveredConfig.getLocationUuid());
            }

            String response = restClient.post()
                    .uri("/visit")
                    .body(visitPayload.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String uuid = root.path("uuid").asText(null);
            log.info("Created new Visit for patient {}: {}", patientUuid, uuid);
            return uuid;
        } catch (Exception e) {
            log.error("Failed to create Visit for patient {}: {}", patientUuid, e.getMessage());
            return null;
        }
    }

    private String createEncounter(String patientUuid, String visitUuid) {
        try {
            String now = ZonedDateTime.now().format(OPENMRS_DATE_FORMAT);

            // Get encounter type UUID for "Consultation"
            String encounterTypeUuid = discoveredConfig.getEncounterTypeCache().get("Consultation");

            ObjectNode encounterPayload = objectMapper.createObjectNode();
            encounterPayload.put("patient", patientUuid);
            encounterPayload.put("encounterDatetime", now);

            if (encounterTypeUuid != null) {
                encounterPayload.put("encounterType", encounterTypeUuid);
            }
            if (visitUuid != null) {
                encounterPayload.put("visit", visitUuid);
            }
            if (discoveredConfig.getLocationUuid() != null) {
                encounterPayload.put("location", discoveredConfig.getLocationUuid());
            }

            String response = restClient.post()
                    .uri("/encounter")
                    .body(encounterPayload.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String uuid = root.path("uuid").asText(null);
            log.info("Created encounter for standalone order: {}", uuid);
            return uuid;
        } catch (Exception e) {
            log.error("Failed to create encounter for order: {}", e.getMessage());
            return null;
        }
    }

    private String extractPatientUuid(ObjectNode node) {
        // Try subject.reference (e.g. "Patient/<uuid>")
        String ref = node.path("subject").path("reference").asText("");
        if (ref.isBlank()) {
            ref = node.path("patient").path("reference").asText("");
        }
        if (!ref.isBlank()) {
            // Extract UUID from ResourceType/UUID
            String[] parts = ref.split("/");
            return parts[parts.length - 1];
        }

        // Try subject.identifier — look up patient in OpenMRS by identifier
        String identifierValue = node.path("subject").path("identifier").path("value").asText("");
        if (identifierValue.isBlank()) {
            identifierValue = node.path("patient").path("identifier").path("value").asText("");
        }
        if (!identifierValue.isBlank()) {
            return searchPatientByIdentifier(identifierValue);
        }

        return null;
    }

    private String searchPatientByIdentifier(String identifier) {
        try {
            String response = restClient.get()
                    .uri("/patient?identifier={id}&v=default", identifier)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                String uuid = results.get(0).path("uuid").asText(null);
                if (uuid != null) {
                    log.info("Resolved patient identifier '{}' → UUID '{}'", identifier, uuid);
                    return uuid;
                }
            }
            log.warn("No patient found in OpenMRS for identifier: {}", identifier);
        } catch (Exception e) {
            log.error("Failed to search patient by identifier '{}': {}", identifier, e.getMessage());
        }
        return null;
    }

    private String extractStartDatetime(ObjectNode node) {
        // Try period.start from encounter
        String start = node.path("period").path("start").asText("");
        if (!start.isBlank()) return start;

        // Fallback to current time
        return ZonedDateTime.now().format(OPENMRS_DATE_FORMAT);
    }

    // ------------------------------------------------------------------
    // Referral-specific flow (SPICE Referral In)
    // ------------------------------------------------------------------

    /**
     * Visit + Encounter creation tailored for SPICE referrals.
     * <ul>
     *   <li>Visit at the configured Outpatient Clinic location with the OPD visit type</li>
     *   <li>Encounter of type "Referral In" linked to that visit</li>
     * </ul>
     * Returns the new (or reused) encounter UUID, or {@code null} if creation
     * failed (in which case the caller should fall back to the generic flow
     * to avoid losing the order entirely).
     */
    public String ensureReferralEncounterForOrder(ObjectNode orderNode,
                                                  Map<String, String> perRequestVisitCache) {
        // NOTE: unlike the generic ensureEncounterForOrder, the referral flow
        // ALWAYS creates a fresh "Referral In" encounter — even when the inbound
        // ServiceRequest already carries an encounter reference. The runbook
        // mandates that a referred patient gets a brand-new Referral In
        // encounter linked to the OPD visit (so the order can be reconciled
        // and surfaced on the triage queue).

        String patientUuid = extractPatientUuid(orderNode);
        if (patientUuid == null) {
            log.warn("Cannot create referral encounter — no patient UUID");
            return null;
        }

        String referralVisitTypeUuid = referralProperties.getVisitTypeUuid();
        String referralLocationUuid = referralProperties.getLocationUuid();
        String referralEncounterTypeUuid = referralProperties.getEncounterTypeUuid();

        if (isBlank(referralVisitTypeUuid) || isBlank(referralLocationUuid)
                || isBlank(referralEncounterTypeUuid)) {
            log.warn("Referral flow enabled but visit-type/location/encounter-type UUID missing — "
                    + "falling back to generic encounter for patient {}", patientUuid);
            return null;
        }

        // Per-request cache (referral-scoped) to share visit across resources in a Bundle
        String cacheKey = REFERRAL_CACHE_PREFIX + patientUuid;
        String visitUuid = perRequestVisitCache.get(cacheKey);
        if (visitUuid == null) {
            visitUuid = findOrCreateReferralVisit(patientUuid, orderNode,
                    referralVisitTypeUuid, referralLocationUuid);
            if (visitUuid != null) {
                perRequestVisitCache.put(cacheKey, visitUuid);
            }
        }

        if (visitUuid == null) {
            log.warn("Referral visit creation failed for patient {} — falling back", patientUuid);
            return null;
        }

        return createReferralEncounter(patientUuid, visitUuid,
                referralEncounterTypeUuid, referralLocationUuid);
    }

    private String findOrCreateReferralVisit(String patientUuid, ObjectNode resourceNode,
                                             String referralVisitTypeUuid,
                                             String referralLocationUuid) {
        String firstActiveVisitUuid = null;

        // Reuse an existing active visit ONLY if it is the same OPD type at the same location
        try {
            String response = restClient.get()
                    .uri("/visit?patient={uuid}&includeInactive=false&v=full", patientUuid)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (results.isArray()) {
                for (JsonNode visit : results) {
                    String vt = visit.path("visitType").path("uuid").asText("");
                    String loc = visit.path("location").path("uuid").asText("");
                    String uuid = visit.path("uuid").asText(null);
                    if (firstActiveVisitUuid == null && uuid != null) {
                        firstActiveVisitUuid = uuid;
                    }
                    if (referralVisitTypeUuid.equals(vt) && referralLocationUuid.equals(loc)) {
                        log.debug("Reusing active referral visit for patient {}: {}", patientUuid, uuid);
                        return uuid;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to search referral visits for patient {}: {}", patientUuid, e.getMessage());
        }

        String created = createReferralVisit(patientUuid, resourceNode, referralVisitTypeUuid, referralLocationUuid);
        if (created != null) {
            return created;
        }

        // OpenMRS only allows one active visit per patient. If creation failed
        // (most commonly because another active visit already exists), reuse it
        // so we can still attach the Referral In encounter and order.
        if (firstActiveVisitUuid != null) {
            log.info("Referral visit creation failed for patient {} — reusing existing active visit {}",
                    patientUuid, firstActiveVisitUuid);
        }
        return firstActiveVisitUuid;
    }

    private String createReferralVisit(String patientUuid, ObjectNode resourceNode,
                                       String referralVisitTypeUuid, String referralLocationUuid) {
        try {
            String startDatetime = extractStartDatetime(resourceNode);

            ObjectNode visitPayload = objectMapper.createObjectNode();
            visitPayload.put("patient", patientUuid);
            visitPayload.put("visitType", referralVisitTypeUuid);
            visitPayload.put("location", referralLocationUuid);
            visitPayload.put("startDatetime", startDatetime);

            String response = restClient.post()
                    .uri("/visit")
                    .body(visitPayload.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String uuid = root.path("uuid").asText(null);
            log.info("Created referral Visit (OPD/Outpatient Clinic) for patient {}: {}",
                    patientUuid, uuid);
            return uuid;
        } catch (Exception e) {
            log.error("Failed to create referral Visit for patient {}: {}",
                    patientUuid, e.getMessage());
            return null;
        }
    }

    private String createReferralEncounter(String patientUuid, String visitUuid,
                                           String referralEncounterTypeUuid,
                                           String referralLocationUuid) {
        try {
            String when = ZonedDateTime.now().format(OPENMRS_DATE_FORMAT);

            ObjectNode encounterPayload = objectMapper.createObjectNode();
            encounterPayload.put("patient", patientUuid);
            encounterPayload.put("encounterDatetime", when);
            encounterPayload.put("encounterType", referralEncounterTypeUuid);
            encounterPayload.put("visit", visitUuid);
            encounterPayload.put("location", referralLocationUuid);

            String response = restClient.post()
                    .uri("/encounter")
                    .body(encounterPayload.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String uuid = root.path("uuid").asText(null);
            log.info("Created Referral In encounter {} for patient {} on visit {}",
                    uuid, patientUuid, visitUuid);
            return uuid;
        } catch (Exception e) {
            log.error("Failed to create Referral In encounter for patient {}: {}",
                    patientUuid, e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
