package org.openphc.cce.receiver.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/** The identifier search must reach OpenMRS at "/patient", not "%2Fpatient". */
class OpenMrsRestClientTest {

    private WireMockServer wm;
    private OpenMrsRestClient client;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        client = new OpenMrsRestClient(RestClient.builder().baseUrl(wm.baseUrl() + "/ws/rest/v1").build());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void search_by_identifier_finds_the_existing_patient() {
        wm.stubFor(get(urlPathEqualTo("/ws/rest/v1/patient"))
                .willReturn(okJson("{\"results\":[{\"uuid\":\"patient-1\",\"identifiers\":[{\"identifier\":\"07006101343535\"}]}]}")));

        assertThat(client.searchByIdentifier("/patient", "07006101343535")).isEqualTo("patient-1");
        wm.verify(getRequestedFor(urlPathEqualTo("/ws/rest/v1/patient")));
    }

    @Test
    void a_patient_whose_identifier_only_contains_the_value_is_not_a_match() {
        // OpenMRS returns partial matches: searching 0123456789 also finds 0101234567890.
        wm.stubFor(get(urlPathEqualTo("/ws/rest/v1/patient")).willReturn(okJson("""
                {"results":[
                  {"uuid":"nifty","identifiers":[{"identifier":"0101234567890"}]},
                  {"uuid":"rufiii","identifiers":[{"identifier":"07006101343538"},{"identifier":"0123456789"}]}]}""")));

        assertThat(client.searchByIdentifier("/patient", "0123456789")).isEqualTo("rufiii");
        assertThat(client.searchByIdentifier("/patient", "1234567")).isNull();
    }

    @Test
    void a_voided_identifier_is_not_a_match() {
        wm.stubFor(get(urlPathEqualTo("/ws/rest/v1/patient")).willReturn(okJson("""
                {"results":[{"uuid":"p1","identifiers":[{"identifier":"0123456789","voided":true}]}]}""")));

        assertThat(client.searchByIdentifier("/patient", "0123456789")).isNull();
    }
}
