// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.core.services.agnostic;

import energy.eddie.api.agnostic.energysharing.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EnergySharingRouterTest {
    private final EnergySharingRouter router = new EnergySharingRouter();
    private final RegionConnectorEnergySharingService service = mock(RegionConnectorEnergySharingService.class);
    private final JsonMapper mapper = new JsonMapper();

    @BeforeEach
    void setup() {
        router.register("future-country", service);
        when(service.capabilities("operator", "connection")).thenReturn(capabilities("1", true));
    }

    @Test
    void routesWithoutAnyAustrianAssumptionsAndPreservesAsyncState() {
        var request = request("1");
        var operation = operation("connection");
        when(service.submit(request)).thenReturn(operation);
        assertEquals("SUBMITTED", router.submit("future-country", request).status());
        assertEquals(List.of("future-country"), router.regionConnectors());
    }

    @Test
    void rejectsAnUnsupportedConnectorWithoutUsingADefaultCountry() {
        assertEquals(EnergySharingException.Code.UNSUPPORTED,
            assertThrows(EnergySharingException.class, () -> router.submit("unknown", request("1"))).code());
        verify(service, never()).submit(any());
    }

    @Test
    void rejectsStaleProfilesAndOfflineTransportBeforeSending() {
        assertEquals(EnergySharingException.Code.CONFLICT,
            assertThrows(EnergySharingException.class, () -> router.submit("future-country", request("0"))).code());
        when(service.capabilities("operator", "connection")).thenReturn(capabilities("1", false));
        assertEquals(EnergySharingException.Code.NOT_READY,
            assertThrows(EnergySharingException.class, () -> router.submit("future-country", request("1"))).code());
        verify(service, never()).submit(any());
    }

    @Test
    void refusesUnadvertisedActions() {
        var request = new EnergySharingRequest("req", "connection", "operator", "community", "SETTLE_TRADE", "1", mapper.createObjectNode());
        assertEquals(EnergySharingException.Code.UNSUPPORTED,
            assertThrows(EnergySharingException.class, () -> router.submit("future-country", request)).code());
        verify(service, never()).submit(any());
    }

    @Test
    void doesNotExposeAnotherConnectionOperation() {
        when(service.operation("req", "connection", "operator", "community")).thenReturn(Optional.of(operation("other")));
        assertEquals(EnergySharingException.Code.FORBIDDEN,
            assertThrows(EnergySharingException.class, () -> router.operation("future-country", "req", "connection", "operator", "community")).code());
    }

    @Test
    void doesNotExposeAnotherOperatorSnapshot() {
        when(service.communities("operator", "connection")).thenReturn(List.of(new EnergySharingCommunitySnapshot(
            "community", "another-operator", "connection", "revision", Instant.EPOCH, true, null, null, List.of(), mapper.createObjectNode())));
        assertEquals(EnergySharingException.Code.FORBIDDEN,
            assertThrows(EnergySharingException.class, () -> router.communities("future-country", "operator", "connection")).code());
    }

    @Test
    void failsRatherThanReplacingAnAlreadyRegisteredConnector() {
        assertThrows(IllegalStateException.class, () -> router.register("future-country", mock(RegionConnectorEnergySharingService.class)));
    }

    private EnergySharingCapabilities capabilities(String version, boolean ready) {
        return new EnergySharingCapabilities("country-profile", version, "operator", ready, Set.of("JOIN"), mapper.createObjectNode(), List.of(), "ZZ");
    }
    private EnergySharingRequest request(String version) {
        return new EnergySharingRequest("req", "connection", "operator", "community", "JOIN", version, mapper.createObjectNode());
    }
    private EnergySharingOperation operation(String connectionId) {
        return new EnergySharingOperation("req", connectionId, "operator", "community", "JOIN", "SUBMITTED", Instant.EPOCH, Instant.EPOCH, "msg", null, null, mapper.createObjectNode());
    }
}
