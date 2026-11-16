// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.api.agnostic.energysharing;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;

/** Append-only source document. Sequence is delivery order, never the precedence of a correction. */
public record EnergySharingMeasurementDocument(long sequence, String documentId,
        String sourceMessageId, String sourceConversationId, String sourceSchemaVersion,
        Instant sourceDocumentCreatedAt, Instant receivedAt, String meteringPointId, String codingScheme,
        String senderId, String receiverId, String rawPayloadSha256,
        List<EnergySharingMeasurement> readings, JsonNode nativeParameters) {
    public EnergySharingMeasurementDocument {
        readings = List.copyOf(readings);
        nativeParameters = nativeParameters.deepCopy();
    }
    @Override public JsonNode nativeParameters() { return nativeParameters.deepCopy(); }
}
