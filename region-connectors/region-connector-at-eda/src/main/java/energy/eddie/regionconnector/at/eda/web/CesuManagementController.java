// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.web;

import energy.eddie.api.agnostic.data.needs.EnergyDirection;
import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.cim.serde.DeserializationException;
import energy.eddie.cim.serde.JsonMessageSerde;
import energy.eddie.cim.v1_12.StandardCodingSchemeTypeList;
import energy.eddie.cim.v1_12.StandardDirectionTypeList;
import energy.eddie.cim.v1_12.esr.EnergyCommunity;
import energy.eddie.dataneeds.exceptions.DataNeedNotFoundException;
import energy.eddie.dataneeds.exceptions.UnsupportedDataNeedException;
import energy.eddie.dataneeds.needs.CESUJoinRequestDataNeed;
import energy.eddie.dataneeds.services.DataNeedsService;
import energy.eddie.regionconnector.at.api.AtPermissionRequest;
import energy.eddie.regionconnector.at.api.AtPermissionRequestRepository;
import energy.eddie.regionconnector.at.eda.EdaRegionConnector;
import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import energy.eddie.regionconnector.at.eda.permission.request.dtos.CreatedPermissionRequest;
import energy.eddie.regionconnector.at.eda.permission.request.dtos.PermissionRequestForCreation;
import energy.eddie.regionconnector.at.eda.services.PermissionRequestCreationAndValidationService;
import jakarta.validation.Validator;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static energy.eddie.regionconnector.shared.web.RestApiPaths.connectionStatusMessagesStreamFor;
import static java.nio.charset.StandardCharsets.UTF_8;

@RestController
@RequestMapping("${eddie.management.server.urlprefix}")
public class CesuManagementController {
    private static final Set<PermissionProcessStatus> CAN_TERMINATE = Set.of(
            PermissionProcessStatus.ACCEPTED,
            PermissionProcessStatus.FULFILLED,
            PermissionProcessStatus.FAILED_TO_TERMINATE
    );

    private final JsonMessageSerde serde = new JsonMessageSerde();
    private final DataNeedsService dataNeedsService;
    private final AtConfiguration configuration;
    private final PermissionRequestCreationAndValidationService creationService;
    private final AtPermissionRequestRepository repository;
    private final EdaRegionConnector regionConnector;
    private final Validator validator;

    public CesuManagementController(
            @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") DataNeedsService dataNeedsService,
            AtConfiguration configuration,
            PermissionRequestCreationAndValidationService creationService,
            AtPermissionRequestRepository repository,
            EdaRegionConnector regionConnector,
            Validator validator
    ) {
        this.dataNeedsService = dataNeedsService;
        this.configuration = configuration;
        this.creationService = creationService;
        this.repository = repository;
        this.regionConnector = regionConnector;
        this.validator = validator;
    }

    @PostMapping(value = "/cesu-join-request", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CreatedPermissionRequest> requestJoin(@RequestBody JsonNode payload)
            throws DataNeedNotFoundException, UnsupportedDataNeedException {
        var factorNode = payload.path("energyCommunity").path("AccountingPoint").path(0)
                .path("energySharingParticipationFactor");
        if (!factorNode.isIntegralNumber()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A whole-number participation factor is required");
        }
        CesuJoinRequest request;
        try {
            request = serde.deserialize(payload.toString().getBytes(UTF_8), CesuJoinRequest.class);
        } catch (DeserializationException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid CESU join request", exception);
        }
        if (request == null || request.energyCommunity() == null || !StringUtils.hasText(request.dataNeedId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A data need and a CIM energy community are required");
        }
        var community = request.energyCommunity();
        var dataNeed = scopedDataNeed(request.dataNeedId(), community.getMRID());
        if (community.getAccountingPoints().size() != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A CESU join must have exactly one accounting point");
        }
        var accountingPoint = community.getAccountingPoints().getFirst();
        var identifier = accountingPoint.getMRID();
        var factor = accountingPoint.getEnergySharingParticipationFactor();
        if (identifier == null || !StandardCodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME.value().equals(identifier.getCodingScheme())
                || identifier.getValue() == null || !identifier.getValue().matches("AT[A-Z0-9]{31}") || factor == null
                || factor.signum() < 1 || factor.compareTo(BigInteger.valueOf(100)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An Austrian accounting point and a participation factor from 1 to 100 are required");
        }
        var direction = energyDirection(accountingPoint.getEnergySharingEnergyDirection());
        if (request.permissionAdministratorId() == null || !request.permissionAdministratorId().matches("AT[0-9]{6}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An Austrian permission administrator ID is required");
        }
        var participationFactor = factor.intValueExact();
        if (dataNeed.energyDirection().filter(value -> value != direction).isPresent()
                || dataNeed.participationFactor().filter(value -> value != participationFactor).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The accounting-point settings do not match the prepared data need");
        }
        var nativeRequest = new PermissionRequestForCreation(request.connectionId(), identifier.getValue(),
                List.of(request.dataNeedId()), request.permissionAdministratorId(), direction, participationFactor);
        var violations = validator.validate(nativeRequest);
        if (!violations.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid accounting-point ID, permission administrator or connection ID");
        }
        var created = creationService.createAndValidatePermissionRequest(nativeRequest);
        return ResponseEntity.created(connectionStatusMessagesStreamFor(created.permissionIds().toArray())).body(created);
    }

    @PostMapping(value = "/cesu-permissions/{permissionId}/termination", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> requestTermination(@PathVariable String permissionId, @RequestBody CesuPermissionScope scope) {
        var permission = scopedPermission(permissionId, scope);
        var status = permission.status();
        if (status == PermissionProcessStatus.REQUIRES_EXTERNAL_TERMINATION || status == PermissionProcessStatus.TERMINATED
                || status == PermissionProcessStatus.REVOKED || status == PermissionProcessStatus.EXTERNALLY_TERMINATED) {
            return ResponseEntity.accepted().build();
        }
        if (!CAN_TERMINATE.contains(status) || permission.consentId().isEmpty() || permission.meteringPointId().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This permission has no active market consent to terminate");
        }
        regionConnector.terminatePermission(permissionId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping(value = "/cesu-permissions/{permissionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CesuPermissionStatus permissionStatus(@PathVariable String permissionId,
            @RequestParam String connectionId, @RequestParam String dataNeedId, @RequestParam String energyCommunityId) {
        var permission = scopedPermission(permissionId, new CesuPermissionScope(connectionId, dataNeedId, energyCommunityId));
        return new CesuPermissionStatus(permission.permissionId(), permission.status(),
                permission.start(), permission.end(), permission.message());
    }

    @GetMapping(value = "/cesu-capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public CesuCapabilities capabilities(@RequestParam String energyCommunityId) {
        if (configuration.resolveEnergyCommunityId(Optional.of(energyCommunityId)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Energy community is not configured for this region connector");
        }
        return new CesuCapabilities(energyCommunityId, configuration.eligiblePartyId(),
                true, true, true);
    }

    private AtPermissionRequest scopedPermission(String permissionId, CesuPermissionScope scope) {
        if (!StringUtils.hasText(scope.dataNeedId()) || !StringUtils.hasText(scope.connectionId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A data need and connection ID are required");
        }
        scopedDataNeed(scope.dataNeedId(), scope.energyCommunityId());
        return repository.findByPermissionId(permissionId)
                .filter(value -> scope.dataNeedId().equals(value.dataNeedId()))
                .filter(value -> scope.connectionId().equals(value.connectionId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CESU permission not found"));
    }

    private CESUJoinRequestDataNeed scopedDataNeed(String dataNeedId, String energyCommunityId) {
        if (!StringUtils.hasText(energyCommunityId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An energy community ID is required");
        }
        return dataNeedsService.findById(dataNeedId)
                .filter(CESUJoinRequestDataNeed.class::isInstance)
                .map(CESUJoinRequestDataNeed.class::cast)
                .filter(need -> configuration.resolveEnergyCommunityId(need.energyCommunityId()).filter(energyCommunityId::equals).isPresent())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scoped CESU data need not found"));
    }

    private static EnergyDirection energyDirection(String direction) {
        if (StandardDirectionTypeList.UP.value().equals(direction)) {
            return EnergyDirection.PRODUCTION;
        }
        if (StandardDirectionTypeList.DOWN.value().equals(direction)) {
            return EnergyDirection.CONSUMPTION;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CIM energy direction must be A01 or A02");
    }

    public record CesuJoinRequest(String connectionId, String dataNeedId, String permissionAdministratorId,
                                  EnergyCommunity energyCommunity) {
    }

    public record CesuPermissionScope(String connectionId, String dataNeedId, String energyCommunityId) {
    }

    public record CesuPermissionStatus(String permissionId, PermissionProcessStatus status,
                                       @Nullable LocalDate start, @Nullable LocalDate end, @Nullable String marketMessage) {
    }

    public record CesuCapabilities(String energyCommunityId, String marketPartyId,
                                   boolean buttonJoin, boolean manualJoin, boolean operatorTermination) {
    }
}
