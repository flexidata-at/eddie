// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import energy.eddie.api.agnostic.energysharing.EnergySharingCommunitySnapshot;
import energy.eddie.api.agnostic.energysharing.EnergySharingException;
import energy.eddie.api.agnostic.energysharing.EnergySharingOperation;
import energy.eddie.api.agnostic.energysharing.EnergySharingRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in EC_PODLIST refresh; no permission, membership, factor or Messenger configuration mutation.
 */
@Component
@EnableConfigurationProperties(EdaMasterDataRefreshConfiguration.class)
public class EdaMasterDataRefreshScheduler {
  private static final Logger LOGGER = LoggerFactory.getLogger(EdaMasterDataRefreshScheduler.class);
  private static final String BLOCKED = "BLOCKED";
  private static final Duration REFRESH_AGE = Duration.ofHours(12);
  private static final Duration CHECK_INTERVAL = Duration.ofMinutes(5);
  private static final Set<String> BLOCKING_STATUSES =
      Set.of("REJECTED", "RECONCILIATION_REQUIRED");
  private final EdaMasterDataRefreshConfiguration configuration;
  private final EdaEnergySharingConfiguration bindings;
  private final EdaMasterDataRefreshStore store;
  private final EdaEnergySharingService service;
  private final ObjectMapper mapper;
  private final Clock clock;

  @Autowired
  public EdaMasterDataRefreshScheduler(
      EdaMasterDataRefreshConfiguration configuration,
      EdaEnergySharingConfiguration bindings,
      EdaMasterDataRefreshStore store,
      EdaEnergySharingService service,
      ObjectMapper mapper) {
    this(configuration, bindings, store, service, mapper, Clock.systemUTC());
  }

  EdaMasterDataRefreshScheduler(
      EdaMasterDataRefreshConfiguration configuration,
      EdaEnergySharingConfiguration bindings,
      EdaMasterDataRefreshStore store,
      EdaEnergySharingService service,
      ObjectMapper mapper,
      Clock clock) {
    this.configuration = configuration;
    this.bindings = bindings;
    this.store = store;
    this.service = service;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
  public void refresh() {
    if (!configuration.enabled()) return;
    for (var target : configuration.targets()) {
      if (!authorized(target)) continue;
      try {
        store
            .claim(target, clock.instant(), UUID.randomUUID().toString())
            .ifPresent(state -> refreshTarget(target, state));
      } catch (RuntimeException exception) {
        // Do not log native payloads or secrets. A failed transaction leaves the prior intent
        // intact.
        LOGGER.warn(
            "Scheduled master-data refresh deferred ({})", exception.getClass().getSimpleName());
      }
    }
  }

  private boolean authorized(EdaMasterDataRefreshConfiguration.Target target) {
    var matches =
        bindings.bindings().stream()
            .filter(
                binding ->
                    target.connectionId().equals(binding.connectionId())
                        && target.operatorId().equals(binding.operatorId()))
            .toList();
    return matches.size() == 1
        && matches.getFirst().partnerConfigured()
        && matches.getFirst().communities().containsKey(target.communityId());
  }

  private void refreshTarget(
      EdaMasterDataRefreshConfiguration.Target target, EdaMasterDataRefreshState state) {
    try {
      var snapshot = snapshot(target);
      var request = state.request();
      if (request == null) prepare(target, state, snapshot);
      else reconcile(target, state, request, snapshot);
    } catch (EnergySharingException exception) {
      var updated =
          switch (exception.code()) {
            case FORBIDDEN, INVALID_REQUEST, UNSUPPORTED, CONFLICT ->
                blocked(state, exception.code().name());
            default -> retry(state, exception.code().name());
          };
      store.save(target, updated, clock.instant(), true);
    } catch (RuntimeException exception) {
      // Keep the frozen intent. A subsequent tick checks its ledger before ever attempting submit.
      store.save(target, retry(state, "CHECK_FAILED"), clock.instant(), true);
    }
  }

  private Optional<NativeConfirmation> snapshot(EdaMasterDataRefreshConfiguration.Target target) {
    var matches =
        service.communities(target.operatorId(), target.connectionId()).stream()
            .filter(
                value ->
                    target.communityId().equals(value.communityId())
                        && target.operatorId().equals(value.operatorId())
                        && target.connectionId().equals(value.connectionId())
                        && value.complete()
                        && !value.receivedAt().isAfter(clock.instant()))
            .toList();
    return matches.size() == 1 ? confirmation(matches.getFirst()) : Optional.empty();
  }

  private Optional<NativeConfirmation> confirmation(EnergySharingCommunitySnapshot snapshot) {
    var parameters = snapshot.nativeParameters();
    String revision = parameters.path("masterDataRevision").asText("");
    String received = parameters.path("masterDataReceivedAt").asText("");
    if (revision.isBlank() || received.isBlank()) return Optional.empty();
    try {
      Instant at = Instant.parse(received);
      return at.isAfter(clock.instant()) || at.isAfter(snapshot.receivedAt())
          ? Optional.empty()
          : Optional.of(new NativeConfirmation(at, revision));
    } catch (java.time.format.DateTimeParseException ignored) {
      return Optional.empty();
    }
  }

  private record NativeConfirmation(Instant receivedAt, String revision) {}

  private void prepare(
      EdaMasterDataRefreshConfiguration.Target target,
      EdaMasterDataRefreshState state,
      Optional<NativeConfirmation> snapshot) {
    if (snapshot.isPresent()
        && snapshot.get().receivedAt().plus(REFRESH_AGE).isAfter(clock.instant())) {
      var value = snapshot.get();
      store.save(
          target,
          state.idleUntil(value.receivedAt().plus(REFRESH_AGE), value.revision()),
          clock.instant(),
          true);
      return;
    }
    if (store.hasOutstandingQuery(target)) {
      store.save(target, retry(state, "EXISTING_QUERY"), clock.instant(), true);
      return;
    }
    var request = newRequest(target);
    var prepared =
        state.preparing(
            request, clock.instant(), snapshot.map(NativeConfirmation::revision).orElse(""));
    if (!store.save(target, prepared, clock.instant(), false)) return;
    // Exceptions must retain PREPARED, never replace it with the preceding empty state.
    reconcilePrepared(target, prepared, request);
  }

  private void reconcilePrepared(
      EdaMasterDataRefreshConfiguration.Target target,
      EdaMasterDataRefreshState prepared,
      EnergySharingRequest request) {
    try {
      recordOperation(target, prepared, service.submit(request), Optional.empty());
    } catch (EnergySharingException exception) {
      var outcome =
          switch (exception.code()) {
            case FORBIDDEN, INVALID_REQUEST, UNSUPPORTED, CONFLICT ->
                blocked(prepared, exception.code().name());
            default -> retry(prepared, exception.code().name());
          };
      store.save(target, outcome, clock.instant(), true);
    } catch (RuntimeException exception) {
      store.save(target, retry(prepared, "SUBMIT_OUTCOME_UNKNOWN"), clock.instant(), true);
    }
  }

  private void reconcile(
      EdaMasterDataRefreshConfiguration.Target target,
      EdaMasterDataRefreshState state,
      EnergySharingRequest request,
      Optional<NativeConfirmation> snapshot) {
    var existing =
        service.operation(
            request.requestId(), target.connectionId(), target.operatorId(), target.communityId());
    if (existing.isEmpty()) {
      if (BLOCKED.equals(state.phase()))
        store.save(target, blocked(state, "OPERATION_NOT_FOUND"), clock.instant(), true);
      else reconcilePrepared(target, state, request);
    } else recordOperation(target, state, existing.get(), snapshot);
  }

  private void recordOperation(
      EdaMasterDataRefreshConfiguration.Target target,
      EdaMasterDataRefreshState state,
      EnergySharingOperation operation,
      Optional<NativeConfirmation> snapshot) {
    EdaMasterDataRefreshState next;
    if ("COMPLETED".equals(operation.status()) && confirmedAfterRequest(state, snapshot)) {
      var value = snapshot.orElseThrow();
      next = state.idleUntil(value.receivedAt().plus(REFRESH_AGE), value.revision());
    } else if (BLOCKING_STATUSES.contains(operation.status()) || BLOCKED.equals(state.phase()))
      next = blocked(state, operation.status());
    else if (operation.createdAt().plus(Duration.ofHours(24)).isBefore(clock.instant()))
      next = blocked(state, "NATIVE_RESPONSE_TIMEOUT");
    else next = state.waiting(clock.instant().plus(CHECK_INTERVAL), operation.status(), 0);
    store.save(target, next, clock.instant(), true);
  }

  private static boolean confirmedAfterRequest(
      EdaMasterDataRefreshState state, Optional<NativeConfirmation> snapshot) {
    Instant createdAt = state.requestCreatedAt();
    return snapshot.isPresent()
        && createdAt != null
        && !snapshot.get().revision().equals(state.triggerRevision())
        && !snapshot.get().receivedAt().isBefore(createdAt);
  }

  private EdaMasterDataRefreshState blocked(EdaMasterDataRefreshState state, String reason) {
    return state.blocked(reason, clock.instant().plus(Duration.ofHours(1)));
  }

  private EdaMasterDataRefreshState retry(EdaMasterDataRefreshState state, String reason) {
    if (BLOCKED.equals(state.phase())) return blocked(state, reason);
    int failures = Math.min(state.failures() + 1, 12);
    long minutes = Math.min(60, 5L << Math.min(failures - 1, 4));
    return state.waiting(clock.instant().plus(Duration.ofMinutes(minutes)), reason, failures);
  }

  private EnergySharingRequest newRequest(EdaMasterDataRefreshConfiguration.Target target) {
    var today = LocalDate.now(clock.withZone(EdaEnergySharingXml.VIENNA));
    var parameters =
        mapper
            .createObjectNode()
            .put("start", today.minusDays(30).toString())
            .put("end", today.plusDays(30).toString());
    return new EnergySharingRequest(
        "auto-roster-" + UUID.randomUUID(),
        target.connectionId(),
        target.operatorId(),
        target.communityId(),
        "SYNC_MASTER_DATA",
        EdaEnergySharingXml.VERSION,
        parameters);
  }
}
