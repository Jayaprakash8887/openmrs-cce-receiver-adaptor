package org.openphc.cce.receiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings for the Beckn endpoints ({@code receiver.beckn.*}). See docs/beckn-integration.md §7.
 *
 * <p>Bound only when {@code receiver.beckn.enabled=true} (by {@code BecknConfig}). {@link #validate()}
 * then makes the start fail when an id is missing.
 */
@ConfigurationProperties(prefix = "receiver.beckn")
public record BecknProperties(
        boolean enabled,
        Onix onix,
        Self self,
        Spice spice,
        String healthIdTypes
) {

    public BecknProperties {
        if (onix == null) onix = new Onix(null);
        if (self == null) self = new Self(null, null, null);
        if (spice == null) spice = new Spice(null, null, null);
        if (healthIdTypes == null) healthIdTypes = "";
    }

    /**
     * The OpenMRS identifier type for a health id of a health referral (participant
     * {@code healthIds[].system}, for example {@code ABHA}), from {@code receiver.beckn.health-id-types}:
     * {@code SYSTEM=type} pairs separated by {@code ;} or {@code ,}, systems matched ignoring case.
     * Without a pair the system itself is the type name; OpenMRS creates the type the first time.
     */
    public String identifierTypeFor(String healthIdSystem) {
        for (String pair : healthIdTypes.split("[;,]")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equalsIgnoreCase(healthIdSystem)) {
                String type = pair.substring(eq + 1).trim();
                if (!type.isEmpty()) {
                    return type;
                }
            }
        }
        return healthIdSystem;
    }

    /** {@code onix-bpp}'s caller endpoint, used for every callback ({@code on_update}, {@code on_confirm}, ...). */
    public record Onix(String bppCallerUrl) {
        public Onix {
            if (bppCallerUrl == null || bppCallerUrl.isBlank()) bppCallerUrl = "http://onix-bpp:8082/bpp/caller";
        }
    }

    /** OpenMRS as a provider (BPP), as registered in the network registry. */
    public record Self(String bppId, String bppUri, String networkId) {
    }

    /** The one HMIS peer (Spice). */
    public record Spice(String bapId, String bapUri, ReferralsIn referralsIn) {
        public Spice {
            if (referralsIn == null) referralsIn = new ReferralsIn(null);
        }
    }

    /**
     * The "referrals in" commitment. There is no confirm, so no contract id is issued: the receiver
     * accepts any non-blank one and echoes the request's contract id and transaction id.
     */
    public record ReferralsIn(Resource commitmentFile) {
    }

    /** Throws when a required setting is missing, naming every missing one. */
    public void validate() {
        List<String> missing = new ArrayList<>();
        require(missing, onix.bppCallerUrl(), "receiver.beckn.onix.bpp-caller-url");
        require(missing, self.bppId(), "receiver.beckn.self.bpp-id");
        require(missing, self.bppUri(), "receiver.beckn.self.bpp-uri");
        require(missing, spice.bapId(), "receiver.beckn.spice.bap-id");
        require(missing, spice.bapUri(), "receiver.beckn.spice.bap-uri");
        if (spice.referralsIn().commitmentFile() == null) {
            missing.add("receiver.beckn.spice.referrals-in.commitment-file");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "receiver.beckn.enabled=true, but these settings are missing: " + String.join(", ", missing));
        }
    }

    private static void require(List<String> missing, String value, String name) {
        if (value == null || value.isBlank()) {
            missing.add(name);
        }
    }
}
