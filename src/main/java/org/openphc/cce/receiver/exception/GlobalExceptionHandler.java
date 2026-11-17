package org.openphc.cce.receiver.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FhirParsingException.class)
    public ResponseEntity<Map<String, Object>> handleFhirParsingException(FhirParsingException ex) {
        log.error("FHIR parsing error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "FHIR_PARSING_ERROR",
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(ResourceTransformException.class)
    public ResponseEntity<Map<String, Object>> handleResourceTransformException(ResourceTransformException ex) {
        log.error("Resource transform error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", "TRANSFORM_ERROR",
                "message", ex.getMessage(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(OpenMrsClientException.class)
    public ResponseEntity<Map<String, Object>> handleOpenMrsClientException(OpenMrsClientException ex) {
        log.error("OpenMRS client error (HTTP {}): {}", ex.getStatusCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "error", "OPENMRS_CLIENT_ERROR",
                "message", ex.getMessage(),
                "statusCode", ex.getStatusCode(),
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * A path nothing is mapped to (for example {@code /beckn/**} with {@code OPENMRS_RECEIVER_BECKN_ENABLED=false})
     * is a 404, not a 500 from the catch-all below.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "NOT_FOUND",
                "message", "No endpoint " + ex.getResourcePath(),
                "timestamp", Instant.now().toString()
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "INTERNAL_ERROR",
                "message", "An unexpected error occurred",
                "timestamp", Instant.now().toString()
        ));
    }
}
