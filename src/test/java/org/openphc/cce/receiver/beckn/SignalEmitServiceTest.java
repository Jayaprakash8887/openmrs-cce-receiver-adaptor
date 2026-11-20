package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.openphc.cce.receiver.config.BecknProperties;
import org.springframework.http.ResponseEntity;

import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.openphc.cce.receiver.beckn.SygnalTestMessages.*;

/** The answers that need a broken executor or notifier. */
class SignalEmitServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final BecknProperties PROPS = new BecknProperties(true, null,
            new BecknProperties.Self(SELF_ID, null, null, null), null, null, new BecknProperties.Cce(CCE_ID));

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final CareAlertNotifier notifier = mock(CareAlertNotifier.class);

    private SignalEmitService service(java.util.concurrent.Executor executor) {
        return new SignalEmitService(OM, new BecknMessages(OM, PROPS, null), new BecknMetrics(meters), notifier, PROPS, executor);
    }

    @Test
    void a_full_queue_gives_a_503_nack_so_cce_may_send_again() {
        ResponseEntity<JsonNode> answer = service(task -> {
            throw new RejectedExecutionException("full");
        }).handle(signalEmit());

        assertThat(answer.getStatusCode().value()).isEqualTo(503);
        assertThat(answer.getBody().at("/message/status").asText()).isEqualTo("NACK");
        assertThat(answer.getBody().at("/message/messageId").asText()).isEqualTo(DELIVERY_ID);
        assertThat(answer.getBody().at("/message/error/code").asText()).isEqualTo("NET_INTERNAL_ERROR");
        assertThat(meters.counter("openmrs.beckn.care.alerts", "result", "dropped").count()).isEqualTo(1);
    }

    @Test
    void an_accepted_alert_is_acked_and_the_notification_result_is_counted() {
        when(notifier.notify(any())).thenReturn(false);
        ResponseEntity<JsonNode> answer = service(Runnable::run).handle(signalEmit());

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody().at("/message/status").asText()).isEqualTo("ACK");
        assertThat(meters.counter("openmrs.beckn.care.alerts", "result", "not-notified").count()).isEqualTo(1);
    }

    @Test
    void an_unexpected_error_gives_a_500_nack() {
        ResponseEntity<JsonNode> answer = service(task -> {
            throw new IllegalStateException("boom");
        }).handle(signalEmit());

        assertThat(answer.getStatusCode().value()).isEqualTo(500);
        assertThat(answer.getBody().at("/message/error/code").asText()).isEqualTo("NET_INTERNAL_ERROR");
        assertThat(answer.getBody().at("/message/messageId").asText()).isEqualTo(DELIVERY_ID);
    }
}
