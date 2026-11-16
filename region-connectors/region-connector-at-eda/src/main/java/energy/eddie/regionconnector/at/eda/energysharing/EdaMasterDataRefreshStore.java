// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Separate record kind and row lock; the lease is committed before any market transport call. */
@Component
public class EdaMasterDataRefreshStore {
  static final String KIND = "MASTER_DATA_REFRESH";
  private static final String ID = "id";
  private static final String SCOPE = "scope";
  private static final String PAYLOAD = "payload";
  private static final Duration LEASE = Duration.ofMinutes(2);
  private final EntityManager entityManager;
  private final ObjectMapper mapper;

  public EdaMasterDataRefreshStore(EntityManager entityManager, ObjectMapper mapper) {
    this.entityManager = entityManager;
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<EdaMasterDataRefreshState> claim(
      EdaMasterDataRefreshConfiguration.Target target, Instant now, String owner) {
    String id = id(target);
    entityManager
        .createNativeQuery(
            """
            INSERT INTO at_eda.energy_sharing_record(id,scope,kind,fingerprint,market_id,payload)
            VALUES (:id,:scope,'MASTER_DATA_REFRESH',:id,NULL,:payload) ON CONFLICT DO NOTHING
            """)
        .setParameter(ID, id)
        .setParameter(SCOPE, scope(target))
        .setParameter(PAYLOAD, mapper.writeValueAsString(EdaMasterDataRefreshState.initial(now)))
        .executeUpdate();
    var state = locked(id);
    if (state.nextAttemptAt().isAfter(now) || state.leaseUntil().isAfter(now))
      return Optional.empty();
    var leased = state.leased(owner, now.plus(LEASE));
    write(id, leased);
    return Optional.of(leased);
  }

  /**
   * Save the frozen request before submit. A stale worker cannot overwrite its successor's state.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean save(
      EdaMasterDataRefreshConfiguration.Target target,
      EdaMasterDataRefreshState state,
      Instant now,
      boolean release) {
    String id = id(target);
    var current = locked(id);
    if (!current.leaseOwner().equals(state.leaseOwner()) || !current.leaseUntil().isAfter(now))
      return false;
    var frozen = current.request();
    if (frozen != null && state.request() != null && !frozen.equals(state.request())) return false;
    if (frozen != null && state.request() == null && !"IDLE".equals(state.phase())) return false;
    write(id, release ? state.released() : state);
    return true;
  }

  /**
   * Only actual management operations can block a new query, never snapshots or scheduler state.
   */
  @Transactional(readOnly = true)
  public boolean hasOutstandingQuery(EdaMasterDataRefreshConfiguration.Target target) {
    var rows =
        entityManager
            .createNativeQuery(
                """
                SELECT payload FROM at_eda.energy_sharing_record WHERE scope=:scope AND kind='OPERATION'
                AND payload::jsonb->'request'->>'operation'='SYNC_MASTER_DATA'
                AND payload::jsonb->'request'->>'communityId'=:community
                AND payload::jsonb->'operation'->>'status' NOT IN ('COMPLETED','REJECTED')
                """)
            .setParameter(SCOPE, scope(target))
            .setParameter("community", target.communityId())
            .setMaxResults(1)
            .getResultList();
    return !rows.isEmpty();
  }

  private EdaMasterDataRefreshState locked(String id) {
    String payload =
        (String)
            entityManager
                .createNativeQuery(
                    """
                    SELECT payload FROM at_eda.energy_sharing_record WHERE id=:id AND kind='MASTER_DATA_REFRESH' FOR UPDATE
                    """)
                .setParameter(ID, id)
                .getSingleResult();
    return mapper.readValue(payload, EdaMasterDataRefreshState.class);
  }

  private void write(String id, EdaMasterDataRefreshState state) {
    entityManager
        .createNativeQuery(
            """
            UPDATE at_eda.energy_sharing_record SET payload=:payload,version=version+1
            WHERE id=:id AND kind='MASTER_DATA_REFRESH'
            """)
        .setParameter(ID, id)
        .setParameter(PAYLOAD, mapper.writeValueAsString(state))
        .executeUpdate();
  }

  String scope(EdaMasterDataRefreshConfiguration.Target target) {
    return hash(mapper.writeValueAsString(List.of(target.operatorId(), target.connectionId())));
  }

  String id(EdaMasterDataRefreshConfiguration.Target target) {
    return hash(
        mapper.writeValueAsString(
            List.of(KIND, target.operatorId(), target.connectionId(), target.communityId())));
  }

  private static String hash(String value) {
    return EdaOperatorMeasurementXml.hash(value.getBytes(StandardCharsets.UTF_8));
  }
}
