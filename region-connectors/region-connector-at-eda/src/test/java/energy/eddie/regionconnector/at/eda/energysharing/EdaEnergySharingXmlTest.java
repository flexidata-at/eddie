// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EdaEnergySharingXmlTest {
    static final String EC="AT0000000000000000000000000000001";
    static final String METER="AT0000000000000000000000000000002";
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-15T09:30:00Z"),ZoneOffset.UTC);
    private final JsonMapper mapper=JsonMapper.builder().build();
    @TempDir Path temporaryDirectory;
    private EnergySharingRequest request(String operation,String json){return new EnergySharingRequest("r","c","PP123456",EC,operation,EdaEnergySharingXml.VERSION,mapper.readTree(json));}
    @Test void createsJoinValidatedAgainstPublishedSchemaAndEscapesText() {
        var r=request("JOIN","""
            {"meteringPointId":"%s","start":"2026-11-16","energyDirection":"GENERATION","participationFactor":35,
             "purpose":"A & B <sharing>","meteringInterval":"QH","transmissionCycle":"D","additionalData":[{"name":"test","value":"A & B"}]}
            """.formatted(METER));
        var m=EdaEnergySharingXml.create(r,"AT001000","ES12345",CLOCK);
        assertEquals("EC_REQ_ONL_02.40",m.schemaSet());
        var xml=new String(m.xml(),StandardCharsets.UTF_8);assertTrue(xml.contains("A &amp; B &lt;sharing&gt;"));assertTrue(xml.contains("ECPartFact>35"));
    }
    @Test void createsLeaveAndCommunityQueryAgainstPublishedSchemas() {
        var leave=EdaEnergySharingXml.create(request("LEAVE","""
                {"meteringPointId":"%s","consentId":"CONSENT1","end":"2026-11-15","reasonKey":0,"reason":"Agreement ended"}
                """.formatted(METER)),"AT001000","ES12345",CLOCK);
        assertEquals("CM_REV_SP_01.30",leave.schemaSet());
        var query=EdaEnergySharingXml.create(request("SYNC_MASTER_DATA","""
                {"start":"2026-10-15","end":"2026-12-15"}
                """),"AT001000","ES12345",CLOCK);
        assertEquals("EC_PODLIST_02.10",query.schemaSet());
        assertTrue(new String(query.xml(),StandardCharsets.UTF_8).contains(EC));
    }
    @Test void createsBothChangeMessagesAndPreservesFuturePeriods() {
        var periods="""
          {"meteringPointId":"%s","start":"2026-11-16","distributionModel":"S","communityType":"GC","zoneLevel":"1",
            "timePeriods":[{"start":"2026-11-16","end":"2026-11-30","activate":"2026-01-01","dataType":"EnergyCommunityRegistration","participationFactor":50,"allocationFactor":22.1234},
                           {"start":"2026-12-01","end":"2099-12-31","activate":"2026-01-01","dataType":"EnergyCommunityRegistration","participationFactor":25,"allocationFactor":30}]}
          """.formatted(METER);
        var cpf=EdaEnergySharingXml.create(request("CHANGE_PARTICIPATION_FACTOR",periods),"AT001000","ES12345",CLOCK);
        assertEquals("EC_PRTFACT_CHANGE_01.10",cpf.schemaSet());
        var ecc=EdaEnergySharingXml.create(request("CHANGE_ALLOCATION",periods),"AT001000","ES12345",CLOCK);
        assertEquals("EC_PRT_CHANGE_02.10",ecc.schemaSet());
        assertEquals(2,EdaEnergySharingXml.parse(ecc.xml()).getElementsByTagNameNS("*","MPTimeData").getLength());
    }
    @Test void rejectsSchemaPermittedButProcessForbiddenJoinEndAndZeroFactor() {
        var p=mapper.readTree("""
          {"meteringPointId":"%s","start":"2026-11-16","end":"2026-11-30","energyDirection":"CONSUMPTION","participationFactor":25}
          """.formatted(METER));
        var forbiddenEnd = new EnergySharingRequest("r","c","PP123456",EC,"JOIN",EdaEnergySharingXml.VERSION,p);
        assertThrows(EnergySharingException.class,()->EdaEnergySharingXml.create(forbiddenEnd,"AT001000","ES12345",CLOCK));
        var zeroFactor = request("JOIN","""
          {"meteringPointId":"%s","start":"2026-11-16","energyDirection":"CONSUMPTION","participationFactor":0}
          """.formatted(METER));
        assertThrows(EnergySharingException.class,()->EdaEnergySharingXml.create(zeroFactor,"AT001000","ES12345",CLOCK));
    }
    @Test void rejectsDoctypeAndWrongNamespace() {
        var malicious = "<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><foo>&xxe;</foo>".getBytes(StandardCharsets.UTF_8);
        var wrongNamespace = "<ECMPList xmlns='urn:wrong'/>".getBytes(StandardCharsets.UTF_8);
        assertThrows(EnergySharingException.class,()->EdaEnergySharingXml.parse(malicious));
        assertThrows(EnergySharingException.class,()->EdaEnergySharingXml.validate(wrongNamespace,"ECMPList_01p20.xsd"));
    }

    @Test void schemaValidationCannotReadExternalEntitiesEvenForAnOtherwiseValidMessage() throws Exception {
        var external = temporaryDirectory.resolve("external-entity.txt");
        Files.writeString(external, "EXTERNAL_ENTITY_MUST_NOT_BE_READ");
        var parameters = validJoin().put("purpose", "ENTITY_PLACEHOLDER");
        var command = command("JOIN", parameters);
        var original = new String(EdaEnergySharingXml.create(command, "AT001000", "ES12345", CLOCK).xml(), StandardCharsets.UTF_8);
        var malicious = original.replace("<CMRequest ", "<!DOCTYPE CMRequest [<!ENTITY external SYSTEM '" + external.toUri() + "'>]><CMRequest ")
                .replace("ENTITY_PLACEHOLDER", "&external;").getBytes(StandardCharsets.UTF_8);
        assertTrue(new String(malicious, StandardCharsets.UTF_8).contains("<!DOCTYPE CMRequest"));
        var failure = assertThrows(EnergySharingException.class,
                () -> EdaEnergySharingXml.validate(malicious, "CMRequest_01p30.xsd"));
        assertEquals(EnergySharingException.Code.INVALID_REQUEST, failure.code());
    }

    @Test void messageBytesHaveValueSemanticsAndCannotBeMutatedThroughCallerArrays() {
        var expected = "private-wire-payload".getBytes(StandardCharsets.UTF_8);
        var supplied = expected.clone();
        var message = new EdaEnergySharingXml.Message("ANFORDERUNG_ECP", "EC_PODLIST_02.10", "02.10", supplied);
        supplied[0] = '!';
        var returned = message.xml();
        returned[1] = '?';
        assertArrayEquals(expected, message.xml());
        var equivalent = new EdaEnergySharingXml.Message("ANFORDERUNG_ECP", "EC_PODLIST_02.10", "02.10", expected);
        assertEquals(message, equivalent);
        assertEquals(message.hashCode(), equivalent.hashCode());
        for (var different : List.of(
                new EdaEnergySharingXml.Message("OTHER", "EC_PODLIST_02.10", "02.10", expected),
                new EdaEnergySharingXml.Message("ANFORDERUNG_ECP", "OTHER", "02.10", expected),
                new EdaEnergySharingXml.Message("ANFORDERUNG_ECP", "EC_PODLIST_02.10", "OTHER", expected),
                new EdaEnergySharingXml.Message("ANFORDERUNG_ECP", "EC_PODLIST_02.10", "02.10", supplied)))
            assertNotEquals(message, different);
        assertTrue(message.toString().contains("xmlBytes=" + expected.length));
        assertFalse(message.toString().contains("private-wire-payload"));
    }

    @Test void joinRulesRejectInvalidDatesFactorsIdentifiersAndNationalOptions() {
        var invalidParameters = List.of(
                validJoin().put("start", "2026-11-15"),
                validJoin().put("start", "2027-01-01"),
                validJoin().put("start", "not-a-date"),
                validJoin().put("participationFactor", 101),
                validJoin().put("participationFactor", "12.5"),
                validJoin().put("participationFactor", "not-a-number"),
                validJoin().put("meteringPointId", "invalid/identifier"),
                validJoin().putNull("meteringPointId"),
                validJoin().put("purpose", "x".repeat(101)),
                validJoin().put("energyDirection", "INVALID"),
                validJoin().put("transmissionCycle", "INVALID"),
                validJoin().put("allocationFactor", 20).put("distributionModel", "D"),
                validJoin().put("allocationFactor", 20).put("distributionModel", "S").put("energyDirection", "GENERATION"),
                validJoin().put("allocationFactor", "20.12345").put("distributionModel", "S"));
        for (var parameters : invalidParameters) {
            var command = command("JOIN", parameters);
            assertEquals(EnergySharingException.Code.INVALID_REQUEST,
                    assertThrows(EnergySharingException.class, () -> EdaEnergySharingXml.create(command, "AT001000", "ES12345", CLOCK)).code());
        }
    }

    @Test void staticJoinKeepsAllocationSeparateFromParticipationAndHonorsWorkingDayLimit() {
        var parameters = validJoin().put("start", "2026-12-29").put("allocationFactor", "22.1234").put("distributionModel", "S");
        var result = EdaEnergySharingXml.create(command("JOIN", parameters), "AT001000", "ES12345", CLOCK);
        var xml = new String(result.xml(), StandardCharsets.UTF_8);
        assertTrue(xml.contains("ECPartFact>25"));
        assertTrue(xml.contains("ECShare>22.1234"));
        assertEquals(LocalDate.of(2026, 12, 29), EdaEnergySharingXml.workdayLimit(LocalDate.of(2026, 11, 15)));
    }

    @Test void malformedAndOverflowingQueryRangesRemainInvalidRequests() {
        for (var json : List.of(
                "{\"start\":\"2026-11-16\",\"end\":\"2026-11-15\"}",
                "{\"start\":\"2026-11-15\",\"end\":\"+999999999-12-31\"}",
                "{\"start\":\"2026-11-15\",\"end\":null}")) {
            var command = request("SYNC_MASTER_DATA", json);
            assertEquals(EnergySharingException.Code.INVALID_REQUEST,
                    assertThrows(EnergySharingException.class, () -> EdaEnergySharingXml.create(command, "AT001000", "ES12345", CLOCK)).code());
        }
    }

    @Test void changeCommandsEnforceEffectiveTimeAndCompleteNominalPeriods() {
        var valid = validChange();
        var command = command("CHANGE_PARTICIPATION_FACTOR", valid);
        var beforeWindow = Clock.fixed(Instant.parse("2026-11-15T07:59:00Z"), ZoneOffset.UTC);
        var afterWindow = Clock.fixed(Instant.parse("2026-11-15T16:00:00Z"), ZoneOffset.UTC);
        assertThrows(EnergySharingException.class, () -> EdaEnergySharingXml.create(command, "AT001000", "ES12345", beforeWindow));
        assertThrows(EnergySharingException.class, () -> EdaEnergySharingXml.create(command, "AT001000", "ES12345", afterWindow));
        var noPeriods = validChange();
        noPeriods.remove("timePeriods");
        var emptyPeriods = validChange();
        emptyPeriods.putArray("timePeriods");
        var mismatchedStart = validChange();
        ((ObjectNode)mismatchedStart.path("timePeriods").get(0)).put("start", "2026-11-17");
        var invertedPeriod = validChange();
        ((ObjectNode)invertedPeriod.path("timePeriods").get(0)).put("end", "2026-11-15");
        var missingFactor = validChange();
        ((ObjectNode)missingFactor.path("timePeriods").get(0)).remove("participationFactor");
        var callerCalculated = validChange();
        ((ObjectNode)callerCalculated.path("timePeriods").get(0)).putArray("calculatedShares");
        for (var parameters : List.of(noPeriods, emptyPeriods, mismatchedStart, invertedPeriod, missingFactor, callerCalculated,
                validChange().put("start", "2026-11-15"))) {
            var invalid = command("CHANGE_PARTICIPATION_FACTOR", parameters);
            assertEquals(EnergySharingException.Code.INVALID_REQUEST,
                    assertThrows(EnergySharingException.class, () -> EdaEnergySharingXml.create(invalid, "AT001000", "ES12345", CLOCK)).code());
        }
    }

    @Test void additionalDataIsLimitedToSupportingProcessesAndPinnedSchemaMustExist() {
        var badAdditional = validJoin().put("additionalData", "not-an-array");
        var invalidJoin = command("JOIN", badAdditional);
        assertThrows(EnergySharingException.class, () -> EdaEnergySharingXml.create(invalidJoin, "AT001000", "ES12345", CLOCK));
        var leaveParameters = mapper.createObjectNode().put("meteringPointId", METER).put("consentId", "CONSENT1").put("end", "2026-11-15");
        leaveParameters.putArray("additionalData").addObject().put("name", "extra").put("value", "forbidden");
        var invalidLeave = command("LEAVE", leaveParameters);
        assertThrows(EnergySharingException.class, () -> EdaEnergySharingXml.create(invalidLeave, "AT001000", "ES12345", CLOCK));
        var bytes = "<unknown/>".getBytes(StandardCharsets.UTF_8);
        assertThrows(EnergySharingException.class, () -> EdaEnergySharingXml.validate(bytes, "Unavailable_99p99.xsd"));
    }

    private EnergySharingRequest command(String operation, ObjectNode parameters) {
        return new EnergySharingRequest("r", "c", "PP123456", EC, operation, EdaEnergySharingXml.VERSION, parameters);
    }

    private ObjectNode validJoin() {
        return mapper.createObjectNode().put("meteringPointId", METER).put("start", "2026-11-16")
                .put("energyDirection", "CONSUMPTION").put("participationFactor", 25);
    }

    private ObjectNode validChange() {
        var parameters = mapper.createObjectNode().put("meteringPointId", METER).put("start", "2026-11-16");
        parameters.putArray("timePeriods").addObject().put("start", "2026-11-16").put("end", "2026-12-31")
                .put("activate", "2026-01-01").put("dataType", "EnergyCommunityRegistration").put("participationFactor", 50);
        return parameters;
    }
}
