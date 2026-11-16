// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

class EdaMasterDataRefreshConfigurationBindingTest {
  @Test
  void canonicalSpringApplicationJsonBindsExactRefreshTargetAndDefaultsDisabled() {
    var empty =
        Binder.get(new MockEnvironment())
            .bindOrCreate(
                "region-connector.at.eda.energy-sharing.master-data-refresh",
                EdaMasterDataRefreshConfiguration.class);
    assertFalse(empty.enabled());
    assertTrue(empty.targets().isEmpty());
    var environment = new MockEnvironment();
    String json =
        """
        {"region-connector.at.eda.energy-sharing.master-data-refresh":{"enabled":true,
        "targets":[{"operator-id":"CC-SYNTHETIC","connection-id":"connection","community-id":"EC-SYNTHETIC"}]}}
        """;
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
                "region-connector.at.eda.energy-sharing.master-data-refresh",
                EdaMasterDataRefreshConfiguration.class)
            .get();
    assertTrue(settings.enabled());
    assertEquals(
        List.of(
            new EdaMasterDataRefreshConfiguration.Target(
                "connection", "CC-SYNTHETIC", "EC-SYNTHETIC")),
        settings.targets());
  }
}
