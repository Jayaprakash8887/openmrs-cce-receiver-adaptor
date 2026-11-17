package org.openphc.cce.receiver.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.openphc.cce.receiver.beckn.BecknCallbackService;
import org.openphc.cce.receiver.beckn.BecknReferralService;
import org.openphc.cce.receiver.beckn.ConditionalOnBecknEnabled;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Beckn endpoints, called by {@code openmrs-onix} (docs/beckn-integration.md §3). They exist only
 * when {@code receiver.beckn.enabled=true}. onix has already checked the Beckn signature and the
 * schema, and appends the action to the path.
 */
@RestController
@RequestMapping("/beckn")
@ConditionalOnBecknEnabled
public class BecknWebhookController {

    private final BecknReferralService referrals;
    private final BecknCallbackService callbacks;

    public BecknWebhookController(BecknReferralService referrals, BecknCallbackService callbacks) {
        this.referrals = referrals;
        this.callbacks = callbacks;
    }

    /** Requests to OpenMRS as provider, from {@code onix-bpp}: {@code update} = a referral from Spice. */
    @PostMapping(value = "/bpp-webhook/{action}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> bppWebhook(@PathVariable String action,
                                               @RequestBody(required = false) String body) {
        return referrals.handle(action, body);
    }

    /** Callbacks to OpenMRS as consumer, from {@code onix-bap}: {@code on_update}, {@code on_status}. */
    @PostMapping(value = "/bap-webhook/{action}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> bapWebhook(@PathVariable String action,
                                               @RequestBody(required = false) String body) {
        return callbacks.handle(action, body);
    }
}
