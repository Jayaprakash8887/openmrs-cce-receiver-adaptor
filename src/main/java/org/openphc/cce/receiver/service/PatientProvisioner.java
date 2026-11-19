package org.openphc.cce.receiver.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.config.DiscoveredConfig;
import org.openphc.cce.receiver.config.OpenMrsConfigDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Resolves a FHIR patient identifier to an OpenMRS patient UUID.
 * <p>If no matching patient exists in OpenMRS, a placeholder patient is
 * provisioned automatically using the inbound identifier (e.g. national-id)
 * plus an auto-generated OpenMRS ID and minimal demographic defaults, so the
 * downstream order/encounter/observation flow can proceed instead of failing
 * with "patient not found".
 */
@Component
public class PatientProvisioner {

    private static final Logger log = LoggerFactory.getLogger(PatientProvisioner.class);
    private static final String DEFAULT_GENDER = "F";
    private static final String DEFAULT_BIRTHDATE = "1970-01-01";

    // Pools used to mint a plausible random name when SPICE returns no
    // demographics for an unknown patient. Keeps placeholder rows in OpenMRS
    // from collapsing into a wall of identical "Unknown Patient-<id>" entries.
    private static final String[] RANDOM_GIVEN_NAMES = {
            "Alex", "Sam", "Jordan", "Taylor", "Casey", "Morgan", "Riley",
            "Quinn", "Avery", "Reese", "Skylar", "Drew", "Robin", "Blair",
            "Dakota", "Jamie", "Parker", "Rowan", "Hayden", "Kai"
    };
    private static final String[] RANDOM_FAMILY_NAMES = {
            "Stone", "Brooks", "Hayes", "Ellis", "Reed", "Lane", "Foster",
            "Holt", "Pierce", "Walsh", "Vega", "Mendez", "Patel", "Khan",
            "Ortiz", "Singh", "Dixon", "Hart", "Wells", "Gibson"
    };
    private static final java.util.concurrent.ThreadLocalRandom RANDOM =
            java.util.concurrent.ThreadLocalRandom.current();

    private final RestClient restClient;
    private final DiscoveredConfig discoveredConfig;
    private final OpenMrsConfigDiscovery configDiscovery;
    private final SpicePatientLookup spicePatientLookup;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String placeholderIdentifierTypeName;

    public PatientProvisioner(@Qualifier("openmrsRestClient") RestClient restClient,
                              DiscoveredConfig discoveredConfig,
                              OpenMrsConfigDiscovery configDiscovery,
                              SpicePatientLookup spicePatientLookup,
                              @Value("${openmrs.placeholder-patient.identifier-type-name:national-id}")
                                  String placeholderIdentifierTypeName) {
        this.restClient = restClient;
        this.discoveredConfig = discoveredConfig;
        this.configDiscovery = configDiscovery;
        this.spicePatientLookup = spicePatientLookup;
        this.placeholderIdentifierTypeName = placeholderIdentifierTypeName;
    }

    /**
     * Returns the OpenMRS UUID for the patient identified by {@code identifierValue},
     * provisioning a placeholder patient on miss. Returns {@code null} only when
     * neither lookup nor creation succeeds.
     *
     * <p><b>Caller contract:</b> exhaust every available lookup strategy
     * (reference, identifier, etc.) via {@link #lookup(String)} first; only the
     * final fallback should call this method, otherwise duplicate placeholder
     * patients can be created.
     */
    public String lookupOrCreate(String identifierValue, String identifierSystem) {
        if (identifierValue == null || identifierValue.isBlank()) return null;

        String existing = lookup(identifierValue);
        if (existing != null) return existing;

        log.info("Patient not found for identifier '{}' (system='{}') — auto-creating placeholder",
                identifierValue, identifierSystem);
        return createPlaceholder(identifierValue, identifierSystem);
    }

    /** Pure lookup — returns {@code null} if no patient matches. */
    public String lookup(String identifierValue) {
        try {
            String response = restClient.get()
                    .uri("/patient?identifier={id}&v=custom:(uuid,identifiers:(identifier,voided))", identifierValue)
                    .retrieve()
                    .body(String.class);
            JsonNode results = objectMapper.readTree(response).path("results");
            // Exact match only: OpenMRS's identifier search also returns identifiers that merely contain the value.
            String uuid = OpenMrsRestClient.exactIdentifierMatch(results, identifierValue);
            if (uuid != null) {
                log.info("Resolved patient identifier '{}' to UUID: {}", identifierValue, uuid);
                return uuid;
            }
        } catch (Exception e) {
            log.warn("Failed to look up patient by identifier '{}': {}", identifierValue, e.getMessage());
        }
        return null;
    }

    private String createPlaceholder(String identifierValue, String identifierSystem) {
        try {
            ObjectNode payload = objectMapper.createObjectNode();

            // Try to fetch real demographics from SPICE; fall back to placeholder defaults.
            SpicePatientLookup.Demographics demographics = null;
            if (spicePatientLookup != null && spicePatientLookup.isEnabled()) {
                demographics = spicePatientLookup.fetchByIdentifier(identifierValue);
                if (demographics != null) {
                    log.info("Fetched SPICE demographics for identifier '{}': given='{}' family='{}' gender='{}' birthDate='{}'",
                            identifierValue, demographics.givenName(), demographics.familyName(),
                            demographics.gender(), demographics.birthDate());
                }
            }

            String givenName = pick(demographics == null ? null : demographics.givenName(), randomGivenName());
            String familyName = pick(demographics == null ? null : demographics.familyName(),
                    randomFamilyName());
            String gender = normalizeGender(demographics == null ? null : demographics.gender());
            String birthdate = pick(demographics == null ? null : demographics.birthDate(), DEFAULT_BIRTHDATE);

            ObjectNode person = payload.putObject("person");
            ObjectNode name = objectMapper.createObjectNode();
            name.put("givenName", givenName);
            name.put("familyName", familyName);
            person.putArray("names").add(name);
            person.put("gender", gender);
            person.put("birthdate", birthdate);

            ArrayNode identifiers = payload.putArray("identifiers");

            // 1) Inbound reference identifier stored under the configured
            //    placeholder identifier type (default: "national-id").
            String inboundTypeUuid = resolvePlaceholderIdentifierTypeUuid();
            if (inboundTypeUuid != null) {
                identifiers.add(buildIdentifier(identifierValue, inboundTypeUuid, true));
            }

            // 2) Auto-generated OpenMRS ID (the primary OpenMRS identifier type).
            String primaryTypeUuid = discoveredConfig.getIdentifierTypeUuid();
            if (primaryTypeUuid != null && !primaryTypeUuid.equals(inboundTypeUuid)) {
                String generated = generateOpenMrsId();
                if (generated != null) {
                    identifiers.add(buildIdentifier(generated, primaryTypeUuid, inboundTypeUuid == null));
                }
            }

            if (identifiers.isEmpty()) {
                log.warn("Cannot auto-create placeholder patient — no identifier type could be resolved "
                        + "(placeholderType='{}', inboundSystem='{}')",
                        placeholderIdentifierTypeName, identifierSystem);
                return null;
            }

            String response = restClient.post()
                    .uri("/patient")
                    .header("Content-Type", "application/json")
                    .body(payload.toString())
                    .retrieve()
                    .body(String.class);
            String uuid = objectMapper.readTree(response).path("uuid").asText(null);
            log.info("Auto-created placeholder patient {} for identifier '{}' (system='{}')",
                    uuid, identifierValue, identifierSystem);
            return uuid;
        } catch (Exception e) {
            log.error("Failed to auto-create placeholder patient for identifier '{}': {}",
                    identifierValue, e.getMessage(), e);
            return null;
        }
    }

    private static String pick(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String randomGivenName() {
        return RANDOM_GIVEN_NAMES[RANDOM.nextInt(RANDOM_GIVEN_NAMES.length)];
    }

    private static String randomFamilyName() {
        return RANDOM_FAMILY_NAMES[RANDOM.nextInt(RANDOM_FAMILY_NAMES.length)];
    }

    private static String normalizeGender(String gender) {
        if (gender == null || gender.isBlank()) return DEFAULT_GENDER;
        String g = gender.trim().substring(0, 1).toUpperCase();
        return (g.equals("M") || g.equals("F") || g.equals("O") || g.equals("U")) ? g : DEFAULT_GENDER;
    }

    private String resolvePlaceholderIdentifierTypeUuid() {
        String typeName = (placeholderIdentifierTypeName == null || placeholderIdentifierTypeName.isBlank())
                ? "national-id" : placeholderIdentifierTypeName;
        Map<String, String> cache = discoveredConfig.getIdentifierTypeUuids();
        if (cache != null && cache.get(typeName) != null) {
            return cache.get(typeName);
        }
        String ensured = configDiscovery.ensureIdentifierTypeExists(typeName);
        if (ensured != null) return ensured;
        // Fall back to the primary OpenMRS identifier type if the configured
        // placeholder type cannot be created or discovered.
        return discoveredConfig.getIdentifierTypeUuid();
    }

    private ObjectNode buildIdentifier(String value, String typeUuid, boolean preferred) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("identifier", value);
        node.set("identifierType", objectMapper.createObjectNode().put("uuid", typeUuid));
        node.put("preferred", preferred);
        if (discoveredConfig.getLocationUuid() != null) {
            node.set("location", objectMapper.createObjectNode().put("uuid", discoveredConfig.getLocationUuid()));
        }
        return node;
    }

    private String generateOpenMrsId() {
        String idgenSourceUuid = discoveredConfig.getIdgenSourceUuid();
        if (idgenSourceUuid == null || idgenSourceUuid.isBlank()) {
            // Try to discover the idgen source dynamically (mirrors FhirToRestTransformer)
            idgenSourceUuid = discoverIdgenSource();
            if (idgenSourceUuid == null) {
                log.warn("No idgen source configured or discoverable — cannot auto-generate OpenMRS ID for placeholder patient");
                return null;
            }
        }
        try {
            String response = restClient.post()
                    .uri("/idgen/identifiersource/{uuid}/identifier", idgenSourceUuid)
                    .header("Content-Type", "application/json")
                    .body("{}")
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(response).path("identifier").asText(null);
        } catch (Exception e) {
            log.error("Failed to generate OpenMRS ID for placeholder patient: {}", e.getMessage());
            return null;
        }
    }

    private String discoverIdgenSource() {
        try {
            String response = restClient.get()
                    .uri("/idgen/identifiersource?v=default")
                    .retrieve()
                    .body(String.class);
            JsonNode results = objectMapper.readTree(response).path("results");
            if (results.isArray()) {
                String primaryTypeName = discoveredConfig.getIdentifierTypeName();
                for (JsonNode source : results) {
                    String typeDisplay = source.path("identifierType").path("display").asText("");
                    if (primaryTypeName != null && typeDisplay.equalsIgnoreCase(primaryTypeName)) {
                        String uuid = source.path("uuid").asText(null);
                        if (uuid != null) {
                            discoveredConfig.setIdgenSourceUuid(uuid);
                            log.info("Discovered idgen source for '{}': {}", primaryTypeName, uuid);
                        }
                        return uuid;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to discover idgen source: {}", e.getMessage());
        }
        return null;
    }
}
