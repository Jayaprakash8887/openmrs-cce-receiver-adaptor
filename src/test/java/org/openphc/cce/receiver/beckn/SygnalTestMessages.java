package org.openphc.cce.receiver.beckn;

/**
 * A {@code sygnal/signal/emit} as cce-beckn-service builds it ({@code MessageFactory#signalEmit}, the
 * alert of its {@code AlertFlowTest}), and variants of it.
 */
public final class SygnalTestMessages {

    public static final String SELF_ID = "bap.openmrs.nextsteps.mdtlabs.org";
    public static final String CCE_ID = "bpp.cce.nextsteps.mdtlabs.org";
    public static final String TRANSACTION_ID = "a41f6b2c-9d3e-4a7b-8c1d-2e3f4a5b6c7d";
    /** The intelligence delivery id: CCE uses it as context.messageId and as message.id. */
    public static final String DELIVERY_ID = "b2c3d4e5-0001-4000-b000-000000000001";
    public static final String PATIENT_ID = "260225-0002-5501";

    /** The CommunicationRequest of cce-beckn-service's AlertFlowTest. */
    public static final String COMMUNICATION_REQUEST = """
            {"resourceType":"CommunicationRequest","status":"active","priority":"urgent",
             "identifier":[{"system":"http://openphc.org/fhir/intelligence-delivery-id","value":"%s"}],
             "subject":{"identifier":{"system":"http://openphc.org/fhir/patient-upid","value":"%s"}},
             "payload":[{"contentString":"[HIGH] step anc-visit-2 overdue"}],
             "extension":[
               {"url":"http://openphc.org/fhir/StructureDefinition/cce-severity","valueCode":"high"},
               {"url":"http://openphc.org/fhir/StructureDefinition/cce-intelligence-event-id","valueId":"e1"},
               {"url":"http://openphc.org/fhir/StructureDefinition/cce-step-status","valueCode":"not-started"},
               {"url":"http://openphc.org/fhir/StructureDefinition/cce-sla-status","valueCode":"overdue"}]}
            """.formatted(DELIVERY_ID, PATIENT_ID);

    private SygnalTestMessages() {
    }

    /**
     * The {@code message} of the alert: a {@code sygnal:Signal} of the CCE profile v3.0, with the
     * care-alert fields directly on it, CCE's level of severity ({@code cceSeverity}) among them. CCE does
     * not send Sygnal's own {@code severity}, nor a {@code signalAttributes} bag.
     */
    public static String signal() {
        return """
                {"@type":"sygnal:Signal","id":"%s","signalType":"SLA_BREACH",
                 "target":{"id":"%s","role":"PROVIDER"},
                 "basis":"CCE care step status: not-started, SLA status: overdue",
                 "issuedAt":"2026-11-20T10:00:00.123Z",
                 "cceSeverity":"HIGH",
                 "patientId":"%s","stepStatus":"not-started","slaStatus":"overdue","fhirVersion":"4.0.1",
                 "resource":%s}"""
                .formatted(DELIVERY_ID, SELF_ID, PATIENT_ID, COMMUNICATION_REQUEST);
    }

    /** The {@code message} of profile v2.0: the {@code cce:CceCareAlert} itself. It is refused now. */
    public static String cceCareAlert() {
        return """
                {"@context":"https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CceCareAlert/v1.0/context.jsonld",
                 "@type":"cce:CceCareAlert","patientId":"%s","severity":"HIGH","stepStatus":"not-started",
                 "slaStatus":"overdue","fhirVersion":"4.0.1","resource":%s}"""
                .formatted(PATIENT_ID, COMMUNICATION_REQUEST);
    }

    /**
     * The {@code message} of profile v1.0: a {@code sygnal:Signal} with the alert only in its
     * {@code signalAttributes} bag, so without a patient or a resource of its own. It is refused now.
     */
    public static String oldSignal() {
        return """
                {"@type":"sygnal:Signal","id":"%s","signalType":"SLA_BREACH",
                 "target":{"id":"%s","role":"PROVIDER"},"severity":"WARNING",
                 "basis":"CCE care step status: not-started, SLA status: overdue",
                 "issuedAt":"2026-11-20T10:00:00.123Z",
                 "signalAttributes":%s}"""
                .formatted(DELIVERY_ID, SELF_ID, cceCareAlert());
    }

    /** The whole {@code sygnal/signal/emit} body. */
    public static String signalEmit() {
        return signalEmit(SignalEmitService.ACTION, CCE_ID, SELF_ID, signal());
    }

    public static String signalEmit(String action, String senderId, String receiverId, String message) {
        return """
                {"context":{"version":"2.0.0","action":"%s","networkId":"nextsteps.mdtlabs.org/cce",
                  "senderId":"%s","receiverId":"%s","receiverUri":"https://openmrs.example.org/sygnal/receiver",
                  "transactionId":"%s","messageId":"%s","timestamp":"2026-11-20T10:00:00.123Z"},
                 "message":%s}"""
                .formatted(action, senderId, receiverId, TRANSACTION_ID, DELIVERY_ID, message);
    }
}
