// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.api.agnostic.energysharing;

import tools.jackson.databind.JsonNode;
import java.util.Objects;

/** A durable idempotency key and explicit national profile accompany every command. */
public record EnergySharingRequest(String requestId, String connectionId, String operatorId,
                                   String communityId, String operation, String profileVersion,
                                   JsonNode parameters) {
    public EnergySharingRequest {
        requireText(requestId, "requestId");
        requireText(connectionId, "connectionId");
        requireText(operatorId, "operatorId");
        requireText(operation, "operation");
        requireText(profileVersion, "profileVersion");
        Objects.requireNonNull(communityId, "communityId");
        if (communityId.isBlank() && !"SYNC_MASTER_DATA".equals(operation)) {
            throw new IllegalArgumentException("communityId is required for this operation");
        }
        Objects.requireNonNull(parameters, "parameters");
        if (!parameters.isObject()) {
            throw new IllegalArgumentException("parameters must be an object");
        }
        parameters = parameters.deepCopy();
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be non-empty text of at most 256 characters");
        }
    }
}
