# Automatic Austrian master-data confirmation

The Austrian connector can periodically request `EC_PODLIST` for an explicit, server-configured community. This feature is **disabled by default**. It does not create PONTON partners, change agreements or routing, join or remove members, change factors, or send customer invitations.

Configure both the existing authorized energy-sharing binding and the additional opt-in:

```properties
region-connector.at.eda.energy-sharing.master-data-refresh.enabled=false
region-connector.at.eda.energy-sharing.master-data-refresh.targets[0].connection-id=your-authorized-connection
region-connector.at.eda.energy-sharing.master-data-refresh.targets[0].operator-id=your-authorized-operator
region-connector.at.eda.energy-sharing.master-data-refresh.targets[0].community-id=your-authorized-ec-id
```

An enabled target must match exactly one existing binding, including `partnerConfigured=true` and its EC-ID-to-DSO mapping. A community cannot be configured twice with different connections. There are at most 100 targets. Deployment must explicitly review the target and existing transport authority before setting `enabled=true`; ordinary client input never enables the task.

## Confirmation and cadence

A one-minute scheduler checks durable work. It requests master data when the last complete native confirmation is at least 12 hours old. The request uses the current Vienna date minus/plus 30 days, frozen when its request ID is created. The existing official profile, XML generation, DSO transport and response validation are reused.

Accepted, correlated `SENDEN_ECP` snapshots expose `nativeParameters.masterDataReceivedAt` and `masterDataRevision`. These identify receipt of the full master-data response and its native message ID. An individual customer revocation can change the snapshot's general `receivedAt` and `revision`, but preserves the master-data confirmation fields. A transport acknowledgment, completed poll, pending request, import, or scheduler run never renews membership authority. A legacy snapshot without the explicit master-data fields causes one initial query after opt-in.

The scheduler does not change the downstream maximum authority age. If transport or a native response fails, the existing confirmation grows stale and consumers must continue to fail closed according to their authority policy.

## Durable behavior and recovery

State is stored in the existing `at_eda.energy_sharing_record` table under the separate `MASTER_DATA_REFRESH` kind, with a null market-correlation ID. No migration is needed. Atomic row locking and a two-minute lease prevent overlapping scheduled owners. The request ID, exact scope, profile version, dates and original trigger revision are persisted before submission. A crashed worker resumes that same intent. The normal operation ledger prevents a retry from resending an already recorded market request.

An existing nonterminal `SYNC_MASTER_DATA` operation for this exact scope/community postpones creation of a scheduled query. Snapshots, daily-factor guards and other record kinds cannot accidentally block it. Read/check failures and a transport that is not ready use exponential 5–60 minute backoff. Submitted/acknowledged operations are checked every five minutes. Only a completed operation together with a newer native master-data confirmation allows the next cycle. Rejected or reconciliation-required operations, and operations without confirmation after 24 hours, block automatic replacement commands. A blocked request is checked once an hour, read-only, even after an uncertain result or missing ledger entry. Only a later completed operation together with a genuinely newer native master-data confirmation releases that block. Failed checks retain the block; they never resend it. The last immutable intent and normal operation ledger remain available for investigation; there is no blind reset or resend endpoint.

For uncertain outcomes, reconcile the native operation and transport evidence first. A restart alone never creates another request ID. Configuration removal/disablement stops future scheduled work; it neither cancels nor revokes anything already submitted. Existing explicitly requested manual operations retain their normal behavior.

Tests use synthetic services and isolated PostgreSQL. They cover exact scope, default disablement, lease competition and expiry, crash recovery, immutable request reuse, independent record kinds, native confirmation versus revocation, uncertain outcomes, timeout and backoff. Enabling this configuration is a real read-only market-data request and must be reviewed as part of the target deployment.
