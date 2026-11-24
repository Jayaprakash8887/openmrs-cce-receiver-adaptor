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

    /** context.messageId of the requests. CCE sets it to the delivery id, as it does message.id. */
    static final String MESSAGE_ID = "7a7a7a7a-0000-4000-8000-000000000002";

    /** message.id of the alerts. It differs from MESSAGE_ID here, to tell the two apart. */
    static final String SIGNAL_ID = "5b5b5b5b-0000-4000-8000-000000000003";

    /**
     * The message of a sygnal/signal/emit: a sygnal:Signal of the CCE profile v3.0 with the care-alert
     * fields directly on it, here the alert of the earlier on_status tests.
     */
    static final String ALERT = """
            {"@type":"sygnal:Signal","id":"5b5b5b5b-0000-4000-8000-000000000003","signalType":"SLA_BREACH",
             "target":{"id":"bap.openmrs.nextsteps.mdtlabs.org","role":"PROVIDER"},
             "basis":"CCE care step status: not-started, SLA status: overdue","issuedAt":"2026-11-20T10:00:00.123Z",
             "cceSeverity":"HIGH",
             "patientId":"0123123123133","stepStatus":"not-started","slaStatus":"overdue","fhirVersion":"4.0.1",
             "resource":{"resourceType":"CommunicationRequest","status":"active","priority":"urgent",
               "identifier":[{"system":"http://openphc.org/fhir/intelligence-delivery-id","value":"9f2c1d4e-0000-4000-8000-000000000001"}],
               "subject":{"type":"Patient","identifier":{"system":"http://openphc.org/fhir/patient-upid","value":"0123123123133"}},
               "payload":[{"contentString":"ECG not ordered within 10 minutes of a Heart disease diagnosis"}],
               "extension":[{"url":"http://openphc.org/fhir/StructureDefinition/cce-protocol-step-id","valueString":"ecg-order"}]}}""";

    /** A Task alert: no delivery id identifier, so message.id is the delivery id. */
    static final String TASK_ALERT = """
            {"@type":"sygnal:Signal","id":"5b5b5b5b-0000-4000-8000-000000000003","signalType":"SLA_BREACH",
             "target":{"id":"bap.openmrs.nextsteps.mdtlabs.org","role":"PROVIDER"},
             "basis":"CCE care step status: not-started, SLA status: missed","issuedAt":"2026-11-20T10:00:00.123Z",
             "cceSeverity":"CRITICAL",
             "patientId":"0123123123133","stepStatus":"not-started","slaStatus":"missed","fhirVersion":"4.0.1",
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

        // The resource's delivery id identifier comes before message.id and context.messageId.
        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifications).notify(payload.capture(), eq(List.of("user-1")),
                eq("SygnalSignal:9f2c1d4e-0000-4000-8000-000000000001"));
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
    void a_task_alert_is_deduplicated_on_the_signal_id() throws Exception {
        assertThat(notifier.notify(request(TASK_ALERT))).isTrue();

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifications).notify(payload.capture(), anyList(), eq("SygnalSignal:" + SIGNAL_ID));
        assertThat(payload.getValue().severity()).isEqualTo("CRITICAL");
        assertThat(payload.getValue().title()).isEqualTo("step anc-visit-2 missed");
        assertThat(payload.getValue().body()).contains("Step status: not-started · SLA status: missed");
    }

    @Test
    void without_a_signal_id_the_context_message_id_is_the_deduplication_key() throws Exception {
        // SignalEmitService refuses a Signal without an id; this is the notifier's last resort.
        assertThat(notifier.notify(request(TASK_ALERT.replace("\"id\":\"" + SIGNAL_ID + "\",", "")))).isTrue();

        verify(notifications).notify(any(), anyList(), eq("SygnalSignal:" + MESSAGE_ID));
    }

    @Test
    void a_signalAttributes_bag_is_not_read() throws Exception {
        // Profile v1.0 had the alert in this bag. Its values must not replace the Signal's own, and its
        // severity is not CCE's level: without cceSeverity the notification is INFO.
        String withBag = ALERT.replace("\"cceSeverity\":\"HIGH\",", """
                "signalAttributes":{"@type":"cce:CceCareAlert","patientId":"from-the-bag","severity":"CRITICAL",
                  "stepStatus":"completed","resource":{"resourceType":"Task","description":"from the bag"}},""");
        assertThat(notifier.notify(request(withBag))).isTrue();

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifications).notify(payload.capture(), anyList(), anyString());
        assertThat(payload.getValue().severity()).isEqualTo("INFO");
        assertThat(payload.getValue().title()).isEqualTo("ECG not ordered within 10 minutes of a Heart disease diagnosis");
        assertThat(payload.getValue().body()).startsWith("Patient national-id 0123123123133 (not registered in OpenMRS) · Step status: not-started");

        // A Signal with the alert only in the bag has no patient: nothing is notified.
        assertThat(notifier.notify(request(
                "{\"@type\":\"sygnal:Signal\",\"id\":\"" + SIGNAL_ID + "\",\"signalAttributes\":" + withBag + "}"))).isFalse();
        verify(notifications).notify(any(), anyList(), anyString());
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
        // CCE's level is only cceSeverity. Sygnal's severity and a signalAttributes bag are not read.
        assertThat(CareAlertNotifier.cceSeverity(OM.readTree("{\"cceSeverity\":\"CRITICAL\"}"))).isEqualTo("CRITICAL");
        assertThat(CareAlertNotifier.cceSeverity(OM.readTree(
                "{\"signalAttributes\":{\"@type\":\"cce:CareAlertAttributes\",\"severity\":\"CRITICAL\"}}"))).isEmpty();
        assertThat(CareAlertNotifier.cceSeverity(OM.readTree("{\"severity\":\"WARNING\"}"))).isEmpty();
        assertThat(CareAlertNotifier.severity("LOW")).isEqualTo("INFO");
        assertThat(CareAlertNotifier.severity("medium")).isEqualTo("WARNING");
        assertThat(CareAlertNotifier.severity("CRITICAL")).isEqualTo("CRITICAL");
        assertThat(CareAlertNotifier.title(OM.readTree("{\"description\":\"Task text\"}"))).isEqualTo("Task text");
        assertThat(CareAlertNotifier.title(OM.readTree("{}"))).isEqualTo("Care coordination alert");
        assertThat(CareAlertNotifier.title(OM.readTree("{\"payload\":[{\"contentString\":\"" + "x".repeat(200) + "\"}]}")))
                .hasSize(120);
        assertThat(CareAlertNotifier.isSignal(OM.readTree(ALERT))).isTrue();
        // The message of profile v2.0, and the performanceAttributes of a Beckn on_status before that.
        assertThat(CareAlertNotifier.isSignal(OM.readTree("{\"@type\":\"cce:CceCareAlert\"}"))).isFalse();
        assertThat(CareAlertNotifier.isSignal(OM.readTree("{\"@type\":\"sygnal:CoordinationEvent\"}"))).isFalse();
        assertThat(CareAlertNotifier.isSignal(OM.readTree("{\"performanceAttributes\":{\"@type\":\"sygnal:Signal\"}}"))).isFalse();
        assertThat(CareAlertNotifier.isSignal(null)).isFalse();
    }
}
