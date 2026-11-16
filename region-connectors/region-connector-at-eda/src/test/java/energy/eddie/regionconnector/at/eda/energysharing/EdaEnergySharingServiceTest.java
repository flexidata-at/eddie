// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.*;
import de.ponton.xp.adapter.api.messages.OutboundMessage;
import energy.eddie.regionconnector.at.eda.config.AtConfiguration;
import energy.eddie.regionconnector.at.eda.persistence.EnergySharingRecord;
import energy.eddie.regionconnector.at.eda.ponton.messenger.MessengerStatus;
import energy.eddie.regionconnector.at.eda.ponton.messenger.PontonMessengerConnection;
import energy.eddie.regionconnector.at.eda.requests.EdaGroupingIdFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EdaEnergySharingServiceTest {
    private static final String OPERATOR = "RC123456";
    private static final String CONNECTION = "application-one";
    private static final String DSO = "AT001000";
    private static final String EC = "AT0000000000000000000000000000001";
    private static final String METER = "AT0000000000000000000000000000002";
    private static final String SECOND_METER = "AT0000000000000000000000000000003";
    private static final String THIRD_METER = "AT0000000000000000000000000000004";
    private static final String COMMON = "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final EdaEnergySharingStore store = mock(EdaEnergySharingStore.class);
    private final PontonMessengerConnection transport = mock(PontonMessengerConnection.class);
    private final Map<String, EnergySharingRecord> records = new LinkedHashMap<>();
    private final MovingClock clock = new MovingClock();
    private final EdaGroupingIdFactory groupingIds =
            new EdaGroupingIdFactory(new AtConfiguration("EP100022", null, "DATADEV"));
    private EdaEnergySharingService service;

    @BeforeEach
    void setup() {
        when(store.find(anyString())).thenAnswer(call -> Optional.ofNullable(records.get(call.getArgument(0))));
        when(store.byMarketId(anyString())).thenAnswer(call -> records.values().stream()
                .filter(row -> row.marketId().equals(call.getArgument(0))).findFirst());
        when(store.records(anyString(), anyString())).thenAnswer(call -> records.values().stream()
                .filter(row -> row.scope().equals(call.getArgument(0)) && row.kind().equals(call.getArgument(1))).toList());
        doAnswer(call -> {
            EnergySharingRecord value = call.getArgument(0);
            if (records.putIfAbsent(value.id(), value) != null) throw new IllegalStateException("Duplicate durable key");
            return null;
        }).when(store).insert(any());
        doAnswer(call -> {
            var row = Objects.requireNonNull(records.get(call.getArgument(0)));
            UnaryOperator<String> update = call.getArgument(1);
            row.payload(update.apply(row.payload()));
            return null;
        }).when(store).update(anyString(), any());
        when(transport.supportsEnergySharing()).thenReturn(true);
        when(transport.messengerStatus()).thenReturn(new MessengerStatus(Map.of(), true));
        var binding = new EdaEnergySharingConfiguration.Binding(CONNECTION, OPERATOR, Map.of(EC, DSO), true);
        service = new EdaEnergySharingService(new EdaEnergySharingConfiguration(List.of(binding)), store, transport, mapper, groupingIds, clock);
    }

    @Test
    void officialDataRequestIsScopedDurableAndAckDoesNotClaimDataCoverage() throws Exception {
        var query = service.submit(request("roster", "SYNC_MASTER_DATA", mapper.createObjectNode()));
        var period = period("2026-01-01", "2026-12-31", 100).replace("<cp:DateActivate>", "<cp:TransmissionCycle>D</cp:TransmissionCycle><cp:DateActivate>");
        service.receive("SENDEN_ECP", list(query.marketMessageId(), "MasterBeforeRequest", "SENDEN_ECP", member(METER, period), DSO));
        var measurements = mock(EdaOperatorMeasurementService.class);
        when(measurements.requestDelivery(eq(OPERATOR), eq(CONNECTION), eq(EC), anyString(), eq(METER)))
                .thenReturn(new EdaOperatorMeasurementStore.Delivery(0, 0));
        service.setOperatorMeasurements(measurements);
        doAnswer(call -> {
            EnergySharingRecord entry = call.getArgument(0);
            var snapshot = records.get(call.getArgument(1));
            UnaryOperator<String> authorize = call.getArgument(2);
            entry.payload(authorize.apply(snapshot.payload()));
            records.put(entry.id(), entry);
            return null;
        }).when(store).insertAuthorized(any(), anyString(), any());
        var command = request("missing-data", "REQUEST_MEASUREMENTS", mapper.createObjectNode()
                .put("meteringPointId", METER).put("start", "2026-10-01").put("end", "2026-10-31"));
        clearInvocations(transport);
        var submitted = service.submit(command);
        assertEquals("SUBMITTED", submitted.status());
        service.submit(command);
        verify(transport, times(1)).sendEnergySharing(any());
        var captured = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(transport).sendEnergySharing(captured.capture());
        var outbound = captured.getValue().getInputStream().readAllBytes();
        EdaEnergySharingXml.validate(outbound, "CPRequest_01p12.xsd");
        assertTrue(new String(outbound, StandardCharsets.UTF_8).contains("ANFORDERUNG_PT"));
        var wrongReference = processNotification(submitted.marketMessageId(), "WrongOriginal", "ANTWORT_PT", 70);
        wrongReference = new String(wrongReference, StandardCharsets.UTF_8).replace("<cp:OriginalMessageID>" + submitted.marketMessageId(), "<cp:OriginalMessageID>WrongId").getBytes(StandardCharsets.UTF_8);
        byte[] invalidReference = wrongReference;
        assertThrows(EnergySharingException.class, () -> service.receive("ANTWORT_PT", invalidReference));
        byte[] invalidCode = processNotification(submitted.marketMessageId(), "WrongCode", "ANTWORT_PT", 94);
        assertThrows(EnergySharingException.class, () -> service.receive("ANTWORT_PT", invalidCode));
        byte[] accepted = processNotification(submitted.marketMessageId(), "AcceptedRequest", "ANTWORT_PT", 70);
        assertTrue(service.receive("ANTWORT_PT", accepted));
        assertTrue(service.receive("ANTWORT_PT", accepted));
        var status = service.operation("missing-data", CONNECTION, OPERATOR, EC).orElseThrow();
        assertEquals("ACKNOWLEDGED", status.status());
        assertEquals("REQUEST_ACCEPTED", status.result().path("stage").asText());
        assertEquals("NO_MATCHING_DOCUMENTS", status.result().path("deliveryStatus").asText());
        assertFalse(status.result().path("coverageComplete").asBoolean());
        when(measurements.requestDelivery(OPERATOR, CONNECTION, EC, submitted.marketMessageId(), METER))
                .thenReturn(new EdaOperatorMeasurementStore.Delivery(2, 192));
        var delivered = service.operation("missing-data", CONNECTION, OPERATOR, EC).orElseThrow();
        assertEquals("ACKNOWLEDGED", delivered.status());
        assertEquals("DOCUMENTS_RECEIVED", delivered.result().path("deliveryStatus").asText());
        assertEquals(2, delivered.result().path("receivedDocumentCount").asInt());
        assertFalse(delivered.result().path("coverageComplete").asBoolean());
        assertTrue(service.receive("ABLEHNUNG_PT", processNotification(submitted.marketMessageId(), "NoData", "ABLEHNUNG_PT", 94)));
        assertEquals("REJECTED", service.operation("missing-data", CONNECTION, OPERATOR, EC).orElseThrow().status());
        verify(transport, times(1)).sendEnergySharing(any()); // No follow-up market query on acknowledgement/read.
        var stored = records.values().stream().filter(row -> row.marketId().equals(submitted.marketMessageId())).findFirst().orElseThrow();
        assertEquals(METER, mapper.readTree(stored.payload()).path("authorityEvidence").path("meteringPointId").asText());
    }

    @Test
    void dataRequestWithoutRosterOrWithUnknownParameterNeverSends() throws Exception {
        var command = request("no-roster", "REQUEST_MEASUREMENTS", mapper.createObjectNode()
                .put("meteringPointId", METER).put("start", "2026-10-01").put("end", "2026-10-31"));
        assertEquals(EnergySharingException.Code.NOT_READY, assertThrows(EnergySharingException.class, () -> service.submit(command)).code());
        var unknown = request("unknown", "REQUEST_MEASUREMENTS", mapper.createObjectNode()
                .put("meteringPointId", METER).put("start", "2026-10-01").put("end", "2026-10-31").put("consentId", "untrusted"));
        assertThrows(EnergySharingException.class, () -> service.submit(unknown));
        assertFalse(service.receive("ANTWORT_PT", processNotification("UnrelatedCESU", "Legacy", "ANTWORT_PT", 70)));
        verify(transport, never()).sendEnergySharing(any());
        assertTrue(records.isEmpty());
    }

    @Test
    void scopeAndParameterErrorsNeverReachTransport() throws Exception {
        var foreign = new EnergySharingRequest("foreign", "another-application", OPERATOR, EC,
                "SYNC_MASTER_DATA", EdaEnergySharingXml.VERSION, mapper.createObjectNode());
        assertEquals(EnergySharingException.Code.FORBIDDEN,
                assertThrows(EnergySharingException.class, () -> service.submit(foreign)).code());
        var invalid = request("invalid", "SYNC_MASTER_DATA",
                mapper.createObjectNode().put("unmappedParameter", "must not disappear"));
        assertThrows(EnergySharingException.class, () -> service.submit(invalid));
        assertTrue(records.isEmpty());
        verify(transport, never()).sendEnergySharing(any());
    }

    @Test
    void replayUsesDurableIdentityWithoutSendingAgainOrChangingPayload() throws Exception {
        var request = request("query", "SYNC_MASTER_DATA", mapper.createObjectNode());
        var submitted = service.submit(request);
        assertEquals("SUBMITTED", submitted.status());
        assertEquals(submitted, service.submit(request));
        verify(transport, times(1)).sendEnergySharing(any());
        var changed = request("query", "SYNC_MASTER_DATA", mapper.createObjectNode().put("start", "2026-11-01"));
        assertEquals(EnergySharingException.Code.CONFLICT,
                assertThrows(EnergySharingException.class, () -> service.submit(changed)).code());
        assertThrows(EnergySharingException.class,
                () -> service.operation("query", "another-application", OPERATOR, EC));
    }

    @Test
    void sharedMessengerRoutingPrefixPreservesWireCorrelationAndTenantScope() throws Exception {
        var first = service.submit(request("routed-one", "SYNC_MASTER_DATA", mapper.createObjectNode()));
        var second = service.submit(request("routed-two", "SYNC_MASTER_DATA", mapper.createObjectNode()));
        assertNotEquals(first.marketMessageId(), second.marketMessageId());
        var messages = ArgumentCaptor.forClass(OutboundMessage.class);
        verify(transport, times(2)).sendEnergySharing(messages.capture());
        var operations = List.of(first, second);
        for (int index = 0; index < operations.size(); index++) {
            var operation = operations.get(index);
            var id = Objects.requireNonNull(operation.marketMessageId());
            assertTrue(id.startsWith("DATADEV"));
            assertTrue(id.matches("[0-9A-Za-z]{1,35}"));
            var xml = messages.getAllValues().get(index).getInputStream().readAllBytes();
            EdaEnergySharingXml.validate(xml, "CPRequest_01p12.xsd");
            var document = EdaEnergySharingXml.parse(xml);
            assertEquals(id, document.getElementsByTagNameNS(COMMON, "ConversationId").item(0).getTextContent());
            assertEquals(id, document.getElementsByTagNameNS(COMMON, "MessageId").item(0).getTextContent());
        }
        assertTrue(service.receive("SENDEN_ECP", list(first.marketMessageId(), "RoutedReply", "SENDEN_ECP", "", DSO)));
        assertEquals("COMPLETED", service.operation("routed-one", CONNECTION, OPERATOR, EC).orElseThrow().status());
        assertEquals("SUBMITTED", service.operation("routed-two", CONNECTION, OPERATOR, EC).orElseThrow().status());
        assertFalse(service.receive("SENDEN_ECP", list("DATADEVUnknownConversation", "UnownedReply", "SENDEN_ECP", "", DSO)));
        assertThrows(EnergySharingException.class,
                () -> service.operation("routed-one", "another-application", OPERATOR, EC));
    }

    @Test
    void uncertainTransportIsRetainedAndNeverBlindlyResent() throws Exception {
        doThrow(new IllegalStateException("Lost transport acknowledgement")).when(transport).sendEnergySharing(any());
        var request = request("uncertain", "SYNC_MASTER_DATA", mapper.createObjectNode());
        assertEquals("RECONCILIATION_REQUIRED", service.submit(request).status());
        assertEquals("RECONCILIATION_REQUIRED", service.submit(request).status());
        verify(transport, times(1)).sendEnergySharing(any());
    }

    @Test
    void missingPartnerAndTransportSetupNeverDispatchesMarketRequests() throws Exception {
        service = configuredService(Map.of(), false);
        when(transport.supportsEnergySharing()).thenReturn(false);
        var capability = service.capabilities(OPERATOR, CONNECTION);
        assertFalse(capability.transportReady());
        assertTrue(capability.setupSteps().stream().anyMatch(step -> step.contains("EC_LIST")));
        assertTrue(capability.setupSteps().stream().anyMatch(step -> step.contains("X/P")));
        assertTrue(capability.setupSteps().stream().anyMatch(step -> step.contains("REST")));
        var command = request("not-ready", "SYNC_MASTER_DATA", mapper.createObjectNode());
        assertEquals(EnergySharingException.Code.NOT_READY,
                assertThrows(EnergySharingException.class, () -> service.submit(command)).code());
        service = configuredService(Map.of(EC, DSO), true);
        when(transport.supportsEnergySharing()).thenReturn(true);
        when(transport.messengerStatus()).thenReturn(new MessengerStatus(Map.of(), false));
        assertFalse(service.capabilities(OPERATOR, CONNECTION).transportReady());
        assertTrue(service.capabilities(OPERATOR, CONNECTION).setupSteps().getFirst().contains("Restore"));
        assertTrue(records.isEmpty());
        verify(transport, never()).sendEnergySharing(any());
    }

    @Test
    void staleProfilesUnsupportedOperationsAndWrongDsoAreRejectedBeforeDispatch() throws Exception {
        var oldProfile = new EnergySharingRequest("old-profile", CONNECTION, OPERATOR, EC, "SYNC_MASTER_DATA", "obsolete", mapper.createObjectNode());
        var unsupported = request("unsupported", "SETTLE_TRADE", mapper.createObjectNode());
        var wrongDso = request("wrong-dso", "SYNC_MASTER_DATA", mapper.createObjectNode().put("permissionAdministratorId", "AT002000"));
        assertEquals(EnergySharingException.Code.INVALID_REQUEST,
                assertThrows(EnergySharingException.class, () -> service.submit(oldProfile)).code());
        assertEquals(EnergySharingException.Code.UNSUPPORTED,
                assertThrows(EnergySharingException.class, () -> service.submit(unsupported)).code());
        assertEquals(EnergySharingException.Code.FORBIDDEN,
                assertThrows(EnergySharingException.class, () -> service.submit(wrongDso)).code());
        verify(transport, never()).sendEnergySharing(any());
        assertTrue(records.isEmpty());
    }

    @Test
    void configuredCommunityFanoutWaitsForEveryChildAndReplayDoesNotResend() throws Exception {
        String secondCommunity = "AT0000000000000000000000000000009";
        service = configuredService(Map.of(EC, DSO, secondCommunity, DSO), true);
        var group = groupRequest("group");
        var submitted = service.submit(group);
        assertEquals(2, submitted.result().path("children").size());
        assertEquals(2, submitted.result().path("operations").size());
        var first = service.operation("group-1", CONNECTION, OPERATOR, EC).orElseThrow();
        var second = service.operation("group-2", CONNECTION, OPERATOR, secondCommunity).orElseThrow();
        assertTrue(service.receive("SENDEN_ECP", list(first.marketMessageId(), "FirstCommunity", "SENDEN_ECP", "", DSO)));
        assertEquals("SUBMITTED", service.operation("group", CONNECTION, OPERATOR, "").orElseThrow().status());
        var secondReply = new String(list(second.marketMessageId(), "SecondCommunity", "SENDEN_ECP", "", DSO), StandardCharsets.UTF_8)
                .replace(EC, secondCommunity).getBytes(StandardCharsets.UTF_8);
        assertTrue(service.receive("SENDEN_ECP", secondReply));
        assertEquals("COMPLETED", service.submit(group).status());
        assertEquals(2, service.communities(OPERATOR, CONNECTION).size());
        verify(transport, times(2)).sendEnergySharing(any());
    }

    @Test
    void groupedQueryReplayRecoversOnlyAChildThatWasNeverPersisted() throws Exception {
        String secondCommunity = "AT0000000000000000000000000000009";
        service = configuredService(Map.of(EC, DSO, secondCommunity, DSO), true);
        var firstAttempt = new AtomicBoolean(true);
        doAnswer(call -> {
            EnergySharingRecord row = call.getArgument(0);
            var id = mapper.readTree(row.payload()).path("request").path("requestId").asText();
            if (id.equals("recover-2") && firstAttempt.getAndSet(false))
                throw new IllegalStateException("Simulated child persistence interruption");
            if (records.putIfAbsent(row.id(), row) != null) throw new IllegalStateException("Duplicate durable key");
            return null;
        }).when(store).insert(any());
        var group = groupRequest("recover");
        assertEquals("RECONCILIATION_REQUIRED", service.submit(group).status());
        assertTrue(service.operation("recover-2", CONNECTION, OPERATOR, secondCommunity).isEmpty());
        assertEquals(2, service.submit(group).result().path("operations").size());
        verify(transport, times(2)).sendEnergySharing(any());
        var rejected = service.operation("recover-2", CONNECTION, OPERATOR, secondCommunity).orElseThrow();
        assertTrue(service.receive("ABLEHNUNG_ECP", processNotification(rejected.marketMessageId(), "RejectedChild", "ABLEHNUNG_ECP", 188)));
        assertEquals("RECONCILIATION_REQUIRED", service.operation("recover", CONNECTION, OPERATOR, "").orElseThrow().status());
        assertEquals("REJECTED", service.operation("recover-2", CONNECTION, OPERATOR, secondCommunity).orElseThrow().status());
    }

    @Test
    void transportCallbacksReconcilePendingRequestsButNeverRegressCompletedOnes() {
        var captured = ArgumentCaptor.forClass(EnergySharingMessageHandler.class);
        verify(transport).withEnergySharingHandler(captured.capture());
        var handler = captured.getValue();
        var submitted = query("callback", "2026-01-01", "2026-12-31");
        assertTrue(handler.accepts("SENDEN_ECP"));
        assertTrue(handler.accepts("AUFHEBUNG_CCMI"));
        assertFalse(handler.accepts("UNRELATED"));
        handler.transportStatus("unknown", "FAILED");
        handler.transportStatus(submitted.marketMessageId(), "SUCCESS");
        assertEquals("SUBMITTED", operation("callback").status());
        handler.transportStatus(submitted.marketMessageId(), "TRANSMISSION_ERROR");
        assertEquals("RECONCILIATION_REQUIRED", operation("callback").status());
        assertTrue(handler.handle("SENDEN_ECP", list(submitted.marketMessageId(), "CallbackSnapshot", "SENDEN_ECP", "", DSO)));
        assertEquals("COMPLETED", operation("callback").status());
        handler.transportStatus(submitted.marketMessageId(), "FAILED");
        assertEquals("COMPLETED", operation("callback").status());
    }

    @Test
    void allocationChangeRequiresAnAuthoritativeStaticCommunity() throws Exception {
        var parameters = mapper.createObjectNode().put("meteringPointId", METER).put("start", "2026-11-16").put("distributionModel", "S");
        parameters.putArray("timePeriods").addObject().put("start", "2026-11-16").put("end", "2026-12-31")
                .put("activate", "2026-01-01").put("dataType", "EnergyCommunityRegistration").put("allocationFactor", 25.125);
        var change = request("static-allocation", "CHANGE_ALLOCATION", parameters);
        assertEquals(EnergySharingException.Code.INVALID_REQUEST,
                assertThrows(EnergySharingException.class, () -> service.submit(change)).code());
        seedAnnualRoster();
        assertEquals(EnergySharingException.Code.INVALID_REQUEST,
                assertThrows(EnergySharingException.class, () -> service.submit(change)).code());
        clock.advance();
        var sync = query("static-roster", "2026-01-01", "2026-12-31");
        var staticReply = new String(list(sync.marketMessageId(), "StaticSnapshot", "SENDEN_ECP",
                member(METER, period("2026-01-01", "2026-12-31", 50)), DSO), StandardCharsets.UTF_8)
                .replace("<cp:ECDisModel>D</cp:ECDisModel>", "<cp:ECDisModel>S</cp:ECDisModel>").getBytes(StandardCharsets.UTF_8);
        assertTrue(service.receive("SENDEN_ECP", staticReply));
        assertEquals("SUBMITTED", service.submit(change).status());
        verify(transport, times(3)).sendEnergySharing(any());
    }

    @Test
    void consentIsNotCompletionAndDelayedMessagesCannotUndoCompletion() {
        var join = request("join", "JOIN", mapper.createObjectNode().put("meteringPointId", METER)
                .put("start", "2026-11-16").put("energyDirection", "CONSUMPTION").put("participationFactor", 25));
        var operation = service.submit(join);
        var consent = notification(operation.marketMessageId(), "ConsentReply", "ZUSTIMMUNG_ECON", 175, DSO);
        assertTrue(service.receive("ZUSTIMMUNG_ECON", consent));
        assertEquals("ACKNOWLEDGED", operation("join").status());
        assertTrue(service.communities(OPERATOR, CONNECTION).isEmpty());
        clock.advance();
        assertTrue(service.receive("ANTWORT_ECON", notification(operation.marketMessageId(), "LateAck", "ANTWORT_ECON", 99, DSO)));
        assertEquals("ACKNOWLEDGED", operation("join").status());
        assertTrue(service.receive("ZUSTIMMUNG_ECON", consent));
        assertEquals(2, operationRecord("join").path("events").size());
        clock.advance();
        assertTrue(service.receive("ABSCHLUSS_ECON", list(operation.marketMessageId(), "CompletedJoin", "ABSCHLUSS_ECON",
                member(METER, period("2026-11-16", "2026-12-31", 25)), DSO)));
        assertEquals("COMPLETED", operation("join").status());
        assertTrue(service.receive("ABLEHNUNG_ECON", notification(operation.marketMessageId(), "LateRejection", "ABLEHNUNG_ECON", 188, DSO)));
        assertEquals("COMPLETED", operation("join").status());
    }

    @Test
    void onlySafeHttpsMarketApprovalLinksBecomeActionableAndConsentReferenceIsRetained() {
        var urls = List.of("https://dso.example/approve", "http://dso.example/approve",
                "javascript:alert(1)", "https://user:pass@dso.example/approve");
        for (int index = 0; index < urls.size(); index++) {
            var id = "approval-link-" + index;
            var join = request(id, "JOIN", mapper.createObjectNode().put("meteringPointId", METER)
                    .put("start", "2026-11-16").put("energyDirection", "CONSUMPTION").put("participationFactor", 25));
            var submitted = service.submit(join);
            var xml = new String(notification(submitted.marketMessageId(), "ApprovalLink" + index,
                    "ANTWORT_ECON", 99, DSO), StandardCharsets.UTF_8)
                    .replace("</cp:ProcessDirectory>", "<cp:PortalApprovalURL>" + urls.get(index)
                            + "</cp:PortalApprovalURL></cp:ProcessDirectory>").getBytes(StandardCharsets.UTF_8);
            assertTrue(service.receive("ANTWORT_ECON", xml));
            var result = operation(id).result();
            assertEquals("CONSENT1", result.path("consentId").asText());
            if (index == 0) assertEquals(urls.getFirst(), result.path("portalApprovalUrl").asText());
            else assertFalse(result.hasNonNull("portalApprovalUrl"));
            assertTrue(result.path("nativeXml").asText().contains(urls.get(index)));
            assertEquals("ACKNOWLEDGED", operation(id).status());
        }
    }

    @Test
    void foreignAndMalformedRepliesDoNotMutateOperationOrRoster() {
        var operation = query("scope", "2026-11-01", "2026-11-30");
        var foreign = list(operation.marketMessageId(), "ForeignSender", "SENDEN_ECP", "", "AT002000");
        assertEquals(EnergySharingException.Code.FORBIDDEN,
                assertThrows(EnergySharingException.class, () -> service.receive("SENDEN_ECP", foreign)).code());
        var valid = list(operation.marketMessageId(), "InvalidNamespace", "SENDEN_ECP", "", DSO);
        var malformed = new String(valid, StandardCharsets.UTF_8)
                .replace("http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p20", "urn:invalid")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(EnergySharingException.class, () -> service.receive("SENDEN_ECP", malformed));
        assertEquals("SUBMITTED", operation("scope").status());
        assertTrue(service.communities(OPERATOR, CONNECTION).isEmpty());
        assertEquals(0, operationRecord("scope").path("events").size());
    }

    @Test
    void duplicateAndOlderQueryResponsesCannotReplaceLatestRoster() {
        var older = query("older", "2026-01-01", "2026-12-31");
        clock.advance();
        var newer = query("newer", "2026-01-01", "2026-12-31");
        var fresh = list(newer.marketMessageId(), "FreshSnapshot", "SENDEN_ECP", member(METER, period("2026-01-01", "2026-12-31", 60)), DSO);
        assertTrue(service.receive("SENDEN_ECP", fresh));
        clock.advance();
        assertTrue(service.receive("SENDEN_ECP", list(older.marketMessageId(), "OlderSnapshot", "SENDEN_ECP",
                member(METER, period("2026-01-01", "2026-12-31", 10)), DSO)));
        assertTrue(service.receive("SENDEN_ECP", fresh));
        var snapshot = service.communities(OPERATOR, CONNECTION).getFirst();
        assertEquals("FreshSnapshot", snapshot.revision());
        assertEquals(60, snapshot.meteringPoints().getFirst().participationFactor().intValueExact());
        assertEquals(1, operationRecord("newer").path("events").size());
    }

    @Test
    void historicalEmptyQueryCompletesWithoutErasingCurrentRoster() {
        seedAnnualRoster();
        clock.advance();
        var historical = query("historical", "2025-01-01", "2025-12-31");
        assertTrue(service.receive("SENDEN_ECP", list(historical.marketMessageId(), "HistoricalEmpty", "SENDEN_ECP", "", DSO)));
        assertEquals("COMPLETED", operation("historical").status());
        assertEquals("AnnualSnapshot", service.communities(OPERATOR, CONNECTION).getFirst().revision());
        assertEquals(3, service.communities(OPERATOR, CONNECTION).getFirst().meteringPoints().size());
    }

    @Test
    void narrowerCurrentResponsePreservesOtherPeriodsForPresentAndMissingMeters() {
        seedAnnualRoster();
        clock.advance();
        var query = query("narrow", "2026-11-01", "2026-11-30");
        assertTrue(service.receive("SENDEN_ECP", list(query.marketMessageId(), "NarrowSnapshot", "SENDEN_ECP",
                member(METER, period("2026-11-01", "2026-11-30", 50)), DSO)));
        var points = service.communities(OPERATOR, CONNECTION).getFirst().meteringPoints();
        assertEquals(2, points.size());
        var present = points.stream().filter(point -> METER.equals(point.meteringPointId())).findFirst().orElseThrow();
        assertEquals(50, present.participationFactor().intValueExact());
        assertEquals(List.of("2026-01-01/2026-10-31", "2026-11-01/2026-11-30", "2026-12-01/2026-12-31"), periods(present));
        var absent = points.stream().filter(point -> SECOND_METER.equals(point.meteringPointId())).findFirst().orElseThrow();
        assertEquals("INACTIVE_FOR_CURRENT_DATE", absent.status());
        assertEquals(List.of("2026-01-01/2026-10-31", "2026-12-01/2026-12-31"), periods(absent));
    }

    @Test
    void completedEmptyCurrentRangeRemovesOnlyCoveredMembershipPeriods() {
        seedAnnualRoster();
        clock.advance();
        var query = query("empty", "2026-11-01", "2026-11-30");
        assertTrue(service.receive("SENDEN_ECP", list(query.marketMessageId(), "CurrentEmpty", "SENDEN_ECP", "", DSO)));
        var points = service.communities(OPERATOR, CONNECTION).getFirst().meteringPoints();
        assertEquals(2, points.size());
        for (var point : points) {
            assertEquals("INACTIVE_FOR_CURRENT_DATE", point.status());
            assertEquals(List.of("2026-01-01/2026-10-31", "2026-12-01/2026-12-31"), periods(point));
        }
    }

    @Test
    void unrelatedLegacyMessagesRemainAvailableToTheirExistingHandler() {
        assertFalse(service.receive("UNRELATED_MESSAGE", "not XML".getBytes(StandardCharsets.UTF_8)));
        assertFalse(service.receive("ZUSTIMMUNG_ECON", notification("LegacyConversation", "LegacyResponse", "ZUSTIMMUNG_ECON", 175, DSO)));
        assertTrue(records.isEmpty());
    }

    @Test
    void factorChangeDailyGuardCannotBeBypassedWithAnotherRequestOrConnection() throws Exception {
        var parameters = mapper.createObjectNode().put("meteringPointId", METER).put("start", "2026-11-16");
        parameters.putArray("timePeriods").addObject().put("start", "2026-11-16").put("end", "2026-12-31")
                .put("activate", "2026-01-01").put("dataType", "EnergyCommunityRegistration").put("participationFactor", 40);
        var first = request("factor-first", "CHANGE_PARTICIPATION_FACTOR", parameters);
        assertEquals("SUBMITTED", service.submit(first).status());
        assertEquals("SUBMITTED", service.submit(first).status());
        var second = request("factor-second", "CHANGE_PARTICIPATION_FACTOR", parameters);
        assertEquals(EnergySharingException.Code.CONFLICT, assertThrows(EnergySharingException.class,
                () -> service.submit(second)).code());

        var secondConnection = "application-two";
        var otherService = new EdaEnergySharingService(new EdaEnergySharingConfiguration(List.of(
                new EdaEnergySharingConfiguration.Binding(secondConnection, OPERATOR, Map.of(EC, DSO), true))),
                store, transport, mapper, groupingIds, clock);
        var crossConnection = new EnergySharingRequest("factor-third", secondConnection, OPERATOR, EC,
                "CHANGE_PARTICIPATION_FACTOR", EdaEnergySharingXml.VERSION, parameters);
        assertEquals(EnergySharingException.Code.CONFLICT,
                assertThrows(EnergySharingException.class, () -> otherService.submit(crossConnection)).code());
        verify(transport, times(1)).sendEnergySharing(any());
    }

    @Test
    void unsolicitedRevocationTrimsOnlyMatchingMembershipAndStaysDeduplicatedAfterResync() {
        seedAnnualRoster();
        var originalConfirmation = service.communities(OPERATOR, CONNECTION).getFirst().nativeParameters().path("masterDataReceivedAt").asText();
        assertFalse(originalConfirmation.isBlank());
        clock.advance();
        var revoke = revocation("CustomerRevocation", "2026-11-14", "2026-11-15T10:31:00+01:00");
        assertFalse(service.receive("AUFHEBUNG_CCMC", revoke)); // Existing permission handling also receives it.
        var snapshot = service.communities(OPERATOR, CONNECTION).getFirst();
        assertEquals("CustomerRevocation", snapshot.revision());
        assertEquals(originalConfirmation, snapshot.nativeParameters().path("masterDataReceivedAt").asText());
        assertEquals("AnnualSnapshot", snapshot.nativeParameters().path("masterDataRevision").asText());
        var ended = snapshot.meteringPoints().stream().filter(point -> METER.equals(point.meteringPointId())).findFirst().orElseThrow();
        assertEquals("ENDED", ended.status());
        assertEquals(List.of("2026-01-01/2026-11-14"), periods(ended));
        var unaffected = snapshot.meteringPoints().stream().filter(point -> SECOND_METER.equals(point.meteringPointId())).findFirst().orElseThrow();
        assertEquals(List.of("2026-01-01/2026-12-31"), periods(unaffected));

        clock.advance();
        var resync = query("after-revocation", "2026-01-01", "2026-12-31");
        var fresh = new String(list(resync.marketMessageId(), "AfterRevocationSnapshot", "SENDEN_ECP",
                member(METER, period("2026-01-01", "2026-12-31", 75)), DSO), StandardCharsets.UTF_8)
                .replace("2026-11-15T10:30:00+01:00", "2026-11-15T10:32:00+01:00").getBytes(StandardCharsets.UTF_8);
        assertTrue(service.receive("SENDEN_ECP", fresh));
        var wrapper = records.values().stream().filter(row -> row.kind().equals("SNAPSHOT"))
                .map(row -> mapper.readTree(row.payload())).findFirst().orElseThrow();
        assertEquals(1, wrapper.path("seenRevocations").size());
        clock.advance();
        assertFalse(service.receive("AUFHEBUNG_CCMC", revoke));
        var current = service.communities(OPERATOR, CONNECTION).getFirst();
        assertEquals("AfterRevocationSnapshot", current.revision());
        assertEquals("AfterRevocationSnapshot", current.nativeParameters().path("masterDataRevision").asText());
        assertNotEquals(originalConfirmation, current.nativeParameters().path("masterDataReceivedAt").asText());
        assertEquals("CONFIRMED", current.meteringPoints().getFirst().status());
        assertEquals(75, current.meteringPoints().getFirst().participationFactor().intValueExact());
        assertEquals(List.of("2026-01-01/2026-12-31"), periods(current.meteringPoints().getFirst()));
    }

    private void seedAnnualRoster() {
        var query = query("annual", "2026-01-01", "2026-12-31");
        var members = member(METER, period("2026-01-01", "2026-12-31", 25))
                + member(SECOND_METER, period("2026-01-01", "2026-12-31", 35))
                + member(THIRD_METER, period("2026-11-01", "2026-11-30", 45));
        assertTrue(service.receive("SENDEN_ECP", list(query.marketMessageId(), "AnnualSnapshot", "SENDEN_ECP", members, DSO)));
    }

    private List<String> periods(EnergySharingMeteringPoint point) {
        var result = new ArrayList<String>();
        point.nativeParameters().path("timePeriods").forEach(period -> result.add(period.path("start").asText() + "/" + period.path("end").asText()));
        return result;
    }

    private EnergySharingRequest request(String id, String operation, JsonNode parameters) {
        return new EnergySharingRequest(id, CONNECTION, OPERATOR, EC, operation, EdaEnergySharingXml.VERSION, parameters);
    }
    private EdaEnergySharingService configuredService(Map<String, String> communities, boolean partnerConfigured) {
        var binding = new EdaEnergySharingConfiguration.Binding(CONNECTION, OPERATOR, communities, partnerConfigured);
        return new EdaEnergySharingService(new EdaEnergySharingConfiguration(List.of(binding)), store, transport, mapper, groupingIds, clock);
    }
    private EnergySharingRequest groupRequest(String id) {
        return new EnergySharingRequest(id, CONNECTION, OPERATOR, "", "SYNC_MASTER_DATA", EdaEnergySharingXml.VERSION,
                mapper.createObjectNode().put("start", "2026-01-01").put("end", "2026-12-31"));
    }
    private EnergySharingOperation query(String id, String start, String end) {
        return service.submit(request(id, "SYNC_MASTER_DATA", mapper.createObjectNode().put("start", start).put("end", end)));
    }
    private EnergySharingOperation operation(String id) { return service.operation(id, CONNECTION, OPERATOR, EC).orElseThrow(); }
    private JsonNode operationRecord(String id) {
        return records.values().stream().filter(row -> row.kind().equals("OPERATION"))
                .map(row -> mapper.readTree(row.payload())).filter(row -> id.equals(row.path("request").path("requestId").asText())).findFirst().orElseThrow();
    }

    private static String header(String code, String sender) {
        return """
                <cp:MarketParticipantDirectory DocumentMode="PROD" Duplicate="false" SchemaVersion="01.20">
                  <ct:RoutingHeader><ct:Sender AddressType="ECNumber"><ct:MessageAddress>%s</ct:MessageAddress></ct:Sender>
                    <ct:Receiver AddressType="ECNumber"><ct:MessageAddress>%s</ct:MessageAddress></ct:Receiver>
                    <ct:DocumentCreationDateTime>2026-11-15T10:30:00+01:00</ct:DocumentCreationDateTime></ct:RoutingHeader>
                  <ct:Sector>01</ct:Sector><cp:MessageCode>%s</cp:MessageCode>
                </cp:MarketParticipantDirectory>
                """.formatted(sender, OPERATOR, code);
    }
    private static byte[] notification(String conversation, String message, String code, int response, String sender) {
        return """
                <cp:CMNotification xmlns:cp="http://www.ebutilities.at/schemata/customerconsent/cmnotification/01p20" xmlns:ct="%s">
                %s<cp:ProcessDirectory><ct:MessageId>%s</ct:MessageId><ct:ConversationId>%s</ct:ConversationId>
                  <cp:CMRequestId>REQUEST1</cp:CMRequestId><cp:ResponseData><cp:ConsentId>CONSENT1</cp:ConsentId>
                  <cp:MeteringPoint>%s</cp:MeteringPoint><cp:ResponseCode>%d</cp:ResponseCode></cp:ResponseData>
                </cp:ProcessDirectory></cp:CMNotification>
                """.formatted(COMMON, header(code, sender), message, conversation, METER, response).getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] list(String conversation, String message, String code, String members, String sender) {
        return """
                <cp:ECMPList xmlns:cp="http://www.ebutilities.at/schemata/customerprocesses/ecmplist/01p20" xmlns:ct="%s">
                %s<cp:ProcessDirectory><cp:MessageId>%s</cp:MessageId><cp:ConversationId>%s</cp:ConversationId>
                  <cp:ProcessDate>2026-11-15</cp:ProcessDate><cp:ECID>%s</cp:ECID><cp:ECType>RC_R</cp:ECType><cp:ECDisModel>D</cp:ECDisModel>
                  %s</cp:ProcessDirectory></cp:ECMPList>
                """.formatted(COMMON, header(code, sender), message, conversation, EC, members).getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] revocation(String message, String end, String createdAt) {
        var header = header("AUFHEBUNG_CCMC", DSO).replace("SchemaVersion=\"01.20\"", "SchemaVersion=\"01.10\"")
                .replace("2026-11-15T10:30:00+01:00", createdAt);
        return """
                <cp:CMRevoke xmlns:cp="http://www.ebutilities.at/schemata/customerconsent/cmrevoke/01p10" xmlns:ct="%s">
                %s<cp:ProcessDirectory><ct:MessageId>%s</ct:MessageId><ct:ConversationId>ExternalRevocation</ct:ConversationId>
                  <cp:ConsentId>CONSENT1</cp:ConsentId><cp:MeteringPoint>%s</cp:MeteringPoint><cp:ConsentEnd>%s</cp:ConsentEnd>
                </cp:ProcessDirectory></cp:CMRevoke>
                """.formatted(COMMON, header, message, METER, end).getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] processNotification(String conversation, String message, String code, int response) {
        var header = header(code, DSO).replace("SchemaVersion=\"01.20\"", "SchemaVersion=\"01.13\"");
        return """
                <cp:CPNotification xmlns:cp="http://www.ebutilities.at/schemata/customerprocesses/cpnotification/01p13" xmlns:ct="%s">
                %s<cp:ProcessDirectory><ct:MessageId>%s</ct:MessageId><ct:ConversationId>%s</ct:ConversationId>
                  <cp:ResponseData><cp:OriginalMessageID>%s</cp:OriginalMessageID><cp:ResponseCode>%d</cp:ResponseCode></cp:ResponseData>
                </cp:ProcessDirectory></cp:CPNotification>
                """.formatted(COMMON, header, message, conversation, conversation, response).getBytes(StandardCharsets.UTF_8);
    }
    private static String member(String meter, String periods) {
        return "<cp:MPListData><cp:MeteringPoint>" + meter + "</cp:MeteringPoint><cp:ConsentId>CONSENT1</cp:ConsentId>" + periods + "</cp:MPListData>";
    }
    private static String period(String start, String end, int factor) {
        return """
                <cp:MPTimeData><cp:DateFrom>%s</cp:DateFrom><cp:DateTo>%s</cp:DateTo><cp:EnergyDirection>CONSUMPTION</cp:EnergyDirection>
                  <cp:ECPartFact>%d</cp:ECPartFact><cp:DataType>EnergyCommunityRegistration</cp:DataType><cp:DateActivate>2026-01-01</cp:DateActivate>
                </cp:MPTimeData>
                """.formatted(start, end, factor);
    }

    private static class MovingClock extends Clock {
        private Instant current = Instant.parse("2026-11-15T09:30:00Z");
        void advance() { current = current.plusSeconds(60); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        @Override public Instant instant() { return current; }
    }
}
