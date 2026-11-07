// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.data.needs.calculation.strategies;

import energy.eddie.api.agnostic.data.needs.Timeframe;
import energy.eddie.dataneeds.needs.CESUJoinRequestDataNeed;
import energy.eddie.dataneeds.needs.DataNeed;
import energy.eddie.regionconnector.shared.services.data.needs.calculation.strategies.PermissionEndIsEnergyDataEndStrategy;
import energy.eddie.regionconnector.shared.services.data.needs.calculation.strategies.PermissionTimeframeStrategy;
import jakarta.annotation.Nullable;

import java.time.ZonedDateTime;

import static energy.eddie.regionconnector.at.eda.EdaRegionConnectorMetadata.AT_ZONE_ID;

/**
 * A custom {@link PermissionTimeframeStrategy} that differentiates between energy community data needs and all others.
 * In Austria, permission timeframes for adding a new meter to an energy community must start between the next day or the next 30 days.
 */
public class EdaPermissionTimeframeStrategy implements PermissionTimeframeStrategy {
    private final PermissionEndIsEnergyDataEndStrategy fallbackStrategy = new PermissionEndIsEnergyDataEndStrategy();

    @Override
    public Timeframe permissionTimeframe(
            @Nullable Timeframe energyDataTimeframe,
            @Nullable DataNeed dataNeed,
            ZonedDateTime referenceDateTime
    ) {
        if (dataNeed instanceof CESUJoinRequestDataNeed) {
            return new Timeframe(referenceDateTime.withZoneSameInstant(AT_ZONE_ID).toLocalDate().plusDays(1), null);
        }
        return fallbackStrategy.permissionTimeframe(energyDataTimeframe, dataNeed, referenceDateTime);
    }
}
