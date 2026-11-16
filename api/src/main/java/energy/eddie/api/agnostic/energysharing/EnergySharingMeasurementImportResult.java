// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.api.agnostic.energysharing;

import java.time.Instant;

/**
 * VALIDATED performs no insert; IMPORTED and ALREADY_PRESENT identify the durable scoped document.
 */
public record EnergySharingMeasurementImportResult(
    String countryCode,
    String connectionId,
    String operatorId,
    String communityId,
    String documentId,
    String sourceMessageId,
    Instant sourceDocumentCreatedAt,
    Instant receivedAt,
    long sequence,
    int readingCount,
    String payloadSha256,
    String status,
    boolean dryRun,
    String archiveId,
    Instant archivedAt) {}
