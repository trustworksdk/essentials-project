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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.functional.CheckedRunnable;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SubscriberGlobalOrderMicrometerMonitorTest {
    private static final SubscriberId  SUBSCRIBER_ID  = SubscriberId.of("OrderProjection");
    private static final AggregateType AGGREGATE_TYPE = AggregateType.of("Orders");
    private static final String        LAG_METRIC     = "DurableSubscriptions_EventOrder_Diff";

    private SimpleMeterRegistry                                       meterRegistry;
    private EventStoreSubscriptionManager                             subscriptionManager;
    private EventStore                                                eventStore;
    private EventStoreUnitOfWorkFactory<EventStoreUnitOfWork>         unitOfWorkFactory;
    private ListAppender<ILoggingEvent>                               logAppender;
    private ch.qos.logback.classic.Logger                             logger;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        subscriptionManager = mock(EventStoreSubscriptionManager.class);
        eventStore = mock(EventStore.class);
        when(subscriptionManager.getEventStore()).thenReturn(eventStore);
        unitOfWorkFactory = mock(EventStoreUnitOfWorkFactory.class);
        doAnswer(invocation -> {
            invocation.<CheckedRunnable>getArgument(0).run();
            return null;
        }).when(unitOfWorkFactory).usingUnitOfWork(any(CheckedRunnable.class));

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SubscriberGlobalOrderMicrometerMonitor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @Test
    void the_lag_counts_the_events_the_subscriber_has_yet_to_read() {
        var monitor = new SubscriberGlobalOrderMicrometerMonitor(subscriptionManager, meterRegistry, unitOfWorkFactory, null);

        // The resume point is the next order to read: resuming from 10 with 10 persisted is one unprocessed event
        positions(10, Optional.of(10L));
        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);
        assertThat(lag()).isEqualTo(1.0);

        positions(11, Optional.of(10L));
        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);
        assertThat(lag()).isEqualTo(0.0);

        positions(1, Optional.of(100L));
        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);
        assertThat(lag()).isEqualTo(100.0);
    }

    @Test
    void no_events_persisted_is_no_lag() {
        var monitor = new SubscriberGlobalOrderMicrometerMonitor(subscriptionManager, meterRegistry, unitOfWorkFactory, null);

        positions(GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER.longValue(), Optional.empty());
        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);

        assertThat(lag()).isEqualTo(0.0);
        assertThat(logAppender.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void a_resume_point_ahead_of_the_head_is_warned_about_once_and_reads_as_no_lag() {
        var monitor = new SubscriberGlobalOrderMicrometerMonitor(subscriptionManager, meterRegistry, unitOfWorkFactory, null);

        // A restored or reset database: the subscriber resumes from 501, the table now ends at 200
        positions(501, Optional.of(200L));
        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);
        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);

        assertThat(lag()).isEqualTo(0.0);
        assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                                    .singleElement()
                                    .extracting(ILoggingEvent::getFormattedMessage).asString()
                                    .contains("'OrderProjection'")
                                    .contains("'Orders'")
                                    .contains("501")
                                    .contains("200");

        // Caught up with again, then ahead again: a new episode, a new WARN
        positions(201, Optional.of(200L));
        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);
        positions(301, Optional.of(200L));
        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);
        assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.WARN).hasSize(2);
    }

    @Test
    void without_a_meter_registry_nothing_is_read_or_registered() {
        var monitor = new SubscriberGlobalOrderMicrometerMonitor(subscriptionManager, null, unitOfWorkFactory, null);

        monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE);

        verifyNoInteractions(unitOfWorkFactory, eventStore);
    }

    @Test
    void a_failure_does_not_escape_the_monitor() {
        var monitor = new SubscriberGlobalOrderMicrometerMonitor(subscriptionManager, meterRegistry, unitOfWorkFactory, null);
        when(subscriptionManager.getCurrentEventOrder(SUBSCRIBER_ID, AGGREGATE_TYPE)).thenReturn(Optional.of(GlobalEventOrder.of(1)));
        when(eventStore.findHighestGlobalEventOrderPersisted(AGGREGATE_TYPE)).thenThrow(new IllegalStateException("Connection refused"));

        // An exception escaping a monitor cancels the EventStoreSubscriptionMonitorManager's schedule
        assertThatCode(() -> monitor.monitor(SUBSCRIBER_ID, AGGREGATE_TYPE)).doesNotThrowAnyException();
        assertThat(logAppender.list).anyMatch(event -> event.getLevel() == Level.WARN);
    }

    private void positions(long resumeFromAndIncluding, Optional<Long> highestPersisted) {
        when(subscriptionManager.getCurrentEventOrder(SUBSCRIBER_ID, AGGREGATE_TYPE)).thenReturn(Optional.of(GlobalEventOrder.of(resumeFromAndIncluding)));
        when(eventStore.findHighestGlobalEventOrderPersisted(AGGREGATE_TYPE)).thenReturn(highestPersisted.map(GlobalEventOrder::of));
    }

    private double lag() {
        return meterRegistry.get(LAG_METRIC).tag("SubscriberId", SUBSCRIBER_ID.toString()).tag("AggregateType", AGGREGATE_TYPE.toString()).gauge().value();
    }
}
