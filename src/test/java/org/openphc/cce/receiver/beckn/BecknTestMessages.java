package org.openphc.cce.receiver.beckn;

/** Sample Beckn messages for the tests (from docs/beckn-integration.md §4.1). */
public final class BecknTestMessages {

    public static final String CONTRACT_ID = "e2c9a7b4-6f1d-4b3a-9c8e-7d6f5a4b3c2d";
    public static final String TRANSACTION_ID = "a41f6b2c-9d3e-4a7b-8c1d-2e3f4a5b6c7d";
    public static final String MESSAGE_ID = "c7d8e9f0-1a2b-4c3d-9e4f-5a6b7c8d9e0f";
    public static final String SR_PERFORMANCE_ID = "8a1b2c3d-4e5f-5a6b-8c7d-9e0f1a2b3c4d";
    public static final String PATIENT_PERFORMANCE_ID = "9b2c3d4e-5f6a-5b7c-9d8e-0f1a2b3c4d5e";
    public static final String BAP_ID = "bap.spice.example.org";
    public static final String BPP_ID = "bpp.openmrs.example.org";

    public static final String SERVICE_REQUEST = """
            {"resourceType":"ServiceRequest","id":"1838","status":"active","intent":"order",
             "subject":{"reference":"Patient/1198503150001234"},"authoredOn":"2026-11-15T09:55:00Z"}""";

    public static final String PATIENT = """
            {"resourceType":"Patient","id":"1198503150001234","name":[{"family":"Test","given":["Pat"]}]}""";

    private BecknTestMessages() {
    }

    /**
     * A performance entry of a referral message: its box is a {@code sygnal:CoordinationEvent} of the CCE
     * profile v3.0 with the FHIR fields (schema {@code CoordinationEvent/v3.0} of cce-beckn-service).
     */
    public static String entry(String performanceId, String resource) {
        return """
                {"id":"%s","performanceAttributes":{
                  "@context":"https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema/CoordinationEvent/v3.0/context.jsonld",
                  "@type":"sygnal:CoordinationEvent","id":"%s","revision":1,"eventType":"DELIVERY_RECORDED",
                  "granularity":"INDIVIDUAL","from":{"id":"bap.spice.example.org","role":"PROVIDER"},
                  "occurredAt":"2026-11-15T09:55:00Z","patientId":"1198503150001234",
                  "fhirVersion":"4.0.1","resource":%s}}""".formatted(performanceId, java.util.UUID.randomUUID(), resource);
    }

    public static String srEntry() {
        return entry(SR_PERFORMANCE_ID, SERVICE_REQUEST);
    }

    public static String patientEntry() {
        return entry(PATIENT_PERFORMANCE_ID, PATIENT);
    }

    /** A message with the given action in context, contract id, extra context fields and performance entries. */
    public static String message(String action, String contractId, String extraContext, String... entries) {
        return """
                {"context":{"version":"2.0.0","action":"%s","bapId":"%s",
                  "bapUri":"https://spice.example.org/bap/receiver","bppId":"%s",
                  "bppUri":"https://openmrs.example.org/bpp/receiver","transactionId":"%s",
                  "messageId":"%s","timestamp":"2026-11-15T10:00:05Z"%s},
                 "message":{"contract":{"id":"%s","commitments":[{"id":"referral"}],
                   "performance":[%s]}}}"""
                .formatted(action, BAP_ID, BPP_ID, TRANSACTION_ID, MESSAGE_ID,
                        extraContext == null ? "" : "," + extraContext, contractId, String.join(",", entries));
    }

    public static String referral(String... entries) {
        return message("update", CONTRACT_ID, null, entries);
    }

    public static String callback(String action, String status) {
        return message(action, "some-other-contract", null,
                "{\"id\":\"p-1\",\"status\":{\"code\":\"" + status + "\"},\"performanceAttributes\":{\"resource\":"
                        + SERVICE_REQUEST + "}}");
    }
}
