// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.api.agnostic.energysharing;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

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
        assertThrows(IllegalArgumentException.class, () -> new EnergySharingRequest("req", "", "operator", "community", "JOIN", "1", parameters));
        assertThrows(IllegalArgumentException.class, () -> new EnergySharingRequest("req", "connection", "operator", "community", "JOIN", "1", mapper.createArrayNode()));
    }

    @Test
    void snapshotDoesNotShareTheCallersMutableParameterTree() {
        var parameters = mapper.createObjectNode().put("allocationFactor", "25.50");
        var request = new EnergySharingRequest("req", "connection", "operator", "community", "JOIN", "1", parameters);
        parameters.put("allocationFactor", "99.00");
        assertEquals("25.50", request.parameters().path("allocationFactor").asString());
    }
}
