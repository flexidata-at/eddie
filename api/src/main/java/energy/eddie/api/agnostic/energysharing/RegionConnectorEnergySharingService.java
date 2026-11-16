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

    default EnergySharingMeasurementPage measurements(String operatorId, String connectionId, String communityId,
                                                       long afterSequence, int limit) {
        throw new EnergySharingException(EnergySharingException.Code.UNSUPPORTED,
                "This connector does not provide operator measurements");
    }

    /** Exact original XML, available only after the same operator/community authorization as the feed. */
    default byte[] measurementSource(String operatorId, String connectionId, String communityId, String documentId) {
        throw new EnergySharingException(EnergySharingException.Code.UNSUPPORTED,
                "This connector does not provide original operator measurement documents");
    }
    /** Optional recovery of original native operator documents; no outbound market action or customer-consent delivery. */
    default EnergySharingMeasurementImportResult importMeasurement(String operatorId, String connectionId, String communityId,
            EnergySharingMeasurementImport request) {
        throw new EnergySharingException(EnergySharingException.Code.UNSUPPORTED,
                "This connector does not support archived operator measurement import");
    }

}
