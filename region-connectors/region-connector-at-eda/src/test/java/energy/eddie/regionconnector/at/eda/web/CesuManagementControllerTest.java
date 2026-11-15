// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.web;

import energy.eddie.api.agnostic.data.needs.EnergyDirection;
import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.dataneeds.needs.DataNeed;
import energy.eddie.dataneeds.services.DataNeedsService;
import energy.eddie.regionconnector.at.api.AtPermissionRequest;
import energy.eddie.regionconnector.at.api.AtPermissionRequestRepository;
import energy.eddie.regionconnector.at.eda.EdaRegionConnector;
import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import energy.eddie.regionconnector.at.eda.permission.request.dtos.CreatedPermissionRequest;
import energy.eddie.regionconnector.at.eda.permission.request.dtos.PermissionRequestForCreation;
import energy.eddie.regionconnector.at.eda.services.PermissionRequestCreationAndValidationService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CesuManagementControllerTest {
    private static final String ACCOUNTING_POINT = "AT9999990699900000000000206868100";
    private static final String JOIN_JSON = """
            {"connectionId":"connection", "dataNeedId":"need", "permissionAdministratorId":"AT999999",
             "energyCommunity":{"mRID":"community-a", "AccountingPoint":[
               {"mRID":{"value":"%s", "codingScheme":"NAT"},
                "energySharingParticipationFactor":25,"energySharingEnergyDirection":"A02"}]}}
            """.formatted(ACCOUNTING_POINT);
    private static final String NEED_JSON = """
            {"type":"cesu-join-request","energyCommunityId":"community-a", "minGranularity":"PT15M", "maxGranularity":"PT15M",
             "regionConnectorFilter":{"type":"allowlist","regionConnectorIds":["at-eda"]}}
            """;

    private final AtConfiguration configuration = new AtConfiguration("rc", null, "", Set.of("community-a", "community-b"));
    private final JsonMapper mapper = JsonMapper.builder().build();
    @Mock
    private DataNeedsService dataNeedsService;
    @Mock
    private PermissionRequestCreationAndValidationService creationService;
    @Mock
    private AtPermissionRequestRepository repository;
    @Mock
    private EdaRegionConnector regionConnector;
    @Mock
    private Validator validator;
    @Mock
    private AtPermissionRequest permission;

    @Test
    void acceptsCimAccountingPointAndUsesExistingRegionalConsentWorkflow() throws Exception {
        when(dataNeedsService.findById("need")).thenReturn(Optional.of(mapper.readValue(NEED_JSON, DataNeed.class)));
        var nativeRequest = new PermissionRequestForCreation("connection", ACCOUNTING_POINT,
                List.of("need"), "AT999999", EnergyDirection.CONSUMPTION, 25);
        when(validator.validate(nativeRequest)).thenReturn(Set.of());
        when(creationService.createAndValidatePermissionRequest(nativeRequest))
                .thenReturn(new CreatedPermissionRequest(List.of("permission")));

        var result = controller().requestJoin(mapper.readTree(JOIN_JSON));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(new CreatedPermissionRequest(List.of("permission")));
    }

    @Test
    void rejectsJoinWhenCommunityDoesNotMatchScopedDataNeed() {
        when(dataNeedsService.findById("need")).thenReturn(Optional.of(mapper.readValue(NEED_JSON, DataNeed.class)));

        assertThatThrownBy(() -> controller().requestJoin(mapper.readTree(JOIN_JSON.replace("community-a", "community-b"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(creationService);
    }

    @Test
    void rejectsFractionalParticipationFactors() {
        assertThatThrownBy(() -> controller().requestJoin(mapper.readTree(JOIN_JSON.replace(":25,", ":25.5,"))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(creationService);
    }

    @Test
    void requiresTheActualConnectionToTerminatePermission() {
        when(dataNeedsService.findById("need")).thenReturn(Optional.of(mapper.readValue(NEED_JSON, DataNeed.class)));
        when(repository.findByPermissionId("permission")).thenReturn(Optional.of(permission));
        when(permission.dataNeedId()).thenReturn("need");
        when(permission.connectionId()).thenReturn("connection");

        var wrongConnection = new CesuManagementController.CesuPermissionScope("other", "need", "community-a");
        assertThatThrownBy(() -> controller().requestTermination("permission", wrongConnection))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(regionConnector);

        when(permission.status()).thenReturn(PermissionProcessStatus.ACCEPTED);
        when(permission.consentId()).thenReturn(Optional.of("market-consent"));
        when(permission.meteringPointId()).thenReturn(Optional.of(ACCOUNTING_POINT));
        var scope = new CesuManagementController.CesuPermissionScope("connection", "need", "community-a");
        assertThat(controller().requestTermination("permission", scope).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(regionConnector).terminatePermission("permission");
    }

    private CesuManagementController controller() {
        return new CesuManagementController(dataNeedsService, configuration,
                creationService, repository, regionConnector, validator);
    }

    @Test
    void statusIsAvailableOnlyForTheMatchingScope() {
        when(dataNeedsService.findById("need")).thenReturn(Optional.of(mapper.readValue(NEED_JSON, DataNeed.class)));
        when(repository.findByPermissionId("permission")).thenReturn(Optional.of(permission));
        when(permission.dataNeedId()).thenReturn("need");
        when(permission.connectionId()).thenReturn("connection");
        when(permission.permissionId()).thenReturn("permission");
        when(permission.status()).thenReturn(PermissionProcessStatus.EXTERNALLY_TERMINATED);

        assertThat(controller().permissionStatus("permission", "connection", "need", "community-a").status())
                .isEqualTo(PermissionProcessStatus.EXTERNALLY_TERMINATED);
        assertThatThrownBy(() -> controller().permissionStatus("permission", "other", "need", "community-a"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
