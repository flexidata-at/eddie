// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.regionconnector.at.eda.persistence.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import jakarta.persistence.EntityManager;

@Component
public class EdaEnergySharingStore {
    private final JpaEnergySharingRepository repository;
    private final EntityManager entityManager;
    public EdaEnergySharingStore(JpaEnergySharingRepository repository, EntityManager entityManager) {
        this.repository=repository; this.entityManager=entityManager;
    }
    public Optional<EnergySharingRecord> find(String id) { return repository.findById(id); }
    public Optional<EnergySharingRecord> byMarketId(String id) { return repository.findByMarketId(id); }
    public List<EnergySharingRecord> records(String scope, String kind) { return repository.findByScopeAndKind(scope,kind); }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void insert(EnergySharingRecord value) { entityManager.persist(value); entityManager.flush(); }
    /** Capture the exact authoritative roster in the same commit as the reserved outbound identity. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void insertAuthorized(EnergySharingRecord value, String snapshotId, UnaryOperator<String> authorize) {
        var snapshot = repository.findLocked(snapshotId).orElseThrow(() -> new energy.eddie.api.agnostic.energysharing.EnergySharingException(
                energy.eddie.api.agnostic.energysharing.EnergySharingException.Code.NOT_READY, "Synchronize authoritative community membership first"));
        if (!"SNAPSHOT".equals(snapshot.kind())) throw new IllegalStateException("Expected authoritative snapshot");
        value.payload(authorize.apply(snapshot.payload()));
        entityManager.persist(value);
        entityManager.flush();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void update(String id, UnaryOperator<String> change) {
        var row=repository.findLocked(id).orElseThrow();
        row.payload(change.apply(row.payload()));
        repository.saveAndFlush(row);
    }
}
