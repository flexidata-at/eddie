// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.EnergySharingException;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.math.BigDecimal;
import static energy.eddie.regionconnector.at.eda.energysharing.OperatorMeasurementFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class EdaOperatorMeasurementXmlTest {
    private final EdaOperatorMeasurementXml parser = new EdaOperatorMeasurementXml(MAPPER);
    private EdaOperatorMeasurementXml.Parsed parse(String value) { return parser.parse(bytes(value), Instant.EPOCH); }

    @Test void preservesExactNativeSeriesIntervalsQualityAndProvenance() {
        var parsed = parse(xml("M1"));
        var document = parsed.document();
        var reading = document.readings().getFirst();
        assertEquals(COMMUNITY, parsed.communityId());
        assertEquals("M1", document.sourceMessageId());
        assertEquals("1", document.nativeParameters().path("Sequence").asText());
        assertEquals(Instant.parse("2024-03-29T23:00:00Z"), reading.start());
        assertEquals(Instant.parse("2024-03-30T23:00:00Z"), reading.end());
        assertEquals(new BigDecimal("36.770000"), reading.value());
        assertEquals("1-1:1.9.0 G.01", reading.valueTag());
        assertEquals("METERED_IMPORT", reading.nativeParameters().path("semanticRole").asText());
        assertEquals("L1", reading.quality());
        assertEquals("D", reading.interval());
        assertEquals("KWH", reading.unit());
        assertEquals("36.770000", reading.nativeParameters().path("BQ").asText());
        assertEquals(64, document.rawPayloadSha256().length());
    }

    @Test void duplicateFlagAndNamespacePrefixDoNotChangeSemanticIdentityButValuesDo() {
        String source = xml("M1");
        var duplicate = parse(source.replace("Duplicate=\"false\"", "Duplicate=\"true\"").replace("cp:", "x:").replace("xmlns:cp", "xmlns:x"));
        assertEquals(parse(source).fingerprint(), duplicate.fingerprint());
        assertNotEquals(parse(source).document().rawPayloadSha256(), duplicate.document().rawPayloadSha256());
        assertNotEquals(parse(source).fingerprint(), parse(source.replace("36.770000", "36.780000")).fingerprint());
        assertEquals(parse(source).fingerprint(), parse(source.replace("36.770000", "<![CDATA[36.770000]]>")).fingerprint());
        assertNotEquals(parse(source).fingerprint(), parse(source.replace("36.770000", "<![CDATA[36.780000]]>")).fingerprint());
    }

    @Test void dstAndVariableIntervalsUseNativeBoundariesRatherThanFixedStepGeneration() {
        String dst = xml("DST").replace("2024-03-30T00:00:00+01:00", "2024-03-31T00:00:00+01:00")
                .replace("2024-03-31T00:00:00+01:00</cp:MeteringPeriodEnd>", "2024-04-01T00:00:00+02:00</cp:MeteringPeriodEnd>")
                .replace("2024-03-31T00:00:00+01:00</cp:DTT>", "2024-04-01T00:00:00+02:00</cp:DTT>");
        var reading = parse(dst).document().readings().getFirst();
        assertEquals(23, java.time.Duration.between(reading.start(), reading.end()).toHours());
        assertEquals("V", parse(xml("V").replace(">D</cp:MeteringIntervall>", ">V</cp:MeteringIntervall>"))
                .document().readings().getFirst().interval());
    }

    @Test void unknownCodeAndLegacyQualityRemainOriginalWithoutFinalQualityInference() {
        var reading = parse(xml("M1").replace("1-1:1.9.0 G.01", "UNRECOGNIZED").replace(">L1<", ">03<"))
                .document().readings().getFirst();
        assertEquals("UNRECOGNIZED", reading.valueTag());
        assertEquals("UNKNOWN", reading.nativeParameters().path("semanticRole").asText());
        assertEquals("03", reading.quality());
    }

    @Test void schemaNamespaceMissingFieldOutOfRangeAndExternalEntitiesFailClosed() {
        String badNamespace = xml("M1").replace("consumptionrecord/01p41", "consumptionrecord/01p42");
        String missing = xml("M1").replace("<cp:MM>L1</cp:MM>", "");
        String range = xml("M1").replace("2024-03-30T00:00:00+01:00</cp:DTF>", "2024-03-28T00:00:00+01:00</cp:DTF>");
        String entity = "<!DOCTYPE cp:ConsumptionRecord [<!ENTITY external SYSTEM 'file:///no-such-file'>]>" + xml("M1");
        for (String invalid : java.util.List.of(badNamespace, missing, range, entity)) {
            assertThrows(EnergySharingException.class, () -> parse(invalid));
        }
    }

    @Test void overlappingSameSeriesIsRejectedWhileDistinctObisSeriesRemainSeparate() {
        String source = xml("M1");
        String data = source.substring(source.indexOf("<cp:EnergyData"), source.indexOf("</cp:EnergyData>") + "</cp:EnergyData>".length());
        String overlap = source.replace("</cp:Energy>", data + "</cp:Energy>");
        assertThrows(EnergySharingException.class, () -> parse(overlap));
        String distinct = source.replace("</cp:Energy>", data.replace("G.01", "G.01T") + "</cp:Energy>");
        assertEquals(2, parse(distinct).document().readings().size());
    }
}
