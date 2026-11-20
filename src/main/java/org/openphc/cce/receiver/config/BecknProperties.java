package org.openphc.cce.receiver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings for the Sygnal endpoint ({@code receiver.beckn.*}). See docs/sygnal-integration.md §6.
 *
 * <p>Bound only when {@code receiver.beckn.enabled=true} (by {@code BecknConfig}). {@link #validate()}
 * then makes the start fail when an id is missing.
 *
 * @param self OpenMRS on the Sygnal network: its subscriber id, which a {@code sygnal/signal/emit} must
 *             carry as {@code context.receiverId}
 * @param cce  CCE: its subscriber id, which a {@code sygnal/signal/emit} must carry as
 *             {@code context.senderId}
 */
@ConfigurationProperties(prefix = "receiver.beckn")
public record BecknProperties(
        boolean enabled,
        Self self,
        Cce cce
) {

    public BecknProperties {
        if (self == null) self = new Self(null);
        if (cce == null) cce = new Cce(null);
    }

    /** OpenMRS's subscriber id in the network registry, e.g. {@code bap.openmrs.nextsteps.mdtlabs.org}. */
    public record Self(String id) {
    }

    /** CCE's subscriber id, e.g. {@code bpp.cce.nextsteps.mdtlabs.org}. */
    public record Cce(String id) {
    }

    /** Throws when a required setting is missing, naming every missing one. */
    public void validate() {
        List<String> missing = new ArrayList<>();
        require(missing, self.id(), "receiver.beckn.self.id");
        require(missing, cce.id(), "receiver.beckn.cce.id");
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
