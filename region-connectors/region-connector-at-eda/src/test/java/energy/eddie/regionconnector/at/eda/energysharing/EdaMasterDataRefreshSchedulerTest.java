// SPDX-FileCopyrightText: 2026 The EDDIE Developers <eddie.developers@fh-hagenberg.at>
// SPDX-License-Identifier: Apache-2.0
package energy.eddie.regionconnector.at.eda.energysharing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import energy.eddie.api.agnostic.energysharing.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EdaMasterDataRefreshSchedulerTest {
  private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
  private static final JsonMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
  private static final EdaMasterDataRefreshConfiguration.Target TARGET =
      new EdaMasterDataRefreshConfiguration.Target("connection", "CC-SYNTHETIC", "EC-SYNTHETIC");
  private final EdaMasterDataRefreshStore store = mock(EdaMasterDataRefreshStore.class);
  private final EdaEnergySharingService service = mock(EdaEnergySharingService.class);
  private final AtomicReference<EdaMasterDataRefreshState> state = new AtomicReference<>();
  private final MutableClock clock = new MutableClock(NOW);
  private final EdaEnergySharingConfiguration bindings =
      new EdaEnergySharingConfiguration(
          List.of(
              new EdaEnergySharingConfiguration.Binding(
                  TARGET.connectionId(),
                  TARGET.operatorId(),
                  Map.of(TARGET.communityId(), "DSO"),
                  true)));
  private EdaMasterDataRefreshScheduler scheduler;

  @BeforeEach
  void setup() {
    scheduler = scheduler(true, TARGET, bindings);
    state.set(EdaMasterDataRefreshState.initial(NOW));
    when(store.claim(eq(TARGET), any(), anyString()))
        .thenAnswer(
            call -> {
              var current = state.get();
              Instant now = call.getArgument(1);
              if (current.nextAttemptAt().isAfter(now) || current.leaseUntil().isAfter(now))
                return Optional.empty();
              var leased = current.leased(call.getArgument(2), now.plusSeconds(120));
              state.set(leased);
              return Optional.of(leased);
            });
    when(store.save(eq(TARGET), any(), any(), anyBoolean()))
        .thenAnswer(
            call -> {
              EdaMasterDataRefreshState next = call.getArgument(1);
              state.set(call.getArgument(3) ? next.released() : next);
              return true;
            });
    when(service.communities(TARGET.operatorId(), TARGET.connectionId()))
        .thenReturn(List.of(snapshot(NOW.minusSeconds(13 * 3600), "old", true)));
    when(service.submit(any()))
        .thenAnswer(call -> operation(call.getArgument(0), "SUBMITTED", NOW));
  }

  private EdaMasterDataRefreshScheduler scheduler(
      boolean enabled,
      EdaMasterDataRefreshConfiguration.Target target,
      EdaEnergySharingConfiguration configuredBindings) {
    return new EdaMasterDataRefreshScheduler(
        new EdaMasterDataRefreshConfiguration(enabled, List.of(target)),
        configuredBindings,
        store,
        service,
        MAPPER,
        clock);
  }

  @Test
  void disabledUnknownConnectionCommunityAndUnconfiguredPartnerNeverClaimOrSend() {
    scheduler(false, TARGET, bindings).refresh();
    scheduler(
            true,
            new EdaMasterDataRefreshConfiguration.Target(
                "foreign", TARGET.operatorId(), TARGET.communityId()),
            bindings)
        .refresh();
    scheduler(
            true,
            new EdaMasterDataRefreshConfiguration.Target(
                TARGET.connectionId(), TARGET.operatorId(), "foreign"),
            bindings)
        .refresh();
    var incomplete =
        new EdaEnergySharingConfiguration(
            List.of(
                new EdaEnergySharingConfiguration.Binding(
                    TARGET.connectionId(),
                    TARGET.operatorId(),
                    Map.of(TARGET.communityId(), "DSO"),
                    false)));
    scheduler(true, TARGET, incomplete).refresh();
    verifyNoInteractions(store, service);
  }

  @Test
  void aNativeConfirmationDefersWorkUntilTwelveHoursWithoutRenewingItsTimestamp() {
    Instant received = NOW.minusSeconds(3600);
    when(service.communities(TARGET.operatorId(), TARGET.connectionId()))
        .thenReturn(List.of(snapshot(received, "fresh", true)));
    scheduler.refresh();
    assertEquals(received.plusSeconds(12 * 3600), state.get().nextAttemptAt());
    assertNull(state.get().request());
    scheduler.refresh();
    verify(service, never()).submit(any());
  }

  @Test
  void onlyOneFrozenScopedRequestIsSubmittedAndPendingDoesNotRenewAuthority() {
    scheduler.refresh();
    var request = state.get().request();
    assertNotNull(request);
    assertEquals("SYNC_MASTER_DATA", request.operation());
    assertEquals(TARGET.connectionId(), request.connectionId());
    assertEquals(TARGET.operatorId(), request.operatorId());
    assertEquals(TARGET.communityId(), request.communityId());
    assertEquals("2026-10-17", request.parameters().path("start").asText());
    assertEquals("2026-12-16", request.parameters().path("end").asText());
    assertEquals("old", state.get().triggerRevision());
    clock.advance(Duration.ofMinutes(5));
    when(service.operation(
            request.requestId(), TARGET.connectionId(), TARGET.operatorId(), TARGET.communityId()))
        .thenReturn(Optional.of(operation(request, "SUBMITTED", NOW)));
    scheduler.refresh();
    assertEquals(request, state.get().request());
    assertEquals("SUBMITTED", state.get().detailCode());
    verify(service, times(1)).submit(any());
  }

  @Test
  void preSubmitFailureRetriesSameImmutableIntentAndUsesBackoff() {
    doThrow(new EnergySharingException(EnergySharingException.Code.NOT_READY, "offline"))
        .when(service)
        .submit(any());
    scheduler.refresh();
    var request = state.get().request();
    assertNotNull(request);
    assertEquals(1, state.get().failures());
    clock.advance(Duration.ofMinutes(5));
    scheduler.refresh();
    assertEquals(request, state.get().request());
    assertEquals(2, state.get().failures());
    assertEquals(clock.instant().plusSeconds(600), state.get().nextAttemptAt());
    verify(service, times(2)).submit(request);
  }

  @Test
  void uncertainOutcomeIsLookedUpAndNeverReplacedOrResubmitted() {
    doThrow(new IllegalStateException("response lost")).when(service).submit(any());
    scheduler.refresh();
    var request = Objects.requireNonNull(state.get().request());
    when(service.operation(
            request.requestId(), TARGET.connectionId(), TARGET.operatorId(), TARGET.communityId()))
        .thenReturn(Optional.of(operation(request, "RECONCILIATION_REQUIRED", NOW)));
    clock.advance(Duration.ofMinutes(5));
    scheduler.refresh();
    assertEquals("BLOCKED", state.get().phase());
    assertEquals("RECONCILIATION_REQUIRED", state.get().detailCode());
    clock.advance(Duration.ofDays(2));
    scheduler.refresh();
    verify(service, times(1)).submit(any());
  }

  @Test
  void onlyCompletedOperationWithANewNativeConfirmationStartsTheNextCycle() {
    scheduler.refresh();
    var request = Objects.requireNonNull(state.get().request());
    when(service.operation(
            request.requestId(), TARGET.connectionId(), TARGET.operatorId(), TARGET.communityId()))
        .thenReturn(Optional.of(operation(request, "COMPLETED", NOW)));
    clock.advance(Duration.ofMinutes(5));
    scheduler.refresh();
    assertNotNull(state.get().request());
    clock.advance(Duration.ofMinutes(5));
    var received = clock.instant().minusSeconds(30);
    when(service.communities(TARGET.operatorId(), TARGET.connectionId()))
        .thenReturn(List.of(snapshot(received, "new", true)));
    scheduler.refresh();
    assertNull(state.get().request());
    assertEquals(received.plusSeconds(12 * 3600), state.get().nextAttemptAt());
    verify(service, times(1)).submit(any());
  }

  @Test
  void partialFutureAndForeignSnapshotsCannotPostponeTheRefresh() {
    when(service.communities(TARGET.operatorId(), TARGET.connectionId()))
        .thenReturn(
            List.of(
                snapshot(NOW, "partial", false),
                snapshot(NOW.plusSeconds(1), "future", true),
                new EnergySharingCommunitySnapshot(
                    "foreign",
                    TARGET.operatorId(),
                    TARGET.connectionId(),
                    "other",
                    NOW,
                    true,
                    null,
                    null,
                    List.of(),
                    MAPPER.createObjectNode())));
    scheduler.refresh();
    verify(service).submit(any());
    assertEquals("", state.get().triggerRevision());
  }

  @Test
  void individualRevocationCannotRenewTheLastCompleteMasterDataConfirmation() {
    var old = NOW.minusSeconds(13 * 3600);
    var nativeParameters =
        MAPPER
            .createObjectNode()
            .put("masterDataReceivedAt", old.toString())
            .put("masterDataRevision", "original-master-data");
    var revoked =
        new EnergySharingCommunitySnapshot(
            TARGET.communityId(),
            TARGET.operatorId(),
            TARGET.connectionId(),
            "new-individual-revocation",
            NOW,
            true,
            null,
            null,
            List.of(),
            nativeParameters);
    when(service.communities(TARGET.operatorId(), TARGET.connectionId()))
        .thenReturn(List.of(revoked));
    scheduler.refresh();
    verify(service).submit(any());
    assertEquals("original-master-data", state.get().triggerRevision());
  }

  @Test
  void existingManualQueryAndLostLeasePreventANewScheduledQuery() {
    when(store.hasOutstandingQuery(TARGET)).thenReturn(true);
    scheduler.refresh();
    assertEquals("EXISTING_QUERY", state.get().detailCode());
    assertNull(state.get().request());
    when(store.hasOutstandingQuery(TARGET)).thenReturn(false);
    clock.advance(Duration.ofMinutes(5));
    doReturn(false).when(store).save(eq(TARGET), any(), any(), eq(false));
    scheduler.refresh();
    verify(service, never()).submit(any());
  }

  @Test
  void rejectedAndUnansweredOperationsStopWithoutIssuingReplacementCommands() {
    scheduler.refresh();
    var request = Objects.requireNonNull(state.get().request());
    when(service.operation(
            request.requestId(), TARGET.connectionId(), TARGET.operatorId(), TARGET.communityId()))
        .thenReturn(Optional.of(operation(request, "SUBMITTED", NOW)));
    clock.advance(Duration.ofHours(25));
    scheduler.refresh();
    assertEquals("NATIVE_RESPONSE_TIMEOUT", state.get().detailCode());
    verify(service, times(1)).submit(any());
  }

  @Test
  void blockedRequestOnlyReadsUntilALateCompletionAndNewNativeConfirmationBothArrive() {
    scheduler.refresh();
    var request = Objects.requireNonNull(state.get().request());
    when(service.operation(
            request.requestId(), TARGET.connectionId(), TARGET.operatorId(), TARGET.communityId()))
        .thenReturn(Optional.of(operation(request, "RECONCILIATION_REQUIRED", NOW)));
    clock.advance(Duration.ofMinutes(5));
    scheduler.refresh();
    assertEquals(clock.instant().plusSeconds(3600), state.get().nextAttemptAt());
    assertEquals("BLOCKED", state.get().phase());
    // A missing ledger entry or failed check must never turn a blocked intent into another send.
    when(service.operation(
            request.requestId(), TARGET.connectionId(), TARGET.operatorId(), TARGET.communityId()))
        .thenReturn(Optional.empty());
    clock.advance(Duration.ofHours(1));
    scheduler.refresh();
    assertEquals("BLOCKED", state.get().phase());
    assertEquals("OPERATION_NOT_FOUND", state.get().detailCode());
    when(service.operation(
            request.requestId(), TARGET.connectionId(), TARGET.operatorId(), TARGET.communityId()))
        .thenThrow(new IllegalStateException("temporary database read failure"));
    clock.advance(Duration.ofHours(1));
    scheduler.refresh();
    assertEquals("BLOCKED", state.get().phase());
    doReturn(Optional.of(operation(request, "COMPLETED", NOW)))
        .when(service)
        .operation(
            request.requestId(), TARGET.connectionId(), TARGET.operatorId(), TARGET.communityId());
    clock.advance(Duration.ofHours(1));
    scheduler.refresh();
    assertEquals("BLOCKED", state.get().phase());
    clock.advance(Duration.ofHours(1));
    Instant received = clock.instant().minusSeconds(60);
    when(service.communities(TARGET.operatorId(), TARGET.connectionId()))
        .thenReturn(List.of(snapshot(received, "late-native", true)));
    scheduler.refresh();
    assertEquals("IDLE", state.get().phase());
    assertNull(state.get().request());
    assertEquals(received.plusSeconds(12 * 3600), state.get().nextAttemptAt());
    verify(service, times(1)).submit(any());
  }

  @Test
  void targetConfigurationRejectsDuplicateOperatorCommunityAcrossConnections() {
    var duplicate =
        new EdaMasterDataRefreshConfiguration.Target(
            "other", TARGET.operatorId(), TARGET.communityId());
    var values = List.of(TARGET, duplicate);
    assertThrows(
        IllegalArgumentException.class, () -> new EdaMasterDataRefreshConfiguration(true, values));
    var blank = List.of(new EdaMasterDataRefreshConfiguration.Target("", "operator", "community"));
    assertThrows(
        IllegalArgumentException.class, () -> new EdaMasterDataRefreshConfiguration(true, blank));
  }

  private static EnergySharingCommunitySnapshot snapshot(
      Instant at, String revision, boolean complete) {
    return new EnergySharingCommunitySnapshot(
        TARGET.communityId(),
        TARGET.operatorId(),
        TARGET.connectionId(),
        revision,
        at,
        complete,
        null,
        null,
        List.of(),
        MAPPER
            .createObjectNode()
            .put("masterDataReceivedAt", at.toString())
            .put("masterDataRevision", revision));
  }

  private static EnergySharingOperation operation(
      EnergySharingRequest request, String status, Instant created) {
    return new EnergySharingOperation(
        request.requestId(),
        request.connectionId(),
        request.operatorId(),
        request.communityId(),
        request.operation(),
        status,
        created,
        created,
        "market",
        null,
        null,
        MAPPER.createObjectNode());
  }

  private static final class MutableClock extends Clock {
    private Instant current;

    private MutableClock(Instant now) {
      current = now;
    }

    void advance(Duration duration) {
      current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return Clock.fixed(current, zone);
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}
