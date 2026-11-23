package org.openphc.cce.receiver.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.openphc.cce.receiver.beckn.ConditionalOnBecknEnabled;
import org.openphc.cce.receiver.beckn.SignalEmitService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Sygnal endpoint, called by {@code onix-openmrs} (docs/sygnal-integration.md §3). It exists only
 * when {@code receiver.beckn.enabled=true}. onix has already checked the signature and the schema, and
 * forwards each action to the path of the same name.
 */
@RestController
@ConditionalOnBecknEnabled
public class SygnalController {

    private final SignalEmitService signals;

    public SygnalController(SignalEmitService signals) {
        this.signals = signals;
    }

    /**
     * A CCE care alert: the {@code message} of the request is a {@code sygnal:Signal} with the
     * care-alert fields directly on it (the CCE profile of Sygnal, v3.0).
     */
    @PostMapping(value = "/sygnal/signal/emit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> signalEmit(@RequestBody(required = false) String body) {
        return signals.handle(body);
    }
}
