// SPDX-FileCopyrightText: 2024-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.shared.event.sourcing.handlers.integration;


import energy.eddie.api.agnostic.process.model.PermissionRequest;
import energy.eddie.api.agnostic.process.model.persistence.PermissionRequestRepository;
import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.regionconnector.shared.event.sourcing.EventBus;
import energy.eddie.regionconnector.shared.event.sourcing.EventBusImpl;
import energy.eddie.regionconnector.shared.permission.requests.SimplePermissionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@SuppressWarnings("resource")
@ExtendWith(MockitoExtension.class)
class ConnectionStatusMessageHandlerTest {
    @Mock
    private PermissionRequestRepository<PermissionRequest> repository;

    @Test
    void streamReplaysPersistedStatusAfterRestart() {
        var permission = new SimplePermissionRequest("pid", "cid", "dnid", PermissionProcessStatus.ACCEPTED);
        var handler = new ConnectionStatusMessageHandler<>(new EventBusImpl(), repository, pr -> "", pr -> null,
                () -> List.of(permission));

        StepVerifier.create(handler.getConnectionStatusMessageStream())
                .assertNext(message -> assertAll(
                        () -> assertEquals("pid", message.permissionId()),
                        () -> assertEquals(PermissionProcessStatus.ACCEPTED, message.status())))
                .thenCancel()
                .verify();
    }

    @Test
    void liveEventUsesNewStatusEvenWhenPersistedProjectionIsStale() {
        var permission = new SimplePermissionRequest("pid", "cid", "dnid",
                PermissionProcessStatus.SENT_TO_PERMISSION_ADMINISTRATOR);
        when(repository.findByPermissionId("pid")).thenReturn(Optional.of(permission));
        var eventBus = new EventBusImpl();
        var handler = new ConnectionStatusMessageHandler<>(eventBus, repository, pr -> "");
        eventBus.emit(new SimpleEvent("pid", PermissionProcessStatus.ACCEPTED));

        StepVerifier.create(handler.getConnectionStatusMessageStream())
                .then(handler::close)
                .assertNext(message -> assertEquals(PermissionProcessStatus.ACCEPTED, message.status()))
                .verifyComplete();
    }

    @Test
    void testAccept_emitsStatusMessage() {
        // Given
        var permissionRequest = new SimplePermissionRequest("pid", "cid", "dnid", PermissionProcessStatus.VALIDATED);
        when(repository.findByPermissionId("pid")).thenReturn(Optional.of(permissionRequest));
        EventBusImpl eventBus = new EventBusImpl();
        var handler = new ConnectionStatusMessageHandler<>(eventBus, repository, pr -> "");

        // When
        eventBus.emit(new SimpleEvent("pid", PermissionProcessStatus.VALIDATED));

        // Then
        StepVerifier.create(handler.getConnectionStatusMessageStream())
                    .then(handler::close)
                    .assertNext(csm -> assertAll(
                            () -> assertEquals(permissionRequest.connectionId(), csm.connectionId()),
                            () -> assertEquals(permissionRequest.permissionId(), csm.permissionId()),
                            () -> assertEquals(permissionRequest.dataNeedId(), csm.dataNeedId()),
                            () -> assertEquals(PermissionProcessStatus.VALIDATED, csm.status()),
                            () -> assertEquals("", csm.message())
                    ))
                    .verifyComplete();
    }

    @Test
    void testAccept_doesNotEmitStatus_ifNoPermissionIsFound() {
        // Given
        when(repository.findByPermissionId("pid")).thenReturn(Optional.empty());
        EventBus eventBus = new EventBusImpl();
        var handler = new ConnectionStatusMessageHandler<>(eventBus, repository, pr -> "");

        // When
        eventBus.emit(new SimpleEvent("pid", PermissionProcessStatus.VALIDATED));

        // Then
        StepVerifier.create(handler.getConnectionStatusMessageStream())
                    .then(handler::close)
                    .verifyComplete();
    }

    @Test
    void testAccept_doesNotEmitStatus_onInternalEvents() {
        // Given
        EventBus eventBus = new EventBusImpl();
        var handler = new ConnectionStatusMessageHandler<>(eventBus, repository, pr -> "");

        // When
        eventBus.emit(new InternalEvent("pid", PermissionProcessStatus.VALIDATED));

        // Then
        StepVerifier.create(handler.getConnectionStatusMessageStream())
                    .then(handler::close)
                    .verifyComplete();
    }

    @Test
    void testAccept_emitsAdditionalInformation() {
        // Given
        var permissionRequest = new SimplePermissionRequest("pid", "cid", "dnid", PermissionProcessStatus.VALIDATED);
        when(repository.findByPermissionId("pid")).thenReturn(Optional.of(permissionRequest));
        EventBus eventBus = new EventBusImpl();
        var handler = new ConnectionStatusMessageHandler<>(eventBus,
                                                           repository,
                                                           pr -> "",
                                                           pr -> JsonNodeFactory.instance.objectNode()
                                                                                         .put("key", "value"));

        // When
        eventBus.emit(new SimpleEvent("pid", PermissionProcessStatus.VALIDATED));

        // Then
        StepVerifier.create(handler.getConnectionStatusMessageStream())
                    .then(handler::close)
                    .assertNext(csm -> assertEquals("value",
                                                    Objects.requireNonNull(csm.additionalInformation())
                                                           .get("key")
                                                           .asString()))
                    .verifyComplete();
    }
}
