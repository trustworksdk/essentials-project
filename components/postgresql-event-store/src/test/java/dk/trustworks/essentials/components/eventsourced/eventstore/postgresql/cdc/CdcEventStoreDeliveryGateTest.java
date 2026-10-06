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
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
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

    // ------------------------------------------------------------------------------------------------------------

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
     * Records what the gate asks of it, and has no transient gaps recorded from before
     */
    private static final class RecordingGapHandler implements SubscriptionGapHandler {
        private final Duration             giveUpThreshold;
        private final List<Reconciliation> reconciliations       = new CopyOnWriteArrayList<>();
        private final List<List<Long>>     givenUp               = new CopyOnWriteArrayList<>();
        private final List<List<Long>>     resolved              = new CopyOnWriteArrayList<>();
        private volatile int               findTransientGapsCalls;
        private volatile boolean           failGivingUp;
        private volatile boolean           failResolving;

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
            return List.of();
        }

        @Override
        public Stream<GlobalEventOrder> getPermanentGapsFor(AggregateType aggregateType) {
            return Stream.empty();
        }
    }
}
