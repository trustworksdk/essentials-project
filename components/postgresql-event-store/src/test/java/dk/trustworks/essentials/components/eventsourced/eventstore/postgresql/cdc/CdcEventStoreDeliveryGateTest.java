/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.TenantSerializer;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.EssentialsJSONEventSerializers;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.test_data.OrderId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.*;
import dk.trustworks.essentials.shared.functional.CheckedFunction;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Container-free tests of what the {@code DeliveryGate} of a {@link CdcEventStore} subscription tells the subscriber's
 * gap handler and the delegate's polls, on the polling leg (CDC is not ACTIVE): the delegate's poll is a sink the test
 * emits into, and the gap handler records every call.
 */
class CdcEventStoreDeliveryGateTest {
    private static final AggregateType ORDERS     = AggregateType.of("orders");
    private static final SubscriberId  SUBSCRIBER = SubscriberId.of("gate-test");

    private final List<Disposable>         subscriptions         = new CopyOnWriteArrayList<>();
    private final Sinks.Many<PersistedEvent> polled              = Sinks.many().replay().all();
    /** What the gate acknowledged to the delegate's poll */
    private final List<Long>               delegateAcknowledged = new CopyOnWriteArrayList<>();
    private final Map<Long, PersistedEvent> events              = new HashMap<>();

    private RecordingGapHandler                                              gapHandler;
    private EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork>      uowFactory;
    private ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate;
    private CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration> cdcEventStore;

    @AfterEach
    void cleanup() {
        subscriptions.forEach(Disposable::dispose);
    }

    /**
     * The event that opens a gap came from a poll (or the bus), not from a query of the gap handler's: it is recorded
     * as a reconciliation that asked for no transient gap, so the gap handler promotes nothing on its account
     */
    @Test
    void recording_the_gap_an_event_opens_claims_no_query_of_transient_gaps() {
        setup(Duration.ofSeconds(10));
        var received = subscribe();

        poll(1, 3);

        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L)));
        assertThat(gapHandler.reconciliations).hasSize(1);
        var reconciliation = gapHandler.reconciliations.getFirst();
        assertThat(reconciliation.range()).isEqualTo(LongRange.between(2, 3));
        assertThat(reconciliation.events()).containsExactly(3L);
        assertThat(reconciliation.transientGapsIncludedInQuery()).isEmpty();
        assertThat(gapHandler.findTransientGapsCalls).as("nothing was queried").isZero();
    }

    /**
     * Once the tracker gives up on a gap the gap handler is told, so the next subscription does not wait for it again.
     * The event for it that commits late is dropped - and acknowledged to the delegate's poll, which handed it on as a
     * gap fill and would otherwise wait for its acknowledgement for as long as it lives
     */
    @Test
    void a_gap_the_tracker_gives_up_on_is_given_up_with_the_gap_handler_and_its_late_event_is_dropped_and_acknowledged_to_the_delegate() {
        setup(Duration.ofMillis(300));
        var received = subscribe();
        poll(1, 3);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L)));
        assertThat(gapHandler.givenUp).isEmpty();

        await().pollDelay(Duration.ofMillis(400)).atMost(Duration.ofSeconds(5)).until(() -> true);
        poll(4);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 4L)));
        assertThat(gapHandler.givenUp).containsExactly(List.of(2L));

        poll(2);
        await().atMost(Duration.ofSeconds(5)).until(() -> delegateAcknowledged.contains(2L));
        assertThat(received).containsExactly(1L, 3L, 4L);
        assertThat(gapHandler.resolved).isEmpty();
    }

    /**
     * Recording the give-up fails: the orders go back to the tracker, and the next event delivered records them
     */
    @Test
    void a_give_up_the_gap_handler_failed_to_record_is_recorded_with_the_next_event_delivered() {
        setup(Duration.ofMillis(300));
        var received = subscribe();
        poll(1, 3);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L)));

        gapHandler.failGivingUp = true;
        await().pollDelay(Duration.ofMillis(400)).atMost(Duration.ofSeconds(5)).until(() -> true);
        poll(4);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 4L)));
        assertThat(gapHandler.givenUp).isEmpty();

        gapHandler.failGivingUp = false;
        poll(5);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 4L, 5L)));
        assertThat(gapHandler.givenUp).containsExactly(List.of(2L));
    }

    /**
     * Nothing is delivered after the tracker's timeout passed, and the subscription is stopped: the give-up is recorded
     * as it ends, rather than left to a later subscription to wait for again
     */
    @Test
    void a_gap_given_up_on_while_idle_is_given_up_with_the_gap_handler_when_the_subscription_is_stopped() {
        setup(Duration.ofMillis(300));
        var received = subscribe();
        poll(1, 3);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L)));
        await().pollDelay(Duration.ofMillis(400)).atMost(Duration.ofSeconds(5)).until(() -> true);
        assertThat(gapHandler.givenUp).isEmpty();

        subscriptions.forEach(Disposable::dispose);

        assertThat(gapHandler.givenUp).containsExactly(List.of(2L));
    }

    /**
     * Stopped on a thread with a unit of work of its own, the subscription does not write the give-up in it
     */
    @Test
    void a_subscription_stopped_inside_a_unit_of_work_does_not_record_the_give_up_in_it() {
        setup(Duration.ofMillis(300));
        var received = subscribe();
        poll(1, 3);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L)));
        await().pollDelay(Duration.ofMillis(400)).atMost(Duration.ofSeconds(5)).until(() -> true);

        doReturn(Optional.of(mock(EventStoreUnitOfWork.class))).when(uowFactory).getCurrentUnitOfWork();
        subscriptions.forEach(Disposable::dispose);

        assertThat(gapHandler.givenUp).isEmpty();
    }

    /**
     * A subscriber that does not acknowledge has a gap fill's gap resolved once its {@code onNext} returned. Inside a
     * unit of work that is current on the delivering thread, a failure to resolve it is thrown by the gap fill protocol -
     * and must not leave {@code onNext}, which would end the subscription; the delegate's poll is acknowledged
     * regardless
     */
    @Test
    void a_failure_to_resolve_a_gap_fill_s_gap_once_handed_on_neither_ends_the_subscription_nor_strands_the_delegate() {
        setup(Duration.ofSeconds(10));
        doReturn(Optional.of(mock(EventStoreUnitOfWork.class))).when(uowFactory).getCurrentUnitOfWork();
        gapHandler.failResolving = true;
        var received = subscribe();

        poll(1, 3, 2, 4);

        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 2L, 4L)));
        assertThat(delegateAcknowledged).contains(2L);
        assertThat(gapHandler.resolved).isEmpty();
    }

    /**
     * The subscriber's acknowledgement fails to resolve a gap fill's gap in its unit of work: the failure reaches the
     * subscriber, and the delegate's poll is acknowledged regardless
     */
    @Test
    void a_failure_to_resolve_an_acknowledged_gap_fill_s_gap_still_acknowledges_it_to_the_delegate() {
        setup(Duration.ofSeconds(10));
        var acknowledgement = SubscriberAcknowledgement.create();
        var received        = subscribe(Optional.of(acknowledgement));
        poll(1, 3, 2);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 2L)));

        doReturn(Optional.of(mock(EventStoreUnitOfWork.class))).when(uowFactory).getCurrentUnitOfWork();
        gapHandler.failResolving = true;
        assertThatThrownBy(() -> acknowledgement.acknowledge(event(2))).isInstanceOf(IllegalStateException.class);

        assertThat(delegateAcknowledged).contains(2L);
        assertThat(gapHandler.resolved).isEmpty();
    }

    /**
     * Subscribing the flux again once the previous subscribe ended - {@code repeat()}, {@code retry()} - disposes the
     * previous subscribe's registration with the acknowledgement: left registered, every acknowledgement also reached the
     * gate of the subscribe that ended, and through it the delegate's poll of that subscribe
     */
    @Test
    void subscribing_the_flux_again_after_the_previous_subscribe_ended_disposes_its_registration_with_the_acknowledgement() {
        setup(Duration.ofSeconds(10));
        var acknowledgement = SubscriberAcknowledgement.create();
        poll(1);
        var received = new CopyOnWriteArrayList<Long>();
        Function<String, EventStorePollingOptimizer> noOptimizer = name -> null;
        subscriptions.add(cdcEventStore.pollEvents(ORDERS, 1L, Optional.of(10), Optional.of(Duration.ofMillis(50)), Optional.empty(), Optional.of(SUBSCRIBER), Optional.of(noOptimizer), acknowledgement)
                                       .take(1)
                                       .repeat(1)
                                       .subscribe(event -> received.add(event.globalEventOrder().longValue())));
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 1L)));

        acknowledgement.acknowledge(event(1));

        assertThat(delegateAcknowledged).containsExactly(1L);
    }

    /**
     * A gap fill the gate let through and the subscriber has not acknowledged yet keeps its gap: a poll that reads it
     * again is dropped without acknowledging it to the delegate. The subscriber's acknowledgement resolves the gap and
     * reaches the delegate
     */
    @Test
    void a_dropped_gap_fill_the_subscriber_is_not_done_with_is_acknowledged_to_the_delegate_only_once_the_subscriber_acknowledges_it() {
        setup(Duration.ofSeconds(10));
        var acknowledgement = SubscriberAcknowledgement.create();
        var received        = subscribe(Optional.of(acknowledgement));
        poll(1, 3, 2);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 2L)));

        // Read again while its gap is open
        poll(2);
        poll(4);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 2L, 4L)));
        assertThat(delegateAcknowledged).doesNotContain(2L);
        assertThat(gapHandler.resolved).isEmpty();

        acknowledgement.acknowledge(event(2));
        assertThat(delegateAcknowledged).contains(2L);
        assertThat(gapHandler.resolved).containsExactly(List.of(2L));
    }

    /**
     * A tenant-filtered subscription keeps the events whose tenant serializes to the wanted tenant's column value under
     * the aggregate type's {@link TenantSerializer} - the predicate the polling path's SQL applies - and those without a
     * tenant; not the tenants' {@code toString()}
     */
    @Test
    void the_tenant_filter_compares_tenants_as_the_aggregate_type_s_tenant_serializer_writes_them() {
        setup(Duration.ofSeconds(10), Optional.of(caseInsensitiveTenantConfiguration()));
        var received = subscribeForTenant(TenantId.of("ACME"));

        polled.tryEmitNext(event(1, Optional.of(TenantId.of("acme")))).orThrow();
        polled.tryEmitNext(event(2, Optional.of(TenantId.of("other")))).orThrow();
        polled.tryEmitNext(event(3, Optional.empty())).orThrow();
        polled.tryEmitNext(event(4, Optional.of(TenantId.of("ACME")))).orThrow();

        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 4L)));
    }

    /**
     * The aggregate type is configured only after the subscription was built: the tenant filter compares the tenants'
     * {@code toString()} until then, and the configured {@link TenantSerializer} from then on - not for the
     * subscription's whole lifetime
     */
    @Test
    void the_tenant_filter_follows_a_configuration_added_after_the_subscription_started() {
        setup(Duration.ofSeconds(10));
        var received = subscribeForTenant(TenantId.of("ACME"));

        polled.tryEmitNext(event(1, Optional.of(TenantId.of("ACME")))).orThrow();
        polled.tryEmitNext(event(2, Optional.of(TenantId.of("acme")))).orThrow();
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L)));

        when(delegate.findAggregateEventStreamConfiguration(ORDERS)).thenReturn(Optional.of(caseInsensitiveTenantConfiguration()));
        polled.tryEmitNext(event(3, Optional.of(TenantId.of("acme")))).orThrow();
        polled.tryEmitNext(event(4, Optional.of(TenantId.of("other")))).orThrow();
        polled.tryEmitNext(event(5, Optional.of(TenantId.of("ACME")))).orThrow();

        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 5L)));
    }

    /**
     * A resume point save whose caller has a unit of work current must not record the gaps kept in memory in it: the gate
     * forgets them once recorded, so a rollback of the caller's unit of work would lose them, and the next save - past
     * the gap - would record nothing that makes a restart wait for the gap's event
     */
    @Test
    void gaps_kept_in_memory_stay_recorded_when_the_unit_of_work_current_on_the_saving_thread_rolls_back() throws Exception {
        setupDeferringGapRecording();
        var received = subscribe();
        poll(1, 3);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L)));
        assertThat(gapHandler.transientGapsAdded).as("kept in memory").isEmpty();

        // A save on a thread whose own unit of work then rolls back
        var callerWithUnitOfWork = Thread.ofPlatform().unstarted(() -> cdcEventStore.beforeResumePointSave(SUBSCRIBER, ORDERS).close());
        doAnswer(inv -> Thread.currentThread().equals(callerWithUnitOfWork) ? Optional.of(mock(EventStoreUnitOfWork.class)) : Optional.empty())
                .when(uowFactory).getCurrentUnitOfWork();
        callerWithUnitOfWork.start();
        callerWithUnitOfWork.join(Duration.ofSeconds(5));
        assertThat(callerWithUnitOfWork.isAlive()).isFalse();
        gapHandler.transientGapsAdded.removeIf(added -> callerWithUnitOfWork.equals(added.thread()));

        // A later save with no unit of work current
        cdcEventStore.beforeResumePointSave(SUBSCRIBER, ORDERS).close();

        assertThat(gapHandler.transientGapsAdded).extracting(TransientGapsAdded::gaps).containsExactly(LongRange.only(2));
    }

    /**
     * A recording on a virtual thread that outlasts the wait - blocked, say, by a lock the saving thread's unit of work
     * holds - keeps the gate lock until it commits. The saving thread, still in that unit of work, must not then wait for
     * the gate lock without a limit: not on a second save, not acknowledging a fill, not ending the subscription
     */
    @Test
    void a_thread_whose_recording_outlasted_the_wait_does_not_wait_for_the_gate_lock_without_a_limit() throws Exception {
        setupDeferringGapRecording();
        cdcEventStore.deferredGapsRecordingWait(Duration.ofMillis(200));
        var acknowledgement = SubscriberAcknowledgement.create();
        var received        = subscribe(Optional.of(acknowledgement));
        // 2 fills a gap kept in memory and is not acknowledged yet; 5 opens a gap kept in memory
        poll(1, 3, 2, 5);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 2L, 5L)));
        var recordingMayEnd = new CountDownLatch(1);
        gapHandler.addingTransientGapsMayEnd = recordingMayEnd;
        try {
            var outcomes = new CopyOnWriteArrayList<String>();
            var callerWithUnitOfWork = Thread.ofPlatform().daemon().unstarted(() -> {
                outcomes.add(outcomeOf(() -> cdcEventStore.beforeResumePointSave(SUBSCRIBER, ORDERS).close()));
                outcomes.add(outcomeOf(() -> cdcEventStore.beforeResumePointSave(SUBSCRIBER, ORDERS).close()));
                outcomes.add(outcomeOf(() -> acknowledgement.acknowledge(event(2))));
                // As awaitProcessed asks it: an order the gate holds nothing for is still awaited, as far as it can tell
                outcomes.add(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(7)) ? "awaited" : "not awaited");
                outcomes.add(outcomeOf(() -> subscriptions.forEach(Disposable::dispose)));
            });
            doAnswer(inv -> Thread.currentThread().equals(callerWithUnitOfWork) ? Optional.of(mock(EventStoreUnitOfWork.class)) : Optional.empty())
                    .when(uowFactory).getCurrentUnitOfWork();
            callerWithUnitOfWork.start();
            callerWithUnitOfWork.join(Duration.ofSeconds(5));

            assertThat(callerWithUnitOfWork.isAlive()).as("waits for the gate lock without a limit").isFalse();
            // The acknowledgement returns rather than fail the subscriber's handler, and with it the error policy
            assertThat(outcomes).containsExactly("IllegalStateException", "IllegalStateException", "returned", "awaited", "returned");
            assertThat(delegateAcknowledged).as("acknowledged to the delegate although the gate could not tell").contains(2L);
        } finally {
            recordingMayEnd.countDown();
        }
        // The recording goes on, and records the gaps once what blocked it is gone
        await().atMost(Duration.ofSeconds(5))
               .untilAsserted(() -> assertThat(gapHandler.transientGapsAdded).extracting(TransientGapsAdded::gaps).contains(LongRange.only(2), LongRange.only(4)));
        // Once only, by the recording that outlasted the wait; the next save records nothing again
        cdcEventStore.beforeResumePointSave(SUBSCRIBER, ORDERS).close();
        assertThat(gapHandler.transientGapsAdded).extracting(TransientGapsAdded::gaps).containsExactly(LongRange.only(2), LongRange.only(4));

        // The saves whose wait ran out closed their window: a new subscription keeps the gaps it opens in memory again,
        // rather than recording them at once
        var reconciliationsBefore = gapHandler.reconciliations.size();
        var resubscribed          = subscribe();
        await().atMost(Duration.ofSeconds(5)).until(() -> resubscribed.equals(List.of(1L, 3L, 2L, 5L)));
        assertThat(gapHandler.reconciliations).as("opened gaps recorded at once").hasSize(reconciliationsBefore);
        assertThat(gapHandler.transientGapsAdded).hasSize(2);
    }

    /**
     * An acknowledgement that could not take the gate lock within the wait leaves its fill to the recording that holds
     * the lock: once that recording committed, the fill's recorded gap is resolved, and the event is no longer awaited
     */
    @Test
    void an_acknowledgement_that_timed_out_behind_a_recording_has_its_fill_s_gap_resolved_once_the_recording_commits() throws Exception {
        setupDeferringGapRecording();
        cdcEventStore.deferredGapsRecordingWait(Duration.ofMillis(200));
        var acknowledgement = SubscriberAcknowledgement.create();
        var received        = subscribe(Optional.of(acknowledgement));
        // 2 fills a gap kept in memory and is not acknowledged yet; 5 opens a gap kept in memory
        poll(1, 3, 2, 5);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 2L, 5L)));
        gapHandler.reportsRecordedTransientGaps = true;
        var recordingMayEnd = new CountDownLatch(1);
        gapHandler.addingTransientGapsMayEnd = recordingMayEnd;
        try {
            var outcomes = new CopyOnWriteArrayList<String>();
            var callerWithUnitOfWork = Thread.ofPlatform().daemon().unstarted(() -> {
                outcomes.add(outcomeOf(() -> cdcEventStore.beforeResumePointSave(SUBSCRIBER, ORDERS).close()));
                outcomes.add(outcomeOf(() -> acknowledgement.acknowledge(event(2))));
            });
            doAnswer(inv -> Thread.currentThread().equals(callerWithUnitOfWork) ? Optional.of(mock(EventStoreUnitOfWork.class)) : Optional.empty())
                    .when(uowFactory).getCurrentUnitOfWork();
            callerWithUnitOfWork.start();
            callerWithUnitOfWork.join(Duration.ofSeconds(5));

            assertThat(callerWithUnitOfWork.isAlive()).isFalse();
            assertThat(outcomes).containsExactly("IllegalStateException", "returned");
            assertThat(gapHandler.resolved).as("left to the recording").isEmpty();
        } finally {
            recordingMayEnd.countDown();
        }

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(gapHandler.resolved).containsExactly(List.of(2L)));
        assertThat(gapHandler.transientGapsAdded).extracting(TransientGapsAdded::gaps).containsExactly(LongRange.only(2), LongRange.only(4));
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(2))).as("the acknowledged fill").isFalse();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(4))).as("the gap still open").isTrue();
    }

    /**
     * While a recording that outlasted the wait holds the gate lock, the delivering thread waits for it no longer than
     * the wait: a duplicate of a held fill is not acknowledged to the delegate's poll, and a gap an event opens is kept
     * without the lock - not recorded at once, which would wait for the gap handler the recording is blocked in - and
     * recorded by the next save
     */
    @Test
    void delivering_does_not_wait_for_the_gate_lock_without_a_limit_while_a_recording_holds_it() throws Exception {
        setupDeferringGapRecording();
        cdcEventStore.deferredGapsRecordingWait(Duration.ofMillis(200));
        var acknowledgement = SubscriberAcknowledgement.create();
        var received        = subscribe(Optional.of(acknowledgement));
        poll(1, 3, 2, 5);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 2L, 5L)));
        var recordingMayEnd = new CountDownLatch(1);
        gapHandler.addingTransientGapsMayEnd = recordingMayEnd;
        try {
            var outcome              = new CopyOnWriteArrayList<String>();
            var callerWithUnitOfWork = Thread.ofPlatform().daemon().unstarted(() -> outcome.add(outcomeOf(() -> cdcEventStore.beforeResumePointSave(SUBSCRIBER, ORDERS).close())));
            doAnswer(inv -> Thread.currentThread().equals(callerWithUnitOfWork) ? Optional.of(mock(EventStoreUnitOfWork.class)) : Optional.empty())
                    .when(uowFactory).getCurrentUnitOfWork();
            callerWithUnitOfWork.start();
            callerWithUnitOfWork.join(Duration.ofSeconds(5));
            assertThat(outcome).containsExactly("IllegalStateException");
            var reconciliationsBefore = gapHandler.reconciliations.size();

            // A duplicate of the held fill 2, then 8, which opens the gap 6..7
            poll(2, 8);

            await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 2L, 5L, 8L)));
            assertThat(delegateAcknowledged).as("a held fill, as far as the gate can tell").doesNotContain(2L);
            assertThat(gapHandler.reconciliations).as("kept, not recorded at once").hasSize(reconciliationsBefore);
        } finally {
            recordingMayEnd.countDown();
        }

        await().atMost(Duration.ofSeconds(5))
               .untilAsserted(() -> assertThat(gapHandler.transientGapsAdded).extracting(TransientGapsAdded::gaps).contains(LongRange.only(2), LongRange.only(4)));
        cdcEventStore.beforeResumePointSave(SUBSCRIBER, ORDERS).close();
        assertThat(gapHandler.transientGapsAdded).extracting(TransientGapsAdded::gaps).contains(LongRange.between(6, 7));
    }

    /**
     * A save waits for its gates' recordings within one wait, not one wait per gate: a gate's recording gets only what
     * the gates before it left of the wait, so a save with a unit of work current never stalls once per gate
     */
    @Test
    void a_save_waits_for_all_of_its_gates_recordings_within_one_wait() throws Exception {
        setupDeferringGapRecording();
        cdcEventStore.deferredGapsRecordingWait(Duration.ofSeconds(1));
        // Two running subscriptions of the subscriber, each keeping gap 2 in memory
        var first  = subscribe();
        var second = subscribe();
        poll(1, 3);
        await().atMost(Duration.ofSeconds(5)).until(() -> first.equals(List.of(1L, 3L)) && second.equals(List.of(1L, 3L)));
        // Each gate's recording takes 600 ms: the first ends within the wait, the second not within what is left of it
        gapHandler.addingTransientGapsTakes = Duration.ofMillis(600);
        var outcome              = new CopyOnWriteArrayList<String>();
        var callerWithUnitOfWork = Thread.ofPlatform().daemon().unstarted(() -> outcome.add(outcomeOf(() -> cdcEventStore.beforeResumePointSave(SUBSCRIBER, ORDERS).close())));
        doAnswer(inv -> Thread.currentThread().equals(callerWithUnitOfWork) ? Optional.of(mock(EventStoreUnitOfWork.class)) : Optional.empty())
                .when(uowFactory).getCurrentUnitOfWork();
        callerWithUnitOfWork.start();
        callerWithUnitOfWork.join(Duration.ofSeconds(5));

        assertThat(outcome).as("the second gate's recording was given a wait of its own").containsExactly("IllegalStateException");
        // The second recording goes on, and records its gaps once it ends
        await().atMost(Duration.ofSeconds(5))
               .untilAsserted(() -> assertThat(gapHandler.transientGapsAdded).extracting(TransientGapsAdded::gaps).containsExactly(LongRange.only(2), LongRange.only(2)));
    }

    /**
     * {@code isAwaitedAsGap} asks every running gate of the subscriber for a held fill within one wait, not one wait per
     * gate: a gate whose lock frees within the wait leaves the next gate only what is left of it. Here the first gate's
     * lock frees part-way through the wait and the second gate's lock is then held past its end, so the order is
     * answered as still awaited once the one wait runs out - rather than after a second wait, as not awaited
     */
    @Test
    void is_awaited_as_gap_asks_all_running_gates_for_a_held_fill_within_one_wait() throws Exception {
        var secondGapHandler = new RecordingGapHandler(Duration.ofSeconds(10));
        setupDeferringGapRecording(List.of(secondGapHandler));
        cdcEventStore.deferredGapsRecordingWait(Duration.ofMillis(200));
        // Two running subscriptions of the subscriber, each keeping gap 2 in memory, each with a gap handler of its own
        var first  = subscribe();
        var second = subscribe();
        poll(1, 3);
        await().atMost(Duration.ofSeconds(5)).until(() -> first.equals(List.of(1L, 3L)) && second.equals(List.of(1L, 3L)));
        var handlers  = List.of(gapHandler, secondGapHandler);
        var mayEnd       = new CountDownLatch(1);
        var secondMayEnd = new CountDownLatch(1);
        handlers.forEach(handler -> handler.addingTransientGapsMayEnd = mayEnd);
        var uowThreads = new CopyOnWriteArrayList<Thread>();
        doAnswer(inv -> uowThreads.contains(Thread.currentThread()) ? Optional.of(mock(EventStoreUnitOfWork.class)) : Optional.empty())
                .when(uowFactory).getCurrentUnitOfWork();
        try {
            // A save stops at the gate asked first, whose recording blocks: its gap handler tells which gate that is
            assertThat(saveWithAUnitOfWork(uowThreads)).isEqualTo("IllegalStateException");
            var askedFirst  = handlers.stream().filter(handler -> handler.addingTransientGapsCalls.get() == 1).findFirst().orElseThrow();
            var askedSecond = askedFirst.equals(gapHandler) ? secondGapHandler : gapHandler;
            // The first gate's recording ends; the next save passes that gate, now empty, and the second gate's recording
            // blocks, holding its gate lock until the test ends
            askedSecond.addingTransientGapsMayEnd = secondMayEnd;
            mayEnd.countDown();
            await().atMost(Duration.ofSeconds(5)).until(() -> askedFirst.transientGapsAdded.size() == 1);
            assertThat(saveWithAUnitOfWork(uowThreads)).isEqualTo("IllegalStateException");
            await().atMost(Duration.ofSeconds(5)).until(() -> askedSecond.addingTransientGapsCalls.get() == 1);
            // 5 opens the gap 4 in the first gate; the second keeps it without its lock
            poll(5);
            await().atMost(Duration.ofSeconds(5)).until(() -> first.size() == 3 && second.size() == 3);
            // The first gate's next recording holds its gate lock 700 ms, past which the second gate's stays held
            var wait = Duration.ofSeconds(1);
            cdcEventStore.deferredGapsRecordingWait(wait);
            askedFirst.addingTransientGapsMayEnd = null;
            askedFirst.addingTransientGapsTakes = Duration.ofMillis(700);
            var saving = Thread.ofPlatform().daemon().unstarted(() -> cdcEventStore.beforeResumePointSave(SUBSCRIBER, ORDERS).close());
            uowThreads.add(saving);
            saving.start();
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(1)).until(() -> askedFirst.addingTransientGapsCalls.get() == 2);

            var outcome = new CopyOnWriteArrayList<String>();
            var took    = new CopyOnWriteArrayList<Duration>();
            var asking  = Thread.ofPlatform().daemon().start(() -> {
                var started = System.nanoTime();
                // As awaitProcessed asks it: an order no gate holds a fill for
                outcome.add(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(7)) ? "awaited" : "not awaited");
                took.add(Duration.ofNanos(System.nanoTime() - started));
            });
            asking.join(Duration.ofSeconds(5));
            saving.join(Duration.ofSeconds(5));

            // The first gate's lock freed after 700 ms; the second gate had what was left of the one wait, not a wait of its own
            assertThat(outcome).as("the second gate's lock was still held").containsExactly("awaited");
            assertThat(took.getFirst()).as("one wait for all gates, not one per gate").isLessThan(Duration.ofMillis(1_400));
        } finally {
            mayEnd.countDown();
            secondMayEnd.countDown();
        }
    }

    /**
     * @return the outcome of a resume point save on a new thread with a unit of work current
     */
    private String saveWithAUnitOfWork(List<Thread> uowThreads) throws InterruptedException {
        var outcome = new CopyOnWriteArrayList<String>();
        var saving  = Thread.ofPlatform().daemon().unstarted(() -> outcome.add(outcomeOf(() -> cdcEventStore.beforeResumePointSave(SUBSCRIBER, ORDERS).close())));
        uowThreads.add(saving);
        saving.start();
        saving.join(Duration.ofSeconds(5));
        return outcome.isEmpty() ? "still running" : outcome.getFirst();
    }

    /**
     * An acknowledgement on a thread whose interrupt flag is set - a handler's backoff the polling/CDC source switch
     * interrupted - still takes the gate lock when it is free
     */
    @Test
    void an_acknowledgement_on_an_interrupted_thread_takes_a_free_gate_lock() throws Exception {
        setupDeferringGapRecording();
        var acknowledgement = SubscriberAcknowledgement.create();
        var received        = subscribe(Optional.of(acknowledgement));
        // 2 fills a gap kept in memory and is not acknowledged yet
        poll(1, 3, 2);
        await().atMost(Duration.ofSeconds(5)).until(() -> received.equals(List.of(1L, 3L, 2L)));
        var outcome = new CopyOnWriteArrayList<String>();
        var interrupted = Thread.ofPlatform().daemon().unstarted(() -> {
            Thread.currentThread().interrupt();
            outcome.add(outcomeOf(() -> acknowledgement.acknowledge(event(2))));
        });
        interrupted.start();
        interrupted.join(Duration.ofSeconds(5));

        assertThat(outcome).containsExactly("returned");
        // Done with before any save: nothing is left to record
        cdcEventStore.beforeResumePointSave(SUBSCRIBER, ORDERS).close();
        assertThat(gapHandler.transientGapsAdded).isEmpty();
    }

    private static String outcomeOf(Runnable action) {
        try {
            action.run();
            return "returned";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    @Test
    void a_recording_on_a_virtual_thread_that_does_not_end_is_waited_for_no_longer_than_the_wait() throws Exception {
        var mayEnd = new CountDownLatch(1);
        try {
            var started = System.nanoTime();
            assertThatThrownBy(() -> CdcEventStore.runOnAVirtualThreadWaitingAtMost("blocked-recording", Duration.ofMillis(100), () -> {
                try {
                    mayEnd.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })).isInstanceOf(TimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        } finally {
            mayEnd.countDown();
        }
    }

    @Test
    void a_recording_on_a_virtual_thread_that_fails_after_the_wait_ran_out_is_logged_at_warn() throws Exception {
        var warnings = new ListAppender<ILoggingEvent>();
        var logger   = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CdcEventStore.class);
        warnings.start();
        logger.addAppender(warnings);
        var mayFail = new CountDownLatch(1);
        var failure = new IllegalStateException("Recording failed late");
        try {
            assertThatThrownBy(() -> CdcEventStore.runOnAVirtualThreadWaitingAtMost("late-failing-recording", Duration.ofMillis(100), () -> {
                try {
                    mayFail.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw failure;
            })).isInstanceOf(TimeoutException.class);
            mayFail.countDown();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(lateFailureWarnings(warnings)).hasSize(1));
            var warning = lateFailureWarnings(warnings).getFirst();
            assertThat(warning.getFormattedMessage()).contains("late-failing-recording");
            assertThat(warning.getThrowableProxy().getMessage()).isEqualTo(failure.getMessage());
        } finally {
            mayFail.countDown();
            logger.detachAppender(warnings);
        }
    }

    @Test
    void a_recording_on_a_virtual_thread_that_fails_after_the_waiter_was_interrupted_is_logged_at_warn() throws Exception {
        var warnings = new ListAppender<ILoggingEvent>();
        var logger   = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CdcEventStore.class);
        warnings.start();
        logger.addAppender(warnings);
        var mayFail = new CountDownLatch(1);
        var failure = new IllegalStateException("Recording failed after an interrupt");
        try {
            // Interrupted before it waits, the waiter's join throws at once, while the recording goes on
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> CdcEventStore.runOnAVirtualThreadWaitingAtMost("interrupted-recording", Duration.ofSeconds(5), () -> {
                try {
                    mayFail.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw failure;
            })).isInstanceOf(InterruptedException.class);
            mayFail.countDown();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(lateFailureWarnings(warnings)).hasSize(1));
            var warning = lateFailureWarnings(warnings).getFirst();
            assertThat(warning.getFormattedMessage()).contains("interrupted-recording");
            assertThat(warning.getThrowableProxy().getMessage()).isEqualTo(failure.getMessage());
        } finally {
            Thread.interrupted();
            mayFail.countDown();
            logger.detachAppender(warnings);
        }
    }

    @Test
    void a_recording_on_a_virtual_thread_that_fails_in_time_is_not_logged_as_a_late_failure() {
        var warnings = new ListAppender<ILoggingEvent>();
        var logger   = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CdcEventStore.class);
        warnings.start();
        logger.addAppender(warnings);
        try {
            assertThatThrownBy(() -> CdcEventStore.runOnAVirtualThreadWaitingAtMost("failing-in-time-recording", Duration.ofSeconds(5), () -> {
                throw new IllegalStateException("Recording failed");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(lateFailureWarnings(warnings)).isEmpty();
        } finally {
            logger.detachAppender(warnings);
        }
    }

    private static List<ILoggingEvent> lateFailureWarnings(ListAppender<ILoggingEvent> appender) {
        List<ILoggingEvent> logged;
        synchronized (appender) { // the appender appends under its own monitor, from the virtual thread
            logged = List.copyOf(appender.list);
        }
        return logged.stream()
                     .filter(event -> event.getLevel() == Level.WARN)
                     .filter(event -> event.getFormattedMessage().contains("no caller sees this failure"))
                     .toList();
    }

    @Test
    void a_recording_on_a_virtual_thread_that_fails_in_time_throws_its_failure() {
        var failure = new IllegalStateException("Recording failed");
        assertThatThrownBy(() -> CdcEventStore.runOnAVirtualThreadWaitingAtMost("failing-recording", Duration.ofSeconds(5), () -> {
            throw failure;
        })).isSameAs(failure);
    }

    // ------------------------------------------------------------------------------------------------------------

    /**
     * As {@link #setup(Duration)}, with the gap handler a {@link PostgresqlEventStreamGapHandler} - the one that can record
     * a gap without its event - and {@link #SUBSCRIBER} opted in to keeping narrow gaps in memory until a resume point save
     */
    @SuppressWarnings("unchecked")
    private void setupDeferringGapRecording() {
        setupDeferringGapRecording(List.of());
    }

    /**
     * As {@link #setupDeferringGapRecording()}, the first subscription given {@link #gapHandler} and each later one the
     * next of {@code laterSubscriptionsGapHandlers}, so their recordings do not share a gap handler's monitor
     */
    @SuppressWarnings("unchecked")
    private void setupDeferringGapRecording(List<RecordingGapHandler> laterSubscriptionsGapHandlers) {
        setup(Duration.ofSeconds(10));
        PostgresqlEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration> gapHandlers = mock(PostgresqlEventStreamGapHandler.class);
        when(gapHandlers.gapHandlerFor(any())).thenReturn(gapHandler, laterSubscriptionsGapHandlers.toArray(RecordingGapHandler[]::new));
        cdcEventStore = new CdcEventStore<>(delegate,
                                            uowFactory,
                                            gapHandlers,
                                            new CdcEventBus(),
                                            new CdcProperties(),
                                            new CdcAvailability());
        cdcEventStore.deferGapRecordingUntilResumePointSave(SUBSCRIBER, ORDERS);
    }

    /**
     * Writes a tenant's column value in lower case, so "ACME" and "acme" are the same tenant
     */
    private static SeparateTablePerAggregateEventStreamConfiguration caseInsensitiveTenantConfiguration() {
        var caseInsensitive = new TenantSerializer<TenantId>() {
            @Override
            public Class<?> tenantType() {
                return TenantId.class;
            }

            @Override
            public String serialize(TenantId tenant) {
                return tenant == null ? null : tenant.toString().toLowerCase(Locale.ROOT);
            }

            @Override
            public Optional<TenantId> deserialize(String tenant) {
                return Optional.ofNullable(tenant).map(TenantId::of);
            }
        };
        return SeparateTablePerAggregateTypeEventStreamConfigurationFactory.standardConfiguration(EssentialsJSONEventSerializers.create(),
                                                                                                  IdentifierColumnType.TEXT,
                                                                                                  JSONColumnType.JSONB,
                                                                                                  caseInsensitive)
                                                                           .createEventStreamConfigurationFor(ORDERS, OrderId.class);
    }

    private List<Long> subscribeForTenant(Tenant tenant) {
        var received = new CopyOnWriteArrayList<Long>();
        Function<String, EventStorePollingOptimizer> noOptimizer = name -> null;
        subscriptions.add(cdcEventStore.pollEvents(ORDERS, 1L, Optional.of(10), Optional.of(Duration.ofMillis(50)), Optional.of(tenant), Optional.of(SUBSCRIBER), Optional.of(noOptimizer))
                                       .subscribe(event -> received.add(event.globalEventOrder().longValue())));
        return received;
    }

    private void setup(Duration giveUpThreshold) {
        setup(giveUpThreshold, Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private void setup(Duration giveUpThreshold, Optional<SeparateTablePerAggregateEventStreamConfiguration> configuration) {
        gapHandler = new RecordingGapHandler(giveUpThreshold);
        delegate = mock(ConfigurableEventStore.class);
        uowFactory = mock(EventStoreUnitOfWorkFactory.class);
        EventStreamGapHandler<?>                                                  gapHandlers = mock(EventStreamGapHandler.class);
        when(uowFactory.withUnitOfWork(any(CheckedFunction.class))).thenAnswer(inv -> ((CheckedFunction<Object, ?>) inv.getArgument(0)).apply(null));
        when(gapHandlers.gapHandlerFor(any())).thenReturn(gapHandler);
        when(delegate.findAggregateEventStreamConfiguration(ORDERS)).thenReturn(configuration);
        when(delegate.getEventStoreSubscriptionObserver()).thenReturn(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver());
        when(delegate.pollEvents(any(), anyLong(), any(), any(), any(), any(), any(), any(SubscriberAcknowledgement.class)))
                .thenAnswer(inv -> {
                    SubscriberAcknowledgement acknowledgement = inv.getArgument(7);
                    acknowledgement.onAcknowledge(acknowledged -> acknowledged.forEach(event -> delegateAcknowledged.add(event.globalEventOrder().longValue())));
                    return polled.asFlux();
                });
        cdcEventStore = new CdcEventStore<>(delegate,
                                            uowFactory,
                                            gapHandlers,
                                            new CdcEventBus(),
                                            new CdcProperties(),
                                            // Stays INACTIVE: the polling leg
                                            new CdcAvailability());
    }

    private List<Long> subscribe() {
        return subscribe(Optional.empty());
    }

    private List<Long> subscribe(Optional<SubscriberAcknowledgement> acknowledgement) {
        var received = new CopyOnWriteArrayList<Long>();
        Function<String, EventStorePollingOptimizer> noOptimizer = name -> null;
        var flux = acknowledgement.isPresent()
                   ? cdcEventStore.pollEvents(ORDERS, 1L, Optional.of(10), Optional.of(Duration.ofMillis(50)), Optional.empty(), Optional.of(SUBSCRIBER), Optional.of(noOptimizer), acknowledgement.get())
                   : cdcEventStore.pollEvents(ORDERS, 1L, Optional.of(10), Optional.of(Duration.ofMillis(50)), Optional.empty(), Optional.of(SUBSCRIBER), Optional.of(noOptimizer));
        subscriptions.add(flux.subscribe(event -> received.add(event.globalEventOrder().longValue())));
        return received;
    }

    /**
     * The delegate's poll hands these on
     */
    private void poll(long... globalOrders) {
        for (var globalOrder : globalOrders) {
            polled.tryEmitNext(event(globalOrder)).orThrow();
        }
    }

    private PersistedEvent event(long globalOrder) {
        return events.computeIfAbsent(globalOrder, order -> event(order, Optional.empty()));
    }

    private static PersistedEvent event(long globalOrder, Optional<Tenant> tenant) {
        var event = mock(PersistedEvent.class);
        when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(globalOrder));
        when(event.aggregateType()).thenReturn(ORDERS);
        doReturn(tenant).when(event).tenant();
        return event;
    }

    private record Reconciliation(LongRange range, List<Long> events, List<Long> transientGapsIncludedInQuery) {
    }

    /**
     * One {@code addTransientGaps} call, and the thread it ran on - so a test can tell which unit of work it was in
     */
    private record TransientGapsAdded(LongRange gaps, Thread thread) {
    }

    /**
     * Records what the gate asks of it, and has no transient gaps recorded from before
     */
    private static final class RecordingGapHandler implements SubscriptionGapHandler {
        private final Duration             giveUpThreshold;
        private final List<Reconciliation> reconciliations       = new CopyOnWriteArrayList<>();
        private final List<List<Long>>     givenUp               = new CopyOnWriteArrayList<>();
        private final List<List<Long>>     resolved              = new CopyOnWriteArrayList<>();
        private final List<TransientGapsAdded> transientGapsAdded = new CopyOnWriteArrayList<>();
        private volatile int               findTransientGapsCalls;
        private volatile boolean           failGivingUp;
        private volatile boolean           failResolving;
        /** When set, {@code addTransientGaps} waits for it - as for a lock another unit of work holds */
        private volatile CountDownLatch    addingTransientGapsMayEnd;
        /** When set (and no latch is), each {@code addTransientGaps} call takes this long - a slow, but not blocked, statement */
        private volatile Duration          addingTransientGapsTakes;
        /** When set, {@code getTransientGapsFor} reports the gaps added and not resolved since - as the database would */
        private volatile boolean           reportsRecordedTransientGaps;
        /** How many {@code addTransientGaps} calls have started */
        private final AtomicInteger        addingTransientGapsCalls = new AtomicInteger();

        private RecordingGapHandler(Duration giveUpThreshold) {
            this.giveUpThreshold = giveUpThreshold;
        }

        @Override
        public SubscriberId subscriberId() {
            return SUBSCRIBER;
        }

        @Override
        public Optional<Duration> transientGapGiveUpThreshold() {
            return Optional.of(giveUpThreshold);
        }

        @Override
        public List<GlobalEventOrder> findTransientGapsToIncludeInQuery(AggregateType aggregateType, LongRange globalOrderQueryRange) {
            findTransientGapsCalls++;
            return List.of();
        }

        @Override
        public void reconcileGaps(AggregateType aggregateType, LongRange globalOrderQueryRange, List<PersistedEvent> persistedEvents, List<GlobalEventOrder> transientGapsIncludedInQuery) {
            reconciliations.add(new Reconciliation(globalOrderQueryRange,
                                                   persistedEvents.stream().map(event -> event.globalEventOrder().longValue()).toList(),
                                                   transientGapsIncludedInQuery.stream().map(GlobalEventOrder::longValue).toList()));
        }

        @Override
        public GapReconciliation resolveFilledGaps(AggregateType aggregateType, List<PersistedEvent> gapFills) {
            if (failResolving) throw new IllegalStateException("Resolving failed");
            resolved.add(gapFills.stream().map(event -> event.globalEventOrder().longValue()).toList());
            return new GapReconciliation(0, gapFills.size(), 0);
        }

        @Override
        public GapReconciliation addTransientGaps(AggregateType aggregateType, LongRange gaps) {
            addingTransientGapsCalls.incrementAndGet();
            var mayEnd = addingTransientGapsMayEnd;
            var takes  = addingTransientGapsTakes;
            if (mayEnd != null || takes != null) {
                try {
                    if (mayEnd != null) {
                        mayEnd.await();
                    } else {
                        new CountDownLatch(1).await(takes.toNanos(), TimeUnit.NANOSECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            transientGapsAdded.add(new TransientGapsAdded(gaps, Thread.currentThread()));
            return GapReconciliation.NONE;
        }

        @Override
        public GapReconciliation giveUpTransientGaps(AggregateType aggregateType, List<GlobalEventOrder> transientGaps) {
            if (failGivingUp) throw new IllegalStateException("Giving up failed");
            givenUp.add(transientGaps.stream().map(GlobalEventOrder::longValue).toList());
            return new GapReconciliation(0, 0, transientGaps.size());
        }

        @Override
        public List<GlobalEventOrder> resetTransientGapsFor(AggregateType aggregateType) {
            return List.of();
        }

        @Override
        public List<GlobalEventOrder> getTransientGapsFor(AggregateType aggregateType) {
            if (!reportsRecordedTransientGaps) return List.of();
            var open = new TreeSet<Long>();
            transientGapsAdded.forEach(added -> added.gaps().stream().forEach(open::add));
            resolved.forEach(open::removeAll);
            return open.stream().map(GlobalEventOrder::of).toList();
        }

        @Override
        public Stream<GlobalEventOrder> getPermanentGapsFor(AggregateType aggregateType) {
            return Stream.empty();
        }
    }
}
