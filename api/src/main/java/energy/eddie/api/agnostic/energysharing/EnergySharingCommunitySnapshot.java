// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.api.agnostic.energysharing;

import jakarta.annotation.Nullable;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/**
 * Latest authoritative, correlated snapshot. Revisions are opaque and only compared inside a profile.
 * Complete is true only when every expected response fragment has arrived; partial responses cannot
 * remove absent members. Native fields preserve the national schema without polluting the common model.
 */
public record EnergySharingCommunitySnapshot(String communityId, String operatorId, String connectionId,
                                             String revision, Instant receivedAt, boolean complete,
                                             @Nullable String name, @Nullable String allocationMethod,
                                             List<EnergySharingMeteringPoint> meteringPoints,
                                             JsonNode nativeParameters) {
    public EnergySharingCommunitySnapshot {
        meteringPoints = List.copyOf(meteringPoints);
        nativeParameters = nativeParameters.deepCopy();
    }
}
