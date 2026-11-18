package org.openphc.cce.receiver.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** The Beckn settings: bound only when enabled, and the start fails when an id is missing. */
class BecknConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BecknConfig.class);

    private static final String[] COMPLETE = {
            "receiver.beckn.enabled=true",
            "receiver.beckn.self.bpp-id=bpp.openmrs.example.org",
            "receiver.beckn.self.bpp-uri=https://openmrs.example.org/bpp/receiver",
            "receiver.beckn.spice.bap-id=bap.spice.example.org",
            "receiver.beckn.spice.bap-uri=https://spice.example.org/bap/receiver",
            "receiver.beckn.spice.referrals-in.commitment-file=classpath:beckn/commitment-referral.json"
    };

    @Test
    void nothing_is_loaded_when_disabled() {
        runner.withPropertyValues("receiver.beckn.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(BecknConfig.class);
            assertThat(ctx).doesNotHaveBean(BecknProperties.class);
        });
    }

    @Test
    void on_by_default_and_fails_fast_without_ids() {
        // demo-beckn: Beckn is on unless switched off, so a start without the ids must fail.
        runner.run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void starts_with_every_setting() {
        runner.withPropertyValues(COMPLETE).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            BecknProperties p = ctx.getBean(BecknProperties.class);
            assertThat(p.onix().bppCallerUrl()).isEqualTo("http://onix-bpp:8082/bpp/caller");
            assertThat(p.spice().referralsIn().commitmentFile().exists()).isTrue();
        });
    }

    @Test
    void fails_fast_when_ids_are_missing() {
        runner.withPropertyValues("receiver.beckn.enabled=true", "receiver.beckn.self.bpp-id=x").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining("receiver.beckn.self.bpp-uri")
                    .hasMessageContaining("receiver.beckn.spice.bap-id")
                    .hasMessageNotContaining("contract-id")
                    .hasMessageNotContaining("receiver.beckn.self.bpp-id,");
        });
    }

    @Test
    void trailing_slash_is_removed_from_the_caller_url() {
        assertThat(BecknConfig.withoutTrailingSlash("http://onix-bpp:8082/bpp/caller/")).isEqualTo(
                "http://onix-bpp:8082/bpp/caller");
    }
}
