// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import static org.junit.jupiter.api.Assertions.*;

import energy.eddie.api.agnostic.energysharing.EnergySharingRequest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import({EdaMasterDataRefreshStore.class, JsonMapper.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EdaMasterDataRefreshStoreIntegrationTest {
  @SuppressWarnings("unused")
  @Container
  @ServiceConnection
  private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:15-alpine");

  private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
  private static final EdaMasterDataRefreshConfiguration.Target TARGET =
      new EdaMasterDataRefreshConfiguration.Target("connection", "operator", "community");
  @Autowired private EdaMasterDataRefreshStore store;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private JsonMapper mapper;

  @BeforeEach
  void clean() {
    jdbc.update("DELETE FROM at_eda.energy_sharing_record");
  }

  @Test
  void concurrentClaimsAllowOneLeaseAndOnlyTheNewOwnerCanUpdateAfterExpiry() throws Exception {
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      var first =
          executor.submit(
              () -> {
                start.await();
                return store.claim(TARGET, NOW, "first");
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return store.claim(TARGET, NOW, "second");
              });
      start.countDown();
      var results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertEquals(1, results.stream().filter(java.util.Optional::isPresent).count());
      var original = results.stream().flatMap(java.util.Optional::stream).findFirst().orElseThrow();
      assertTrue(store.claim(TARGET, NOW.plusSeconds(60), "early").isEmpty());
      var replacement = store.claim(TARGET, NOW.plusSeconds(121), "replacement").orElseThrow();
      assertFalse(
          store.save(
              TARGET,
              original.blocked("old-worker", NOW.plusSeconds(3600)),
              NOW.plusSeconds(122),
              true));
      assertTrue(
          store.save(
              TARGET,
              replacement.waiting(NOW.plusSeconds(600), "WAIT", 1),
              NOW.plusSeconds(122),
              true));
      assertTrue(store.claim(TARGET, NOW.plusSeconds(599), "too-early").isEmpty());
      assertEquals(
          "WAIT", store.claim(TARGET, NOW.plusSeconds(600), "next").orElseThrow().detailCode());
    }
    assertEquals(
        1L,
        jdbc.queryForObject(
            "SELECT count(*) FROM at_eda.energy_sharing_record WHERE kind='MASTER_DATA_REFRESH'",
            Long.class));
  }

  @Test
  void frozenIntentSurvivesCrashAndCannotBeReplacedByAnOldEmptyState() {
    var initial = store.claim(TARGET, NOW, "one").orElseThrow();
    var parameters = mapper.createObjectNode().put("start", "2026-10-17").put("end", "2026-12-16");
    var request =
        new EnergySharingRequest(
            "request-id",
            "connection",
            "operator",
            "community",
            "SYNC_MASTER_DATA",
            "version",
            parameters);
    var prepared = initial.preparing(request, NOW, "native-revision");
    assertTrue(store.save(TARGET, prepared, NOW, false));
    assertFalse(store.save(TARGET, initial.waiting(NOW, "failure", 1), NOW, true));
    var resumed = store.claim(TARGET, NOW.plusSeconds(121), "after-restart").orElseThrow();
    assertEquals(request, resumed.request());
    assertEquals(NOW, resumed.requestCreatedAt());
    var other =
        new EnergySharingRequest(
            "different-id",
            "connection",
            "operator",
            "community",
            "SYNC_MASTER_DATA",
            "version",
            parameters);
    assertFalse(
        store.save(TARGET, resumed.preparing(other, NOW, "other"), NOW.plusSeconds(121), true));
    assertTrue(
        store.save(
            TARGET,
            resumed.idleUntil(NOW.plusSeconds(3600), "confirmed"),
            NOW.plusSeconds(121),
            true));
  }

  @Test
  void onlySameScopeActualOutstandingQueryBlocksAndSchedulerHasNoMarketCorrelationId() {
    store.claim(TARGET, NOW, "lease");
    assertFalse(store.hasOutstandingQuery(TARGET));
    String payload =
        "{\"request\":{\"operation\":\"SYNC_MASTER_DATA\",\"communityId\":\"community\"},\"operation\":{\"status\":\"SUBMITTED\"}}";
    insert("snapshot", store.scope(TARGET), "SNAPSHOT", payload);
    insert("other-kind", store.scope(TARGET), "DAILY_CHANGE", payload);
    insert("foreign-scope", "foreign", "OPERATION", payload);
    insert(
        "foreign-community",
        store.scope(TARGET),
        "OPERATION",
        payload.replace("community\"", "other\""));
    assertFalse(store.hasOutstandingQuery(TARGET));
    insert("active", store.scope(TARGET), "OPERATION", payload);
    assertTrue(store.hasOutstandingQuery(TARGET));
    jdbc.update(
        "UPDATE at_eda.energy_sharing_record SET payload=replace(payload,'SUBMITTED','COMPLETED')"
            + " WHERE id='active'");
    assertFalse(store.hasOutstandingQuery(TARGET));
    assertNull(
        jdbc.queryForObject(
            "SELECT market_id FROM at_eda.energy_sharing_record WHERE kind='MASTER_DATA_REFRESH'",
            String.class));
  }

  private void insert(String id, String scope, String kind, String payload) {
    jdbc.update(
        "INSERT INTO at_eda.energy_sharing_record(id,scope,kind,fingerprint,market_id,payload)"
            + " VALUES(?,?,?,'hash',NULL,?)",
        id,
        scope,
        kind,
        payload);
  }
}
