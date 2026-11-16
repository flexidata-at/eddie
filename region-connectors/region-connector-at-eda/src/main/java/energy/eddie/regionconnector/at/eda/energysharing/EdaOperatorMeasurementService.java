// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.*;
import energy.eddie.cim.v1_12.StandardCodingSchemeTypeList;
import energy.eddie.regionconnector.at.eda.dto.EdaConsumptionRecord;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static energy.eddie.api.agnostic.energysharing.EnergySharingException.Code.*;

/** Operator rights come solely from verified configuration and authoritative EDA membership, never CESU. */
@Component
public class EdaOperatorMeasurementService {
    private static final String TIME_PERIODS = "timePeriods";
    private final EdaEnergySharingConfiguration configuration;
    private final EdaOperatorMeasurementStore store;
    private final ObjectMapper mapper;
    private final EdaOperatorMeasurementXml parser;

    public EdaOperatorMeasurementService(EdaEnergySharingConfiguration configuration,
                                        EdaOperatorMeasurementStore store, ObjectMapper mapper) {
        this.configuration = configuration;
        this.store = store;
        this.mapper = mapper;
        this.parser = new EdaOperatorMeasurementXml(mapper);
    }

    public boolean ingest(EdaConsumptionRecord consumptionRecord) {
        var community = consumptionRecord.energyCommunityId();
        if (community.isEmpty()) return false;
        var bindings = configuration.bindings().stream().filter(binding -> binding.partnerConfigured()
                && binding.operatorId().equals(consumptionRecord.receiverMessageAddress())
                && binding.communities().containsKey(community.get())).toList();
        if (bindings.isEmpty()) return false;
        byte[] bytes = consumptionRecord.originalXml().orElseThrow(() -> new EnergySharingException(INVALID_REQUEST, "Exact native operator document is required"));
        var parsed = parser.parse(bytes, Instant.now());
        var document = parsed.document();
        if (!community.get().equals(parsed.communityId()) || !consumptionRecord.messageId().equals(document.sourceMessageId())
                || !consumptionRecord.meteringPoint().equals(document.meteringPointId())) {
            throw new EnergySharingException(INVALID_REQUEST, "Wire and parsed consumption record disagree");
        }
        for (var binding : bindings) {
            requireRouting(binding, parsed.communityId(), document);
            append(binding, parsed, bytes);
        }
        return true;
    }

    private void append(EdaEnergySharingConfiguration.Binding binding, EdaOperatorMeasurementXml.Parsed parsed, byte[] bytes) {
        try {
            store.append(scope(binding, parsed.communityId()), snapshotId(binding, parsed.communityId()), parsed.fingerprint(), parsed.document(), bytes,
                    roster -> authorityProof(roster, binding, parsed.communityId(), parsed.document()));
        } catch (EnergySharingException exception) {
            if (exception.code() == NOT_READY) {
                // Ponton's existing temporary-error handling retries delivery after the roster arrives.
                throw new IllegalStateException("Authoritative operator roster is not available yet", exception);
            }
            throw exception;
        }
    }

    public EnergySharingMeasurementImportResult importArchive(String operatorId, String connectionId, String communityId,
            EnergySharingMeasurementImport request) {
        var binding = binding(operatorId, connectionId, communityId);
        byte[] bytes = request.source();
        if (!EdaOperatorMeasurementXml.hash(bytes).equals(request.payloadSha256()))
            throw new EnergySharingException(INVALID_REQUEST, "Archive payload hash does not match the supplied original bytes");
        Instant now = Instant.now();
        var parsed = parser.parse(bytes, now);
        if (!communityId.equals(parsed.communityId())) throw new EnergySharingException(FORBIDDEN, "Archived native community does not match import scope");
        var document = archiveDocument(parsed.document(), request, now);
        requireRouting(binding, communityId, document);
        String scope = scope(binding, communityId);
        if (request.dryRun()) {
            store.validateArchive(scope, snapshotId(binding, communityId), parsed.fingerprint(), document,
                    roster -> authorityProof(roster, binding, communityId, document));
            return importResult(binding, communityId, document, request, "VALIDATED");
        }
        var stored = store.append(scope, snapshotId(binding, communityId), parsed.fingerprint(), document, bytes,
                roster -> authorityProof(roster, binding, communityId, document));
        if (!stored.document().rawPayloadSha256().equals(request.payloadSha256()))
            throw new EnergySharingException(CONFLICT, "An accepted document already has different original bytes");
        return importResult(binding, communityId, stored.document(), request, stored.created() ? "IMPORTED" : "ALREADY_PRESENT");
    }

    private EnergySharingMeasurementDocument archiveDocument(EnergySharingMeasurementDocument document,
            EnergySharingMeasurementImport request, Instant now) {
        if (request.archivedAt().isAfter(now) || request.archivedAt().isBefore(document.sourceDocumentCreatedAt().minusSeconds(300)))
            throw new EnergySharingException(INVALID_REQUEST, "Archive receipt time is inconsistent with the native source creation time");
        var metadata = (tools.jackson.databind.node.ObjectNode) document.nativeParameters();
        var archive = mapper.createObjectNode();
        archive.put("archiveId", request.archiveId());
        archive.put("originalReceivedAt", request.archivedAt().toString());
        archive.put("acceptedAt", now.toString());
        archive.put("payloadSha256", request.payloadSha256());
        archive.put("delivery", "AUTHENTICATED_ARCHIVE_IMPORT");
        metadata.set("archiveImport", archive);
        return new EnergySharingMeasurementDocument(document.sequence(), document.documentId(), document.sourceMessageId(),
                document.sourceConversationId(), document.sourceSchemaVersion(), document.sourceDocumentCreatedAt(), now,
                document.meteringPointId(), document.codingScheme(), document.senderId(), document.receiverId(),
                document.rawPayloadSha256(), document.readings(), metadata);
    }

    private static EnergySharingMeasurementImportResult importResult(EdaEnergySharingConfiguration.Binding binding,
            String communityId, EnergySharingMeasurementDocument document, EnergySharingMeasurementImport request, String status) {
        return new EnergySharingMeasurementImportResult("AT", binding.connectionId(), binding.operatorId(), communityId,
                document.documentId(), document.sourceMessageId(), document.sourceDocumentCreatedAt(), document.receivedAt(),
                document.sequence(), document.readings().size(), document.rawPayloadSha256(), status, request.dryRun(),
                request.archiveId(), request.archivedAt());
    }

    public EnergySharingMeasurementPage measurements(String operatorId, String connectionId, String communityId,
                                                     long afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > 100) throw new EnergySharingException(INVALID_REQUEST, "Invalid measurement cursor or page limit");
        var binding = binding(operatorId, connectionId, communityId);
        var page = store.page(scope(binding, communityId), afterSequence, limit);
        return new EnergySharingMeasurementPage("AT", connectionId, operatorId, communityId, afterSequence,
                page.nextSequence(), page.hasMore(), page.documents());
    }

    public EdaOperatorMeasurementStore.Delivery requestDelivery(String operatorId, String connectionId, String communityId,
            String conversationId, String meteringPointId) {
        var binding = binding(operatorId, connectionId, communityId);
        return store.requestDelivery(scope(binding, communityId), conversationId, meteringPointId);
    }

    public byte[] source(String operatorId, String connectionId, String communityId, String documentId) {
        var binding = binding(operatorId, connectionId, communityId);
        if (!documentId.matches("[0-9a-f]{64}")) throw new EnergySharingException(NOT_FOUND, "Measurement source not found");
        return store.source(scope(binding, communityId), documentId);
    }

    private EdaEnergySharingConfiguration.Binding binding(String operatorId, String connectionId, String communityId) {
        return configuration.bindings().stream().filter(binding -> binding.operatorId().equals(operatorId)
                && binding.connectionId().equals(connectionId) && binding.partnerConfigured()
                && binding.communities().containsKey(communityId)).findFirst()
                .orElseThrow(() -> new EnergySharingException(FORBIDDEN, "Operator/community is not authorized for this connection"));
    }

    private static void requireRouting(EdaEnergySharingConfiguration.Binding binding, String communityId,
                                       EnergySharingMeasurementDocument document) {
        if (!binding.operatorId().equals(document.receiverId())
                || !document.senderId().equals(binding.communities().get(communityId))) {
            throw new EnergySharingException(FORBIDDEN, "Unexpected operator measurement sender or receiver");
        }
    }

    private tools.jackson.databind.JsonNode authorityProof(String payload, EdaEnergySharingConfiguration.Binding binding,
            String communityId, EnergySharingMeasurementDocument document) {
        var point = authorizedPoint(payload, binding, communityId, document).orElseThrow(() ->
                new EnergySharingException(FORBIDDEN, "Operator measurement is outside authoritative community membership"));
        var snapshot = mapper.treeToValue(mapper.readTree(payload).path("snapshot"), EnergySharingCommunitySnapshot.class);
        var evidence = mapper.createObjectNode();
        evidence.put("countryCode", "AT");
        evidence.put("connectionId", binding.connectionId());
        evidence.put("operatorId", binding.operatorId());
        evidence.put("communityId", communityId);
        evidence.put("senderId", document.senderId());
        evidence.put("meteringPointId", point.meteringPointId());
        evidence.put("codingScheme", point.codingScheme());
        evidence.put("snapshotRevision", snapshot.revision());
        evidence.put("snapshotReceivedAt", snapshot.receivedAt().toString());
        evidence.put("snapshotSha256", EdaOperatorMeasurementXml.hash(payload.getBytes(StandardCharsets.UTF_8)));
        evidence.set(TIME_PERIODS, point.nativeParameters().path(TIME_PERIODS).deepCopy());
        return evidence;
    }

    boolean authorized(String payload, EdaEnergySharingConfiguration.Binding binding, String communityId,
                       EnergySharingMeasurementDocument document) {
        return authorizedPoint(payload, binding, communityId, document).isPresent();
    }

    private Optional<EnergySharingMeteringPoint> authorizedPoint(String payload,
            EdaEnergySharingConfiguration.Binding binding, String communityId, EnergySharingMeasurementDocument document) {
        requireRouting(binding, communityId, document);
        var snapshot = mapper.treeToValue(mapper.readTree(payload).path("snapshot"), EnergySharingCommunitySnapshot.class);
        if (!snapshot.complete() || !snapshot.operatorId().equals(binding.operatorId())
                || !snapshot.connectionId().equals(binding.connectionId()) || !snapshot.communityId().equals(communityId)) return Optional.empty();
        var matching = snapshot.meteringPoints().stream().filter(point -> point.meteringPointId().equals(document.meteringPointId())
                && StandardCodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME.value().equals(point.codingScheme())
                && document.senderId().equals(point.permissionAdministratorId())).toList();
        if (matching.size() != 1) return Optional.empty();
        var point = matching.getFirst();
        return covers(point, document) ? Optional.of(point) : Optional.empty();
    }

    private static boolean covers(EnergySharingMeteringPoint point, EnergySharingMeasurementDocument document) {
            var periods = new java.util.ArrayList<Period>();
            for (var period : point.nativeParameters().path(TIME_PERIODS)) {
                if (!period.hasNonNull("start") || !period.hasNonNull("end") || !period.hasNonNull("activate")) continue;
                LocalDate from = LocalDate.parse(period.path("start").asText());
                LocalDate to = LocalDate.parse(period.path("end").asText());
                LocalDate activated = LocalDate.parse(period.path("activate").asText());
                if (activated.isAfter(from)) from = activated;
                if (period.hasNonNull("deactivate")) {
                    var deactivated = LocalDate.parse(period.path("deactivate").asText());
                    if (deactivated.isBefore(to)) to = deactivated;
                }
                if (!to.isBefore(from)) periods.add(new Period(from.atStartOfDay(EdaEnergySharingXml.VIENNA).toInstant(),
                        to.plusDays(1).atStartOfDay(EdaEnergySharingXml.VIENNA).toInstant()));
            }
            periods.sort(Comparator.comparing(Period::start));
            return !periods.isEmpty() && document.readings().stream().allMatch(reading -> covers(periods, reading.start(), reading.end()));
    }

    private static boolean covers(List<Period> periods, Instant start, Instant end) {
        Instant covered = start;
        for (var period : periods) {
            if (period.end().isBefore(covered)) continue;
            if (period.start().isAfter(covered)) return false;
            if (period.end().isAfter(covered)) covered = period.end();
            if (!covered.isBefore(end)) return true;
        }
        return false;
    }

    record Period(Instant start, Instant end) {}
    private String scope(EdaEnergySharingConfiguration.Binding binding, String communityId) {
        return hash(List.of(binding.operatorId(), binding.connectionId(), communityId));
    }
    private String snapshotId(EdaEnergySharingConfiguration.Binding binding, String communityId) {
        return hash(List.of(binding.operatorId(), binding.connectionId(), communityId, "__snapshot__"));
    }
    private String hash(List<String> identity) {
        return EdaOperatorMeasurementXml.hash(mapper.writeValueAsString(identity).getBytes(StandardCharsets.UTF_8));
    }
}
