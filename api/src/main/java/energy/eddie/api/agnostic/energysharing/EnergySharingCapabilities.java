// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.api.agnostic.energysharing;

import tools.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;

/** Versioned national parameter schemas; transport readiness never implies market approval. */
public record EnergySharingCapabilities(String profileId, String profileVersion, String operatorId,
                                        boolean transportReady, Set<String> operations,
                                        JsonNode parameterSchemas, List<String> setupSteps, String countryCode) {
    public EnergySharingCapabilities {
        if (countryCode == null || !countryCode.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("A two-letter countryCode is required");
        }
        operations = Set.copyOf(operations);
        parameterSchemas = parameterSchemas.deepCopy();
        setupSteps = List.copyOf(setupSteps);
    }
}
