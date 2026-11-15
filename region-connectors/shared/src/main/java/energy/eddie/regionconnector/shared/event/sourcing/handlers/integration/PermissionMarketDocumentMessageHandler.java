// SPDX-FileCopyrightText: 2024-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.shared.event.sourcing.handlers.integration;

import energy.eddie.api.agnostic.MessageStream;
import energy.eddie.api.agnostic.process.model.PermissionRequest;
import energy.eddie.api.agnostic.process.model.events.InternalPermissionEvent;
import energy.eddie.api.agnostic.process.model.events.PermissionEvent;
import energy.eddie.api.agnostic.process.model.persistence.PermissionRequestRepository;
import energy.eddie.api.cim.config.CommonInformationModelConfiguration;
import energy.eddie.cim.v0_82.pmd.PermissionEnvelope;
import energy.eddie.cim.v1_12.rpmd.RequestPermissionEnvelope;
import energy.eddie.dataneeds.services.DataNeedsService;
import energy.eddie.regionconnector.shared.cim.IntermediatePermissionMarketDocument;
import energy.eddie.regionconnector.shared.cim.IntermediatePermissionMarketDocumentFactory;
import energy.eddie.regionconnector.shared.cim.v0_82.TransmissionScheduleProvider;
import energy.eddie.regionconnector.shared.event.sourcing.EventBus;
import energy.eddie.regionconnector.shared.event.sourcing.handlers.EventHandler;
import energy.eddie.regionconnector.shared.exceptions.PermissionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/**
 * This class converts a {@link PermissionEvent} to a CIM compliant document and provides them via a {@link Flux}.
 * It subscribes to all events present in an {@link EventBus} and based on thos creates PermissionMarketDocuments.
 */
public class PermissionMarketDocumentMessageHandler<T extends PermissionRequest> implements EventHandler<PermissionEvent>, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PermissionMarketDocumentMessageHandler.class);
    private final PermissionRequestRepository<T> repository;
    private final DataNeedsService dataNeedsService;
    private final Sinks.Many<PermissionEnvelope> pmdSink = Sinks.many().multicast().onBackpressureBuffer();
    private final Sinks.Many<Object> genericPmdSink = Sinks.many().multicast().onBackpressureBuffer();
    private final IntermediatePermissionMarketDocumentFactory<T> permissionMarketDocumentFactory;
    private final Supplier<? extends Iterable<T>> initialRequests;

    public PermissionMarketDocumentMessageHandler(
            EventBus eventBus,
            PermissionRequestRepository<T> repository,
            DataNeedsService dataNeedsService,
            String eligiblePartyId,
            CommonInformationModelConfiguration cimConfig,
            TransmissionScheduleProvider<T> transmissionScheduleProvider,
            ZoneId zoneId
    ) {
        this(eventBus, repository, dataNeedsService, eligiblePartyId, cimConfig,
                transmissionScheduleProvider, zoneId, List::of);
    }

    public PermissionMarketDocumentMessageHandler(
            EventBus eventBus,
            PermissionRequestRepository<T> repository,
            DataNeedsService dataNeedsService,
            String eligiblePartyId,
            CommonInformationModelConfiguration cimConfig,
            TransmissionScheduleProvider<T> transmissionScheduleProvider,
            ZoneId zoneId,
            Supplier<? extends Iterable<T>> initialRequests
    ) {
        this.repository = repository;
        this.dataNeedsService = dataNeedsService;
        this.initialRequests = initialRequests;
        this.permissionMarketDocumentFactory = new IntermediatePermissionMarketDocumentFactory<>(
                eligiblePartyId,
                transmissionScheduleProvider,
                cimConfig.eligiblePartyNationalCodingScheme().value(),
                zoneId
        );
        eventBus.filteredFlux(PermissionEvent.class)
                .subscribe(this::accept);
    }

    @Override
    public void accept(PermissionEvent permissionEvent) {
        if (permissionEvent instanceof InternalPermissionEvent) {
            return;
        }
        String permissionId = permissionEvent.permissionId();
        var optionalRequest = repository.findByPermissionId(permissionId);
        if (optionalRequest.isEmpty()) {
            LOGGER.warn("Got event without permission request for permission id {}", permissionId);
            genericPmdSink.tryEmitError(new PermissionNotFoundException(permissionId));
            return;
        }
        var permissionRequest = optionalRequest.get();
        var dataNeed = dataNeedsService.getById(permissionRequest.dataNeedId());
        permissionMarketDocumentFactory.each(permissionRequest, permissionEvent.status(), dataNeed)
                                       .map(IntermediatePermissionMarketDocument::toPermissionMarketDocument)
                                       .onErrorContinue(PermissionMarketDocumentMessageHandler::onError)
                                       .subscribe(this::emit);
    }

    @MessageStream(PermissionEnvelope.class)
    public Flux<PermissionEnvelope> getPermissionMarketDocumentStream() {
        return Flux.merge(initialDocuments(), genericPmdSink.asFlux())
                             .ofType(PermissionEnvelope.class);
    }

    @MessageStream(RequestPermissionEnvelope.class)
    public Flux<RequestPermissionEnvelope> getRequestPermissionDocumentStream() {
        return Flux.merge(initialDocuments(), genericPmdSink.asFlux())
                             .ofType(RequestPermissionEnvelope.class);
    }

    @Override
    public void close() {
        pmdSink.tryEmitComplete();
        genericPmdSink.tryEmitComplete();
    }

    private static void onError(Throwable throwable, Object obj) {
        LOGGER.warn("Error while trying to convert PermissionMarketDocument {}.", obj, throwable);
    }

    private Flux<Object> initialDocuments() {
        return Flux.defer(() -> Flux.fromIterable(initialRequests.get()))
                .flatMap(request -> permissionMarketDocumentFactory.each(request, request.status(),
                                dataNeedsService.getById(request.dataNeedId()))
                        .map(IntermediatePermissionMarketDocument::toPermissionMarketDocument))
                .onErrorContinue(PermissionMarketDocumentMessageHandler::onError);
    }

    private synchronized void emit(Object document) {
        var result = genericPmdSink.tryEmitNext(document);
        if (result.isFailure()) {
            LOGGER.warn("Could not emit PermissionMarketDocument {}: {}", document, result);
        }
    }
}
