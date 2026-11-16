# Request missing community measurements

`REQUEST_MEASUREMENTS` is an optional, advertised operation of the existing country-neutral energy-sharing management SPI. A caller explicitly submits the normal `EnergySharingRequest` envelope and polls the same scoped operation. Reading capabilities, status or measurements never initiates this request. Other regional connectors remain unsupported until they advertise a national mapping.

The Austrian profile maps it to **CR_REQ_PT 04.30**, effective **5 October 2026**, with the existing `CR_REQ_PT_03.00` transport schema set. No Messenger partner, agreement or routing is created or changed. The configured operator must already be authorized for the exact connection, EC-ID and DSO.

| Parameter | Meaning |
| --- | --- |
| `meteringPointId` | One exact known Austrian national metering point in this community |
| `start` | First requested local date, inclusive |
| `end` | Last requested local date, inclusive |
| `meteringInterval` | Optional `QH`, `H`, `D` or `V`; absence leaves the native interval unspecified |

The native CPRequest01p12 contains the operator as sender, configured DSO as receiver, `ANFORDERUNG_PT`, creation-day `ProcessDate`, the point, and mandatory `Extension/DateTimeFrom` and `DateTimeTo`. Vienna midnight at `start` and midnight after `end` provide the correct 23/25-hour DST days. `AssumptionOfCosts=false` satisfies the XSD without accepting charges. No EC-ID or ConsentId is invented: this native request has neither field. Returned operator documents still require their own EC-ID, qualified point, DSO and membership authorization before entering any tenant's ledger.

The connector verifies a complete, correctly scoped native roster confirmed within 24 hours. It selects exactly one point by national coding scheme, point identifier and DSO. Every requested day must belong to exactly one authoritative period, intersecting DateFrom/DateTo with DateActivate/DateDeactivate. The original roster revision and native periods are captured under the snapshot lock in the same transaction as the durable request identity. Caller-supplied authority, a customer CESU grant, missing periods or stale confirmation cannot replace this proof.

Only completed past dates within three years are accepted. The official LPZ lower boundary of 4 October 2021 is already older than this profile's three-year window from its October 2026 effective date. For this missing-data action, the connector derives the transmission cycle from the authoritative periods: daily requests wait three market working days after expected next-day receipt; monthly requests wait until the sixth of the following month. For missing/variable native cycles (the field is optional), the application conservatively enforces the later of both waiting dates. It does not infer a transmission cycle; recent requests can therefore wait longer, while authorized older history remains requestable. The market working-day calendar excludes weekends, Austrian legal holidays, Good Friday, 24 and 31 December. This is the conservative Austrian market calendar used for the waiting safeguard; the DSO remains responsible for its definitive process checks. No fixed two-year maximum or arbitrary one-month request cap is imposed.

A request ID reserves the exact payload durably before transport. Reusing it with changed parameters fails. Reusing it unchanged does not resend, including after an uncertain transport outcome.

## Status and delivery evidence

`ANTWORT_PT` with response code **70** produces `ACKNOWLEDGED` and `result.stage=REQUEST_ACCEPTED`. This means the DSO accepted the request, possibly only in part. `ABLEHNUNG_PT` accepts the documented codes **55, 56, 76, 82, 94** and produces `REJECTED`. Replies must pass pinned CPNotification01p13 validation and match the original ConversationId, OriginalMessageID, sender and receiver. Unrelated legacy retransmission replies remain with their existing handler.

CR_REQ_PT04.30 requires subsequent `CR_MSG` deliveries to retain the original ConversationId. The operation's read-only status projection therefore reports:

- `deliveryStatus`: `NO_MATCHING_DOCUMENTS` or `DOCUMENTS_RECEIVED`;
- `receivedDocumentCount` and `receivedReadingCount`: accepted immutable ledger documents/readings for the exact connection/operator/community, point and conversation;
- `coverageComplete=false`: a receipt is not a completeness certificate. Data may be partial, contain corrections, or use the DSO's smallest available energy interval extending beyond the requested bounds.

The measurement feed retains native timestamps, qualities, OBIS tags and original document provenance. The request operation does not duplicate readings or replace the existing correction-selection policy. There is no automatic retry, automatic demand/backfill, or live request in tests.

## Primary specifications

- [CR_REQ_PT04.30 current process and deadlines](https://www.ebutilities.at/prozesse/717), [public process definition](https://www.ebutilities.at/api/processes/show/717).
- [Current process message/schema and response-code mapping](https://www.ebutilities.at/api/market-notifications/process/717/notifications/list).
- [CPRequest01p12 schema](https://www.ebutilities.at/schemas/68), [official XSD](https://www.ebutilities.at/schemata/customerprocesses/cprequest/01p12/CPRequest_01p12.xsd).
- [E-Control market working-day explanation, WVO2014](https://www.e-control.at/documents/1785851/1811312/erlaeuterungen-zur-wvo-2014.pdf/be4aa45d-53a8-437c-8158-5f330055dc6d?t=1413910408336).

The implementation uses the repository's pinned official XSDs; tests validate outgoing and incoming wire documents locally without contacting EDA.
