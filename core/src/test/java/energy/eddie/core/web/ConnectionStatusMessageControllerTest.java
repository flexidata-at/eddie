// SPDX-FileCopyrightText: 2024-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.core.web;

import energy.eddie.cim.agnostic.ConnectionStatusMessage;
import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.core.security.JwtIssuerFilter;
import energy.eddie.core.services.MessageStreamHub;
import energy.eddie.spring.regionconnector.extensions.StreamProviderAndSupplierRegistrar;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;

import java.util.List;
import java.util.stream.IntStream;

import static java.util.Objects.requireNonNull;

@WebFluxTest(
        value = ConnectionStatusMessageController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JwtIssuerFilter.class)
)
@AutoConfigureMockMvc(addFilters = false) // disables spring security filters
@Import(ConnectionStatusMessageControllerTest.TestConfig.class)
class ConnectionStatusMessageControllerTest {
    @Autowired
    private WebTestClient webTestClient;
    @Autowired
    @SuppressWarnings("unused")
    private ConnectionStatusMessageController controller;
    @Autowired
    private MessageStreamHub messageStreamHub;

    @Test
    void connectionStatusMessageByPermissionIds_sendsStatus() {
        var message1 = statusMessage("1", PermissionProcessStatus.CREATED);
        var message2 = statusMessage("2", PermissionProcessStatus.SENT_TO_PERMISSION_ADMINISTRATOR);
        var message3 = statusMessage("3", PermissionProcessStatus.VALIDATED);
        TestPublisher<ConnectionStatusMessage> publisher = TestPublisher.create();
        messageStreamHub.registerProvider(ConnectionStatusMessage.class, publisher::flux);
        publisher.next(message1, message2, message3);
        publisher.complete();

        var result = webTestClient.get()
                                  .uri(b -> b.path("/api/connection-status-messages")
                                             .queryParam("permission-id", "1", "3")
                                             .build())
                                  .accept(MediaType.TEXT_EVENT_STREAM)
                                  .exchange()
                                  .expectStatus().isOk()
                                  .returnResult(ConnectionStatusMessage.class)
                                  .getResponseBody();

        StepVerifier.create(result)
                    // only check status to avoid time zone issues
                    .expectNextMatches(message -> message1.status().equals(message.status()))
                    .expectNextMatches(message -> message3.status().equals(message.status()))
                    .thenCancel()
                    .verify();
    }

    @Test
    void connectionStatusMessageByPermissionIds_replaysLatestStatusAfterEventBufferRolledOver() {
        var isolatedController = new ConnectionStatusMessageController();
        TestPublisher<ConnectionStatusMessage> publisher = TestPublisher.create();
        isolatedController.subscribe(publisher.flux());
        publisher.next(statusMessage("durable", PermissionProcessStatus.ACCEPTED));
        IntStream.range(0, 110)
                .mapToObj(index -> statusMessage("other-" + index, PermissionProcessStatus.CREATED))
                .forEach(publisher::next);

        StepVerifier.create(requireNonNull(isolatedController.connectionStatusMessageByPermissionId(List.of("durable")).getBody()))
                .expectNextMatches(message -> message.permissionId().equals("durable")
                        && message.status() == PermissionProcessStatus.ACCEPTED)
                .thenCancel()
                .verify();
    }

    @Test
    void lateSubscriberGetsLatestStateThenLiveUpdatesWithoutOlderBufferedStates() {
        var isolatedController = new ConnectionStatusMessageController();
        TestPublisher<ConnectionStatusMessage> publisher = TestPublisher.create();
        isolatedController.subscribe(publisher.flux());
        var accepted = statusMessage("permission", PermissionProcessStatus.ACCEPTED);
        var duplicateAccepted = new ConnectionStatusMessage(accepted.connectionId(), accepted.permissionId(),
                accepted.dataNeedId(), accepted.dataSourceInformation(), accepted.timestamp(), accepted.status(),
                accepted.message(), accepted.additionalInformation());
        publisher.next(statusMessage("permission", PermissionProcessStatus.CREATED),
                accepted);

        StepVerifier.create(requireNonNull(isolatedController.connectionStatusMessageByPermissionId(List.of("permission")).getBody()))
                .expectNextMatches(message -> message.status() == PermissionProcessStatus.ACCEPTED)
                .then(() -> publisher.next(duplicateAccepted, statusMessage("permission", PermissionProcessStatus.REVOKED)))
                .expectNextMatches(message -> message.status() == PermissionProcessStatus.REVOKED)
                .thenCancel()
                .verify();
    }

    private ConnectionStatusMessage statusMessage(String pid, PermissionProcessStatus status) {
        return new ConnectionStatusMessage(
                "1",
                pid,
                "1",
                null,
                status);
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        MessageStreamHub messageStreamHub() {
            return new MessageStreamHub();
        }

        @Bean
        StreamProviderAndSupplierRegistrar streamProviderAndSupplierRegistrar() {
            return new StreamProviderAndSupplierRegistrar();
        }
    }
}
