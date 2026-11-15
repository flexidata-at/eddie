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
        var request = request("1");
        assertEquals(EnergySharingException.Code.UNSUPPORTED,
            assertThrows(EnergySharingException.class, () -> router.submit("unknown", request)).code());
        verify(service, never()).submit(any());
    }

    @Test
    void rejectsStaleProfilesAndOfflineTransportBeforeSending() {
        var stale = request("0");
        var current = request("1");
        assertEquals(EnergySharingException.Code.CONFLICT,
            assertThrows(EnergySharingException.class, () -> router.submit("future-country", stale)).code());
        when(service.capabilities("operator", "connection")).thenReturn(capabilities("1", false));
        assertEquals(EnergySharingException.Code.NOT_READY,
            assertThrows(EnergySharingException.class, () -> router.submit("future-country", current)).code());
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
        var replacement = mock(RegionConnectorEnergySharingService.class);
        assertThrows(IllegalStateException.class, () -> router.register("future-country", replacement));
    }

    @Test
    void capabilityLookupRequiresBothScopesAndMatchingOperator() {
        assertEquals(EnergySharingException.Code.INVALID_REQUEST,
                assertThrows(EnergySharingException.class, () -> router.capabilities("future-country", "", "connection")).code());
        assertEquals(EnergySharingException.Code.INVALID_REQUEST,
                assertThrows(EnergySharingException.class, () -> router.capabilities("future-country", "operator", "")).code());
        var foreign = new EnergySharingCapabilities("profile", "1", "other", true, Set.of("JOIN"), mapper.createObjectNode(), List.of(), "ZZ");
        when(service.capabilities("operator", "connection")).thenReturn(foreign);
        assertEquals(EnergySharingException.Code.FORBIDDEN,
                assertThrows(EnergySharingException.class, () -> router.capabilities("future-country", "operator", "connection")).code());
    }

    @Test
    void scopedOperationLookupSeparatesMissingRequestsFromValidReceipts() {
        when(service.operation("req", "connection", "operator", "community")).thenReturn(Optional.empty());
        assertEquals(EnergySharingException.Code.NOT_FOUND,
                assertThrows(EnergySharingException.class, () -> router.operation("future-country", "req", "connection", "operator", "community")).code());
        var expected = operation("connection");
        when(service.operation("req", "connection", "operator", "community")).thenReturn(Optional.of(expected));
        assertEquals(expected, router.operation("future-country", "req", "connection", "operator", "community"));
    }

    @Test
    void communityLookupReturnsOnlyTheRequestedScopedSnapshot() {
        var snapshot = new EnergySharingCommunitySnapshot("community", "operator", "connection", "revision", Instant.EPOCH,
                true, null, null, List.of(), mapper.createObjectNode());
        when(service.communities("operator", "connection")).thenReturn(List.of(snapshot));
        assertEquals(snapshot, router.community("future-country", "community", "operator", "connection"));
        assertEquals(EnergySharingException.Code.NOT_FOUND,
                assertThrows(EnergySharingException.class, () -> router.community("future-country", "missing", "operator", "connection")).code());
        var foreign = new EnergySharingCommunitySnapshot("community", "operator", "other-connection", "revision", Instant.EPOCH,
                true, null, null, List.of(), mapper.createObjectNode());
        when(service.communities("operator", "connection")).thenReturn(List.of(foreign));
        assertEquals(EnergySharingException.Code.FORBIDDEN,
                assertThrows(EnergySharingException.class, () -> router.community("future-country", "community", "operator", "connection")).code());
    }

    @Test
    void submittedReceiptCannotChangeTheRequestIdentityOrCommunity() {
        var request = request("1");
        for (var invalid : List.of(
                new EnergySharingOperation("other", "connection", "operator", "community", "JOIN", "SUBMITTED", Instant.EPOCH, Instant.EPOCH, null, null, null, mapper.createObjectNode()),
                new EnergySharingOperation("req", "connection", "other", "community", "JOIN", "SUBMITTED", Instant.EPOCH, Instant.EPOCH, null, null, null, mapper.createObjectNode()),
                new EnergySharingOperation("req", "connection", "operator", "other", "JOIN", "SUBMITTED", Instant.EPOCH, Instant.EPOCH, null, null, null, mapper.createObjectNode()))) {
            when(service.submit(request)).thenReturn(invalid);
            assertEquals(EnergySharingException.Code.FORBIDDEN,
                    assertThrows(EnergySharingException.class, () -> router.submit("future-country", request)).code());
        }
    }

    @Test
    void repeatedRegistrationOfTheSameServiceIsSafeAndConnectorNamesAreSorted() {
        router.register("future-country", service);
        router.register("another-country", service);
        assertEquals(List.of("another-country", "future-country"), router.regionConnectors());
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
