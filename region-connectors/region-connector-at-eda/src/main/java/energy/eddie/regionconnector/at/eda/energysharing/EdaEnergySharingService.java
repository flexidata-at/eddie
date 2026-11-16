// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import static energy.eddie.api.agnostic.energysharing.EnergySharingException.Code.*;

import de.ponton.xp.adapter.api.domainvalues.*;
import de.ponton.xp.adapter.api.messages.OutboundMessage;
import energy.eddie.api.agnostic.energysharing.*;
import energy.eddie.cim.v1_12.StandardCodingSchemeTypeList;
import energy.eddie.regionconnector.at.eda.persistence.EnergySharingRecord;
import energy.eddie.regionconnector.at.eda.ponton.messenger.PontonMessengerConnection;
import energy.eddie.regionconnector.at.eda.requests.EdaGroupingIdFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;

/** Durable, connection-scoped management of EDA market requests. */
@Component
public class EdaEnergySharingService implements RegionConnectorEnergySharingService {
  private static final String OPERATOR_MEASUREMENT_UNAVAILABLE = "Operator measurement service unavailable";
  private static final String METERING_POINT_ID_FIELD = "meteringPointId";
  private static final String ANTWORT_PT = "ANTWORT_PT";
  private static final String REQUEST_MEASUREMENTS = "REQUEST_MEASUREMENTS";
  private static final String SYNC_MASTER_DATA = "SYNC_MASTER_DATA";
  private static final String LEAVE = "LEAVE";
  private static final String CHANGE_PARTICIPATION_FACTOR = "CHANGE_PARTICIPATION_FACTOR";
  private static final String CHANGE_ALLOCATION = "CHANGE_ALLOCATION";
  private static final String COMPLETED = "COMPLETED";
  private static final String REJECTED = "REJECTED";
  private static final String RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED";
  private static final String OPERATION_FIELD = "operation";
  private static final String CHILDREN_FIELD = "children";
  private static final String ALLOCATION_FACTOR_FIELD = "allocationFactor";
  private static final String SNAPSHOT_FIELD = "snapshot";
  private static final String REQUEST_FIELD = "request";
  private static final String EVENTS_FIELD = "events";
  private static final String SNAPSHOT = "SNAPSHOT";
  private static final String ANTWORT_ECON = "ANTWORT_ECON";
  private static final String PROCESS_DIRECTORY = "ProcessDirectory";
  private static final String ROUTING_HEADER = "RoutingHeader";
  private static final String MARKET_PARTICIPANT_DIRECTORY = "MarketParticipantDirectory";
  private static final String MESSAGE_ADDRESS = "MessageAddress";
  private static final String METERING_POINT = "MeteringPoint";
  private static final String COUNTRY_CODE_FIELD = "countryCode";
  private static final String CONSENT_ID = "ConsentId";
  private static final String CONSENT_ID_FIELD = "consentId";
  private static final String ACKNOWLEDGED = "ACKNOWLEDGED";
  private static final String START_FIELD = "start";
  private static final String TIME_PERIODS_FIELD = "timePeriods";
  private static final String DATE_FROM = "DateFrom";
  private static final String PARTICIPATION_FACTOR_FIELD = "participationFactor";
  private static final String DATE_TO = "DateTo";
  private static final String REQUESTED_AT_FIELD = "requestedAt";
  private static final String AUTHORITY_CREATED_AT_FIELD = "authorityCreatedAt";
  private static final String DOCUMENT_CREATION_DATE_TIME = "DocumentCreationDateTime";
  private static final String SEEN_REVOCATIONS_FIELD = "seenRevocations";
  private static final String PROPERTIES_FIELD = "properties";
  private static final String DEFAULT_FIELD = "default";

  private static final Set<String> OPERATIONS =
      Set.of("JOIN", LEAVE, CHANGE_PARTICIPATION_FACTOR, CHANGE_ALLOCATION, SYNC_MASTER_DATA, REQUEST_MEASUREMENTS);
  private static final Set<String> TERMINAL = Set.of(COMPLETED, REJECTED);
  private final EdaEnergySharingConfiguration config;
  private final EdaEnergySharingStore store;
  private final PontonMessengerConnection transport;
  private final ObjectMapper mapper;
  private final Clock clock;
  private final EdaGroupingIdFactory groupingIds;
  private @org.jspecify.annotations.Nullable EdaOperatorMeasurementService operatorMeasurements;

  @Autowired
  public EdaEnergySharingService(
      EdaEnergySharingConfiguration config,
      EdaEnergySharingStore store,
      PontonMessengerConnection transport,
      ObjectMapper mapper,
      EdaGroupingIdFactory groupingIds) {
    this(config, store, transport, mapper, groupingIds, Clock.systemUTC());
  }

  EdaEnergySharingService(
      EdaEnergySharingConfiguration config,
      EdaEnergySharingStore store,
      PontonMessengerConnection transport,
      ObjectMapper mapper,
      EdaGroupingIdFactory groupingIds,
      Clock clock) {
    this.config = config;
    this.store = store;
    this.transport = transport;
    this.mapper = mapper;
    this.groupingIds = groupingIds;
    this.clock = clock;
    transport.withEnergySharingHandler(
        new EnergySharingMessageHandler() {
          @Override
          public boolean handle(String code, byte[] xml) {
            return receive(code, xml);
          }

          @Override
          public void transportStatus(String id, String state) {
            if (!Set.of("FAILED", "CONFIG_ERROR", "CONTENT_ERROR", "TRANSMISSION_ERROR")
                .contains(state)) return;
            store
                .byMarketId(id)
                .ifPresent(
                    storedRow ->
                        store.update(
                            storedRow.id(),
                            payload -> {
                              var data = (ObjectNode) mapper.readTree(payload);
                              var old = readOperation(payload);
                              if (TERMINAL.contains(old.status())) return payload;
                              data.set(
                                  OPERATION_FIELD,
                                  mapper.valueToTree(
                                      copy(
                                          old,
                                          RECONCILIATION_REQUIRED,
                                          state,
                                          "Messenger reported a transport failure; reconcile before"
                                              + " resending",
                                          old.result())));
                              return data.toString();
                            }));
          }
        });
  }

  @Override
  public EnergySharingCapabilities capabilities(String operatorId, String connectionId) {
    var binding = binding(operatorId, connectionId);
    var steps = new ArrayList<String>();
    if (binding.communities().isEmpty())
      steps.add(
          "Configure the authorized EC-ID and its DSO once. EDA publishes no operator-accessible"
              + " EC_LIST discovery process.");
    if (!binding.partnerConfigured()) {
      steps.add(
          "Verify the operator's ebUtilities RC/CC/PP role, EC-ID and relevant DSO contract.");
      steps.add(
          "Complete the EDA platform contract, mandate to the IT service and Messenger routing."
              + " Portal credentials alone are insufficient.");
      steps.add(
          "Have the administrator create/import the local operator partner and remote DSO"
              + " certificates/agreement in PONTON X/P.");
      steps.add(
          "Activate EC_REQ_ONL_02.40, EC_PODLIST_02.10, EC_PRTFACT_CHANGE_01.10,"
              + " EC_PRT_CHANGE_02.10, CM_REV_SP_01.30 and CR_REQ_PT_03.00 as applicable.");
      steps.add(
          "Configure the EDDIE adapter, verify its connection-to-operator authorization, then test"
              + " transport and synchronize master data.");
    }
    if (!transport.supportsEnergySharing())
      steps.add(
          "Enable the production PONTON Messenger transport; the development REST replacement"
              + " cannot send these market requests.");
    boolean ready = steps.isEmpty() && transport.messengerStatus().ok();
    if (steps.isEmpty() && !ready)
      steps.add("Restore the PONTON Messenger connection before submitting requests.");
    return new EnergySharingCapabilities(
        EdaEnergySharingXml.PROFILE,
        EdaEnergySharingXml.VERSION,
        operatorId,
        ready,
        OPERATIONS,
        schemas(),
        steps,
        "AT");
  }

  @Override
  public EnergySharingOperation submit(EnergySharingRequest request) {
    var binding = binding(request.operatorId(), request.connectionId());
    validateSubmission(request);
    var id = key(request);
    var fingerprint = hash(canonical(mapper.valueToTree(request)));
    var existing = store.find(id);
    if (existing.isPresent()) return replayExisting(request, existing.get(), fingerprint);
    if (!capabilities(request.operatorId(), request.connectionId()).transportReady())
      throw error(NOT_READY, "PONTON operator partner/connection setup is incomplete");
    if (request.communityId().isBlank()) return discover(request, binding, fingerprint);
    String dso = community(binding, request.communityId());
    validateCommunityRequest(request, dso);
    String marketId = groupingIds.createRandomId();
    var message = EdaEnergySharingXml.create(withSyncDefaults(request), dso, marketId, clock);
    if (request.operation().startsWith("CHANGE_")) reserveDailyChange(request, id);
    var operation =
        new EnergySharingOperation(
            request.requestId(),
            request.connectionId(),
            request.operatorId(),
            request.communityId(),
            request.operation(),
            "QUEUED",
            clock.instant(),
            clock.instant(),
            marketId,
            null,
            null,
            mapper.createObjectNode());
    var data = mapper.createObjectNode();
    data.set(REQUEST_FIELD, mapper.valueToTree(withSyncDefaults(request)));
    data.set(OPERATION_FIELD, mapper.valueToTree(operation));
    data.putArray(EVENTS_FIELD);
    try {
      var entry = new EnergySharingRecord(id, scope(request.operatorId(), request.connectionId()),
          "OPERATION", fingerprint, marketId, data.toString());
      if (REQUEST_MEASUREMENTS.equals(request.operation())) {
        store.insertAuthorized(entry, snapshotKey(request), snapshot -> {
          data.set("authorityEvidence", measurementRequestProof(request, dso, snapshot));
          return data.toString();
        });
      } else store.insert(entry);
    } catch (RuntimeException e) {
      var race = store.find(id);
      if (race.isPresent()) return replay(race.get(), fingerprint);
      throw e;
    }
    dispatch(request, id, dso, message);
    return operation(
            request.requestId(),
            request.connectionId(),
            request.operatorId(),
            request.communityId())
        .orElseThrow();
  }

  private void validateSubmission(EnergySharingRequest request) {
    if (!EdaEnergySharingXml.VERSION.equals(request.profileVersion()))
      throw error(INVALID_REQUEST, "Unsupported national profile version");
    if (!OPERATIONS.contains(request.operation()))
      throw error(UNSUPPORTED, "Operation not supported by the Austrian profile");
    if (request.requestId().isBlank()) throw error(INVALID_REQUEST, "requestId is required");
    validateParameters(request);
  }

  private EnergySharingOperation replayExisting(
      EnergySharingRequest request, EnergySharingRecord existing, String fingerprint) {
    var saved = replay(existing, fingerprint);
    if (request.communityId().isBlank() && saved.result().path(CHILDREN_FIELD).isArray()) {
      for (var child : saved.result().path(CHILDREN_FIELD))
        submit(mapper.treeToValue(child, EnergySharingRequest.class));
      return operation(request.requestId(), request.connectionId(), request.operatorId(), "")
          .orElseThrow();
    }
    return saved;
  }

  private void validateCommunityRequest(EnergySharingRequest request, String dso) {
    if (REQUEST_MEASUREMENTS.equals(request.operation())) {
      var snapshot = store.find(snapshotKey(request)).orElseThrow(() -> error(NOT_READY, "Synchronize authoritative community membership first"));
      measurementRequestProof(request, dso, snapshot.payload());
    }
    if (request.parameters().hasNonNull("permissionAdministratorId")
        && !dso.equals(request.parameters().path("permissionAdministratorId").asText()))
      throw error(FORBIDDEN, "DSO does not match the authorized community binding");
    if (request.operation().equals(CHANGE_ALLOCATION)
        || request.parameters().hasNonNull(ALLOCATION_FACTOR_FIELD)) {
      var snapshot = store.find(snapshotKey(request));
      if (snapshot.isEmpty()
          || !"S"
              .equals(
                  mapper
                      .readTree(snapshot.get().payload())
                      .path(SNAPSHOT_FIELD)
                      .path("allocationMethod")
                      .asText()))
        throw error(
            INVALID_REQUEST,
            "A confirmed existing static community snapshot is required for ECShare");
    }
  }

  private JsonNode measurementRequestProof(EnergySharingRequest request, String dso, String snapshot) {
    return new EdaMeasurementRequestPolicy(mapper, clock).authorize(request, dso, snapshot);
  }

  private void dispatch(
      EnergySharingRequest request, String id, String dso, EdaEnergySharingXml.Message message) {
    try {
      var meta =
          new OutboundMetaData.OutboundMetaDataBuilder()
              .setSenderId(new SenderId(request.operatorId()))
              .setReceiverId(new ReceiverId(dso))
              .setMessageType(
                  new MessageType.MessageTypeBuilder()
                      .setName(new MessageTypeName(message.code()))
                      .setSchemaSet(new SchemaSet(message.schemaSet()))
                      .setVersion(new MessageTypeVersion(message.version()))
                      .setMimeType(new MimeType("text/xml"))
                      .build())
              .build();
      transport.sendEnergySharing(
          OutboundMessage.newBuilder()
              .setInputStream(new ByteArrayInputStream(message.xml()))
              .setOutboundMetaData(meta)
              .build());
      status(id, "SUBMITTED", null, "Submitted to Messenger; awaiting market response");
    } catch (Exception e) {
      status(
          id,
          RECONCILIATION_REQUIRED,
          null,
          "Transport result is uncertain; reconcile before retrying. The same request ID will not"
              + " resend.");
    }
  }

  private EnergySharingOperation discover(
      EnergySharingRequest request,
      EdaEnergySharingConfiguration.Binding binding,
      String fingerprint) {
    if (!request.operation().equals(SYNC_MASTER_DATA))
      throw error(INVALID_REQUEST, "communityId is required");
    var children = mapper.createArrayNode();
    int index = 0;
    for (String communityId : new TreeSet<>(binding.communities().keySet())) {
      var child =
          new EnergySharingRequest(
              request.requestId() + "-" + ++index,
              request.connectionId(),
              request.operatorId(),
              communityId,
              request.operation(),
              request.profileVersion(),
              request.parameters());
      EdaEnergySharingXml.create(
          withSyncDefaults(child),
          community(binding, communityId),
          groupingIds.createRandomId(),
          clock);
      children.add(mapper.valueToTree(child));
    }
    var now = clock.instant();
    var result = mapper.createObjectNode();
    result.set(CHILDREN_FIELD, children);
    var operation =
        new EnergySharingOperation(
            request.requestId(),
            request.connectionId(),
            request.operatorId(),
            "",
            request.operation(),
            "SUBMITTED",
            now,
            now,
            null,
            null,
            "Synchronizing configured, authorized EC-IDs",
            result);
    var data = mapper.createObjectNode();
    data.set(REQUEST_FIELD, mapper.valueToTree(request));
    data.set(OPERATION_FIELD, mapper.valueToTree(operation));
    data.putArray(EVENTS_FIELD);
    try {
      store.insert(
          new EnergySharingRecord(
              key(request),
              scope(request.operatorId(), request.connectionId()),
              "OPERATION",
              fingerprint,
              "GROUP" + hash(key(request)).substring(0, 30),
              data.toString()));
    } catch (RuntimeException e) {
      return replay(store.find(key(request)).orElseThrow(() -> e), fingerprint);
    }
    for (var child : children) {
      try {
        submit(mapper.treeToValue(child, EnergySharingRequest.class));
      } catch (RuntimeException e) {
        store.update(
            key(request),
            payload -> {
              var changed = (ObjectNode) mapper.readTree(payload);
              changed.set(
                  OPERATION_FIELD,
                  mapper.valueToTree(
                      copy(
                          readOperation(payload),
                          RECONCILIATION_REQUIRED,
                          null,
                          "Some child synchronizations could not start; inspect child operations",
                          result)));
              return changed.toString();
            });
        break;
      }
    }
    return operation(request.requestId(), request.connectionId(), request.operatorId(), "")
        .orElseThrow();
  }

  @Override
  public Optional<EnergySharingOperation> operation(
      String requestId, String connectionId, String operatorId, String communityId) {
    var binding = binding(operatorId, connectionId);
    if (!communityId.isBlank()) community(binding, communityId);
    return store
        .find(key(operatorId, connectionId, communityId, requestId))
        .map(row -> measurementRequestStatus(row, aggregateOperation(readOperation(row.payload()), connectionId, operatorId)));
  }

  private EnergySharingOperation measurementRequestStatus(EnergySharingRecord row, EnergySharingOperation operation) {
    if (!REQUEST_MEASUREMENTS.equals(operation.operation())) return operation;
    var request = mapper.treeToValue(mapper.readTree(row.payload()).path(REQUEST_FIELD), EnergySharingRequest.class);
    var delivery = Objects.requireNonNull(operatorMeasurements, OPERATOR_MEASUREMENT_UNAVAILABLE)
        .requestDelivery(request.operatorId(), request.connectionId(), request.communityId(),
            Objects.requireNonNull(operation.marketMessageId()), request.parameters().path(METERING_POINT_ID_FIELD).asText());
    var result = (ObjectNode) operation.result().deepCopy();
    result.put("deliveryStatus", delivery.documentCount() == 0 ? "NO_MATCHING_DOCUMENTS" : "DOCUMENTS_RECEIVED");
    result.put("receivedDocumentCount", delivery.documentCount());
    result.put("receivedReadingCount", delivery.readingCount());
    result.put("coverageComplete", false);
    return new EnergySharingOperation(operation.requestId(), operation.connectionId(), operation.operatorId(), operation.communityId(),
        operation.operation(), operation.status(), operation.createdAt(), operation.updatedAt(), operation.marketMessageId(),
        operation.nativeCode(), operation.message(), result);
  }

  private EnergySharingOperation aggregateOperation(
      EnergySharingOperation op, String connectionId, String operatorId) {
    var children = op.result().path(CHILDREN_FIELD);
    if (!children.isArray() || children.isEmpty()) return op;
    var values = mapper.createArrayNode();
    boolean complete = true;
    boolean failed = false;
    for (var child : children) {
      var found =
          operation(
              child.path("requestId").asText(),
              connectionId,
              operatorId,
              child.path("communityId").asText());
      if (found.isEmpty()) complete = false;
      else {
        var value = found.get();
        values.add(mapper.valueToTree(value));
        complete &= COMPLETED.equals(value.status());
        failed |= Set.of(REJECTED, RECONCILIATION_REQUIRED).contains(value.status());
      }
    }
    var result = (ObjectNode) op.result().deepCopy();
    result.set("operations", values);
    String state = op.status();
    if (failed) state = RECONCILIATION_REQUIRED;
    if (complete) state = COMPLETED;
    return copy(op, state, op.nativeCode(), op.message(), result);
  }

  @Autowired
  public void setOperatorMeasurements(EdaOperatorMeasurementService service) {
    this.operatorMeasurements = service;
  }

  @Override
  public EnergySharingMeasurementPage measurements(String operatorId, String connectionId, String communityId, long afterSequence, int limit) {
    return Objects.requireNonNull(operatorMeasurements, OPERATOR_MEASUREMENT_UNAVAILABLE)
        .measurements(operatorId, connectionId, communityId, afterSequence, limit);
  }

  @Override
  public EnergySharingMeasurementImportResult importMeasurement(String operatorId, String connectionId, String communityId,
      EnergySharingMeasurementImport request) {
    return Objects.requireNonNull(operatorMeasurements, OPERATOR_MEASUREMENT_UNAVAILABLE)
        .importArchive(operatorId, connectionId, communityId, request);
  }

  @Override
  public byte[] measurementSource(String operatorId, String connectionId, String communityId, String documentId) {
    return Objects.requireNonNull(operatorMeasurements, OPERATOR_MEASUREMENT_UNAVAILABLE)
        .source(operatorId, connectionId, communityId, documentId);
  }

  @Override
  public List<EnergySharingCommunitySnapshot> communities(String operatorId, String connectionId) {
    var binding = binding(operatorId, connectionId);
    return store.records(scope(operatorId, connectionId), SNAPSHOT).stream()
        .map(
            r ->
                mapper.treeToValue(
                    mapper.readTree(r.payload()).path(SNAPSHOT_FIELD),
                    EnergySharingCommunitySnapshot.class))
        .filter(s -> binding.communities().containsKey(s.communityId()))
        .toList();
  }

  boolean receive(String messageCode, byte[] xml) {
    if (Set.of("AUFHEBUNG_CCMC", "AUFHEBUNG_CCMI").contains(messageCode)) {
      receiveRevocation(messageCode, xml);
      return false; // The existing permission pipeline also needs the revocation.
    }
    if (!Set.of(
            ANTWORT_ECON,
            "ZUSTIMMUNG_ECON",
            "ABSCHLUSS_ECON",
            "ABLEHNUNG_ECON",
            "ABBRUCH_ECON",
            "ANTWORT_CPF",
            "ABLEHNUNG_CPF",
            "ANTWORT_ECC",
            "ABLEHNUNG_ECC",
            "SENDEN_ECP",
            "ABLEHNUNG_ECP",
            "ANTWORT_CCMS",
            "ABLEHNUNG_CCMS", ANTWORT_PT, "ABLEHNUNG_PT")
        .contains(messageCode)) return false;
    var root = EdaEnergySharingXml.parse(xml).getDocumentElement();
    String schema =
        switch (root.getLocalName()) {
          case "ECMPList" -> "ECMPList_01p20.xsd";
          case "CMNotification" -> "CMNotification_01p20.xsd";
          case "CPNotification" -> "CPNotification_01p13.xsd";
          default -> throw error(INVALID_REQUEST, "Unexpected market payload");
        };
    var directory = one(root, PROCESS_DIRECTORY);
    var found = store.byMarketId(text(directory, "ConversationId"));
    if (found.isEmpty()) return false;
    EdaEnergySharingXml.validate(xml, schema);
    var storedRow = found.get();
    var request =
        mapper.treeToValue(
            mapper.readTree(storedRow.payload()).path(REQUEST_FIELD), EnergySharingRequest.class);
    var reply = new IncomingReply(storedRow, request, root, directory, messageCode);
    validateReplyScope(reply);
    applyReply(reply, xml);
    return true;
  }

  private record IncomingReply(
      EnergySharingRecord storedRow,
      EnergySharingRequest request,
      Element root,
      Element directory,
      String code) {}

  private void validateReplyScope(IncomingReply reply) {
    var req = reply.request();
    var dso = community(binding(req.operatorId(), req.connectionId()), req.communityId());
    var participants = one(reply.root(), MARKET_PARTICIPANT_DIRECTORY);
    var routing = one(participants, ROUTING_HEADER);
    if (!reply.code().equals(text(participants, "MessageCode"))
        || !dso.equals(text(one(routing, "Sender"), MESSAGE_ADDRESS))
        || !req.operatorId().equals(text(one(routing, "Receiver"), MESSAGE_ADDRESS)))
      throw error(FORBIDDEN, "Market reply routing does not match the authorized operation");
    String ec = textOrEmpty(reply.directory(), "ECID");
    if (!ec.isEmpty() && !ec.equals(req.communityId()))
      throw error(FORBIDDEN, "Market reply community mismatch");
    if (!allowedReply(req.operation(), reply.code()))
      throw error(INVALID_REQUEST, "Reply does not belong to this operation");
    if (REQUEST_MEASUREMENTS.equals(req.operation())) EdaMeasurementRequestPolicy.validateReply(reply.code(), reply.directory(), reply.storedRow().marketId());
    var expectedMeter = req.parameters().path(METERING_POINT_ID_FIELD).asText("");
    for (var meter : descendants(reply.directory(), METERING_POINT)) {
      if (!expectedMeter.isEmpty() && !expectedMeter.equals(meter.getTextContent()))
        throw error(FORBIDDEN, "Market reply meter mismatch");
    }
  }

  private void applyReply(IncomingReply reply, byte[] xml) {
    var messageId = text(reply.directory(), "MessageId");
    var result = replyResult(reply, xml);
    var state = replyState(reply.code());
    var codes = descendants(reply.directory(), "ResponseCode");
    String nativeCode = codes.isEmpty() ? "" : codes.getFirst().getTextContent();
    store.update(
        reply.storedRow().id(),
        payload ->
            updatedOperationPayload(payload, reply, result, state, nativeCode, messageId, xml));
    if (state.equals(COMPLETED) && !reply.request().operation().equals(SYNC_MASTER_DATA))
      reconcileAfterReply(reply);
  }

  private ObjectNode replyResult(IncomingReply reply, byte[] xml) {
    var result = mapper.createObjectNode();
    result.put("messageCode", reply.code());
    result.put("nativeXml", new String(xml, StandardCharsets.UTF_8));
    result.put(COUNTRY_CODE_FIELD, "AT");
    if (REQUEST_MEASUREMENTS.equals(reply.request().operation())) {
      result.put("stage", ANTWORT_PT.equals(reply.code()) ? "REQUEST_ACCEPTED" : "REQUEST_REJECTED");
      result.put("coverageComplete", false);
    }
    if ("CMNotification".equals(reply.root().getLocalName()))
      addConsentResult(reply.directory(), result);
    return result;
  }

  private void addConsentResult(Element directory, ObjectNode result) {
    var consentIds =
        descendants(directory, CONSENT_ID).stream()
            .map(Element::getTextContent)
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .distinct()
            .toList();
    if (consentIds.size() == 1) result.put(CONSENT_ID_FIELD, consentIds.getFirst());
    var portalUrl = textOrEmpty(directory, "PortalApprovalURL").strip();
    if (!portalUrl.isEmpty() && safeApprovalUrl(portalUrl))
      result.put("portalApprovalUrl", portalUrl);
  }

  private static boolean safeApprovalUrl(String portalUrl) {
    try {
      var uri = URI.create(portalUrl);
      return uri.isAbsolute()
          && "https".equalsIgnoreCase(uri.getScheme())
          && uri.getHost() != null
          && !uri.getHost().isBlank()
          && uri.getRawUserInfo() == null;
    } catch (IllegalArgumentException ignored) {
      // Retain the original response for reconciliation, but do not expose an unsafe approval
      // action.
      return false;
    }
  }

  private static String replyState(String code) {
    if (code.startsWith("ABLEHNUNG") || code.equals("ABBRUCH_ECON")) return REJECTED;
    if (code.equals("ZUSTIMMUNG_ECON") || code.equals(ANTWORT_ECON) || code.equals(ANTWORT_PT)) return ACKNOWLEDGED;
    return COMPLETED;
  }

  private String updatedOperationPayload(
      String payload,
      IncomingReply reply,
      ObjectNode result,
      String state,
      String nativeCode,
      String messageId,
      byte[] xml) {
    var current = (ObjectNode) mapper.readTree(payload);
    var events = (ArrayNode) current.path(EVENTS_FIELD);
    for (var event : events) {
      if (messageId.equals(event.path("messageId").asText())) return payload;
    }
    var old = readOperation(payload);
    if (reply.code().equals("SENDEN_ECP") && !TERMINAL.contains(old.status())) {
      if (!textOrEmpty(reply.directory(), "ECID").equals(reply.request().communityId()))
        throw error(INVALID_REQUEST, "Authoritative snapshot must identify the requested EC-ID");
      persistSnapshot(reply.request(), reply.root(), xml, old.createdAt(), messageId);
    }
    var event = mapper.createObjectNode();
    event.put("messageId", messageId);
    event.put("receivedAt", clock.instant().toString());
    event.set("response", result);
    events.add(event);
    if (!TERMINAL.contains(old.status())
        && !(ACKNOWLEDGED.equals(old.status()) && reply.code().equals(ANTWORT_ECON)))
      current.set(
          OPERATION_FIELD,
          mapper.valueToTree(copy(old, state, nativeCode, "Received " + reply.code(), result)));
    return current.toString();
  }

  private void reconcileAfterReply(IncomingReply reply) {
    var req = reply.request();
    try {
      submit(
          new EnergySharingRequest(
              "reconcile-" + reply.storedRow().marketId(),
              req.connectionId(),
              req.operatorId(),
              req.communityId(),
              SYNC_MASTER_DATA,
              req.profileVersion(),
              mapper.createObjectNode()));
    } catch (RuntimeException e) {
      store.update(
          reply.storedRow().id(),
          payload -> {
            var current = (ObjectNode) mapper.readTree(payload);
            ((ObjectNode) current.path(OPERATION_FIELD).path("result"))
                .put("reconciliationPending", true);
            return current.toString();
          });
    }
  }

  private void persistSnapshot(
      EnergySharingRequest req, Element root, byte[] xml, Instant requestedAt, String revision) {
    var range = withSyncDefaults(req).parameters();
    var rangeStart = EdaEnergySharingXml.date(range, START_FIELD);
    var rangeEnd = EdaEnergySharingXml.date(range, "end");
    var currentDate = LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA));
    if (currentDate.isBefore(rangeStart) || currentDate.isAfter(rangeEnd))
      return; // Historical queries are available in operation.result, never replace the current
    // roster.
    var pd = one(root, PROCESS_DIRECTORY);
    var meters = new ArrayList<EnergySharingMeteringPoint>();
    var dso = community(binding(req.operatorId(), req.connectionId()), req.communityId());
    for (var member : children(pd, "MPListData")) meters.add(readMeter(member, dso));
    var nativeParams = mapper.createObjectNode();
    nativeParams.put(COUNTRY_CODE_FIELD, "AT");
    nativeParams.put("masterDataReceivedAt", clock.instant().toString());
    nativeParams.put("masterDataRevision", revision);
    nativeParams.put("communityType", textOrEmpty(pd, "ECType"));
    nativeParams.put("zoneLevel", textOrEmpty(pd, "ECZoneLevel"));
    nativeParams.put("nativeXml", new String(xml, StandardCharsets.UTF_8));
    nativeParams.set("requestedRange", withSyncDefaults(req).parameters());
    var snapshot =
        new EnergySharingCommunitySnapshot(
            req.communityId(),
            req.operatorId(),
            req.connectionId(),
            revision,
            clock.instant(),
            true,
            null,
            nullable(textOrEmpty(pd, "ECDisModel")),
            meters,
            nativeParams);
    var data = mapper.createObjectNode();
    data.put(REQUESTED_AT_FIELD, requestedAt.toString());
    data.set(SNAPSHOT_FIELD, mapper.valueToTree(snapshot));
    data.put(
        AUTHORITY_CREATED_AT_FIELD,
        text(
            one(one(root, MARKET_PARTICIPANT_DIRECTORY), ROUTING_HEADER),
            DOCUMENT_CREATION_DATE_TIME));
    var id = snapshotKey(req);
    var existing = store.find(id);
    if (existing.isEmpty()) {
      try {
        store.insert(
            new EnergySharingRecord(
                id,
                scope(req.operatorId(), req.connectionId()),
                SNAPSHOT,
                "",
                "SNAP" + id.substring(0, 30),
                data.toString()));
        return;
      } catch (RuntimeException e) {
        if (store.find(id).isEmpty()) throw e;
      }
    }
    store.update(
        id,
        old -> {
          if (!Instant.parse(mapper.readTree(old).path(REQUESTED_AT_FIELD).asText())
              .isBefore(requestedAt)) return old;
          var previousWrapper = mapper.readTree(old);
          if (previousWrapper.has(SEEN_REVOCATIONS_FIELD))
            data.set(SEEN_REVOCATIONS_FIELD, previousWrapper.path(SEEN_REVOCATIONS_FIELD));
          var previous =
              mapper.treeToValue(
                  previousWrapper.path(SNAPSHOT_FIELD), EnergySharingCommunitySnapshot.class);
          var merged = mergeOutsideRange(previous.meteringPoints(), meters, rangeStart, rangeEnd);
          var assembled =
              new EnergySharingCommunitySnapshot(
                  snapshot.communityId(),
                  snapshot.operatorId(),
                  snapshot.connectionId(),
                  snapshot.revision(),
                  snapshot.receivedAt(),
                  true,
                  snapshot.name(),
                  snapshot.allocationMethod(),
                  merged,
                  snapshot.nativeParameters());
          data.set(SNAPSHOT_FIELD, mapper.valueToTree(assembled));
          return data.toString();
        });
  }

  private EnergySharingMeteringPoint readMeter(Element member, String dso) {
    String meter = text(member, METERING_POINT);
    var nativeParams = mapper.createObjectNode();
    nativeParams.put(CONSENT_ID_FIELD, textOrEmpty(member, CONSENT_ID));
    nativeParams.put(COUNTRY_CODE_FIELD, "AT");
    var nativePeriods = nativeParams.putArray(TIME_PERIODS_FIELD);
    var today = LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA));
    @Nullable Element current = null;
    for (var period : children(member, "MPTimeData")) {
      nativePeriods.add(readNativePeriod(period));
      var from = Objects.requireNonNull(localDate(period, DATE_FROM));
      var until = Objects.requireNonNull(localDate(period, DATE_TO));
      if (!today.isBefore(from) && !today.isAfter(until)) {
        if (current != null) throw error(INVALID_REQUEST, "Overlapping authoritative time periods");
        current = period;
      }
    }
    if (current == null)
      return new EnergySharingMeteringPoint(
          meter,
          dso,
          null,
          null,
          null,
          null,
          null,
          "INACTIVE_FOR_CURRENT_DATE",
          nativeParams,
          StandardCodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME.value());
    return new EnergySharingMeteringPoint(
        meter,
        dso,
        nullable(textOrEmpty(current, "EnergyDirection")),
        decimal(current, "ECPartFact"),
        decimal(current, "ECShare"),
        localDate(current, DATE_FROM),
        localDate(current, DATE_TO),
        "CONFIRMED",
        nativeParams,
        StandardCodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME.value());
  }

  private ObjectNode readNativePeriod(Element period) {
    var value = mapper.createObjectNode();
    var names =
        Map.ofEntries(
            Map.entry(DATE_FROM, START_FIELD),
            Map.entry(DATE_TO, "end"),
            Map.entry("DateActivate", "activate"),
            Map.entry("DateDeactivate", "deactivate"),
            Map.entry("DataType", "dataType"),
            Map.entry("ECPartFact", PARTICIPATION_FACTOR_FIELD),
            Map.entry("ECShare", ALLOCATION_FACTOR_FIELD),
            Map.entry("EnergyDirection", "energyDirection"),
            Map.entry("TransmissionCycle", "transmissionCycle"),
            Map.entry("Purpose", "purpose"),
            Map.entry("TechCode", "techCode"),
            Map.entry("FuelCode", "fuelCode"));
    for (var node : children(period, null)) {
      var field = names.get(node.getLocalName());
      if (field != null) {
        if (Set.of(PARTICIPATION_FACTOR_FIELD, ALLOCATION_FACTOR_FIELD).contains(field))
          value.put(field, new BigDecimal(node.getTextContent()));
        else value.put(field, node.getTextContent());
      }
    }
    var calculated = value.putArray("calculatedShares");
    for (var share : children(period, "ECShC")) {
      var item = mapper.createObjectNode();
      item.put(START_FIELD, text(share, DATE_FROM));
      item.put("end", text(share, DATE_TO));
      item.put(ALLOCATION_FACTOR_FIELD, new BigDecimal(text(share, "ECShareCalc")));
      calculated.add(item);
    }
    return value;
  }

  List<EnergySharingMeteringPoint> mergeOutsideRange(
      List<EnergySharingMeteringPoint> previous,
      List<EnergySharingMeteringPoint> incoming,
      LocalDate from,
      LocalDate to) {
    var points = new LinkedHashMap<String, EnergySharingMeteringPoint>();
    incoming.forEach(point -> points.put(point.meteringPointId(), point));
    for (var old : previous) {
      var fresh = points.get(old.meteringPointId());
      var periods = new ArrayList<JsonNode>();
      if (fresh != null) fresh.nativeParameters().path(TIME_PERIODS_FIELD).forEach(periods::add);
      for (var oldPeriod : old.nativeParameters().path(TIME_PERIODS_FIELD)) {
        var remaining = subtractPeriods(List.of(oldPeriod), from, to);
        for (var next : periods)
          remaining =
              subtractPeriods(
                  remaining,
                  EdaEnergySharingXml.date(next, START_FIELD),
                  EdaEnergySharingXml.date(next, "end"));
        periods.addAll(remaining);
      }
      if (periods.isEmpty()) continue;
      periods.sort(Comparator.comparing(n -> EdaEnergySharingXml.date(n, START_FIELD)));
      var source = fresh == null ? old : fresh;
      var nativeParams = (ObjectNode) source.nativeParameters().deepCopy();
      var array = nativeParams.putArray(TIME_PERIODS_FIELD);
      periods.forEach(array::add);
      points.put(old.meteringPointId(), pointFromPeriods(source, nativeParams));
    }
    return List.copyOf(points.values());
  }

  private List<JsonNode> subtractPeriods(List<JsonNode> periods, LocalDate from, LocalDate to) {
    var result = new ArrayList<JsonNode>();
    for (var period : periods) {
      var start = EdaEnergySharingXml.date(period, START_FIELD);
      var end = EdaEnergySharingXml.date(period, "end");
      if (end.isBefore(from) || start.isAfter(to)) {
        result.add(period);
        continue;
      }
      if (start.isBefore(from)) {
        var left = (ObjectNode) period.deepCopy();
        left.put("end", from.minusDays(1).toString());
        result.add(left);
      }
      if (end.isAfter(to)) {
        var right = (ObjectNode) period.deepCopy();
        right.put(START_FIELD, to.plusDays(1).toString());
        result.add(right);
      }
    }
    return result;
  }

  private EnergySharingMeteringPoint pointFromPeriods(
      EnergySharingMeteringPoint point, ObjectNode nativeParams) {
    var today = LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA));
    @Nullable JsonNode current = null;
    for (var p : nativeParams.path(TIME_PERIODS_FIELD))
      if (!today.isBefore(EdaEnergySharingXml.date(p, START_FIELD))
          && !today.isAfter(EdaEnergySharingXml.date(p, "end"))) current = p;
    if (current == null)
      return new EnergySharingMeteringPoint(
          point.meteringPointId(),
          point.permissionAdministratorId(),
          null,
          null,
          null,
          null,
          null,
          "INACTIVE_FOR_CURRENT_DATE",
          nativeParams,
          point.codingScheme());
    return new EnergySharingMeteringPoint(
        point.meteringPointId(),
        point.permissionAdministratorId(),
        current.path("energyDirection").asText(null),
        current.hasNonNull(PARTICIPATION_FACTOR_FIELD)
            ? current.path(PARTICIPATION_FACTOR_FIELD).decimalValue()
            : null,
        current.hasNonNull(ALLOCATION_FACTOR_FIELD)
            ? current.path(ALLOCATION_FACTOR_FIELD).decimalValue()
            : null,
        EdaEnergySharingXml.date(current, START_FIELD),
        EdaEnergySharingXml.date(current, "end"),
        "CONFIRMED",
        nativeParams,
        point.codingScheme());
  }

  private void status(String id, String state, @Nullable String nativeCode, String message) {
    store.update(
        id,
        payload -> {
          var data = (ObjectNode) mapper.readTree(payload);
          var old = readOperation(payload);
          if (!"QUEUED".equals(old.status())) return payload;
          data.set(
              OPERATION_FIELD,
              mapper.valueToTree(copy(old, state, nativeCode, message, old.result())));
          return data.toString();
        });
  }

  private EnergySharingOperation replay(EnergySharingRecord storedRow, String fingerprint) {
    if (!storedRow.fingerprint().equals(fingerprint))
      throw error(CONFLICT, "Request ID already used with a different payload");
    return readOperation(storedRow.payload());
  }

  private EnergySharingOperation readOperation(String data) {
    return mapper.treeToValue(
        mapper.readTree(data).path(OPERATION_FIELD), EnergySharingOperation.class);
  }

  private EnergySharingOperation copy(
      EnergySharingOperation old,
      String state,
      @Nullable String code,
      @Nullable String message,
      JsonNode result) {
    return new EnergySharingOperation(
        old.requestId(),
        old.connectionId(),
        old.operatorId(),
        old.communityId(),
        old.operation(),
        state,
        old.createdAt(),
        clock.instant(),
        old.marketMessageId(),
        code,
        message,
        result);
  }

  private EdaEnergySharingConfiguration.Binding binding(String operator, String connection) {
    return config.bindings().stream()
        .filter(b -> operator.equals(b.operatorId()) && connection.equals(b.connectionId()))
        .findFirst()
        .orElseThrow(() -> error(FORBIDDEN, "Operator is not authorized for this connection"));
  }

  private String community(EdaEnergySharingConfiguration.Binding b, String id) {
    if (!b.communities().containsKey(id))
      throw error(FORBIDDEN, "Community is not authorized for this operator and connection");
    return Objects.requireNonNull(b.communities().get(id));
  }

  private String key(EnergySharingRequest r) {
    return key(r.operatorId(), r.connectionId(), r.communityId(), r.requestId());
  }

  private String key(String o, String c, String ec, String r) {
    return hash(mapper.writeValueAsString(List.of(o, c, ec, r)));
  }

  private String snapshotKey(EnergySharingRequest r) {
    return key(r.operatorId(), r.connectionId(), r.communityId(), "__snapshot__");
  }

  private String scope(String o, String c) {
    return hash(mapper.writeValueAsString(List.of(o, c)));
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private String canonical(JsonNode node) {
    if (node.isObject()) {
      var out = mapper.createObjectNode();
      var fields = new TreeMap<String, JsonNode>();
      node.properties().forEach(e -> fields.put(e.getKey(), e.getValue()));
      fields.forEach((k, v) -> out.set(k, mapper.readTree(canonical(v))));
      return out.toString();
    }
    if (node.isArray()) {
      var out = mapper.createArrayNode();
      for (var v : node) out.add(mapper.readTree(canonical(v)));
      return out.toString();
    }
    return node.toString();
  }

  private static boolean allowedReply(String operation, String code) {
    return switch (operation) {
      case "JOIN" -> code.endsWith("_ECON");
      case LEAVE -> code.endsWith("_CCMS");
      case CHANGE_PARTICIPATION_FACTOR -> code.endsWith("_CPF");
      case CHANGE_ALLOCATION -> code.endsWith("_ECC");
      case SYNC_MASTER_DATA -> code.endsWith("_ECP");
      case REQUEST_MEASUREMENTS -> code.equals(ANTWORT_PT) || code.equals("ABLEHNUNG_PT");
      default -> false;
    };
  }

  private static List<Element> children(Element parent, @Nullable String name) {
    var out = new ArrayList<Element>();
    for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling())
      if (n instanceof Element e && (name == null || name.equals(e.getLocalName()))) out.add(e);
    return out;
  }

  private static List<Element> descendants(Element parent, String name) {
    var out = new ArrayList<Element>();
    var nodes = parent.getElementsByTagNameNS("*", name);
    for (int i = 0; i < nodes.getLength(); i++) out.add((Element) nodes.item(i));
    return out;
  }

  private static Element one(Element parent, String name) {
    var values = children(parent, name);
    if (values.size() != 1) throw error(INVALID_REQUEST, "Missing or duplicate XML field " + name);
    return values.getFirst();
  }

  private static String text(Element parent, String name) {
    return one(parent, name).getTextContent();
  }

  private static String textOrEmpty(Element parent, String name) {
    var values = children(parent, name);
    return values.isEmpty() ? "" : values.getFirst().getTextContent();
  }

  @Nullable
  private static String nullable(String v) {
    return v.isBlank() ? null : v;
  }

  @Nullable
  private static BigDecimal decimal(Element parent, String name) {
    var value = textOrEmpty(parent, name);
    return value.isEmpty() ? null : new BigDecimal(value);
  }

  @Nullable
  private static LocalDate localDate(Element parent, String name) {
    var value = textOrEmpty(parent, name);
    return value.isEmpty() ? null : LocalDate.parse(value.substring(0, 10));
  }

  private static EnergySharingException error(EnergySharingException.Code code, String message) {
    return new EnergySharingException(code, message);
  }

  private JsonNode schemas() {
    try (var stream = getClass().getResourceAsStream("/energy-sharing/at-eda-2026-10-05.json")) {
      if (stream == null) throw new IllegalStateException("Missing national profile schema");
      var profiles = mapper.readTree(stream);
      var today = LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA));
      ((ObjectNode) profiles.path(SYNC_MASTER_DATA).path(PROPERTIES_FIELD).path(START_FIELD))
          .put(DEFAULT_FIELD, today.minusDays(30).toString());
      ((ObjectNode) profiles.path(SYNC_MASTER_DATA).path(PROPERTIES_FIELD).path("end"))
          .put(DEFAULT_FIELD, today.plusDays(30).toString());
      for (var operation : List.of("JOIN", CHANGE_PARTICIPATION_FACTOR, CHANGE_ALLOCATION))
        ((ObjectNode) profiles.path(operation).path(PROPERTIES_FIELD).path(START_FIELD))
            .put(DEFAULT_FIELD, today.plusDays(1).toString());
      ((ObjectNode) profiles.path(LEAVE).path(PROPERTIES_FIELD).path("end"))
          .put(DEFAULT_FIELD, today.toString());
      return profiles;
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private EnergySharingRequest withSyncDefaults(EnergySharingRequest r) {
    if (!SYNC_MASTER_DATA.equals(r.operation())) return r;
    var p = (ObjectNode) r.parameters().deepCopy();
    var today = LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA));
    if (!p.hasNonNull(START_FIELD)) p.put(START_FIELD, today.minusDays(30).toString());
    if (!p.hasNonNull("end")) p.put("end", today.plusDays(30).toString());
    return new EnergySharingRequest(
        r.requestId(),
        r.connectionId(),
        r.operatorId(),
        r.communityId(),
        r.operation(),
        r.profileVersion(),
        p);
  }

  private void reserveDailyChange(EnergySharingRequest request, String operationKey) {
    var day = LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA)).toString();
    var id =
        key(
            request.operatorId(),
            "",
            request.communityId(),
            "DAILY:"
                + request.operation()
                + ":"
                + request.parameters().path(METERING_POINT_ID_FIELD).asText()
                + ":"
                + day);
    var old = store.find(id);
    if (old.isPresent()) {
      if (!old.get().fingerprint().equals(operationKey))
        throw error(
            CONFLICT,
            "A factor/allocation change for this meter and community is already reserved today");
      return;
    }
    try {
      store.insert(
          new EnergySharingRecord(
              id,
              scope(request.operatorId(), request.connectionId()),
              "DAILY_CHANGE",
              operationKey,
              "DAY" + id.substring(0, 30),
              "{}"));
    } catch (RuntimeException e) {
      if (store.find(id).filter(row -> row.fingerprint().equals(operationKey)).isEmpty())
        throw error(CONFLICT, "A change for this meter and community is already reserved today");
    }
  }

  private void receiveRevocation(String code, byte[] xml) {
    var revocation = readRevocation(code, xml);
    for (var binding : config.bindings()) {
      if (revocation.operator().equals(binding.operatorId()))
        applyRevocation(binding, revocation, xml);
    }
  }

  private record AuthorityTime(String value, Instant instant) {}

  private record Revocation(
      String sender,
      String operator,
      String meter,
      String consent,
      LocalDate end,
      String revision,
      AuthorityTime authority) {
    String eventKey() {
      return hash(sender + "|" + operator + "|" + consent + "|" + revision);
    }
  }

  private Revocation readRevocation(String code, byte[] xml) {
    EdaEnergySharingXml.validate(xml, "CMRevoke_01p10.xsd");
    var root = EdaEnergySharingXml.parse(xml).getDocumentElement();
    var directory = one(root, PROCESS_DIRECTORY);
    var participants = one(root, MARKET_PARTICIPANT_DIRECTORY);
    var route = one(participants, ROUTING_HEADER);
    if (!code.equals(text(participants, "MessageCode")))
      throw error(INVALID_REQUEST, "Revocation message code mismatch");
    var authorityValue = text(route, DOCUMENT_CREATION_DATE_TIME);
    var authority =
        new AuthorityTime(authorityValue, OffsetDateTime.parse(authorityValue).toInstant());
    return new Revocation(
        text(one(route, "Sender"), MESSAGE_ADDRESS),
        text(one(route, "Receiver"), MESSAGE_ADDRESS),
        text(directory, METERING_POINT),
        text(directory, CONSENT_ID),
        Objects.requireNonNull(localDate(directory, "ConsentEnd")),
        text(directory, "MessageId"),
        authority);
  }

  private void applyRevocation(
      EdaEnergySharingConfiguration.Binding binding, Revocation revocation, byte[] xml) {
    for (var row : store.records(scope(binding.operatorId(), binding.connectionId()), SNAPSHOT)) {
      var prior =
          mapper.treeToValue(
              mapper.readTree(row.payload()).path(SNAPSHOT_FIELD),
              EnergySharingCommunitySnapshot.class);
      if (revocation.sender().equals(binding.communities().get(prior.communityId()))
          && prior.meteringPoints().stream()
              .anyMatch(point -> matchesRevocation(point, revocation))) {
        store.update(row.id(), payload -> revokedSnapshotPayload(payload, revocation, xml));
      }
    }
  }

  private static boolean matchesRevocation(
      EnergySharingMeteringPoint point, Revocation revocation) {
    return point.meteringPointId().equals(revocation.meter())
        && revocation.consent().equals(point.nativeParameters().path(CONSENT_ID_FIELD).asText());
  }

  private String revokedSnapshotPayload(String payload, Revocation revocation, byte[] xml) {
    var wrapper = (ObjectNode) mapper.readTree(payload);
    var snapshot =
        mapper.treeToValue(wrapper.path(SNAPSHOT_FIELD), EnergySharingCommunitySnapshot.class);
    var seen =
        wrapper.has(SEEN_REVOCATIONS_FIELD)
            ? (ArrayNode) wrapper.path(SEEN_REVOCATIONS_FIELD)
            : wrapper.putArray(SEEN_REVOCATIONS_FIELD);
    var eventKey = revocation.eventKey();
    for (var entry : seen) {
      if (entry.asText().equals(eventKey)) return payload;
    }
    seen.add(eventKey);
    if (wrapper.hasNonNull(AUTHORITY_CREATED_AT_FIELD)
        && revocation
            .authority()
            .instant()
            .isBefore(
                OffsetDateTime.parse(wrapper.path(AUTHORITY_CREATED_AT_FIELD).asText())
                    .toInstant())) return wrapper.toString();
    var changed =
        snapshot.meteringPoints().stream()
            .map(point -> revokePoint(point, revocation, xml))
            .toList();
    var updated =
        new EnergySharingCommunitySnapshot(
            snapshot.communityId(),
            snapshot.operatorId(),
            snapshot.connectionId(),
            revocation.revision(),
            clock.instant(),
            snapshot.complete(),
            snapshot.name(),
            snapshot.allocationMethod(),
            changed,
            snapshot.nativeParameters());
    wrapper.put(REQUESTED_AT_FIELD, clock.instant().toString());
    wrapper.put(AUTHORITY_CREATED_AT_FIELD, revocation.authority().value());
    wrapper.set(SNAPSHOT_FIELD, mapper.valueToTree(updated));
    return wrapper.toString();
  }

  private EnergySharingMeteringPoint revokePoint(
      EnergySharingMeteringPoint point, Revocation revocation, byte[] xml) {
    if (!matchesRevocation(point, revocation)) return point;
    var nativeParams = (ObjectNode) point.nativeParameters().deepCopy();
    nativeParams.put("revocationXml", new String(xml, StandardCharsets.UTF_8));
    nativeParams.set(
        TIME_PERIODS_FIELD,
        revokedPeriods(point.nativeParameters().path(TIME_PERIODS_FIELD), revocation.end()));
    String state =
        revocation.end().isBefore(LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA)))
            ? "ENDED"
            : "ENDING";
    return new EnergySharingMeteringPoint(
        point.meteringPointId(),
        point.permissionAdministratorId(),
        point.direction(),
        point.participationFactor(),
        point.allocationFactor(),
        point.start(),
        revocation.end(),
        state,
        nativeParams,
        StandardCodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME.value());
  }

  private ArrayNode revokedPeriods(JsonNode original, LocalDate end) {
    var periods = mapper.createArrayNode();
    for (var period : original) {
      if (!EdaEnergySharingXml.date(period, START_FIELD).isAfter(end)) {
        var adjusted = (ObjectNode) period.deepCopy();
        if (EdaEnergySharingXml.date(period, "end").isAfter(end))
          adjusted.put("end", end.toString());
        adjusted.put("deactivate", end.toString());
        periods.add(adjusted);
      }
    }
    return periods;
  }

  private void validateParameters(EnergySharingRequest r) {
    validateKeys(r.parameters(), schemas().path(r.operation()));
    if (r.operation().equals(CHANGE_PARTICIPATION_FACTOR)) {
      for (var period : r.parameters().path(TIME_PERIODS_FIELD))
        if (period.hasNonNull(ALLOCATION_FACTOR_FIELD))
          throw error(
              INVALID_REQUEST,
              "Use CHANGE_ALLOCATION to change ECShare; CPF changes participation only");
    }
  }

  private static void validateKeys(JsonNode value, JsonNode schema) {
    if (!value.isObject()) throw error(INVALID_REQUEST, "parameters must be an object");
    for (var entry : value.properties()) {
      var property = schema.path(PROPERTIES_FIELD).get(entry.getKey());
      if (property == null)
        throw error(
            INVALID_REQUEST, "Unknown or inapplicable national parameter: " + entry.getKey());
      if (entry.getValue().isArray()
          && property.path("items").path("type").asText().equals("object"))
        for (var item : entry.getValue()) validateKeys(item, property.path("items"));
    }
  }
}
