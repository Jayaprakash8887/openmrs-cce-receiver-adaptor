package org.openphc.cce.receiver.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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
                .willReturn(okJson("{\"results\":[{\"uuid\":\"patient-1\"}]}")));

        assertThat(client.searchByIdentifier("/patient", "07006101343535")).isEqualTo("patient-1");
        wm.verify(getRequestedFor(urlEqualTo("/ws/rest/v1/patient?identifier=07006101343535&v=default")));
    }
}
