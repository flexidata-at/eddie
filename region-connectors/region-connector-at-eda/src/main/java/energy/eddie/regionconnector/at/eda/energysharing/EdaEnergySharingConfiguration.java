// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.util.List;
import java.util.Map;

/** Server-owned authorization. Client-entered operator identifiers never grant authority. */
@ConfigurationProperties("region-connector.at.eda.energy-sharing")
public record EdaEnergySharingConfiguration(@DefaultValue List<Binding> bindings) {
    public record Binding(String connectionId, String operatorId, Map<String, String> communities,
                          @DefaultValue("false") boolean partnerConfigured) {}
}
