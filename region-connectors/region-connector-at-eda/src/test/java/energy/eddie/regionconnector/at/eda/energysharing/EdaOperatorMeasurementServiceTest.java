// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import static energy.eddie.regionconnector.at.eda.energysharing.OperatorMeasurementFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EdaOperatorMeasurementServiceTest {
    private final EdaOperatorMeasurementStore store = mock(EdaOperatorMeasurementStore.class);
    private final EdaOperatorMeasurementService service = new EdaOperatorMeasurementService(
            new EdaEnergySharingConfiguration(List.of(BINDING)), store, MAPPER);

    @Test void operatorIngestionRequiresNoCustomerPermissionAndPassesExactWireToDurableStore() throws Exception {
        doAnswer(invocation -> {
            Function<String, JsonNode> authorize = invocation.getArgument(5);
            var proof = authorize.apply(roster());
            assertEquals(COMMUNITY, proof.path("communityId").asText());
            assertEquals("revision", proof.path("snapshotRevision").asText());
            assertFalse(proof.path("timePeriods").isEmpty());
            return null;
        }).when(store).append(anyString(), anyString(), anyString(), any(), any(), any());
        var consumptionRecord = consumptionRecord(xml("M1"));
        assertTrue(service.ingest(consumptionRecord));
        verify(store).append(anyString(), anyString(), anyString(), argThat(doc -> doc.meteringPointId().equals(METER)),
                argThat(source -> java.util.Arrays.equals(source, bytes(xml("M1")))), any());
    }

    @Test void authorityProofUsesTheSameQualifiedPointThatPassedAuthorization() throws Exception {
        var source = MAPPER.readTree(roster());
        var points = (tools.jackson.databind.node.ArrayNode) source.path("snapshot").path("meteringPoints");
        var unrelated = (tools.jackson.databind.node.ObjectNode) points.get(0).deepCopy();
        unrelated.put("codingScheme", "FOREIGN");
        ((tools.jackson.databind.node.ObjectNode) unrelated.path("nativeParameters").path("timePeriods").get(0)).put("activate", "2024-04-01");
        points.insert(0, unrelated);
        doAnswer(invocation -> {
            Function<String, JsonNode> authorize = invocation.getArgument(5);
            var proof = authorize.apply(source.toString());
            assertEquals("NAT", proof.path("codingScheme").asText());
            assertEquals("2024-03-01", proof.path("timePeriods").get(0).path("activate").asText());
            return null;
        }).when(store).append(anyString(), anyString(), anyString(), any(), any(), any());
        assertTrue(service.ingest(consumptionRecord(xml("M1"))));
    }

    @Test void duplicateQualifiedRosterIdentityIsAmbiguousAndCannotAuthorize() {
        var source = MAPPER.readTree(roster());
        var points = (tools.jackson.databind.node.ArrayNode) source.path("snapshot").path("meteringPoints");
        points.add(points.get(0).deepCopy());
        assertFalse(service.authorized(source.toString(), BINDING, COMMUNITY, document("M1")));
    }

    @Test void missingAuthoritativeRosterRequestsTransportRetryWithoutInventingMembership() throws Exception {
        doThrow(new EnergySharingException(EnergySharingException.Code.NOT_READY, "Roster missing"))
                .when(store).append(anyString(), anyString(), anyString(), any(), any(), any());
        var consumptionRecord = consumptionRecord(xml("M1"));
        assertThrows(IllegalStateException.class, () -> service.ingest(consumptionRecord));
    }

    @Test void unconfiguredReceiverAndNonCommunityDocumentsFallThroughToLegacyPath() throws Exception {
        assertFalse(service.ingest(consumptionRecord(xml("M1").replace("CC000001", "CC000002"))));
        assertFalse(service.ingest(consumptionRecord(xml("M1").replace("<cp:ECID>" + COMMUNITY + "</cp:ECID>", ""))));
        verifyNoInteractions(store);
    }

    @Test void wrongDsoAndMalformedOwnedSchemaCannotPersist() throws Exception {
        var foreign = consumptionRecord(xml("M1").replace("AT000000</ct:MessageAddress>", "AT000009</ct:MessageAddress>"));
        assertThrows(EnergySharingException.class, () -> service.ingest(foreign));
        var incomplete = consumptionRecord(xml("M1").replace("<cp:MM>L1</cp:MM>", ""));
        assertThrows(EnergySharingException.class, () -> service.ingest(incomplete));
        verifyNoInteractions(store);
    }

    @Test void exactRosterScopePointAndPeriodAreAllRequired() {
        var document = document("M1");
        assertTrue(service.authorized(roster(), BINDING, COMMUNITY, document));
        for (String invalid : List.of(roster().replace("\"connection\"", "\"another-connection\""),
                roster().replace(METER, METER.toLowerCase(java.util.Locale.ROOT)), roster().replace("\"complete\":true", "\"complete\":false"),
                roster().replace("2024-03-01", "2024-04-01"), roster().replace("2024-04-30", "2024-03-29"))) {
            assertFalse(service.authorized(invalid, BINDING, COMMUNITY, document));
        }
        String ended = roster().replace("\"activate\":\"2024-03-01\"", "\"activate\":\"2024-03-01\",\"deactivate\":\"2024-03-29\"");
        assertFalse(service.authorized(ended, BINDING, COMMUNITY, document));
    }

    @Test void pageAndSourceRequireCurrentBindingButDoNotDependOnCurrentRollingRoster() {
        var document = document("M1");
        when(store.page(anyString(), eq(2L), eq(10))).thenReturn(new EdaOperatorMeasurementStore.Page(3, false, List.of(document)));
        var page = service.measurements("CC000001", "connection", COMMUNITY, 2, 10);
        assertEquals("AT", page.countryCode());
        assertEquals(COMMUNITY, page.communityId());
        assertEquals(3, page.nextSequence());
        String id = "a".repeat(64);
        when(store.source(anyString(), eq(id))).thenReturn(bytes(xml("M1")));
        assertArrayEquals(bytes(xml("M1")), service.source("CC000001", "connection", COMMUNITY, id));
        assertThrows(EnergySharingException.class, () -> service.source("CC000001", "connection", COMMUNITY, "../foreign"));
    }

    @Test void readCallsRejectOtherConnectionCommunityOperatorAndUnconfiguredPartnerBeforeStore() {
        assertThrows(EnergySharingException.class, () -> service.measurements("CC000001", "foreign", COMMUNITY, 0, 20));
        assertThrows(EnergySharingException.class, () -> service.measurements("CC000002", "connection", COMMUNITY, 0, 20));
        assertThrows(EnergySharingException.class, () -> service.measurements("CC000001", "connection", "foreign", 0, 20));
        assertThrows(EnergySharingException.class, () -> service.measurements("CC000001", "connection", COMMUNITY, -1, 20));
        assertThrows(EnergySharingException.class, () -> service.measurements("CC000001", "connection", COMMUNITY, 0, 101));
        var offline = new EdaOperatorMeasurementService(new EdaEnergySharingConfiguration(List.of(
                new EdaEnergySharingConfiguration.Binding("connection", "CC000001", Map.of(COMMUNITY, "AT000000"), false))), store, MAPPER);
        assertThrows(EnergySharingException.class, () -> offline.source("CC000001", "connection", COMMUNITY, "id"));
        verifyNoInteractions(store);
    }
}
