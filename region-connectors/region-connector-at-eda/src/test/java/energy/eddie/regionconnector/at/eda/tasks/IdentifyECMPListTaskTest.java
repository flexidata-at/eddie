// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.tasks;

import energy.eddie.api.agnostic.Granularity;
import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.dataneeds.needs.CESUJoinRequestDataNeed;
import energy.eddie.dataneeds.services.DataNeedsService;
import energy.eddie.regionconnector.at.api.AtPermissionRequestProjection;
import energy.eddie.regionconnector.at.api.AtPermissionRequestRepository;
import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import energy.eddie.regionconnector.at.eda.dto.energycommunity.EdaECMPList;
import energy.eddie.regionconnector.at.eda.dto.energycommunity.EnergyCommunityMeteringPointData;
import energy.eddie.regionconnector.at.eda.ponton.messages.MarshallerConfig;
import energy.eddie.regionconnector.at.eda.ponton.messages.ecmplist._01p20.EdaECMPList01p20InboundMessageFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, SpringExtension.class})
@ContextConfiguration(classes = MarshallerConfig.class)
class IdentifyECMPListTaskTest {
    @Autowired
    private Jaxb2Marshaller marshaller;

    @Mock
    private AtPermissionRequestRepository repository;
    @Mock
    private AtPermissionRequestProjection projection;
    @Mock
    private DataNeedsService dataNeedsService;
    private final AtConfiguration configuration = new AtConfiguration("ec-party", "ecId", "");

    @Test
    void givenNoPermissionRequest_whenIdentifyingECMPList_thenReturnOptionalEmpty() {
        // Given
        when(repository.findByConversationIdOrCMRequestId("conversationId", null))
                .thenReturn(List.of());
        var ecmpList = mock(EdaECMPList.class);
        when(ecmpList.receiverMessageAddress()).thenReturn("ec-party");
        when(ecmpList.conversationId()).thenReturn("conversationId");
        var task = new IdentifyECMPListTask(repository, dataNeedsService, configuration);

        // When
        var res = task.identify(ecmpList);

        // Then
        assertThat(res).isEmpty();
    }

    @Test
    void givenPermissionRequest_whenIdentifyingECMPList_thenReturnIdentifiedECMPList() {
        // Given
        when(projection.getStatus()).thenReturn(PermissionProcessStatus.ACCEPTED.name());
        when(projection.getCreated()).thenReturn(Instant.now(Clock.systemUTC()));
        when(projection.getDsoId()).thenReturn("dso");
        when(projection.getMeteringPointId()).thenReturn("metering-point");
        when(projection.getDataNeedId()).thenReturn("need");
        when(dataNeedsService.findById("need")).thenReturn(Optional.of(
                new CESUJoinRequestDataNeed(null, Granularity.PT15M, Granularity.PT15M, null)));
        when(repository.findByConversationIdOrCMRequestId("conversationId", null))
                .thenReturn(List.of(projection));
        var ecmpList = mock(EdaECMPList.class);
        when(ecmpList.receiverMessageAddress()).thenReturn("ec-party");
        when(ecmpList.conversationId()).thenReturn("conversationId");
        var meter = mock(EnergyCommunityMeteringPointData.class);
        when(meter.meteringPoint()).thenReturn("metering-point");
        when(ecmpList.mpListData()).thenReturn(List.of(meter));
        when(ecmpList.senderMessageAddress()).thenReturn("dso");
        when(ecmpList.ecId()).thenReturn("ecId");
        var task = new IdentifyECMPListTask(repository, dataNeedsService, configuration);

        assertThat(task.identify(ecmpList)).isPresent();
        when(ecmpList.ecId()).thenReturn("anotherCommunity");
        assertThat(task.identify(ecmpList)).isEmpty();
    }

    @Test
    void givenCurrentSchemaFixture_whenIdentifying_thenEnforceCommunityAndSingleMeteringPointScope() throws IOException {
        var communityId = "AT00100000000RC100123000000123456";
        var scopedConfiguration = new AtConfiguration("RC100123", null, "", Set.of(communityId));
        var factory = new EdaECMPList01p20InboundMessageFactory(marshaller);
        try (var input = getClass().getResourceAsStream("/xsd/ecmplist/_01p20/ecmplist.xml")) {
            assert input != null;
            var ecmpList = factory.parseInputStream(input);
            var process = ecmpList.getOriginal().getProcessDirectory();
            var points = process.getMPListData();
            assertThat(points).hasSize(4);
            var unrelatedPoint = points.get(1);
            points.subList(1, points.size()).clear();

            when(projection.getStatus()).thenReturn(PermissionProcessStatus.ACCEPTED.name());
            when(projection.getCreated()).thenReturn(Instant.parse("2026-11-07T00:00:00Z"));
            when(projection.getPermissionId()).thenReturn("scoped-permission");
            when(projection.getDsoId()).thenReturn("AT001000");
            when(projection.getMeteringPointId()).thenReturn("AT0010000103600000000123456123456");
            when(projection.getDataNeedId()).thenReturn("scoped-need");
            when(dataNeedsService.findById("scoped-need")).thenReturn(Optional.of(
                    new CESUJoinRequestDataNeed(null, Granularity.PT15M, Granularity.PT15M, null, communityId)));
            when(repository.findByConversationIdOrCMRequestId("0ASDF", null)).thenReturn(List.of(projection));
            var task = new IdentifyECMPListTask(repository, dataNeedsService, scopedConfiguration);

            var result = task.identify(ecmpList);
            assertThat(result).as("current schema with one matching accounting point").isPresent();
            assertThat(result.orElseThrow().ecmpList()).isSameAs(ecmpList);
            assertThat(result.orElseThrow().permissionRequest().permissionId()).isEqualTo("scoped-permission");

            process.setECID("AT00100000000RC100123000000999999");
            assertThat(task.identify(ecmpList)).as("different community").isEmpty();

            process.setECID(communityId);
            points.add(unrelatedPoint);
            assertThat(task.identify(ecmpList)).as("response also contains an unrelated accounting point").isEmpty();
        }
    }
}
