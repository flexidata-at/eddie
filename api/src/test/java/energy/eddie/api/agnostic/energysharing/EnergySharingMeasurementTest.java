// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.api.agnostic.energysharing;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class EnergySharingMeasurementTest {
    @Test void measurementDocumentsAndPagesAreImmutableSnapshots() {
        var nativeData = new JsonMapper().createObjectNode().put("MM", "L1");
        var reading = new EnergySharingMeasurement(Instant.EPOCH, Instant.EPOCH.plusSeconds(900), BigDecimal.ONE,
                "KWH", "native-series", "native-series", "L1", "QH", nativeData);
        var values = new ArrayList<>(List.of(reading));
        var document = new EnergySharingMeasurementDocument(1, "doc", "msg", "conv", "1", Instant.EPOCH, Instant.EPOCH,
                "point", "scheme", "sender", "operator", "sha", values, nativeData);
        var documents = new ArrayList<>(List.of(document));
        var page = new EnergySharingMeasurementPage("XX", "connection", "operator", "community", 0, 1, false, documents);
        values.clear();
        documents.clear();
        nativeData.put("MM", "L3");
        ((tools.jackson.databind.node.ObjectNode) reading.nativeParameters()).put("MM", "mutated");
        ((tools.jackson.databind.node.ObjectNode) document.nativeParameters()).put("MM", "mutated");
        assertEquals(1, page.documents().size());
        assertEquals(1, document.readings().size());
        assertEquals("L1", reading.nativeParameters().path("MM").asText());
        assertEquals("L1", document.nativeParameters().path("MM").asText());
        var pageDocuments = page.documents();
        assertThrows(UnsupportedOperationException.class, pageDocuments::clear);
    }

    @Test void existingNationalImplementationsDefaultToUnsupportedWithoutImplementingAnAtFeed() {
        RegionConnectorEnergySharingService unsupported = new RegionConnectorEnergySharingService() {
            @Override public EnergySharingCapabilities capabilities(String operatorId, String connectionId) { throw new UnsupportedOperationException(); }
            @Override public EnergySharingOperation submit(EnergySharingRequest request) { throw new UnsupportedOperationException(); }
            @Override public Optional<EnergySharingOperation> operation(String requestId, String connectionId, String operatorId, String communityId) { return Optional.empty(); }
            @Override public List<EnergySharingCommunitySnapshot> communities(String operatorId, String connectionId) { return List.of(); }
        };
        var exception = assertThrows(EnergySharingException.class, () -> unsupported.measurements("operator", "connection", "community", 0, 20));
        assertEquals(EnergySharingException.Code.UNSUPPORTED, exception.code());
        assertThrows(EnergySharingException.class, () -> unsupported.measurementSource("operator", "connection", "community", "doc"));
        var request = new EnergySharingMeasurementImport(new byte[]{1}, "archive:1", Instant.EPOCH, "a".repeat(64), true);
        assertThrows(EnergySharingException.class, () -> unsupported.importMeasurement("operator", "connection", "community", request));
    }
    @Test void archivedNativeBytesAreDefensiveAndBounded() {
        byte[] bytes = {1, 2};
        String hash = "a".repeat(64);
        var request = new EnergySharingMeasurementImport(bytes, "ponton:123", Instant.EPOCH, hash, true);
        bytes[0] = 9;
        request.source()[1] = 9;
        assertArrayEquals(new byte[]{1, 2}, request.source());
        assertEquals("ponton:123", request.archiveId());
        assertEquals(Instant.EPOCH, request.archivedAt());
        assertEquals(hash, request.payloadSha256());
        assertTrue(request.dryRun());
        byte[] empty = {};
        byte[] oversized = new byte[EnergySharingMeasurementImport.MAX_BYTES + 1];
        assertThrows(EnergySharingException.class, () -> new EnergySharingMeasurementImport(empty, "a", Instant.EPOCH, hash, true));
        assertThrows(EnergySharingException.class, () -> new EnergySharingMeasurementImport(oversized, "a", Instant.EPOCH, hash, true));
        assertThrows(EnergySharingException.class, () -> new EnergySharingMeasurementImport(bytes, "../unsafe", Instant.EPOCH, hash, true));
        assertThrows(EnergySharingException.class, () -> new EnergySharingMeasurementImport(bytes, "a", Instant.EPOCH, "bad", true));
    }

}
