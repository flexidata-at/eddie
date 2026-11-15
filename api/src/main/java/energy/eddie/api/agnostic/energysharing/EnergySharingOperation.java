// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.api.agnostic.energysharing;

import jakarta.annotation.Nullable;
import tools.jackson.databind.JsonNode;
import java.time.Instant;

/** Asynchronous transport and market status; SUBMITTED is not accepted membership. */
public record EnergySharingOperation(String requestId, String connectionId, String operatorId,
                                     String communityId, String operation, String status,
                                     Instant createdAt, Instant updatedAt,
                                     @Nullable String marketMessageId, @Nullable String nativeCode,
                                     @Nullable String message, JsonNode result) {
    public EnergySharingOperation {
        result = result.deepCopy();
    }
}
