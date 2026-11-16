// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.core.web;

import energy.eddie.api.agnostic.energysharing.*;
import energy.eddie.core.services.agnostic.EnergySharingRouter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Trusted management port only; callers must enforce authenticated tenant/operator assignments. */
@RestController
@RequestMapping("/${eddie.management.server.urlprefix}/energy-sharing")
public class EnergySharingManagementController {
    private final EnergySharingRouter router;

    public EnergySharingManagementController(EnergySharingRouter router) {
        this.router = router;
    }

    @GetMapping("/region-connectors")
    public List<String> regionConnectors() {
        return router.regionConnectors();
    }

    @GetMapping("/{regionConnectorId}/capabilities")
    public EnergySharingCapabilities capabilities(@PathVariable String regionConnectorId,
            @RequestParam String operatorId, @RequestParam String connectionId) {
        return router.capabilities(regionConnectorId, operatorId, connectionId);
    }

    @PostMapping("/{regionConnectorId}/operations")
    public ResponseEntity<EnergySharingOperation> submit(@PathVariable String regionConnectorId,
                                                        @RequestBody EnergySharingRequest request) {
        return ResponseEntity.accepted().body(router.submit(regionConnectorId, request));
    }

    @GetMapping("/{regionConnectorId}/operations/{requestId}")
    public EnergySharingOperation operation(@PathVariable String regionConnectorId, @PathVariable String requestId,
            @RequestParam String connectionId, @RequestParam String operatorId,
            @RequestParam(defaultValue = "") String communityId) {
        return router.operation(regionConnectorId, requestId, connectionId, operatorId, communityId);
    }

    @GetMapping("/{regionConnectorId}/communities")
    public List<EnergySharingCommunitySnapshot> communities(@PathVariable String regionConnectorId,
            @RequestParam String operatorId, @RequestParam String connectionId) {
        return router.communities(regionConnectorId, operatorId, connectionId);
    }

    @GetMapping("/{regionConnectorId}/communities/{communityId}")
    public EnergySharingCommunitySnapshot community(@PathVariable String regionConnectorId,
            @PathVariable String communityId, @RequestParam String operatorId, @RequestParam String connectionId) {
        return router.community(regionConnectorId, communityId, operatorId, connectionId);
    }

    @ExceptionHandler(EnergySharingException.class)
    public ResponseEntity<Map<String, String>> failure(EnergySharingException exception) {
        var status = switch (exception.code()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case UNSUPPORTED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case NOT_READY -> HttpStatus.SERVICE_UNAVAILABLE;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
        };
        return ResponseEntity.status(status).body(Map.of("code", exception.code().name(), "message", exception.getMessage()));
    }

    @GetMapping("/{regionConnectorId}/communities/{communityId}/measurements")
    public EnergySharingMeasurementPage measurements(@PathVariable String regionConnectorId,
            @PathVariable String communityId, @RequestParam String operatorId, @RequestParam String connectionId,
            @RequestParam(defaultValue = "0") long afterSequence, @RequestParam(defaultValue = "20") int limit) {
        return router.measurements(regionConnectorId, communityId, operatorId, connectionId, afterSequence, limit);
    }

    @GetMapping(value = "/{regionConnectorId}/communities/{communityId}/measurement-documents/{documentId}/source",
                produces = "application/xml")
    public ResponseEntity<byte[]> measurementSource(@PathVariable String regionConnectorId,
            @PathVariable String communityId, @PathVariable String documentId,
            @RequestParam String operatorId, @RequestParam String connectionId) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("X-Content-Type-Options", "nosniff")
                .body(router.measurementSource(regionConnectorId, communityId, operatorId, connectionId, documentId));
    }
}
