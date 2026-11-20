package org.openphc.cce.receiver.beckn;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The Sygnal counters (docs/sygnal-integration.md §8.1). Prometheus shows them with the prefix
 * {@code openmrs_beckn_} and the suffix {@code _total}.
 */
@Component
@ConditionalOnBecknEnabled
public class BecknMetrics {

    private final MeterRegistry registry;

    public BecknMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A {@code sygnal/signal/emit} answered. {@code result}: ack or nack. */
    public void received(String peer, String action, String result) {
        registry.counter("openmrs.beckn.messages.received",
                "peer", label(peer), "action", SignalEmitService.ACTION.equals(action) ? action : "other",
                "result", result)
                .increment();
    }

    /** A CCE care alert after its {@code Ack}. {@code result}: notified, not-notified or dropped. */
    public void careAlert(String result) {
        registry.counter("openmrs.beckn.care.alerts", "result", result).increment();
    }

    private static String label(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String v = BecknMessageLog.clean(value);
        return v.length() > 100 ? v.substring(0, 100) : v;
    }
}
