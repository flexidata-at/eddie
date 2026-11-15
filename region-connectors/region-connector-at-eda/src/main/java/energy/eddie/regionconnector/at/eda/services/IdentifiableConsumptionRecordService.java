// SPDX-FileCopyrightText: 2024-2025 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.services;

import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.dataneeds.needs.CESUJoinRequestDataNeed;
import energy.eddie.dataneeds.needs.DataNeed;
import energy.eddie.dataneeds.needs.ValidatedHistoricalDataDataNeed;
import energy.eddie.dataneeds.services.DataNeedsService;
import energy.eddie.regionconnector.at.api.AtPermissionRequest;
import energy.eddie.regionconnector.at.api.AtPermissionRequestProjection;
import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import energy.eddie.regionconnector.at.eda.dto.EdaConsumptionRecord;
import energy.eddie.regionconnector.at.eda.dto.IdentifiableConsumptionRecord;
import energy.eddie.regionconnector.at.eda.permission.request.EdaPermissionRequest;
import energy.eddie.regionconnector.at.eda.persistence.JpaPermissionRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

@Component
public class IdentifiableConsumptionRecordService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IdentifiableConsumptionRecordService.class);
    private final JpaPermissionRequestRepository repository;
    private final DataNeedsService dataNeedsService;
    private final AtConfiguration configuration;


    public IdentifiableConsumptionRecordService(
            JpaPermissionRequestRepository repository,
            @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") DataNeedsService dataNeedsService,
            AtConfiguration configuration
    ) {
        this.repository = repository;
        this.dataNeedsService = dataNeedsService;
        this.configuration = configuration;
    }

    public Optional<IdentifiableConsumptionRecord> mapToIdentifiableConsumptionRecord(EdaConsumptionRecord consumptionRecord) {
        LocalDate startDate = consumptionRecord.startDate();
        LocalDate endDate = consumptionRecord.endDate();
        String meteringPoint = consumptionRecord.meteringPoint();
        var communityId = consumptionRecord.energyCommunityId();
        var addressedToCommunity = configuration.isEnergyCommunityMarketParty(consumptionRecord.receiverMessageAddress());
        if (communityId.isPresent() && !addressedToCommunity) {
            LOGGER.warn("Dropping consumption record with inconsistent community identifier and recipient");
            return Optional.empty();
        }
        if (communityId.isPresent() && configuration.resolveEnergyCommunityId(communityId).isEmpty()) {
            LOGGER.warn("Dropping consumption record for a community outside the configured market party's scope");
            return Optional.empty();
        }

        // we consider all permission requests that match the metering point and date and are in state SentToPa or after Accepted as relevant.
        // All states after Accepted are relevant, not only because we cant uniquely identify for which PermissionRequest data actually is, but also because
        // DSOs can send data for PermissionRequests that have already been revoked/terminated. This happens for example if some metering values
        // in the Timeframe we still had access are updated (e.g from replacement to actual values).
        // Please note that this causes the ConsumptionRecords to also be emitted for these PermissionRequests (EPs should be able to deal with values for previously received data being updated(
        List<AtPermissionRequestProjection> permissionRequestProjections = repository
                .findByMeteringPointIdAndDateAndStateSentToPAOrAfterAccepted(
                        meteringPoint,
                        startDate
                );

        var dataNeeds = new HashMap<String, Optional<DataNeed>>();
        var lastReadingDate = communityId.isPresent() ? consumptionRecord.lastReadingDate() : endDate;
        var permissionRequests = permissionRequestProjections.stream()
                .filter(projection -> dataNeeds.computeIfAbsent(projection.getDataNeedId(), dataNeedsService::findById)
                        .filter(need -> matchesScope(projection, need, consumptionRecord, communityId, lastReadingDate))
                        .isPresent())
                .<AtPermissionRequest>map(EdaPermissionRequest::fromProjection)
                .toList();

        if (permissionRequests.isEmpty()) {
            LOGGER.warn("No permission requests found for consumption record with date {}", startDate);
            return Optional.empty();
        }

        permissionRequests.forEach(permissionRequest -> LOGGER.info(
                "Received consumption record (ConversationId '{}') for permissionId {} and connectionId {}",
                permissionRequest.conversationId(),
                permissionRequest.permissionId(),
                permissionRequest.connectionId()));

        return Optional.of(new IdentifiableConsumptionRecord(
                consumptionRecord,
                permissionRequests,
                startDate,
                endDate
        ));
    }

    private boolean matchesScope(
            AtPermissionRequestProjection permission,
            DataNeed dataNeed,
            EdaConsumptionRecord record,
            Optional<String> communityId,
            LocalDate lastReadingDate
    ) {
        if (communityId.isEmpty()) {
            return dataNeed instanceof ValidatedHistoricalDataDataNeed;
        }
        var permissionEnd = permission.getPermissionEnd();
        var status = permission.getStatus();
        return dataNeed instanceof CESUJoinRequestDataNeed cesuNeed
                && configuration.resolveEnergyCommunityId(cesuNeed.energyCommunityId()).equals(communityId)
                && permission.getDsoId().equals(record.senderMessageAddress())
                && (PermissionProcessStatus.ACCEPTED.name().equals(status)
                    || PermissionProcessStatus.FULFILLED.name().equals(status))
                && (permissionEnd == null || !lastReadingDate.isAfter(permissionEnd));
    }
}
