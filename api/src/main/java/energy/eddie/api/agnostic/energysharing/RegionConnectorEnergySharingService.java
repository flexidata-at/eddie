// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.api.agnostic.energysharing;

import java.util.List;
import java.util.Optional;

/**
 * Optional country-neutral market-management capability. It does not grant data-access permission.
 * Implementations authorize the configured operator AND connection scope on every call, validate a
 * pinned national profile, persist idempotency before transport, and reconcile authoritative replies.
 * A repeated request ID with a different payload must fail; uncertain sends must never be blindly retried.
 * These methods are only exposed on the trusted management port, behind the eligible party's tenant authorization.
 */
public interface RegionConnectorEnergySharingService {
    EnergySharingCapabilities capabilities(String operatorId, String connectionId);
    EnergySharingOperation submit(EnergySharingRequest request);
    Optional<EnergySharingOperation> operation(String requestId, String connectionId, String operatorId, String communityId);
    List<EnergySharingCommunitySnapshot> communities(String operatorId, String connectionId);
}
