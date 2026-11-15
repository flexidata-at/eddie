// SPDX-FileCopyrightText: 2024-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.core.web;

import energy.eddie.api.agnostic.MessageStream;
import energy.eddie.cim.agnostic.ConnectionStatusMessage;
import org.slf4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static energy.eddie.regionconnector.shared.web.RestApiPaths.CONNECTION_STATUS_STREAM_BASE;

@RestController
public class ConnectionStatusMessageController {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ConnectionStatusMessageController.class);
    private final Sinks.Many<ConnectionStatusMessage> messages = Sinks.many().replay().limit(100);
    private final Map<String, ConnectionStatusMessage> latestMessagesByPermissionId = new ConcurrentHashMap<>();


    @GetMapping(value = CONNECTION_STATUS_STREAM_BASE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ConnectionStatusMessage>> connectionStatusMessageByPermissionId(
            @RequestParam("permission-id") List<String> permissionId
    ) {
        var filtered = Flux.defer(() -> {
            var lastSent = new HashMap<String, ConnectionStatusMessage>();
            var latest = Flux.fromIterable(permissionId).mapNotNull(latestMessagesByPermissionId::get);
            var updates = messages.asFlux()
                    .filter(message -> permissionId.contains(message.permissionId()))
                    // Do not replay an older buffered state after the current snapshot.
                    .filter(message -> Objects.equals(latestMessagesByPermissionId.get(message.permissionId()), message));
            return latest.concatWith(updates)
                    .filter(message -> !Objects.equals(lastSent.put(message.permissionId(), message), message));
        });

        return ResponseEntity.ok()
                             // Tell reverse proxies like Nginx not to buffer the response
                             .header("X-Accel-Buffering", "no")
                             .header("Connection", "keep-alive")
                             .body(filtered);
    }

    @MessageStream(ConnectionStatusMessage.class)
    void subscribe(Flux<ConnectionStatusMessage> flux) {
        flux
                .onErrorContinue((err, obj) -> LOGGER.warn(
                                         "Encountered error while processing connection status message",
                                         err
                                 )
                )
                .subscribe(this::emit);
    }

    private synchronized void emit(ConnectionStatusMessage message) {
        latestMessagesByPermissionId.put(message.permissionId(), message);
        messages.tryEmitNext(message);
    }
}
