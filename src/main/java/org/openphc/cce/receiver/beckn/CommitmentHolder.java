package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openphc.cce.receiver.config.BecknProperties;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * The commitment of the "referrals in" contract, sent in every {@code on_update}. Beckn requires at
 * least one commitment in a contract. It is read once, at start, from
 * {@code receiver.beckn.spice.referrals-in.commitment-file}.
 */
@Component
@ConditionalOnBecknEnabled
public class CommitmentHolder {

    private final JsonNode commitment;

    public CommitmentHolder(BecknProperties props, ObjectMapper mapper) {
        Resource file = props.spice().referralsIn().commitmentFile();
        try (InputStream in = file.getInputStream()) {
            JsonNode node = mapper.readTree(in);
            if (node == null || !node.isObject() || node.path("id").asText("").isBlank()) {
                throw new IllegalStateException(
                        "Commitment file " + file + " must hold a JSON object with an \"id\"");
            }
            this.commitment = node;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the commitment file " + file + ": " + e.getMessage(), e);
        }
    }

    /** A copy, so that no caller can change the shared tree. */
    public JsonNode get() {
        return commitment.deepCopy();
    }
}
