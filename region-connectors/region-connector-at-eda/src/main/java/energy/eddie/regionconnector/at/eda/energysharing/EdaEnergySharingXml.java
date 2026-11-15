// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.*;
import energy.eddie.regionconnector.at.eda.utils.CMRequestId;
import org.w3c.dom.*;
import tools.jackson.databind.JsonNode;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import org.jspecify.annotations.Nullable;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static energy.eddie.api.agnostic.energysharing.EnergySharingException.Code.INVALID_REQUEST;

/** Version-pinned wire mapping; XML is built with DOM so user fields are always escaped. */
public final class EdaEnergySharingXml {
    public static final String PROFILE="AT-EDA-BEM";
    public static final String VERSION="2026-10-05";
    public static final ZoneId VIENNA=ZoneId.of("Europe/Vienna");
    private static final String CT="http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20";
    public record Message(String code,String schemaSet,String version,byte[] xml) {}
    private EdaEnergySharingXml() {}

    public static Message create(EnergySharingRequest r,String dso,String id,Clock clock) {
        var p=r.parameters();
        if(p==null||!p.isObject()) throw invalid("parameters must be an object");
        var today=LocalDate.now(clock.withZone(VIENNA));
        String code,set,root,ns,version;
        switch(r.operation()) {
            case "JOIN" -> {code="ANFORDERUNG_ECON";set="EC_REQ_ONL_02.40";root="CMRequest";version="01.30";ns="customerconsent/cmrequest/01p30";}
            case "LEAVE" -> {code="AUFHEBUNG_CCMS";set="CM_REV_SP_01.30";root="CMRevoke";version="01.10";ns="customerconsent/cmrevoke/01p10";}
            case "CHANGE_PARTICIPATION_FACTOR" -> {code="ANFORDERUNG_CPF";set="EC_PRTFACT_CHANGE_01.10";root="ECMPList";version="01.20";ns="customerprocesses/ecmplist/01p20";}
            case "CHANGE_ALLOCATION" -> {code="ANFORDERUNG_ECC";set="EC_PRT_CHANGE_02.10";root="ECMPList";version="01.20";ns="customerprocesses/ecmplist/01p20";}
            case "SYNC_MASTER_DATA" -> {code="ANFORDERUNG_ECP";set="EC_PODLIST_02.10";root="CPRequest";version="01.12";ns="customerprocesses/cprequest/01p12";}
            default -> throw invalid("Unsupported EDA operation");
        }
        ns="http://www.ebutilities.at/schemata/"+ns;
        try {
            var doc=factory().newDocumentBuilder().newDocument();
            var top=doc.createElementNS(ns,root); doc.appendChild(top);
            top.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,"xmlns:ct",CT);
            var mp=child(top,ns,"MarketParticipantDirectory",null);
            mp.setAttribute("SchemaVersion",version);mp.setAttribute("DocumentMode","PROD");mp.setAttribute("Duplicate","false");
            var routing=child(mp,CT,"ct:RoutingHeader",null);
            address(routing,"Sender",r.operatorId());address(routing,"Receiver",dso);
            child(routing,CT,"ct:DocumentCreationDateTime",OffsetDateTime.now(clock.withZone(VIENNA)).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            child(mp,CT,"ct:Sector","01");child(mp,ns,"MessageCode",code);
            var pd=child(top,ns,"ProcessDirectory",null);
            var common=root.equals("ECMPList")?ns:CT;
            child(pd,common,common.equals(CT)?"ct:MessageId":"MessageId",id);
            child(pd,common,common.equals(CT)?"ct:ConversationId":"ConversationId",id);
            if(root.equals("CMRequest")) {
                child(pd,ns,"ProcessDate",today.toString());
                child(pd,ns,"MeteringPoint",identifier(p,"meteringPointId",33));
                child(pd,ns,"CMRequestId",new CMRequestId(id).toString());
                var req=child(pd,ns,"CMRequest",null);
                child(req,ns,"ReqDatType","EnergyCommunityRegistration");
                var start=date(p,"start"); future(start,today.plusDays(1),workdayLimit(today));
                if(p.hasNonNull("end"))throw invalid("DateTo/end is forbidden for EC_REQ_ONL");
                child(req,ns,"DateFrom",start.toString());
                optionalEnum(req,ns,p,"meteringInterval","MeteringIntervall",Set.of("QH","H","D","V"));
                optionalEnum(req,ns,p,"transmissionCycle","TransmissionCycle",Set.of("D","M","V"));
                child(req,ns,"ECID",r.communityId());
                child(req,ns,"ECPartFact",factor(p,"participationFactor",true));
                if(p.hasNonNull("allocationFactor")) {
                    if(!"S".equals(p.path("distributionModel").asText()))throw invalid("Static allocation requires distributionModel S");
                    if(!"CONSUMPTION".equals(text(p,"energyDirection")))throw invalid("Static allocation is a consumption-meter parameter");
                    child(req,ns,"ECShare",factor(p,"allocationFactor",false));
                }
                child(req,ns,"EnergyDirection",enumValue(p,"energyDirection",Set.of("CONSUMPTION","GENERATION")));
                optional(req,ns,p,"purpose","Purpose",100);
            } else if(root.equals("CMRevoke")) {
                child(pd,ns,"ConsentId",identifier(p,"consentId",35));
                child(pd,ns,"MeteringPoint",identifier(p,"meteringPointId",33));
                var end=date(p,"end");future(end,today,workdayLimit(today));child(pd,ns,"ConsentEnd",end.toString());
                if(p.hasNonNull("reasonKey")) {
                    var key=text(p,"reasonKey");if(!key.matches("[0-9]"))throw invalid("reasonKey must be 0..9");child(pd,ns,"ReasonKey",key);
                }
                optional(pd,ns,p,"reason","Reason",50);
            } else if(root.equals("CPRequest")) {
                child(pd,CT,"ct:ProcessDate",today.toString());child(pd,CT,"ct:MeteringPoint",r.communityId());
                var ext=child(pd,ns,"Extension",null);
                var start=date(p,"start");var end=date(p,"end");if(end.isBefore(start))throw invalid("end must not precede start");
                child(ext,ns,"DateTimeFrom",start.atStartOfDay(VIENNA).toOffsetDateTime().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                child(ext,ns,"DateTimeTo",end.plusDays(1).atStartOfDay(VIENNA).toOffsetDateTime().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                child(ext,ns,"AssumptionOfCosts","false");
            } else {
                var start=date(p,"start");
                var time=LocalTime.now(clock.withZone(VIENNA));
                if(time.isBefore(LocalTime.of(9,0))||!time.isBefore(LocalTime.of(17,0))||!start.equals(today.plusDays(1)))
                    throw invalid("EDA factor changes must arrive 09:00–17:00 Vienna time for the next calendar day");
                child(pd,ns,"ProcessDate",start.toString());child(pd,ns,"ECID",r.communityId());
                optionalEnum(pd,ns,p,"communityType","ECType",Set.of("GC","RC_R","RC_L","CC","SC","PP"));
                optionalEnum(pd,ns,p,"distributionModel","ECDisModel",Set.of("D","S"));
                optionalEnum(pd,ns,p,"zoneLevel","ECZoneLevel",Set.of("1","2","3","4","5","6","7"));
                if(r.operation().equals("CHANGE_ALLOCATION")&&!"S".equals(p.path("distributionModel").asText()))
                    throw invalid("ECShare changes are only supported for existing static agreements");
                var meter=child(pd,ns,"MPListData",null);child(meter,ns,"MeteringPoint",identifier(p,"meteringPointId",33));
                if(p.hasNonNull("consentId"))child(meter,ns,"ConsentId",identifier(p,"consentId",35));
                var periods=p.path("timePeriods");
                if(!periods.isArray()||periods.isEmpty())throw invalid("timePeriods containing DateTo/DataType/DateActivate and all future nominal changes are required");
                if(!date(periods.get(0),"start").equals(start))throw invalid("First time period must start at the requested effective date");
                for(var period:periods)timePeriod(meter,ns,period,r.operation());
            }
            if(p.hasNonNull("additionalData")) {
                if(!Set.of("CMRequest","CPRequest").contains(root))throw invalid("AdditionalData is not supported by this process schema");
                var additional=p.path("additionalData");if(!additional.isArray()||additional.size()>1000)throw invalid("Invalid AdditionalData list");
                for(var entry:additional){var value=child(pd,ns,"AdditionalData",bounded(text(entry,"value"),120));value.setAttribute("Name",bounded(text(entry,"name"),40));}
            }
            var tf=TransformerFactory.newInstance();tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET,"");
            var out=new ByteArrayOutputStream();tf.newTransformer().transform(new DOMSource(doc),new StreamResult(out));
            validate(out.toByteArray(),root+"_"+version.replace(".","p")+".xsd");
            return new Message(code,set,set.substring(set.lastIndexOf('_')+1),out.toByteArray());
        } catch(EnergySharingException e) {throw e;} catch(Exception e) {throw invalid("Could not construct EDA message: "+e.getClass().getSimpleName());}
    }
    private static void timePeriod(Element parent,String ns,JsonNode p,String operation) {
        var el=child(parent,ns,"MPTimeData",null);var start=date(p,"start");var end=date(p,"end");
        if(end.isBefore(start))throw invalid("Time period end precedes start");
        child(el,ns,"DateFrom",start.toString());child(el,ns,"DateTo",end.toString());
        optionalEnum(el,ns,p,"energyDirection","EnergyDirection",Set.of("CONSUMPTION","GENERATION"));
        if(p.hasNonNull("participationFactor"))child(el,ns,"ECPartFact",factor(p,"participationFactor",true));
        else if(operation.equals("CHANGE_PARTICIPATION_FACTOR"))throw invalid("Each period needs participationFactor");
        child(el,ns,"DataType",bounded(text(p,"dataType"),30));
        optionalEnum(el,ns,p,"transmissionCycle","TransmissionCycle",Set.of("D","M","V"));
        optional(el,ns,p,"purpose","Purpose",100);optional(el,ns,p,"techCode","TechCode",10);optional(el,ns,p,"fuelCode","FuelCode",10);
        child(el,ns,"DateActivate",date(p,"activate").toString());
        if(p.hasNonNull("deactivate"))child(el,ns,"DateDeactivate",date(p,"deactivate").toString());
        if(p.hasNonNull("allocationFactor"))child(el,ns,"ECShare",factor(p,"allocationFactor",false));
        else if(operation.equals("CHANGE_ALLOCATION"))throw invalid("Each period needs allocationFactor");
        if(p.hasNonNull("calculatedShares"))throw invalid("calculatedShares are DSO response values, not caller-set allocation commands");
    }
    public static Document parse(byte[] xml) {
        try {return factory().newDocumentBuilder().parse(new ByteArrayInputStream(xml));}
        catch(Exception e){throw invalid("Invalid or unsafe XML");}
    }
    static void validate(byte[] xml,String schemaName) {
        try {
            var url=EdaEnergySharingXml.class.getResource("/energy-sharing/xsd/"+schemaName);
            if(url==null)throw new IllegalStateException("Missing pinned EDA schema "+schemaName);
            var factory=SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,"");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"file,jar");
            factory.newSchema(url).newValidator().validate(new StreamSource(new ByteArrayInputStream(xml)));
        }catch(Exception e){throw invalid("EDA schema validation failed: "+e.getMessage());}
    }
    private static DocumentBuilderFactory factory() throws Exception {
        var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");return f;
    }
    private static void address(Element routing,String name,String value){var e=child(routing,CT,"ct:"+name,null);e.setAttribute("AddressType","ECNumber");child(e,CT,"ct:MessageAddress",value);}
    private static Element child(Element parent,String ns,String name,@Nullable String value){var e=parent.getOwnerDocument().createElementNS(ns,name);if(value!=null)e.setTextContent(value);parent.appendChild(e);return e;}
    private static void optional(Element el,String ns,JsonNode p,String key,String wire,int limit){if(p.hasNonNull(key))child(el,ns,wire,bounded(text(p,key),limit));}
    private static void optionalEnum(Element el,String ns,JsonNode p,String key,String wire,Set<String> values){if(p.hasNonNull(key))child(el,ns,wire,enumValue(p,key,values));}
    private static String enumValue(JsonNode p,String key,Set<String> values){var v=text(p,key);if(!values.contains(v))throw invalid("Invalid "+key);return v;}
    public static String text(JsonNode p,String key){var n=p.get(key);if(n==null||n.isNull()||n.asText().isBlank())throw invalid("Missing "+key);return n.asText();}
    private static String bounded(String s,int n){if(s.isBlank()||s.length()>n)throw invalid("Invalid field length");return s;}
    private static String identifier(JsonNode p,String key,int max){var v=text(p,key);if(!v.matches("[A-Za-z0-9]{1,"+max+"}"))throw invalid("Invalid "+key);return v;}
    private static String factor(JsonNode p,String key,boolean participation){try{var x=new BigDecimal(text(p,key));if(x.compareTo(participation?BigDecimal.ONE:BigDecimal.ZERO)<0||x.compareTo(new BigDecimal("100"))>0||x.stripTrailingZeros().scale()>(participation?0:4))throw invalid("Invalid "+key);return x.toPlainString();}catch(NumberFormatException e){throw invalid("Invalid "+key);}}
    public static LocalDate date(JsonNode p,String key){try{return LocalDate.parse(text(p,key));}catch(DateTimeException e){throw invalid("Invalid "+key);}}
    private static void future(LocalDate date,LocalDate from,LocalDate to){if(date.isBefore(from)||date.isAfter(to))throw invalid("Date outside EDA permitted interval");}
    static LocalDate workdayLimit(LocalDate now){var d=now;for(int n=0;n<30;){d=d.plusDays(1);if(d.getDayOfWeek().getValue()<6&&!holiday(d))n++;}return d;}
    private static boolean holiday(LocalDate d){var md=MonthDay.from(d);if(Set.of("--01-01","--01-06","--05-01","--08-15","--10-26","--11-01","--12-08","--12-25","--12-26").contains(md.toString()))return true;int y=d.getYear(),a=y%19,b=y/100,c=y%100,dd=b/4,e=b%4,f=(b+8)/25,g=(b-f+1)/3,h=(19*a+b-dd-g+15)%30,i=c/4,k=c%4,l=(32+2*e+2*i-h-k)%7,m=(a+11*h+22*l)/451;var easter=LocalDate.of(y,(h+l-7*m+114)/31,(h+l-7*m+114)%31+1);return List.of(1,39,50,60).stream().anyMatch(n->d.equals(easter.plusDays(n)));}
    private static EnergySharingException invalid(String message){return new EnergySharingException(INVALID_REQUEST,message);}
}
