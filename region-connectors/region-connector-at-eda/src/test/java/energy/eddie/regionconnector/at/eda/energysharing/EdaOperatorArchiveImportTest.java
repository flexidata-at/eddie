// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import static energy.eddie.regionconnector.at.eda.energysharing.OperatorMeasurementFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import energy.eddie.api.agnostic.energysharing.*;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class EdaOperatorArchiveImportTest {
  private final EdaOperatorMeasurementStore store = mock(EdaOperatorMeasurementStore.class);
  private final EdaOperatorMeasurementService service =
      new EdaOperatorMeasurementService(
          new EdaEnergySharingConfiguration(List.of(BINDING)), store, MAPPER);

  private EnergySharingMeasurementImport request(String xml, boolean dryRun) {
    byte[] source = bytes(xml);
    return new EnergySharingMeasurementImport(
        source,
        "ponton:123",
        Instant.parse("2024-04-02T15:00:00Z"),
        EdaOperatorMeasurementXml.hash(source),
        dryRun);
  }

  @Test
  void dryRunValidatesNativeSchemaAndExactRosterWithoutCallingAppend() {
    doAnswer(
            invocation -> {
              Function<String, JsonNode> authorize = invocation.getArgument(4);
              assertEquals(COMMUNITY, authorize.apply(roster()).path("communityId").asText());
              return null;
            })
        .when(store)
        .validateArchive(anyString(), anyString(), anyString(), any(), any());
    var result =
        service.importArchive("CC000001", "connection", COMMUNITY, request(xml("M1"), true));
    assertEquals("VALIDATED", result.status());
    assertTrue(result.dryRun());
    assertEquals(0, result.sequence());
    assertEquals(Instant.parse("2024-04-02T14:40:48Z"), result.sourceDocumentCreatedAt());
    verify(store, never()).append(anyString(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void commitReusesDurableStoreAndPreservesNativeAndArchiveTimesSeparately() {
    when(store.append(anyString(), anyString(), anyString(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              EnergySharingMeasurementDocument document = invocation.getArgument(3);
              Function<String, JsonNode> authorize = invocation.getArgument(5);
              assertEquals(COMMUNITY, authorize.apply(roster()).path("communityId").asText());
              assertEquals(
                  "ponton:123",
                  document.nativeParameters().path("archiveImport").path("archiveId").asText());
              assertEquals(
                  "2024-04-02T15:00:00Z",
                  document
                      .nativeParameters()
                      .path("archiveImport")
                      .path("originalReceivedAt")
                      .asText());
              assertArrayEquals(bytes(xml("M1")), invocation.getArgument(4));
              return new EdaOperatorMeasurementStore.Stored(document, true);
            });
    var result =
        service.importArchive("CC000001", "connection", COMMUNITY, request(xml("M1"), false));
    assertEquals("IMPORTED", result.status());
    assertFalse(result.dryRun());
    assertTrue(result.receivedAt().isAfter(result.sourceDocumentCreatedAt()));
    doReturn(new EdaOperatorMeasurementStore.Stored(document("M1"), false))
        .when(store)
        .append(anyString(), anyString(), anyString(), any(), any(), any());
    assertEquals(
        "ALREADY_PRESENT",
        service
            .importArchive("CC000001", "connection", COMMUNITY, request(xml("M1"), false))
            .status());
  }

  @Test
  void archiveIdentifierCannotAuthorizeAnotherConnectionNativeCommunityOrSender() {
    var valid = request(xml("M1"), true);
    assertThrows(
        EnergySharingException.class,
        () -> service.importArchive("CC000001", "foreign", COMMUNITY, valid));
    var wrongCommunity =
        request(xml("M1").replace(COMMUNITY, "AT000000000000000000000000000099"), true);
    assertThrows(
        EnergySharingException.class,
        () -> service.importArchive("CC000001", "connection", COMMUNITY, wrongCommunity));
    var wrongDso =
        request(
            xml("M1").replace("AT000000</ct:MessageAddress>", "AT000009</ct:MessageAddress>"),
            true);
    assertThrows(
        EnergySharingException.class,
        () -> service.importArchive("CC000001", "connection", COMMUNITY, wrongDso));
    verifyNoInteractions(store);
  }

  @Test
  void changedBytesBadSchemaAndImpossibleArchiveTimesAreRejected() {
    byte[] xml = bytes(xml("M1"));
    var wrongHash =
        new EnergySharingMeasurementImport(xml, "ponton:123", Instant.now(), "0".repeat(64), true);
    assertThrows(
        EnergySharingException.class,
        () -> service.importArchive("CC000001", "connection", COMMUNITY, wrongHash));
    var invalid =
        request(
            new String(xml, java.nio.charset.StandardCharsets.UTF_8)
                .replace("<cp:MM>L1</cp:MM>", ""),
            true);
    assertThrows(
        EnergySharingException.class,
        () -> service.importArchive("CC000001", "connection", COMMUNITY, invalid));
    var early =
        new EnergySharingMeasurementImport(
            xml, "ponton:123", Instant.EPOCH, EdaOperatorMeasurementXml.hash(xml), true);
    assertThrows(
        EnergySharingException.class,
        () -> service.importArchive("CC000001", "connection", COMMUNITY, early));
    var future =
        new EnergySharingMeasurementImport(
            xml,
            "ponton:123",
            Instant.now().plusSeconds(3600),
            EdaOperatorMeasurementXml.hash(xml),
            true);
    assertThrows(
        EnergySharingException.class,
        () -> service.importArchive("CC000001", "connection", COMMUNITY, future));
    verifyNoInteractions(store);
  }
}
