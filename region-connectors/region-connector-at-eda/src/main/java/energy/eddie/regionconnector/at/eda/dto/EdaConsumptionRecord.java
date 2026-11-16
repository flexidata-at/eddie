// SPDX-FileCopyrightText: 2024-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.dto;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface EdaConsumptionRecord {
    String messageId();

    String conversationId();

    String meteringPoint();

    default Optional<String> energyCommunityId() {
        return Optional.empty();
    }

    LocalDate startDate();

    LocalDate endDate();

    default LocalDate lastReadingDate() {
        return endDate();
    }

    String senderMessageAddress();

    ZonedDateTime documentCreationDateTime();

    String receiverMessageAddress();

    List<Energy> energy();

    String schemaVersion();

    ZonedDateTime processDate();

    Object originalConsumptionRecord();

    /** Exact wire bytes, when retained by the national inbound parser. */
    default Optional<byte[]> originalXml() { return Optional.empty(); }
}
