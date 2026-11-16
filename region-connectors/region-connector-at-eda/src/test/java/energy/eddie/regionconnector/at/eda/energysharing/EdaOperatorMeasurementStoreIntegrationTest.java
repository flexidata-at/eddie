// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.EnergySharingException;
import energy.eddie.regionconnector.at.eda.persistence.EnergySharingRecord;
import energy.eddie.regionconnector.at.eda.persistence.JpaEnergySharingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import java.util.concurrent.*;
import static energy.eddie.regionconnector.at.eda.energysharing.OperatorMeasurementFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({EdaOperatorMeasurementStore.class, EdaEnergySharingStore.class, JsonMapper.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EdaOperatorMeasurementStoreIntegrationTest {
    @SuppressWarnings("unused")
    @Container @ServiceConnection
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");
    @Autowired private EdaOperatorMeasurementStore store;
    @Autowired private EdaEnergySharingStore snapshots;
    @Autowired private JpaEnergySharingRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach void clean() {
        jdbc.update("DELETE FROM at_eda.operator_measurement_document");
        jdbc.update("DELETE FROM at_eda.operator_measurement_cursor");
        repository.deleteAll();
        snapshots.insert(new EnergySharingRecord("snapshot", "scope", "SNAPSHOT", "hash", "snapshot-market", roster()));
    }
    private void append(String message) {
        store.append("scope", "snapshot", message, document(message), bytes(xml(message)), ignored -> MAPPER.readTree(roster()));
    }
    private EdaOperatorMeasurementStore.Page page(long cursor, int limit) {
        return store.page("scope", cursor, limit);
    }

    @Test void sameSourceIsIdempotentDifferentContentConflictsAndOriginalBytesRemainScoped() {
        append("M1");
        append("M1");
        var changed = document("M1");
        byte[] original = bytes(xml("M1"));
        assertThrows(EnergySharingException.class, () -> store.append("scope", "snapshot", "changed", changed, original, ignored -> MAPPER.readTree(roster())));
        var first = page(0, 20);
        assertEquals(1, first.documents().size());
        assertEquals(1, first.nextSequence());
        assertFalse(first.hasMore());
        String id = first.documents().getFirst().documentId();
        assertArrayEquals(original, store.source("scope", id));
        assertThrows(EnergySharingException.class, () -> store.source("foreign", id));
        assertEquals("revision", first.documents().getFirst().nativeParameters().path("authorityEvidence").path("snapshot").path("revision").asText());
    }

    @Test void rejectedRosterRollsBackCursorReservationWithoutAGap() {
        var document = document("REJECT");
        byte[] source = bytes(xml("REJECT"));
        assertThrows(IllegalStateException.class, () -> store.append("scope", "snapshot", "reject", document, source,
                ignored -> { throw new IllegalStateException("Not authorized"); }));
        append("M2");
        assertEquals(1, page(0, 1).documents().getFirst().sequence());
    }

    @Test void concurrentCommitCannotMakeAConsumerSkipAnEarlierSequence() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> store.append("scope", "snapshot", "M1", document("M1"), bytes(xml("M1")), ignored -> {
                locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                return MAPPER.readTree(roster());
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> append("M2"));
            try {
                assertTrue(page(0, 10).documents().isEmpty());
                assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(java.util.List.of(1L, 2L), page(0, 10).documents().stream().map(doc -> doc.sequence()).toList());
    }

    @Test void rollingRosterAndEndedMembershipDoNotEraseHistoricallyAcceptedDocumentsOrSkipSequence() {
        append("M1");
        append("M2");
        append("M3");
        var first = page(0, 1);
        assertTrue(first.hasMore());
        assertEquals(1, first.nextSequence());
        String originalProof = first.documents().getFirst().nativeParameters().path("authorityEvidence").toString();
        snapshots.update("snapshot", ignored -> "{\"snapshot\":{\"meteringPoints\":[],\"revision\":\"new-window\"}}");
        var second = page(1, 1);
        assertEquals(2, second.nextSequence());
        assertEquals("M2", second.documents().getFirst().sourceMessageId());
        assertTrue(second.hasMore());
        assertEquals("M3", page(2, 20).documents().getFirst().sourceMessageId());
        assertEquals(originalProof, page(0, 1).documents().getFirst().nativeParameters().path("authorityEvidence").toString());
        assertArrayEquals(bytes(xml("M1")), store.source("scope", first.documents().getFirst().documentId()));
        // An accepted retransmission does not require the old interval to appear in a new rolling query.
        store.append("scope", "snapshot", "M1", document("M1"), bytes(xml("M1")), ignored -> {
            throw new IllegalStateException("Must not reauthorize an already accepted source");
        });
        assertEquals(3, page(0, 20).documents().size());
        // Model large native documents without allocating 100k fixture readings.
        jdbc.update("UPDATE at_eda.operator_measurement_document SET reading_count=60000");
        assertEquals(1, page(0, 20).documents().size());
        assertTrue(page(0, 20).hasMore());
        jdbc.update("UPDATE at_eda.operator_measurement_document SET reading_count=1,payload_bytes=20000000");
        assertEquals(1, page(0, 20).documents().size());
    }

    @Test void sourceChronologyIsRetainedEvenWhenOldCorrectionArrivesLater() {
        var newer = document("NEWER");
        var older = new EdaOperatorMeasurementXml(MAPPER).parse(bytes(xml("OLDER").replace("2024-04-02T14:40:48Z", "2024-04-01T14:40:48Z")), java.time.Instant.EPOCH).document();
        store.append("scope", "snapshot", "new", newer, bytes(xml("NEWER")), ignored -> MAPPER.readTree(roster()));
        store.append("scope", "snapshot", "old", older, bytes(xml("OLDER")), ignored -> MAPPER.readTree(roster()));
        var results = page(0, 10).documents();
        assertEquals(2, results.size());
        assertEquals(older.sourceDocumentCreatedAt(), results.getLast().sourceDocumentCreatedAt());
        assertEquals(2, results.getLast().sequence());
        assertTrue(results.getLast().sourceDocumentCreatedAt().isBefore(results.getFirst().sourceDocumentCreatedAt()));
    }
}
