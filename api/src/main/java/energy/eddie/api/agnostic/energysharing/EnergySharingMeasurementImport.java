// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.api.agnostic.energysharing;

import java.time.Instant;
import java.util.Objects;

/** Exact archived native bytes. Archive metadata is provenance, never an authorization selector. */
public final class EnergySharingMeasurementImport {
  public static final int MAX_BYTES = 16 * 1024 * 1024;
  private final byte[] source;
  private final String archiveId;
  private final Instant archivedAt;
  private final String payloadSha256;
  private final boolean dryRun;

  public EnergySharingMeasurementImport(
      byte[] source, String archiveId, Instant archivedAt, String payloadSha256, boolean dryRun) {
    if (source.length == 0
        || source.length > MAX_BYTES
        || !archiveId.matches("[A-Za-z0-9_.:-]{1,200}")
        || !payloadSha256.matches("[0-9a-f]{64}"))
      throw new EnergySharingException(
          EnergySharingException.Code.INVALID_REQUEST,
          "Invalid bounded archive document or identity");
    this.source = source.clone();
    this.archiveId = archiveId;
    this.archivedAt = Objects.requireNonNull(archivedAt);
    this.payloadSha256 = payloadSha256;
    this.dryRun = dryRun;
  }

  public byte[] source() {
    return source.clone();
  }

  public String archiveId() {
    return archiveId;
  }

  public Instant archivedAt() {
    return archivedAt;
  }

  public String payloadSha256() {
    return payloadSha256;
  }

  public boolean dryRun() {
    return dryRun;
  }
}
