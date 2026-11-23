package org.openphc.cce.receiver.beckn;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openphc.cce.receiver.config.OpenMrsConfigDiscovery;
import org.openphc.cce.receiver.service.InboundProcessingService;
import org.openphc.cce.receiver.service.OpenMrsNotificationClient;
import org.openphc.cce.receiver.service.OpenMrsNotificationClient.NotificationPayload;
import org.openphc.cce.receiver.service.OpenMrsRestClient;
import org.openphc.cce.receiver.service.RecipientResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.openphc.cce.receiver.beckn.SygnalTestMessages.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /sygnal/signal/emit} with {@code receiver.beckn.enabled=true}, the Sygnal ids from application.yml
 * and the bearer token on. OpenMRS is mocked; the care alert notifier runs for real.
 */
@SpringBootTest(properties = {
        "receiver.beckn.enabled=true",
        // The Spice referral settings are required as well when the endpoints are on.
        "receiver.beckn.self.bpp-id=bpp.openmrs.example.org",
        "receiver.beckn.self.bpp-uri=https://openmrs.example.org/bpp/receiver",
        "receiver.beckn.spice.bap-id=bap.spice.example.org",
        "receiver.beckn.spice.bap-uri=https://spice.example.org/bap/receiver",
        "cce.security.enabled=true",
        "cce.security.token=secret-token"
})
@AutoConfigureMockMvc
class SignalEmitEndpointTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry meters;

    @MockitoBean
    InboundProcessingService processingService;

    @MockitoBean
    OpenMrsConfigDiscovery discovery;

    @MockitoBean
    OpenMrsNotificationClient notifications;

    @MockitoBean
    RecipientResolver recipients;

    @MockitoBean
    OpenMrsRestClient openMrs;

    @BeforeEach
    void setUp() {
        reset(notifications, recipients, openMrs);
        when(recipients.resolveForReferral(any())).thenReturn(List.of("user-1"));
    }

    /** As onix-openmrs forwards it: no bearer token, but the Sygnal "Authorization: Signature ...". */
    private ResultActions emit(String body) throws Exception {
        return mvc.perform(post("/sygnal/signal/emit")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Signature keyId=\"bpp.cce.nextsteps.mdtlabs.org|k1|ed25519\"")
                .content(body));
    }

    private ResultActions expectNack(ResultActions answer, int status, String code) throws Exception {
        return answer.andExpect(status().is(status))
                .andExpect(jsonPath("$.message.status").value("NACK"))
                .andExpect(jsonPath("$.message.messageId").value(DELIVERY_ID))
                .andExpect(jsonPath("$.message.error.code").value(code))
                .andExpect(jsonPath("$.message.error.message").isNotEmpty());
    }

    private double count(String name, String... tags) {
        var c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void a_care_alert_is_acked_and_notified() throws Exception {
        when(openMrs.searchByIdentifier("/patient", PATIENT_ID)).thenReturn("patient-uuid");
        double before = count("openmrs.beckn.care.alerts", "result", "notified");

        emit(signalEmit()).andExpect(status().isOk())
                .andExpect(jsonPath("$.message.status").value("ACK"))
                .andExpect(jsonPath("$.message.messageId").value(DELIVERY_ID))
                .andExpect(jsonPath("$.message.error").doesNotExist());

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifications, timeout(3000)).notify(payload.capture(), eq(List.of("user-1")),
                eq("CceCareAlert:" + DELIVERY_ID));
        NotificationPayload p = payload.getValue();
        assertThat(p.type()).isEqualTo("CCE_ALERT");
        assertThat(p.severity()).isEqualTo("WARNING");
        assertThat(p.title()).isEqualTo("[HIGH] step anc-visit-2 overdue");
        assertThat(p.body()).isEqualTo("Patient national-id " + PATIENT_ID
                + " · Step status: not-started · SLA status: overdue");
        assertThat(p.link()).isEqualTo("/openmrs/spa/patient/patient-uuid/chart");

        long until = System.currentTimeMillis() + 3000;
        while (count("openmrs.beckn.care.alerts", "result", "notified") < before + 1
                && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }
        assertThat(count("openmrs.beckn.care.alerts", "result", "notified")).isEqualTo(before + 1);
        assertThat(count("openmrs.beckn.messages.received", "action", SignalEmitService.ACTION, "result", "ack"))
                .isGreaterThanOrEqualTo(1);
        verify(processingService, never()).process(any(), any(), any());
    }

    @Test
    void a_repeated_alert_is_acked_with_the_same_deduplication_key() throws Exception {
        emit(signalEmit()).andExpect(status().isOk());
        emit(signalEmit()).andExpect(status().isOk());
        // OpenMrsNotificationClient drops the second one on this key.
        verify(notifications, timeout(3000).times(2)).notify(any(), anyList(), eq("CceCareAlert:" + DELIVERY_ID));
    }

    @Test
    void an_alert_for_another_receiver_is_refused() throws Exception {
        expectNack(emit(signalEmit(SignalEmitService.ACTION, CCE_ID, "bap.openhim.nextsteps.mdtlabs.org", careAlert())),
                400, "CTX_INVALID_FIELD");
        verify(notifications, after(200).never()).notify(any(), anyList(), anyString());
    }

    @Test
    void an_alert_from_another_sender_is_refused() throws Exception {
        expectNack(emit(signalEmit(SignalEmitService.ACTION, "bap.openhim.nextsteps.mdtlabs.org", SELF_ID, careAlert())),
                400, "CTX_INVALID_FIELD");
        verify(notifications, after(200).never()).notify(any(), anyList(), anyString());
    }

    @Test
    void missing_ids_are_refused() throws Exception {
        expectNack(emit(signalEmit(SignalEmitService.ACTION, "", SELF_ID, careAlert())), 400, "CTX_MISSING_FIELD");
        expectNack(emit(signalEmit(SignalEmitService.ACTION, CCE_ID, "", careAlert())), 400, "CTX_MISSING_FIELD");
    }

    @Test
    void another_action_is_refused() throws Exception {
        expectNack(emit(signalEmit("sygnal/event/report", CCE_ID, SELF_ID, careAlert())), 400, "CTX_ACTION_MISMATCH");
        expectNack(emit(signalEmit("on_status", CCE_ID, SELF_ID, careAlert())), 400, "CTX_ACTION_MISMATCH");
    }

    @Test
    void the_old_signal_shape_is_refused() throws Exception {
        // Before 2026-11-23 the message was a sygnal:Signal with the alert in signalAttributes.
        expectNack(emit(signalEmit(SignalEmitService.ACTION, CCE_ID, SELF_ID, oldSignal())), 400, "SCH_TYPE_NOT_SUPPORTED")
                .andExpect(jsonPath("$.message.error.message").value("message.@type must be cce:CceCareAlert"));
        verify(notifications, after(200).never()).notify(any(), anyList(), anyString());
    }

    @Test
    void other_types_are_refused() throws Exception {
        expectNack(emit(signalEmit(SignalEmitService.ACTION, CCE_ID, SELF_ID,
                careAlert().replace("\"cce:CceCareAlert\"", "\"cce:CceClinicalEvent\""))), 400, "SCH_TYPE_NOT_SUPPORTED");
        expectNack(emit(signalEmit(SignalEmitService.ACTION, CCE_ID, SELF_ID,
                careAlert().replace("\"@type\":\"cce:CceCareAlert\",", ""))), 400, "SCH_TYPE_NOT_SUPPORTED");
        verify(notifications, after(200).never()).notify(any(), anyList(), anyString());
    }

    @Test
    void an_alert_without_message_patient_or_resource_is_refused() throws Exception {
        expectNack(emit(signalEmit(SignalEmitService.ACTION, CCE_ID, SELF_ID, "null")), 400, "SCH_REQUIRED_FIELD_MISSING");
        expectNack(emit(signalEmit(SignalEmitService.ACTION, CCE_ID, SELF_ID,
                careAlert().replace("\"patientId\":\"" + PATIENT_ID + "\",", ""))), 400, "SCH_REQUIRED_FIELD_MISSING");
        expectNack(emit(signalEmit(SignalEmitService.ACTION, CCE_ID, SELF_ID,
                careAlert().replace("\"resource\":", "\"notTheResource\":"))), 400, "SCH_REQUIRED_FIELD_MISSING");
        expectNack(emit(signalEmit(SignalEmitService.ACTION, CCE_ID, SELF_ID,
                careAlert().replace("\"resourceType\":\"CommunicationRequest\",", ""))), 400, "SCH_REQUIRED_FIELD_MISSING");
        verify(notifications, after(200).never()).notify(any(), anyList(), anyString());
    }

    @Test
    void a_task_alert_is_deduplicated_on_the_context_message_id() throws Exception {
        // A Task has no delivery id identifier: the key is context.messageId.
        String task = """
                {"@context":"https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceCareAlert/v1.0/context.jsonld",
                 "@type":"cce:CceCareAlert","patientId":"%s","severity":"CRITICAL","stepStatus":"not-started",
                 "slaStatus":"missed","fhirVersion":"4.0.1",
                 "resource":{"resourceType":"Task","status":"requested","intent":"order",
                   "description":"step anc-visit-2 missed"}}""".formatted(PATIENT_ID);

        emit(signalEmit(SignalEmitService.ACTION, CCE_ID, SELF_ID, task)).andExpect(status().isOk());

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifications, timeout(3000)).notify(payload.capture(), anyList(), eq("CceCareAlert:" + DELIVERY_ID));
        assertThat(payload.getValue().severity()).isEqualTo("CRITICAL");
        assertThat(payload.getValue().title()).isEqualTo("step anc-visit-2 missed");
    }

    @Test
    void a_body_that_is_not_a_json_object_is_refused() throws Exception {
        for (String body : new String[]{"not json", "[]", ""}) {
            emit(body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message.status").value("NACK"))
                    .andExpect(jsonPath("$.message.messageId").isNotEmpty())
                    .andExpect(jsonPath("$.message.error.code").value("SCH_INVALID_JSON"));
        }
    }

    @Test
    void the_spice_beckn_endpoints_stay_next_to_it() throws Exception {
        // A care alert is not a Beckn message: the bap-webhook does not take it as an alert any more.
        mvc.perform(post("/beckn/bap-webhook/sygnal/signal/emit").contentType(MediaType.APPLICATION_JSON)
                        .content(signalEmit()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/beckn/bpp-webhook/cancel").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message.error.code").value("NOT_SUPPORTED"));
        verify(notifications, after(200).never()).notify(any(), anyList(), anyString());
    }

    @Test
    void sygnal_needs_no_token_with_security_on_but_api_still_does() throws Exception {
        emit(signalEmit()).andExpect(status().isOk());

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
    }
}
