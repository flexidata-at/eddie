// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.EnergySharingException;
import energy.eddie.api.agnostic.energysharing.EnergySharingMeasurement;
import energy.eddie.api.agnostic.energysharing.EnergySharingMeasurementDocument;
import energy.eddie.cim.v1_12.StandardCodingSchemeTypeList;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import tools.jackson.databind.ObjectMapper;

import javax.xml.XMLConstants;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

import static energy.eddie.api.agnostic.energysharing.EnergySharingException.Code.INVALID_REQUEST;

/** Pinned CR_MSG projection. Native DTF/DTT are authoritative, including DST and variable intervals. */
final class EdaOperatorMeasurementXml {
    static final int MAX_READINGS = 100_000;
    // Reserve 1 MiB for the scope/page envelope under the public 32 MiB response bound.
    static final int MAX_JSON_BYTES = 31 * 1024 * 1024;
    private static final String METER_CODE = "MeterCode";
    private static final String NS = "http://www.ebutilities.at/schemata/customerprocesses/consumptionrecord/01p41";
    private static final String COMMON = "http://www.ebutilities.at/schemata/customerprocesses/common/types/01p20";
    private static final Map<String, String> TAGS = Map.ofEntries(
            Map.entry("1-1:1.9.0 G.01", "METERED_IMPORT"),
            Map.entry("1-1:2.9.0 G.01", "METERED_EXPORT"),
            Map.entry("1-1:1.9.0 G.01T", "PARTICIPATION_IMPORT"),
            Map.entry("1-1:2.9.0 G.01T", "PARTICIPATION_EXPORT"),
            Map.entry("1-1:2.9.0 G.02", "ALLOCATION_ENTITLEMENT"),
            Map.entry("1-1:2.9.0 G.03", "SHARED_COVERAGE"),
            Map.entry("1-1:2.9.0 G.03R", "RENEWABLE_SHARED_COVERAGE"),
            Map.entry("1-1:1.9.0 P.01", "RESIDUAL_IMPORT"),
            Map.entry("1-1:2.9.0 P.01", "RESIDUAL_EXPORT"),
            Map.entry("1-1:2.9.0 P.01T", "COMMUNITY_RESIDUAL_EXPORT"));
    private final ObjectMapper mapper;

    EdaOperatorMeasurementXml(ObjectMapper mapper) { this.mapper = mapper; }

    record Parsed(String communityId, String fingerprint, EnergySharingMeasurementDocument document) {}

    Parsed parse(byte[] bytes, Instant receivedAt) {
        var dom = EdaEnergySharingXml.parse(bytes);
        EdaEnergySharingXml.validate(bytes, "ConsumptionRecord_01p41.xsd");
        var root = dom.getDocumentElement();
        if (!NS.equals(root.getNamespaceURI()) || !"ConsumptionRecord".equals(root.getLocalName())) {
            throw invalid("Unexpected consumption record namespace");
        }
        var market = child(root, NS, "MarketParticipantDirectory");
        var routing = child(market, COMMON, "RoutingHeader");
        var process = child(root, NS, "ProcessDirectory");
        String code = value(market, NS, "MessageCode");
        if (!"DATEN_CRMSG".equals(code)) throw invalid("Expected DATEN_CRMSG operator data");
        var nativeData = mapper.createObjectNode();
        nativeData.put("messageCode", code);
        nativeData.put("processDate", value(process, COMMON, "ProcessDate"));
        copyOptional(process, NS, "Sequence", nativeData);
        copyOptional(process, NS, "DeliveryPoint", nativeData);
        nativeData.put("duplicate", market.getAttribute("Duplicate"));
        nativeData.put("countryCode", "AT");
        var readings = new ArrayList<EnergySharingMeasurement>();
        int energyIndex = 0;
        for (var energy : children(process, NS, "Energy")) {
            addEnergy(energy, energyIndex++, readings);
        }
        if (readings.isEmpty()) throw invalid("Consumption record contains no readings");
        requireUnambiguousIntervals(readings);
        nativeData.put("readingCount", readings.size());
        nativeData.put("sourceBytes", bytes.length);
        var createdAt = instant(value(routing, COMMON, "DocumentCreationDateTime"));
        var document = new EnergySharingMeasurementDocument(0, "", value(process, COMMON, "MessageId"),
                value(process, COMMON, "ConversationId"), market.getAttribute("SchemaVersion"), createdAt, receivedAt,
                value(process, COMMON, "MeteringPoint"), StandardCodingSchemeTypeList.AUSTRIA_NATIONAL_CODING_SCHEME.value(),
                value(child(routing, COMMON, "Sender"), COMMON, "MessageAddress"),
                value(child(routing, COMMON, "Receiver"), COMMON, "MessageAddress"), hash(bytes), readings, nativeData);
        var digest = digest();
        canonical(root, digest);
        return new Parsed(value(process, NS, "ECID"), HexFormat.of().formatHex(digest.digest()), document);
    }

    private void addEnergy(Element energy, int energyIndex, List<EnergySharingMeasurement> readings) {
        Instant periodStart = instant(value(energy, NS, "MeteringPeriodStart"));
        Instant periodEnd = instant(value(energy, NS, "MeteringPeriodEnd"));
        String interval = value(energy, NS, "MeteringIntervall");
        int dataIndex = 0;
        for (var data : children(energy, NS, "EnergyData")) {
            int positionIndex = 0;
            for (var position : children(data, NS, "EP")) {
                if (readings.size() >= MAX_READINGS) throw invalid("Consumption record exceeds 100000 readings");
                var start = instant(value(position, NS, "DTF"));
                var end = instant(value(position, NS, "DTT"));
                requireWithinPeriod(start, end, periodStart, periodEnd);
                var nativeData = mapper.createObjectNode();
                for (String field : List.of("DTF", "DTT", "MM", "BQ")) {
                    nativeData.put(field, value(position, NS, field));
                }
                nativeData.put(METER_CODE, data.getAttribute(METER_CODE));
                nativeData.put("UOM", data.getAttribute("UOM"));
                nativeData.put("energyIndex", energyIndex);
                nativeData.put("dataIndex", dataIndex);
                nativeData.put("positionIndex", positionIndex++);
                for (String field : List.of("MeteringPeriodStart", "MeteringPeriodEnd", "NumberOfMeteringIntervall")) {
                    nativeData.put(field, value(energy, NS, field));
                }
                copyOptional(energy, NS, "MeteringReason", nativeData);
                String meterCode = data.getAttribute(METER_CODE);
                nativeData.put("semanticRole", TAGS.getOrDefault(meterCode, "UNKNOWN"));
                readings.add(new EnergySharingMeasurement(start, end, new BigDecimal(value(position, NS, "BQ")),
                        data.getAttribute("UOM"), meterCode, meterCode,
                        value(position, NS, "MM"), interval, nativeData));
            }
            dataIndex++;
        }
    }

    private static void requireWithinPeriod(Instant start, Instant end, Instant periodStart, Instant periodEnd) {
        if (!start.isBefore(end) || start.isBefore(periodStart) || end.isAfter(periodEnd)) {
            throw invalid("Reading interval is outside its declared energy period");
        }
    }

    private static void requireUnambiguousIntervals(List<EnergySharingMeasurement> readings) {
        var previous = new HashMap<String, Instant>();
        for (var reading : readings.stream().sorted(Comparator.comparing(EnergySharingMeasurement::start)).toList()) {
            String key = reading.meterCode() + "|" + reading.unit();
            Instant end = previous.put(key, reading.end());
            if (end != null && reading.start().isBefore(end)) throw invalid("Overlapping readings in one source series");
        }
    }

    private static void copyOptional(Element parent, String namespace, String name,
                                     tools.jackson.databind.node.ObjectNode target) {
        var values = children(parent, namespace, name);
        if (!values.isEmpty()) target.put(name, values.getFirst().getTextContent());
    }

    private static List<Element> children(Element parent, String namespace, String name) {
        var result = new ArrayList<Element>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && namespace.equals(element.getNamespaceURI())
                    && name.equals(element.getLocalName())) result.add(element);
        }
        return result;
    }

    private static Element child(Element parent, String namespace, String name) {
        var found = children(parent, namespace, name);
        if (found.size() != 1) throw invalid("Missing or repeated " + name);
        return found.getFirst();
    }

    private static String value(Element parent, String namespace, String name) {
        return child(parent, namespace, name).getTextContent().trim();
    }

    private static Instant instant(String value) {
        try { return OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeParseException exception) { throw invalid("Timestamp must include its explicit offset"); }
    }

    /** Namespace-aware semantic hash ignores transport Duplicate and prefix/indentation changes only. */
    private static void canonical(Element element, MessageDigest digest) {
        token(digest, element.getNamespaceURI());
        token(digest, element.getLocalName());
        var attributes = new TreeMap<String, String>();
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            var attribute = element.getAttributes().item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                    || ("MarketParticipantDirectory".equals(element.getLocalName()) && "Duplicate".equals(attribute.getNodeName()))) continue;
            attributes.put(Objects.toString(attribute.getNamespaceURI(), "") + ":" + attribute.getNodeName(), attribute.getNodeValue());
        }
        attributes.forEach((key, value) -> { token(digest, key); token(digest, value); });
        token(digest, "children");
        boolean hasChildElements = false;
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                hasChildElements = true;
                canonical(child, digest);
            }
        }
        // Text and CDATA are equivalent XML character content, never omitted from identity.
        if (!hasChildElements) token(digest, element.getTextContent().trim());
        token(digest, "end");
    }

    private static void token(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length + ":").getBytes(StandardCharsets.UTF_8));
        digest.update(bytes);
    }

    static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static EnergySharingException invalid(String message) { return new EnergySharingException(INVALID_REQUEST, message); }
}
