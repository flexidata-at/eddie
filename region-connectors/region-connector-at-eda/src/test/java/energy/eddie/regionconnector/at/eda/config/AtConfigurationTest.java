// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.config;

import energy.eddie.regionconnector.at.eda.ponton.PontonXPAdapterConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AtConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void atConfiguration_bindsWithoutConversationIdPrefix() {
        contextRunner
                .withPropertyValues("region-connector.at.eda.eligibleparty.id=EP123456")
                .run(context -> assertThat(context.getBean(AtConfiguration.class).conversationIdPartyIdFor(
                        AtConfiguration.PartyIdType.ELIGIBLE_PARTY
                )).isEqualTo("EP123456"));
    }

    @Test
    void atConfiguration_filtersCharactersDisallowedInConversationIdPrefix() {
        contextRunner
                .withPropertyValues(
                        "region-connector.at.eda.eligibleparty.id=EP123456",
                        "region-connector.at.eda.conversation-id.prefix=DEMO-_+#"
                )
                .run(context -> assertThat(context.getBean(AtConfiguration.class).conversationIdPartyIdFor(
                        AtConfiguration.PartyIdType.ELIGIBLE_PARTY
                )).isEqualTo("DEMOEP123456"));
    }

    @Test
    void atConfiguration_ignoresConversationIdPrefixWhenNoValidCharactersRemain() {
        contextRunner
                .withPropertyValues(
                        "region-connector.at.eda.eligibleparty.id=EP123456",
                        "region-connector.at.eda.conversation-id.prefix=-_+#"
                )
                .run(context -> assertThat(context.getBean(AtConfiguration.class).conversationIdPartyIdFor(
                        AtConfiguration.PartyIdType.ELIGIBLE_PARTY
                )).isEqualTo("EP123456"));
    }

    @Test
    void pontonXPAdapterConfiguration_bindsWithDefaultConnectionWatchdogProperties() {
        contextRunner
                .withPropertyValues(
                        "region-connector.at.eda.ponton.messenger.adapter.id=adapter",
                        "region-connector.at.eda.ponton.messenger.adapter.version=1.0",
                        "region-connector.at.eda.ponton.messenger.hostname=localhost",
                        "region-connector.at.eda.ponton.messenger.port=2600",
                        "region-connector.at.eda.ponton.messenger.api.endpoint=http://localhost/api",
                        "region-connector.at.eda.ponton.messenger.folder=/tmp",
                        "region-connector.at.eda.ponton.messenger.username=user",
                        "region-connector.at.eda.ponton.messenger.password=password"
                )
                .run(context -> assertThat(context.getBean(PontonXPAdapterConfiguration.class).inboundConnections())
                        .isEqualTo(1));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AtConfiguration.class, PontonXPAdapterConfiguration.class})
    static class TestConfig {
    }
}
