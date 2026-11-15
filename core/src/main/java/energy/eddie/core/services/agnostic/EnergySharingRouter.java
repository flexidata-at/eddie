// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.core.services.agnostic;

import energy.eddie.api.agnostic.energysharing.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static energy.eddie.api.agnostic.energysharing.EnergySharingException.Code.*;

/** Routes only explicitly supported national management profiles. */
@Service
public class EnergySharingRouter {
    private final Map<String, RegionConnectorEnergySharingService> services = new ConcurrentHashMap<>();

    public void register(String regionConnectorId, RegionConnectorEnergySharingService service) {
        var previous = services.putIfAbsent(regionConnectorId, service);
        if (previous != null && previous != service) {
            throw new IllegalStateException("An energy sharing service is already registered for " + regionConnectorId);
        }
    }

    public List<String> regionConnectors() {
        return services.keySet().stream().sorted().toList();
    }

    public EnergySharingCapabilities capabilities(String regionConnectorId, String operatorId, String connectionId) {
        requireScope(operatorId, connectionId);
        var result = service(regionConnectorId).capabilities(operatorId, connectionId);
        if (!operatorId.equals(result.operatorId())) {
            throw new EnergySharingException(FORBIDDEN, "Operator scope mismatch");
        }
        return result;
    }

    public EnergySharingOperation submit(String regionConnectorId, EnergySharingRequest request) {
        var capabilities = capabilities(regionConnectorId, request.operatorId(), request.connectionId());
        if (!capabilities.profileVersion().equals(request.profileVersion())) {
            throw new EnergySharingException(CONFLICT, "Refresh capabilities before submitting a different profile version");
        }
        if (!capabilities.operations().contains(request.operation())) {
            throw new EnergySharingException(UNSUPPORTED, "This operation is not supported by the selected profile");
        }
        if (!capabilities.transportReady()) {
            throw new EnergySharingException(NOT_READY, "Complete the operator transport setup before submitting");
        }
        var operation = service(regionConnectorId).submit(request);
        checkScope(operation, request.requestId(), request.connectionId(), request.operatorId(), request.communityId());
        return operation;
    }

    public EnergySharingOperation operation(String regionConnectorId, String requestId, String connectionId,
                                            String operatorId, String communityId) {
        requireScope(operatorId, connectionId);
        var result = service(regionConnectorId).operation(requestId, connectionId, operatorId, communityId)
                .orElseThrow(() -> new EnergySharingException(NOT_FOUND, "Energy sharing operation not found"));
        checkScope(result, requestId, connectionId, operatorId, communityId);
        return result;
    }

    public List<EnergySharingCommunitySnapshot> communities(String regionConnectorId, String operatorId, String connectionId) {
        requireScope(operatorId, connectionId);
        var result = service(regionConnectorId).communities(operatorId, connectionId);
        if (result.stream().anyMatch(snapshot -> !operatorId.equals(snapshot.operatorId())
                || !connectionId.equals(snapshot.connectionId()))) {
            throw new EnergySharingException(FORBIDDEN, "Community scope mismatch");
        }
        return List.copyOf(result);
    }

    public EnergySharingCommunitySnapshot community(String regionConnectorId, String communityId,
                                                     String operatorId, String connectionId) {
        return communities(regionConnectorId, operatorId, connectionId).stream()
                .filter(snapshot -> snapshot.communityId().equals(communityId)).findFirst()
                .orElseThrow(() -> new EnergySharingException(NOT_FOUND, "Energy sharing community not found"));
    }

    private RegionConnectorEnergySharingService service(String regionConnectorId) {
        var result = services.get(regionConnectorId);
        if (result == null) {
            throw new EnergySharingException(UNSUPPORTED, "This region connector does not support energy sharing management");
        }
        return result;
    }

    private static void requireScope(String operatorId, String connectionId) {
        if (operatorId.isBlank() || connectionId.isBlank()) {
            throw new EnergySharingException(INVALID_REQUEST, "Operator and connection scope are required");
        }
    }

    private static void checkScope(EnergySharingOperation result, String requestId, String connectionId,
                                    String operatorId, String communityId) {
        if (!requestId.equals(result.requestId()) || !connectionId.equals(result.connectionId())
                || !operatorId.equals(result.operatorId()) || !communityId.equals(result.communityId())) {
            throw new EnergySharingException(FORBIDDEN, "Operation scope mismatch");
        }
    }
}
