package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.openphc.cce.receiver.service.OpenMrsNotificationClient;
import org.openphc.cce.receiver.service.OpenMrsNotificationClient.NotificationPayload;
import org.openphc.cce.receiver.service.OpenMrsRestClient;
import org.openphc.cce.receiver.service.RecipientResolver;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CareAlertNotifierTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** context.messageId of the requests: CCE sets it to the delivery id. */
    static final String MESSAGE_ID = "7a7a7a7a-0000-4000-8000-000000000002";

    /** The message of a sygnal/signal/emit: the cce:CceCareAlert itself, with the alert of the earlier on_status tests. */
    static final String ALERT = """
            {"@context":"https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceCareAlert/v1.0/context.jsonld",
             "@type":"cce:CceCareAlert","patientId":"0123123123133","severity":"HIGH","stepStatus":"not-started",
             "slaStatus":"overdue","fhirVersion":"4.0.1",
             "resource":{"resourceType":"CommunicationRequest","status":"active","priority":"urgent",
               "identifier":[{"system":"http://openphc.org/fhir/intelligence-delivery-id","value":"9f2c1d4e-0000-4000-8000-000000000001"}],
               "subject":{"type":"Patient","identifier":{"system":"http://openphc.org/fhir/patient-upid","value":"0123123123133"}},
               "payload":[{"contentString":"ECG not ordered within 10 minutes of a Heart disease diagnosis"}],
               "extension":[{"url":"http://openphc.org/fhir/StructureDefinition/cce-protocol-step-id","valueString":"ecg-order"}]}}""";

    /** A Task alert: no delivery id identifier, so context.messageId is the delivery id. */
    static final String TASK_ALERT = """
            {"@context":"https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceCareAlert/v1.0/context.jsonld",
             "@type":"cce:CceCareAlert","patientId":"0123123123133","severity":"CRITICAL",
             "stepStatus":"not-started","slaStatus":"missed","fhirVersion":"4.0.1",
             "resource":{"resourceType":"Task","status":"requested","intent":"order",
               "description":"step anc-visit-2 missed"}}""";

    private OpenMrsNotificationClient notifications;
    private RecipientResolver recipients;
    private OpenMrsRestClient openMrs;
    private CareAlertNotifier notifier;

    @BeforeEach
    void setUp() {
        notifications = mock(OpenMrsNotificationClient.class);
        recipients = mock(RecipientResolver.class);
        openMrs = mock(OpenMrsRestClient.class);
        notifier = new CareAlertNotifier(notifications, recipients, openMrs);
        when(recipients.resolveForReferral(any())).thenReturn(List.of("user-1"));
    }

    /** A sygnal/signal/emit with this message, as SignalEmitService hands it over. */
    private static JsonNode request(String alertJson) throws Exception {
        return OM.readTree("{\"context\":{\"action\":\"sygnal/signal/emit\",\"messageId\":\"" + MESSAGE_ID
                + "\"},\"message\":" + alertJson + "}");
    }

    @Test
    void an_alert_for_a_known_patient_notifies_with_the_text_national_id_and_chart_link() throws Exception {
        when(openMrs.searchByIdentifier("/patient", "0123123123133")).thenReturn("patient-uuid");

        assertThat(notifier.notify(request(ALERT))).isTrue();

        // The resource's delivery id identifier comes before context.messageId.
        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifications).notify(payload.capture(), eq(List.of("user-1")),
                eq("CceCareAlert:9f2c1d4e-0000-4000-8000-000000000001"));
        NotificationPayload p = payload.getValue();
        assertThat(p.type()).isEqualTo("CCE_ALERT");
        assertThat(p.severity()).isEqualTo("WARNING");
        assertThat(p.title()).isEqualTo("ECG not ordered within 10 minutes of a Heart disease diagnosis");
        assertThat(p.body()).isEqualTo("Patient national-id 0123123123133 · Step status: not-started · SLA status: overdue · Step: ecg-order");
        assertThat(p.link()).isEqualTo("/openmrs/spa/patient/patient-uuid/chart");
    }

    @Test
    void an_alert_for_a_patient_not_in_openmrs_still_notifies_without_a_link() throws Exception {
        assertThat(notifier.notify(request(ALERT))).isTrue();

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifications).notify(payload.capture(), anyList(), anyString());
        assertThat(payload.getValue().body()).startsWith("Patient national-id 0123123123133 (not registered in OpenMRS)");
        assertThat(payload.getValue().link()).isNull();
    }

    @Test
    void a_task_alert_is_deduplicated_on_the_context_message_id() throws Exception {
        assertThat(notifier.notify(request(TASK_ALERT))).isTrue();

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifications).notify(payload.capture(), anyList(),
                eq("CceCareAlert:7a7a7a7a-0000-4000-8000-000000000002"));
        assertThat(payload.getValue().severity()).isEqualTo("CRITICAL");
        assertThat(payload.getValue().title()).isEqualTo("step anc-visit-2 missed");
        assertThat(payload.getValue().body()).contains("Step status: not-started · SLA status: missed");
    }

    @Test
    void without_slaStatus_the_body_has_no_sla_part() throws Exception {
        notifier.notify(request(ALERT.replace("\"slaStatus\":\"overdue\",", "")));

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifications).notify(payload.capture(), anyList(), anyString());
        assertThat(payload.getValue().body()).doesNotContain("SLA");
    }

    @Test
    void an_alert_without_patient_or_recipients_is_not_notified() throws Exception {
        assertThat(notifier.notify(request(ALERT.replace("\"patientId\":\"0123123123133\",", "")))).isFalse();
        when(recipients.resolveForReferral(any())).thenReturn(List.of());
        assertThat(notifier.notify(request(ALERT))).isFalse();
        verify(notifications, never()).notify(any(), anyList(), anyString());
    }

    @Test
    void severity_and_title_rules() throws Exception {
        assertThat(CareAlertNotifier.severity("LOW")).isEqualTo("INFO");
        assertThat(CareAlertNotifier.severity("medium")).isEqualTo("WARNING");
        assertThat(CareAlertNotifier.severity("CRITICAL")).isEqualTo("CRITICAL");
        assertThat(CareAlertNotifier.title(OM.readTree("{\"description\":\"Task text\"}"))).isEqualTo("Task text");
        assertThat(CareAlertNotifier.title(OM.readTree("{}"))).isEqualTo("Care coordination alert");
        assertThat(CareAlertNotifier.title(OM.readTree("{\"payload\":[{\"contentString\":\"" + "x".repeat(200) + "\"}]}")))
                .hasSize(120);
        assertThat(CareAlertNotifier.isCareAlert(OM.readTree(ALERT))).isTrue();
        assertThat(CareAlertNotifier.isCareAlert(OM.readTree("{\"@type\":\"cce:CceClinicalEvent\"}"))).isFalse();
        // The earlier places of the alert are not read any more: the signalAttributes of a sygnal:Signal,
        // and the performanceAttributes of a Beckn on_status.
        assertThat(CareAlertNotifier.isCareAlert(OM.readTree(
                "{\"@type\":\"sygnal:Signal\",\"signalAttributes\":{\"@type\":\"cce:CceCareAlert\"}}"))).isFalse();
        assertThat(CareAlertNotifier.isCareAlert(OM.readTree("{\"performanceAttributes\":{\"@type\":\"cce:CceCareAlert\"}}"))).isFalse();
    }
}
