# Community operator measurements

This optional Energy Sharing management capability receives the distribution operator's community measurements independently of individual customer CESU data permissions. Existing consent-based consumption streams continue receiving the same matching documents. This feed does not grant customer data access, create market requests, backfill message history or configure X/P Messenger.

## Trusted API

Both endpoints are available only on the configured management connector. The eligible party must enforce its own authenticated application/operator assignment before calling them. EDDIE additionally checks the configured exact connection, operator, community and DSO on every call.

- `GET /management/energy-sharing/{regionConnectorId}/communities/{communityId}/measurements?operatorId={operator}&connectionId={connection}&afterSequence=0&limit=20`
- `GET /management/energy-sharing/{regionConnectorId}/communities/{communityId}/measurement-documents/{documentId}/source?operatorId={operator}&connectionId={connection}`

Use the configured context and management URL prefix when constructing the URL. Original XML is returned with `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`. The source endpoint uses the same current binding authorization and immutable accepted authority proof as the feed. Other region connectors default to `UNSUPPORTED` until they implement their national profile.

A page has `countryCode`, `connectionId`, `operatorId`, `communityId`, `afterSequence`, `nextSequence`, `hasMore`, and `documents`. Each document contains `sequence`, `documentId`, `sourceMessageId`, `sourceConversationId`, `sourceSchemaVersion`, `sourceDocumentCreatedAt`, `receivedAt`, `meteringPointId`, `codingScheme`, `senderId`, `receiverId`, `rawPayloadSha256`, `readings`, and `nativeParameters`.

Each reading contains its exact `start`, `end`, decimal `value`, `unit`, `valueTag`, `meterCode`, `quality`, `interval`, and `nativeParameters`. National series identities are retained exactly in `valueTag` and `meterCode`, including spaces and case. The qualified point identity is the page country, document coding scheme and exact point code.

Persist the page and its `nextSequence` in one downstream transaction. Every accepted source remains in the feed: a later rolling query or confirmed membership end does not silently erase historical operator data. A document carries immutable `nativeParameters.authorityEvidence` with its exact binding, point, accepted periods and source-snapshot revision/hash. Sequence orders committed delivery, not corrections. Select corrections by source document creation time; equal-time divergent source values require conflict handling. Retain all source revisions and compare matching interval, unit and series identities. Never sum measured, adjusted, allocated and residual series together as if they were independent energy.

## Austrian profile

Only `DATEN_CRMSG` with pinned `ConsumptionRecord_01p41.xsd` is projected. The schema and its pinned common types are bundled by the existing checksum-verified build. External XML entities and external schema resolution are disabled. Exact incoming XML is retained separately from the page and its SHA-256 is exposed for provenance.

Ingestion requires an enabled, server-configured `region-connector.at.eda.energy-sharing.bindings` entry whose receiver/operator, EC-ID and DSO sender match the document. The latest complete authoritative community snapshot must contain that qualified point. Every reading must lie inside its known coverage and activation/deactivation dates; historical periods are retained by the membership synchronization implementation. A missing snapshot produces a temporary transport error, allowing existing delivery retry after synchronization. Unknown/unconfigured operator records continue through the legacy consent path. No source is accepted merely because a caller enters an operator ID.

Native `EP/DTF` and `EP/DTT` are used directly, including daylight-saving transitions and `V` variable intervals. `MM`, `BQ`, `UOM`, energy-block boundaries, interval counts, indexes and process `Sequence` remain available. Unknown OBIS codes stay original, with `nativeParameters.semanticRole=UNKNOWN`. The optional semantic role is descriptive; it does not change the native join key.

The [official OBIS table](https://www.ebutilities.at/documents/2023/12/13122023_MeterCodes_CR_MSG.pdf) distinguishes measured G.01, participation-adjusted G.01T, generation entitlement G.02, shared coverage G.03/G.03R and residual P.01/P.01T. The [quality specification](https://www.ebutilities.at/documents/2024/02/QM_SM_Ersatzwertbildung_Finalversion.pdf), pages 10–11, describes L1 and L2 as final categories that can exceptionally be corrected, while L3 is provisional. This adapter preserves all native MM codes, including 01–05, without inventing a normalized finality classification.

## Durability, bounds and rollout

Flyway `at_eda` migration **1.8** adds only `operator_measurement_cursor` and `operator_measurement_document`. It does not change permission events, existing membership rows, message routes or partner configuration. Each community/connection/operator has an independent cursor. An exclusive cursor-row lock is held through the document commit, so a later writer cannot commit a higher sequence while an earlier sequence remains invisible. A shared lock on the authoritative snapshot prevents a membership update racing initial acceptance. The authority proof commits with the original document. Historical reads require the still-configured exact operator/connection/community binding, without reinterpreting the current query window as a historical access revocation.

The same source message ID and equivalent XML semantics are idempotent. Namespace-prefix, indentation and transport `Duplicate` changes do not create another entry. A reused message ID with changed content is rejected; distinct correction messages are appended. Original bytes from the first accepted delivery remain unchanged.

Input is limited to 16 MiB XML and 100,000 readings per document. Projected documents and combined page payloads are limited to 31 MiB, leaving envelope space below a 32 MiB response limit; pages also stop at 100,000 combined readings. `limit` is 1–100 and defaults to 20. A page never splits a document. Overlapping intervals within one native series or intervals outside declared energy/membership periods are rejected. Bounds reject the whole document rather than silently truncate readings.

Before deployment, back up the current database, verify this additive migration on an isolated restored backup, build/publish an immutable exact-source artifact through the normal fork workflow and verify the trusted/public port boundary. Older software can continue using its prior tables after a binary rollback if its configured Flyway future-migration policy permits 1.8; verify that policy against the actual previous artifact before rollout. No rollback should delete the new ledger or restore a database over newer permission events automatically.
