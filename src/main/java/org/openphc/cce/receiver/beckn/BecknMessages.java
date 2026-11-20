package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.config.BecknProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Builds the Beckn bodies this service sends: {@code Ack}, {@code Nack} (the same shape as
 * cce-beckn-service), the {@code on_update} to Spice (docs/beckn-integration.md §3.4, §4.4) and the
 * callbacks of the health-referral flow (§4A).
 */
@Component
@ConditionalOnBecknEnabled
public class BecknMessages {

    public static final String VERSION = "2.0.0";

    static final String HEALTH_REFERRAL_CONTEXT = "https://schema.beckn.io/HealthReferral/v2.1/context.jsonld";
    static final String HEALTH_REFERRAL_TYPE = "hrf:HealthReferral";

    private final ObjectMapper mapper;
    private final BecknProperties props;
    private final CommitmentHolder commitment;

    public BecknMessages(ObjectMapper mapper, BecknProperties props, CommitmentHolder commitment) {
        this.mapper = mapper;
        this.props = props;
        this.commitment = commitment;
    }

    /** The body of a 200 answer. */
    public ObjectNode ack(String messageId) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode m = root.putObject("message");
        m.put("status", "ACK");
        m.put("messageId", Json.uuidOrRandom(messageId));
        return root;
    }

    /** The body of a 400 or 500 answer. */
    public ObjectNode nack(String messageId, String code, String message) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode m = root.putObject("message");
        m.put("status", "NACK");
        m.put("messageId", Json.uuidOrRandom(messageId));
        ObjectNode error = m.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return root;
    }

    /**
     * The {@code on_update} for an accepted referral. Spice is the bap, OpenMRS the bpp. It keeps the
     * request's contract id, {@code transactionId} and {@code messageId}; its one performance entry
     * carries Spice's performance id.
     */
    public ObjectNode onUpdateAccepted(ReferralMessage referral) {
        ObjectNode context = callbackContext("on_update", referral.networkId(), referral.transactionId(),
                referral.messageId());

        ObjectNode root = mapper.createObjectNode();
        root.set("context", context);
        ObjectNode c = root.putObject("message").putObject("contract");
        c.put("id", referral.contractId());
        c.putObject("status").put("code", "ACTIVE");
        c.putArray("commitments").add(commitment.get());
        ObjectNode performance = c.putArray("performance").addObject();
        performance.put("id", referral.performanceId());
        performance.putObject("status").put("code", "ACCEPTED");
        return root;
    }

    /**
     * A callback of the health-referral flow ({@code on_select}, {@code on_init}, {@code on_confirm},
     * {@code on_status}). It echoes {@code contract} (the request's, or the one stored at
     * {@code confirm}) with the contract and commitment status set to {@code contractStatus} and
     * {@code contractAttributes.lifecycleState} set to {@code lifecycleState}. Spice is the bap,
     * OpenMRS the bpp; the request's {@code transactionId} and {@code messageId} are kept.
     *
     * <p>What the schemas require and a minimal request may lack is filled in: at least one
     * commitment (the "referrals in" one), and a {@code HealthReferral} with
     * {@code coordinationId} and {@code targetCriteria}.
     */
    public ObjectNode healthCallback(String action, HealthReferral referral, JsonNode contract,
                                     String contractStatus, String lifecycleState) {
        ObjectNode root = mapper.createObjectNode();
        root.set("context", callbackContext(action, referral.networkId(), referral.transactionId(),
                referral.messageId()));

        ObjectNode c = contract != null && contract.isObject()
                ? ((ObjectNode) contract).deepCopy() : mapper.createObjectNode();
        c.put("id", referral.contractId());
        c.putObject("status").put("code", contractStatus);

        JsonNode commitments = c.path("commitments");
        if (!commitments.isArray() || commitments.isEmpty()) {
            c.putArray("commitments").add(commitment.get());
        }
        for (JsonNode item : c.path("commitments")) {
            if (item.isObject()) {
                ((ObjectNode) item).putObject("status").putObject("descriptor").put("code", contractStatus);
            }
        }

        ObjectNode attrs = c.path("contractAttributes").isObject()
                ? (ObjectNode) c.get("contractAttributes") : c.putObject("contractAttributes");
        if (Json.text(attrs, "@context").isBlank()) attrs.put("@context", HEALTH_REFERRAL_CONTEXT);
        if (Json.text(attrs, "@type").isBlank()) attrs.put("@type", HEALTH_REFERRAL_TYPE);
        attrs.put("coordinationId", referral.coordinationId());
        attrs.put("lifecycleState", lifecycleState);
        if (!attrs.path("targetCriteria").isObject()) {
            attrs.putObject("targetCriteria");
        }
        root.putObject("message").set("contract", c);
        return root;
    }

    private ObjectNode callbackContext(String action, String requestNetworkId, String transactionId,
                                       String messageId) {
        ObjectNode context = mapper.createObjectNode();
        context.put("version", VERSION);
        context.put("action", action);
        String networkId = requestNetworkId != null && !requestNetworkId.isBlank()
                ? requestNetworkId : props.self().networkId();
        if (networkId != null && !networkId.isBlank()) {
            context.put("networkId", networkId);
        }
        context.put("bapId", props.spice().bapId());
        context.put("bapUri", props.spice().bapUri());
        context.put("bppId", props.self().bppId());
        context.put("bppUri", props.self().bppUri());
        context.put("transactionId", transactionId);
        context.put("messageId", Json.uuidOrRandom(messageId));
        context.put("timestamp", Instant.now().toString());
        return context;
    }

    public JsonNode readTree(String body) throws com.fasterxml.jackson.core.JsonProcessingException {
        return mapper.readTree(body);
    }
}
