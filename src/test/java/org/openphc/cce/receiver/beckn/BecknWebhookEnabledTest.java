package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openphc.cce.receiver.config.OpenMrsConfigDiscovery;
import org.openphc.cce.receiver.exception.FhirParsingException;
import org.openphc.cce.receiver.exception.OpenMrsClientException;
import org.openphc.cce.receiver.model.ProcessingResponse;
import org.openphc.cce.receiver.model.RoutingResult;
import org.openphc.cce.receiver.service.InboundProcessingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.openphc.cce.receiver.beckn.BecknTestMessages.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Beckn endpoints with {@code receiver.beckn.enabled=true} and the bearer token on. The write
 * path is mocked; onix-bpp's caller is a WireMock server.
 */
@SpringBootTest(properties = {
        "receiver.beckn.enabled=true",
        "receiver.beckn.self.bpp-id=" + BPP_ID,
        "receiver.beckn.self.bpp-uri=https://openmrs.example.org/bpp/receiver",
        "receiver.beckn.self.network-id=net-from-settings",
        "receiver.beckn.spice.bap-id=" + BAP_ID,
        "receiver.beckn.spice.bap-uri=https://spice.example.org/bap/receiver",
        "receiver.beckn.spice.referrals-in.contract-id=" + CONTRACT_ID,
        "receiver.beckn.spice.referrals-in.transaction-id=" + TRANSACTION_ID,
        "cce.security.enabled=true",
        "cce.security.token=secret-token",
        "openmrs.notification.enabled=false"
})
@AutoConfigureMockMvc
class BecknWebhookEnabledTest {

    private static final WireMockServer ONIX = new WireMockServer(options().dynamicPort());

    static {
        ONIX.start();
    }

    @DynamicPropertySource
    static void onix(DynamicPropertyRegistry registry) {
        registry.add("receiver.beckn.onix.bpp-caller-url", () -> ONIX.baseUrl() + "/bpp/caller/");
    }

    @AfterAll
    static void stopOnix() {
        ONIX.stop();
    }

    private static final ObjectMapper OM = new ObjectMapper();

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry meters;

    @MockitoBean
    InboundProcessingService processingService;

    @MockitoBean
    OpenMrsConfigDiscovery discovery;

    @BeforeEach
    void setUp() {
        reset(processingService);
        ONIX.resetAll();
        ONIX.stubFor(WireMock.post(urlEqualTo("/bpp/caller/on_update"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":{\"ack\":{\"status\":\"ACK\"}}}")));
    }

    // ---- helpers ----

    private ResultActions bpp(String action, String body) throws Exception {
        return mvc.perform(post("/beckn/bpp-webhook/" + action)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Signature keyId=\"x\"")
                .content(body));
    }

    private ResultActions bap(String action, String body) throws Exception {
        return mvc.perform(post("/beckn/bap-webhook/" + action)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void writePathAnswers(RoutingResult... results) {
        when(processingService.process(any(), any(), any()))
                .thenReturn(ProcessingResponse.of(MESSAGE_ID, List.of(results)));
    }

    private static RoutingResult srCreated() {
        return RoutingResult.success("ServiceRequest", "order-uuid", "REST:/order", 201, "{}");
    }

    private static RoutingResult patientCreated() {
        return RoutingResult.success("Patient", "patient-uuid", "REST:/patient", 201, "{}");
    }

    /** Waits for the on_update to reach onix, then a little longer to see it is not sent twice. */
    private List<LoggedRequest> onUpdatesReceived(int expected) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until
                && ONIX.findAll(postRequestedFor(urlEqualTo("/bpp/caller/on_update"))).size() < expected) {
            Thread.sleep(20);
        }
        Thread.sleep(300);
        return ONIX.findAll(postRequestedFor(urlEqualTo("/bpp/caller/on_update")));
    }

    private double count(String name, String... tags) {
        var c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private void assertAck(ResultActions r) throws Exception {
        r.andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"))
                .andExpect(jsonPath("$.message.messageId").value(MESSAGE_ID))
                .andExpect(jsonPath("$.message.error").doesNotExist());
    }

    private ResultActions assertNack(ResultActions r, int http, String code) throws Exception {
        return r.andExpect(status().is(http))
                .andExpect(jsonPath("$.message.status").value("NACK"))
                .andExpect(jsonPath("$.message.messageId").value(MESSAGE_ID))
                .andExpect(jsonPath("$.message.error.code").value(code));
    }

    // ---- update: the answer table ----

    @Test
    void written_202_gives_ack_and_one_on_update_with_the_right_ids() throws Exception {
        writePathAnswers(patientCreated(), srCreated());
        double before = count("openmrs.beckn.messages.received", "action", "update", "result", "ack");

        assertAck(bpp("update", referral(srEntry(), patientEntry())));

        List<LoggedRequest> sent = onUpdatesReceived(1);
        assertThat(sent).hasSize(1);
        JsonNode m = OM.readTree(sent.get(0).getBodyAsString());
        JsonNode ctx = m.path("context");
        assertThat(ctx.path("action").asText()).isEqualTo("on_update");
        assertThat(ctx.path("messageId").asText()).isEqualTo(MESSAGE_ID);
        assertThat(ctx.path("transactionId").asText()).isEqualTo(TRANSACTION_ID);
        assertThat(ctx.path("bapId").asText()).isEqualTo(BAP_ID);
        assertThat(ctx.path("bapUri").asText()).isEqualTo("https://spice.example.org/bap/receiver");
        assertThat(ctx.path("bppId").asText()).isEqualTo(BPP_ID);
        assertThat(ctx.path("bppUri").asText()).isEqualTo("https://openmrs.example.org/bpp/receiver");
        assertThat(ctx.path("networkId").asText()).isEqualTo("net-from-settings");
        JsonNode contract = m.path("message").path("contract");
        assertThat(contract.path("id").asText()).isEqualTo(CONTRACT_ID);
        assertThat(contract.path("commitments").get(0).path("id").asText()).isEqualTo("referral");
        assertThat(contract.path("performance")).hasSize(1);
        assertThat(contract.path("performance").get(0).path("id").asText()).isEqualTo(SR_PERFORMANCE_ID);
        assertThat(contract.path("performance").get(0).path("status").path("code").asText()).isEqualTo("ACCEPTED");

        assertThat(count("openmrs.beckn.messages.received", "action", "update", "result", "ack")).isEqualTo(before + 1);
        long until = System.currentTimeMillis() + 2000;
        while (count("openmrs.beckn.on.update.sent", "result", "success") < 1 && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertThat(count("openmrs.beckn.on.update.sent", "result", "success")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void on_update_copies_the_request_network_id() throws Exception {
        writePathAnswers(srCreated());
        assertAck(bpp("update", message("update", CONTRACT_ID, "\"networkId\":\"net-from-request\"", srEntry())));
        List<LoggedRequest> sent = onUpdatesReceived(1);
        assertThat(sent).hasSize(1);
        assertThat(OM.readTree(sent.get(0).getBodyAsString()).path("context").path("networkId").asText())
                .isEqualTo("net-from-request");
    }

    @Test
    void partial_207_with_service_request_written_gives_ack() throws Exception {
        writePathAnswers(RoutingResult.failure("Patient", "REST:/patient", 400, "bad gender"), srCreated());
        assertAck(bpp("update", referral(srEntry(), patientEntry())));
        assertThat(onUpdatesReceived(1)).hasSize(1);
    }

    @Test
    void duplicate_referral_gives_ack_and_on_update_accepted() throws Exception {
        writePathAnswers(RoutingResult.duplicate("ServiceRequest", "existing-order", "REST:/order"));
        assertAck(bpp("update", referral(srEntry())));
        List<LoggedRequest> sent = onUpdatesReceived(1);
        assertThat(sent).hasSize(1);
        assertThat(OM.readTree(sent.get(0).getBodyAsString())
                .at("/message/contract/performance/0/status/code").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void all_failed_422_gives_referral_rejected_with_reason_and_no_on_update() throws Exception {
        writePathAnswers(RoutingResult.failure("ServiceRequest", "REST:/order", 400, "patient could not be resolved"));
        assertNack(bpp("update", referral(srEntry())), 400, "REFERRAL_REJECTED")
                .andExpect(jsonPath("$.message.error.message")
                        .value("ServiceRequest/1838: patient could not be resolved"));
        assertThat(onUpdatesReceived(0)).isEmpty();
    }

    @Test
    void partial_207_without_service_request_gives_referral_rejected() throws Exception {
        writePathAnswers(patientCreated(),
                RoutingResult.failure("ServiceRequest", "REST:/order", 400, "concept not found"));
        assertNack(bpp("update", referral(srEntry(), patientEntry())), 400, "REFERRAL_REJECTED")
                .andExpect(jsonPath("$.message.error.message").value("ServiceRequest/1838: concept not found"));
        assertThat(onUpdatesReceived(0)).isEmpty();
    }

    @Test
    void partial_207_with_transient_service_request_failure_gives_unavailable() throws Exception {
        writePathAnswers(patientCreated(),
                RoutingResult.failure("ServiceRequest", "REST:/order", 503, "Service Unavailable"));
        assertNack(bpp("update", referral(srEntry(), patientEntry())), 500, "UNAVAILABLE");
        assertThat(onUpdatesReceived(0)).isEmpty();
    }

    @Test
    void write_path_503_gives_unavailable_500() throws Exception {
        writePathAnswers(RoutingResult.failure("ServiceRequest", "REST:/order", 0, "Connection refused"));
        double before = count("openmrs.beckn.messages.received", "action", "update", "result", "unavailable");
        assertNack(bpp("update", referral(srEntry())), 500, "UNAVAILABLE");
        assertThat(count("openmrs.beckn.messages.received", "action", "update", "result", "unavailable"))
                .isEqualTo(before + 1);
        assertThat(onUpdatesReceived(0)).isEmpty();
    }

    @Test
    void write_path_exception_gives_unavailable_500() throws Exception {
        when(processingService.process(any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        assertNack(bpp("update", referral(srEntry())), 500, "UNAVAILABLE");
    }

    @Test
    void write_path_openmrs_client_error_502_gives_unavailable_500() throws Exception {
        when(processingService.process(any(), any(), any()))
                .thenThrow(new OpenMrsClientException("bad gateway", 502));
        assertNack(bpp("update", referral(srEntry())), 500, "UNAVAILABLE");
    }

    @Test
    void write_path_parse_error_400_gives_referral_rejected() throws Exception {
        when(processingService.process(any(), any(), any()))
                .thenThrow(new FhirParsingException("unreadable"));
        assertNack(bpp("update", referral(srEntry())), 400, "REFERRAL_REJECTED");
    }

    @Test
    void wrong_contract_gives_bad_request_and_never_reaches_the_write_path() throws Exception {
        double before = count("openmrs.beckn.contract.mismatch", "peer", BAP_ID);
        assertNack(bpp("update", message("update", "not-the-contract", null, srEntry())), 400, "BAD_REQUEST");
        assertThat(count("openmrs.beckn.contract.mismatch", "peer", BAP_ID)).isEqualTo(before + 1);
        verify(processingService, never()).process(any(), any(), any());
    }

    @Test
    void entry_without_resource_gives_bad_request() throws Exception {
        String noResource = "{\"id\":\"p-2\",\"performanceAttributes\":{\"eventType\":\"Patient\"}}";
        assertNack(bpp("update", referral(srEntry(), noResource)), 400, "BAD_REQUEST")
                .andExpect(jsonPath("$.message.error.message")
                        .value("performance[1].performanceAttributes.resource is missing"));
        verify(processingService, never()).process(any(), any(), any());
    }

    @Test
    void referral_without_service_request_gives_bad_request() throws Exception {
        assertNack(bpp("update", referral(patientEntry())), 400, "BAD_REQUEST");
        verify(processingService, never()).process(any(), any(), any());
    }

    @Test
    void context_action_must_match_the_path() throws Exception {
        assertNack(bpp("update", message("confirm", CONTRACT_ID, null, srEntry())), 400, "BAD_REQUEST");
        verify(processingService, never()).process(any(), any(), any());
    }

    @Test
    void other_actions_give_not_supported() throws Exception {
        for (String action : List.of("confirm", "status", "cancel", "select", "init", "track", "rate", "support")) {
            assertNack(bpp(action, message(action, CONTRACT_ID, null, srEntry())), 400, "NOT_SUPPORTED");
        }
        assertThat(count("openmrs.beckn.actions.unsupported", "action", "confirm")).isGreaterThanOrEqualTo(1);
        verify(processingService, never()).process(any(), any(), any());
    }

    @Test
    void body_that_is_not_json_gives_bad_request() throws Exception {
        bpp("update", "not json").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.status").value("NACK"))
                .andExpect(jsonPath("$.message.error.code").value("BAD_REQUEST"));
    }

    @Test
    void bundle_is_a_transaction_with_patient_first_and_ids_passed_on() throws Exception {
        writePathAnswers(patientCreated(), srCreated());
        // ServiceRequest sent first, Patient second.
        assertAck(bpp("update", referral(srEntry(), patientEntry())));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(processingService).process(payload.capture(), eq(MESSAGE_ID), eq(BAP_ID));
        JsonNode bundle = OM.readTree(payload.getValue());
        assertThat(bundle.path("resourceType").asText()).isEqualTo("Bundle");
        assertThat(bundle.path("type").asText()).isEqualTo("transaction");
        JsonNode entries = bundle.path("entry");
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).path("fullUrl").asText()).isEqualTo("Patient/1198503150001234");
        assertThat(entries.get(0).path("request").path("method").asText()).isEqualTo("POST");
        assertThat(entries.get(0).path("request").path("url").asText()).isEqualTo("Patient");
        assertThat(entries.get(0).path("resource")).isEqualTo(OM.readTree(PATIENT));
        assertThat(entries.get(1).path("fullUrl").asText()).isEqualTo("ServiceRequest/1838");
        assertThat(entries.get(1).path("resource")).isEqualTo(OM.readTree(SERVICE_REQUEST));
        onUpdatesReceived(1);
    }

    @Test
    void on_update_failure_does_not_change_the_ack() throws Exception {
        ONIX.resetAll();
        ONIX.stubFor(WireMock.post(urlEqualTo("/bpp/caller/on_update")).willReturn(aResponse().withStatus(500)));
        writePathAnswers(srCreated());
        double before = count("openmrs.beckn.on.update.sent", "result", "failed");

        assertAck(bpp("update", referral(srEntry())));

        assertThat(onUpdatesReceived(1)).hasSize(1);
        long until = System.currentTimeMillis() + 2000;
        while (count("openmrs.beckn.on.update.sent", "result", "failed") < before + 1
                && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertThat(count("openmrs.beckn.on.update.sent", "result", "failed")).isEqualTo(before + 1);
    }

    // ---- callbacks ----

    @Test
    void callbacks_on_update_and_on_status_are_acked() throws Exception {
        bap("on_update", callback("on_update", "ACCEPTED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"))
                .andExpect(jsonPath("$.message.messageId").value(MESSAGE_ID));
        bap("on_update", callback("on_update", "DUPLICATE")).andExpect(status().isOk());
        bap("on_status", callback("on_status", "COMPLETED")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"));
        assertThat(count("openmrs.beckn.callback.status", "action", "on_status", "status", "COMPLETED"))
                .isGreaterThanOrEqualTo(1);
        verify(processingService, never()).process(any(), any(), any());
    }

    @Test
    void other_callbacks_give_not_supported() throws Exception {
        bap("on_confirm", callback("on_confirm", "ACCEPTED")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.status").value("NACK"))
                .andExpect(jsonPath("$.message.error.code").value("NOT_SUPPORTED"));
        bap("update", callback("update", "ACCEPTED")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.error.code").value("NOT_SUPPORTED"));
    }

    // ---- security ----

    @Test
    void beckn_needs_no_token_with_security_on_but_api_still_does() throws Exception {
        writePathAnswers(srCreated());
        // No bearer token (onix sends "Authorization: Signature ...").
        assertAck(bpp("update", referral(srEntry())));
        bap("on_update", callback("on_update", "ACCEPTED")).andExpect(status().isOk());

        mvc.perform(post("/api/v1/openmrs/fhir").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/openmrs/fhir").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Signature keyId=\"x\""))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/openmrs/fhir").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Invalid token"));
        // A valid token passes the filter and the ".authenticated()" rule: neither 401 nor 403.
        mvc.perform(post("/api/v1/openmrs/fhir").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", "Bearer secret-token"))
                .andExpect(status().is(org.hamcrest.Matchers.not(401)))
                .andExpect(status().is(org.hamcrest.Matchers.not(403)));
        // No token at all is still refused.
        mvc.perform(post("/api/v1/openmrs/fhir").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        onUpdatesReceived(1);
    }
}
