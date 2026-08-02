// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.permission.request.events;

import energy.eddie.api.agnostic.data.needs.AccountingPointDataNeedResult;
import energy.eddie.api.agnostic.data.needs.Timeframe;
import energy.eddie.dataneeds.needs.AccountingPointDataNeed;
import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static energy.eddie.regionconnector.at.eda.EdaRegionConnectorMetadata.AT_ZONE_ID;
import static org.assertj.core.api.Assertions.assertThat;

class ValidatedEventFactoryTest {
    @Test
    void createValidatedEvent_prefixesConversationId() {
        var event = createValidatedEvent("TEST");

        assertThat(event.conversationId()).startsWith("TESTEP123456T");
    }

    @Test
    void createValidatedEvent_filtersCharactersDisallowedInConversationIdPrefix() {
        var event = createValidatedEvent("DEMO-");

        assertThat(event.conversationId()).startsWith("DEMOEP123456T");
    }

    @Test
    void createValidatedEvent_usesEligiblePartyIdWhenConversationIdPrefixHasNoValidCharacters() {
        var event = createValidatedEvent("-_+#");

        assertThat(event.conversationId()).startsWith("EP123456T");
    }

    @Test
    void createValidatedEvent_usesEligiblePartyIdWhenConversationIdPrefixIsNotConfigured() {
        var event = createValidatedEvent(null);

        assertThat(event.conversationId()).startsWith("EP123456T");
    }

    @Test
    void createValidatedEvent_usesEligiblePartyIdWhenConversationIdPrefixIsBlank() {
        var event = createValidatedEvent(" ");

        assertThat(event.conversationId()).startsWith("EP123456T");
    }

    private static ValidatedEvent createValidatedEvent(@Nullable String conversationIdPrefix) {
        var configuration = new AtConfiguration("EP123456", null, null, conversationIdPrefix);
        var factory = new ValidatedEventFactory(configuration);
        var timeframe = new Timeframe(LocalDate.now(AT_ZONE_ID), LocalDate.now(AT_ZONE_ID));

        return factory.createValidatedEvent(
                "permission-id",
                timeframe.start(),
                timeframe.end(),
                null,
                new AccountingPointDataNeedResult(timeframe, new AccountingPointDataNeed())
        );
    }
}
