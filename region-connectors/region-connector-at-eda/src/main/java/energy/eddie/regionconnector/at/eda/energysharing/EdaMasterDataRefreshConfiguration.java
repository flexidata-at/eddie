// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import java.util.HashSet;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Additional, explicit server-owned opt-in; never inferred from configured transport access. */
@ConfigurationProperties("region-connector.at.eda.energy-sharing.master-data-refresh")
public record EdaMasterDataRefreshConfiguration(
    @DefaultValue("false") boolean enabled, @DefaultValue List<Target> targets) {
  public EdaMasterDataRefreshConfiguration {
    targets = List.copyOf(targets);
    if (targets.size() > 100)
      throw new IllegalArgumentException("At most 100 refresh targets are supported");
    var communities = new HashSet<List<String>>();
    for (var target : targets) {
      if (target.connectionId().isBlank()
          || target.operatorId().isBlank()
          || target.communityId().isBlank())
        throw new IllegalArgumentException(
            "Refresh targets require an exact connection, operator and community");
      if (!communities.add(List.of(target.operatorId(), target.communityId())))
        throw new IllegalArgumentException(
            "A community must have only one scheduled refresh connection");
    }
  }

  public record Target(String connectionId, String operatorId, String communityId) {}
}
