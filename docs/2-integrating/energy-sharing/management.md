# Energy sharing management

Energy sharing is an optional region-connector capability. It manages market participation and community master data separately from EDDIE data-access permissions. A market membership, an invitation, a granted data permission, a price agreement and a settled energy quantity remain separate records.

## Integration boundary

`RegionConnectorEnergySharingService` is registered by `EnergySharingRouterRegistrar` only when the connector implements it. Existing data-only connectors require no changes. The core router has no Austrian field names, percentage rules, EDA process codes or country fallback.

The management API is exposed on the existing **trusted management port**, under `/management/energy-sharing`. It must never be published as an unauthenticated customer endpoint. The eligible-party backend authenticates the operator, checks application/tenant administration and the verified market-operator assignment, then sends a server-owned `connectionId`. Regional adapters independently bind that connection to the authorized operator and communities. RC/CC/PP numbers are identifiers, not credentials.

| Method | Path relative to `/management/energy-sharing` | Meaning |
| --- | --- | --- |
| GET | `/region-connectors` | Connectors implementing this capability |
| GET | `/{region}/capabilities?operatorId=…&connectionId=…` | Country, profile version, actual operations, parameter schemas and setup steps |
| POST | `/{region}/operations` | Submit a scoped, idempotent operation |
| GET | `/{region}/operations/{requestId}?operatorId=…&connectionId=…&communityId=…` | Reconcile an existing operation without resending |
| GET | `/{region}/communities?operatorId=…&connectionId=…` | Latest correlated community snapshots |
| GET | `/{region}/communities/{communityId}?operatorId=…&connectionId=…` | One scoped community snapshot |

Capabilities carry `countryCode`, `profileId`, `profileVersion`, `operatorId`, `transportReady`, `operations`, `parameterSchemas` and `setupSteps`. A schema describes the allowed fields for that operation; it does not authorize the operation. The adapter validates both process rules and its configured authority. A future country supplies its own profile, schemas, validation, transport and reply reconciliation without changing the common controller.

## Submission and lifecycle

Requests contain `requestId`, `connectionId`, `operatorId`, `communityId`, `operation`, `profileVersion` and an object `parameters`. Only initial `SYNC_MASTER_DATA` may omit the community ID by supplying an empty string, and only an authorized regional registry/configuration may resolve it. An unsupported discovery operation returns a setup requirement rather than invented master data.

The caller persists the request and its unique ID before transport. Retrying the same ID with changed content is a conflict. Lost responses are reconciled with GET; an uncertain send must not generate a replacement operation ID. The regional adapter persists its correlation and idempotency record before sending. Process versions and transport schema-set versions belong to the regional adapter.

HTTP 202 and status `SUBMITTED` only confirm submission. `ACKNOWLEDGED` means the market partner acknowledged receipt. `COMPLETED` is based on the appropriate authoritative process response; the requested effective date can still be in the future. `REJECTED`, `FAILED` and `RECONCILIATION_REQUIRED` preserve native codes and useful messages. Native audit payloads remain inside the authorized operator scope and must not be written into public logs or error responses. An EDA online consent acknowledgment must never be rendered as active participation.

## Master-data synchronization

Snapshots carry an explicit operator/connection/community scope, opaque `revision`, `receivedAt`, `complete`, qualified member identifiers and country-native parameters. Each member preserves its case-sensitive `meteringPointId` and explicit `codingScheme`, with separate `participationFactor` and `allocationFactor`. The national payload preserves effective periods and process-specific fields; it must not flatten future or historical periods into one misleading current value.

The regional adapter validates sender, recipient, conversation, community and meter correlation, and handles late/out-of-order replies before publishing snapshots. Clients ignore duplicate revisions and older snapshots. A partial response cannot delete absent members. A complete empty list may clear that community's market roster, but it never deletes the customer's independent account or data permissions. Data consent remains customer-controlled and is checked separately on every data access.

## Adding a country

1. Implement the optional service with country-specific capabilities and versioned parameter schemas.
2. Bind verified operator authority to the caller's server-owned connection scope.
3. Implement durable idempotency, send correlation and uncertain-delivery recovery.
4. Validate national message schemas and business constraints, then process authoritative replies with replay/out-of-order protection.
5. Publish normalized snapshots while preserving national fields and original identifier case.
6. Test the connector with its market partners before advertising production readiness. Unsupported operations remain unavailable.

The Austrian profile and current EDA limitations are documented in the connector implementation and in the Energy Sharing Reference Model. No generic API endpoint itself provisions an XP Messenger partner, grants customer consent or executes financial settlement.
