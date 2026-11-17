package org.openphc.cce.receiver.controller;

import org.openphc.cce.receiver.model.ProcessingResponse;
import org.openphc.cce.receiver.service.InboundProcessingService;
import org.openphc.cce.receiver.service.ProcessingStatusResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class InboundResourceController {

    private static final Logger log = LoggerFactory.getLogger(InboundResourceController.class);

    private final InboundProcessingService processingService;
    private final ProcessingStatusResolver statusResolver;

    public InboundResourceController(InboundProcessingService processingService,
                                     ProcessingStatusResolver statusResolver) {
        this.processingService = processingService;
        this.statusResolver = statusResolver;
    }

    @PostMapping(value = "/openmrs/fhir", consumes = {"application/fhir+json", "application/json"})
    public ResponseEntity<ProcessingResponse> receiveBundle(
            @RequestBody String payload,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId,
            @RequestHeader(value = "X-Source-System", required = false) String sourceSystem,
            @RequestHeader(value = "X-CCE-Intelligence-Delivery-Id", required = false) String intelligenceDeliveryId,
            @RequestHeader(value = "X-CCE-Intelligence-Event-Id", required = false) String intelligenceEventId) {

        log.info("Received inbound FHIR payload - correlationId: {}, source: {}, " +
                        "intelligenceDeliveryId: {}, intelligenceEventId: {}, size: {} bytes",
                correlationId, sourceSystem, intelligenceDeliveryId, intelligenceEventId,
                payload != null ? payload.length() : 0);

        ProcessingResponse response = processingService.process(payload, correlationId, sourceSystem);

        HttpStatus status = statusResolver.resolve(response);

        return ResponseEntity.status(status).body(response);
    }
}
