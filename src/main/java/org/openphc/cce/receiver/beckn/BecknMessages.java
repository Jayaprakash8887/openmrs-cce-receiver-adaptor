package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.receiver.config.BecknProperties;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Builds the Beckn bodies this service sends: {@code Ack}, {@code Nack} (the same shape as
 * cce-beckn-service) and the {@code on_update} to Spice (docs/beckn-integration.md §3.4, §4.4).
 */
@Component
@ConditionalOnBecknEnabled
public class BecknMessages {

    public static final String VERSION = "2.0.0";

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
        BecknProperties.Spice peer = props.spice();

        ObjectNode context = mapper.createObjectNode();
        context.put("version", VERSION);
        context.put("action", "on_update");
        String networkId = !referral.networkId().isBlank() ? referral.networkId() : props.self().networkId();
        if (networkId != null && !networkId.isBlank()) {
            context.put("networkId", networkId);
        }
        context.put("bapId", peer.bapId());
        context.put("bapUri", peer.bapUri());
        context.put("bppId", props.self().bppId());
        context.put("bppUri", props.self().bppUri());
        context.put("transactionId", referral.transactionId());
        context.put("messageId", referral.messageId());
        context.put("timestamp", Instant.now().toString());

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

    public JsonNode readTree(String body) throws com.fasterxml.jackson.core.JsonProcessingException {
        return mapper.readTree(body);
    }
}
