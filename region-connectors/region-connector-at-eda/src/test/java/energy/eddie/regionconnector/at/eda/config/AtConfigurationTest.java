// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.config;

import energy.eddie.regionconnector.at.eda.requests.EdaGroupingIdFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Optional;
import java.util.Map;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class AtConfigurationTest {
    private static final String PREFIX = "region-connector.at.eda.";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EnableConfig.class)
            .withPropertyValues(PREFIX + "eligibleparty.id=EP123456");

    @Test
    void omittedConversationIdPrefix_usesEmptyDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AtConfiguration.class).conversationIdPrefix()).isEmpty();
        });
    }

    @Test
    void configuredConversationIdPrefix_isBound() {
        contextRunner
                .withPropertyValues(PREFIX + "conversation-id.prefix=DEV1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AtConfiguration.class).conversationIdPrefix()).isEqualTo("DEV1");
                });
    }

    @Test
    void deployedConversationPrefixEnvironmentVariable_isBound() {
        contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("deployment", Map.of(
                        "REGION_CONNECTOR_AT_EDA_CONVERSATION_ID_PREFIX", "DATADEV"
                ))
        )).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AtConfiguration.class).conversationIdPrefix()).isEqualTo("DATADEV");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEV-", "DEV_1", "+"})
    void invalidConversationIdPrefix_failsStartup(String prefix) {
        contextRunner
                .withPropertyValues(PREFIX + "conversation-id.prefix=" + prefix)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("must contain only ASCII letters and digits");
                });
    }

    @Test
    void conversationIdPrefixProducingOverlongGroupingId_failsStartup() {
        contextRunner
                .withPropertyValues(PREFIX + "conversation-id.prefix=12345678901234")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageStartingWith(
                                    "region-connector.at.eda.conversation-id.prefix is too long " +
                                    "for the configured eligible party ID:"
                            )
                            .hasMessageContaining("exceeds the maximum of 35 characters");
                });
    }

    @Test
    void blankEligiblePartyId_failsStartup() {
        contextRunner
                .withPropertyValues(PREFIX + "eligibleparty.id=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void atConfiguration_bindsExplicitCommunityAllowlistWithoutLegacyDefault() {
        contextRunner.withPropertyValues(
                PREFIX + "allowed-energy-community-ids=community-a,community-b"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            var configuration = context.getBean(AtConfiguration.class);
            assertThat(configuration.resolveEnergyCommunityId(Optional.of("community-b"))).contains("community-b");
            assertThat(configuration.resolveEnergyCommunityId(Optional.of("other-community"))).isEmpty();
            assertThat(configuration.resolveEnergyCommunityId(Optional.empty())).isEmpty();
            assertThat(configuration.isEnergyCommunityMarketParty("RC123456")).isFalse();
            assertThat(configuration.isEnergyCommunityMarketParty("EP123456")).isTrue();
        });
    }

    @Test
    void legacyCommunityConfiguration_usesEligiblePartyAndDefaultCommunity() {
        contextRunner.withPropertyValues(PREFIX + "energy-community-id=community-a").run(context -> {
            assertThat(context).hasNotFailed();
            var configuration = context.getBean(AtConfiguration.class);
            assertThat(configuration.supportsEnergyCommunity()).isTrue();
            assertThat(configuration.resolveEnergyCommunityId(Optional.empty())).contains("community-a");
            assertThat(configuration.resolveEnergyCommunityId(Optional.of("community-b"))).isEmpty();
            assertThat(configuration.eligiblePartyId()).isEqualTo("EP123456");
            assertThat(configuration.isEnergyCommunityMarketParty("EP123456")).isTrue();
            assertThat(configuration.isEnergyCommunityMarketParty("RC123456")).isFalse();
        });
    }

    @Test
    void legacyConstructor_preservesConversationPrefixArgument() {
        var configuration = new AtConfiguration("EP123456", "community-a", "DEV");
        assertThat(configuration.conversationIdPrefix()).isEqualTo("DEV");
        assertThat(configuration.eligiblePartyId()).isEqualTo("EP123456");
    }

    @Test
    void missingCommunityConfiguration_doesNotEnableCommunityRequests() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var configuration = context.getBean(AtConfiguration.class);
            assertThat(configuration.supportsEnergyCommunity()).isFalse();
            assertThat(configuration.isEnergyCommunityMarketParty("EP123456")).isFalse();
            assertThat(configuration.resolveEnergyCommunityId(Optional.of("community-a"))).isEmpty();
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AtConfiguration.class)
    @Import(EdaGroupingIdFactory.class)
    static class EnableConfig {
    }
}
