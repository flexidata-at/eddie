// SPDX-FileCopyrightText: 2024-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.shared.event.sourcing.handlers.integration;

import energy.eddie.api.agnostic.process.model.PermissionRequest;
import energy.eddie.api.agnostic.process.model.persistence.PermissionRequestRepository;
import energy.eddie.api.cim.config.PlainCommonInformationModelConfiguration;
import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.cim.v0_82.vhd.CodingSchemeTypeList;
import energy.eddie.dataneeds.needs.AccountingPointDataNeed;
import energy.eddie.dataneeds.services.DataNeedsService;
import energy.eddie.regionconnector.shared.event.sourcing.EventBus;
import energy.eddie.regionconnector.shared.event.sourcing.EventBusImpl;
import energy.eddie.regionconnector.shared.exceptions.PermissionNotFoundException;
import energy.eddie.regionconnector.shared.permission.requests.SimplePermissionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SuppressWarnings("resource")
@ExtendWith(MockitoExtension.class)
class PermissionMarketDocumentMessageHandlerTest {
    @Mock
    private PermissionRequestRepository<PermissionRequest> repository;
    @Mock
    private DataNeedsService dataNeedsService;

    @Test
    void testAccept_emitsPermissionMarketDocument() {
        // Given
        var start = LocalDate.now(ZoneOffset.UTC);
        var end = start.plusDays(10);
        var permissionRequest = new SimplePermissionRequest(
                "pid", "cid", "dnid", start, end, ZonedDateTime.now(ZoneOffset.UTC), PermissionProcessStatus.VALIDATED
        );
        when(repository.findByPermissionId("pid")).thenReturn(Optional.of(permissionRequest));
        when(dataNeedsService.getById(anyString())).thenReturn(new AccountingPointDataNeed());
        PlainCommonInformationModelConfiguration cimConfig = new PlainCommonInformationModelConfiguration(
                CodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME, "fallbackId");
        EventBus eventBus = new EventBusImpl();
        var handler = new PermissionMarketDocumentMessageHandler<>(eventBus,
                                                                   repository,
                                                                   dataNeedsService,
                                                                   "EP-ID",
                                                                   cimConfig,
                                                                   pr -> null,
                                                                   ZoneOffset.UTC);

        // When
        eventBus.emit(new SimpleEvent("pid", PermissionProcessStatus.VALIDATED));

        // Then
        StepVerifier.create(handler.getRequestPermissionDocumentStream())
                    .expectNextCount(1)
                    .thenCancel();
        StepVerifier.create(handler.getPermissionMarketDocumentStream())
                    .then(handler::close)
                    .expectNextCount(1)
                    .verifyComplete();
    }

    @Test
    void testAccept_doesNotEmitStatus_ifNoPermissionIsFound() {
        // Given
        when(repository.findByPermissionId("pid")).thenReturn(Optional.empty());
        var cimConfig = new PlainCommonInformationModelConfiguration(
                CodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME, "fallbackId");
        EventBus eventBus = new EventBusImpl();
        var handler = new PermissionMarketDocumentMessageHandler<>(eventBus,
                                                                   repository,
                                                                   dataNeedsService,
                                                                   "EP-ID",
                                                                   cimConfig,
                                                                   pr -> null,
                                                                   ZoneOffset.UTC);

        // When
        eventBus.emit(new SimpleEvent("pid", PermissionProcessStatus.VALIDATED));

        // Then
        StepVerifier.create(handler.getPermissionMarketDocumentStream())
                    .then(handler::close)
                    .expectError(PermissionNotFoundException.class)
                    .verify();
    }

    @Test
    void testAccept_doesNotEmitStatus_onInternalEvent() {
        // Given
        var cimConfig = new PlainCommonInformationModelConfiguration(
                CodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME, "fallbackId");
        EventBus eventBus = new EventBusImpl();
        var handler = new PermissionMarketDocumentMessageHandler<>(eventBus,
                                                                   repository,
                                                                   dataNeedsService,
                                                                   "EP-ID",
                                                                   cimConfig,
                                                                   pr -> null,
                                                                   ZoneOffset.UTC);

        // When
        eventBus.emit(new InternalEvent("pid", PermissionProcessStatus.VALIDATED));

        // Then
        StepVerifier.create(handler.getPermissionMarketDocumentStream())
                    .then(handler::close)
                    .verifyComplete();
    }

    @Test
    void bothCimStreamsReplayPersistedPermissionStatus() {
        var start = LocalDate.now(ZoneOffset.UTC);
        var permission = new SimplePermissionRequest("pid", "cid", "dnid", start, start.plusDays(10),
                ZonedDateTime.now(ZoneOffset.UTC), PermissionProcessStatus.ACCEPTED);
        when(dataNeedsService.getById("dnid")).thenReturn(new AccountingPointDataNeed());
        var cimConfig = new PlainCommonInformationModelConfiguration(
                CodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME, "fallbackId");
        var handler = new PermissionMarketDocumentMessageHandler<>(new EventBusImpl(), repository,
                dataNeedsService, "EP-ID", cimConfig, pr -> null, ZoneOffset.UTC, () -> List.of(permission));

        StepVerifier.create(handler.getPermissionMarketDocumentStream().take(1))
                .expectNextCount(1)
                .verifyComplete();
        StepVerifier.create(handler.getRequestPermissionDocumentStream().take(1))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void concurrentPermissionEventsAreDeliveredToBothCimStreams() throws Exception {
        var start = LocalDate.now(ZoneOffset.UTC);
        var permission = new SimplePermissionRequest("pid", "cid", "dnid", start, start.plusDays(10),
                ZonedDateTime.now(ZoneOffset.UTC), PermissionProcessStatus.ACCEPTED);
        when(repository.findByPermissionId("pid")).thenReturn(Optional.of(permission));
        when(dataNeedsService.getById("dnid")).thenReturn(new AccountingPointDataNeed());
        var cimConfig = new PlainCommonInformationModelConfiguration(
                CodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME, "fallbackId");
        var handler = new PermissionMarketDocumentMessageHandler<>(new EventBusImpl(), repository,
                dataNeedsService, "EP-ID", cimConfig, pr -> null, ZoneOffset.UTC);
        var pmdCount = new AtomicInteger();
        var requestPmdCount = new AtomicInteger();
        var pmdSubscription = handler.getPermissionMarketDocumentStream().subscribe(ignored -> pmdCount.incrementAndGet());
        var requestSubscription = handler.getRequestPermissionDocumentStream().subscribe(ignored -> requestPmdCount.incrementAndGet());
        try (var executor = Executors.newFixedThreadPool(4)) {
            var submissions = new ArrayList<Future<?>>();
            for (var index = 0; index < 100; index++) {
                submissions.add(executor.submit(() -> handler.accept(new SimpleEvent("pid", PermissionProcessStatus.ACCEPTED))));
            }
            for (var submission : submissions) {
                submission.get(10, TimeUnit.SECONDS);
            }
            assertEquals(100, pmdCount.get());
            assertEquals(100, requestPmdCount.get());
        } finally {
            pmdSubscription.dispose();
            requestSubscription.dispose();
            handler.close();
        }
    }
}
