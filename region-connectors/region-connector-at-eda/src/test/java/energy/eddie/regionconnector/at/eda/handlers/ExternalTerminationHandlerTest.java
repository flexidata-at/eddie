// SPDX-FileCopyrightText: 2024-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.handlers;

import energy.eddie.api.agnostic.Granularity;
import energy.eddie.api.agnostic.data.needs.EnergyDirection;
import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.dataneeds.needs.CESUJoinRequestDataNeed;
import energy.eddie.dataneeds.needs.ValidatedHistoricalDataDataNeed;
import energy.eddie.dataneeds.services.DataNeedsService;
import energy.eddie.regionconnector.at.api.AtPermissionRequest;
import energy.eddie.regionconnector.at.api.AtPermissionRequestRepository;
import energy.eddie.regionconnector.at.eda.EdaAdapter;
import energy.eddie.regionconnector.at.eda.TransmissionException;
import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import energy.eddie.regionconnector.at.eda.permission.request.EdaPermissionRequestBuilder;
import energy.eddie.regionconnector.at.eda.permission.request.events.SimpleEvent;
import energy.eddie.regionconnector.at.eda.permission.request.events.TerminationEvent;
import energy.eddie.regionconnector.at.eda.requests.CCMORevoke;
import energy.eddie.regionconnector.at.eda.requests.EdaGroupingIdFactory;
import energy.eddie.regionconnector.at.eda.requests.restricted.enums.AllowedGranularity;
import energy.eddie.regionconnector.shared.event.sourcing.EventBus;
import energy.eddie.regionconnector.shared.event.sourcing.EventBusImpl;
import energy.eddie.regionconnector.shared.event.sourcing.Outbox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

import static energy.eddie.cim.agnostic.PermissionProcessStatus.FAILED_TO_TERMINATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExternalTerminationHandlerTest {
    private final EventBus eventBus = new EventBusImpl();
    private final AtConfiguration configuration = new AtConfiguration("epid", null, "DEV");
    private final EdaGroupingIdFactory groupingIdFactory = new EdaGroupingIdFactory(configuration);
    @Mock
    private AtPermissionRequestRepository repository;
    @Mock
    private Outbox outbox;
    @Mock
    private EdaAdapter edaAdapter;
    @Mock
    private DataNeedsService dataNeedsService;
    @Captor
    private ArgumentCaptor<SimpleEvent> simpleEventCaptor;

    @Test
    void terminatePermission_edaThrows_emitsFailedToTerminate() throws Exception {
        // given
        var start = LocalDate.now(ZoneOffset.UTC);
        var end = start.plusDays(10);
        doThrow(new TransmissionException(null)).when(edaAdapter).sendCMRevoke(any());
        var permissionRequest = new EdaPermissionRequestBuilder().setConnectionId("connectionId")
                                                                 .setPermissionId("pid")
                                                                 .setDataNeedId("dnid")
                                                                 .setCmRequestId("cmRequestId")
                                                                 .setConversationId("conversationId")
                                                                 .setMeteringPointId("mid")
                                                                 .setDsoId("dsoId")
                                                                 .setStart(start)
                                                                 .setEnd(end)
                                                                 .setGranularity(AllowedGranularity.PT15M)
                                                                 .setStatus(PermissionProcessStatus.ACCEPTED)
                                                                 .setMessage("")
                                                                 .setConsentId("consentId")
                                                                 .setCreated(ZonedDateTime.now(ZoneOffset.UTC))
                                                                 .setEnergyDirection(EnergyDirection.CONSUMPTION)
                                                                 .setParticipationFactor(1)
                                                                 .build();
        when(repository.findByPermissionId("pid")).thenReturn(Optional.of(permissionRequest));
        when(dataNeedsService.getById("dnid")).thenReturn(new ValidatedHistoricalDataDataNeed());
        new ExternalTerminationHandler(outbox, eventBus, repository, configuration, groupingIdFactory, edaAdapter, dataNeedsService);
        // when
        eventBus.emit(new TerminationEvent("pid", "convId"));

        // then
        verify(outbox).commit(simpleEventCaptor.capture());
        var res = simpleEventCaptor.getValue();
        assertEquals(FAILED_TO_TERMINATE, res.status());
    }

    @Test
    void terminatePermission_unknownPermissionRequest_emitsNothing() {
        // given
        when(repository.findByPermissionId("pid")).thenReturn(Optional.empty());
        new ExternalTerminationHandler(outbox, eventBus, repository, configuration, groupingIdFactory, edaAdapter, dataNeedsService);
        // when
        eventBus.emit(new TerminationEvent("pid", "convId"));

        // then
        verify(outbox, never()).commit(any());
    }

    @Test
    void terminatePermission_revokeIsSent() throws Exception {
        // given
        var start = LocalDate.now(ZoneOffset.UTC);
        var end = start.plusDays(10);
        var permissionRequest = new EdaPermissionRequestBuilder().setConnectionId("connectionId")
                                                                 .setPermissionId("pid")
                                                                 .setDataNeedId("dnid")
                                                                 .setCmRequestId("cmRequestId")
                                                                 .setConversationId("conversationId")
                                                                 .setMeteringPointId("mid")
                                                                 .setDsoId("dsoId")
                                                                 .setStart(start)
                                                                 .setEnd(end)
                                                                 .setGranularity(AllowedGranularity.PT15M)
                                                                 .setStatus(PermissionProcessStatus.ACCEPTED)
                                                                 .setMessage("")
                                                                 .setConsentId("consentId")
                                                                 .setCreated(ZonedDateTime.now(ZoneOffset.UTC))
                                                                 .setEnergyDirection(EnergyDirection.CONSUMPTION)
                                                                 .setParticipationFactor(1)
                                                                 .build();
        when(repository.findByPermissionId("pid")).thenReturn(Optional.of(permissionRequest));
        when(dataNeedsService.getById("dnid")).thenReturn(new ValidatedHistoricalDataDataNeed());
        new ExternalTerminationHandler(outbox, eventBus, repository, configuration, groupingIdFactory, edaAdapter, dataNeedsService);
        // when
        eventBus.emit(new TerminationEvent("pid", "convId"));

        // then
        var revokeCaptor = ArgumentCaptor.forClass(CCMORevoke.class);
        verify(edaAdapter).sendCMRevoke(revokeCaptor.capture());
        assertTrue(revokeCaptor.getValue().messageId().startsWith("DEVepidT"));
    }

    @Test
    void terminateCommunityPermission_usesCommunitySenderAndNewTerminationConversation() throws Exception {
        var config = new AtConfiguration("rcid", null, "DEV", Set.of("community-a"));
        var permission = communityPermission();
        when(repository.findByPermissionId("pid")).thenReturn(Optional.of(permission));
        when(dataNeedsService.getById("dnid")).thenReturn(communityNeed("community-a"));
        new ExternalTerminationHandler(outbox, eventBus, repository, config,
                                       new EdaGroupingIdFactory(config), edaAdapter, dataNeedsService);

        eventBus.emit(new TerminationEvent("pid", "terminationConversation"));

        var revokeCaptor = ArgumentCaptor.forClass(CCMORevoke.class);
        verify(edaAdapter).sendCMRevoke(revokeCaptor.capture());
        var revoke = revokeCaptor.getValue();
        assertEquals("rcid", revoke.eligiblePartyId());
        assertTrue(revoke.messageId().startsWith("DEVrcidT"));
        assertEquals("terminationConversation", revoke.conversationId());
        // A future membership must not receive a termination end before its start.
        assertEquals(permission.start(), revoke.consentEnd().toLocalDate());
        verifyNoInteractions(outbox);
    }

    @Test
    void terminateCommunityPermission_unconfiguredCommunityDoesNotSendRevoke() throws Exception {
        var config = new AtConfiguration("rcid", null, "DEV", Set.of("community-a"));
        when(repository.findByPermissionId("pid")).thenReturn(Optional.of(communityPermission()));
        when(dataNeedsService.getById("dnid")).thenReturn(communityNeed("community-b"));
        new ExternalTerminationHandler(outbox, eventBus, repository, config,
                                       new EdaGroupingIdFactory(config), edaAdapter, dataNeedsService);

        eventBus.emit(new TerminationEvent("pid", "terminationConversation"));

        verifyNoInteractions(edaAdapter);
        verify(outbox).commit(simpleEventCaptor.capture());
        assertEquals(FAILED_TO_TERMINATE, simpleEventCaptor.getValue().status());
    }

    private CESUJoinRequestDataNeed communityNeed(String communityId) {
        return new CESUJoinRequestDataNeed(100, Granularity.PT15M, Granularity.PT15M,
                                           EnergyDirection.CONSUMPTION, communityId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void terminateCommunityPermission_missingConsentOrPointDoesNotSendRevoke(boolean missingConsent) throws Exception {
        var config = new AtConfiguration("rcid", null, "DEV", Set.of("community-a"));
        var permission = mock(AtPermissionRequest.class);
        when(repository.findByPermissionId("pid")).thenReturn(Optional.of(permission));
        when(permission.dataNeedId()).thenReturn("dnid");
        when(dataNeedsService.getById("dnid")).thenReturn(communityNeed("community-a"));
        when(permission.consentId()).thenReturn(missingConsent ? Optional.empty() : Optional.of("consentId"));
        if (!missingConsent) {
            when(permission.meteringPointId()).thenReturn(Optional.empty());
        }
        new ExternalTerminationHandler(outbox, eventBus, repository, config,
                                       new EdaGroupingIdFactory(config), edaAdapter, dataNeedsService);

        eventBus.emit(new TerminationEvent("pid", "terminationConversation"));

        verifyNoInteractions(edaAdapter);
        verify(outbox).commit(simpleEventCaptor.capture());
        assertEquals(FAILED_TO_TERMINATE, simpleEventCaptor.getValue().status());
    }

    private AtPermissionRequest communityPermission() {
        var start = LocalDate.now(ZoneOffset.UTC).plusDays(10);
        return new EdaPermissionRequestBuilder().setConnectionId("connectionId")
                .setPermissionId("pid").setDataNeedId("dnid").setCmRequestId("cmRequestId")
                .setConversationId("joinConversation").setMeteringPointId("mid").setDsoId("dsoId")
                .setStart(start).setEnd(start.plusDays(10)).setGranularity(AllowedGranularity.PT15M)
                .setStatus(PermissionProcessStatus.ACCEPTED).setMessage("").setConsentId("consentId")
                .setCreated(ZonedDateTime.now(ZoneOffset.UTC)).setEnergyDirection(EnergyDirection.CONSUMPTION)
                .setParticipationFactor(100).build();
    }
}
