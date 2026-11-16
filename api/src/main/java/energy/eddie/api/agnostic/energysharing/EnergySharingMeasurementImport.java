// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.api.agnostic.energysharing;

import java.time.Instant;
import java.util.Objects;

/** Exact archived native bytes. Archive metadata is provenance, never an authorization selector. */
public record EnergySharingMeasurementImport(
    byte[] source, String archiveId, Instant archivedAt, String payloadSha256, boolean dryRun) {
  public static final int MAX_BYTES = 16 * 1024 * 1024;

  public EnergySharingMeasurementImport {
    if (source.length == 0
        || source.length > MAX_BYTES
        || !archiveId.matches("[A-Za-z0-9_.:-]{1,200}")
        || !payloadSha256.matches("[0-9a-f]{64}"))
      throw new EnergySharingException(
          EnergySharingException.Code.INVALID_REQUEST,
          "Invalid bounded archive document or identity");
    source = source.clone();
    Objects.requireNonNull(archivedAt);
  }

  @Override
  public byte[] source() {
    return source.clone();
  }

  @Override
  public boolean equals(Object other) {
    return other
            instanceof
            EnergySharingMeasurementImport(
                var otherSource,
                var otherArchiveId,
                var otherArchivedAt,
                var otherHash,
                var otherDryRun)
        && java.util.Arrays.equals(source, otherSource)
        && archiveId.equals(otherArchiveId)
        && archivedAt.equals(otherArchivedAt)
        && payloadSha256.equals(otherHash)
        && dryRun == otherDryRun;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        java.util.Arrays.hashCode(source), archiveId, archivedAt, payloadSha256, dryRun);
  }

  @Override
  public String toString() {
    return "EnergySharingMeasurementImport[sourceBytes=%d, dryRun=%s]"
        .formatted(source.length, dryRun);
  }
}
