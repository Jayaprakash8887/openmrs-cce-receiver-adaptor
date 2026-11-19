package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import org.openphc.cce.receiver.config.BecknConfig;
import org.openphc.cce.receiver.model.ProcessingResponse;
import org.openphc.cce.receiver.model.RoutingResult;
import org.openphc.cce.receiver.service.InboundProcessingService;
import org.openphc.cce.receiver.service.OpenMrsRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * The health-referral flow from Spice, OpenMRS being the provider (docs/beckn-integration.md §4A).
 * The same verbs care-coordinator answers as a BPP:
 *
 * <table>
 *   <tr><th>Request</th><th>OpenMRS</th><th>Answer, then callback</th></tr>
 *   <tr><td>{@code select}</td><td>nothing written</td><td>Ack, {@code on_select} DRAFT</td></tr>
 *   <tr><td>{@code init}</td><td>nothing written</td><td>Ack, {@code on_init} DRAFT</td></tr>
 *   <tr><td>{@code confirm}</td><td>Patient + referral order through the write path</td>
 *       <td>Ack and {@code on_confirm} ACTIVE, or Nack (as for {@code update})</td></tr>
 *   <tr><td>{@code status}</td><td>reads the order</td>
 *       <td>Ack, {@code on_status} with the order's state; none when there is no order</td></tr>
 * </table>
 *
 * Every callback echoes the request's contract (for {@code status}, the confirmed one) and goes to
 * Spice through {@code onix-bpp}, on another thread. A callback failure is logged only: Spice
 * already has its Ack.
 */
@Service
@ConditionalOnBecknEnabled
public class HealthReferralService {

    public static final Set<String> ACTIONS = Set.of("select", "init", "confirm", "status");

    private static final Logger LOG = LoggerFactory.getLogger(HealthReferralService.class);
    private static final String DRAFT = "DRAFT";
    private static final String ACTIVE = "ACTIVE";

    private final HealthReferralMapper referralMapper;
    private final HealthReferralIndex index;
    private final InboundProcessingService processingService;
    private final BecknAnswerMapper answerMapper;
    private final OpenMrsRestClient openMrs;
    private final BecknMessages messages;
    private final OnixClient onix;
    private final BecknMetrics metrics;
    private final Executor callbackExecutor;

    public HealthReferralService(HealthReferralMapper referralMapper, HealthReferralIndex index,
                                 InboundProcessingService processingService, BecknAnswerMapper answerMapper,
                                 OpenMrsRestClient openMrs, BecknMessages messages, OnixClient onix,
                                 BecknMetrics metrics,
                                 @Qualifier(BecknConfig.ON_UPDATE_EXECUTOR) Executor callbackExecutor) {
        this.referralMapper = referralMapper;
        this.index = index;
        this.processingService = processingService;
        this.answerMapper = answerMapper;
        this.openMrs = openMrs;
        this.messages = messages;
        this.onix = onix;
        this.metrics = metrics;
        this.callbackExecutor = callbackExecutor;
    }

    /** {@code action} is one of {@link #ACTIONS}. A broken rule throws {@link BecknException}. */
    public ResponseEntity<JsonNode> handle(String action, JsonNode body) {
        HealthReferral referral = referralMapper.read(body);
        return switch (action) {
            case "select" -> acknowledge(action, referral,
                    () -> messages.healthCallback("on_select", referral, referral.contract(), DRAFT, DRAFT));
            case "init" -> acknowledge(action, referral,
                    () -> messages.healthCallback("on_init", referral, referral.contract(), DRAFT, DRAFT));
            case "confirm" -> confirm(referral, body);
            case "status" -> acknowledge(action, referral, () -> statusCallback(referral));
            default -> throw BecknException.notSupported(action);
        };
    }

    /** Ack now; build and send the callback on another thread. A null callback is not sent. */
    private ResponseEntity<JsonNode> acknowledge(String action, HealthReferral referral, Callback callback) {
        metrics.received(referral.bapId(), action, "ack");
        inLine(action, referral).result("ACK").write();
        schedule("on_" + action, referral, callback);
        return ResponseEntity.ok(messages.ack(referral.messageId()));
    }

    private ResponseEntity<JsonNode> confirm(HealthReferral referral, JsonNode body) {
        HealthReferralMapper.Fhir fhir = referralMapper.toFhir(referral,
                authoredOn(Json.text(body.path("context"), "timestamp")));

        BecknAnswerMapper.Answer answer;
        ProcessingResponse response = null;
        try {
            response = processingService.process(fhir.bundleJson(), referral.messageId(), referral.bapId());
            answer = answerMapper.map(response, fhir.serviceRequestId());
        } catch (Exception e) {
            LOG.error("The write path failed for health referral {}", BecknMessageLog.clean(referral.coordinationId()), e);
            answer = answerMapper.map(e);
        }

        BecknMessageLog line = inLine("confirm", referral).httpStatus(answer.writeStatus());
        if (answer.ack()) {
            index.put(referral.coordinationId(), new HealthReferralIndex.Entry(
                    resourceId(response, "Patient"), resourceId(response, "ServiceRequest"), referral.contract()));
            metrics.received(referral.bapId(), "confirm", "ack");
            line.result(answer.duplicate() ? "ACK_DUPLICATE" : "ACK").write();
            schedule("on_confirm", referral,
                    () -> messages.healthCallback("on_confirm", referral, referral.contract(), ACTIVE, ACTIVE));
            return ResponseEntity.ok(messages.ack(referral.messageId()));
        }

        metrics.received(referral.bapId(), "confirm",
                answer.httpStatus().is5xxServerError() ? "unavailable" : "nack");
        line.result("NACK").code(answer.code()).error(answer.reason()).write();
        return ResponseEntity.status(answer.httpStatus())
                .body(messages.nack(referral.messageId(), answer.code(), answer.reason()));
    }

    /** The {@code on_status}, or null when OpenMRS has no order for the referral. Runs off the request thread. */
    private JsonNode statusCallback(HealthReferral referral) {
        HealthReferralIndex.Entry entry = index.get(referral.coordinationId());
        String orderUuid = entry != null ? entry.orderUuid() : findOrder(referral);
        JsonNode order = orderUuid == null || orderUuid.isBlank() ? null : openMrs.getOrderState(orderUuid);
        if (order == null) {
            metrics.statusUnknown();
            outLine("on_status", referral).result("SKIPPED")
                    .error("OpenMRS has no order for this referral; no on_status sent").write();
            return null;
        }
        String[] state = state(order);
        JsonNode contract = entry != null ? entry.contract() : referral.contract();
        return messages.healthCallback("on_status", referral, contract, state[0], state[1]);
    }

    /** After a restart: the patient by the request's health id, then the order by accession number. */
    private String findOrder(HealthReferral referral) {
        JsonNode healthId = referralMapper.patientHealthId(referral);
        if (healthId == null) {
            return null;
        }
        String patientUuid = openMrs.searchByIdentifier("/patient", Json.text(healthId, "value"));
        return patientUuid == null ? null : openMrs.findOrderByAccessionNumber(patientUuid, referral.coordinationId());
    }

    /**
     * The order's state as {contract status, lifecycleState}: voided or discontinued → CANCELLED;
     * fulfilled (fulfillerStatus COMPLETED) or otherwise stopped → COMPLETE / CLOSED; else ACTIVE.
     */
    static String[] state(JsonNode order) {
        if (order.path("voided").asBoolean(false) || "DISCONTINUE".equals(Json.text(order, "action"))) {
            return new String[]{"CANCELLED", "CANCELLED"};
        }
        boolean stopped = order.hasNonNull("dateStopped") && !Json.text(order, "dateStopped").isBlank();
        if ("COMPLETED".equals(Json.text(order, "fulfillerStatus")) || stopped) {
            return new String[]{"COMPLETE", "CLOSED"};
        }
        return new String[]{ACTIVE, ACTIVE};
    }

    // ---- callbacks ----

    @FunctionalInterface
    private interface Callback {
        JsonNode build();
    }

    private void schedule(String action, HealthReferral referral, Callback callback) {
        try {
            callbackExecutor.execute(() -> send(action, referral, callback));
        } catch (RejectedExecutionException e) {
            metrics.callbackSent(action, "dropped");
            outLine(action, referral).result("ERROR").error(action + " dropped, the queue is full").write();
        }
    }

    private void send(String action, HealthReferral referral, Callback callback) {
        try {
            JsonNode message = callback.build();
            if (message == null) {
                return;
            }
            OnixClient.Result r = onix.post(action, message);
            if (r.isSuccess()) {
                metrics.callbackSent(action, "success");
                outLine(action, referral).result(Json.text(
                        message.path("message").path("contract").path("contractAttributes"), "lifecycleState"))
                        .httpStatus(r.httpStatus()).write();
            } else {
                metrics.callbackSent(action, "failed");
                outLine(action, referral).result("ERROR").httpStatus(r.httpStatus()).error(r.error()).write();
            }
        } catch (RuntimeException e) {
            metrics.callbackSent(action, "failed");
            outLine(action, referral).result("ERROR").error(e.getClass().getSimpleName()).write();
        }
    }

    // ---- helpers ----

    /** The request's timestamp to the second (OpenMRS reads no nanoseconds), or blank. */
    static String authoredOn(String timestamp) {
        try {
            return Instant.parse(timestamp).truncatedTo(ChronoUnit.SECONDS).toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String resourceId(ProcessingResponse response, String resourceType) {
        List<RoutingResult> results = response == null || response.results() == null ? List.of() : response.results();
        return results.stream()
                .filter(r -> resourceType.equals(r.resourceType()) && !"failed".equals(r.status()))
                .map(RoutingResult::resourceId)
                .findFirst()
                .orElse(null);
    }

    private static BecknMessageLog inLine(String action, HealthReferral referral) {
        return BecknMessageLog.in(action)
                .peer(referral.bapId())
                .contractId(referral.contractId())
                .transactionId(referral.transactionId())
                .messageId(referral.messageId())
                .resourceType("ServiceRequest")
                .referralId(referral.coordinationId());
    }

    private static BecknMessageLog outLine(String action, HealthReferral referral) {
        return BecknMessageLog.out(action)
                .peer(referral.bapId())
                .contractId(referral.contractId())
                .transactionId(referral.transactionId())
                .messageId(referral.messageId())
                .resourceType("ServiceRequest")
                .referralId(referral.coordinationId());
    }
}
