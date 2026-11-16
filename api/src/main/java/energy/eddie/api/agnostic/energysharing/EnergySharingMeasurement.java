// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.api.agnostic.energysharing;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;

/** One original interval. Quality and interval retain national codes; valueTag retains the original canonical series code. */
public record EnergySharingMeasurement(Instant start, Instant end, BigDecimal value, String unit,
                                       String valueTag, String meterCode, String quality, String interval,
                                       JsonNode nativeParameters) {
    public EnergySharingMeasurement {
        nativeParameters = nativeParameters.deepCopy();
    }
    @Override public JsonNode nativeParameters() { return nativeParameters.deepCopy(); }
}
