// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.core.web;

import energy.eddie.api.agnostic.energysharing.*;
import energy.eddie.core.services.agnostic.EnergySharingImportAuthorization;
import energy.eddie.core.services.agnostic.EnergySharingRouter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Management-port filter plus an expiring, exact-scope import credential; dry-run is the default.
 */
@RestController
@RequestMapping(
    "/${eddie.management.server.urlprefix}/energy-sharing/{regionConnectorId}/communities/{communityId}/measurement-documents/import")
public class EnergySharingMeasurementImportController {
  private final EnergySharingRouter router;
  private final EnergySharingImportAuthorization authorization;

  public EnergySharingMeasurementImportController(
      EnergySharingRouter router, EnergySharingImportAuthorization authorization) {
    this.router = router;
    this.authorization = authorization;
  }

  @PostMapping(consumes = "application/xml")
  public ResponseEntity<EnergySharingMeasurementImportResult> importDocument(
      @PathVariable String regionConnectorId,
      @PathVariable String communityId,
      @RequestParam String operatorId,
      @RequestParam String connectionId,
      @RequestParam(defaultValue = "true") boolean dryRun,
      HttpServletRequest http)
      throws IOException {
    authorization.require(
        http.getHeader("Authorization"),
        new EnergySharingImportAuthorization.Scope(
            regionConnectorId, operatorId, connectionId, communityId));
    if (http.getContentLengthLong() > EnergySharingMeasurementImport.MAX_BYTES)
      throw new EnergySharingException(
          EnergySharingException.Code.INVALID_REQUEST, "Archive document exceeds 16 MiB");
    byte[] bytes = http.getInputStream().readNBytes(EnergySharingMeasurementImport.MAX_BYTES + 1);
    var request = archiveRequest(http, bytes, dryRun);
    return ResponseEntity.ok()
        .header("Cache-Control", "no-store")
        .body(
            router.importMeasurement(
                regionConnectorId, communityId, operatorId, connectionId, request));
  }

  private static EnergySharingMeasurementImport archiveRequest(
      HttpServletRequest http, byte[] bytes, boolean dryRun) {
    try {
      return new EnergySharingMeasurementImport(
          bytes,
          java.util.Objects.toString(http.getHeader("X-Archive-Id"), ""),
          Instant.parse(java.util.Objects.toString(http.getHeader("X-Archive-Received-At"), "")),
          java.util.Objects.toString(http.getHeader("X-Archive-Payload-Sha256"), ""),
          dryRun);
    } catch (java.time.format.DateTimeParseException invalid) {
      throw new EnergySharingException(
          EnergySharingException.Code.INVALID_REQUEST,
          "A valid original archive receipt timestamp is required");
    }
  }

  @ExceptionHandler(EnergySharingException.class)
  public ResponseEntity<Map<String, String>> failure(EnergySharingException failure) {
    var status =
        switch (failure.code()) {
          case FORBIDDEN -> HttpStatus.FORBIDDEN;
          case NOT_READY -> HttpStatus.SERVICE_UNAVAILABLE;
          case CONFLICT -> HttpStatus.CONFLICT;
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case UNSUPPORTED -> HttpStatus.UNPROCESSABLE_CONTENT;
          case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
        };
    return ResponseEntity.status(status)
        .body(Map.of("code", failure.code().name(), "message", failure.getMessage()));
  }
}
