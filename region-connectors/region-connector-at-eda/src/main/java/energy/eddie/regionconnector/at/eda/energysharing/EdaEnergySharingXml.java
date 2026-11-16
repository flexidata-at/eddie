// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.EnergySharingException;
import energy.eddie.api.agnostic.energysharing.EnergySharingRequest;
import energy.eddie.regionconnector.at.eda.utils.CMRequestId;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import tools.jackson.databind.JsonNode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static energy.eddie.api.agnostic.energysharing.EnergySharingException.Code.INVALID_REQUEST;

/** Version-pinned wire mapping; XML is built with DOM so user fields are always escaped. */
public final class EdaEnergySharingXml {
    public static final String PROFILE = "AT-EDA-BEM";
    public static final String VERSION = "2026-10-05";
    public static final ZoneId VIENNA = ZoneId.of("Europe/Vienna");
    private static final String CT = "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20";
    private static final String CM_REQUEST = "CMRequest";
    private static final String EC_MP_LIST = "ECMPList";
    private static final String CP_REQUEST = "CPRequest";
    private static final String CHANGE_ALLOCATION = "CHANGE_ALLOCATION";
    private static final String CHANGE_PARTICIPATION = "CHANGE_PARTICIPATION_FACTOR";
    private static final String METERING_POINT = "MeteringPoint";
    private static final String METERING_POINT_ID = "meteringPointId";
    private static final String START = "start";
    private static final String END = "end";
    private static final String PARTICIPATION_FACTOR = "participationFactor";
    private static final String ALLOCATION_FACTOR = "allocationFactor";
    private static final String DISTRIBUTION_MODEL = "distributionModel";
    private static final String ENERGY_DIRECTION = "energyDirection";
    private static final String CONSUMPTION = "CONSUMPTION";
    private static final String CONSENT_ID = "consentId";
    private static final String INVALID = "Invalid ";
    private static final Set<String> DIRECTIONS = Set.of(CONSUMPTION, "GENERATION");
    private static final Set<String> TRANSMISSION_CYCLES = Set.of("D", "M", "V");
    private static final Set<MonthDay> FIXED_HOLIDAYS = Set.of(
            MonthDay.of(Month.JANUARY, 1),
            MonthDay.of(Month.JANUARY, 6),
            MonthDay.of(Month.MAY, 1),
            MonthDay.of(Month.AUGUST, 15),
            MonthDay.of(Month.OCTOBER, 26),
            MonthDay.of(Month.NOVEMBER, 1),
            MonthDay.of(Month.DECEMBER, 8),
            MonthDay.of(Month.DECEMBER, 25),
            MonthDay.of(Month.DECEMBER, 26)
    );

    public record Message(String code, String schemaSet, String version, byte[] xml) {
        public Message {
            xml = xml.clone();
        }

        @Override
        public byte[] xml() {
            return xml.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Message(var otherCode, var otherSchemaSet, var otherVersion, var otherXml)
                    && code.equals(otherCode) && schemaSet.equals(otherSchemaSet)
                    && version.equals(otherVersion) && Arrays.equals(xml, otherXml);
        }

        @Override
        public int hashCode() {
            return Objects.hash(code, schemaSet, version, Arrays.hashCode(xml));
        }

        @Override
        public String toString() {
            return "Message[code=%s, schemaSet=%s, version=%s, xmlBytes=%d, xmlHash=%d]"
                    .formatted(code, schemaSet, version, xml.length, Arrays.hashCode(xml));
        }
    }

    private record WireProfile(String code, String schemaSet, String root, String namespace, String version) {
        String schemaName() {
            return root + "_" + version.replace(".", "p") + ".xsd";
        }
    }

    private EdaEnergySharingXml() {}

    public static Message create(EnergySharingRequest request, String dso, String id, Clock clock) {
        var parameters = request.parameters();
        if (parameters == null || !parameters.isObject()) {
            throw invalid("parameters must be an object");
        }
        var today = LocalDate.now(clock.withZone(VIENNA));
        var profile = profile(request.operation());
        try {
            var document = factory().newDocumentBuilder().newDocument();
            var root = document.createElementNS(profile.namespace(), profile.root());
            document.appendChild(root);
            root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:ct", CT);
            addHeader(root, profile, request.operatorId(), dso, clock);
            var directory = addDirectory(root, profile, id);
            addProcess(directory, profile, request, id, today, clock);
            addAdditionalData(directory, profile, parameters);
            var xml = serialize(document);
            validate(xml, profile.schemaName());
            return new Message(profile.code(), profile.schemaSet(),
                    profile.schemaSet().substring(profile.schemaSet().lastIndexOf('_') + 1), xml);
        } catch (ParserConfigurationException | TransformerException | DateTimeException exception) {
            throw invalid("Could not construct EDA message: " + exception.getClass().getSimpleName());
        }
    }

    private static WireProfile profile(String operation) {
        return switch (operation) {
            case "JOIN" -> wire("ANFORDERUNG_ECON", "EC_REQ_ONL_02.40", CM_REQUEST,
                    "customerconsent/cmrequest/01p30", "01.30");
            case "LEAVE" -> wire("AUFHEBUNG_CCMS", "CM_REV_SP_01.30", "CMRevoke",
                    "customerconsent/cmrevoke/01p10", "01.10");
            case CHANGE_PARTICIPATION -> wire("ANFORDERUNG_CPF", "EC_PRTFACT_CHANGE_01.10", EC_MP_LIST,
                    "customerprocesses/ecmplist/01p20", "01.20");
            case CHANGE_ALLOCATION -> wire("ANFORDERUNG_ECC", "EC_PRT_CHANGE_02.10", EC_MP_LIST,
                    "customerprocesses/ecmplist/01p20", "01.20");
            case "REQUEST_MEASUREMENTS" -> wire("ANFORDERUNG_PT", "CR_REQ_PT_03.00", CP_REQUEST,
                    "customerprocesses/cprequest/01p12", "01.12");
            case "SYNC_MASTER_DATA" -> wire("ANFORDERUNG_ECP", "EC_PODLIST_02.10", CP_REQUEST,
                    "customerprocesses/cprequest/01p12", "01.12");
            default -> throw invalid("Unsupported EDA operation");
        };
    }

    private static WireProfile wire(String code, String set, String root, String path, String version) {
        return new WireProfile(code, set, root, "http://www.ebutilities.at/schemata/" + path, version);
    }

    private static void addHeader(Element root, WireProfile profile, String operator, String dso, Clock clock) {
        var directory = child(root, profile.namespace(), "MarketParticipantDirectory", null);
        directory.setAttribute("SchemaVersion", profile.version());
        directory.setAttribute("DocumentMode", "PROD");
        directory.setAttribute("Duplicate", "false");
        var routing = child(directory, CT, "ct:RoutingHeader", null);
        address(routing, "Sender", operator);
        address(routing, "Receiver", dso);
        child(routing, CT, "ct:DocumentCreationDateTime",
                OffsetDateTime.now(clock.withZone(VIENNA)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        child(directory, CT, "ct:Sector", "01");
        child(directory, profile.namespace(), "MessageCode", profile.code());
    }

    private static Element addDirectory(Element root, WireProfile profile, String id) {
        var directory = child(root, profile.namespace(), "ProcessDirectory", null);
        boolean list = profile.root().equals(EC_MP_LIST);
        var namespace = list ? profile.namespace() : CT;
        child(directory, namespace, list ? "MessageId" : "ct:MessageId", id);
        child(directory, namespace, list ? "ConversationId" : "ct:ConversationId", id);
        return directory;
    }

    private static void addProcess(Element directory, WireProfile profile, EnergySharingRequest request,
                                   String id, LocalDate today, Clock clock) {
        switch (request.operation()) {
            case "JOIN" -> addJoin(directory, profile.namespace(), request, id, today);
            case "LEAVE" -> addLeave(directory, profile.namespace(), request.parameters(), today);
            case "REQUEST_MEASUREMENTS" -> addMeasurementRequest(directory, profile.namespace(), request, today);
            case "SYNC_MASTER_DATA" -> addQuery(directory, profile.namespace(), request, today);
            default -> addFactorChange(directory, profile.namespace(), request, today, clock);
        }
    }

    private static void addJoin(Element directory, String namespace, EnergySharingRequest request,
                                String id, LocalDate today) {
        var parameters = request.parameters();
        child(directory, namespace, "ProcessDate", today.toString());
        child(directory, namespace, METERING_POINT, identifier(parameters, METERING_POINT_ID, 33));
        child(directory, namespace, "CMRequestId", new CMRequestId(id).toString());
        var registration = child(directory, namespace, CM_REQUEST, null);
        child(registration, namespace, "ReqDatType", "EnergyCommunityRegistration");
        var start = date(parameters, START);
        future(start, today.plusDays(1), workdayLimit(today));
        if (parameters.hasNonNull(END)) {
            throw invalid("DateTo/end is forbidden for EC_REQ_ONL");
        }
        child(registration, namespace, "DateFrom", start.toString());
        optionalEnum(registration, namespace, parameters, "meteringInterval", "MeteringIntervall", Set.of("QH", "H", "D", "V"));
        optionalEnum(registration, namespace, parameters, "transmissionCycle", "TransmissionCycle", TRANSMISSION_CYCLES);
        child(registration, namespace, "ECID", request.communityId());
        child(registration, namespace, "ECPartFact", factor(parameters, PARTICIPATION_FACTOR, true));
        addJoinAllocation(registration, namespace, parameters);
        child(registration, namespace, "EnergyDirection", enumValue(parameters, ENERGY_DIRECTION, DIRECTIONS));
        optional(registration, namespace, parameters, "purpose", "Purpose", 100);
    }

    private static void addJoinAllocation(Element registration, String namespace, JsonNode parameters) {
        if (!parameters.hasNonNull(ALLOCATION_FACTOR)) {
            return;
        }
        if (!"S".equals(parameters.path(DISTRIBUTION_MODEL).asText())) {
            throw invalid("Static allocation requires distributionModel S");
        }
        if (!CONSUMPTION.equals(text(parameters, ENERGY_DIRECTION))) {
            throw invalid("Static allocation is a consumption-meter parameter");
        }
        child(registration, namespace, "ECShare", factor(parameters, ALLOCATION_FACTOR, false));
    }

    private static void addLeave(Element directory, String namespace, JsonNode parameters, LocalDate today) {
        child(directory, namespace, "ConsentId", identifier(parameters, CONSENT_ID, 35));
        child(directory, namespace, METERING_POINT, identifier(parameters, METERING_POINT_ID, 33));
        var end = date(parameters, END);
        future(end, today, workdayLimit(today));
        child(directory, namespace, "ConsentEnd", end.toString());
        if (parameters.hasNonNull("reasonKey")) {
            var key = text(parameters, "reasonKey");
            if (!key.matches("\\d")) {
                throw invalid("reasonKey must be 0..9");
            }
            child(directory, namespace, "ReasonKey", key);
        }
        optional(directory, namespace, parameters, "reason", "Reason", 50);
    }

    private static void addQuery(Element directory, String namespace, EnergySharingRequest request, LocalDate today) {
        child(directory, CT, "ct:ProcessDate", today.toString());
        child(directory, CT, "ct:MeteringPoint", request.communityId());
        var extension = child(directory, namespace, "Extension", null);
        var start = date(request.parameters(), START);
        var end = date(request.parameters(), END);
        if (end.isBefore(start)) {
            throw invalid("end must not precede start");
        }
        child(extension, namespace, "DateTimeFrom", start.atStartOfDay(VIENNA).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        child(extension, namespace, "DateTimeTo", end.plusDays(1).atStartOfDay(VIENNA).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        child(extension, namespace, "AssumptionOfCosts", "false");
    }

    private static void addMeasurementRequest(Element directory, String namespace, EnergySharingRequest request, LocalDate today) {
        var parameters = request.parameters();
        EdaMeasurementRequestPolicy.validateDates(parameters, today);
        child(directory, CT, "ct:ProcessDate", today.toString());
        child(directory, CT, "ct:MeteringPoint", identifier(parameters, METERING_POINT_ID, 33));
        var extension = child(directory, namespace, "Extension", null);
        optionalEnum(extension, namespace, parameters, "meteringInterval", "MeteringIntervall", Set.of("QH", "H", "D", "V"));
        child(extension, namespace, "DateTimeFrom", date(parameters, START).atStartOfDay(VIENNA).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        child(extension, namespace, "DateTimeTo", date(parameters, END).plusDays(1).atStartOfDay(VIENNA).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        child(extension, namespace, "AssumptionOfCosts", "false");
    }

    private static void addFactorChange(Element directory, String namespace, EnergySharingRequest request,
                                        LocalDate today, Clock clock) {
        var parameters = request.parameters();
        var start = date(parameters, START);
        var time = LocalTime.now(clock.withZone(VIENNA));
        if (time.isBefore(LocalTime.of(9, 0)) || !time.isBefore(LocalTime.of(17, 0)) || !start.equals(today.plusDays(1))) {
            throw invalid("EDA factor changes must arrive 09:00–17:00 Vienna time for the next calendar day");
        }
        child(directory, namespace, "ProcessDate", start.toString());
        child(directory, namespace, "ECID", request.communityId());
        optionalEnum(directory, namespace, parameters, "communityType", "ECType", Set.of("GC", "RC_R", "RC_L", "CC", "SC", "PP"));
        optionalEnum(directory, namespace, parameters, DISTRIBUTION_MODEL, "ECDisModel", Set.of("D", "S"));
        optionalEnum(directory, namespace, parameters, "zoneLevel", "ECZoneLevel", Set.of("1", "2", "3", "4", "5", "6", "7"));
        if (request.operation().equals(CHANGE_ALLOCATION) && !"S".equals(parameters.path(DISTRIBUTION_MODEL).asText())) {
            throw invalid("ECShare changes are only supported for existing static agreements");
        }
        var meter = child(directory, namespace, "MPListData", null);
        child(meter, namespace, METERING_POINT, identifier(parameters, METERING_POINT_ID, 33));
        if (parameters.hasNonNull(CONSENT_ID)) {
            child(meter, namespace, "ConsentId", identifier(parameters, CONSENT_ID, 35));
        }
        addTimePeriods(meter, namespace, parameters, request.operation(), start);
    }

    private static void addTimePeriods(Element meter, String namespace, JsonNode parameters, String operation, LocalDate start) {
        var periods = parameters.path("timePeriods");
        if (!periods.isArray() || periods.isEmpty()) {
            throw invalid("timePeriods containing DateTo/DataType/DateActivate and all future nominal changes are required");
        }
        if (!date(periods.get(0), START).equals(start)) {
            throw invalid("First time period must start at the requested effective date");
        }
        for (var period : periods) {
            timePeriod(meter, namespace, period, operation);
        }
    }

    private static void addAdditionalData(Element directory, WireProfile profile, JsonNode parameters) {
        if (!parameters.hasNonNull("additionalData")) {
            return;
        }
        if (!Set.of(CM_REQUEST, CP_REQUEST).contains(profile.root())) {
            throw invalid("AdditionalData is not supported by this process schema");
        }
        var additional = parameters.path("additionalData");
        if (!additional.isArray() || additional.size() > 1000) {
            throw invalid("Invalid AdditionalData list");
        }
        for (var entry : additional) {
            var value = child(directory, profile.namespace(), "AdditionalData", bounded(text(entry, "value"), 120));
            value.setAttribute("Name", bounded(text(entry, "name"), 40));
        }
    }

    private static void timePeriod(Element parent, String namespace, JsonNode parameters, String operation) {
        var element = child(parent, namespace, "MPTimeData", null);
        var start = date(parameters, START);
        var end = date(parameters, END);
        if (end.isBefore(start)) {
            throw invalid("Time period end precedes start");
        }
        child(element, namespace, "DateFrom", start.toString());
        child(element, namespace, "DateTo", end.toString());
        optionalEnum(element, namespace, parameters, ENERGY_DIRECTION, "EnergyDirection", DIRECTIONS);
        addPeriodFactor(element, namespace, parameters, PARTICIPATION_FACTOR, "ECPartFact", operation.equals(CHANGE_PARTICIPATION));
        child(element, namespace, "DataType", bounded(text(parameters, "dataType"), 30));
        optionalEnum(element, namespace, parameters, "transmissionCycle", "TransmissionCycle", TRANSMISSION_CYCLES);
        optional(element, namespace, parameters, "purpose", "Purpose", 100);
        optional(element, namespace, parameters, "techCode", "TechCode", 10);
        optional(element, namespace, parameters, "fuelCode", "FuelCode", 10);
        child(element, namespace, "DateActivate", date(parameters, "activate").toString());
        if (parameters.hasNonNull("deactivate")) {
            child(element, namespace, "DateDeactivate", date(parameters, "deactivate").toString());
        }
        addPeriodFactor(element, namespace, parameters, ALLOCATION_FACTOR, "ECShare", operation.equals(CHANGE_ALLOCATION));
        if (parameters.hasNonNull("calculatedShares")) {
            throw invalid("calculatedShares are DSO response values, not caller-set allocation commands");
        }
    }

    private static void addPeriodFactor(Element element, String namespace, JsonNode parameters,
                                        String field, String wireField, boolean required) {
        if (parameters.hasNonNull(field)) {
            child(element, namespace, wireField, factor(parameters, field, PARTICIPATION_FACTOR.equals(field)));
        } else if (required) {
            throw invalid("Each period needs " + field);
        }
    }

    private static byte[] serialize(Document document) throws TransformerException {
        var factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var output = new ByteArrayOutputStream();
        factory.newTransformer().transform(new DOMSource(document), new StreamResult(output));
        return output.toByteArray();
    }

    public static Document parse(byte[] xml) {
        try {
            return factory().newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            throw invalid("Invalid or unsafe XML");
        }
    }

    static void validate(byte[] xml, String schemaName) {
        try (var common = schemaInput("CPCommonTypes_01p20.xsd"); var specific = schemaInput(schemaName)) {
            var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            // Supply both pinned documents directly: resolving imports never needs filesystem or network access.
            var schema = factory.newSchema(new Source[]{new StreamSource(common), new StreamSource(specific)});
            var validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.validate(new StreamSource(new ByteArrayInputStream(xml)));
        } catch (SAXException | IOException | IllegalStateException exception) {
            throw invalid("EDA schema validation failed: " + exception.getMessage());
        }
    }

    private static InputStream schemaInput(String name) {
        var input = EdaEnergySharingXml.class.getResourceAsStream("/energy-sharing/xsd/" + name);
        if (input == null) {
            throw new IllegalStateException("Missing pinned EDA schema " + name);
        }
        return input;
    }

    private static DocumentBuilderFactory factory() throws ParserConfigurationException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static void address(Element routing, String name, String value) {
        var element = child(routing, CT, "ct:" + name, null);
        element.setAttribute("AddressType", "ECNumber");
        child(element, CT, "ct:MessageAddress", value);
    }

    private static Element child(Element parent, String namespace, String name, @Nullable String value) {
        var element = parent.getOwnerDocument().createElementNS(namespace, name);
        if (value != null) {
            element.setTextContent(value);
        }
        parent.appendChild(element);
        return element;
    }

    private static void optional(Element element, String namespace, JsonNode parameters, String key, String wire, int limit) {
        if (parameters.hasNonNull(key)) {
            child(element, namespace, wire, bounded(text(parameters, key), limit));
        }
    }

    private static void optionalEnum(Element element, String namespace, JsonNode parameters, String key, String wire, Set<String> values) {
        if (parameters.hasNonNull(key)) {
            child(element, namespace, wire, enumValue(parameters, key, values));
        }
    }

    private static String enumValue(JsonNode parameters, String key, Set<String> values) {
        var value = text(parameters, key);
        if (!values.contains(value)) {
            throw invalid(INVALID + key);
        }
        return value;
    }

    public static String text(JsonNode parameters, String key) {
        var value = parameters.get(key);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw invalid("Missing " + key);
        }
        return value.asText();
    }

    private static String bounded(String value, int limit) {
        if (value.isBlank() || value.length() > limit) {
            throw invalid("Invalid field length");
        }
        return value;
    }

    private static String identifier(JsonNode parameters, String key, int max) {
        var value = text(parameters, key);
        if (!value.matches("[A-Za-z0-9]{1," + max + "}")) {
            throw invalid(INVALID + key);
        }
        return value;
    }

    private static String factor(JsonNode parameters, String key, boolean participation) {
        try {
            var value = new BigDecimal(text(parameters, key));
            var minimum = participation ? BigDecimal.ONE : BigDecimal.ZERO;
            if (value.compareTo(minimum) < 0 || value.compareTo(new BigDecimal("100")) > 0
                    || value.stripTrailingZeros().scale() > (participation ? 0 : 4)) {
                throw invalid(INVALID + key);
            }
            return value.toPlainString();
        } catch (NumberFormatException exception) {
            throw invalid(INVALID + key);
        }
    }

    public static LocalDate date(JsonNode parameters, String key) {
        try {
            return LocalDate.parse(text(parameters, key));
        } catch (DateTimeException exception) {
            throw invalid(INVALID + key);
        }
    }

    private static void future(LocalDate date, LocalDate from, LocalDate to) {
        if (date.isBefore(from) || date.isAfter(to)) {
            throw invalid("Date outside EDA permitted interval");
        }
    }

    static LocalDate workdayLimit(LocalDate now) {
        var date = now;
        int workingDays = 0;
        while (workingDays < 30) {
            date = date.plusDays(1);
            if (date.getDayOfWeek().getValue() < 6 && !holiday(date)) {
                workingDays++;
            }
        }
        return date;
    }

    static boolean holiday(LocalDate date) {
        if (FIXED_HOLIDAYS.contains(MonthDay.from(date))) {
            return true;
        }
        var easter = easterSunday(date.getYear());
        return List.of(1, 39, 50, 60).stream().anyMatch(offset -> date.equals(easter.plusDays(offset)));
    }

    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int monthAndDay = h + l - 7 * m + 114;
        return LocalDate.of(year, monthAndDay / 31, monthAndDay % 31 + 1);
    }

    private static EnergySharingException invalid(String message) {
        return new EnergySharingException(INVALID_REQUEST, message);
    }
}
