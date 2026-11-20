package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import org.openphc.cce.receiver.config.BecknConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Posts a callback to {@code onix-bpp}'s caller endpoint ({@code receiver.beckn.onix.bpp-caller-url}).
 * The adapter signs it and sends it to Spice.
 */
@Component
@ConditionalOnBecknEnabled
public class OnixClient {

    /** What onix answered. {@code httpStatus} is 0 when it did not answer. */
    public record Result(int httpStatus, String error) {
        public boolean isSuccess() {
            return httpStatus >= 200 && httpStatus < 300;
        }
    }

    private final RestClient client;

    public OnixClient(@Qualifier(BecknConfig.ONIX_CLIENT) RestClient client) {
        this.client = client;
    }

    /** Never throws for a bad answer or a missing one. */
    public Result post(String action, JsonNode message) {
        try {
            return client.post()
                    .uri("/{action}", action)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(message)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        response.getBody().readAllBytes();
                        return new Result(status, status >= 200 && status < 300 ? null : "onix answered " + status);
                    });
        } catch (RestClientException e) {
            return new Result(0, rootMessage(e));
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }
}
