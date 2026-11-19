package org.openphc.cce.receiver.beckn;

import com.fasterxml.jackson.databind.JsonNode;
import org.openphc.cce.receiver.config.BecknConfig;
import org.openphc.cce.receiver.model.ProcessingResponse;
import org.openphc.cce.receiver.service.InboundProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Handles {@code POST /beckn/bpp-webhook/{action}}: a referral from Spice (docs/beckn-integration.md §4).
 *
 * <p>{@code update} (FHIR resources in {@code performance[]}) → check the contract → {@code Bundle} →
 * the existing write path ({@link InboundProcessingService#process}) → {@code Ack} or {@code Nack}.
 * After an {@code Ack}, an {@code on_update} {@code ACCEPTED} goes to Spice through {@code onix-bpp},
 * on another thread.
 *
 * <p>{@code select}, {@code init}, {@code confirm} and {@code status} (a {@code hrf:HealthReferral}
 * contract, §4A) go to {@link HealthReferralService}. Every other action is answered
 * {@code NOT_SUPPORTED}.
 */
@Service
@ConditionalOnBecknEnabled
public class BecknReferralService {

    private static final Logger LOG = LoggerFactory.getLogger(BecknReferralService.class);

    private final BecknUnwrapper unwrapper;
    private final InboundProcessingService processingService;
    private final BecknAnswerMapper answerMapper;
    private final BecknMessages messages;
    private final OnixClient onix;
    private final BecknMetrics metrics;
    private final Executor onUpdateExecutor;
    private final HealthReferralService healthReferrals;

    public BecknReferralService(BecknUnwrapper unwrapper, InboundProcessingService processingService,
                                BecknAnswerMapper answerMapper, BecknMessages messages, OnixClient onix,
                                BecknMetrics metrics,
                                @Qualifier(BecknConfig.ON_UPDATE_EXECUTOR) Executor onUpdateExecutor,
                                HealthReferralService healthReferrals) {
        this.unwrapper = unwrapper;
        this.processingService = processingService;
        this.answerMapper = answerMapper;
        this.messages = messages;
        this.onix = onix;
        this.metrics = metrics;
        this.onUpdateExecutor = onUpdateExecutor;
        this.healthReferrals = healthReferrals;
    }

    public ResponseEntity<JsonNode> handle(String action, String rawBody) {
        JsonNode body;
        try {
            body = messages.readTree(rawBody);
        } catch (Exception e) {
            body = null;
        }
        if (body == null || !body.isObject()) {
            return nack(action, null, new BecknException(HttpStatus.BAD_REQUEST, BecknCodes.BAD_REQUEST,
                    "The body is not a JSON object"));
        }

        try {
            String claimed = Json.text(body.path("context"), "action");
            if (!claimed.isBlank() && !claimed.equals(action)) {
                throw BecknException.badRequest("context.action '" + BecknMessageLog.clean(claimed)
                        + "' does not match the request path '" + BecknMessageLog.clean(action) + "'");
            }
            if ("update".equals(action)) {
                return handleUpdate(body);
            }
            if (HealthReferralService.ACTIONS.contains(action)) {
                return healthReferrals.handle(action, body);
            }
            metrics.unsupported(action);
            throw BecknException.notSupported(action);
        } catch (BecknException e) {
            return nack(action, body, e);
        } catch (RuntimeException e) {
            LOG.error("The Beckn '{}' request could not be handled", BecknMessageLog.clean(action), e);
            return nack(action, body, new BecknException(HttpStatus.INTERNAL_SERVER_ERROR, BecknCodes.UNAVAILABLE,
                    "The request could not be handled. Send it again later."));
        }
    }

    private ResponseEntity<JsonNode> handleUpdate(JsonNode body) {
        ReferralMessage referral = unwrapper.unwrap(body);

        BecknAnswerMapper.Answer answer;
        try {
            ProcessingResponse response = processingService.process(
                    referral.bundleJson(), referral.messageId(), referral.bapId());
            answer = answerMapper.map(response, referral.serviceRequestId());
        } catch (Exception e) {
            LOG.error("The write path failed for referral {}", BecknMessageLog.clean(referral.serviceRequestId()), e);
            answer = answerMapper.map(e);
        }

        BecknMessageLog line = BecknMessageLog.in("update")
                .peer(referral.bapId())
                .contractId(referral.contractId())
                .transactionId(referral.transactionId())
                .messageId(referral.messageId())
                .performanceId(referral.performanceId())
                .resourceType("ServiceRequest")
                .referralId(referral.serviceRequestId())
                .httpStatus(answer.writeStatus());

        if (answer.ack()) {
            metrics.received(referral.bapId(), "update", "ack");
            line.result("ACK").write();
            scheduleOnUpdate(referral);
            return ResponseEntity.ok(messages.ack(referral.messageId()));
        }

        metrics.received(referral.bapId(), "update",
                answer.httpStatus().is5xxServerError() ? "unavailable" : "nack");
        line.result("NACK").code(answer.code()).error(answer.reason()).write();
        return ResponseEntity.status(answer.httpStatus())
                .body(messages.nack(referral.messageId(), answer.code(), answer.reason()));
    }

    /** A Nack for a request that did not reach the write path. */
    private ResponseEntity<JsonNode> nack(String action, JsonNode body, BecknException e) {
        JsonNode context = body == null ? null : body.path("context");
        String messageId = Json.text(context, "messageId");
        String peer = Json.text(context, "bapId");
        String contractId = body == null ? "" : Json.text(body.path("message").path("contract"), "id");
        metrics.received(peer, action, e.status().is5xxServerError() ? "unavailable" : "nack");
        BecknMessageLog.in(action)
                .peer(peer)
                .contractId(contractId)
                .transactionId(Json.text(context, "transactionId"))
                .messageId(messageId)
                .result("NACK")
                .code(e.code())
                .error(e.getMessage())
                .write();
        return ResponseEntity.status(e.status()).body(messages.nack(messageId, e.code(), e.getMessage()));
    }

    /** Runs after the Ack is decided. A failure is logged only: Spice already has its Ack. */
    private void scheduleOnUpdate(ReferralMessage referral) {
        JsonNode message = messages.onUpdateAccepted(referral);
        try {
            onUpdateExecutor.execute(() -> sendOnUpdate(referral, message));
        } catch (RejectedExecutionException e) {
            metrics.onUpdateSent("dropped");
            outLine(referral, message).result("ERROR").error("on_update dropped, the queue is full").write();
        }
    }

    private void sendOnUpdate(ReferralMessage referral, JsonNode message) {
        try {
            OnixClient.Result r = onix.post("on_update", message);
            if (r.isSuccess()) {
                metrics.onUpdateSent("success");
                outLine(referral, message).result("ACCEPTED").httpStatus(r.httpStatus()).write();
            } else {
                metrics.onUpdateSent("failed");
                outLine(referral, message).result("ERROR").httpStatus(r.httpStatus()).error(r.error()).write();
            }
        } catch (RuntimeException e) {
            metrics.onUpdateSent("failed");
            outLine(referral, message).result("ERROR").error(e.getClass().getSimpleName()).write();
        }
    }

    private static BecknMessageLog outLine(ReferralMessage referral, JsonNode message) {
        JsonNode context = message.path("context");
        return BecknMessageLog.out("on_update")
                .peer(Json.text(context, "bapId"))
                .contractId(Json.text(message.path("message").path("contract"), "id"))
                .transactionId(Json.text(context, "transactionId"))
                .messageId(referral.messageId())
                .performanceId(referral.performanceId())
                .resourceType("ServiceRequest")
                .referralId(referral.serviceRequestId());
    }
}
