package org.openphc.cce.receiver.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** The Sygnal settings: bound only when enabled, and the start fails when an id is missing. */
class BecknConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BecknConfig.class);

    private static final String[] COMPLETE = {
            "receiver.beckn.enabled=true",
            "receiver.beckn.self.id=bap.openmrs.nextsteps.mdtlabs.org",
            "receiver.beckn.cce.id=bpp.cce.nextsteps.mdtlabs.org"
    };

    @Test
    void nothing_is_loaded_when_disabled() {
        runner.withPropertyValues("receiver.beckn.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(BecknConfig.class);
            assertThat(ctx).doesNotHaveBean(BecknProperties.class);
            assertThat(ctx).doesNotHaveBean(BecknConfig.CARE_ALERT_EXECUTOR);
        });
    }

    @Test
    void on_by_default_and_fails_fast_without_ids() {
        // On unless switched off. The ids' defaults live in application.yml, which this runner does not
        // read, so a start without them must fail.
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void starts_with_every_setting() {
        runner.withPropertyValues(COMPLETE).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            BecknProperties p = ctx.getBean(BecknProperties.class);
            assertThat(p.self().id()).isEqualTo("bap.openmrs.nextsteps.mdtlabs.org");
            assertThat(p.cce().id()).isEqualTo("bpp.cce.nextsteps.mdtlabs.org");
            assertThat(ctx).hasBean(BecknConfig.CARE_ALERT_EXECUTOR);
        });
    }

    @Test
    void fails_fast_when_ids_are_missing() {
        runner.withPropertyValues("receiver.beckn.enabled=true", "receiver.beckn.self.id=x",
                "receiver.beckn.cce.id= ").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining("receiver.beckn.cce.id")
                    .hasMessageNotContaining("receiver.beckn.self.id");
        });
    }
}
