// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import static energy.eddie.api.agnostic.energysharing.EnergySharingException.Code.*;

import energy.eddie.api.agnostic.energysharing.*;
import energy.eddie.cim.v1_12.StandardCodingSchemeTypeList;
import java.time.*;
import java.util.*;
import org.w3c.dom.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CR_REQ_PT04.30 operator authority and missing-data waiting rules, independent of customer CESU.
 */
final class EdaMeasurementRequestPolicy {
  private static final String START = "start";
  private static final String END = "end";
  private static final String METERING_POINT_ID = "meteringPointId";
  private static final String TIME_PERIODS = "timePeriods";
  private static final String MASTER_RECEIVED = "masterDataReceivedAt";
  private static final String NOTIFICATION =
      "http://www.ebutilities.at/schemata/customerprocesses/cpnotification/01p13";
  private final ObjectMapper mapper;
  private final Clock clock;

  EdaMeasurementRequestPolicy(ObjectMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  JsonNode authorize(EnergySharingRequest request, String dso, String payload) {
    var today = LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA));
    validateDates(request.parameters(), today);
    var snapshot =
        mapper.treeToValue(
            mapper.readTree(payload).path("snapshot"), EnergySharingCommunitySnapshot.class);
    requireSnapshot(snapshot, request);
    var matching =
        snapshot.meteringPoints().stream()
            .filter(
                point ->
                    point
                            .meteringPointId()
                            .equals(request.parameters().path(METERING_POINT_ID).asText())
                        && StandardCodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME
                            .value()
                            .equals(point.codingScheme())
                        && dso.equals(point.permissionAdministratorId()))
            .toList();
    if (matching.size() != 1)
      throw failure(FORBIDDEN, "Requested point is not uniquely authorized in this community");
    var point = matching.getFirst();
    var periods = periods(point.nativeParameters().path(TIME_PERIODS));
    var start = date(request.parameters(), START);
    var end = date(request.parameters(), END);
    for (var day = start; !day.isAfter(end); day = day.plusDays(1))
      validateDay(day, periods, today);
    var proof = mapper.createObjectNode();
    proof.put("snapshotRevision", snapshot.revision());
    proof.put(MASTER_RECEIVED, snapshot.nativeParameters().path(MASTER_RECEIVED).asText());
    proof.put(METERING_POINT_ID, point.meteringPointId());
    proof.put("codingScheme", point.codingScheme());
    proof.put("permissionAdministratorId", dso);
    proof.put("requestedFrom", start.toString());
    proof.put("requestedThrough", end.toString());
    proof.set(TIME_PERIODS, point.nativeParameters().path(TIME_PERIODS).deepCopy());
    return proof;
  }

  private void requireSnapshot(
      EnergySharingCommunitySnapshot snapshot, EnergySharingRequest request) {
    if (!snapshot.complete()
        || !snapshot.communityId().equals(request.communityId())
        || !snapshot.operatorId().equals(request.operatorId())
        || !snapshot.connectionId().equals(request.connectionId()))
      throw failure(FORBIDDEN, "Authoritative roster scope does not match this request");
    try {
      var received = Instant.parse(snapshot.nativeParameters().path(MASTER_RECEIVED).asText());
      if (received.isAfter(clock.instant())
          || received.isBefore(clock.instant().minus(Duration.ofHours(24))))
        throw failure(
            NOT_READY, "Synchronize fresh master data before requesting missing measurements");
    } catch (DateTimeException exception) {
      throw failure(
          NOT_READY,
          "A native master-data confirmation is required before requesting measurements");
    }
  }

  static void validateDates(JsonNode parameters, LocalDate today) {
    var start = date(parameters, START);
    var end = date(parameters, END);
    if (end.isBefore(start) || !end.isBefore(today) || start.isBefore(today.minusYears(3)))
      throw failure(INVALID_REQUEST, "Request inclusive past dates within the last three years");
  }

  private record Period(LocalDate start, LocalDate end, String cycle) {
    boolean contains(LocalDate day) {
      return !day.isBefore(start) && !day.isAfter(end);
    }
  }

  private static List<Period> periods(JsonNode values) {
    var result = new ArrayList<Period>();
    for (var value : values) {
      var from = date(value, START);
      var to = date(value, END);
      var activate = date(value, "activate");
      if (activate.isAfter(from)) from = activate;
      if (value.hasNonNull("deactivate")) {
        var end = date(value, "deactivate");
        if (end.isBefore(to)) to = end;
      }
      if (!to.isBefore(from))
        result.add(new Period(from, to, value.path("transmissionCycle").asText()));
    }
    return result;
  }

  private static void validateDay(LocalDate day, List<Period> periods, LocalDate today) {
    var matching = periods.stream().filter(period -> period.contains(day)).toList();
    if (matching.size() != 1)
      throw failure(FORBIDDEN, "Requested dates contain a membership gap or ambiguous period");
    var cycle = matching.getFirst().cycle();
    var daily = afterWorkingDays(day.plusDays(1), 3);
    var monthly = day.withDayOfMonth(1).plusMonths(1).withDayOfMonth(6);
    var earliest =
        switch (cycle) {
          case "D" -> daily;
          case "M" -> monthly;
          // TransmissionCycle is optional in native ECMPList. Enforce both waiting safeguards
          // rather
          // than invent a cycle or block otherwise authorized historical requests indefinitely.
          default -> daily.isAfter(monthly) ? daily : monthly;
        };
    if (today.isBefore(earliest)) {
      String reason =
          Set.of("D", "M").contains(cycle)
              ? "Missing-data waiting period has not elapsed"
              : "Transmission cycle is not confirmed; the conservative missing-data waiting period"
                    + " applies";
      throw failure(INVALID_REQUEST, reason + "; earliest request date " + earliest);
    }
  }

  static LocalDate afterWorkingDays(LocalDate date, int days) {
    var result = date;
    for (int remaining = days; remaining > 0; ) {
      result = result.plusDays(1);
      if (marketWorkingDay(result)) remaining--;
    }
    return result;
  }

  // Austrian market working-day convention: weekends, legal holidays, Good Friday, 24/31 December
  // excluded.
  private static boolean marketWorkingDay(LocalDate day) {
    return day.getDayOfWeek().getValue() < 6
        && !EdaEnergySharingXml.holiday(day)
        && !day.equals(EdaEnergySharingXml.easterSunday(day.getYear()).minusDays(2))
        && !(day.getMonth() == Month.DECEMBER && Set.of(24, 31).contains(day.getDayOfMonth()));
  }

  static void validateReply(String code, Element directory, String originalId) {
    var originals = directory.getElementsByTagNameNS(NOTIFICATION, "OriginalMessageID");
    if (originals.getLength() != 1 || !originalId.equals(originals.item(0).getTextContent()))
      throw failure(FORBIDDEN, "Native response does not identify the original request");
    var values = directory.getElementsByTagNameNS(NOTIFICATION, "ResponseCode");
    if (values.getLength() == 0)
      throw failure(INVALID_REQUEST, "Expected native request response codes");
    var allowed = "ANTWORT_PT".equals(code) ? Set.of("70") : Set.of("55", "56", "76", "82", "94");
    for (int index = 0; index < values.getLength(); index++) {
      if (!allowed.contains(values.item(index).getTextContent()))
        throw failure(INVALID_REQUEST, "Unexpected CR_REQ_PT response code");
    }
  }

  private static LocalDate date(JsonNode node, String key) {
    try {
      return LocalDate.parse(node.path(key).asText());
    } catch (DateTimeException exception) {
      throw failure(INVALID_REQUEST, "Invalid date: " + key);
    }
  }

  private static EnergySharingException failure(EnergySharingException.Code code, String message) {
    return new EnergySharingException(code, message);
  }
}
