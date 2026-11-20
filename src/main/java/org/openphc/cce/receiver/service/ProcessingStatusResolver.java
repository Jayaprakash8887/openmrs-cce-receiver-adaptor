package org.openphc.cce.receiver.service;

import org.openphc.cce.receiver.model.ProcessingResponse;
import org.openphc.cce.receiver.model.RoutingResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Reads the result of the write path ({@link InboundProcessingService#process}) as one HTTP status.
 * Used by {@code InboundResourceController} for {@code /api/v1/openmrs/fhir}.
 *
 * <ul>
 *   <li>202 — every resource was processed</li>
 *   <li>207 — some resources were processed, some failed</li>
 *   <li>503 — all failed, and at least one failure looks transient</li>
 *   <li>422 — all failed, none looks transient</li>
 * </ul>
 */
@Component
public class ProcessingStatusResolver {

    public HttpStatus resolve(ProcessingResponse response) {
        if (response.failureCount() == 0) {
            // All resources processed successfully.
            return HttpStatus.ACCEPTED;
        }
        if (response.successCount() > 0) {
            // Partial success — surface as 207 so callers can inspect per-resource results.
            return HttpStatus.MULTI_STATUS;
        }
        if (hasRetryableFailure(response)) {
            // All failed and at least one looks transient (downstream 5xx / timeout / connection
            // error). Return 503 so the caller (e.g. cce-intelligence-service) retries with backoff
            // instead of treating it as a permanent rejection.
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        // All failed due to validation / mapping issues — non-retryable.
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }

    public boolean hasRetryableFailure(ProcessingResponse response) {
        if (response.results() == null) return false;
        for (RoutingResult r : response.results()) {
            if (isRetryableFailure(r)) return true;
        }
        return false;
    }

    /**
     * A failure is treated as retryable only when the underlying call indicates a transient
     * server-side or network problem. Definitive client errors (4xx with status, validation
     * rejections) are NOT retried, even if their stack-trace bodies happen to mention
     * "connection" or similar.
     *
     * Retryable conditions:
     *  - HTTP 5xx, 408 Request Timeout, 429 Too Many Requests
     *  - HTTP status 0 (no response received at all — i.e. socket/network exception) AND
     *    the error message hints at a transport-level failure.
     */
    public boolean isRetryableFailure(RoutingResult r) {
        if (r == null || !"failed".equals(r.status())) return false;
        int code = r.httpStatus();
        if (code >= 500 && code <= 599) return true;
        if (code == 408 || code == 429) return true;
        if (code == 0) {
            String msg = r.errorMessage();
            if (msg == null) return true; // unknown transport failure — retry
            String lower = msg.toLowerCase();
            return lower.contains("timeout")
                    || lower.contains("timed out")
                    || lower.contains("unavailable")
                    || lower.contains("unreachable")
                    || lower.contains("refused")
                    || lower.contains("connect");
        }
        return false;
    }
}
