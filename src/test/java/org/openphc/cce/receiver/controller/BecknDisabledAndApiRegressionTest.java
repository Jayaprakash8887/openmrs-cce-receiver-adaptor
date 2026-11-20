package org.openphc.cce.receiver.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openphc.cce.receiver.config.OpenMrsConfigDiscovery;
import org.openphc.cce.receiver.model.ProcessingResponse;
import org.openphc.cce.receiver.model.RoutingResult;
import org.openphc.cce.receiver.service.InboundProcessingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Beckn switched off ({@code receiver.beckn.enabled=false}): the Beckn endpoints do not exist, and
 * {@code /api/v1/openmrs/fhir} answers exactly as before the Beckn change (202 / 207 / 422 / 503).
 * Security is off here (the default); the bearer-token rule is tested in {@code BecknWebhookEnabledTest}.
 */
@SpringBootTest(properties = {"openmrs.notification.enabled=false", "receiver.beckn.enabled=false"})
@AutoConfigureMockMvc
class BecknDisabledAndApiRegressionTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @MockitoBean
    InboundProcessingService processingService;

    @MockitoBean
    OpenMrsConfigDiscovery discovery;

    @BeforeEach
    void setUp() {
        reset(processingService);
    }

    @Test
    void beckn_endpoints_do_not_exist_when_disabled() throws Exception {
        assertThat(context.getBeanNamesForType(BecknWebhookController.class)).isEmpty();
        mvc.perform(post("/beckn/bpp-webhook/update").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/beckn/bap-webhook/on_update").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        assertThat(context.getBeanNamesForType(SygnalController.class)).isEmpty();
        mvc.perform(post("/sygnal/signal/emit").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    private ResultActions postFhir() throws Exception {
        return mvc.perform(post("/api/v1/openmrs/fhir")
                .contentType("application/fhir+json")
                .header("X-Correlation-ID", "corr-1")
                .header("X-Source-System", "spice")
                .content("{\"resourceType\":\"Bundle\"}"));
    }

    private void answers(RoutingResult... results) {
        when(processingService.process(any(), eq("corr-1"), eq("spice")))
                .thenReturn(new ProcessingResponse("corr-1", results.length,
                        (int) List.of(results).stream().filter(r -> !"failed".equals(r.status())).count(),
                        (int) List.of(results).stream().filter(r -> "failed".equals(r.status())).count(),
                        Instant.parse("2026-11-15T10:00:00Z"), List.of(results)));
    }

    @Test
    void all_written_is_202_with_the_processing_response() throws Exception {
        answers(RoutingResult.success("ServiceRequest", "o-1", "REST:/order", 201, "{}"));
        postFhir().andExpect(status().isAccepted())
                .andExpect(jsonPath("$.bundleId").value("corr-1"))
                .andExpect(jsonPath("$.totalResources").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0))
                .andExpect(jsonPath("$.results[0].status").value("created"))
                .andExpect(jsonPath("$.results[0].resourceId").value("o-1"));
    }

    @Test
    void duplicate_only_is_202() throws Exception {
        answers(RoutingResult.duplicate("ServiceRequest", "o-1", "REST:/order"));
        postFhir().andExpect(status().isAccepted());
    }

    @Test
    void partial_is_207() throws Exception {
        answers(RoutingResult.success("Patient", "p-1", "REST:/patient", 201, "{}"),
                RoutingResult.failure("ServiceRequest", "REST:/order", 400, "bad"));
        postFhir().andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1));
    }

    @Test
    void all_failed_permanently_is_422() throws Exception {
        answers(RoutingResult.failure("ServiceRequest", "REST:/order", 400, "connection details invalid"));
        postFhir().andExpect(status().isUnprocessableEntity());
    }

    @Test
    void all_failed_with_a_transient_failure_is_503() throws Exception {
        answers(RoutingResult.failure("ServiceRequest", "REST:/order", 502, "Bad Gateway"));
        postFhir().andExpect(status().isServiceUnavailable());
        answers(RoutingResult.failure("ServiceRequest", "REST:/order", 429, "slow down"));
        postFhir().andExpect(status().isServiceUnavailable());
        answers(RoutingResult.failure("ServiceRequest", "N/A", 0, "Read timed out"));
        postFhir().andExpect(status().isServiceUnavailable());
        answers(RoutingResult.failure("ServiceRequest", "N/A", 0, null));
        postFhir().andExpect(status().isServiceUnavailable());
    }

    @Test
    void status_zero_without_a_transport_hint_is_422() throws Exception {
        answers(RoutingResult.failure("ServiceRequest", "N/A", 0, "mapping failed"));
        postFhir().andExpect(status().isUnprocessableEntity());
    }

    @Test
    void empty_bundle_is_202() throws Exception {
        answers();
        postFhir().andExpect(status().isAccepted()).andExpect(jsonPath("$.totalResources").value(0));
    }
}
