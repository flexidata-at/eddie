// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import de.ponton.xp.adapter.api.domainvalues.*;
import de.ponton.xp.adapter.api.messages.OutboundMessage;
import energy.eddie.api.agnostic.energysharing.*;
import energy.eddie.cim.v1_12.StandardCodingSchemeTypeList;
import energy.eddie.regionconnector.at.eda.persistence.EnergySharingRecord;
import energy.eddie.regionconnector.at.eda.ponton.messenger.PontonMessengerConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static energy.eddie.api.agnostic.energysharing.EnergySharingException.Code.*;

/** Durable, connection-scoped management of EDA market requests. */
@Component
public class EdaEnergySharingService implements RegionConnectorEnergySharingService {
    private static final Set<String> OPERATIONS=Set.of("JOIN","LEAVE","CHANGE_PARTICIPATION_FACTOR","CHANGE_ALLOCATION","SYNC_MASTER_DATA");
    private static final Set<String> TERMINAL=Set.of("COMPLETED","REJECTED");
    private final EdaEnergySharingConfiguration config;
    private final EdaEnergySharingStore store;
    private final PontonMessengerConnection transport;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public EdaEnergySharingService(EdaEnergySharingConfiguration config,EdaEnergySharingStore store,
                                  PontonMessengerConnection transport,ObjectMapper mapper) {
        this(config,store,transport,mapper,Clock.systemUTC());
    }
    EdaEnergySharingService(EdaEnergySharingConfiguration config,EdaEnergySharingStore store,
                           PontonMessengerConnection transport,ObjectMapper mapper,Clock clock) {
        this.config=config;this.store=store;this.transport=transport;this.mapper=mapper;this.clock=clock;
        transport.withEnergySharingHandler(new EnergySharingMessageHandler() {
            @Override public boolean handle(String code,byte[] xml){return receive(code,xml);}
            @Override public void transportStatus(String id,String state) {
                if(!Set.of("FAILED","CONFIG_ERROR","CONTENT_ERROR","TRANSMISSION_ERROR").contains(state))return;
                store.byMarketId(id).ifPresent(record->store.update(record.id(),payload->{
                    var data=(ObjectNode)mapper.readTree(payload);var old=readOperation(payload);if(TERMINAL.contains(old.status()))return payload;
                    data.set("operation",mapper.valueToTree(copy(old,"RECONCILIATION_REQUIRED",state,"Messenger reported a transport failure; reconcile before resending",old.result())));return data.toString();
                }));
            }
        });
    }
    @Override
    public EnergySharingCapabilities capabilities(String operatorId,String connectionId) {
        var binding=binding(operatorId,connectionId);var steps=new ArrayList<String>();
        if(binding.communities().isEmpty())steps.add("Configure the authorized EC-ID and its DSO once. EDA publishes no operator-accessible EC_LIST discovery process.");
        if(!binding.partnerConfigured()) {
            steps.add("Verify the operator's ebUtilities RC/CC/PP role, EC-ID and relevant DSO contract.");
            steps.add("Complete the EDA platform contract, mandate to the IT service and Messenger routing. Portal credentials alone are insufficient.");
            steps.add("Have the administrator create/import the local operator partner and remote DSO certificates/agreement in PONTON X/P.");
            steps.add("Activate EC_REQ_ONL_02.40, EC_PODLIST_02.10, EC_PRTFACT_CHANGE_01.10, EC_PRT_CHANGE_02.10 and CM_REV_SP_01.30 as applicable.");
            steps.add("Configure the EDDIE adapter, verify its connection-to-operator authorization, then test transport and synchronize master data.");
        }
        if(!transport.supportsEnergySharing())steps.add("Enable the production PONTON Messenger transport; the development REST replacement cannot send these market requests.");
        boolean ready=steps.isEmpty()&&transport.messengerStatus().ok();
        if(steps.isEmpty()&&!ready)steps.add("Restore the PONTON Messenger connection before submitting requests.");
        return new EnergySharingCapabilities(EdaEnergySharingXml.PROFILE,EdaEnergySharingXml.VERSION,operatorId,ready,OPERATIONS,schemas(),steps,"AT");
    }
    @Override
    public EnergySharingOperation submit(EnergySharingRequest request) {
        var binding=binding(request.operatorId(),request.connectionId());
        if(!EdaEnergySharingXml.VERSION.equals(request.profileVersion()))throw error(INVALID_REQUEST,"Unsupported national profile version");
        if(!OPERATIONS.contains(request.operation()))throw error(UNSUPPORTED,"Operation not supported by the Austrian profile");
        if(request.requestId().isBlank())throw error(INVALID_REQUEST,"requestId is required");
        validateParameters(request);
        var id=key(request);var fingerprint=hash(canonical(mapper.valueToTree(request)));
        var existing=store.find(id);
        if(existing.isPresent()) {
            var saved=replay(existing.get(),fingerprint);
            if(request.communityId().isBlank()&&saved.result().path("children").isArray()) {
                for(var child:saved.result().path("children"))submit(mapper.treeToValue(child,EnergySharingRequest.class));
                return operation(request.requestId(),request.connectionId(),request.operatorId(),"").orElseThrow();
            }
            return saved;
        }
        if(!capabilities(request.operatorId(),request.connectionId()).transportReady())throw error(NOT_READY,"PONTON operator partner/connection setup is incomplete");
        if(request.communityId().isBlank())return discover(request,binding,fingerprint);
        String dso=community(binding,request.communityId());
        if(request.parameters().hasNonNull("permissionAdministratorId")&&!dso.equals(request.parameters().path("permissionAdministratorId").asText()))
            throw error(FORBIDDEN,"DSO does not match the authorized community binding");
        if(request.operation().equals("CHANGE_ALLOCATION")||request.parameters().hasNonNull("allocationFactor")) {
            var snapshot=store.find(snapshotKey(request));
            if(snapshot.isEmpty()||!"S".equals(mapper.readTree(snapshot.get().payload()).path("snapshot").path("allocationMethod").asText()))
                throw error(INVALID_REQUEST,"A confirmed existing static community snapshot is required for ECShare");
        }
        String marketId="ES"+UUID.randomUUID().toString().replace("-","");
        var message=EdaEnergySharingXml.create(withSyncDefaults(request),dso,marketId,clock);
        if(request.operation().startsWith("CHANGE_"))reserveDailyChange(request,id);
        var operation=new EnergySharingOperation(request.requestId(),request.connectionId(),request.operatorId(),request.communityId(),request.operation(),"QUEUED",clock.instant(),clock.instant(),marketId,null,null,mapper.createObjectNode());
        var data=mapper.createObjectNode();data.set("request",mapper.valueToTree(withSyncDefaults(request)));data.set("operation",mapper.valueToTree(operation));data.putArray("events");
        try {store.insert(new EnergySharingRecord(id,scope(request.operatorId(),request.connectionId()),"OPERATION",fingerprint,marketId,data.toString()));}
        catch(RuntimeException e){var race=store.find(id);if(race.isPresent())return replay(race.get(),fingerprint);throw e;}
        try {
            var meta=new OutboundMetaData.OutboundMetaDataBuilder().setSenderId(new SenderId(request.operatorId())).setReceiverId(new ReceiverId(dso))
                    .setMessageType(new MessageType.MessageTypeBuilder().setName(new MessageTypeName(message.code())).setSchemaSet(new SchemaSet(message.schemaSet()))
                            .setVersion(new MessageTypeVersion(message.version())).setMimeType(new MimeType("text/xml")).build()).build();
            transport.sendEnergySharing(OutboundMessage.newBuilder().setInputStream(new ByteArrayInputStream(message.xml())).setOutboundMetaData(meta).build());
            status(id,"SUBMITTED",null,"Submitted to Messenger; awaiting market response");
        } catch(Exception e) {
            status(id,"RECONCILIATION_REQUIRED",null,"Transport result is uncertain; reconcile before retrying. The same request ID will not resend.");
        }
        return operation(request.requestId(),request.connectionId(),request.operatorId(),request.communityId()).orElseThrow();
    }
    private EnergySharingOperation discover(EnergySharingRequest request,EdaEnergySharingConfiguration.Binding binding,String fingerprint) {
        if(!request.operation().equals("SYNC_MASTER_DATA"))throw error(INVALID_REQUEST,"communityId is required");
        var children=mapper.createArrayNode();int index=0;
        for(String communityId:new TreeSet<>(binding.communities().keySet())) {
            var child=new EnergySharingRequest(request.requestId()+"-"+ ++index,request.connectionId(),request.operatorId(),communityId,request.operation(),request.profileVersion(),request.parameters());
            EdaEnergySharingXml.create(withSyncDefaults(child),community(binding,communityId),"ES"+UUID.randomUUID().toString().replace("-",""),clock);
            children.add(mapper.valueToTree(child));
        }
        var now=clock.instant();var result=mapper.createObjectNode();result.set("children",children);
        var operation=new EnergySharingOperation(request.requestId(),request.connectionId(),request.operatorId(),"",request.operation(),"SUBMITTED",now,now,null,null,"Synchronizing configured, authorized EC-IDs",result);
        var data=mapper.createObjectNode();data.set("request",mapper.valueToTree(request));data.set("operation",mapper.valueToTree(operation));data.putArray("events");
        try {store.insert(new EnergySharingRecord(key(request),scope(request.operatorId(),request.connectionId()),"OPERATION",fingerprint,"GROUP"+hash(key(request)).substring(0,30),data.toString()));}
        catch(RuntimeException e){return replay(store.find(key(request)).orElseThrow(()->e),fingerprint);}
        for(var child:children) {
            try {submit(mapper.treeToValue(child,EnergySharingRequest.class));}
            catch(RuntimeException e) {
                store.update(key(request),payload->{var changed=(ObjectNode)mapper.readTree(payload);changed.set("operation",mapper.valueToTree(copy(readOperation(payload),"RECONCILIATION_REQUIRED",null,"Some child synchronizations could not start; inspect child operations",result)));return changed.toString();});
                break;
            }
        }
        return operation(request.requestId(),request.connectionId(),request.operatorId(),"").orElseThrow();
    }
    @Override
    public Optional<EnergySharingOperation> operation(String requestId,String connectionId,String operatorId,String communityId) {
        var binding=binding(operatorId,connectionId);if(!communityId.isBlank())community(binding,communityId);
        return store.find(key(operatorId,connectionId,communityId,requestId)).map(r->{
            var op=readOperation(r.payload());var children=op.result().path("children");
            if(children.isArray()&&!children.isEmpty()) {
                var values=mapper.createArrayNode();boolean complete=true;boolean failed=false;
                for(var child:children) {
                    var found=operation(child.path("requestId").asText(),connectionId,operatorId,child.path("communityId").asText());
                    if(found.isEmpty())complete=false;else {var value=found.get();values.add(mapper.valueToTree(value));complete&="COMPLETED".equals(value.status());failed|=Set.of("REJECTED","RECONCILIATION_REQUIRED").contains(value.status());}
                }
                var result=(ObjectNode)op.result().deepCopy();result.set("operations",values);
                return copy(op,complete?"COMPLETED":failed?"RECONCILIATION_REQUIRED":op.status(),op.nativeCode(),op.message(),result);
            }
            return op;
        });
    }
    @Override
    public List<EnergySharingCommunitySnapshot> communities(String operatorId,String connectionId) {
        var binding=binding(operatorId,connectionId);
        return store.records(scope(operatorId,connectionId),"SNAPSHOT").stream().map(r->mapper.treeToValue(mapper.readTree(r.payload()).path("snapshot"),EnergySharingCommunitySnapshot.class))
                .filter(s->binding.communities().containsKey(s.communityId())).toList();
    }
    boolean receive(String messageCode,byte[] xml) {
        if(Set.of("AUFHEBUNG_CCMC","AUFHEBUNG_CCMI").contains(messageCode)) {
            receiveRevocation(messageCode,xml);
            return false; // The existing permission pipeline also needs the revocation.
        }
        if(!Set.of("ANTWORT_ECON","ZUSTIMMUNG_ECON","ABSCHLUSS_ECON","ABLEHNUNG_ECON","ABBRUCH_ECON","ANTWORT_CPF","ABLEHNUNG_CPF","ANTWORT_ECC","ABLEHNUNG_ECC","SENDEN_ECP","ABLEHNUNG_ECP","ANTWORT_CCMS","ABLEHNUNG_CCMS").contains(messageCode))return false;
        var root=EdaEnergySharingXml.parse(xml).getDocumentElement();
        String schema=switch(root.getLocalName()){case "ECMPList"->"ECMPList_01p20.xsd";case "CMNotification"->"CMNotification_01p20.xsd";case "CPNotification"->"CPNotification_01p13.xsd";default->throw error(INVALID_REQUEST,"Unexpected market payload");};
        var pd=one(root,"ProcessDirectory");
        var found=store.byMarketId(text(pd,"ConversationId"));if(found.isEmpty())return false;
        EdaEnergySharingXml.validate(xml,schema);
        var record=found.get();var data=mapper.readTree(record.payload());var req=mapper.treeToValue(data.path("request"),EnergySharingRequest.class);
        var binding=binding(req.operatorId(),req.connectionId());var dso=community(binding,req.communityId());
        var mp=one(root,"MarketParticipantDirectory");var routing=one(mp,"RoutingHeader");
        if(!messageCode.equals(text(mp,"MessageCode"))||!dso.equals(text(one(routing,"Sender"),"MessageAddress"))||!req.operatorId().equals(text(one(routing,"Receiver"),"MessageAddress")))
            throw error(FORBIDDEN,"Market reply routing does not match the authorized operation");
        String ec=textOrEmpty(pd,"ECID");if(!ec.isEmpty()&&!ec.equals(req.communityId()))throw error(FORBIDDEN,"Market reply community mismatch");
        if(!allowedReply(req.operation(),messageCode))throw error(INVALID_REQUEST,"Reply does not belong to this operation");
        var expectedMeter=req.parameters().path("meteringPointId").asText("");
        for(var meter:descendants(pd,"MeteringPoint"))if(!expectedMeter.isEmpty()&&!expectedMeter.equals(meter.getTextContent()))throw error(FORBIDDEN,"Market reply meter mismatch");
        var messageId=text(pd,"MessageId");var result=mapper.createObjectNode();result.put("messageCode",messageCode);result.put("nativeXml",new String(xml,StandardCharsets.UTF_8));result.put("countryCode","AT");
        if("CMNotification".equals(root.getLocalName())) {
            var consentIds=descendants(pd,"ConsentId").stream().map(Element::getTextContent).map(String::strip).filter(value->!value.isEmpty()).distinct().toList();
            if(consentIds.size()==1)result.put("consentId",consentIds.getFirst());
            var portalUrl=textOrEmpty(pd,"PortalApprovalURL").strip();
            if(!portalUrl.isEmpty()) {
                try {
                    var uri=URI.create(portalUrl);
                    if(uri.isAbsolute()&&"https".equalsIgnoreCase(uri.getScheme())&&uri.getHost()!=null&&!uri.getHost().isBlank()&&uri.getRawUserInfo()==null)
                        result.put("portalApprovalUrl",portalUrl);
                } catch(IllegalArgumentException ignored) {
                    // Retain the original response for reconciliation, but never expose an unsafe approval action.
                }
            }
        }
        String state=messageCode.startsWith("ABLEHNUNG")?"REJECTED":messageCode.equals("ABBRUCH_ECON")?"REJECTED":messageCode.equals("ZUSTIMMUNG_ECON")?"ACKNOWLEDGED":messageCode.equals("ANTWORT_ECON")?"ACKNOWLEDGED":"COMPLETED";
        var codes=descendants(pd,"ResponseCode");String nativeCode=codes.isEmpty()?"":codes.getFirst().getTextContent();
        store.update(record.id(),payload->{
            var current=(ObjectNode)mapper.readTree(payload);var events=(ArrayNode)current.path("events");
            for(var event:events)if(messageId.equals(event.path("messageId").asText()))return payload;
            var old=readOperation(payload);
            if(messageCode.equals("SENDEN_ECP")&&!TERMINAL.contains(old.status())) {
                if(!ec.equals(req.communityId()))throw error(INVALID_REQUEST,"Authoritative snapshot must identify the requested EC-ID");
                persistSnapshot(req,root,xml,old.createdAt(),messageId);
            }
            var event=mapper.createObjectNode();event.put("messageId",messageId);event.put("receivedAt",clock.instant().toString());event.set("response",result);events.add(event);
            if(!TERMINAL.contains(old.status())&&!("ACKNOWLEDGED".equals(old.status())&&messageCode.equals("ANTWORT_ECON")))current.set("operation",mapper.valueToTree(copy(old,state,nativeCode,"Received "+messageCode,result)));
            return current.toString();
        });
        if(state.equals("COMPLETED")&&!req.operation().equals("SYNC_MASTER_DATA")) {
            try {submit(new EnergySharingRequest("reconcile-"+record.marketId(),req.connectionId(),req.operatorId(),req.communityId(),"SYNC_MASTER_DATA",req.profileVersion(),mapper.createObjectNode()));}
            catch(RuntimeException e) {
                store.update(record.id(),payload->{var current=(ObjectNode)mapper.readTree(payload);((ObjectNode)current.path("operation").path("result")).put("reconciliationPending",true);return current.toString();});
            }
        }
        return true;
    }
    private void persistSnapshot(EnergySharingRequest req,Element root,byte[] xml,Instant requestedAt,String revision) {
        var range=withSyncDefaults(req).parameters();var rangeStart=EdaEnergySharingXml.date(range,"start");var rangeEnd=EdaEnergySharingXml.date(range,"end");
        var currentDate=LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA));
        if(currentDate.isBefore(rangeStart)||currentDate.isAfter(rangeEnd))return; // Historical queries are available in operation.result, never replace the current roster.
        var pd=one(root,"ProcessDirectory");var meters=new ArrayList<EnergySharingMeteringPoint>();var dso=community(binding(req.operatorId(),req.connectionId()),req.communityId());
        for(var member:children(pd,"MPListData")) {
            String meter=text(member,"MeteringPoint");var consent=textOrEmpty(member,"ConsentId");
            var periods=children(member,"MPTimeData");
            var nativeParams=mapper.createObjectNode();nativeParams.put("consentId",consent);nativeParams.put("countryCode","AT");
            var nativePeriods=nativeParams.putArray("timePeriods");var today=LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA));
            @Nullable Element current=null;
            for(var period:periods) {
                var value=mapper.createObjectNode();
                var names=Map.ofEntries(Map.entry("DateFrom","start"),Map.entry("DateTo","end"),Map.entry("DateActivate","activate"),Map.entry("DateDeactivate","deactivate"),Map.entry("DataType","dataType"),Map.entry("ECPartFact","participationFactor"),Map.entry("ECShare","allocationFactor"),Map.entry("EnergyDirection","energyDirection"),Map.entry("TransmissionCycle","transmissionCycle"),Map.entry("Purpose","purpose"),Map.entry("TechCode","techCode"),Map.entry("FuelCode","fuelCode"));
                for(var n:children(period,null)) {
                    var field=names.get(n.getLocalName());
                    if(field!=null){if(Set.of("participationFactor","allocationFactor").contains(field))value.put(field,new BigDecimal(n.getTextContent()));else value.put(field,n.getTextContent());}
                }
                var calculated=value.putArray("calculatedShares");
                for(var share:children(period,"ECShC")){var v=mapper.createObjectNode();v.put("start",text(share,"DateFrom"));v.put("end",text(share,"DateTo"));v.put("allocationFactor",new BigDecimal(text(share,"ECShareCalc")));calculated.add(v);}
                nativePeriods.add(value);
                var from=Objects.requireNonNull(localDate(period,"DateFrom"));var until=Objects.requireNonNull(localDate(period,"DateTo"));
                if(!today.isBefore(from)&&!today.isAfter(until)){if(current!=null)throw error(INVALID_REQUEST,"Overlapping authoritative time periods");current=period;}
            }
            if(current==null) {
                meters.add(new EnergySharingMeteringPoint(meter,dso,null,null,null,null,null,"INACTIVE_FOR_CURRENT_DATE",nativeParams,StandardCodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME.value()));
            } else {
                meters.add(new EnergySharingMeteringPoint(meter,dso,nullable(textOrEmpty(current,"EnergyDirection")),decimal(current,"ECPartFact"),decimal(current,"ECShare"),localDate(current,"DateFrom"),localDate(current,"DateTo"),"CONFIRMED",nativeParams,StandardCodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME.value()));
            }
        }
        var nativeParams=mapper.createObjectNode();nativeParams.put("countryCode","AT");nativeParams.put("communityType",textOrEmpty(pd,"ECType"));nativeParams.put("zoneLevel",textOrEmpty(pd,"ECZoneLevel"));nativeParams.put("nativeXml",new String(xml,StandardCharsets.UTF_8));nativeParams.set("requestedRange",withSyncDefaults(req).parameters());
        var snapshot=new EnergySharingCommunitySnapshot(req.communityId(),req.operatorId(),req.connectionId(),revision,clock.instant(),true,null,nullable(textOrEmpty(pd,"ECDisModel")),meters,nativeParams);
        var data=mapper.createObjectNode();data.put("requestedAt",requestedAt.toString());data.set("snapshot",mapper.valueToTree(snapshot));
        data.put("authorityCreatedAt",text(one(one(root,"MarketParticipantDirectory"),"RoutingHeader"),"DocumentCreationDateTime"));
        var id=snapshotKey(req);var existing=store.find(id);
        if(existing.isEmpty()) {
            try{store.insert(new EnergySharingRecord(id,scope(req.operatorId(),req.connectionId()),"SNAPSHOT","", "SNAP"+id.substring(0,30),data.toString()));return;}
            catch(RuntimeException e){if(store.find(id).isEmpty())throw e;}
        }
        store.update(id,old->{
            if(!Instant.parse(mapper.readTree(old).path("requestedAt").asText()).isBefore(requestedAt))return old;
            var previousWrapper=mapper.readTree(old);
            if(previousWrapper.has("seenRevocations"))data.set("seenRevocations",previousWrapper.path("seenRevocations"));
            var previous=mapper.treeToValue(previousWrapper.path("snapshot"),EnergySharingCommunitySnapshot.class);
            var merged=mergeOutsideRange(previous.meteringPoints(),meters,rangeStart,rangeEnd);
            var assembled=new EnergySharingCommunitySnapshot(snapshot.communityId(),snapshot.operatorId(),snapshot.connectionId(),snapshot.revision(),snapshot.receivedAt(),true,snapshot.name(),snapshot.allocationMethod(),merged,snapshot.nativeParameters());
            data.set("snapshot",mapper.valueToTree(assembled));return data.toString();
        });
    }
    List<EnergySharingMeteringPoint> mergeOutsideRange(List<EnergySharingMeteringPoint> previous,List<EnergySharingMeteringPoint> incoming,LocalDate from,LocalDate to) {
        var points=new LinkedHashMap<String,EnergySharingMeteringPoint>();
        incoming.forEach(point->points.put(point.meteringPointId(),point));
        for(var old:previous) {
            var fresh=points.get(old.meteringPointId());
            var periods=new ArrayList<JsonNode>();
            if(fresh!=null)fresh.nativeParameters().path("timePeriods").forEach(periods::add);
            for(var oldPeriod:old.nativeParameters().path("timePeriods")) {
                var remaining=subtractPeriods(List.of(oldPeriod),from,to);
                for(var next:periods)remaining=subtractPeriods(remaining,EdaEnergySharingXml.date(next,"start"),EdaEnergySharingXml.date(next,"end"));
                periods.addAll(remaining);
            }
            if(periods.isEmpty())continue;
            periods.sort(Comparator.comparing(n->EdaEnergySharingXml.date(n,"start")));
            var source=fresh==null?old:fresh;var nativeParams=(ObjectNode)source.nativeParameters().deepCopy();var array=nativeParams.putArray("timePeriods");periods.forEach(array::add);
            points.put(old.meteringPointId(),pointFromPeriods(source,nativeParams));
        }
        return List.copyOf(points.values());
    }
    private List<JsonNode> subtractPeriods(List<JsonNode> periods,LocalDate from,LocalDate to) {
        var result=new ArrayList<JsonNode>();
        for(var period:periods) {
            var start=EdaEnergySharingXml.date(period,"start");var end=EdaEnergySharingXml.date(period,"end");
            if(end.isBefore(from)||start.isAfter(to)){result.add(period);continue;}
            if(start.isBefore(from)){var left=(ObjectNode)period.deepCopy();left.put("end",from.minusDays(1).toString());result.add(left);}
            if(end.isAfter(to)){var right=(ObjectNode)period.deepCopy();right.put("start",to.plusDays(1).toString());result.add(right);}
        }
        return result;
    }
    private EnergySharingMeteringPoint pointFromPeriods(EnergySharingMeteringPoint point,ObjectNode nativeParams) {
        var today=LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA));@Nullable JsonNode current=null;
        for(var p:nativeParams.path("timePeriods"))if(!today.isBefore(EdaEnergySharingXml.date(p,"start"))&&!today.isAfter(EdaEnergySharingXml.date(p,"end")))current=p;
        if(current==null)return new EnergySharingMeteringPoint(point.meteringPointId(),point.permissionAdministratorId(),null,null,null,null,null,"INACTIVE_FOR_CURRENT_DATE",nativeParams,point.codingScheme());
        return new EnergySharingMeteringPoint(point.meteringPointId(),point.permissionAdministratorId(),current.path("energyDirection").asText(null),
                current.hasNonNull("participationFactor")?current.path("participationFactor").decimalValue():null,current.hasNonNull("allocationFactor")?current.path("allocationFactor").decimalValue():null,
                EdaEnergySharingXml.date(current,"start"),EdaEnergySharingXml.date(current,"end"),"CONFIRMED",nativeParams,point.codingScheme());
    }
    private void status(String id,String state,@Nullable String nativeCode,String message) {
        store.update(id,payload->{var data=(ObjectNode)mapper.readTree(payload);var old=readOperation(payload);
            if(!"QUEUED".equals(old.status()))return payload;
            data.set("operation",mapper.valueToTree(copy(old,state,nativeCode,message,old.result())));return data.toString();});
    }
    private EnergySharingOperation replay(EnergySharingRecord record,String fingerprint){if(!record.fingerprint().equals(fingerprint))throw error(CONFLICT,"Request ID already used with a different payload");return readOperation(record.payload());}
    private EnergySharingOperation readOperation(String data){return mapper.treeToValue(mapper.readTree(data).path("operation"),EnergySharingOperation.class);}
    private EnergySharingOperation copy(EnergySharingOperation old,String state,@Nullable String code,@Nullable String message,JsonNode result){return new EnergySharingOperation(old.requestId(),old.connectionId(),old.operatorId(),old.communityId(),old.operation(),state,old.createdAt(),clock.instant(),old.marketMessageId(),code,message,result);}
    private EdaEnergySharingConfiguration.Binding binding(String operator,String connection){return config.bindings().stream().filter(b->operator.equals(b.operatorId())&&connection.equals(b.connectionId())).findFirst().orElseThrow(()->error(FORBIDDEN,"Operator is not authorized for this connection"));}
    private String community(EdaEnergySharingConfiguration.Binding b,String id){if(!b.communities().containsKey(id))throw error(FORBIDDEN,"Community is not authorized for this operator and connection");return Objects.requireNonNull(b.communities().get(id));}
    private String key(EnergySharingRequest r){return key(r.operatorId(),r.connectionId(),r.communityId(),r.requestId());}
    private String key(String o,String c,String ec,String r){return hash(mapper.writeValueAsString(List.of(o,c,ec,r)));}
    private String snapshotKey(EnergySharingRequest r){return key(r.operatorId(),r.connectionId(),r.communityId(),"__snapshot__");}
    private String scope(String o,String c){return hash(mapper.writeValueAsString(List.of(o,c)));}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String canonical(JsonNode node){if(node.isObject()){var out=mapper.createObjectNode();var fields=new TreeMap<String,JsonNode>();node.properties().forEach(e->fields.put(e.getKey(),e.getValue()));fields.forEach((k,v)->out.set(k,mapper.readTree(canonical(v))));return out.toString();}if(node.isArray()){var out=mapper.createArrayNode();for(var v:node)out.add(mapper.readTree(canonical(v)));return out.toString();}return node.toString();}
    private static boolean allowedReply(String operation,String code){return switch(operation){case "JOIN"->code.endsWith("_ECON");case "LEAVE"->code.endsWith("_CCMS");case "CHANGE_PARTICIPATION_FACTOR"->code.endsWith("_CPF");case "CHANGE_ALLOCATION"->code.endsWith("_ECC");case "SYNC_MASTER_DATA"->code.endsWith("_ECP");default->false;};}
    private static List<Element> children(Element parent,@Nullable String name){var out=new ArrayList<Element>();for(var n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e&&(name==null||name.equals(e.getLocalName())))out.add(e);return out;}
    private static List<Element> descendants(Element parent,String name){var out=new ArrayList<Element>();var nodes=parent.getElementsByTagNameNS("*",name);for(int i=0;i<nodes.getLength();i++)out.add((Element)nodes.item(i));return out;}
    private static Element one(Element parent,String name){var values=children(parent,name);if(values.size()!=1)throw error(INVALID_REQUEST,"Missing or duplicate XML field "+name);return values.getFirst();}
    private static String text(Element parent,String name){return one(parent,name).getTextContent();}
    private static String textOrEmpty(Element parent,String name){var values=children(parent,name);return values.isEmpty()?"":values.getFirst().getTextContent();}
    @Nullable private static String nullable(String v){return v.isBlank()?null:v;}
    @Nullable private static BigDecimal decimal(Element parent,String name){var value=textOrEmpty(parent,name);return value.isEmpty()?null:new BigDecimal(value);}
    @Nullable private static LocalDate localDate(Element parent,String name){var value=textOrEmpty(parent,name);return value.isEmpty()?null:LocalDate.parse(value.substring(0,10));}
    private static EnergySharingException error(EnergySharingException.Code code,String message){return new EnergySharingException(code,message);}
    private JsonNode schemas(){try(var stream=getClass().getResourceAsStream("/energy-sharing/at-eda-2026-10-05.json")){if(stream==null)throw new IllegalStateException("Missing national profile schema");var profiles=mapper.readTree(stream);var today=LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA));((ObjectNode)profiles.path("SYNC_MASTER_DATA").path("properties").path("start")).put("default",today.minusDays(30).toString());((ObjectNode)profiles.path("SYNC_MASTER_DATA").path("properties").path("end")).put("default",today.plusDays(30).toString());for(var operation:List.of("JOIN","CHANGE_PARTICIPATION_FACTOR","CHANGE_ALLOCATION"))((ObjectNode)profiles.path(operation).path("properties").path("start")).put("default",today.plusDays(1).toString());((ObjectNode)profiles.path("LEAVE").path("properties").path("end")).put("default",today.toString());return profiles;}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    private EnergySharingRequest withSyncDefaults(EnergySharingRequest r) {
        if(!"SYNC_MASTER_DATA".equals(r.operation()))return r;
        var p=(ObjectNode)r.parameters().deepCopy();var today=LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA));
        if(!p.hasNonNull("start"))p.put("start",today.minusDays(30).toString());
        if(!p.hasNonNull("end"))p.put("end",today.plusDays(30).toString());
        return new EnergySharingRequest(r.requestId(),r.connectionId(),r.operatorId(),r.communityId(),r.operation(),r.profileVersion(),p);
    }
    private void reserveDailyChange(EnergySharingRequest request,String operationKey) {
        var day=LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA)).toString();
        var id=key(request.operatorId(),"",request.communityId(),"DAILY:"+request.operation()+":"+request.parameters().path("meteringPointId").asText()+":"+day);
        var old=store.find(id);
        if(old.isPresent()){if(!old.get().fingerprint().equals(operationKey))throw error(CONFLICT,"A factor/allocation change for this meter and community is already reserved today");return;}
        try {store.insert(new EnergySharingRecord(id,scope(request.operatorId(),request.connectionId()),"DAILY_CHANGE",operationKey,"DAY"+id.substring(0,30),"{}"));}
        catch(RuntimeException e){if(store.find(id).filter(row->row.fingerprint().equals(operationKey)).isEmpty())throw error(CONFLICT,"A change for this meter and community is already reserved today");}
    }
    private void receiveRevocation(String code,byte[] xml) {
        EdaEnergySharingXml.validate(xml,"CMRevoke_01p10.xsd");
        var root=EdaEnergySharingXml.parse(xml).getDocumentElement();var pd=one(root,"ProcessDirectory");var mp=one(root,"MarketParticipantDirectory");var route=one(mp,"RoutingHeader");
        if(!code.equals(text(mp,"MessageCode")))throw error(INVALID_REQUEST,"Revocation message code mismatch");
        var sender=text(one(route,"Sender"),"MessageAddress");var operator=text(one(route,"Receiver"),"MessageAddress");
        var meter=text(pd,"MeteringPoint");var consent=text(pd,"ConsentId");var end=Objects.requireNonNull(localDate(pd,"ConsentEnd"));var revision=text(pd,"MessageId");
        var eventKey=hash(sender+"|"+operator+"|"+consent+"|"+revision);
        var createdAt=OffsetDateTime.parse(text(route,"DocumentCreationDateTime")).toInstant();
        for(var binding:config.bindings()) {
            if(!operator.equals(binding.operatorId()))continue;
            for(var record:store.records(scope(binding.operatorId(),binding.connectionId()),"SNAPSHOT")) {
                var prior=mapper.treeToValue(mapper.readTree(record.payload()).path("snapshot"),EnergySharingCommunitySnapshot.class);
                if(!sender.equals(binding.communities().get(prior.communityId())))continue;
                if(prior.meteringPoints().stream().noneMatch(m->m.meteringPointId().equals(meter)&&consent.equals(m.nativeParameters().path("consentId").asText())))continue;
                store.update(record.id(),payload->{
                    var wrapper=(ObjectNode)mapper.readTree(payload);var snapshot=mapper.treeToValue(wrapper.path("snapshot"),EnergySharingCommunitySnapshot.class);
                    var seen=wrapper.has("seenRevocations")?(ArrayNode)wrapper.path("seenRevocations"):wrapper.putArray("seenRevocations");
                    for(var entry:seen)if(entry.asText().equals(eventKey))return payload;
                    seen.add(eventKey);
                    if(wrapper.hasNonNull("authorityCreatedAt")&&createdAt.isBefore(OffsetDateTime.parse(wrapper.path("authorityCreatedAt").asText()).toInstant()))return wrapper.toString();
                    var changed=snapshot.meteringPoints().stream().map(m->{
                        if(!meter.equals(m.meteringPointId())||!consent.equals(m.nativeParameters().path("consentId").asText()))return m;
                        var nativeParams=(ObjectNode)m.nativeParameters().deepCopy();nativeParams.put("revocationXml",new String(xml,StandardCharsets.UTF_8));
                        var periods=mapper.createArrayNode();
                        for(var period:m.nativeParameters().path("timePeriods")) {
                            if(EdaEnergySharingXml.date(period,"start").isAfter(end))continue;
                            var adjusted=(ObjectNode)period.deepCopy();
                            if(EdaEnergySharingXml.date(period,"end").isAfter(end))adjusted.put("end",end.toString());
                            adjusted.put("deactivate",end.toString());periods.add(adjusted);
                        }
                        nativeParams.set("timePeriods",periods);
                        return new EnergySharingMeteringPoint(m.meteringPointId(),m.permissionAdministratorId(),m.direction(),m.participationFactor(),m.allocationFactor(),m.start(),end,
                                end.isBefore(LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA)))?"ENDED":"ENDING",nativeParams,StandardCodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME.value());
                    }).toList();
                    var updated=new EnergySharingCommunitySnapshot(snapshot.communityId(),snapshot.operatorId(),snapshot.connectionId(),revision,clock.instant(),snapshot.complete(),snapshot.name(),snapshot.allocationMethod(),changed,snapshot.nativeParameters());
                    wrapper.put("requestedAt",clock.instant().toString());wrapper.put("authorityCreatedAt",text(route,"DocumentCreationDateTime"));wrapper.set("snapshot",mapper.valueToTree(updated));return wrapper.toString();
                });
            }
        }
    }
    private void validateParameters(EnergySharingRequest r) {
        validateKeys(r.parameters(),schemas().path(r.operation()));
        if(r.operation().equals("CHANGE_PARTICIPATION_FACTOR")) {
            for(var period:r.parameters().path("timePeriods"))if(period.hasNonNull("allocationFactor"))
                throw error(INVALID_REQUEST,"Use CHANGE_ALLOCATION to change ECShare; CPF changes participation only");
        }
    }
    private static void validateKeys(JsonNode value,JsonNode schema) {
        if(!value.isObject())throw error(INVALID_REQUEST,"parameters must be an object");
        for(var entry:value.properties()) {
            var property=schema.path("properties").get(entry.getKey());
            if(property==null)throw error(INVALID_REQUEST,"Unknown or inapplicable national parameter: "+entry.getKey());
            if(entry.getValue().isArray()&&property.path("items").path("type").asText().equals("object"))for(var item:entry.getValue())validateKeys(item,property.path("items"));
        }
    }

}
