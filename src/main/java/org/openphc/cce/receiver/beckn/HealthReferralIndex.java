package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where each confirmed health referral landed in OpenMRS, by coordination id, so that {@code status}
 * can find the order without the patient. In memory and bounded (the oldest entries go first): after
 * a restart, {@code status} falls back to finding the patient by the health id the request carries
 * (docs/beckn-integration.md §4A.4).
 */
@Component
@ConditionalOnBecknEnabled
public class HealthReferralIndex {

    static final int MAX_ENTRIES = 10_000;

    /**
     * @param patientUuid    the OpenMRS patient
     * @param orderUuid      the OpenMRS order (accession number = coordination id)
     * @param contract       the confirmed contract, echoed in {@code on_status}
     */
    public record Entry(String patientUuid, String orderUuid, JsonNode contract) {
    }

    private final Map<String, Entry> entries = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    public void put(String coordinationId, Entry entry) {
        entries.put(coordinationId, entry);
    }

    /** The entry, or null. */
    public Entry get(String coordinationId) {
        return entries.get(coordinationId);
    }
}
