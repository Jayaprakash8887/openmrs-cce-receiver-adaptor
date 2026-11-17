package org.openphc.cce.receiver.beckn;

import org.openphc.cce.receiver.exception.FhirParsingException;
import org.openphc.cce.receiver.exception.OpenMrsClientException;
import org.openphc.cce.receiver.exception.ResourceTransformException;
import org.openphc.cce.receiver.model.ProcessingResponse;
import org.openphc.cce.receiver.model.RoutingResult;
import org.openphc.cce.receiver.service.ProcessingStatusResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Reads the write path's result as a Beckn answer (docs/beckn-integration.md §4.3). The write path's
 * status comes from {@link ProcessingStatusResolver}, the same rule as {@code /api/v1/openmrs/fhir}.
 *
 * <table>
 *   <tr><th>Write path</th><th>Answer</th></tr>
 *   <tr><td>202, or 207 with the ServiceRequest written (created, updated, duplicate)</td><td>Ack</td></tr>
 *   <tr><td>207 whose ServiceRequest failed for a transient reason (5xx, timeout)</td><td>Nack 500 UNAVAILABLE</td></tr>
 *   <tr><td>400, 422, other 207 without the ServiceRequest</td><td>Nack 400 REFERRAL_REJECTED</td></tr>
 *   <tr><td>503, 500, 502, exception</td><td>Nack 500 UNAVAILABLE</td></tr>
 * </table>
 */
@Component
@ConditionalOnBecknEnabled
public class BecknAnswerMapper {

    private static final Set<String> WRITTEN = Set.of("created", "updated", "duplicate");
    private static final int MAX_REASON = 200;

    private final ProcessingStatusResolver statusResolver;

    public BecknAnswerMapper(ProcessingStatusResolver statusResolver) {
        this.statusResolver = statusResolver;
    }

    /**
     * The answer to Spice.
     *
     * @param ack         true for an Ack
     * @param httpStatus  200, 400 or 500
     * @param code        the Nack code; null for an Ack
     * @param reason      the Nack text; null for an Ack
     * @param writeStatus the write path's status (202, 207, 422, 503, or 400 / 500 / 502 for an exception)
     * @param duplicate   true when the ServiceRequest was already in OpenMRS
     */
    public record Answer(boolean ack, HttpStatus httpStatus, String code, String reason, int writeStatus,
                         boolean duplicate) {

        static Answer ack(int writeStatus, boolean duplicate) {
            return new Answer(true, HttpStatus.OK, null, null, writeStatus, duplicate);
        }

        static Answer rejected(int writeStatus, String reason) {
            return new Answer(false, HttpStatus.BAD_REQUEST, BecknCodes.REFERRAL_REJECTED, reason, writeStatus, false);
        }

        static Answer unavailable(int writeStatus) {
            return new Answer(false, HttpStatus.INTERNAL_SERVER_ERROR, BecknCodes.UNAVAILABLE,
                    "OpenMRS could not take the referral now. Send it again later.", writeStatus, false);
        }
    }

    public Answer map(ProcessingResponse response, String serviceRequestId) {
        HttpStatus status = statusResolver.resolve(response);
        int write = status.value();
        List<RoutingResult> results = response.results() == null ? List.of() : response.results();
        RoutingResult sr = results.stream()
                .filter(r -> "ServiceRequest".equals(r.resourceType()))
                .findFirst()
                .orElse(null);

        return switch (status) {
            case ACCEPTED, MULTI_STATUS -> {
                if (sr != null && WRITTEN.contains(sr.status())) {
                    yield Answer.ack(write, "duplicate".equals(sr.status()));
                }
                if (statusResolver.isRetryableFailure(sr)) {
                    // The Patient was written but the referral itself hit a transient failure.
                    // A rejection would make Spice drop the referral, so ask it to send again.
                    yield Answer.unavailable(write);
                }
                yield Answer.rejected(write, reason(results, serviceRequestId,
                        "the ServiceRequest was not written"));
            }
            case UNPROCESSABLE_ENTITY -> Answer.rejected(write, reason(results, serviceRequestId,
                    "the referral could not be written"));
            default -> Answer.unavailable(write);
        };
    }

    /** The answer when the write path threw, read as {@code GlobalExceptionHandler} would answer it. */
    public Answer map(Exception e) {
        if (e instanceof FhirParsingException) {
            return Answer.rejected(400, cut("the referral could not be read: " + e.getMessage()));
        }
        if (e instanceof ResourceTransformException) {
            return Answer.rejected(422, cut("the referral could not be mapped: " + e.getMessage()));
        }
        if (e instanceof OpenMrsClientException) {
            return Answer.unavailable(502);
        }
        return Answer.unavailable(500);
    }

    /**
     * The first errorMessage of the failed results, prefixed with the resource. A Patient is named
     * by type only: its id is the national id.
     */
    private static String reason(List<RoutingResult> results, String serviceRequestId, String fallback) {
        RoutingResult failed = results.stream()
                .filter(r -> "failed".equals(r.status()))
                .findFirst()
                .orElse(null);
        if (failed == null) {
            return fallback;
        }
        String subject = failed.resourceType() == null || failed.resourceType().isBlank()
                ? "resource" : failed.resourceType();
        if ("ServiceRequest".equals(subject) && serviceRequestId != null && !serviceRequestId.isBlank()) {
            subject = subject + "/" + serviceRequestId;
        }
        String text = failed.errorMessage() == null || failed.errorMessage().isBlank()
                ? fallback : failed.errorMessage();
        return cut(subject + ": " + text);
    }

    private static String cut(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > MAX_REASON ? t.substring(0, MAX_REASON) + "..." : t;
    }
}
