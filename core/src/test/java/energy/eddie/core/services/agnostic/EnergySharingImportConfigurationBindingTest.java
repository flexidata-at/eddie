// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.core.services.agnostic;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class EnergySharingImportConfigurationBindingTest {
  @Test
  void canonicalSpringApplicationJsonBindsExactImportOnlySettingsAndDefaultsDisabled() {
    var empty =
        Binder.get(new MockEnvironment())
            .bindOrCreate(
                "eddie.management.energy-sharing.import",
                EnergySharingImportAuthorization.Settings.class);
    assertFalse(empty.enabled());
    assertTrue(empty.scopes().isEmpty());
    var environment = new MockEnvironment();
    String json =
        """
        {"eddie.management.energy-sharing.import":{"enabled":true,"token-sha256":"%s",
        "expires-at":"2026-11-16T15:00:00Z","scopes":[{"region-connector-id":"at-eda",
        "operator-id":"CC-SYNTHETIC","connection-id":"connection","community-id":"EC-SYNTHETIC"}]}}
        """
            .formatted("a".repeat(64));
    environment
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "synthetic-environment", Map.of("SPRING_APPLICATION_JSON", json)));
    new SpringApplicationJsonEnvironmentPostProcessor()
        .postProcessEnvironment(environment, new SpringApplication());
    var settings =
        Binder.get(environment)
            .bind(
                "eddie.management.energy-sharing.import",
                EnergySharingImportAuthorization.Settings.class)
            .get();
    assertTrue(settings.enabled());
    assertEquals("a".repeat(64), settings.tokenSha256());
    assertEquals(Instant.parse("2026-11-16T15:00:00Z"), settings.expiresAt());
    assertEquals(
        java.util.List.of(
            new EnergySharingImportAuthorization.Scope(
                "at-eda", "CC-SYNTHETIC", "connection", "EC-SYNTHETIC")),
        settings.scopes());
  }
}
