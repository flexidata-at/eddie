// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.*;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static energy.eddie.api.agnostic.energysharing.EnergySharingException.Code.*;

/** Committed, contiguous per-scope sequence; cursor row lock is retained through the document commit. */
@Component
public class EdaOperatorMeasurementStore {
    private static final String SCOPE = "scope";
    private static final String MESSAGE = "message";
    private final EntityManager entityManager;
    private final ObjectMapper mapper;

    public EdaOperatorMeasurementStore(EntityManager entityManager, ObjectMapper mapper) {
        this.entityManager = entityManager;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Stored append(String scope, String snapshotId, String fingerprint,
                       EnergySharingMeasurementDocument document, byte[] source, Function<String, JsonNode> authorize) {
        entityManager.createNativeQuery("INSERT INTO at_eda.operator_measurement_cursor(scope, last_sequence) VALUES (:scope,0) ON CONFLICT DO NOTHING")
                .setParameter(SCOPE, scope).executeUpdate();
        long previous = ((Number) entityManager.createNativeQuery("SELECT last_sequence FROM at_eda.operator_measurement_cursor WHERE scope=:scope FOR UPDATE")
                .setParameter(SCOPE, scope).getSingleResult()).longValue();
        var duplicate = entityManager.createNativeQuery("SELECT fingerprint FROM at_eda.operator_measurement_document WHERE scope=:scope AND source_message_id=:message")
                .setParameter(SCOPE, scope).setParameter(MESSAGE, document.sourceMessageId()).getResultList();
        if (!duplicate.isEmpty()) {
            if (!fingerprint.equals(duplicate.getFirst())) throw new EnergySharingException(CONFLICT, "Source message identity was reused with changed content");
            return new Stored(existingDocument(scope, document.sourceMessageId()), false);
        }
        // Accepted duplicate deliveries retain their original proof even after the rolling roster window moves.
        // For a new document, hold this snapshot lock until its authority proof and source commit together.
        JsonNode authority = authorize.apply(snapshot(snapshotId));
        long sequence = Math.addExact(previous, 1);
        String id = EdaOperatorMeasurementXml.hash((scope + ":" + document.sourceMessageId()).getBytes(StandardCharsets.UTF_8));
        var stored = withAuthority(document, sequence, id, authority);
        String payload = boundedPayload(stored);
        int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
        entityManager.createNativeQuery("""
                INSERT INTO at_eda.operator_measurement_document
                (id,scope,sequence,source_message_id,fingerprint,payload,payload_bytes,reading_count,source_xml)
                VALUES (:id,:scope,:sequence,:message,:fingerprint,:payload,:bytes,:count,:source)
                """)
                .setParameter("id", id).setParameter(SCOPE, scope).setParameter("sequence", sequence)
                .setParameter(MESSAGE, document.sourceMessageId()).setParameter("fingerprint", fingerprint)
                .setParameter("payload", payload).setParameter("bytes", bytes).setParameter("count", document.readings().size())
                .setParameter("source", source).executeUpdate();
        entityManager.createNativeQuery("UPDATE at_eda.operator_measurement_cursor SET last_sequence=:sequence WHERE scope=:scope")
                .setParameter("sequence", sequence).setParameter(SCOPE, scope).executeUpdate();
        return new Stored(stored, true);
    }

    /** Validation performs no cursor insert or other write. Real import rechecks under the existing append lock. */
    @Transactional
    public void validateArchive(String scope, String snapshotId, String fingerprint,
            EnergySharingMeasurementDocument document, Function<String, JsonNode> authorize) {
        var duplicate = entityManager.createNativeQuery("SELECT fingerprint FROM at_eda.operator_measurement_document WHERE scope=:scope AND source_message_id=:message")
                .setParameter(SCOPE, scope).setParameter(MESSAGE, document.sourceMessageId()).getResultList();
        if (!duplicate.isEmpty()) {
            if (!fingerprint.equals(duplicate.getFirst())) throw new EnergySharingException(CONFLICT, "Source message identity was reused with changed content");
            var existing = existingDocument(scope, document.sourceMessageId());
            if (!existing.rawPayloadSha256().equals(document.rawPayloadSha256()))
                throw new EnergySharingException(CONFLICT, "An accepted document already has different original bytes");
            return;
        }
        var authority = authorize.apply(snapshot(snapshotId));
        boundedPayload(withAuthority(document, 0, "", authority));
    }

    private String boundedPayload(EnergySharingMeasurementDocument document) {
        String payload = mapper.writeValueAsString(document);
        if (payload.getBytes(StandardCharsets.UTF_8).length > EdaOperatorMeasurementXml.MAX_JSON_BYTES)
            throw new EnergySharingException(INVALID_REQUEST, "Projected measurement document exceeds 32 MiB");
        return payload;
    }

    private static EnergySharingMeasurementDocument withAuthority(EnergySharingMeasurementDocument document,
            long sequence, String id, JsonNode authority) {
        var nativeParameters = (ObjectNode) document.nativeParameters();
        nativeParameters.set("authorityEvidence", authority);
        return new EnergySharingMeasurementDocument(sequence, id, document.sourceMessageId(), document.sourceConversationId(),
                document.sourceSchemaVersion(), document.sourceDocumentCreatedAt(), document.receivedAt(), document.meteringPointId(),
                document.codingScheme(), document.senderId(), document.receiverId(), document.rawPayloadSha256(), document.readings(), nativeParameters);
    }

    private EnergySharingMeasurementDocument existingDocument(String scope, String sourceMessageId) {
        String payload = (String) entityManager.createNativeQuery("SELECT payload FROM at_eda.operator_measurement_document WHERE scope=:scope AND source_message_id=:message")
                .setParameter(SCOPE, scope).setParameter(MESSAGE, sourceMessageId).getSingleResult();
        return mapper.readValue(payload, EnergySharingMeasurementDocument.class);
    }

    public record Stored(EnergySharingMeasurementDocument document, boolean created) {}
    record Page(long nextSequence, boolean hasMore, List<EnergySharingMeasurementDocument> documents) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Page page(String scope, long after, int limit) {
        var candidates = entityManager.createNativeQuery("""
                SELECT id,sequence,payload_bytes,reading_count FROM at_eda.operator_measurement_document
                WHERE scope=:scope AND sequence>:after ORDER BY sequence
                """).setParameter(SCOPE, scope).setParameter("after", after).setMaxResults(limit + 1).getResultList();
        var documents = new ArrayList<EnergySharingMeasurementDocument>();
        long next = after;
        int bytes = 0;
        int readings = 0;
        int scanned = 0;
        for (Object candidate : candidates) {
            var row = (Object[]) candidate;
            int documentBytes = ((Number) row[2]).intValue();
            int documentReadings = ((Number) row[3]).intValue();
            if (scanned >= limit || bytes + documentBytes > EdaOperatorMeasurementXml.MAX_JSON_BYTES
                    || readings + documentReadings > EdaOperatorMeasurementXml.MAX_READINGS) break;
            String payload = (String) entityManager.createNativeQuery("SELECT payload FROM at_eda.operator_measurement_document WHERE id=:id AND scope=:scope")
                    .setParameter("id", row[0]).setParameter(SCOPE, scope).getSingleResult();
            var document = mapper.readValue(payload, EnergySharingMeasurementDocument.class);
            documents.add(document);
            bytes += documentBytes;
            readings += documentReadings;
            next = ((Number) row[1]).longValue();
            scanned++;
        }
        return new Page(next, scanned < candidates.size(), List.copyOf(documents));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public byte[] source(String scope, String documentId) {
        var rows = entityManager.createNativeQuery("SELECT source_xml FROM at_eda.operator_measurement_document WHERE scope=:scope AND id=:id")
                .setParameter(SCOPE, scope).setParameter("id", documentId).getResultList();
        if (rows.isEmpty()) throw new EnergySharingException(NOT_FOUND, "Measurement source not found");
        return ((byte[]) rows.getFirst()).clone();
    }

    public record Delivery(long documentCount, long readingCount) {}

    /** Read accepted ledger metadata only. Native CR_REQ_PT04.30 mandates the same ConversationId. */
    @Transactional(readOnly = true)
    public Delivery requestDelivery(String scope, String conversationId, String meteringPointId) {
        var row = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*), COALESCE(SUM(reading_count),0) FROM at_eda.operator_measurement_document
                WHERE scope=:scope AND CAST(payload AS jsonb)->>'sourceConversationId'=:conversation
                  AND CAST(payload AS jsonb)->>'meteringPointId'=:meter
                """).setParameter(SCOPE, scope).setParameter("conversation", conversationId)
                .setParameter("meter", meteringPointId).getSingleResult();
        return new Delivery(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
    }

    private String snapshot(String id) {
        var rows = entityManager.createNativeQuery("SELECT payload FROM at_eda.energy_sharing_record WHERE id=:id AND kind='SNAPSHOT' FOR SHARE")
                .setParameter("id", id).getResultList();
        if (rows.isEmpty()) throw new EnergySharingException(NOT_READY, "Synchronize authoritative community membership first");
        return (String) rows.getFirst();
    }
}
