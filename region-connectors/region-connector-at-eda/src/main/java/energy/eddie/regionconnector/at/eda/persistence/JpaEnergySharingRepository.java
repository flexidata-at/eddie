// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface JpaEnergySharingRepository extends JpaRepository<EnergySharingRecord,String> {
    Optional<EnergySharingRecord> findByMarketId(String marketId);
    List<EnergySharingRecord> findByScopeAndKind(String scope, String kind);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from EnergySharingRecord r where r.id = :id")
    Optional<EnergySharingRecord> findLocked(String id);
}
