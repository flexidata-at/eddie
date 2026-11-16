// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.*;
import energy.eddie.cim.v1_12.StandardCodingSchemeTypeList;
import energy.eddie.regionconnector.at.eda.dto.EdaConsumptionRecord;
import energy.eddie.regionconnector.at.eda.ponton.messages.consumptionrecord._01p41.EdaConsumptionRecord01p41InboundMessageFactory;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class OperatorMeasurementFixtures {
    static final ObjectMapper MAPPER = new JsonMapper();
    static final String COMMUNITY = "AT000000000000000000000000000001";
    static final String METER = "AT000000000000000000000000000002";
    static final EdaEnergySharingConfiguration.Binding BINDING = new EdaEnergySharingConfiguration.Binding(
            "connection", "CC000001", Map.of(COMMUNITY, "AT000000"), true);
    private OperatorMeasurementFixtures() {}

    static String xml(String message) {
        return """
                <cp:ConsumptionRecord xmlns:cp="http://www.ebutilities.at/schemata/customerprocesses/consumptionrecord/01p41"
                  xmlns:ct="http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20">
                  <cp:MarketParticipantDirectory DocumentMode="PROD" Duplicate="false" SchemaVersion="01.41">
                    <ct:RoutingHeader><ct:Sender AddressType="ECNumber"><ct:MessageAddress>AT000000</ct:MessageAddress></ct:Sender>
                    <ct:Receiver AddressType="ECNumber"><ct:MessageAddress>CC000001</ct:MessageAddress></ct:Receiver>
                    <ct:DocumentCreationDateTime>2024-04-02T14:40:48Z</ct:DocumentCreationDateTime></ct:RoutingHeader>
                    <ct:Sector>01</ct:Sector><cp:MessageCode>DATEN_CRMSG</cp:MessageCode>
                  </cp:MarketParticipantDirectory>
                  <cp:ProcessDirectory><ct:MessageId>%s</ct:MessageId><ct:ConversationId>DATADEVTEST</ct:ConversationId>
                    <ct:ProcessDate>2024-04-02</ct:ProcessDate><ct:MeteringPoint>%s</ct:MeteringPoint><cp:ECID>%s</cp:ECID>
                    <cp:Sequence>1</cp:Sequence>
                    <cp:Energy><cp:MeteringReason>00</cp:MeteringReason>
                    <cp:MeteringPeriodStart>2024-03-30T00:00:00+01:00</cp:MeteringPeriodStart>
                    <cp:MeteringPeriodEnd>2024-03-31T00:00:00+01:00</cp:MeteringPeriodEnd>
                    <cp:MeteringIntervall>D</cp:MeteringIntervall><cp:NumberOfMeteringIntervall>1</cp:NumberOfMeteringIntervall>
                    <cp:EnergyData MeterCode="1-1:1.9.0 G.01" UOM="KWH"><cp:EP>
                    <cp:DTF>2024-03-30T00:00:00+01:00</cp:DTF><cp:DTT>2024-03-31T00:00:00+01:00</cp:DTT>
                    <cp:MM>L1</cp:MM><cp:BQ>36.770000</cp:BQ></cp:EP></cp:EnergyData></cp:Energy>
                  </cp:ProcessDirectory>
                </cp:ConsumptionRecord>
                """.formatted(message, METER, COMMUNITY);
    }

    static EdaConsumptionRecord record(String xml) throws Exception {
        var marshaller = new Jaxb2Marshaller();
        marshaller.setPackagesToScan("at.ebutilities.schemata");
        marshaller.afterPropertiesSet();
        return new EdaConsumptionRecord01p41InboundMessageFactory(marshaller)
                .parseInputStream(new ByteArrayInputStream(bytes(xml)));
    }
    static byte[] bytes(String xml) { return xml.getBytes(StandardCharsets.UTF_8); }
    static EnergySharingMeasurementDocument document(String message) {
        return new EdaOperatorMeasurementXml(MAPPER).parse(bytes(xml(message)), Instant.parse("2026-11-16T10:00:00Z")).document();
    }
    static String roster() {
        var nativeData = MAPPER.createObjectNode();
        nativeData.putArray("timePeriods").addObject().put("start", "2024-03-01").put("end", "2024-04-30")
                .put("activate", "2024-03-01");
        var point = new EnergySharingMeteringPoint(METER, "AT000000", "CONSUMPTION", null, null, null, null,
                "CONFIRMED", nativeData, StandardCodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME.value());
        var snapshot = new EnergySharingCommunitySnapshot(COMMUNITY, "CC000001", "connection", "revision", Instant.EPOCH,
                true, "Synthetic", "D", List.of(point), MAPPER.createObjectNode());
        return MAPPER.createObjectNode().set("snapshot", MAPPER.valueToTree(snapshot)).toString();
    }
}
