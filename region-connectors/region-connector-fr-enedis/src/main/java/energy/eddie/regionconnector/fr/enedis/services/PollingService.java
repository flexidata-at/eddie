// SPDX-FileCopyrightText: 2023-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.fr.enedis.services;

import energy.eddie.regionconnector.fr.enedis.api.EnedisMeterReadingApi;
import energy.eddie.regionconnector.fr.enedis.api.FrEnedisPermissionRequest;
import energy.eddie.regionconnector.fr.enedis.dto.EnedisApiError;
import energy.eddie.regionconnector.fr.enedis.providers.IdentifiableMeterReading;
import energy.eddie.regionconnector.fr.enedis.providers.MeterReadingType;
import energy.eddie.regionconnector.fr.enedis.tasks.UpdateGranularityTask;
import energy.eddie.regionconnector.shared.services.CommonPollingService;
import energy.eddie.regionconnector.shared.services.MeterReadingPermissionUpdateAndFulfillmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static energy.eddie.regionconnector.fr.enedis.EnedisRegionConnectorMetadata.ZONE_ID_FR;

@Service
public class PollingService implements CommonPollingService<FrEnedisPermissionRequest> {
    private static final Logger LOGGER = LoggerFactory.getLogger(PollingService.class);
    public static final RetryBackoffSpec RETRY_BACKOFF_SPEC = Retry.backoff(10, Duration.ofMinutes(1))
                                                                   .filter(PollingService::isRetryable);
    private final EnedisMeterReadingApi enedisApi;
    private final MeterReadingPermissionUpdateAndFulfillmentService meterReadingPermissionUpdateAndFulfillmentService;
    private final Map<String, Object> inFlight = new ConcurrentHashMap<>();
    private final UpdateGranularityTask updateGranularityTask;
    private final EnergyDataStreams streams;

    public PollingService(
            EnedisMeterReadingApi enedisApi,
            MeterReadingPermissionUpdateAndFulfillmentService meterReadingPermissionUpdateAndFulfillmentService,
            UpdateGranularityTask updateGranularityTask,
            EnergyDataStreams streams
    ) {
        this.enedisApi = enedisApi;
        this.meterReadingPermissionUpdateAndFulfillmentService = meterReadingPermissionUpdateAndFulfillmentService;
        this.updateGranularityTask = updateGranularityTask;
        this.streams = streams;
    }

    @Override
    public void pollTimeSeriesData(FrEnedisPermissionRequest permissionRequest) {
        LocalDate permissionStart = permissionRequest.start();
        LocalDate permissionEnd = permissionRequest.end();
        LocalDate now = LocalDate.now(ZONE_ID_FR);
        var end = now.isAfter(permissionEnd) ? permissionEnd.plusDays(1) : now;
        fetchMeterReadings(permissionRequest, permissionStart, end);
    }

    @Override
    public boolean isActiveAndNeedsToBeFetched(FrEnedisPermissionRequest permissionRequest) {
        LocalDate now = LocalDate.now(ZONE_ID_FR);
        return permissionRequest.start().isBefore(now)
               && permissionRequest.latestMeterReadingEndDate()
                                   .map(latest -> latest.isBefore(now))
                                   .orElse(true);
    }

    /**
     * Retries when the error is: - a 429 TooManyRequests - a 401 Unauthorized (i.e. when the token is expired)
     */
    private static boolean isRetryable(Throwable e) {
        var retryable = e instanceof WebClientResponseException.TooManyRequests || e instanceof WebClientResponseException.Unauthorized;
        LOGGER.info("Checking if error is retryable({})", retryable, e);
        return retryable;
    }

    private void fetchMeterReadings(
            FrEnedisPermissionRequest permissionRequest,
            LocalDate start,
            LocalDate end
    ) {
        String permissionId = permissionRequest.permissionId();
        LOGGER.info("Preparing to fetch data from ENEDIS for permission request '{}' from '{}' to '{}' (exclusive)",
                    permissionId, start, end);

        if (!start.isBefore(end)) {
            return;
        }
        AtomicBoolean incompleteFetch = new AtomicBoolean();
        AtomicReference<Throwable> noData = new AtomicReference<>();
        AtomicReference<IdentifiableMeterReading> latest = new AtomicReference<>();
        Flux<IdentifiableMeterReading> readings = switch (permissionRequest.granularity()) {
            case PT30M -> calculateBatchDates(start, end).concatMapDelayError(batchStart ->
                    fetchAvailableData(permissionRequest, batchStart,
                                       batchStart.plusWeeks(1).isAfter(end) ? end : batchStart.plusWeeks(1),
                                       incompleteFetch, noData), true, 1);
            case P1D -> fetchAvailableData(permissionRequest, start, end, incompleteFetch, noData);
            default -> throw new IllegalStateException("Unsupported granularity: " + permissionRequest.granularity());
        };
        var pollingRun = new Object();
        if (inFlight.putIfAbsent(permissionId, pollingRun) != null) {
            return;
        }
        // Completion can synchronously start a new granularity poll. An older run must
        // never release the lock belonging to that new subscription in doFinally.
        readings.doOnComplete(() -> inFlight.remove(permissionId, pollingRun))
                .doFinally(ignored -> inFlight.remove(permissionId, pollingRun))
                .subscribe(reading -> publishAvailableReading(reading, incompleteFetch, latest),
                           error -> LOGGER.warn("ENEDIS fetch incomplete for permission '{}'; preserving delivered readings and permission for retry",
                                                permissionId, error),
                           () -> completeFetch(permissionRequest, incompleteFetch.get(), latest.get(), noData.get()));
    }

    private void publishAvailableReading(IdentifiableMeterReading reading, AtomicBoolean incompleteFetch,
                                         AtomicReference<IdentifiableMeterReading> latest) {
        // Empty responses must not masquerade as delivered data or fulfillment.
        if (reading.payload().readings().stream().allMatch(value -> value.points().isEmpty())) {
            incompleteFetch.set(true);
            return;
        }
        latest.updateAndGet(previous -> previous == null
                || reading.meterReadingEndDate().isAfter(previous.meterReadingEndDate()) ? reading : previous);
        streams.publish(reading);
    }

    private void completeFetch(FrEnedisPermissionRequest permissionRequest, boolean incompleteFetch,
                               @org.jspecify.annotations.Nullable IdentifiableMeterReading latest,
                               @org.jspecify.annotations.Nullable Throwable noData) {
        // Only decide fulfillment once ALL batches/directions have completed.
        // A missing interval is not a revoked consent; an access/technical error
        // must not be hidden by a successful later batch.
        if (incompleteFetch || (latest != null && noData != null)) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn("ENEDIS has unavailable intervals of permission '{}'; keeping available data and permission for retry",
                            permissionRequest.permissionId());
            }
        } else if (latest != null) {
            meterReadingPermissionUpdateAndFulfillmentService.tryUpdateAndFulfillPermissionRequest(permissionRequest, latest);
        } else if (noData != null) {
            updateGranularityTask.update(permissionRequest, noData);
        }
    }

    private Flux<IdentifiableMeterReading> fetchAvailableData(
            FrEnedisPermissionRequest request, LocalDate start, LocalDate end,
            AtomicBoolean incompleteFetch, AtomicReference<Throwable> noData
    ) {
        // Handle consumption and production separately: one absent direction cannot cancel the other.
        return switch (request.usagePointType()) {
            case CONSUMPTION -> fetchAvailableDirection(request, start, end, MeterReadingType.CONSUMPTION, incompleteFetch, noData);
            case PRODUCTION -> fetchAvailableDirection(request, start, end, MeterReadingType.PRODUCTION, incompleteFetch, noData);
            case CONSUMPTION_AND_PRODUCTION -> Flux.concatDelayError(
                    fetchAvailableDirection(request, start, end, MeterReadingType.CONSUMPTION, incompleteFetch, noData),
                    fetchAvailableDirection(request, start, end, MeterReadingType.PRODUCTION, incompleteFetch, noData));
        };
    }

    private Flux<IdentifiableMeterReading> fetchAvailableDirection(
            FrEnedisPermissionRequest request, LocalDate start, LocalDate end, MeterReadingType type,
            AtomicBoolean incompleteFetch, AtomicReference<Throwable> noData
    ) {
        return Flux.defer(() -> type == MeterReadingType.CONSUMPTION
                        ? enedisApi.getConsumptionMeterReading(request.usagePointId(), start, end, request.granularity())
                        : enedisApi.getProductionMeterReading(request.usagePointId(), start, end, request.granularity()))
                .retryWhen(RETRY_BACKOFF_SPEC)
                .map(reading -> new IdentifiableMeterReading(request, reading, type))
                .onErrorResume(error -> {
                    String code = providerErrorCode(error);
                    boolean missing = "no_data_found".equals(code);
                    boolean deniedInterval = "ADAM-DC-0008".equals(code);
                    if (!missing && !deniedInterval) {
                        return Flux.error(error);
                    }
                    // Enedis may reject a whole batch even when individual days are available.
                    // Narrow dates only; retain the same metering point, consent and resolution.
                    long days = ChronoUnit.DAYS.between(start, end);
                    if (days > 1) {
                        return Flux.range(0, Math.toIntExact(days)).concatMapDelayError(day ->
                                fetchAvailableDirection(request, start.plusDays(day), start.plusDays(day + 1L),
                                                        type, incompleteFetch, noData), true, 1);
                    }
                    if (missing) {
                        noData.compareAndSet(null, error);
                    } else {
                        incompleteFetch.set(true);
                    }
                    LOGGER.info("ENEDIS interval unavailable for permission '{}' from '{}' to '{}' ({}); continuing",
                                request.permissionId(), start, end, code);
                    return Flux.empty();
                });
    }

    @org.jspecify.annotations.Nullable
    private static String providerErrorCode(Throwable error) {
        if (error instanceof WebClientResponseException response) {
            try {
                var body = response.getResponseBodyAs(EnedisApiError.class);
                return body == null ? null : body.error();
            } catch (RuntimeException ignored) {
                // Technical/gateway errors do not establish a customer revocation.
            }
        }
        return null;
    }

    private Flux<LocalDate> calculateBatchDates(LocalDate start, LocalDate end) {
        // the api allows for a maximum of 7 days per request, so we need to split the request into multiple batches
        long daysBetween = ChronoUnit.DAYS.between(start, end);
        return Flux.range(0, (int) Math.ceil(daysBetween / 7.0))
                   .map(start::plusWeeks)
                   .takeWhile(batchStart -> !batchStart.isAfter(end)); // Ensure not to exceed the end date
    }
}
