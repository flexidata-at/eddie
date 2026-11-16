// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.api.agnostic.energysharing;

import java.util.List;

/** A bounded page within one country, connection, operator and community. */
public record EnergySharingMeasurementPage(String countryCode, String connectionId, String operatorId,
        String communityId, long afterSequence, long nextSequence, boolean hasMore,
        List<EnergySharingMeasurementDocument> documents) {
    public EnergySharingMeasurementPage { documents = List.copyOf(documents); }
}
