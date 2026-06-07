/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.converter.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.EventStreamGapHandler;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.shared.functional.CheckedConsumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for CdcDispatcher's resilience to failures inside the poison-handling path.
 * <p>
 * The original implementation called {@code extractPoisonGaps(...)} inside the outer
 * {@code catch} block but outside any inner {@code try}. If gap extraction itself threw, the
 * exception propagated out of {@code tick()}, which — combined with
 * {@code ScheduledExecutorService.scheduleWithFixedDelay}'s "suppress further ticks on throw"
 * contract — silently killed the dispatcher.
 */
class CdcDispatcherPoisonGapFailureTest {

    private static final String SLOT = "test_slot";

    @Test
    void gap_extraction_failure_still_marks_row_POISON_and_does_not_kill_dispatcher() throws Exception {
        var inbox = mock(CdcInboxRepository.class);
        @SuppressWarnings("unchecked")
        HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> uowFactory = mock(HandleAwareUnitOfWorkFactory.class);
        var gapHandler = mock(EventStreamGapHandler.class);
        var plugin = mock(LogicalDecodingPlugin.class);
        var notifier = mock(CdcPoisonNotifier.class);

        // Invoke the uow consumer inline (no real transaction).
        doAnswer(inv -> {
            CheckedConsumer<HandleAwareUnitOfWork> consumer = inv.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(uowFactory).usingUnitOfWork(any(CheckedConsumer.class));

        // One poison row to dispatch.
        var row = new CdcInboxRepository.InboxRow(
                1L,
                "0/ABCDEF",
                "{\"not\":\"parseable\"}".getBytes()
        );
        when(inbox.fetchNextBatch(eq(SLOT), anyInt(), anyInt()))
                .thenReturn(List.of(row))
                .thenReturn(List.of());

        // Primary conversion fails with QUARANTINE_AND_CONTINUE → poison path runs.
        when(plugin.decode(any(byte[].class)))
                .thenThrow(new RuntimeException("simulated conversion failure"));

        // Gap extraction ALSO fails — this is the regression we're guarding.
        when(plugin.extractGaps(any(byte[].class)))
                .thenThrow(new RuntimeException("simulated gap extraction failure"));

        var dispatcher = new CdcDispatcher(
                inbox,
                uowFactory,
                gapHandler,
                plugin,
                Optional.of(notifier),
                events -> { /* no-op */ },
                SLOT,
                CdcProperties.CdcDispatcherProperties.defaults(),
                CdcProperties.CdcDeliveryMode.INBOX,
                ignoreAvailability(),
                Optional.empty()
        );

        // When — run one tick directly (no scheduler).
        dispatcher.tick();

        // Then — the row IS quarantined despite gap extraction failing.
        var reasonCaptor = ArgumentCaptor.forClass(String.class);
        verify(inbox, times(1)).markPoison(eq(SLOT), eq("0/ABCDEF"), reasonCaptor.capture());
        assertThat(reasonCaptor.getValue()).contains("simulated conversion failure");

        // Gap registration did NOT happen (extractor threw → empty gap list).
        verify(gapHandler, never()).registerPermanentGaps(any(), anyList(), anyString());
        verify(notifier, never()).onPoison(any(), anyList(), anyString());

        // Status counters reflect the failure.
        var status = dispatcher.getStatus();
        assertThat(status.conversionFailures()).isEqualTo(1L);
        assertThat(status.gapExtractionFailures()).isEqualTo(1L);
        assertThat(status.poisonRows()).isEqualTo(1L);
        assertThat(status.tickFailures()).isZero();
        assertThat(status.stopping()).isFalse();

        // And — a second tick (with empty batch) still runs without throwing, proving
        // the dispatcher is still alive.
        dispatcher.tick();
        assertThat(dispatcher.getStatus().ticks()).isEqualTo(2L);
    }

    @Test
    void unexpected_fetch_failure_is_caught_and_dispatcher_survives_for_next_tick() {
        var inbox = mock(CdcInboxRepository.class);
        @SuppressWarnings("unchecked")
        HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> uowFactory = mock(HandleAwareUnitOfWorkFactory.class);
        var gapHandler = mock(EventStreamGapHandler.class);
        var plugin = mock(LogicalDecodingPlugin.class);

        // First tick: fetch blows up with a transient DB-like error. Second tick: normal empty fetch.
        when(inbox.fetchNextBatch(eq(SLOT), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("simulated DB failure"))
                .thenReturn(List.of());

        var dispatcher = new CdcDispatcher(
                inbox,
                uowFactory,
                gapHandler,
                plugin,
                Optional.empty(),
                events -> { /* no-op */ },
                SLOT,
                CdcProperties.CdcDispatcherProperties.defaults(),
                CdcProperties.CdcDeliveryMode.INBOX,
                ignoreAvailability(),
                Optional.empty()
        );

        // tick() must NOT throw — the outer catch-all keeps the scheduler alive.
        dispatcher.tick();

        assertThat(dispatcher.getStatus().tickFailures()).isEqualTo(1L);
        assertThat(dispatcher.getStatus().stopping()).isFalse();

        // Second tick continues normally.
        dispatcher.tick();
        assertThat(dispatcher.getStatus().tickFailures()).isEqualTo(1L);
    }

    /**
     * Regression: dispatcher used to check availability at {@code start()} and bail permanently
     * if CDC wasn't already ACTIVE. Spring Lifecycle ordering doesn't guarantee the tailer
     * transitions availability to ACTIVE before dispatcher.start() runs — the perf-lab showed
     * 248k RECEIVED inbox rows with 0 dispatched because the dispatcher gave up at startup.
     * <p>
     * The check now lives in {@link CdcDispatcher#tick()}: ticks run regardless, they just
     * no-op when availability isn't ACTIVE. A later {@code availability.active(...)} call
     * flips the switch and subsequent ticks start processing.
     */
    @Test
    void tick_no_ops_when_availability_is_not_active_then_picks_up_after_activation() {
        var inbox = mock(CdcInboxRepository.class);
        @SuppressWarnings("unchecked")
        HandleAwareUnitOfWorkFactory<HandleAwareUnitOfWork> uowFactory = mock(HandleAwareUnitOfWorkFactory.class);
        var gapHandler = mock(EventStreamGapHandler.class);
        var plugin = mock(LogicalDecodingPlugin.class);

        var startingInactive = new CdcAvailability(); // INACTIVE by default

        when(inbox.fetchNextBatch(eq(SLOT), anyInt(), anyInt())).thenReturn(List.of());

        var dispatcher = new CdcDispatcher(
                inbox,
                uowFactory,
                gapHandler,
                plugin,
                Optional.empty(),
                events -> { /* no-op */ },
                SLOT,
                CdcProperties.CdcDispatcherProperties.defaults(),
                CdcProperties.CdcDeliveryMode.INBOX,
                startingInactive,
                Optional.empty()
        );

        // While INACTIVE: tick must not query the inbox (no wasted round-trips) and must not
        // fault or increment any failure counter.
        dispatcher.tick();
        dispatcher.tick();
        verify(inbox, never()).fetchNextBatch(any(), anyInt(), anyInt());
        assertThat(dispatcher.getStatus().ticks()).isZero();
        assertThat(dispatcher.getStatus().tickFailures()).isZero();

        // Simulate the tailer connecting after dispatcher startup — availability flips ACTIVE.
        startingInactive.active(SLOT);

        // Next tick must now attempt to fetch the inbox batch, proving the dispatcher self-heals.
        dispatcher.tick();
        verify(inbox, atLeastOnce()).fetchNextBatch(eq(SLOT), anyInt(), anyInt());
        assertThat(dispatcher.getStatus().ticks()).isEqualTo(1L);
        assertThat(dispatcher.getStatus().tickFailures()).isZero();
    }

    private static CdcAvailability ignoreAvailability() {
        var a = new CdcAvailability();
        a.active("test");
        return a;
    }
}
