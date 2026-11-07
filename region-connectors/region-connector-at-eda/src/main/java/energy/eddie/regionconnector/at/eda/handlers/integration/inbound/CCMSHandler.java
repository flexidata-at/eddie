// SPDX-FileCopyrightText: 2024-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.handlers.integration.inbound;

import energy.eddie.cim.agnostic.PermissionProcessStatus;
import energy.eddie.dataneeds.needs.CESUJoinRequestDataNeed;
import energy.eddie.dataneeds.services.DataNeedsService;
import energy.eddie.regionconnector.at.api.AtPermissionRequest;
import energy.eddie.regionconnector.at.api.AtPermissionRequestRepository;
import energy.eddie.regionconnector.at.eda.models.CMRequestStatus;
import energy.eddie.regionconnector.at.eda.models.ResponseCode;
import energy.eddie.regionconnector.at.eda.permission.request.EdaPermissionRequest;
import energy.eddie.regionconnector.at.eda.permission.request.events.EdaAnswerEvent;
import energy.eddie.regionconnector.at.eda.requests.CCMORevoke;
import energy.eddie.regionconnector.shared.event.sourcing.Outbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CCMSHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CCMSHandler.class);
    private final Outbox outbox;
    private final AtPermissionRequestRepository repository;
    private final DataNeedsService dataNeedsService;

    public CCMSHandler(Outbox outbox, AtPermissionRequestRepository repository,
                       @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection") DataNeedsService dataNeedsService) {
        this.outbox = outbox;
        this.repository = repository;
        this.dataNeedsService = dataNeedsService;
    }


    /**
     * Handle a CCMS_REJECT message. We mark the permission request as externally terminated no matter the reason. This
     * means even if we still receive data from the DSO we will not process it.
     * <p>
     * We do this, since the response codes {@link ResponseCode.KnownResponseCodes#NO_CONSENT_PRESENT} and
     * {@link ResponseCode.KnownResponseCodes#CONSENT_ID_EXPIRED} both indicate that the consent is not valid anymore, so we should
     * not receive any data anymore. This case should only occur if the eligible party tries to terminate a permission
     * request that has already been {@link PermissionProcessStatus#FULFILLED}.
     * <p>
     * The response code {@link ResponseCode.KnownResponseCodes#INVALID_PROCESSDATE} should never occur since we set the process
     * date of the {@link CCMORevoke} to the current date.
     * <p>
     * The last option {@link ResponseCode.KnownResponseCodes#CONSENT_AND_METERINGPOINT_DO_NOT_MATCH} should never occur, if this
     * occurs we have a bug in our system.
     */
    public void handleCCMSReject(CMRequestStatus cmRequestStatus) {
        for (var permissionRequest : repository
                .findByConversationIdOrCMRequestId(
                        cmRequestStatus.conversationId(),
                        cmRequestStatus.cmRequestId())
                .stream()
                .map(EdaPermissionRequest::fromProjection)
                .toList()) {
            // the cmRequestId is not necessarily unique, only process marked permission requests
            if (permissionRequest.status() != PermissionProcessStatus.REQUIRES_EXTERNAL_TERMINATION) {
                continue;
            }

            var isCesu = isCesu(permissionRequest);
            if (isCesu && !matchesCesuReply(permissionRequest, cmRequestStatus)) {
                continue;
            }
            var permissionId = permissionRequest.permissionId();
            var responseCodes = cmRequestStatus.consentData().stream()
                    .flatMap(data -> data.responseCodes().stream())
                    .toList();
            for (Integer statusCode : responseCodes) {
                if (statusCode == ResponseCode.KnownResponseCodes.INVALID_PROCESSDATE.getCode()) {
                    LOGGER.error(
                            "Received a message that indicates that we send an invalid process date for the CMRevoke message for permission request '{}', this should never happen",
                            permissionId);
                } else if (statusCode == ResponseCode.KnownResponseCodes.CONSENT_AND_METERINGPOINT_DO_NOT_MATCH.getCode()) {
                    LOGGER.error(
                            "Received a message that indicates that the consent id and metering point id provided in the CMRevoke message do not match for permission request '{}', this should never happen",
                            permissionId);
                }
            }

            var absentOrExpired = !responseCodes.isEmpty() && responseCodes.stream().allMatch(code ->
                    code == ResponseCode.KnownResponseCodes.NO_CONSENT_PRESENT.getCode()
                    || code == ResponseCode.KnownResponseCodes.CONSENT_ID_EXPIRED.getCode());
            var result = isCesu && !absentOrExpired
                    ? PermissionProcessStatus.FAILED_TO_TERMINATE
                    : PermissionProcessStatus.EXTERNALLY_TERMINATED;
            outbox.commit(
                    new EdaAnswerEvent(permissionId,
                                       result,
                                       cmRequestStatus.message())
            );
        }
    }

    public void handleCCMSAnswer(CMRequestStatus cmRequestStatus) {
        for (var permissionRequest : repository
                .findByConversationIdOrCMRequestId(
                        cmRequestStatus.conversationId(),
                        cmRequestStatus.cmRequestId())
                .stream()
                .map(EdaPermissionRequest::fromProjection)
                .toList()) {
            // the cmRequestId is not necessarily unique, only process permission requests that have been marked for external termination
            if (permissionRequest.status() == PermissionProcessStatus.REQUIRES_EXTERNAL_TERMINATION
                    && (!isCesu(permissionRequest) || matchesCesuReply(permissionRequest, cmRequestStatus))) {
                outbox.commit(
                        new EdaAnswerEvent(permissionRequest.permissionId(),
                                           PermissionProcessStatus.EXTERNALLY_TERMINATED,
                                           cmRequestStatus.message())
                );
            }
        }
    }

    private boolean isCesu(AtPermissionRequest request) {
        return dataNeedsService.findById(request.dataNeedId()).filter(CESUJoinRequestDataNeed.class::isInstance).isPresent();
    }

    private static boolean matchesCesuReply(AtPermissionRequest permission, CMRequestStatus response) {
        return permission.conversationId().equals(response.conversationId())
                && response.consentData().stream().anyMatch(data ->
                        (data.cmConsentId().isEmpty() || data.cmConsentId().equals(permission.consentId()))
                        && (data.meteringPoint().isEmpty() || data.meteringPoint().equals(permission.meteringPointId())));
    }
}
