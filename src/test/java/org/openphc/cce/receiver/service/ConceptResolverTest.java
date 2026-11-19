package org.openphc.cce.receiver.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/** Name search and auto-create: a created concept must be found by the next request. */
class ConceptResolverTest {

    private static final String PARTIAL_MATCH =
            "{\"results\":[{\"uuid\":\"other\",\"display\":\"Consultation note\"}]}";
    private static final String EXACT_MATCH =
            "{\"results\":[{\"uuid\":\"concept-1\",\"display\":\"Consultation\"}]}";

    private WireMockServer wm;
    private ConceptResolver resolver;

    @BeforeEach
    void setUp() {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        resolver = new ConceptResolver(RestClient.builder().baseUrl(wm.baseUrl()).build());
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    private void searchAnswers(String body) {
        wm.stubFor(get(urlPathEqualTo("/concept")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    void a_created_concept_is_found_by_the_next_request_without_creating_it_again() {
        searchAnswers(PARTIAL_MATCH);
        wm.stubFor(post(urlPathEqualTo("/concept")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"uuid\":\"concept-1\"}")));

        // First referral: not found, so created.
        assertThat(resolver.searchConceptByName("Consultation")).isNull();
        assertThat(resolver.autoCreateConcept("Consultation")).isEqualTo("concept-1");

        // Second referral: found, no second create.
        assertThat(resolver.searchConceptByName("Consultation")).isEqualTo("concept-1");
        wm.verify(1, postRequestedFor(urlPathEqualTo("/concept")));
    }

    @Test
    void a_duplicate_name_on_create_falls_back_to_a_fresh_search() {
        // The cache says "not found", but the concept exists by now (another request, an earlier run).
        wm.stubFor(get(urlPathEqualTo("/concept")).inScenario("s").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(PARTIAL_MATCH))
                .willSetStateTo("created"));
        wm.stubFor(get(urlPathEqualTo("/concept")).inScenario("s").whenScenarioStateIs("created")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(EXACT_MATCH)));
        wm.stubFor(post(urlPathEqualTo("/concept")).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"['Consultation' is a duplicate name in locale 'en']\"}}")));

        assertThat(resolver.searchConceptByName("Consultation")).isNull();
        assertThat(resolver.autoCreateConcept("Consultation")).isEqualTo("concept-1");
        assertThat(resolver.searchConceptByName("Consultation")).isEqualTo("concept-1");
    }
}
