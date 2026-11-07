// SPDX-FileCopyrightText: 2024-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.services;

import energy.eddie.api.agnostic.Granularity;
import energy.eddie.api.agnostic.data.needs.EnergyDirection;
import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.dataneeds.needs.CESUJoinRequestDataNeed;
import energy.eddie.dataneeds.needs.ValidatedHistoricalDataDataNeed;
import energy.eddie.dataneeds.services.DataNeedsService;
import energy.eddie.regionconnector.at.api.AtPermissionRequestProjection;
import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import energy.eddie.regionconnector.at.api.AtPermissionRequestRepository;
import energy.eddie.regionconnector.at.eda.EdaAdapter;
import energy.eddie.regionconnector.at.eda.dto.EdaCMRevoke;
import energy.eddie.regionconnector.at.eda.dto.SimpleEdaCMRevoke;
import energy.eddie.regionconnector.at.eda.handlers.integration.inbound.AtPermissionRequestProjectionTest;
import energy.eddie.regionconnector.at.eda.permission.request.EdaPermissionRequest;
import energy.eddie.regionconnector.at.eda.permission.request.EdaPermissionRequestBuilder;
import energy.eddie.regionconnector.at.eda.permission.request.events.SimpleEvent;
import energy.eddie.regionconnector.at.eda.requests.restricted.enums.AllowedGranularity;
import energy.eddie.regionconnector.shared.event.sourcing.Outbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.publisher.TestPublisher;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static energy.eddie.regionconnector.at.eda.EdaRegionConnectorMetadata.AT_ZONE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RevocationServiceTest {
    @Mock
    private Outbox outbox;
    @Mock
    private DataNeedsService dataNeedsService;
    private final AtConfiguration configuration = new AtConfiguration("ep", "community-a", "");
    @Captor
    private ArgumentCaptor<SimpleEvent> captor;

    @Test
    void revokeWithConsentId_revokesPermissionRequest() {
        // Given
        var edaAdapter = mock(EdaAdapter.class);
        var permissionRequest = new EdaPermissionRequestBuilder()
                .setPermissionId("pid")
                .setStatus(PermissionProcessStatus.ACCEPTED)
                .build();
        TestPublisher<EdaCMRevoke> revocationStream = TestPublisher.create();
        when(edaAdapter.getCMRevokeStream()).thenReturn(revocationStream.flux());
        var repository = mock(AtPermissionRequestRepository.class);
        when(repository.findByConsentId("consentId")).thenReturn(Optional.of(projection(permissionRequest)));
        EdaCMRevoke cmRevoke = revocation("dnid", "ep", "mpid", "consentId");
        when(dataNeedsService.findById(permissionRequest.dataNeedId())).thenReturn(Optional.of(new ValidatedHistoricalDataDataNeed()));
        new RevocationService(edaAdapter, repository, outbox, dataNeedsService, configuration);

        // When
        revocationStream.emit(cmRevoke);

        // Then
        verify(outbox).commit(captor.capture());
        assertEquals(PermissionProcessStatus.REVOKED, captor.getValue().status());
    }

    @Test
    void revokeWithoutConsentId_revokesPermissionRequest() {
        // Given
        var edaAdapter = mock(EdaAdapter.class);
        LocalDate now = LocalDate.now(AT_ZONE_ID);
        var permissionRequest = new EdaPermissionRequestBuilder()
                .setPermissionId("pid")
                .setStatus(PermissionProcessStatus.ACCEPTED)
                .build();
        TestPublisher<EdaCMRevoke> revocationStream = TestPublisher.create();
        when(edaAdapter.getCMRevokeStream()).thenReturn(revocationStream.flux());
        var repository = mock(AtPermissionRequestRepository.class);
        when(repository.findByConsentId("consentId")).thenReturn(Optional.empty());

        when(repository.findAcceptedAndFulfilledByMeteringPointIdAndDate(anyString(), any()))
                .thenReturn(List.of(projection(permissionRequest)));
        EdaCMRevoke cmRevoke = new SimpleEdaCMRevoke()
                .setConsentEnd(now)
                .setMeteringPoint("mpid")
                .setConsentId("consentId");
        when(dataNeedsService.findById(permissionRequest.dataNeedId())).thenReturn(Optional.of(new ValidatedHistoricalDataDataNeed()));
        new RevocationService(edaAdapter, repository, outbox, dataNeedsService, configuration);

        // When
        revocationStream.emit(cmRevoke);

        // Then
        verify(outbox).commit(captor.capture());
        assertEquals(PermissionProcessStatus.REVOKED, captor.getValue().status());
    }

    @Test
    void revokeWithConsentId_fulfilledPermissionRequest_doesNotRevoke() {
        // Given
        var edaAdapter = mock(EdaAdapter.class);
        var permissionRequest = new EdaPermissionRequestBuilder()
                .setPermissionId("pid")
                .setStatus(PermissionProcessStatus.FULFILLED)
                .build();
        TestPublisher<EdaCMRevoke> revocationStream = TestPublisher.create();
        when(edaAdapter.getCMRevokeStream()).thenReturn(revocationStream.flux());
        var repository = mock(AtPermissionRequestRepository.class);
        when(repository.findByConsentId("consentId")).thenReturn(Optional.of(projection(permissionRequest)));
        EdaCMRevoke cmRevoke = new SimpleEdaCMRevoke().setConsentId("consentId");
        when(dataNeedsService.findById(permissionRequest.dataNeedId())).thenReturn(Optional.of(new ValidatedHistoricalDataDataNeed()));
        new RevocationService(edaAdapter, repository, outbox, dataNeedsService, configuration);

        // When
        revocationStream.emit(cmRevoke);

        // Then
        verifyNoInteractions(outbox);
        verify(repository, never()).findAcceptedAndFulfilledByMeteringPointIdAndDate(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = PermissionProcessStatus.class, names = {"CREATED", "FULFILLED"})
    void revokeWithWrongPermissionState_doesNotRevokePermissionRequest(PermissionProcessStatus status) {
        // Given
        var edaAdapter = mock(EdaAdapter.class);
        LocalDate now = LocalDate.now(AT_ZONE_ID);
        var permissionRequest = new EdaPermissionRequestBuilder()
                .setPermissionId("pid")
                .setStatus(status)
                .build();
        TestPublisher<EdaCMRevoke> revocationStream = TestPublisher.create();
        when(edaAdapter.getCMRevokeStream()).thenReturn(revocationStream.flux());
        var repository = mock(AtPermissionRequestRepository.class);
        when(repository.findByConsentId("consentId")).thenReturn(Optional.empty());

        when(repository.findAcceptedAndFulfilledByMeteringPointIdAndDate(anyString(), any()))
                .thenReturn(List.of(projection(permissionRequest)));
        EdaCMRevoke cmRevoke = new SimpleEdaCMRevoke()
                .setConsentEnd(now)
                .setMeteringPoint("mpid")
                .setConsentId("consentId");
        when(dataNeedsService.findById(permissionRequest.dataNeedId())).thenReturn(Optional.of(new ValidatedHistoricalDataDataNeed()));
        new RevocationService(edaAdapter, repository, outbox, dataNeedsService, configuration);

        // When
        revocationStream.emit(cmRevoke);

        // Then
        verify(outbox, never()).commit(any());
    }

    @ParameterizedTest
    @EnumSource(value = PermissionProcessStatus.class, names = {"ACCEPTED", "FULFILLED"})
    void revokeCesuWithSharedMarketParty_revokesMatchingConsent(PermissionProcessStatus status) {
        var edaAdapter = mock(EdaAdapter.class);
        TestPublisher<EdaCMRevoke> revocationStream = TestPublisher.create();
        when(edaAdapter.getCMRevokeStream()).thenReturn(revocationStream.flux());
        var repository = mock(AtPermissionRequestRepository.class);
        when(repository.findByConsentId("cesu-consent")).thenReturn(Optional.of(cesuProjection(status)));
        when(dataNeedsService.findById("cesu-need")).thenReturn(Optional.of(cesuNeed()));
        new RevocationService(edaAdapter, repository, outbox, dataNeedsService, configuration);

        revocationStream.emit(revocation("AT999999", "ep", "cesu-meter", "cesu-consent"));

        verify(outbox).commit(captor.capture());
        assertEquals("cesu-permission", captor.getValue().permissionId());
        assertEquals(PermissionProcessStatus.REVOKED, captor.getValue().status());
        verify(repository, never()).findAcceptedAndFulfilledByMeteringPointIdAndDate(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"other-dso,ep,cesu-meter", "AT999999,other-party,cesu-meter", "AT999999,ep,other-meter"})
    void revokeCesuWithMismatchedScope_doesNotRevoke(String sender, String receiver, String meter) {
        var edaAdapter = mock(EdaAdapter.class);
        TestPublisher<EdaCMRevoke> revocationStream = TestPublisher.create();
        when(edaAdapter.getCMRevokeStream()).thenReturn(revocationStream.flux());
        var repository = mock(AtPermissionRequestRepository.class);
        when(repository.findByConsentId("cesu-consent"))
                .thenReturn(Optional.of(cesuProjection(PermissionProcessStatus.ACCEPTED)));
        when(dataNeedsService.findById("cesu-need")).thenReturn(Optional.of(cesuNeed()));
        new RevocationService(edaAdapter, repository, outbox, dataNeedsService, configuration);

        revocationStream.emit(revocation(sender, receiver, meter, "cesu-consent"));

        verifyNoInteractions(outbox);
        verify(repository, never()).findAcceptedAndFulfilledByMeteringPointIdAndDate(any(), any());
    }

    @Test
    void revokeUnknownConsentWithSharedMarketParty_fallsBackOnlyForOrdinaryPermissions() {
        var edaAdapter = mock(EdaAdapter.class);
        TestPublisher<EdaCMRevoke> revocationStream = TestPublisher.create();
        when(edaAdapter.getCMRevokeStream()).thenReturn(revocationStream.flux());
        var repository = mock(AtPermissionRequestRepository.class);
        var ordinary = new EdaPermissionRequestBuilder()
                .setPermissionId("ordinary-permission")
                .setStatus(PermissionProcessStatus.ACCEPTED)
                .build();
        when(repository.findByConsentId("unknown")).thenReturn(Optional.empty());
        when(repository.findAcceptedAndFulfilledByMeteringPointIdAndDate("cesu-meter", LocalDate.now(AT_ZONE_ID)))
                .thenReturn(List.of(projection(ordinary), cesuProjection(PermissionProcessStatus.ACCEPTED)));
        when(dataNeedsService.findById(ordinary.dataNeedId())).thenReturn(Optional.of(new ValidatedHistoricalDataDataNeed()));
        when(dataNeedsService.findById("cesu-need")).thenReturn(Optional.of(cesuNeed()));
        new RevocationService(edaAdapter, repository, outbox, dataNeedsService, configuration);

        revocationStream.emit(revocation("AT999999", "ep", "cesu-meter", "unknown"));

        verify(outbox).commit(captor.capture());
        assertEquals("ordinary-permission", captor.getValue().permissionId());
        assertEquals(PermissionProcessStatus.REVOKED, captor.getValue().status());
    }

    private static CESUJoinRequestDataNeed cesuNeed() {
        return new CESUJoinRequestDataNeed(100, Granularity.PT15M, Granularity.PT15M,
                EnergyDirection.CONSUMPTION, "community-a");
    }

    private static AtPermissionRequestProjection cesuProjection(PermissionProcessStatus status) {
        return new AtPermissionRequestProjectionTest(
                "cesu-permission", "connection", "request", "conversation",
                LocalDate.now(AT_ZONE_ID), LocalDate.now(AT_ZONE_ID), "cesu-need", "AT999999",
                "cesu-meter", "cesu-consent", "", AllowedGranularity.PT15M.name(), status.name(),
                Instant.now(), EnergyDirection.CONSUMPTION, 100);
    }

    private static EdaCMRevoke revocation(String sender, String receiver, String meter, String consent) {
        return new EdaCMRevoke() {
            @Override public String meteringPoint() {return meter;}
            @Override public String consentId() {return consent;}
            @Override public LocalDate consentEnd() {return LocalDate.now(AT_ZONE_ID);}
            @Override public String senderMessageAddress() {return sender;}
            @Override public String receiverMessageAddress() {return receiver;}
        };
    }

    private static AtPermissionRequestProjection projection(EdaPermissionRequest epm) {
        return new AtPermissionRequestProjectionTest(
                epm.permissionId(),
                epm.connectionId(),
                epm.cmRequestId(),
                epm.conversationId(),
                LocalDate.now(ZoneId.systemDefault()),
                LocalDate.now(ZoneId.systemDefault()),
                epm.dataNeedId(),
                "dnid",
                null,
                null,
                epm.message(),
                AllowedGranularity.PT15M.name(),
                epm.status().name(),
                Instant.now(),
                EnergyDirection.CONSUMPTION, 100);
    }
}
