# Austrian energy-sharing management profile

The optional region-neutral management SPI uses profile AT-EDA-BEM, revision
2026-10-05, country AT. It supports online membership requests, departure,
participation-factor changes, existing static allocation changes, and authoritative
community/member synchronization. A submitted or transport-delivered message is
not a confirmed market action.

## Operator authorization

Configure each eligible-party connection explicitly. IDs entered in a UI never
create these bindings. The community map contains EC-ID → DSO market-partner ID.

    region-connector:
      at:
        eda:
          energy-sharing:
            bindings:
              - connection-id: tenant-connection-123
                operator-id: PP123456
                partner-configured: false
                communities:
                  "[AT0000000000000000000000000000001]": AT001000

The IDs above are examples. The partner-configured flag must only become true after
an administrator verifies the EDA/DSO contracts and mandate, local and remote PONTON
partners/certificates, agreements, authorized schema sets, and adapter routing.
Messenger health must also be good. The development WebPonton replacement does
not claim support for production management sends. Capabilities provide the
ordered setup steps; this module does not invent a PONTON administration API.

No public EC_LIST operator-discovery process was verified. An empty community ID
on SYNC_MASTER_DATA fans out to this binding's configured communities.
EDA_REQ_EC is published for DSO/EDA roles and requires an already known EC-ID;
it must not be used to discover arbitrary RC/CC communities.

## Parameter contract

Capabilities return a complete JSON Schema per supported operation. National
fields stay inside parameters; the core interface does not acquire Austrian
field names. The schemas are in
src/main/resources/energy-sharing/at-eda-2026-10-05.json.

- JOIN: CMRequest01.30 / EC_REQ_ONL_02.40; no end/DateTo. Start is tomorrow
  through 30 Austrian working days ahead. Direction is CONSUMPTION or GENERATION.
- LEAVE: CMRevoke01.10 / CM_REV_SP_01.30; meter, consent ID and end date required.
- CHANGE_PARTICIPATION_FACTOR: ECMPList01.20 / EC_PRTFACT_CHANGE_01.10.
  Participation is an integer 1–100 percent, distinct from static allocation.
- CHANGE_ALLOCATION: ECMPList01.20 / EC_PRT_CHANGE_02.10. Requires a confirmed
  static (S) community snapshot. Decimal allocation has at most four fractional
  places. New P2P agreements are dynamic.
- Both changes include the required effective-period structures and all future
  scheduled periods. DSO-calculated shares are response-only. A durable daily
  reservation prevents another request ID from submitting the same kind of change
  for the operator/community/meter that day, including across connection bindings.
  The current profile only sends during 09:00–17:00 Vienna time for the next day.
- SYNC_MASTER_DATA: CPRequest01.12 / EC_PODLIST_02.10. With no dates, requests
  30 calendar days before through 30 calendar days after the submission date.
  DateTimeTo is the following local midnight so the requested end date is included.
  The exact effective query range is persisted with the request.

Parameter names not supported by the selected operation are rejected. XML is
constructed through DOM and checked against pinned official XSDs packaged with
the connector. Unknown response content is retained in the native XML rather than
silently discarded. Imported snapshots include one item per meter, explicit
Austrian coding scheme and DSO identity, and all native time periods.

## Durability and authoritative state

The PostgreSQL migration creates a durable operation ledger, daily reservations,
and community snapshots. Request identity includes connection, operator,
community and client request ID. A repeated ID with different parameters fails.
The intent is committed before transport; an ambiguous send becomes
RECONCILIATION_REQUIRED. PONTON sends for this workflow are not blindly retried.
The same ID never sends a second copy after an uncertain result.
New management message/conversation IDs retain the configured
`region-connector.at.eda.conversation-id.prefix`, so shared PONTON agreements can
route replies to the correct adapter. Their random suffix keeps requests unique
even within the same millisecond, within EDA's 35-character alphanumeric limit.

Replies are routed by the persisted market conversation and checked against
sender, receiver, community, meter, operation and pinned schema. Transport
acknowledgment and customer consent remain distinct from market completion.
Validated, correlated consent notifications expose their consent ID and an
actionable portalApprovalUrl only when it is an absolute HTTPS URL without user
information. The customer must complete that approval; the connector does not
open the link or grant consent. Original response XML remains available even if
its URL cannot safely be exposed as an action.
Duplicates cannot regress terminal state. Snapshot application is locked and
ordered by the original request time; equal or older requests cannot replace a
newer authoritative snapshot.

Current-range synchronization replaces only the authoritative queried window and
retains prior periods and meters outside it. Historical-only queries remain
inspectable in their operation result and do not replace the current roster.
Empty lists are meaningful only after a valid, correlated complete ECP response.
The complete flag describes the assembled roster with its explicit queried range;
it does not claim knowledge of all historical or future national records.

Successful join/leave/factor/allocation replies trigger a bounded follow-up
EC_PODLIST query. If that query cannot start, the result records
reconciliationPending. Customer and implicit revocations match authorized
DSO/operator plus meter and consent, persist event deduplication, trim affected
time periods, and also continue through the existing permission pipeline.

## Verification and remaining deployment work

Automated tests use real published schemas, simulated transport, and a local
PostgreSQL test container. They never send to EDA or configure production
partners. DSO interoperability acceptance, contractual mandates and production
Messenger agreements are deployment prerequisites, not implied by passing tests.

Primary definitions:

- [Online registration](https://www.ebutilities.at/prozesse/711)
- [Participation factor](https://www.ebutilities.at/prozesse/718)
- [Static allocation](https://www.ebutilities.at/prozesse/722)
- [Community reconciliation](https://www.ebutilities.at/prozesse/736)
- [Operator departure](https://www.ebutilities.at/prozesse/727)
- [Restricted citizen-energy master-data query](https://www.ebutilities.at/prozesse/732)
- [EDA FAQ](https://www.eda.at/faq)
- [EDA connection types](https://www.eda.at/anbindungsarten)
