// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.core.services.agnostic;

import static org.junit.jupiter.api.Assertions.*;

import energy.eddie.api.agnostic.energysharing.EnergySharingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class EnergySharingImportAuthorizationTest {
  private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
  private static final String TOKEN = "synthetic-local-import-test-credential-only";
  private static final EnergySharingImportAuthorization.Scope SCOPE =
      new EnergySharingImportAuthorization.Scope("at-eda", "operator", "connection", "community");

  private String digest() throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(TOKEN.getBytes(StandardCharsets.UTF_8)));
  }

  private EnergySharingImportAuthorization gate(boolean enabled, Instant expires) throws Exception {
    return new EnergySharingImportAuthorization(
        new EnergySharingImportAuthorization.Settings(enabled, digest(), expires, List.of(SCOPE)),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void exactImportCredentialAndScopeRequiredIncludingAllFourScopeParts() throws Exception {
    var gate = gate(true, NOW.plusSeconds(60));
    gate.require("Bearer " + TOKEN, SCOPE);
    for (var foreign :
        List.of(
            new EnergySharingImportAuthorization.Scope(
                "other", "operator", "connection", "community"),
            new EnergySharingImportAuthorization.Scope(
                "at-eda", "other", "connection", "community"),
            new EnergySharingImportAuthorization.Scope("at-eda", "operator", "other", "community"),
            new EnergySharingImportAuthorization.Scope(
                "at-eda", "operator", "connection", "other")))
      assertThrows(EnergySharingException.class, () -> gate.require("Bearer " + TOKEN, foreign));
    for (String credential :
        List.of(
            "",
            "Basic " + TOKEN,
            "Bearer short",
            "Bearer " + "x".repeat(500),
            "Bearer " + "x".repeat(64)))
      assertThrows(EnergySharingException.class, () -> gate.require(credential, SCOPE));
    assertThrows(EnergySharingException.class, () -> gate.require(null, SCOPE));
  }

  @Test
  void disabledExpiredMissingExpiryAndMalformedConfigurationFailClosed() throws Exception {
    for (var gate : List.of(gate(false, NOW.plusSeconds(60)), gate(true, NOW), gate(true, null)))
      assertThrows(EnergySharingException.class, () -> gate.require("Bearer " + TOKEN, SCOPE));
    var bad =
        new EnergySharingImportAuthorization(
            new EnergySharingImportAuthorization.Settings(
                true, "not-a-digest", NOW.plusSeconds(60), List.of(SCOPE)),
            Clock.fixed(NOW, ZoneOffset.UTC));
    assertThrows(EnergySharingException.class, () -> bad.require("Bearer " + TOKEN, SCOPE));
  }
}
