// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.tasks;

import energy.eddie.dataneeds.needs.CESUJoinRequestDataNeed;
import energy.eddie.dataneeds.services.DataNeedsService;
import energy.eddie.regionconnector.at.api.AtPermissionRequestProjection;
import energy.eddie.regionconnector.at.api.AtPermissionRequestRepository;
import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import energy.eddie.regionconnector.at.eda.dto.IdentifiableECMPList;
import energy.eddie.regionconnector.at.eda.dto.energycommunity.EdaECMPList;
import energy.eddie.regionconnector.at.eda.permission.request.EdaPermissionRequest;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class IdentifyECMPListTask {
    private final AtPermissionRequestRepository repository;
    private final DataNeedsService dataNeedsService;
    private final AtConfiguration configuration;

    public IdentifyECMPListTask(
            AtPermissionRequestRepository repository,
            @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") DataNeedsService dataNeedsService,
            AtConfiguration configuration
    ) {
        this.repository = repository;
        this.dataNeedsService = dataNeedsService;
        this.configuration = configuration;
    }

    public Optional<IdentifiableECMPList> identify(EdaECMPList ecmpList) {
        if (!configuration.isEnergyCommunityMarketParty(ecmpList.receiverMessageAddress())) {
            return Optional.empty();
        }
        var requests = repository.findByConversationIdOrCMRequestId(ecmpList.conversationId(), null).stream()
                .filter(request -> matchesScope(request, ecmpList))
                .limit(2)
                .toList();
        if (requests.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(new IdentifiableECMPList(ecmpList, EdaPermissionRequest.fromProjection(requests.getFirst())));
    }

    private boolean matchesScope(AtPermissionRequestProjection request, EdaECMPList ecmpList) {
        var meteringPoint = request.getMeteringPointId();
        var points = ecmpList.mpListData();
        if (!request.getDsoId().equals(ecmpList.senderMessageAddress()) || meteringPoint == null
                || points.isEmpty() || points.stream().anyMatch(point -> !meteringPoint.equals(point.meteringPoint()))) {
            return false;
        }
        return dataNeedsService.findById(request.getDataNeedId())
                .filter(CESUJoinRequestDataNeed.class::isInstance)
                .map(CESUJoinRequestDataNeed.class::cast)
                .flatMap(need -> configuration.resolveEnergyCommunityId(need.energyCommunityId()))
                .filter(ecmpList.ecId()::equals)
                .isPresent();
    }
}
