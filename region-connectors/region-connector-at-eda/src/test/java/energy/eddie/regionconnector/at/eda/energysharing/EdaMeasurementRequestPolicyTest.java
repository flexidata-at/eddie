// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import static energy.eddie.regionconnector.at.eda.energysharing.OperatorMeasurementFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import energy.eddie.api.agnostic.energysharing.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class EdaMeasurementRequestPolicyTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
  private final EdaMeasurementRequestPolicy policy = new EdaMeasurementRequestPolicy(MAPPER, CLOCK);

  private EnergySharingRequest request(String start, String end) {
    return new EnergySharingRequest(
        "request",
        "connection",
        "CC000001",
        COMMUNITY,
        "REQUEST_MEASUREMENTS",
        EdaEnergySharingXml.VERSION,
        MAPPER
            .createObjectNode()
            .put("meteringPointId", METER)
            .put("start", start)
            .put("end", end));
  }

  private ObjectNode snapshot(String cycle) {
    var root = (ObjectNode) MAPPER.readTree(roster());
    ((ObjectNode) root.path("snapshot").path("nativeParameters"))
        .put("masterDataReceivedAt", CLOCK.instant().toString());
    var period =
        (ObjectNode)
            root.path("snapshot")
                .path("meteringPoints")
                .get(0)
                .path("nativeParameters")
                .path("timePeriods")
                .get(0);
    period
        .put("start", "2023-01-01")
        .put("activate", "2023-01-01")
        .put("end", "9999-12-31")
        .put("transmissionCycle", cycle);
    return root;
  }

  @Test
  void inclusiveDatesUseViennaMidnightAndBothDstTransitions() {
    for (var day : List.of("2026-03-29", "2026-10-25")) {
      var command = request(day, day);
      var message = EdaEnergySharingXml.create(command, "AT000000", "DATADEVTEST", CLOCK);
      assertEquals("ANFORDERUNG_PT", message.code());
      assertEquals("CR_REQ_PT_03.00", message.schemaSet());
      EdaEnergySharingXml.validate(message.xml(), "CPRequest_01p12.xsd");
      var xml = EdaEnergySharingXml.parse(message.xml());
      var from =
          OffsetDateTime.parse(
              xml.getElementsByTagNameNS("*", "DateTimeFrom").item(0).getTextContent());
      var to =
          OffsetDateTime.parse(
              xml.getElementsByTagNameNS("*", "DateTimeTo").item(0).getTextContent());
      assertEquals(day.equals("2026-03-29") ? 23 : 25, Duration.between(from, to).toHours());
      assertEquals(0, xml.getElementsByTagNameNS("*", "ECID").getLength());
      assertEquals(
          METER, xml.getElementsByTagNameNS("*", "MeteringPoint").item(0).getTextContent());
    }
  }

  @Test
  void dateLimitsRejectFutureReversalAndMoreThanThreeYearsButIncludeBoundary() {
    var today = LocalDate.of(2026, Month.NOVEMBER, 16);
    EdaMeasurementRequestPolicy.validateDates(
        request("2023-11-16", "2023-11-16").parameters(), today);
    for (var command :
        List.of(
            request("2023-11-15", "2023-11-16"),
            request("2026-11-15", "2026-11-16"),
            request("2026-11-10", "2026-11-09"))) {
      var parameters = command.parameters();
      assertThrows(
          EnergySharingException.class,
          () -> EdaMeasurementRequestPolicy.validateDates(parameters, today));
    }
  }

  @Test
  void dailyAndMonthlyWaitingPeriodsAreDerivedFromNativeMembership() {
    var daily = snapshot("D").toString();
    policy.authorize(request("2026-11-10", "2026-11-10"), "AT000000", daily);
    var tooRecent = request("2026-11-11", "2026-11-11");
    assertThrows(
        EnergySharingException.class, () -> policy.authorize(tooRecent, "AT000000", daily));
    var monthly = snapshot("M").toString();
    policy.authorize(request("2026-10-01", "2026-10-31"), "AT000000", monthly);
    var sameMonth = request("2026-11-01", "2026-11-01");
    assertThrows(
        EnergySharingException.class, () -> policy.authorize(sameMonth, "AT000000", monthly));
    for (String unconfirmed : List.of("V", "")) {
      var unknown = snapshot(unconfirmed).toString();
      var old = request("2026-01-01", "2026-01-01");
      policy.authorize(old, "AT000000", unknown);
      var recent = request("2026-11-01", "2026-11-01");
      var error =
          assertThrows(
              EnergySharingException.class, () -> policy.authorize(recent, "AT000000", unknown));
      assertEquals(EnergySharingException.Code.INVALID_REQUEST, error.code());
      assertTrue(error.getMessage().contains("conservative"));
      assertTrue(error.getMessage().contains("2026-12-06"));
    }
  }

  @Test
  void marketCalendarExcludesWeekendsGoodFridayLegalHolidaysAndYearEnd() {
    assertEquals(
        LocalDate.of(2026, Month.APRIL, 9),
        EdaMeasurementRequestPolicy.afterWorkingDays(LocalDate.of(2026, Month.APRIL, 2), 3));
    assertEquals(
        LocalDate.of(2027, Month.JANUARY, 5),
        EdaMeasurementRequestPolicy.afterWorkingDays(LocalDate.of(2026, Month.DECEMBER, 29), 3));
    assertEquals(
        LocalDate.of(2026, Month.DECEMBER, 30),
        EdaMeasurementRequestPolicy.afterWorkingDays(LocalDate.of(2026, Month.DECEMBER, 23), 3));
  }

  @Test
  void gapsEndedMembershipStaleAndWrongQualifiedPointCannotAuthorizeRequest() {
    var command = request("2026-10-01", "2026-10-31");
    for (String kind : List.of("stale", "country", "dso", "ended", "gap", "overlap")) {
      var data = snapshot("D");
      var snap = (ObjectNode) data.path("snapshot");
      var point = (ObjectNode) snap.path("meteringPoints").get(0);
      var periods =
          (tools.jackson.databind.node.ArrayNode)
              point.path("nativeParameters").path("timePeriods");
      var period = (ObjectNode) periods.get(0);
      switch (kind) {
        case "stale" ->
            ((ObjectNode) snap.path("nativeParameters"))
                .put("masterDataReceivedAt", "2026-11-14T00:00:00Z");
        case "country" -> point.put("codingScheme", "FOREIGN");
        case "dso" -> point.put("permissionAdministratorId", "AT999999");
        case "ended" -> period.put("deactivate", "2026-10-15");
        case "gap" -> period.put("start", "2026-10-02");
        case "overlap" -> periods.add(period.deepCopy());
        default -> throw new AssertionError(kind);
      }
      var payload = data.toString();
      assertThrows(
          EnergySharingException.class, () -> policy.authorize(command, "AT000000", payload), kind);
    }
  }
}
