// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.fr.enedis.services;

import energy.eddie.api.agnostic.Granularity;
import energy.eddie.api.agnostic.data.needs.DataNeedCalculationService;
import energy.eddie.api.agnostic.data.needs.Timeframe;
import energy.eddie.api.agnostic.data.needs.ValidatedHistoricalDataDataNeedResult;
import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.dataneeds.needs.ValidatedHistoricalDataDataNeed;
import energy.eddie.regionconnector.fr.enedis.client.EnedisApiClient;
import energy.eddie.regionconnector.fr.enedis.client.EnedisTokenProvider;
import energy.eddie.regionconnector.fr.enedis.permission.events.FrGranularityUpdateEvent;
import energy.eddie.regionconnector.fr.enedis.permission.request.EnedisPermissionRequestBuilder;
import energy.eddie.regionconnector.fr.enedis.tasks.UpdateGranularityTask;
import energy.eddie.regionconnector.shared.event.sourcing.Outbox;
import energy.eddie.regionconnector.shared.services.MeterReadingPermissionUpdateAndFulfillmentService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.Month;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PollingFailureIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void halfHourlyNoData_routesRealHttpErrorAccordingToDataNeed(boolean dailyAllowed) throws Exception {
        try (var server = new MockWebServer(); var streams = new EnergyDataStreams()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(404)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""
                            {"error":"no_data_found","error_description":"no measure found for this usage point"}
                            """));
            var tokenProvider = mock(EnedisTokenProvider.class);
            when(tokenProvider.getToken()).thenReturn(Mono.just("test-token"));
            var client = new EnedisApiClient(tokenProvider, WebClient.builder()
                    .baseUrl(server.url("/").toString()).build());
            var outbox = mock(Outbox.class);
            var calculation = mock(DataNeedCalculationService.class);
            var fulfillment = mock(MeterReadingPermissionUpdateAndFulfillmentService.class);
            var start = LocalDate.of(2026, Month.AUGUST, 14);
            var end = start;
            var timeframe = new Timeframe(start, end);
            when(calculation.calculate(eq("data-need"), any())).thenReturn(
                    new ValidatedHistoricalDataDataNeedResult(
                            dailyAllowed ? List.of(Granularity.PT30M, Granularity.P1D) : List.of(Granularity.PT30M),
                            timeframe, timeframe, new ValidatedHistoricalDataDataNeed()));
            var request = new EnedisPermissionRequestBuilder()
                    .setPermissionId("permission-id").setDataNeedId("data-need")
                    .setStart(start).setEnd(end).setGranularity(Granularity.PT30M)
                    .setUsagePointId("test-meter").setStatus(PermissionProcessStatus.ACCEPTED).create();
            var polling = new PollingService(client, fulfillment,
                    new UpdateGranularityTask(outbox, calculation), streams);

            polling.pollTimeSeriesData(request);

            verify(calculation, timeout(5000)).calculate(eq("data-need"), any());
            if (dailyAllowed) {
                verify(outbox, timeout(5000)).commit(assertArg(event -> {
                    var updated = assertInstanceOf(FrGranularityUpdateEvent.class, event);
                    assertEquals(Granularity.P1D, updated.granularity());
                }));
            } else {
                verify(outbox, after(300).never()).commit(any());
            }
            verifyNoInteractions(fulfillment);
            var httpRequest = server.takeRequest();
            assertEquals("/mesure_synchrone_auto/v2/courbe_de_charge_consommation?pointId=test-meter&dateDebut=2026-08-14&dateFin=2026-08-15",
                         httpRequest.getPath());
        }
    }
}
