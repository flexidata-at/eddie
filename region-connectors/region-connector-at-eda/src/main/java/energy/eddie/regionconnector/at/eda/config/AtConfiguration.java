// SPDX-FileCopyrightText: 2023-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

import java.util.Optional;
import java.util.Set;

/**
 * The main configuration for the EDA region connector.
 *
 * @param eligiblePartyId      ID that will be used as the sender for all messages sent to EDA.
 *                             This ID must be registered with EDA at <a href="https://www.ebutilities.at/registrierung">ebUtilities</a>.
 * @param conversationIdPrefix Optional prefix used for generated EDA message and conversation IDs.
 */
@Validated
@ConfigurationProperties("region-connector.at.eda")
public record AtConfiguration(
        @Name("eligibleparty.id") @NotBlank String eligiblePartyId,
        @Nullable String energyCommunityId,
        @Name("conversation-id.prefix")
        @NotNull
        @Pattern(regexp = CONVERSATION_ID_PREFIX_PATTERN,
                message = "must contain only ASCII letters and digits")
        @DefaultValue("") String conversationIdPrefix,
        @Nullable Set<String> allowedEnergyCommunityIds
) {
    public static final String CONVERSATION_ID_PREFIX_PATTERN = "[0-9A-Za-z]*";

    @ConstructorBinding
    public AtConfiguration {
    }

    public AtConfiguration(
            String eligiblePartyId,
            @Nullable String energyCommunityId,
            String conversationIdPrefix
    ) {
        this(eligiblePartyId, energyCommunityId, conversationIdPrefix, null);
    }

    public boolean supportsEnergyCommunity() {
        return hasEnergyCommunityIds();
    }

    public Optional<String> resolveEnergyCommunityId(Optional<String> requestedCommunityId) {
        if (!supportsEnergyCommunity()) {
            return Optional.empty();
        }
        return requestedCommunityId
                .or(() -> Optional.ofNullable(energyCommunityId))
                .filter(id -> !id.isBlank() && (id.equals(energyCommunityId)
                        || (allowedEnergyCommunityIds != null && allowedEnergyCommunityIds.contains(id))));
    }

    public boolean isEnergyCommunityMarketParty(@Nullable String partyId) {
        return supportsEnergyCommunity() && partyId != null && !partyId.isBlank() && partyId.equals(eligiblePartyId);
    }

    private boolean hasEnergyCommunityIds() {
        return (energyCommunityId != null && !energyCommunityId.isBlank())
                || (allowedEnergyCommunityIds != null && !allowedEnergyCommunityIds.isEmpty());
    }

    public enum PartyIdType {
        ENERGY_COMMUNITY,
        ELIGIBLE_PARTY
    }
}
