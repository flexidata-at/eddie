// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.core.services.agnostic;

import energy.eddie.api.agnostic.energysharing.EnergySharingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;

/**
 * Separate import-only credential; ordinary management read access does not grant native import
 * rights.
 */
@Component
@EnableConfigurationProperties(EnergySharingImportAuthorization.Settings.class)
public class EnergySharingImportAuthorization {
  @ConfigurationProperties("eddie.management.energy-sharing.import")
  public record Settings(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("") String tokenSha256,
      @Nullable Instant expiresAt,
      @DefaultValue List<Scope> scopes) {
    public Settings {
      scopes = List.copyOf(scopes);
    }
  }

  public record Scope(
      String regionConnectorId, String operatorId, String connectionId, String communityId) {}

  private final Settings settings;
  private final Clock clock;

  @org.springframework.beans.factory.annotation.Autowired
  public EnergySharingImportAuthorization(Settings settings) {
    this(settings, Clock.systemUTC());
  }

  EnergySharingImportAuthorization(Settings settings, Clock clock) {
    this.settings = settings;
    this.clock = clock;
  }

  public void require(@Nullable String authorization, Scope scope) {
    if (!active() || !settings.scopes().contains(scope) || !validToken(authorization))
      throw new EnergySharingException(
          EnergySharingException.Code.FORBIDDEN, "Scoped archive-import authorization is required");
  }

  private boolean active() {
    var expiry = settings.expiresAt();
    return settings.enabled()
        && expiry != null
        && expiry.isAfter(clock.instant())
        && settings.tokenSha256().matches("[0-9a-f]{64}");
  }

  private boolean validToken(@Nullable String authorization) {
    if (authorization == null
        || !authorization.startsWith("Bearer ")
        || authorization.length() < 39
        || authorization.length() > 207) return false;
    try {
      byte[] actual =
          MessageDigest.getInstance("SHA-256")
              .digest(authorization.substring(7).getBytes(StandardCharsets.UTF_8));
      return MessageDigest.isEqual(HexFormat.of().parseHex(settings.tokenSha256()), actual);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
