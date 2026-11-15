// SPDX-FileCopyrightText: 2024-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.services;

import energy.eddie.api.agnostic.Granularity;
import energy.eddie.api.agnostic.data.needs.EnergyDirection;
import energy.eddie.dataneeds.needs.AccountingPointDataNeed;
import energy.eddie.dataneeds.needs.CESUJoinRequestDataNeed;
import energy.eddie.dataneeds.needs.ValidatedHistoricalDataDataNeed;
import energy.eddie.dataneeds.services.DataNeedsService;
import energy.eddie.regionconnector.at.api.AtPermissionRequest;
import energy.eddie.regionconnector.at.api.AtPermissionRequestProjection;
import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import energy.eddie.regionconnector.at.eda.dto.*;
import energy.eddie.regionconnector.at.eda.handlers.integration.inbound.AtPermissionRequestProjectionTest;
import energy.eddie.regionconnector.at.eda.persistence.JpaPermissionRequestRepository;
import energy.eddie.regionconnector.at.eda.requests.restricted.enums.AllowedGranularity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentifiableConsumptionRecordServiceTest {

    @Mock
    private JpaPermissionRequestRepository repository;
    @Mock
    private DataNeedsService dataNeedsService;
    private final AtConfiguration configuration = new AtConfiguration("community-party", null, "", Set.of("ec-a", "ec-b"));

    private static AtPermissionRequestProjection projection(String permissionId, String connectionId, String dataNeedId,
                                                            String cmRequestId, String conversationId) {
        return new AtPermissionRequestProjectionTest(
                permissionId, connectionId, cmRequestId, conversationId,
                LocalDate.now(ZoneId.systemDefault()), LocalDate.now(ZoneId.systemDefault()), dataNeedId, "dsoId", "meteringPointId", "consentId", "message",
                AllowedGranularity.PT15M.name(),
                "ACCEPTED",
                Instant.now(),
                EnergyDirection.CONSUMPTION,
                100);
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    @Test
    void mapToIdentifiableConsumptionRecord_returnsCorrectlyMappedRecord() {

        String identifiableMeteringPoint = "identifiableMeteringPoint";
        var identifiableConsumptionRecord = createConsumptionRecord(identifiableMeteringPoint);

        var permissionRequests = List.of(
                projection("pmId1", "connId1", "dataNeedId1", "test1", "any1"),
                projection("pmId2", "connId2", "dataNeedId2", "test2", "any2")
        );

        when(repository.findByMeteringPointIdAndDateAndStateSentToPAOrAfterAccepted(
                eq(identifiableMeteringPoint),
                any()
        ))
                .thenReturn(permissionRequests);

        when(dataNeedsService.findById("dataNeedId1")).thenReturn(Optional.of(new ValidatedHistoricalDataDataNeed()));
        when(dataNeedsService.findById("dataNeedId2")).thenReturn(Optional.of(new ValidatedHistoricalDataDataNeed()));
        IdentifiableConsumptionRecordService service = new IdentifiableConsumptionRecordService(repository, dataNeedsService, configuration);

        var result = service.mapToIdentifiableConsumptionRecord(identifiableConsumptionRecord);

        assertAll(
                () -> assertTrue(result.isPresent()),
                () -> assertEquals(identifiableConsumptionRecord, result.get().consumptionRecord()),
                () -> assertEquals(2, result.get().permissionRequests().size())
        );
    }

    @Test
    void mapToIdentifiableConsumptionRecord_withUnmappableRecord_returnsEmpty() {
        String unidentifiableMeteringPoint = "unidentifiableMeteringPoint";
        var unidentifiableConsumptionRecord = createConsumptionRecord(unidentifiableMeteringPoint);

        when(repository.findByMeteringPointIdAndDateAndStateSentToPAOrAfterAccepted(eq(unidentifiableMeteringPoint),
                              any()))
                .thenReturn(List.of());

        IdentifiableConsumptionRecordService service = new IdentifiableConsumptionRecordService(repository, dataNeedsService, configuration);

        var result = service.mapToIdentifiableConsumptionRecord(unidentifiableConsumptionRecord);

        assertTrue(result.isEmpty());
    }

    @Test
    void communityAllocationReachesOnlyItsCommunityPermission() {
        var consumptionRecord = mock(EdaConsumptionRecord.class);
        var today = LocalDate.now(ZoneId.systemDefault());
        when(consumptionRecord.energyCommunityId()).thenReturn(Optional.of("ec-a"));
        when(consumptionRecord.receiverMessageAddress()).thenReturn("community-party");
        when(consumptionRecord.senderMessageAddress()).thenReturn("dsoId");
        when(consumptionRecord.meteringPoint()).thenReturn("meteringPointId");
        when(consumptionRecord.startDate()).thenReturn(today);
        when(consumptionRecord.endDate()).thenReturn(today.plusDays(1));
        when(consumptionRecord.lastReadingDate()).thenReturn(today);
        when(repository.findByMeteringPointIdAndDateAndStateSentToPAOrAfterAccepted("meteringPointId", today))
                .thenReturn(List.of(projection("communityA", "a", "need-a", "req-a", "a"),
                                    projection("communityB", "b", "need-b", "req-b", "b"),
                                    projection("history", "h", "need-history", "req-h", "h"),
                                    projection("masterData", "m", "need-master", "req-m", "m")));
        when(dataNeedsService.findById("need-a")).thenReturn(Optional.of(communityNeed("ec-a")));
        when(dataNeedsService.findById("need-b")).thenReturn(Optional.of(communityNeed("ec-b")));
        when(dataNeedsService.findById("need-history")).thenReturn(Optional.of(new ValidatedHistoricalDataDataNeed()));
        when(dataNeedsService.findById("need-master")).thenReturn(Optional.of(new AccountingPointDataNeed()));
        var service = new IdentifiableConsumptionRecordService(repository, dataNeedsService, configuration);

        var result = service.mapToIdentifiableConsumptionRecord(consumptionRecord).orElseThrow();
        assertThat(result.permissionRequests()).extracting(AtPermissionRequest::permissionId).containsExactly("communityA");
    }

    @Test
    void rejectsCommunityAllocationSentToDifferentMarketParty() {
        var consumptionRecord = mock(EdaConsumptionRecord.class);
        when(consumptionRecord.energyCommunityId()).thenReturn(Optional.of("ec-a"));
        when(consumptionRecord.receiverMessageAddress()).thenReturn("ep");
        var service = new IdentifiableConsumptionRecordService(repository, dataNeedsService, configuration);

        assertThat(service.mapToIdentifiableConsumptionRecord(consumptionRecord)).isEmpty();
    }

    @Test
    void ordinaryReadingForSharedMarketPartyDoesNotReachCommunityPermission() {
        var consumptionRecord = createConsumptionRecord("meteringPointId");
        when(repository.findByMeteringPointIdAndDateAndStateSentToPAOrAfterAccepted("meteringPointId", consumptionRecord.startDate()))
                .thenReturn(List.of(projection("history", "h", "need-history", "req-h", "h"),
                                    projection("communityA", "a", "need-a", "req-a", "a")));
        when(dataNeedsService.findById("need-history")).thenReturn(Optional.of(new ValidatedHistoricalDataDataNeed()));
        when(dataNeedsService.findById("need-a")).thenReturn(Optional.of(communityNeed("ec-a")));
        var service = new IdentifiableConsumptionRecordService(repository, dataNeedsService, configuration);

        var result = service.mapToIdentifiableConsumptionRecord(consumptionRecord).orElseThrow();

        assertThat(result.permissionRequests()).extracting(AtPermissionRequest::permissionId).containsExactly("history");
    }

    private static CESUJoinRequestDataNeed communityNeed(String communityId) {
        return new CESUJoinRequestDataNeed(100, Granularity.PT15M, Granularity.PT15M, EnergyDirection.CONSUMPTION, communityId);
    }

    private EdaConsumptionRecord createConsumptionRecord(String meteringPoint) {
        var meteringType = "L1";
        var consumptionValue = 10;
        var granularity = Granularity.PT15M;
        var unit = "KWH";
        return createConsumptionRecord(meteringPoint,
                                       meteringType,
                                       LocalDate.now(ZoneOffset.UTC),
                                       granularity,
                                       consumptionValue,
                                       unit);
    }

    private EdaConsumptionRecord createConsumptionRecord(
            String meteringPoint,
            String meteringType,
            LocalDate meteringPeriodStart,
            Granularity granularity,
            double consumptionValue,
            String unit
    ) {
        return new SimpleEdaConsumptionRecord()
                .setMeteringPoint(meteringPoint)
                .setReceiverMessageAddress("community-party")
                .setStartDate(meteringPeriodStart)
                .setEndDate(meteringPeriodStart)
                .setEnergy(List.of(
                        new SimpleEnergy()
                                .setGranularity(granularity)
                                .setEnergyData(List.of(
                                        new SimpleEnergyData()
                                                .setBillingUnit(unit)
                                                .setEnergyPositions(List.of(new EnergyPosition(
                                                                            BigDecimal.valueOf(consumptionValue),
                                                                            meteringType)
                                                                    )
                                                )
                                ))
                ));
    }
}
