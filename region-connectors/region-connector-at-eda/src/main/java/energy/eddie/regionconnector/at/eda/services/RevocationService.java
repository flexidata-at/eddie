// SPDX-FileCopyrightText: 2024-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.services;

import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.dataneeds.needs.CESUJoinRequestDataNeed;
import energy.eddie.dataneeds.services.DataNeedsService;
import energy.eddie.regionconnector.at.api.AtPermissionRequest;
import energy.eddie.regionconnector.at.api.AtPermissionRequestProjection;
import energy.eddie.regionconnector.at.api.AtPermissionRequestRepository;
import energy.eddie.regionconnector.at.eda.EdaAdapter;
import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import energy.eddie.regionconnector.at.eda.dto.EdaCMRevoke;
import energy.eddie.regionconnector.at.eda.permission.request.EdaPermissionRequest;
import energy.eddie.regionconnector.at.eda.permission.request.events.SimpleEvent;
import energy.eddie.regionconnector.shared.event.sourcing.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RevocationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(RevocationService.class);
    private final AtPermissionRequestRepository repository;
    private final Outbox outbox;
    private final DataNeedsService dataNeedsService;
    private final AtConfiguration configuration;

    public RevocationService(
            EdaAdapter edaAdapter, AtPermissionRequestRepository repository, Outbox outbox,
            @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") DataNeedsService dataNeedsService,
            AtConfiguration configuration
    ) {
        this.repository = repository;
        this.outbox = outbox;
        this.dataNeedsService = dataNeedsService;
        this.configuration = configuration;
        edaAdapter.getCMRevokeStream()
                  .subscribe(this::onRevocation);
    }

    private void onRevocation(EdaCMRevoke cmRevoke) {
        String consentId = cmRevoke.consentId();
        var optionalRequest = repository.findByConsentId(consentId);
        if (optionalRequest.isPresent()) {
            var projection = optionalRequest.get();
            var need = dataNeedsService.findById(projection.getDataNeedId());
            if (need.orElse(null) instanceof CESUJoinRequestDataNeed cesuNeed) {
                if (configuration.isEnergyCommunityMarketParty(cmRevoke.receiverMessageAddress())
                        && configuration.resolveEnergyCommunityId(cesuNeed.energyCommunityId()).isPresent()
                        && cmRevoke.meteringPoint().equals(projection.getMeteringPointId())
                        && projection.getDsoId().equals(cmRevoke.senderMessageAddress())) {
                    revoke(EdaPermissionRequest.fromProjection(projection), true);
                }
            } else if (need.isPresent()) {
                revoke(EdaPermissionRequest.fromProjection(projection), false);
            }
            return;
        }
        LOGGER.atWarn()
              .addArgument(consentId)
              .log("Got CM Revoke Message with unknown consent id: {}; Using fallback and querying permission request by metering point id and revoke date.");

        var requests = fallback(cmRevoke);
        if (requests.isEmpty()) {
            LOGGER.error("Got Revoke Message with unknown consent id: {}; Could not revoke.", consentId);
        } else {
            // The shared market-party receiver cannot identify the process type. Preserve the
            // ordinary metering-data fallback, but never revoke a CESU without its consent ID.
            requests.stream()
                    .filter(request -> dataNeedsService.findById(request.getDataNeedId())
                            .filter(need -> !(need instanceof CESUJoinRequestDataNeed)).isPresent())
                    .forEach(request -> revoke(EdaPermissionRequest.fromProjection(request), false));
        }
    }

    private void revoke(AtPermissionRequest permissionRequest, boolean community) {
        if (permissionRequest.status() != PermissionProcessStatus.ACCEPTED
                && (!community || permissionRequest.status() != PermissionProcessStatus.FULFILLED)) {
            return;
        }
        outbox.commit(new SimpleEvent(permissionRequest.permissionId(), PermissionProcessStatus.REVOKED));
        LOGGER.atInfo()
              .addArgument(permissionRequest::permissionId)
              .log("Revoking permission for permission id {}");
    }

    private List<AtPermissionRequestProjection> fallback(EdaCMRevoke cmRevoke) {
        return repository.findAcceptedAndFulfilledByMeteringPointIdAndDate(cmRevoke.meteringPoint(),
                                                                           cmRevoke.consentEnd());
    }
}
