# Maintained Dev integration behaviour

The November 2026 fork refresh includes scoped CESU community joins and allocation.
Deployments that previously used the VALANDEX Dev runtime must also preserve the
runtime fixes below. They are source changes with regression tests, rather than
binary overlays on an unrelated upstream image.

- Persisted AT/AIIDA connection-status and permission-document replay, a current
  status per permission, lifecycle buffering for late receivers and serialized
  emission. AIIDA subscriptions reconnect after startup and connection loss.
- Datadis retries authorization visibility, and embedded forms retain progress,
  server-provided identifiers and retry feedback. Repeated/CSV data-need IDs are
  normalized, and historical/future data requests do not overlap at midnight.
- Enedis publishes available readings while keeping incomplete batches eligible
  for retry. Transport/availability errors do not revoke a consent. Its current
  upstream API uses `mesure_synchrone_auto/v2`; verify provider access before rollout.
- Fluvius preserves bounded HTTPS token requests without redirects and rereads the
  managed certificate bundle at refresh. Existing Dev registrations use
  `region-connector.be.fluvius.oauth.assertion-algorithm=RS256`; PS256 remains the
  upstream-compatible default. Both use a SHA-256 signature.
- AT and DK forms keep disabled prefilled metering points. The connect button
  retains readable colours and avoids dynamic code generation when optional
  string callbacks are absent, allowing normal events on strict-CSP pages.

The existing Dev Ponton routing prefix remains `DATADEV`, with registered sender
`EP100022`. Environment binding is covered by a regression test. The current
upstream grouping-ID format includes the sender after that routing prefix.
Never replace registered parties or enable community IDs using demonstration values.

## Verification and deployment

`./gradlew build integrationTest codeCoverageReport` runs the normal verification.
Root `check` also runs `componentTests` through the repository-managed Node/pnpm
runtime. `node downstream/permission-forms.test.mjs .` runs the cross-connector form
regressions directly; core CSP and AT form tests live beside their components.

Publish an exact tested snapshot using the main-only workflow described in
[FlexiData CI](../.github/CI.md). Pin the resulting registry digest. Preserve Dev
connector schedules, OAuth callbacks, trust configuration, secrets and the 20 MB
codec buffer. The old `werner-eddie-component-csp-v1` whole-core JAR mount must be
removed when switching to this source build, because it would mask the new core.

Before rollout, back up the EDDIE PostgreSQL database and retain the previous
immutable image and deployment specification. The new migrations are
`data_needs/2.6`, `aiida/2.5` and `at_eda/1.6`; they add nullable fields and replace
compatible permission views. An image rollback leaves those migrations in place.
Do not restore an old database over newer events without a separate recovery plan.
