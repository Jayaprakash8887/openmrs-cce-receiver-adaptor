package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openphc.cce.receiver.config.OpenMrsConfigDiscovery;
import org.openphc.cce.receiver.model.ProcessingResponse;
import org.openphc.cce.receiver.model.RoutingResult;
import org.openphc.cce.receiver.service.InboundProcessingService;
import org.openphc.cce.receiver.service.OpenMrsRestClient;
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
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.openphc.cce.receiver.beckn.BecknTestMessages.BAP_ID;
import static org.openphc.cce.receiver.beckn.BecknTestMessages.BPP_ID;
import static org.openphc.cce.receiver.beckn.BecknTestMessages.MESSAGE_ID;
import static org.openphc.cce.receiver.beckn.BecknTestMessages.TRANSACTION_ID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The health-referral flow (select / init / confirm / status) with a {@code hrf:HealthReferral}
 * contract, as Spice sends it to care-coordinator. The write path and the OpenMRS REST client are
 * mocked; onix-bpp's caller is a WireMock server.
 */
@SpringBootTest(properties = {
        "receiver.beckn.enabled=true",
        "receiver.beckn.self.bpp-id=" + BPP_ID,
        "receiver.beckn.self.bpp-uri=https://openmrs.example.org/bpp/receiver",
        "receiver.beckn.self.network-id=net-from-settings",
        "receiver.beckn.spice.bap-id=" + BAP_ID,
        "receiver.beckn.spice.bap-uri=https://spice.example.org/bap/receiver",
        "receiver.beckn.health-id-types=ABHA=national-id",
        "openmrs.notification.enabled=false"
})
@AutoConfigureMockMvc
class HealthReferralFlowTest {

    private static final WireMockServer ONIX = new WireMockServer(options().dynamicPort());
    private static final ObjectMapper OM = new ObjectMapper();
    private static final String ABHA = "07006101343534";

    static {
        ONIX.start();
    }

    @DynamicPropertySource
    static void onix(DynamicPropertyRegistry registry) {
        registry.add("receiver.beckn.onix.bpp-caller-url", () -> ONIX.baseUrl() + "/bpp/caller");
    }

    @AfterAll
    static void stopOnix() {
        ONIX.stop();
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    InboundProcessingService processingService;

    @MockitoBean
    OpenMrsRestClient openMrs;

    @MockitoBean
    OpenMrsConfigDiscovery discovery;

    @BeforeEach
    void setUp() {
        reset(processingService, openMrs);
        ONIX.resetAll();
        ONIX.stubFor(WireMock.post(WireMock.urlMatching("/bpp/caller/on_.*"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":{\"ack\":{\"status\":\"ACK\"}}}")));
    }

    // ---- messages ----

    private static final String PARTICIPANTS = """
            [{"id":"participant-asha-priya","descriptor":{"name":"Priya Devi","shortDesc":"ASHA worker"},
              "participantAttributes":{"@context":"https://schema.beckn.io/HealthParticipant/v2.1/context.jsonld",
                "@type":"hpa:HealthParticipant","participantRole":"FIELD_WORKER","primaryLanguage":"mr",
                "healthIds":[{"system":"HFR","value":"HFR-ASHA-MH-1234"}]}},
             {"id":"participant-patient","descriptor":{"name":"Rajesh dhoni","shortDesc":"Patient (Subject)"},
              "participantAttributes":{"@context":"https://schema.beckn.io/HealthParticipant/v2.1/context.jsonld",
                "@type":"hpa:HealthParticipant","participantRole":"PATIENT","primaryLanguage":"mr",
                "healthIds":[{"system":"ABHA","value":"%s"}]}}]""".formatted(ABHA);

    /** A Spice health-referral message (the shape Sahjad sent), with the given action and participants. */
    private static String health(String action, String coordinationId, String state, String participants) {
        return """
                {"context":{"networkId":"net-from-request","action":"%s","version":"2.0.0",
                  "bapId":"%s","bapUri":"https://spice.example.org/bap/receiver",
                  "transactionId":"%s","messageId":"%s","timestamp":"2026-11-18T12:37:24.012818396Z",
                  "bppId":"%s","bppUri":"https://openmrs.example.org/bpp/receiver"},
                 "message":{"contract":{"id":"%s","status":{"code":"%s"},
                   "commitments":[{"id":"commitment-anc-001","status":{"descriptor":{"code":"%s"}},
                     "resources":[{"id":"res-cc-anc","quantity":{"count":1}}],
                     "offer":{"id":"offer-cc-anc","resourceIds":["res-cc-anc"]}}],
                   "participants":%s,
                   "consideration":[{"id":"cons-cc-anc-001","status":{"code":"PENDING"},
                     "considerationAttributes":{"@context":"https://schema.beckn.io/HealthConsideration/v2.1/context.jsonld",
                       "@type":"hcn:HealthConsideration","pricePerUnit":50,"currency":"INR"}}],
                   "contractAttributes":{"@context":"https://schema.beckn.io/HealthReferral/v2.1/context.jsonld",
                     "@type":"hrf:HealthReferral","coordinationId":"%s","lifecycleState":"%s",
                     "clinicalUrgencyTier":"URGENT","healthServiceType":"PHYSICAL_CONSULTATION",
                     "targetCriteria":{"serviceCategory":{"code":"CONSULTATION","display":"Consultation"},
                       "procedureNeeds":["ANTENATAL_CHECKUP","BP_MEASUREMENT"],"consultationModality":"IN_PERSON"},
                     "schemeRef":{"code":"PMJAY","display":"Ayushman Bharat PM-JAY"},
                     "referralReason":"High BP - ANC Visit 1"}}}}"""
                .formatted(action, BAP_ID, TRANSACTION_ID, MESSAGE_ID, BPP_ID, coordinationId, state, state,
                        participants, coordinationId, state);
    }

    private static String health(String action, String coordinationId) {
        String state = "confirm".equals(action) ? "ACTIVE" : "DRAFT";
        return health(action, coordinationId, state, PARTICIPANTS);
    }

    /** A minimal status, as care-coordinator's sample: only the coordination id. */
    private static String minimalStatus(String coordinationId, String participants) {
        return """
                {"context":{"action":"status","version":"2.0.0","bapId":"%s",
                  "bapUri":"https://spice.example.org/bap/receiver","transactionId":"%s","messageId":"%s",
                  "timestamp":"2026-11-18T13:00:00Z","bppId":"%s","bppUri":"https://openmrs.example.org/bpp/receiver"},
                 "message":{"contract":{%s"contractAttributes":{
                   "@context":"https://schema.beckn.io/HealthReferral/v2.1/context.jsonld",
                   "@type":"hrf:HealthReferral","coordinationId":"%s"}}}}"""
                .formatted(BAP_ID, TRANSACTION_ID, MESSAGE_ID, BPP_ID,
                        participants == null ? "" : "\"participants\":" + participants + ",", coordinationId);
    }

    // ---- helpers ----

    private ResultActions bpp(String action, String body) throws Exception {
        return mvc.perform(post("/beckn/bpp-webhook/" + action)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static void assertAck(ResultActions r) throws Exception {
        r.andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"))
                .andExpect(jsonPath("$.message.messageId").value(MESSAGE_ID));
    }

    private static ResultActions assertNack(ResultActions r, int http, String code) throws Exception {
        return r.andExpect(status().is(http))
                .andExpect(jsonPath("$.message.status").value("NACK"))
                .andExpect(jsonPath("$.message.error.code").value(code));
    }

    /** Waits for the callbacks to reach onix, then a little longer to see no extra one comes. */
    private static List<JsonNode> callbacks(String action, int expected) throws Exception {
        String path = "/bpp/caller/" + action;
        long until = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < until
                && ONIX.findAll(postRequestedFor(urlEqualTo(path))).size() < expected) {
            Thread.sleep(20);
        }
        Thread.sleep(300);
        List<LoggedRequest> requests = ONIX.findAll(postRequestedFor(urlEqualTo(path)));
        return requests.stream().map(r -> {
            try {
                return OM.readTree(r.getBodyAsString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private void writePathAnswers(RoutingResult... results) {
        when(processingService.process(any(), any(), any()))
                .thenReturn(ProcessingResponse.of(MESSAGE_ID, List.of(results)));
    }

    private static RoutingResult patientCreated() {
        return RoutingResult.success("Patient", "patient-uuid", "REST:/patient", 201, "{}");
    }

    private static RoutingResult orderCreated(String uuid) {
        return RoutingResult.success("ServiceRequest", uuid, "REST:/order", 201, "{}");
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    // ---- select / init ----

    @Test
    void select_acks_and_sends_on_select_draft_echoing_the_contract() throws Exception {
        String id = newId();
        assertAck(bpp("select", health("select", id)));

        List<JsonNode> sent = callbacks("on_select", 1);
        assertThat(sent).hasSize(1);
        JsonNode m = sent.get(0);
        assertThat(m.at("/context/action").asText()).isEqualTo("on_select");
        assertThat(m.at("/context/bapId").asText()).isEqualTo(BAP_ID);
        assertThat(m.at("/context/bapUri").asText()).isEqualTo("https://spice.example.org/bap/receiver");
        assertThat(m.at("/context/bppId").asText()).isEqualTo(BPP_ID);
        assertThat(m.at("/context/transactionId").asText()).isEqualTo(TRANSACTION_ID);
        assertThat(m.at("/context/messageId").asText()).isEqualTo(MESSAGE_ID);
        assertThat(m.at("/context/networkId").asText()).isEqualTo("net-from-request");
        assertThat(m.at("/message/contract/id").asText()).isEqualTo(id);
        assertThat(m.at("/message/contract/status/code").asText()).isEqualTo("DRAFT");
        assertThat(m.at("/message/contract/commitments/0/status/descriptor/code").asText()).isEqualTo("DRAFT");
        assertThat(m.at("/message/contract/contractAttributes/lifecycleState").asText()).isEqualTo("DRAFT");
        assertThat(m.at("/message/contract/contractAttributes/coordinationId").asText()).isEqualTo(id);
        assertThat(m.at("/message/contract/participants/1/descriptor/name").asText()).isEqualTo("Rajesh dhoni");
        verify(processingService, never()).process(any(), any(), any());
    }

    @Test
    void init_acks_and_sends_on_init_draft() throws Exception {
        assertAck(bpp("init", health("init", newId())));

        List<JsonNode> sent = callbacks("on_init", 1);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).at("/context/action").asText()).isEqualTo("on_init");
        assertThat(sent.get(0).at("/message/contract/contractAttributes/lifecycleState").asText()).isEqualTo("DRAFT");
        verify(processingService, never()).process(any(), any(), any());
    }

    // ---- confirm ----

    @Test
    void confirm_writes_patient_and_referral_then_sends_on_confirm_active() throws Exception {
        String id = newId();
        writePathAnswers(patientCreated(), orderCreated("order-1"));

        assertAck(bpp("confirm", health("confirm", id)));

        ArgumentCaptor<String> bundle = ArgumentCaptor.forClass(String.class);
        verify(processingService).process(bundle.capture(), eq(MESSAGE_ID), eq(BAP_ID));
        JsonNode b = OM.readTree(bundle.getValue());
        assertThat(b.at("/type").asText()).isEqualTo("transaction");

        JsonNode patient = b.at("/entry/0/resource");
        assertThat(patient.at("/resourceType").asText()).isEqualTo("Patient");
        assertThat(patient.at("/id").asText()).isEqualTo(ABHA);
        assertThat(b.at("/entry/0/fullUrl").asText()).isEqualTo("Patient/" + ABHA);
        assertThat(patient.at("/identifier/0/system").asText()).isEqualTo("ABHA");
        assertThat(patient.at("/identifier/0/value").asText()).isEqualTo(ABHA);
        assertThat(patient.at("/identifier/0/type/text").asText()).isEqualTo("national-id");
        assertThat(patient.at("/name/0/given/0").asText()).isEqualTo("Rajesh");
        assertThat(patient.at("/name/0/family").asText()).isEqualTo("dhoni");
        assertThat(patient.at("/gender").asText()).isEqualTo("unknown");
        assertThat(patient.at("/birthDate").asText()).isEqualTo("1970-01-01");

        JsonNode sr = b.at("/entry/1/resource");
        assertThat(sr.at("/resourceType").asText()).isEqualTo("ServiceRequest");
        assertThat(sr.at("/id").asText()).isEqualTo(id);
        assertThat(sr.at("/identifier/0/system").asText()).isEqualTo(HealthReferralMapper.COORDINATION_ID_SYSTEM);
        assertThat(sr.at("/identifier/0/value").asText()).isEqualTo(id);
        assertThat(sr.at("/identifier/1/value").asText()).isEqualTo("Consultation");
        assertThat(sr.at("/category/0/coding/0/code").asText()).isEqualTo("3457005");
        assertThat(sr.at("/priority").asText()).isEqualTo("urgent");
        assertThat(sr.at("/status").asText()).isEqualTo("active");
        assertThat(sr.at("/intent").asText()).isEqualTo("order");
        assertThat(sr.at("/subject/reference").asText()).isEqualTo("Patient/" + ABHA);
        assertThat(sr.at("/subject/identifier/value").asText()).isEqualTo(ABHA);
        assertThat(sr.at("/authoredOn").asText()).isEqualTo("2026-11-18T12:37:24Z");
        assertThat(sr.at("/patientInstruction").asText()).isEqualTo("High BP - ANC Visit 1");
        assertThat(sr.at("/performer/0/reference").asText()).isEqualTo("Practitioner/HFR-ASHA-MH-1234");
        assertThat(sr.at("/performer/0/display").asText()).isEqualTo("Priya Devi");
        assertThat(sr.at("/note/0/text").asText())
                .contains("Procedures: ANTENATAL_CHECKUP, BP_MEASUREMENT")
                .contains("Scheme: Ayushman Bharat PM-JAY")
                .contains("Referred by: Priya Devi (FIELD_WORKER)");

        List<JsonNode> sent = callbacks("on_confirm", 1);
        assertThat(sent).hasSize(1);
        JsonNode m = sent.get(0);
        assertThat(m.at("/context/action").asText()).isEqualTo("on_confirm");
        assertThat(m.at("/message/contract/status/code").asText()).isEqualTo("ACTIVE");
        assertThat(m.at("/message/contract/contractAttributes/lifecycleState").asText()).isEqualTo("ACTIVE");
        assertThat(m.at("/message/contract/id").asText()).isEqualTo(id);
    }

    @Test
    void confirm_of_a_referral_already_in_openmrs_still_acks() throws Exception {
        writePathAnswers(patientCreated(), RoutingResult.duplicate("ServiceRequest", "order-dup", "REST:/order"));

        assertAck(bpp("confirm", health("confirm", newId())));

        assertThat(callbacks("on_confirm", 1)).hasSize(1);
    }

    @Test
    void confirm_rejected_by_the_write_path_gives_nack_and_no_on_confirm() throws Exception {
        writePathAnswers(RoutingResult.failure("Patient", "N/A", 400, "gender is required"),
                RoutingResult.failure("ServiceRequest", "N/A", 400, "no patient"));

        assertNack(bpp("confirm", health("confirm", newId())), 400, "REFERRAL_REJECTED")
                .andExpect(jsonPath("$.message.error.message").value(org.hamcrest.Matchers.containsString("gender")));

        assertThat(callbacks("on_confirm", 0)).isEmpty();
    }

    @Test
    void confirm_without_a_patient_participant_gives_bad_request() throws Exception {
        String onlyReferrer = """
                [{"id":"p1","descriptor":{"name":"Priya Devi"},
                  "participantAttributes":{"participantRole":"FIELD_WORKER","healthIds":[{"system":"HFR","value":"X"}]}}]""";
        assertNack(bpp("confirm", health("confirm", newId(), "ACTIVE", onlyReferrer)), 400, "BAD_REQUEST");
        verify(processingService, never()).process(any(), any(), any());
    }

    @Test
    void confirm_with_a_patient_without_health_id_gives_bad_request() throws Exception {
        String noIds = """
                [{"id":"p1","descriptor":{"name":"Rajesh dhoni"},"participantAttributes":{"participantRole":"PATIENT"}}]""";
        assertNack(bpp("confirm", health("confirm", newId(), "ACTIVE", noIds)), 400, "BAD_REQUEST");
        verify(processingService, never()).process(any(), any(), any());
    }

    // ---- sender and ids ----

    @Test
    void unknown_sender_gives_bad_request() throws Exception {
        String body = health("select", newId()).replace("\"bapId\":\"" + BAP_ID + "\"", "\"bapId\":\"bap.other.org\"");
        assertNack(bpp("select", body), 400, "BAD_REQUEST");
        assertThat(callbacks("on_select", 0)).isEmpty();
    }

    @Test
    void contract_without_coordination_id_or_id_gives_bad_request() throws Exception {
        String body = """
                {"context":{"action":"select","version":"2.0.0","bapId":"%s","transactionId":"%s","messageId":"%s"},
                 "message":{"contract":{"commitments":[{"id":"c"}],"contractAttributes":{"lifecycleState":"DRAFT"}}}}"""
                .formatted(BAP_ID, TRANSACTION_ID, MESSAGE_ID);
        assertNack(bpp("select", body), 400, "BAD_REQUEST");
    }

    // ---- status ----

    @Test
    void status_after_confirm_reports_the_order_state_with_the_confirmed_contract() throws Exception {
        String id = newId();
        writePathAnswers(patientCreated(), orderCreated("order-status"));
        assertAck(bpp("confirm", health("confirm", id)));
        callbacks("on_confirm", 1);
        when(openMrs.getOrderState("order-status"))
                .thenReturn(OM.readTree("{\"uuid\":\"order-status\",\"voided\":false,\"fulfillerStatus\":\"COMPLETED\"}"));

        assertAck(bpp("status", minimalStatus(id, null)));

        List<JsonNode> sent = callbacks("on_status", 1);
        assertThat(sent).hasSize(1);
        JsonNode m = sent.get(0);
        assertThat(m.at("/context/action").asText()).isEqualTo("on_status");
        assertThat(m.at("/message/contract/status/code").asText()).isEqualTo("COMPLETE");
        assertThat(m.at("/message/contract/contractAttributes/lifecycleState").asText()).isEqualTo("CLOSED");
        // The confirmed contract is echoed, not the sparse status request.
        assertThat(m.at("/message/contract/participants/1/descriptor/name").asText()).isEqualTo("Rajesh dhoni");
        assertThat(m.at("/message/contract/contractAttributes/targetCriteria/serviceCategory/code").asText())
                .isEqualTo("CONSULTATION");
    }

    @Test
    void status_after_a_restart_finds_the_order_by_the_patient_health_id() throws Exception {
        String id = newId();
        when(openMrs.searchByIdentifier("/patient", ABHA)).thenReturn("patient-uuid");
        when(openMrs.findOrderByAccessionNumber("patient-uuid", id)).thenReturn("order-found");
        when(openMrs.getOrderState("order-found"))
                .thenReturn(OM.readTree("{\"uuid\":\"order-found\",\"voided\":false}"));

        assertAck(bpp("status", minimalStatus(id, PARTICIPANTS)));

        List<JsonNode> sent = callbacks("on_status", 1);
        assertThat(sent).hasSize(1);
        JsonNode m = sent.get(0);
        assertThat(m.at("/message/contract/status/code").asText()).isEqualTo("ACTIVE");
        assertThat(m.at("/message/contract/contractAttributes/lifecycleState").asText()).isEqualTo("ACTIVE");
        // A minimal request gets what the schemas require: a commitment and targetCriteria.
        assertThat(m.at("/message/contract/commitments/0/id").asText()).isNotBlank();
        assertThat(m.at("/message/contract/contractAttributes/targetCriteria").isObject()).isTrue();
        assertThat(m.at("/message/contract/id").asText()).isEqualTo(id);
    }

    @Test
    void status_of_an_unknown_referral_acks_and_sends_no_on_status() throws Exception {
        assertAck(bpp("status", minimalStatus(newId(), null)));

        assertThat(callbacks("on_status", 0)).isEmpty();
        verify(openMrs, never()).getOrderState(anyString());
    }

    @Test
    void cancelled_order_reports_cancelled() throws Exception {
        assertThat(HealthReferralService.state(OM.readTree("{\"voided\":true}")))
                .containsExactly("CANCELLED", "CANCELLED");
        assertThat(HealthReferralService.state(OM.readTree("{\"action\":\"DISCONTINUE\"}")))
                .containsExactly("CANCELLED", "CANCELLED");
        assertThat(HealthReferralService.state(OM.readTree("{\"dateStopped\":\"2026-11-19T10:00:00.000+0000\"}")))
                .containsExactly("COMPLETE", "CLOSED");
        assertThat(HealthReferralService.state(OM.readTree("{\"dateStopped\":null}")))
                .containsExactly("ACTIVE", "ACTIVE");
    }
}
