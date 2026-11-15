// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.fr.enedis.services;

import energy.eddie.api.agnostic.Granularity;
import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.regionconnector.fr.enedis.api.*;
import energy.eddie.regionconnector.fr.enedis.dto.EnedisApiError;
import energy.eddie.regionconnector.fr.enedis.dto.readings.*;
import energy.eddie.regionconnector.fr.enedis.permission.request.EnedisPermissionRequestBuilder;
import energy.eddie.regionconnector.fr.enedis.providers.IdentifiableMeterReading;
import energy.eddie.regionconnector.fr.enedis.providers.MeterReadingType;
import energy.eddie.regionconnector.fr.enedis.tasks.UpdateGranularityTask;
import energy.eddie.regionconnector.shared.services.MeterReadingPermissionUpdateAndFulfillmentService;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import java.time.LocalDate;
import java.time.Month;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PartialReadingsTest {
    private final EnedisMeterReadingApi api = mock(EnedisMeterReadingApi.class);
    private final UpdateGranularityTask granularity = mock(UpdateGranularityTask.class);
    private final MeterReadingPermissionUpdateAndFulfillmentService fulfillment = mock(MeterReadingPermissionUpdateAndFulfillmentService.class);
    private final LocalDate start = LocalDate.of(2026, Month.AUGUST, 1);
    private FrEnedisPermissionRequest request(int days, UsagePointType type) {
        return new EnedisPermissionRequestBuilder().setPermissionId("p").setUsagePointId("meter")
                .setStart(start).setEnd(start.plusDays(days - 1L)).setGranularity(Granularity.PT30M)
                .setUsagePointType(type).setStatus(PermissionProcessStatus.ACCEPTED).create();
    }
    private MeterReading reading(LocalDate from, LocalDate to) {
        return new MeterReading("meter", MeterReading.ReadingType.RAW, new MeterReading.Period(from, to),
                Optional.empty(), MeterReading.CalculationMode.MEASURED, Optional.empty(),
                List.of(new MeterReading.Reading(MeterReading.Direction.CONSUMPTION, "PA", "W",
                        List.of(new MeterReading.Point(42.0,
                                from.atStartOfDay(energy.eddie.regionconnector.fr.enedis.EnedisRegionConnectorMetadata.ZONE_ID_FR).plusMinutes(30),
                                Optional.of(java.time.Duration.ofMinutes(30)), Optional.empty(), Optional.empty(), Optional.empty())),
                        List.of())), List.of());
    }
    private WebClientResponseException error(int status, String code) {
        var e = WebClientResponseException.create(status, "provider error", null, null, null);
        e.setBodyDecodeFunction(t -> new EnedisApiError(code, "test"));
        return e;
    }
    @Test void weeklyGapIsNarrowedToDaysAndAvailableReadingsSurviveWithoutFulfillment() {
        try (var streams = new EnergyDataStreams()) {
            var delivered = new ArrayList<IdentifiableMeterReading>(); streams.getValidatedHistoricalData().subscribe(delivered::add);
            when(api.getConsumptionMeterReading(anyString(), any(), any(), any())).thenAnswer(call -> {
                LocalDate from = call.getArgument(1), to = call.getArgument(2);
                if (from.plusDays(1).isBefore(to) || from.equals(start.plusDays(2))) return Mono.error(error(404, "no_data_found"));
                return Mono.just(reading(from, to));
            });
            var pr = request(7, UsagePointType.CONSUMPTION);
            new PollingService(api, fulfillment, granularity, streams).pollTimeSeriesData(pr);
            assertEquals(6, delivered.size());
            assertTrue(delivered.stream().noneMatch(r -> r.payload().period().start().equals(start.plusDays(2))));
            verify(api, times(8)).getConsumptionMeterReading(eq("meter"), any(), any(), eq(Granularity.PT30M));
            // The final day is present, but an earlier gap must still be retried.
            verifyNoInteractions(granularity, fulfillment);
        }
    }
    @Test void deniedIntervalsDoNotRevokeOrFalselyFulfillButOtherDaysArePublished() {
        try (var streams = new EnergyDataStreams()) {
            var delivered = new ArrayList<IdentifiableMeterReading>(); streams.getValidatedHistoricalData().subscribe(delivered::add);
            when(api.getConsumptionMeterReading(anyString(), any(), any(), any())).thenAnswer(call -> {
                LocalDate from = call.getArgument(1), to = call.getArgument(2);
                return from.equals(start) ? Mono.error(error(403, "ADAM-DC-0008")) : Mono.just(reading(from, to));
            });
            new PollingService(api, fulfillment, granularity, streams).pollTimeSeriesData(request(3, UsagePointType.CONSUMPTION));
            assertEquals(2, delivered.size());
            verifyNoInteractions(granularity, fulfillment);
        }
    }
    @Test void temporaryErrorDoesNotCancelLaterWeeklyBatches() {
        try (var streams = new EnergyDataStreams()) {
            var delivered = new ArrayList<IdentifiableMeterReading>(); streams.getValidatedHistoricalData().subscribe(delivered::add);
            when(api.getConsumptionMeterReading(anyString(), any(), any(), any()))
                    .thenReturn(Mono.error(error(500, "technical_error")), Mono.just(reading(start.plusWeeks(1), start.plusWeeks(2))));
            new PollingService(api, fulfillment, granularity, streams).pollTimeSeriesData(request(14, UsagePointType.CONSUMPTION));
            assertEquals(1, delivered.size()); verifyNoInteractions(granularity, fulfillment);
        }
    }
    @Test void missingProductionDoesNotCancelConsumptionOrDowngradeIt() {
        try (var streams = new EnergyDataStreams()) {
            var delivered = new ArrayList<IdentifiableMeterReading>(); streams.getValidatedHistoricalData().subscribe(delivered::add);
            when(api.getConsumptionMeterReading(anyString(), any(), any(), any())).thenReturn(Mono.just(reading(start, start.plusDays(1))));
            when(api.getProductionMeterReading(anyString(), any(), any(), any())).thenReturn(Mono.error(error(404, "no_data_found")));
            new PollingService(api, fulfillment, granularity, streams).pollTimeSeriesData(request(1, UsagePointType.CONSUMPTION_AND_PRODUCTION));
            assertEquals(1, delivered.size()); assertEquals(MeterReadingType.CONSUMPTION, delivered.getFirst().meterReadingType());
            verifyNoInteractions(granularity, fulfillment);
        }
    }
    @Test void emptyResponsesNeitherPublishNorFulfill() {
        try (var streams = new EnergyDataStreams()) {
            var delivered = new ArrayList<IdentifiableMeterReading>(); streams.getValidatedHistoricalData().subscribe(delivered::add);
            when(api.getConsumptionMeterReading(anyString(), any(), any(), any())).thenReturn(Mono.just(new MeterReading("meter", MeterReading.ReadingType.RAW, new MeterReading.Period(start, start.plusDays(1)), Optional.empty(), MeterReading.CalculationMode.MEASURED, Optional.empty(), List.of(), List.of())));
            new PollingService(api, fulfillment, granularity, streams).pollTimeSeriesData(request(1, UsagePointType.CONSUMPTION));
            assertTrue(delivered.isEmpty()); verifyNoInteractions(granularity, fulfillment);
        }
    }
    @Test void fulfillmentWaitsForEveryBatchAndConcurrentPollsAreDeduplicated() {
        try (var streams = new EnergyDataStreams()) {
            var last = Sinks.<MeterReading>one();
            when(api.getConsumptionMeterReading(anyString(), any(), any(), any()))
                    .thenReturn(Mono.just(reading(start, start.plusWeeks(1))), last.asMono());
            var service = new PollingService(api, fulfillment, granularity, streams);
            var pr = request(14, UsagePointType.CONSUMPTION);
            service.pollTimeSeriesData(pr); service.pollTimeSeriesData(pr);
            verifyNoInteractions(fulfillment);
            verify(api, times(2)).getConsumptionMeterReading(anyString(), any(), any(), any());
            last.tryEmitValue(reading(start.plusWeeks(1), start.plusWeeks(2)));
            verify(fulfillment).tryUpdateAndFulfillPermissionRequest(eq(pr), any());
        }
    }
    @Test void anEmptyEarlierBatchCannotBeHiddenBySuccessfulFinalBatch() {
        try (var streams = new EnergyDataStreams()) {
            var delivered = new ArrayList<IdentifiableMeterReading>();
            streams.getValidatedHistoricalData().subscribe(delivered::add);
            var empty = new MeterReading("meter", MeterReading.ReadingType.RAW,
                    new MeterReading.Period(start, start.plusWeeks(1)), Optional.empty(),
                    MeterReading.CalculationMode.MEASURED, Optional.empty(), List.of(), List.of());
            when(api.getConsumptionMeterReading(anyString(), any(), any(), any()))
                    .thenReturn(Mono.just(empty), Mono.just(reading(start.plusWeeks(1), start.plusWeeks(2))));
            new PollingService(api, fulfillment, granularity, streams)
                    .pollTimeSeriesData(request(14, UsagePointType.CONSUMPTION));
            assertEquals(1, delivered.size());
            verifyNoInteractions(granularity, fulfillment);
        }
    }

    @Test void completedPollCannotUnlockItsSynchronouslyStartedSuccessor() {
        try (var streams = new EnergyDataStreams()) {
            var pending = Sinks.<MeterReading>one();
            var pr = request(1, UsagePointType.CONSUMPTION);
            var noData = error(404, "no_data_found");
            when(api.getConsumptionMeterReading(anyString(), any(), any(), any()))
                    .thenReturn(Mono.error(noData), pending.asMono());
            var service = new PollingService(api, fulfillment, granularity, streams);
            doAnswer(invocation -> {
                service.pollTimeSeriesData(pr);
                return null;
            }).when(granularity).update(pr, noData);
            service.pollTimeSeriesData(pr);
            // A granularity update can start the next poll from the completion callback.
            service.pollTimeSeriesData(pr);
            verify(api, times(2)).getConsumptionMeterReading(anyString(), any(), any(), any());
            verifyNoInteractions(fulfillment);
            pending.tryEmitValue(reading(start, start.plusDays(1)));
            verify(fulfillment).tryUpdateAndFulfillPermissionRequest(eq(pr), any());
        }
    }

    @Test void futureWindowDoesNotAskForTodaysUnpublishedReadings() {
        try (var streams = new EnergyDataStreams()) {
            var today = LocalDate.now(energy.eddie.regionconnector.fr.enedis.EnedisRegionConnectorMetadata.ZONE_ID_FR);
            for (int offset : new int[]{0, 14}) {
                var pr = new EnedisPermissionRequestBuilder().setPermissionId("p").setStart(today.plusDays(offset)).setEnd(today.plusDays(offset + 5L)).setGranularity(Granularity.PT30M).setUsagePointType(UsagePointType.CONSUMPTION).create();
                new PollingService(api, fulfillment, granularity, streams).pollTimeSeriesData(pr);
            }
            verifyNoInteractions(api, fulfillment);
        }
    }
}
