// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.api.agnostic.energysharing;

import jakarta.annotation.Nullable;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Market membership is independent of a customer's separately authorized data connection. */
public record EnergySharingMeteringPoint(String meteringPointId,
                                         @Nullable String permissionAdministratorId,
                                         @Nullable String direction,
                                         @Nullable BigDecimal participationFactor,
                                         @Nullable BigDecimal allocationFactor,
                                         @Nullable LocalDate start, @Nullable LocalDate end,
                                         @Nullable String status, JsonNode nativeParameters, @Nullable String codingScheme) {
    public EnergySharingMeteringPoint {
        nativeParameters = nativeParameters.deepCopy();
    }
}
