// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.EnergySharingRequest;
import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * Persisted scheduling intent. A retry retains the exact original request, including query dates.
 */
public record EdaMasterDataRefreshState(
    @Nullable EnergySharingRequest request,
    @Nullable Instant requestCreatedAt,
    String triggerRevision,
    String leaseOwner,
    Instant leaseUntil,
    Instant nextAttemptAt,
    int failures,
    String phase,
    String detailCode) {
  static EdaMasterDataRefreshState initial(Instant now) {
    return new EdaMasterDataRefreshState(null, null, "", "", Instant.EPOCH, now, 0, "IDLE", "");
  }

  EdaMasterDataRefreshState leased(String owner, Instant until) {
    return new EdaMasterDataRefreshState(
        request,
        requestCreatedAt,
        triggerRevision,
        owner,
        until,
        nextAttemptAt,
        failures,
        phase,
        detailCode);
  }

  EdaMasterDataRefreshState preparing(EnergySharingRequest next, Instant now, String revision) {
    return new EdaMasterDataRefreshState(
        next, now, revision, leaseOwner, leaseUntil, now, 0, "PREPARED", "");
  }

  EdaMasterDataRefreshState idleUntil(Instant next, String revision) {
    return new EdaMasterDataRefreshState(
        null, null, revision, leaseOwner, leaseUntil, next, 0, "IDLE", "NATIVE_CONFIRMATION");
  }

  EdaMasterDataRefreshState waiting(Instant next, String detail, int attempts) {
    return new EdaMasterDataRefreshState(
        request,
        requestCreatedAt,
        triggerRevision,
        leaseOwner,
        leaseUntil,
        next,
        attempts,
        "WAITING",
        detail);
  }

  EdaMasterDataRefreshState blocked(String detail, Instant next) {
    return new EdaMasterDataRefreshState(
        request,
        requestCreatedAt,
        triggerRevision,
        leaseOwner,
        leaseUntil,
        next,
        failures,
        "BLOCKED",
        detail);
  }

  EdaMasterDataRefreshState released() {
    return leased("", Instant.EPOCH);
  }
}
