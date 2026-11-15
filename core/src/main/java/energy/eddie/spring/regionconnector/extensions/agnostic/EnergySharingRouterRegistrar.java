// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.spring.regionconnector.extensions.agnostic;

import energy.eddie.api.agnostic.RegionConnectorExtension;
import energy.eddie.api.agnostic.energysharing.RegionConnectorEnergySharingService;
import energy.eddie.api.v0.RegionConnector;
import energy.eddie.core.services.agnostic.EnergySharingRouter;

import java.util.Optional;

/** Additive capability: connectors without market management continue to operate unchanged. */
@RegionConnectorExtension
public class EnergySharingRouterRegistrar {
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public EnergySharingRouterRegistrar(RegionConnector regionConnector,
                                        Optional<RegionConnectorEnergySharingService> service,
                                        Optional<EnergySharingRouter> router) {
        if (service.isPresent() && router.isPresent()) {
            router.get().register(regionConnector.getMetadata().id(), service.get());
        }
    }
}
