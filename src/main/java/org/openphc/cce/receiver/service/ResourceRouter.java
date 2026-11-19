package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.config.ReferralProperties;
import org.openphc.cce.receiver.model.ResourceEntry;
import org.openphc.cce.receiver.model.RoutingResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ResourceRouter {

    private static final Logger log = LoggerFactory.getLogger(ResourceRouter.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ReferenceResolver referenceResolver;
    private final ConceptResolver conceptResolver;
    private final RequiredFieldEnricher requiredFieldEnricher;
    private final PatientIdentifierEnricher patientIdentifierEnricher;
    private final VisitManager visitManager;
    private final FhirToRestTransformer fhirToRestTransformer;
    private final OpenMrsRestClient openMrsRestClient;
    private final OpenMrsFhirClient openMrsFhirClient;
    private final ReferralProperties referralProperties;
    private final OpenMrsNotificationClient notificationClient;
    private final RecipientResolver recipientResolver;

    private static final Set<String> FHIR_ONLY_TYPES = Set.of(
            "Task", "DiagnosticReport", "Procedure",
            "MedicationDispense", "MedicationAdministration", "Consent"
    );

    private static final Map<String, Integer> DEPENDENCY_ORDER = Map.of(
            "Patient", 1,
            "Practitioner", 2,
            "Location", 3,
            "Medication", 4,
            "Encounter", 5
    );

    public ResourceRouter(ReferenceResolver referenceResolver,
                          ConceptResolver conceptResolver,
                          RequiredFieldEnricher requiredFieldEnricher,
                          PatientIdentifierEnricher patientIdentifierEnricher,
                          VisitManager visitManager,
                          FhirToRestTransformer fhirToRestTransformer,
                          OpenMrsRestClient openMrsRestClient,
                          OpenMrsFhirClient openMrsFhirClient,
                          ReferralProperties referralProperties,
                          OpenMrsNotificationClient notificationClient,
                          RecipientResolver recipientResolver) {
        this.referenceResolver = referenceResolver;
        this.conceptResolver = conceptResolver;
        this.requiredFieldEnricher = requiredFieldEnricher;
        this.patientIdentifierEnricher = patientIdentifierEnricher;
        this.visitManager = visitManager;
        this.fhirToRestTransformer = fhirToRestTransformer;
        this.openMrsRestClient = openMrsRestClient;
        this.openMrsFhirClient = openMrsFhirClient;
        this.referralProperties = referralProperties;
        this.notificationClient = notificationClient;
        this.recipientResolver = recipientResolver;
    }

    public List<RoutingResult> routeAll(List<ResourceEntry> entries) {
        // Sort by dependency order
        List<ResourceEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(e -> DEPENDENCY_ORDER.getOrDefault(e.resourceType(), 10)));

        SourceIdMappingStore mappingStore = new SourceIdMappingStore();
        Map<String, String> perRequestVisitCache = new ConcurrentHashMap<>();
        List<RoutingResult> results = new ArrayList<>();

        for (ResourceEntry entry : sorted) {
            try {
                RoutingResult result = processEntry(entry, mappingStore, perRequestVisitCache);
                results.add(result);

                // Store mapping for cross-references within Bundle
                if (result.resourceId() != null && !"failed".equals(result.status())) {
                    mappingStore.put(entry.fullUrl(), entry.resourceType(),
                            extractId(entry.resourceJson()), result.resourceId());
                }
            } catch (Exception e) {
                log.error("Failed to process {} entry: {}", entry.resourceType(), e.getMessage(), e);
                results.add(RoutingResult.failure(entry.resourceType(), "N/A", 500, e.getMessage()));
            }
        }

        return results;
    }

    private RoutingResult processEntry(ResourceEntry entry, SourceIdMappingStore mappingStore,
                                       Map<String, String> perRequestVisitCache) throws Exception {
        String resourceType = entry.resourceType();
        ObjectNode resourceNode = (ObjectNode) objectMapper.readTree(entry.resourceJson());

        // Enrichment Pipeline
        // [1] Resolve references
        referenceResolver.resolveReferences(resourceNode, mappingStore);

        // [2] Resolve concepts (done during transform for REST path)
        // ConceptResolver is called by FhirToRestTransformer

        // [3] Required field enrichment
        requiredFieldEnricher.enrich(resourceNode, resourceType);

        // [4] Patient identifier enrichment
        if ("Patient".equals(resourceType)) {
            patientIdentifierEnricher.enrich(resourceNode);
        }

        // [5] Visit management for Encounters
        if ("Encounter".equals(resourceType)) {
            visitManager.ensureVisit(resourceNode, perRequestVisitCache);
        }

        // Ensure encounter for standalone orders
        if ("ServiceRequest".equals(resourceType) || "MedicationRequest".equals(resourceType)) {
            String encounterUuid = null;

            // Referral-aware path: only ServiceRequests detected as referrals AND only when enabled.
            // MedicationRequests and non-referral ServiceRequests keep the existing generic flow.
            if ("ServiceRequest".equals(resourceType)
                    && referralProperties.isEnabled()
                    && fhirToRestTransformer.isReferral(resourceNode)) {
                // A redelivered referral (intelligence retries, replays) must not create a second
                // Referral In encounter and order: the accession number is the source SR id.
                String accessionNumber = fhirToRestTransformer.extractAccessionNumber(resourceNode);
                String patientUuid = visitManager.resolvePatientUuid(resourceNode);
                if (accessionNumber != null && patientUuid != null) {
                    String existingOrder = openMrsRestClient.findOrderByAccessionNumber(patientUuid, accessionNumber);
                    if (existingOrder != null) {
                        log.info("Referral {} already exists as order {} — skipping", accessionNumber, existingOrder);
                        return RoutingResult.duplicate(resourceType, existingOrder, "REST:/order");
                    }
                }
                encounterUuid = visitManager.ensureReferralEncounterForOrder(resourceNode);
                if (encounterUuid != null) {
                    log.debug("ServiceRequest routed via referral flow (encounter={})", encounterUuid);
                } else {
                    log.warn("Referral flow could not create encounter — falling back to generic flow");
                }
            }

            if (encounterUuid == null) {
                encounterUuid = visitManager.ensureEncounterForOrder(resourceNode, perRequestVisitCache);
            }
            if (encounterUuid != null) {
                ObjectNode encounterRef = objectMapper.createObjectNode();
                encounterRef.put("reference", "Encounter/" + encounterUuid);
                resourceNode.set("encounter", encounterRef);
            }
        }

        // Route to REST or FHIR path
        if (FHIR_ONLY_TYPES.contains(resourceType)) {
            return routeViaFhir(resourceNode, resourceType);
        } else {
            return routeViaRest(resourceNode, resourceType, entry);
        }
    }

    private RoutingResult routeViaRest(ObjectNode resourceNode, String resourceType, ResourceEntry entry) {
        // [6] Transform FHIR → REST payload
        FhirToRestTransformer.TransformResult transformResult =
                fhirToRestTransformer.transform(resourceNode, resourceType);

        // Determine create vs update
        String existingUuid = resolveExistingUuid(resourceNode, resourceType, entry);

        // A patient OpenMRS already has (found by one of the inbound identifiers) is reused as it is: a
        // referral must not overwrite the demographics OpenMRS holds, and creating it again would be
        // refused (the identifier is unique). The referral's other resources then point at this patient.
        if ("Patient".equals(resourceType) && existingUuid != null) {
            log.info("Patient already in OpenMRS ({}) — reusing it", existingUuid);
            return RoutingResult.duplicate(resourceType, existingUuid, "REST:/patient");
        }

        RoutingResult result;
        if (existingUuid != null) {
            result = openMrsRestClient.update(transformResult.endpoint(), existingUuid,
                    transformResult.payload(), resourceType);
        } else {
            result = openMrsRestClient.create(transformResult.endpoint(), transformResult.payload(), resourceType);
        }

        // [7] Fire user-facing notification for newly-created referral orders.
        // Fire-and-forget — never propagates failures into the routing result.
        if ("ServiceRequest".equals(resourceType)
                && "created".equals(result.status())
                && referralProperties.isEnabled()
                && fhirToRestTransformer.isReferral(resourceNode)) {
            fireReferralNotification(resourceNode, result);
        }

        return result;
    }

    private void fireReferralNotification(ObjectNode resourceNode, RoutingResult result) {
        try {
            // Prefer the resolved OpenMRS patient UUID returned by POST /order.
            // The inbound FHIR ServiceRequest.subject.reference is often the
            // upstream source identifier (e.g. SPICE national-id) and would
            // produce a broken /openmrs/spa/patient/{id}/chart link.
            String patientUuid = patientUuidFromOrderResponse(result.responseBody());
            if (patientUuid == null) {
                String fallback = extractPatientUuid(resourceNode);
                if (isUuid(fallback)) patientUuid = fallback;
            }
            String link = patientUuid != null
                    ? "/openmrs/spa/patient/" + patientUuid + "/chart/Referrals"
                    : null;

            String body = "Patient routed for review";
            String label = patientLabelFromOrderResponse(result.responseBody());
            if (label == null) {
                label = shortPatientLabel(resourceNode, patientUuid);
            }
            if (label != null) {
                body = "Patient " + label + " has been referred";
            }

            String dedupeKey = "ServiceRequest:" + safe(resourceNode.path("id").asText(null));

            String severity = resolveSeverity(resourceNode);

            List<String> recipients = recipientResolver.resolveForReferral(resourceNode);
            if (recipients.isEmpty()) {
                log.info("No notification recipients resolved for referral {}", result.resourceId());
                return;
            }
            notificationClient.notify(
                    new OpenMrsNotificationClient.NotificationPayload(
                            "REFERRAL_ARRIVED",
                            severity,
                            "New referral received",
                            body,
                            link),
                    recipients,
                    dedupeKey);
        } catch (Exception e) {
            // Defensive — notification must never break the routing flow.
            log.warn("Failed to fire referral notification: {}", e.toString());
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private String extractPatientUuid(ObjectNode resourceNode) {
        String ref = resourceNode.path("subject").path("reference").asText("");
        if (ref.isBlank()) return null;
        int slash = ref.lastIndexOf('/');
        String tail = slash >= 0 ? ref.substring(slash + 1) : ref;
        return tail.isBlank() ? null : tail;
    }

    private String shortPatientLabel(ObjectNode resourceNode, String patientUuid) {
        // Prefer a display label if upstream resolution set one; otherwise show
        // a truncated UUID so clinicians can correlate across UI surfaces.
        String display = resourceNode.path("subject").path("display").asText(null);
        if (display != null && !display.isBlank()) return display;
        if (patientUuid != null && patientUuid.length() >= 8) return patientUuid.substring(0, 8);
        return null;
    }

    private static final java.util.regex.Pattern UUID_PATTERN = java.util.regex.Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static boolean isUuid(String s) {
        return s != null && UUID_PATTERN.matcher(s).matches();
    }

    /**
     * Extracts the resolved OpenMRS patient UUID from the {@code POST /order}
     * response body ({@code patient.uuid}). This is the only place the real
     * OpenMRS UUID is available — the inbound FHIR ref usually carries the
     * upstream source identifier.
     */
    private String patientUuidFromOrderResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        try {
            String uuid = objectMapper.readTree(responseBody)
                    .path("patient").path("uuid").asText(null);
            return isUuid(uuid) ? uuid : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts a human-friendly patient label from the {@code POST /order} response,
     * which OpenMRS returns with a nested {@code patient.display} like
     * {@code "1234567890222 - Xerta Xerta"}. Returns the trailing name portion when present.
     */
    private String patientLabelFromOrderResponse(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) return null;
        try {
            String display = objectMapper.readTree(responseBody)
                    .path("patient").path("display").asText(null);
            if (display == null || display.isBlank()) return null;
            int dash = display.indexOf(" - ");
            String name = dash >= 0 ? display.substring(dash + 3).trim() : display.trim();
            return name.isEmpty() ? null : name;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Maps a FHIR ServiceRequest to a UI severity (INFO | WARNING | CRITICAL).
     *
     * <p>Rules (highest match wins):
     * <ol>
     *   <li>{@code priority = stat | asap} → CRITICAL</li>
     *   <li>{@code priority = urgent} → WARNING</li>
     *   <li>Any identifier value or {@code patientInstruction} containing
     *       "high risk", "critical", or "emergency" upgrades INFO→WARNING and
     *       WARNING→CRITICAL.</li>
     *   <li>Default → INFO</li>
     * </ol>
     */
    private String resolveSeverity(ObjectNode resourceNode) {
        String severity;
        String priority = resourceNode.path("priority").asText("").toLowerCase();
        severity = switch (priority) {
            case "stat", "asap" -> "CRITICAL";
            case "urgent" -> "WARNING";
            default -> "INFO";
        };

        if (containsRiskKeyword(resourceNode)) {
            severity = switch (severity) {
                case "INFO" -> "WARNING";
                case "WARNING" -> "CRITICAL";
                default -> severity;
            };
        }
        return severity;
    }

    private boolean containsRiskKeyword(ObjectNode resourceNode) {
        String instruction = resourceNode.path("patientInstruction").asText("").toLowerCase();
        if (matchesRisk(instruction)) return true;

        JsonNode identifiers = resourceNode.path("identifier");
        if (identifiers.isArray()) {
            for (JsonNode id : identifiers) {
                if (matchesRisk(id.path("value").asText("").toLowerCase())) return true;
            }
        }
        return false;
    }

    private static boolean matchesRisk(String s) {
        return s.contains("high risk") || s.contains("critical") || s.contains("emergency");
    }

    private RoutingResult routeViaFhir(ObjectNode resourceNode, String resourceType) {
        String payload = resourceNode.toString();

        // Check for existing resource by ID
        String id = resourceNode.path("id").asText(null);
        if (id != null && !id.isBlank()) {
            // For FHIR path, attempt update
            return openMrsFhirClient.update(resourceType, id, payload);
        }

        return openMrsFhirClient.create(resourceType, payload);
    }

    private String resolveExistingUuid(ObjectNode resourceNode, String resourceType, ResourceEntry entry) {
        String id = resourceNode.path("id").asText(null);
        if (id == null || id.isBlank()) return null;

        // UUID pattern check
        if (id.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) {
            String endpoint = getRestEndpoint(resourceType);
            if (endpoint != null && openMrsRestClient.exists(endpoint, id)) {
                return id;
            }
        }

        // For Patient, try identifier-based search
        if ("Patient".equals(resourceType)) {
            JsonNode identifiers = resourceNode.path("identifier");
            if (identifiers.isArray()) {
                for (JsonNode idNode : identifiers) {
                    String value = idNode.path("value").asText(idNode.path("identifier").asText(""));
                    if (!value.isBlank()) {
                        String found = openMrsRestClient.searchByIdentifier("/patient", value);
                        if (found != null) return found;
                    }
                }
            }
        }

        return null;
    }

    private String getRestEndpoint(String resourceType) {
        return switch (resourceType) {
            case "Patient" -> "/patient";
            case "Encounter" -> "/encounter";
            case "Observation" -> "/obs";
            case "Condition" -> "/condition";
            case "AllergyIntolerance" -> "/allergy";
            case "ServiceRequest", "MedicationRequest" -> "/order";
            case "Location" -> "/location";
            case "Practitioner" -> "/provider";
            case "Medication" -> "/drug";
            case "Immunization" -> "/obs";
            default -> null;
        };
    }

    private String extractId(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return node.path("id").asText(null);
        } catch (Exception e) {
            return null;
        }
    }
}
