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

    /** A performance entry as cce-beckn-service sends it in on_status. */
    static final String ALERT_ENTRY = """
            {"id":"9f2c1d4e-0000-4000-8000-000000000001","status":{"code":"OVERDUE"},
             "performanceAttributes":{
               "@context":"https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceCareAlert/v1.0/context.jsonld",
               "@type":"cce:CceCareAlert","patientId":"0123123123133","severity":"HIGH","stepStatus":"overdue",
               "fhirVersion":"4.0.1",
               "resource":{"resourceType":"CommunicationRequest","status":"active","priority":"urgent",
                 "identifier":[{"system":"http://openphc.org/fhir/intelligence-delivery-id","value":"9f2c1d4e-0000-4000-8000-000000000001"}],
                 "subject":{"type":"Patient","identifier":{"system":"http://openphc.org/fhir/patient-upid","value":"0123123123133"}},
                 "payload":[{"contentString":"ECG not ordered within 10 minutes of a Heart disease diagnosis"}],
                 "extension":[{"url":"http://openphc.org/fhir/StructureDefinition/cce-protocol-step-id","valueString":"ecg-order"}]}}}""";

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

    private static JsonNode entry(String json) throws Exception {
        return OM.readTree(json);
    }

    @Test
    void an_alert_for_a_known_patient_notifies_with_the_text_national_id_and_chart_link() throws Exception {
        when(openMrs.searchByIdentifier("/patient", "0123123123133")).thenReturn("patient-uuid");

        assertThat(notifier.notify(entry(ALERT_ENTRY))).isTrue();

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifications).notify(payload.capture(), eq(List.of("user-1")),
                eq("CceCareAlert:9f2c1d4e-0000-4000-8000-000000000001"));
        NotificationPayload p = payload.getValue();
        assertThat(p.type()).isEqualTo("CCE_ALERT");
        assertThat(p.severity()).isEqualTo("WARNING");
        assertThat(p.title()).isEqualTo("ECG not ordered within 10 minutes of a Heart disease diagnosis");
        assertThat(p.body()).isEqualTo("Patient national-id 0123123123133 · Step status: overdue · Step: ecg-order");
        assertThat(p.link()).isEqualTo("/openmrs/spa/patient/patient-uuid/chart");
    }

    @Test
    void an_alert_for_a_patient_not_in_openmrs_still_notifies_without_a_link() throws Exception {
        assertThat(notifier.notify(entry(ALERT_ENTRY))).isTrue();

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifications).notify(payload.capture(), anyList(), anyString());
        assertThat(payload.getValue().body()).startsWith("Patient national-id 0123123123133 (not registered in OpenMRS)");
        assertThat(payload.getValue().link()).isNull();
    }

    @Test
    void the_older_stepState_field_is_read_too() throws Exception {
        String old = ALERT_ENTRY.replace("\"stepStatus\":\"overdue\"", "\"stepState\":\"missed\"");
        notifier.notify(entry(old));

        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(notifications).notify(payload.capture(), anyList(), anyString());
        assertThat(payload.getValue().body()).contains("Step status: missed");
    }

    @Test
    void an_alert_without_patient_or_recipients_is_not_notified() throws Exception {
        assertThat(notifier.notify(entry(ALERT_ENTRY.replace("\"patientId\":\"0123123123133\",", "")))).isFalse();
        when(recipients.resolveForReferral(any())).thenReturn(List.of());
        assertThat(notifier.notify(entry(ALERT_ENTRY))).isFalse();
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
        assertThat(CareAlertNotifier.isCareAlert(entry(ALERT_ENTRY))).isTrue();
        assertThat(CareAlertNotifier.isCareAlert(OM.readTree("{\"performanceAttributes\":{\"@type\":\"cce:CceClinicalEvent\"}}"))).isFalse();
    }
}
