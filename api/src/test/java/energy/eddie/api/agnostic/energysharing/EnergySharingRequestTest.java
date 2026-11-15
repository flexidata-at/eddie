// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.api.agnostic.energysharing;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EnergySharingRequestTest {
    private final JsonMapper mapper = new JsonMapper();

    @Test
    void discoveryMayOmitCommunityButMembershipCannot() {
        var parameters = mapper.createObjectNode();
        assertDoesNotThrow(() -> new EnergySharingRequest("req", "connection", "operator", "", "SYNC_MASTER_DATA", "1", parameters));
        assertThrows(IllegalArgumentException.class, () -> new EnergySharingRequest("req", "connection", "operator", "", "JOIN", "1", parameters));
    }

    @Test
    void rejectsUnscopedOrNonObjectRequests() {
        var parameters = mapper.createObjectNode();
        var invalidParameters = mapper.createArrayNode();
        assertThrows(IllegalArgumentException.class, () -> new EnergySharingRequest("req", "", "operator", "community", "JOIN", "1", parameters));
        assertThrows(IllegalArgumentException.class, () -> new EnergySharingRequest("req", "connection", "operator", "community", "JOIN", "1", invalidParameters));
    }

    @Test
    void snapshotDoesNotShareTheCallersMutableParameterTree() {
        var parameters = mapper.createObjectNode().put("allocationFactor", "25.50");
        var request = new EnergySharingRequest("req", "connection", "operator", "community", "JOIN", "1", parameters);
        parameters.put("allocationFactor", "99.00");
        assertEquals("25.50", request.parameters().path("allocationFactor").asString());
    }

    @Test
    void capabilityMetadataCopiesCallerCollectionsAndRejectsUnqualifiedCountries() {
        var actions = new HashSet<>(Set.of("JOIN"));
        var steps = new ArrayList<>(List.of("Verify the operator mandate"));
        var schema = mapper.createObjectNode().put("version", "pinned");
        var capabilities = new EnergySharingCapabilities("profile", "1", "operator", false, actions, schema, steps, "AT");
        actions.clear();
        steps.clear();
        schema.put("version", "changed");
        assertEquals(Set.of("JOIN"), capabilities.operations());
        assertEquals(List.of("Verify the operator mandate"), capabilities.setupSteps());
        assertEquals("pinned", capabilities.parameterSchemas().path("version").asString());
        assertThrows(IllegalArgumentException.class,
                () -> new EnergySharingCapabilities("profile", "1", "operator", false, actions, schema, steps, "at"));
    }

    @Test
    void operationAndRosterSnapshotsDetachCallerOwnedNationalData() {
        var nativeData = mapper.createObjectNode().put("nationalConsent", "original");
        var point = new EnergySharingMeteringPoint("opaqueMeter", null, null, null, null, null, null,
                "CONFIRMED", nativeData, "national-code");
        var points = new ArrayList<>(List.of(point));
        var snapshot = new EnergySharingCommunitySnapshot("community", "operator", "connection", "revision",
                Instant.EPOCH, true, null, null, points, nativeData);
        var operation = new EnergySharingOperation("req", "connection", "operator", "community", "JOIN", "ACKNOWLEDGED",
                Instant.EPOCH, Instant.EPOCH, null, null, null, nativeData);
        nativeData.put("nationalConsent", "modified");
        points.clear();
        assertEquals(1, snapshot.meteringPoints().size());
        assertEquals("original", snapshot.nativeParameters().path("nationalConsent").asString());
        assertEquals("original", point.nativeParameters().path("nationalConsent").asString());
        assertEquals("original", operation.result().path("nationalConsent").asString());
    }
}
