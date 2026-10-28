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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.types.LongRange;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcDeliveryTracker.MAX_AWAITED_ORDERS_PER_GAP_END;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link CdcEventStore} as an {@link AwaitedGapInspector}: what {@code awaitProcessed} asks it about a subscriber's gaps
 */
class CdcEventStoreAwaitedGapInspectorTest {
    private static final AggregateType ORDERS     = AggregateType.of("orders");
    private static final SubscriberId  SUBSCRIBER = SubscriberId.of("inspector-test");

    @Test
    void forwards_to_a_delegate_that_is_an_awaited_gap_inspector() {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(ConfigurableEventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
        when(((AwaitedGapInspector) delegate).isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(7))).thenReturn(true);
        var cdcEventStore = cdcEventStore(delegate, transientGaps());

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(7))).isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(8))).isFalse();
    }

    @Test
    void answers_from_the_transient_gaps_its_own_gap_handler_records_when_the_delegate_is_no_awaited_gap_inspector() {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(ConfigurableEventStore.class);
        var cdcEventStore = cdcEventStore(delegate, transientGaps(GlobalEventOrder.of(3)));

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(3))).isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(4))).isFalse();
    }

    @Test
    void does_not_ask_a_gap_handler_that_records_no_gaps() {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(ConfigurableEventStore.class);
        var gapHandler = spy(new NoEventStreamGapHandler<SeparateTablePerAggregateEventStreamConfiguration>());
        var cdcEventStore = cdcEventStore(delegate, gapHandler);

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(3))).isFalse();
        verify(gapHandler, never()).gapHandlerFor(any());
    }

    @Test
    void answers_from_the_middles_a_subscription_left_for_the_next_one_when_neither_the_delegate_nor_the_gap_handler_awaits_them() {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(ConfigurableEventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
        var cdcEventStore = cdcEventStore(delegate, transientGaps());
        var middles = cdcEventStore.gapMiddlesAwaitedAcrossSubscribes();
        var subscribe = middles.subscribe(SUBSCRIBER, ORDERS, 1, Duration.ofMinutes(2));
        subscribe.await(LongRange.between(100, 200));
        middles.subscribeEnded(subscribe);

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(150))).isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(201))).isFalse();
        assertThat(cdcEventStore.isAwaitedAsGap(SubscriberId.of("another-subscriber"), ORDERS, GlobalEventOrder.of(150))).isFalse();
    }

    @Test
    void answers_from_the_running_subscription_for_the_middle_of_a_wide_gap_it_found_itself_until_the_subscription_ends() {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(ConfigurableEventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
        var cdcEventStore = cdcEventStore(delegate, transientGaps());
        var tracker = CdcDeliveryTracker.startingAfter("inspector-test", 0);
        tracker.markDelivered(1);
        // Opens a gap wider than twice MAX_AWAITED_ORDERS_PER_GAP_END: its middle is awaited in memory only, by the tracker
        long aboveTheGap = 2 + 2L * MAX_AWAITED_ORDERS_PER_GAP_END + 100;
        tracker.markDelivered(aboveTheGap);
        long inTheMiddle = 2 + MAX_AWAITED_ORDERS_PER_GAP_END + 50;
        cdcEventStore.deliveryTrackerRunning(SUBSCRIBER, ORDERS, tracker);

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(inTheMiddle))).as("in the middle, not delivered").isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(1))).as("delivered").isFalse();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(aboveTheGap))).as("delivered").isFalse();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(aboveTheGap + 1))).as("above the highest delivered").isFalse();
        assertThat(cdcEventStore.isAwaitedAsGap(SubscriberId.of("another-subscriber"), ORDERS, GlobalEventOrder.of(inTheMiddle))).isFalse();

        // Delivered as a gap fill: still awaited until the subscriber is done with it
        tracker.markDelivered(inTheMiddle);
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(inTheMiddle))).as("delivered, the subscriber not done with it").isTrue();
        tracker.doneWith(List.of(eventWithGlobalOrder(inTheMiddle)));
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(inTheMiddle))).as("the subscriber is done with it").isFalse();

        // The subscription ends: the tracker is no longer asked
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(inTheMiddle + 1))).isTrue();
        cdcEventStore.deliveryTrackerEnded(SUBSCRIBER, ORDERS, tracker);
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(inTheMiddle + 1))).isFalse();
        assertThat(cdcEventStore.runningDeliveryTrackerCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void does_not_read_the_transient_gaps_a_second_time_when_the_delegate_reads_them_with_the_same_gap_handler() {
        PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(PostgresqlEventStore.class);
        var gapHandler = transientGaps(GlobalEventOrder.of(3));
        doReturn(Optional.of(gapHandler)).when(delegate).transientGapsReadFrom();
        when(delegate.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(3))).thenReturn(true);
        var cdcEventStore = cdcEventStore(delegate, gapHandler);

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(3))).isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(4))).isFalse();
        verify(gapHandler, never()).gapHandlerFor(any());
        verify(delegate).isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(4));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reads_the_transient_gaps_of_its_own_gap_handler_when_the_delegate_has_another_one() {
        PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(PostgresqlEventStore.class);
        doReturn(Optional.of(transientGaps())).when(delegate).transientGapsReadFrom();
        var gapHandler    = transientGaps(GlobalEventOrder.of(3));
        var cdcEventStore = cdcEventStore(delegate, gapHandler);

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(3))).isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(4))).isFalse();
        verify(gapHandler, times(2)).gapHandlerFor(SUBSCRIBER);
    }

    @Test
    void reads_the_transient_gaps_once_per_call_behind_a_forwarding_decorator_over_a_store_with_the_same_gap_handler() {
        var gapHandler    = transientGaps(GlobalEventOrder.of(3));
        var cdcEventStore = cdcEventStore(forwardingDecorator(storeReadingTheTransientGapsOf(gapHandler)), gapHandler);

        for (var order : List.of(GlobalEventOrder.of(3), GlobalEventOrder.of(4))) {
            clearInvocations(gapHandler);
            assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, order)).isEqualTo(order.equals(GlobalEventOrder.of(3)));
            verify(gapHandler, times(1).description("read by the store behind the decorator only")).gapHandlerFor(SUBSCRIBER);
        }
    }

    @Test
    void reads_the_transient_gaps_of_its_own_gap_handler_too_behind_a_forwarding_decorator_over_a_store_with_another_one() {
        var delegatesGapHandler = transientGaps(GlobalEventOrder.of(5));
        var gapHandler          = transientGaps(GlobalEventOrder.of(3));
        var cdcEventStore       = cdcEventStore(forwardingDecorator(storeReadingTheTransientGapsOf(delegatesGapHandler)), gapHandler);

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(3))).as("its own gap handler's").isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(5))).as("the delegate's gap handler's").isTrue();
        clearInvocations(gapHandler, delegatesGapHandler);
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(4))).isFalse();
        verify(gapHandler).gapHandlerFor(SUBSCRIBER);
        verify(delegatesGapHandler).gapHandlerFor(SUBSCRIBER);
    }

    @Test
    void reports_its_own_gap_handler_as_the_one_it_reads_transient_gaps_from_or_the_delegates_when_its_own_records_none() {
        var delegatesGapHandler = transientGaps();
        var delegate            = storeReadingTheTransientGapsOf(delegatesGapHandler);
        var gapHandler          = transientGaps();

        assertThat(cdcEventStore(delegate, gapHandler).transientGapsReadFrom()).containsSame(gapHandler);
        assertThat(cdcEventStore(delegate, new NoEventStreamGapHandler<>()).transientGapsReadFrom()).containsSame(delegatesGapHandler);
        assertThat(cdcEventStore(mock(ConfigurableEventStore.class), new NoEventStreamGapHandler<>()).transientGapsReadFrom()).isEmpty();
    }

    @Test
    void warns_once_when_built_over_an_event_store_that_is_no_awaited_gap_inspector() {
        var warnings = new ListAppender<ILoggingEvent>();
        var logger   = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(CdcEventStore.class);
        warnings.start();
        logger.addAppender(warnings);
        try {
            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> noInspector = mock(ConfigurableEventStore.class);
            var cdcEventStore = cdcEventStore(noInspector, transientGaps());
            cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(3));
            cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(4));
            assertThat(awaitedGapInspectorWarnings(warnings)).hasSize(1);
            assertThat(awaitedGapInspectorWarnings(warnings).getFirst().getFormattedMessage()).contains(noInspector.getClass().getName());

            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> inspector = mock(ConfigurableEventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
            cdcEventStore(inspector, transientGaps());
            assertThat(awaitedGapInspectorWarnings(warnings)).as("no WARN for a delegate that can be asked").hasSize(1);
        } finally {
            logger.detachAppender(warnings);
        }
    }

    @Test
    void keeps_asking_the_running_tracker_of_a_subscriber_while_another_of_its_trackers_ends() {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(ConfigurableEventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
        var cdcEventStore = cdcEventStore(delegate, transientGaps());
        // Two subscribes of the subscriber - a new one started before the old one's end has run - each awaiting the
        // middle of a different wide gap in memory only
        var first  = trackerAwaitingTheMiddleOfAWideGapAbove(0);
        var second = trackerAwaitingTheMiddleOfAWideGapAbove(first.aboveTheGap());
        cdcEventStore.deliveryTrackerRunning(SUBSCRIBER, ORDERS, first.tracker());
        cdcEventStore.deliveryTrackerRunning(SUBSCRIBER, ORDERS, second.tracker());

        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(first.inTheMiddle()))).isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(second.inTheMiddle()))).isTrue();
        assertThat(cdcEventStore.runningDeliveryTrackerCount()).as("one subscriber and aggregate type").isEqualTo(1);

        cdcEventStore.deliveryTrackerEnded(SUBSCRIBER, ORDERS, first.tracker());
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(second.inTheMiddle()))).as("the second tracker still runs").isTrue();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(first.inTheMiddle()))).as("the first tracker ended").isFalse();
        assertThat(cdcEventStore.runningDeliveryTrackerCount()).isEqualTo(1);

        cdcEventStore.deliveryTrackerEnded(SUBSCRIBER, ORDERS, second.tracker());
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, GlobalEventOrder.of(second.inTheMiddle()))).isFalse();
        assertThat(cdcEventStore.runningDeliveryTrackerCount()).isZero();
    }

    @Test
    void a_middle_the_running_tracker_awaits_stays_awaited_while_its_subscription_ends_and_hands_it_over() {
        ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate = mock(ConfigurableEventStore.class, withSettings().extraInterfaces(AwaitedGapInspector.class));
        var cdcEventStore = cdcEventStore(delegate, transientGaps());
        var middles       = cdcEventStore.gapMiddlesAwaitedAcrossSubscribes();
        var wideGap       = trackerAwaitingTheMiddleOfAWideGapAbove(0);
        var tracker       = wideGap.tracker();
        tracker.seedEarlierMiddles(middles.subscribe(SUBSCRIBER, ORDERS, 1, Duration.ofMinutes(2)));
        cdcEventStore.deliveryTrackerRunning(SUBSCRIBER, ORDERS, tracker);
        var inTheMiddle = GlobalEventOrder.of(wideGap.inTheMiddle());
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, inTheMiddle)).as("awaited by the running tracker").isTrue();

        // The steps of DeliveryGate.subscriptionEnded, in its order: hand the middle over, then stop asking the tracker
        tracker.handOverMiddlesAwaitedInMemoryOnly();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, inTheMiddle)).as("handed over, the tracker still running").isTrue();
        middles.subscribeEnded(tracker.earlierMiddles());
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, inTheMiddle)).as("the subscribe ended, the tracker still running").isTrue();
        cdcEventStore.deliveryTrackerEnded(SUBSCRIBER, ORDERS, tracker);
        assertThat(cdcEventStore.runningDeliveryTrackerCount()).isZero();
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, inTheMiddle)).as("kept for the next subscription").isTrue();

        // The next subscription starts at the resume point, above the gap, and takes the middle over
        var next = middles.subscribe(SUBSCRIBER, ORDERS, wideGap.aboveTheGap() + 1, Duration.ofMinutes(2));
        assertThat(cdcEventStore.isAwaitedAsGap(SUBSCRIBER, ORDERS, inTheMiddle)).as("taken over by the next subscription").isTrue();
        middles.subscribeEnded(next);
    }

    /**
     * A tracker that delivered the order right above {@code delivered}, then one so far above it that the gap between is
     * wider than twice {@code MAX_AWAITED_ORDERS_PER_GAP_END}: its middle is awaited in memory only, by the tracker
     */
    private static WideGap trackerAwaitingTheMiddleOfAWideGapAbove(long delivered) {
        var  tracker     = CdcDeliveryTracker.startingAfter("inspector-test", delivered);
        long belowTheGap = delivered + 1;
        tracker.markDelivered(belowTheGap);
        long aboveTheGap = belowTheGap + 1 + 2L * MAX_AWAITED_ORDERS_PER_GAP_END + 100;
        tracker.markDelivered(aboveTheGap);
        return new WideGap(tracker, belowTheGap + 1 + MAX_AWAITED_ORDERS_PER_GAP_END + 50, aboveTheGap);
    }

    private record WideGap(CdcDeliveryTracker tracker, long inTheMiddle, long aboveTheGap) {
    }

    /**
     * A {@link PostgresqlEventStore} as far as {@link AwaitedGapInspector} goes: answers from the transient gaps of
     * {@code gapHandler}, and reports it as the gap handler it reads them from
     */
    @SuppressWarnings("unchecked")
    private static PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> storeReadingTheTransientGapsOf(EventStreamGapHandler<?> gapHandler) {
        PostgresqlEventStore<SeparateTablePerAggregateEventStreamConfiguration> store = mock(PostgresqlEventStore.class);
        when(store.isAwaitedAsGap(any(), any(), any())).thenAnswer(call -> gapHandler.gapHandlerFor(call.getArgument(0))
                                                                                     .getTransientGapsFor(call.getArgument(1))
                                                                                     .contains(call.getArgument(2)));
        doReturn(Optional.of(gapHandler)).when(store).transientGapsReadFrom();
        return store;
    }

    /**
     * An application decorator around {@code delegate} that implements {@link AwaitedGapInspector} and forwards every
     * call to it, {@link AwaitedGapInspector#transientGapsReadFrom()} included - a JDK dynamic proxy, so the test needn't
     * hand-write a forwarder for every {@link ConfigurableEventStore} method
     */
    @SuppressWarnings("unchecked")
    private static ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> forwardingDecorator(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate) {
        return (ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration>) Proxy.newProxyInstance(CdcEventStoreAwaitedGapInspectorTest.class.getClassLoader(),
                                                                                                                  new Class<?>[]{ConfigurableEventStore.class, AwaitedGapInspector.class},
                                                                                                                  (proxy, method, args) -> {
                                                                                                                      try {
                                                                                                                          return method.invoke(delegate, args);
                                                                                                                      } catch (InvocationTargetException e) {
                                                                                                                          throw e.getCause();
                                                                                                                      }
                                                                                                                  });
    }

    private static List<ILoggingEvent> awaitedGapInspectorWarnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                            .filter(event -> event.getLevel() == Level.WARN)
                            .filter(event -> event.getFormattedMessage().contains(AwaitedGapInspector.class.getSimpleName()))
                            .toList();
    }

    private static PersistedEvent eventWithGlobalOrder(long globalOrder) {
        var event = mock(PersistedEvent.class);
        when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(globalOrder));
        return event;
    }

    @SuppressWarnings("unchecked")
    private static EventStreamGapHandler<?> transientGaps(GlobalEventOrder... gaps) {
        var subscriptionGapHandler = mock(SubscriptionGapHandler.class);
        when(subscriptionGapHandler.getTransientGapsFor(ORDERS)).thenReturn(List.of(gaps));
        EventStreamGapHandler<?> gapHandler = mock(EventStreamGapHandler.class);
        when(gapHandler.gapHandlerFor(any())).thenReturn(subscriptionGapHandler);
        return gapHandler;
    }

    @SuppressWarnings("unchecked")
    private static CdcEventStore<SeparateTablePerAggregateEventStreamConfiguration> cdcEventStore(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> delegate,
                                                                                                  EventStreamGapHandler<?> gapHandler) {
        return new CdcEventStore<>(delegate,
                                   mock(EventStoreUnitOfWorkFactory.class),
                                   gapHandler,
                                   new CdcEventBus(),
                                   new CdcProperties(),
                                   new CdcAvailability());
    }
}
