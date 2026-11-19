package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.receiver.config.BecknProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the {@code update} of a referral from Spice into a FHIR transaction {@code Bundle}
 * (docs/beckn-integration.md §4.1, §4.2).
 *
 * <ul>
 *   <li>{@code context.bapId} must be Spice; the contract id must be non-blank and is echoed back.</li>
 *   <li>One or two performance entries, each with a FHIR resource in
 *       {@code performanceAttributes.resource}: exactly one {@code ServiceRequest}, and at most one
 *       {@code Patient}. Two entries are allowed on this leg only.</li>
 *   <li>The {@code Bundle} has the {@code Patient} first. Each resource goes in as sent.</li>
 * </ul>
 * A broken rule throws {@link BecknException} with {@code BAD_REQUEST}.
 */
@Component
@ConditionalOnBecknEnabled
public class BecknUnwrapper {

    private static final int MAX_ENTRIES = 2;

    private final ObjectMapper mapper;
    private final BecknProperties props;
    private final BecknMetrics metrics;

    public BecknUnwrapper(ObjectMapper mapper, BecknProperties props, BecknMetrics metrics) {
        this.mapper = mapper;
        this.props = props;
        this.metrics = metrics;
    }

    public ReferralMessage unwrap(JsonNode body) {
        JsonNode context = body.path("context");
        JsonNode contract = body.path("message").path("contract");
        String bapId = Json.text(context, "bapId");

        if (!props.spice().bapId().equals(bapId)) {
            metrics.unknownPeer(bapId);
            throw BecknException.badRequest(bapId.isBlank()
                    ? "context.bapId is missing"
                    : "context.bapId '" + BecknMessageLog.clean(bapId) + "' is not a known referral sender");
        }

        // No confirm in the demo, so nobody issues a contract id: any non-blank id is accepted
        // (as cce-beckn-service does) and echoed back in on_update.
        String contractId = Json.text(contract, "id");
        if (contractId.isBlank()) {
            throw BecknException.badRequest("message.contract.id is missing");
        }

        JsonNode performance = contract.path("performance");
        if (!performance.isArray() || performance.isEmpty()) {
            throw BecknException.badRequest("message.contract.performance has no entry");
        }
        if (performance.size() > MAX_ENTRIES) {
            throw BecknException.badRequest("message.contract.performance has " + performance.size()
                    + " entries; at most " + MAX_ENTRIES + " (ServiceRequest, Patient) are allowed");
        }

        JsonNode serviceRequest = null;
        JsonNode patient = null;
        String performanceId = "";
        for (int i = 0; i < performance.size(); i++) {
            JsonNode entry = performance.get(i);
            JsonNode resource = entry.path("performanceAttributes").path("resource");
            if (!resource.isObject() || resource.isEmpty()) {
                throw BecknException.badRequest("performance[" + i + "].performanceAttributes.resource is missing");
            }
            String type = Json.text(resource, "resourceType");
            switch (type) {
                case "ServiceRequest" -> {
                    if (serviceRequest != null) {
                        throw BecknException.badRequest("more than one ServiceRequest in message.contract.performance");
                    }
                    serviceRequest = resource;
                    performanceId = Json.text(entry, "id");
                    if (performanceId.isBlank()) {
                        throw BecknException.badRequest("performance[" + i + "].id is missing");
                    }
                }
                case "Patient" -> {
                    if (patient != null) {
                        throw BecknException.badRequest("more than one Patient in message.contract.performance");
                    }
                    patient = resource;
                }
                case "" -> throw BecknException.badRequest(
                        "performance[" + i + "].performanceAttributes.resource.resourceType is missing");
                default -> throw BecknException.badRequest("performance[" + i + "]: resource type '"
                        + BecknMessageLog.clean(type) + "' is not accepted; a referral carries a ServiceRequest"
                        + " and optionally a Patient");
            }
        }
        if (serviceRequest == null) {
            throw BecknException.badRequest("message.contract.performance has no ServiceRequest");
        }

        List<JsonNode> ordered = new ArrayList<>(2);
        if (patient != null) {
            ordered.add(patient);
        }
        ordered.add(serviceRequest);

        return new ReferralMessage(
                TransactionBundle.of(mapper, ordered).toString(),
                Json.text(serviceRequest, "id"),
                performanceId,
                contractId,
                Json.text(context, "transactionId"),
                Json.text(context, "messageId"),
                bapId,
                Json.text(context, "networkId"));
    }
}
