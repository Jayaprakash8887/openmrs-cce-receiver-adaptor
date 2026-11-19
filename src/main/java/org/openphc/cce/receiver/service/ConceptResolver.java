package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.exception.ResourceTransformException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConceptResolver {

    private static final Logger log = LoggerFactory.getLogger(ConceptResolver.class);
    private static final String NOT_FOUND = "__NOT_FOUND__";
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    private static final Map<String, String> SYSTEM_TO_SOURCE = Map.ofEntries(
            Map.entry("http://loinc.org", "LOINC"),
            Map.entry("http://snomed.info/sct", "SNOMED CT"),
            Map.entry("urn:oid:2.16.840.1.113883.6.96", "SNOMED CT"),
            Map.entry("http://hl7.org/fhir/sid/icd-10", "ICD-10-WHO"),
            Map.entry("http://hl7.org/fhir/sid/icd-10-cm", "ICD-10-WHO"),
            Map.entry("https://icd.who.int", "ICD-11"),
            Map.entry("http://www.nlm.nih.gov/research/umls/rxnorm", "RxNORM"),
            Map.entry("http://hl7.org/fhir/sid/cvx", "CVX"),
            Map.entry("http://npc.rw", "NPC"),
            Map.entry("http://www.ichi.org/", "ICHI"),
            Map.entry("urn:ietf:rfc:3986", "CIEL"),
            Map.entry("http://ciel.org", "CIEL"),
            Map.entry("https://openconceptlab.org/orgs/CIEL/sources/CIEL", "CIEL"),
            Map.entry("https://openconceptlab.org/orgs/Medtronic-LABS/sources/SPICE", "SPICE")
    );

    private static final String[] NATIVE_SYSTEMS = {
            "http://fhir.openmrs.org",
            "http://openmrs.org"
    };

    private static final String UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    public ConceptResolver(@Qualifier("openmrsRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public String resolveConceptUuid(JsonNode codeableConcept) {
        if (codeableConcept == null || codeableConcept.isMissingNode()) {
            return null;
        }

        JsonNode codingArray = codeableConcept.path("coding");
        if (!codingArray.isArray() || codingArray.isEmpty()) {
            return null;
        }

        for (JsonNode coding : codingArray) {
            String system = coding.path("system").asText("");
            String code = coding.path("code").asText("");

            if (code.isEmpty()) continue;

            // Check native system first
            if (isNativeSystem(system)) {
                return verifyConceptExists(code);
            }

            // Check if code is a UUID
            if (code.matches(UUID_PATTERN)) {
                String verified = verifyConceptByUuid(code);
                if (verified != null) return verified;
            }

            // Map system to source and search
            String source = SYSTEM_TO_SOURCE.get(system);
            if (source != null) {
                String uuid = searchConceptBySourceAndCode(source, code);
                if (uuid != null) return uuid;
            }
        }

        // Fallback: try searching by display name or text
        String fallbackUuid = tryFallbackByName(codeableConcept);
        if (fallbackUuid != null) return fallbackUuid;

        // Auto-create concept if not found
        String createdUuid = autoCreateConcept(codeableConcept);
        if (createdUuid != null) return createdUuid;

        throw new ResourceTransformException("Unable to resolve concept from CodeableConcept: " +
                codeableConcept.toString());
    }

    public String searchConceptByName(String name) {
        if (name == null || name.isBlank()) return null;
        String cacheKey = nameKey(name);
        String cached = cache.get(cacheKey);
        if (cached != null) return NOT_FOUND.equals(cached) ? null : cached;

        try {
            String response = restClient.get()
                    .uri("/concept?q={name}&v=default", name)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) return null;

            // Only accept exact match on display name
            for (JsonNode result : results) {
                String display = result.path("display").asText("");
                if (display.equalsIgnoreCase(name)) {
                    String uuid = result.path("uuid").asText(null);
                    cache.put(cacheKey, uuid);
                    return uuid;
                }
            }
        } catch (Exception e) {
            log.warn("Concept name search failed for '{}': {}", name, e.getMessage());
        }
        cache.put(cacheKey, NOT_FOUND);
        return null;
    }

    public ObjectNode resolveCodeableConceptFields(ObjectNode resourceNode) {
        resolveField(resourceNode, "code");
        resolveField(resourceNode, "medicationCodeableConcept");
        resolveField(resourceNode, "vaccineCode");
        resolveField(resourceNode, "valueCodeableConcept");
        resolveField(resourceNode, "bodySite");
        resolveField(resourceNode, "method");
        resolveField(resourceNode, "clinicalStatus");
        resolveField(resourceNode, "verificationStatus");
        resolveField(resourceNode, "severity");
        resolveField(resourceNode, "serviceType");

        // Handle reasonCode array
        JsonNode reasonCodes = resourceNode.path("reasonCode");
        if (reasonCodes.isArray()) {
            for (JsonNode rc : reasonCodes) {
                // resolve each entry's coding
                resolveCodeableConceptNode(rc);
            }
        }

        // Handle component[].code
        JsonNode components = resourceNode.path("component");
        if (components.isArray()) {
            for (JsonNode comp : components) {
                resolveField((ObjectNode) comp, "code");
            }
        }

        return resourceNode;
    }

    private String tryFallbackByName(JsonNode codeableConcept) {
        // Try display from first coding
        JsonNode codingArray = codeableConcept.path("coding");
        if (codingArray.isArray()) {
            for (JsonNode coding : codingArray) {
                String display = coding.path("display").asText("");
                if (!display.isBlank()) {
                    String uuid = searchConceptByName(display);
                    if (uuid != null) {
                        log.info("Resolved concept via display name fallback '{}': {}", display, uuid);
                        return uuid;
                    }
                }
            }
        }
        // Try text field
        String text = codeableConcept.path("text").asText("");
        if (!text.isBlank()) {
            String uuid = searchConceptByName(text);
            if (uuid != null) {
                log.info("Resolved concept via text fallback '{}': {}", text, uuid);
                return uuid;
            }
        }
        return null;
    }

    private void resolveField(ObjectNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        if (!field.isMissingNode() && field.isObject()) {
            resolveCodeableConceptNode(field);
        }
    }

    private void resolveCodeableConceptNode(JsonNode codeableConcept) {
        // Resolution is used at transform time — concepts are resolved to UUIDs
        // and placed directly into the REST payload's "concept" field
    }

    private boolean isNativeSystem(String system) {
        for (String native_sys : NATIVE_SYSTEMS) {
            if (native_sys.equals(system)) return true;
        }
        return false;
    }

    private String verifyConceptExists(String code) {
        try {
            String response = restClient.get()
                    .uri("/concept/{uuid}", code)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            return root.path("uuid").asText(null);
        } catch (Exception e) {
            throw new ResourceTransformException(
                    "Native concept UUID '" + code + "' not found in OpenMRS");
        }
    }

    private String verifyConceptByUuid(String uuid) {
        try {
            String response = restClient.get()
                    .uri("/concept/{uuid}", uuid)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            return root.path("uuid").asText(null);
        } catch (Exception e) {
            log.debug("UUID '{}' not found as concept in OpenMRS", uuid);
            return null;
        }
    }

    private String autoCreateConcept(JsonNode codeableConcept) {
        // Determine concept name from display or text
        String name = null;

        JsonNode codingArray = codeableConcept.path("coding");
        if (codingArray.isArray()) {
            for (JsonNode coding : codingArray) {
                String display = coding.path("display").asText("");
                if (!display.isBlank() && name == null) name = display;
            }
        }
        if (name == null || name.isBlank()) {
            name = codeableConcept.path("text").asText("");
        }
        if (name.isBlank()) return null;

        return autoCreateConcept(name);
    }

    private static String nameKey(String name) {
        return "name:" + name.toLowerCase();
    }

    /**
     * Auto-creates a concept in OpenMRS with Misc Order class and Text datatype.
     * Used when no existing concept matches the given name.
     *
     * <p>The name search caches "not found", so a created concept replaces that entry; otherwise the
     * next request skips the search, tries to create the concept again and OpenMRS refuses the
     * duplicate name. When creating fails (for example the concept already exists, made by another
     * request or an earlier run), the name is searched again past the cache.
     */
    public String autoCreateConcept(String name) {
        if (name == null || name.isBlank()) return null;

        try {
            // Misc Order concept class (compatible with Referral Order type)
            String conceptClassUuid = "8d492ee0-c2cc-11de-8d13-0010c6dffd0f";
            // Text datatype (allows entering results in O3)
            String datatypeUuid = "8d4a4ab4-c2cc-11de-8d13-0010c6dffd0f";

            ObjectNode conceptPayload = objectMapper.createObjectNode();
            ObjectNode nameNode = objectMapper.createObjectNode();
            nameNode.put("name", name);
            nameNode.put("locale", "en");
            nameNode.put("localePreferred", true);
            nameNode.put("conceptNameType", "FULLY_SPECIFIED");
            conceptPayload.putArray("names").add(nameNode);
            conceptPayload.put("datatype", datatypeUuid);
            conceptPayload.put("conceptClass", conceptClassUuid);

            String body = objectMapper.writeValueAsString(conceptPayload);
            log.info("Auto-creating concept '{}' in OpenMRS", name);

            String response = restClient.post()
                    .uri("/concept")
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            String uuid = root.path("uuid").asText(null);
            if (uuid != null) {
                log.info("Auto-created concept '{}' with UUID: {}", name, uuid);
                cache.put(nameKey(name), uuid);
            }
            return uuid;
        } catch (Exception e) {
            log.warn("Failed to auto-create concept '{}': {}", name, e.getMessage());
            cache.remove(nameKey(name));
            String existing = searchConceptByName(name);
            if (existing != null) {
                log.info("Concept '{}' already exists: {}", name, existing);
            }
            return existing;
        }
    }

    private String searchConceptBySourceAndCode(String source, String code) {
        String cacheKey = source + ":" + code;
        String cached = cache.get(cacheKey);
        if (cached != null) return NOT_FOUND.equals(cached) ? null : cached;

        try {
            String response = restClient.get()
                    .uri("/concept?source={source}&code={code}", source, code)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.path("results");
            if (results.isArray() && !results.isEmpty()) {
                String uuid = results.get(0).path("uuid").asText(null);
                cache.put(cacheKey, uuid);
                return uuid;
            }
            cache.put(cacheKey, NOT_FOUND);
            return null;
        } catch (Exception e) {
            log.warn("Concept search failed for source={}, code={}: {}", source, code, e.getMessage());
            return null;
        }
    }
}
