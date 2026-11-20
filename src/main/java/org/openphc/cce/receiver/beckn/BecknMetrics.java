package org.openphc.cce.receiver.beckn;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The Beckn counters (docs/beckn-integration.md §9.1). Prometheus shows them with the prefix
 * {@code openmrs_beckn_} and the suffix {@code _total}.
 */
@Component
@ConditionalOnBecknEnabled
public class BecknMetrics {

    /** Beckn actions that may appear as a label. Anything else is counted as "other". */
    private static final Set<String> KNOWN_ACTIONS = Set.of(
            "discover", "search", "select", "init", "confirm", "status", "track", "update", "cancel",
            "rate", "rating", "support",
            "on_discover", "on_search", "on_select", "on_init", "on_confirm", "on_status", "on_track",
            "on_update", "on_cancel", "on_rate", "on_rating", "on_support",
            SignalEmitService.ACTION);

    private static final Set<String> KNOWN_STATUSES = Set.of("ACCEPTED", "DUPLICATE", "COMPLETED", "DECLINED");

    private final MeterRegistry registry;

    public BecknMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** {@code result}: ack, nack or unavailable. */
    public void received(String peer, String action, String result) {
        registry.counter("openmrs.beckn.messages.received",
                "peer", label(peer), "action", action(action), "result", result)
                .increment();
    }

    public void callbackStatus(String peer, String action, String status) {
        registry.counter("openmrs.beckn.callback.status",
                "peer", label(peer), "action", action(action),
                "status", KNOWN_STATUSES.contains(status) ? status : "OTHER")
                .increment();
    }

    public void unsupported(String action) {
        registry.counter("openmrs.beckn.actions.unsupported", "action", action(action)).increment();
    }

    /** A referral ({@code update}, {@code select}, ...) from a {@code bapId} that is not the configured sender. */
    public void unknownPeer(String peer) {
        registry.counter("openmrs.beckn.peer.unknown", "peer", label(peer)).increment();
    }

    /** {@code result}: success, failed or dropped. */
    public void onUpdateSent(String result) {
        registry.counter("openmrs.beckn.on.update.sent", "result", result).increment();
    }

    /**
     * A callback of the health-referral flow sent to {@code onix-bpp} ({@code on_select}, {@code on_init},
     * {@code on_confirm}, {@code on_status}). {@code result}: success, failed or dropped.
     */
    public void callbackSent(String action, String result) {
        registry.counter("openmrs.beckn.callback.sent", "action", action(action), "result", result).increment();
    }

    /**
     * A CCE care alert accepted on {@code POST /sygnal/signal/emit}. {@code result}: notified,
     * not-notified or dropped (the queue was full; answered 503).
     */
    public void careAlert(String result) {
        registry.counter("openmrs.beckn.care.alerts", "result", result).increment();
    }

        /** A {@code status} for a coordination id OpenMRS has no order for: no {@code on_status} is sent. */
    public void statusUnknown() {
        registry.counter("openmrs.beckn.status.unknown").increment();
    }

    private static String action(String action) {
        return action != null && KNOWN_ACTIONS.contains(action) ? action : "other";
    }

    private static String label(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String v = BecknMessageLog.clean(value);
        return v.length() > 100 ? v.substring(0, 100) : v;
    }
}
