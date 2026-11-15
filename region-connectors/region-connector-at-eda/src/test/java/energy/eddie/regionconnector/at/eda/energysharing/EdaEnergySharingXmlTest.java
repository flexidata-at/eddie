// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class EdaEnergySharingXmlTest {
    static final String EC="AT0000000000000000000000000000001";
    static final String METER="AT0000000000000000000000000000002";
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-15T09:30:00Z"),ZoneOffset.UTC);
    private final JsonMapper mapper=JsonMapper.builder().build();
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
        assertThrows(EnergySharingException.class,()->EdaEnergySharingXml.create(new EnergySharingRequest("r","c","PP123456",EC,"JOIN",EdaEnergySharingXml.VERSION,p),"AT001000","ES12345",CLOCK));
        assertThrows(EnergySharingException.class,()->EdaEnergySharingXml.create(request("JOIN","""
          {"meteringPointId":"%s","start":"2026-11-16","energyDirection":"CONSUMPTION","participationFactor":0}
          """.formatted(METER)),"AT001000","ES12345",CLOCK));
    }
    @Test void rejectsDoctypeAndWrongNamespace() {
        assertThrows(EnergySharingException.class,()->EdaEnergySharingXml.parse("<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><foo>&xxe;</foo>".getBytes(StandardCharsets.UTF_8)));
        assertThrows(EnergySharingException.class,()->EdaEnergySharingXml.validate("<ECMPList xmlns='urn:wrong'/>".getBytes(StandardCharsets.UTF_8),"ECMPList_01p20.xsd"));
    }
}
