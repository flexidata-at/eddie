// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.data.needs;

import energy.eddie.api.agnostic.Granularity;
import energy.eddie.api.agnostic.data.needs.EnergyType;
import energy.eddie.dataneeds.needs.CESUJoinRequestDataNeed;
import energy.eddie.dataneeds.needs.DataNeed;
import energy.eddie.dataneeds.needs.RegionConnectorFilter;
import energy.eddie.dataneeds.rules.DataNeedRule;
import energy.eddie.dataneeds.rules.DataNeedRule.AccountingPointDataNeedRule;
import energy.eddie.dataneeds.rules.DataNeedRule.AllowMultipleDataNeedsRule;
import energy.eddie.dataneeds.rules.DataNeedRule.ValidatedHistoricalDataDataNeedRule;
import energy.eddie.dataneeds.rules.DataNeedRuleSet;
import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static energy.eddie.regionconnector.at.eda.EdaRegionConnectorMetadata.REGION_CONNECTOR_ID;

@Component
public class EdaDataNeedRuleSet implements DataNeedRuleSet {
    private static final List<Granularity> SUPPORTED_GRANULARITIES = List.of(Granularity.PT15M, Granularity.P1D);
    private static final Logger LOGGER = LoggerFactory.getLogger(EdaDataNeedRuleSet.class);
    private final AtConfiguration config;

    public EdaDataNeedRuleSet(AtConfiguration config) {this.config = config;}

    @Override
    public List<DataNeedRule> dataNeedRules() {
        List<DataNeedRule> dataNeedRules = new ArrayList<>();
        dataNeedRules.add(new AccountingPointDataNeedRule());
        dataNeedRules.add(new ValidatedHistoricalDataDataNeedRule(EnergyType.ELECTRICITY, SUPPORTED_GRANULARITIES));
        dataNeedRules.add(new AllowMultipleDataNeedsRule());
        if (config.supportsEnergyCommunity()) {
            LOGGER.debug(
                    "Energy Community ID present, enabling the CESU Join Request data need for the AT EDA Region Connector");
            dataNeedRules.add(new DataNeedRule.CESUJoinRequestDataNeedRule(SUPPORTED_GRANULARITIES));
        } else {
            LOGGER.debug(
                    "Energy Community ID not present, disabling the CESU Join Request data need for the AT EDA Region Connector");
        }
        return dataNeedRules;
    }

    @Override
    public boolean hasRuleFor(DataNeed dataNeed) {
        if (!(dataNeed instanceof CESUJoinRequestDataNeed cesuNeed)) {
            return DataNeedRuleSet.super.hasRuleFor(dataNeed);
        }
        if (config.resolveEnergyCommunityId(cesuNeed.energyCommunityId()).isEmpty()) {
            return false;
        }
        // Existing needs without a community ID remain bound to the legacy configured default.
        return cesuNeed.energyCommunityId().isEmpty()
                || cesuNeed.regionConnectorFilter()
                           .filter(filter -> filter.type() == RegionConnectorFilter.Type.ALLOWLIST)
                           .filter(filter -> filter.regionConnectorIds().equals(List.of(REGION_CONNECTOR_ID)))
                           .isPresent();
    }
}
