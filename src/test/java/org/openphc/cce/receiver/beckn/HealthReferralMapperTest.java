package org.openphc.cce.receiver.beckn;

import org.junit.jupiter.api.Test;
import org.openphc.cce.receiver.config.BecknProperties;

import static org.assertj.core.api.Assertions.assertThat;

/** The small mapping rules of {@link HealthReferralMapper} and the health id type setting. */
class HealthReferralMapperTest {

    @Test
    void gender_maps_the_health_participant_enum() {
        assertThat(HealthReferralMapper.gender("MALE")).isEqualTo("male");
        assertThat(HealthReferralMapper.gender("female")).isEqualTo("female");
        assertThat(HealthReferralMapper.gender("OTHER")).isEqualTo("other");
        assertThat(HealthReferralMapper.gender("PREFER_NOT_TO_SAY")).isEqualTo("unknown");
        assertThat(HealthReferralMapper.gender("")).isEqualTo("unknown");
    }

    @Test
    void urgency_tier_maps_to_fhir_priority() {
        assertThat(HealthReferralMapper.priority("ROUTINE")).isEqualTo("routine");
        assertThat(HealthReferralMapper.priority("URGENT")).isEqualTo("urgent");
        assertThat(HealthReferralMapper.priority("EMERGENCY")).isEqualTo("stat");
        assertThat(HealthReferralMapper.priority("")).isEqualTo("routine");
    }

    @Test
    void fhir_id_keeps_allowed_characters_and_length() {
        assertThat(HealthReferralMapper.fhirId("91-1234-5678-9012", "x")).isEqualTo("91-1234-5678-9012");
        assertThat(HealthReferralMapper.fhirId("ABC 12/34", "x")).isEqualTo("ABC-12-34");
        assertThat(HealthReferralMapper.fhirId("", "participant-1")).isEqualTo("participant-1");
        assertThat(HealthReferralMapper.fhirId("a".repeat(80), "x")).hasSize(64);
    }

    @Test
    void health_id_types_map_systems_ignoring_case_and_default_to_the_system() {
        BecknProperties props = new BecknProperties(true, null, null, null, "ABHA=national-id; hfr = hfr-id", null);
        assertThat(props.identifierTypeFor("ABHA")).isEqualTo("national-id");
        assertThat(props.identifierTypeFor("abha")).isEqualTo("national-id");
        assertThat(props.identifierTypeFor("HFR")).isEqualTo("hfr-id");
        assertThat(props.identifierTypeFor("NHID")).isEqualTo("NHID");
        assertThat(new BecknProperties(true, null, null, null, null, null).identifierTypeFor("ABHA")).isEqualTo("ABHA");
    }

    @Test
    void authored_on_is_cut_to_the_second() {
        assertThat(HealthReferralService.authoredOn("2026-11-18T12:37:24.012818396Z")).isEqualTo("2026-11-18T12:37:24Z");
        assertThat(HealthReferralService.authoredOn("not a time")).isEmpty();
        assertThat(HealthReferralService.authoredOn("")).isEmpty();
    }
}
