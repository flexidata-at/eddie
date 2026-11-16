// SPDX-FileCopyrightText: 2025-2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0

package energy.eddie.regionconnector.at.eda.ponton.messages.consumptionrecord._01p41;

import at.ebutilities.schemata.customerprocesses.consumptionrecord._01p41.ConsumptionRecord;
import energy.eddie.regionconnector.at.eda.dto.EdaConsumptionRecord;
import energy.eddie.regionconnector.at.eda.dto.Energy;
import energy.eddie.regionconnector.at.eda.processing.utils.XmlGregorianCalenderUtils;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static energy.eddie.regionconnector.at.eda.EdaRegionConnectorMetadata.AT_ZONE_ID;

public record EdaConsumptionRecord01p41(ConsumptionRecord consumptionRecord, String encodedOriginalXml) implements EdaConsumptionRecord {
    public EdaConsumptionRecord01p41(ConsumptionRecord consumptionRecord) {
        this(consumptionRecord, "");
    }

    @Override
    public String toString() {
        return "EdaConsumptionRecord01p41[messageId=" + messageId() + ", originalXmlRetained=" + !encodedOriginalXml.isEmpty() + "]";
    }

    @Override
    public Optional<byte[]> originalXml() {
        return encodedOriginalXml.isEmpty() ? Optional.empty()
                : Optional.of(java.util.Base64.getDecoder().decode(encodedOriginalXml));
    }

    @Override
    public String messageId() {
        return consumptionRecord.getProcessDirectory().getMessageId();
    }

    @Override
    public String conversationId() {
        return consumptionRecord.getProcessDirectory().getConversationId();
    }

    @Override
    public String meteringPoint() {
        return consumptionRecord.getProcessDirectory().getMeteringPoint();
    }

    @Override
    public Optional<String> energyCommunityId() {
        return Optional.ofNullable(consumptionRecord.getProcessDirectory().getECID());
    }

    @Override
    public LocalDate startDate() {
        if (energyCommunityId().isPresent()) {
            return consumptionRecord.getProcessDirectory().getEnergy().stream()
                                    .map(energy -> XmlGregorianCalenderUtils.toUtcZonedDateTime(energy.getMeteringPeriodStart()))
                                    .map(time -> time.withZoneSameInstant(AT_ZONE_ID).toLocalDate())
                                    .min(LocalDate::compareTo).orElseThrow();
        }
        return consumptionRecord.getProcessDirectory()
                                .getEnergy()
                                .getFirst()
                                .getMeteringPeriodStart().toGregorianCalendar().toZonedDateTime().toLocalDate();
    }

    @Override
    public LocalDate endDate() {
        return consumptionRecord.getProcessDirectory()
                                .getEnergy()
                                .getLast()
                                .getMeteringPeriodEnd().toGregorianCalendar().toZonedDateTime().toLocalDate();
    }

    @Override
    public LocalDate lastReadingDate() {
        return consumptionRecord.getProcessDirectory().getEnergy().stream()
                                .map(energy -> XmlGregorianCalenderUtils.toUtcZonedDateTime(energy.getMeteringPeriodEnd()))
                                .map(time -> time.minusNanos(1).withZoneSameInstant(AT_ZONE_ID).toLocalDate())
                                .max(LocalDate::compareTo).orElseThrow();
    }

    @Override
    public String senderMessageAddress() {
        return consumptionRecord.getMarketParticipantDirectory().getRoutingHeader().getSender().getMessageAddress();
    }

    @Override
    public ZonedDateTime documentCreationDateTime() {
        return XmlGregorianCalenderUtils.toUtcZonedDateTime(consumptionRecord.getMarketParticipantDirectory()
                                                                             .getRoutingHeader()
                                                                             .getDocumentCreationDateTime());
    }

    @Override
    public String receiverMessageAddress() {
        return consumptionRecord.getMarketParticipantDirectory().getRoutingHeader().getReceiver().getMessageAddress();
    }

    @Override
    public List<Energy> energy() {
        return consumptionRecord.getProcessDirectory().getEnergy().stream()
                                .map(energy -> (Energy) new Energy01p41(energy))
                                .toList();
    }

    @Override
    public String schemaVersion() {
        return consumptionRecord.getMarketParticipantDirectory().getSchemaVersion();
    }

    @Override
    public ZonedDateTime processDate() {
        return consumptionRecord.getProcessDirectory()
                                .getProcessDate()
                                .toGregorianCalendar()
                                .toZonedDateTime();
    }

    @Override
    public Object originalConsumptionRecord() {
        return consumptionRecord;
    }
}
