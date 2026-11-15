// SPDX-FileCopyrightText: 2025-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.fr.enedis.tasks;

import energy.eddie.api.agnostic.Granularity;
import energy.eddie.api.agnostic.data.needs.DataNeedCalculationService;
import energy.eddie.api.agnostic.data.needs.ValidatedHistoricalDataDataNeedResult;
import energy.eddie.regionconnector.fr.enedis.api.FrEnedisPermissionRequest;
import energy.eddie.regionconnector.fr.enedis.dto.EnedisApiError;
import energy.eddie.regionconnector.fr.enedis.permission.events.FrGranularityUpdateEvent;
import energy.eddie.regionconnector.shared.event.sourcing.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
public class UpdateGranularityTask {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateGranularityTask.class);
    private final Outbox outbox;
    private final DataNeedCalculationService calculationService;

    public UpdateGranularityTask(Outbox outbox, DataNeedCalculationService calculationService) {
        this.outbox = outbox;
        this.calculationService = calculationService;
    }

    public void update(FrEnedisPermissionRequest permissionRequest, Throwable throwable) {
        var permissionId = permissionRequest.permissionId();
        if (!isDataNotFoundError(throwable)) {
            return;
        }
        if (containsHigherGranularity(permissionRequest)) {
            LOGGER.info("Updating granularity of permission request {} from {} to {}",
                        permissionId,
                        permissionRequest.granularity(),
                        Granularity.P1D);
            outbox.commit(new FrGranularityUpdateEvent(permissionId, Granularity.P1D));
            return;
        }
        LOGGER.info("ENEDIS has no data yet for permission request {} at {}; retaining accepted permission for retry",
                    permissionId, permissionRequest.granularity());
    }

    private static boolean isDataNotFoundError(Throwable throwable) {
        if (!(throwable instanceof WebClientResponseException webclientException)) {
            return false;
        }
        try {
            var error = webclientException.getResponseBodyAs(EnedisApiError.class);
            return error != null && "no_data_found".equals(error.error());
        } catch (RuntimeException exception) {
            // A proxy/technical error may return HTML or malformed JSON. It is not a no-data decision.
            LOGGER.warn("Could not decode ENEDIS error response with HTTP status {}; retaining the permission for retry",
                        webclientException.getStatusCode());
            return false;
        }
    }

    private boolean containsHigherGranularity(FrEnedisPermissionRequest permissionRequest) {
        if (permissionRequest.granularity() == Granularity.P1D) {
            return false;
        }
        var result = calculationService.calculate(permissionRequest.dataNeedId(), permissionRequest.created());
        return switch (result) {
            case ValidatedHistoricalDataDataNeedResult vhd when vhd.granularities().contains(Granularity.P1D) -> true;
            default -> false;
        };
    }
}
