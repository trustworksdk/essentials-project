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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.reactive.EventBus;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DefaultEventStoreSubscriptionManagerTest {
    private static final SubscriberId  SUBSCRIBER = SubscriberId.of("ThingsView");
    private static final AggregateType THINGS     = AggregateType.of("Things");

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        managerLogger().addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        managerLogger().detachAppender(logAppender);
    }

    @Test
    void test_advanced_resume_points_are_checked_ten_times_per_snapshot_interval() {
        assertThat(DefaultEventStoreSubscriptionManager.advancedResumePointsCheckInterval(Duration.ofSeconds(1))).isEqualTo(Duration.ofMillis(100));
        assertThat(DefaultEventStoreSubscriptionManager.advancedResumePointsCheckInterval(Duration.ofSeconds(5))).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void test_advanced_resume_points_are_checked_at_least_every_second_however_long_the_snapshot_interval() {
        assertThat(DefaultEventStoreSubscriptionManager.advancedResumePointsCheckInterval(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(1));
        assertThat(DefaultEventStoreSubscriptionManager.advancedResumePointsCheckInterval(Duration.ofHours(1))).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void test_advanced_resume_points_are_checked_no_more_often_than_every_50_ms() {
        assertThat(DefaultEventStoreSubscriptionManager.advancedResumePointsCheckInterval(Duration.ofMillis(200))).isEqualTo(Duration.ofMillis(50));
    }

    @Test
    void test_advanced_resume_points_are_checked_no_less_often_than_the_periodic_save() {
        assertThat(DefaultEventStoreSubscriptionManager.advancedResumePointsCheckInterval(Duration.ofMillis(30))).isEqualTo(Duration.ofMillis(30));
    }

    @Test
    void subscribing_again_to_a_held_subscription_that_was_stopped_returns_it_unstarted_and_WARNs_naming_unsubscribe() {
        var manager = startedManager();
        try {
            var held = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});
            assertThat(held.isStarted()).isTrue();
            held.stop();

            var returned = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});

            assertThat(returned).isSameAs(held);
            assertThat(returned.isStarted()).isFalse();
            assertThat(warnings()).singleElement()
                                  .satisfies(warning -> assertThat(warning.getFormattedMessage()).contains(SUBSCRIBER.toString())
                                                                                                 .contains(THINGS.toString())
                                                                                                 .contains("not started")
                                                                                                 .contains("event handler included")
                                                                                                 .contains("EventStoreSubscription#unsubscribe()"));
        } finally {
            manager.stop();
        }
    }

    @Test
    void subscribing_again_to_a_held_subscription_that_is_started_does_not_WARN() {
        var manager = startedManager();
        try {
            var held = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});

            var returned = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});

            assertThat(returned).isSameAs(held);
            assertThat(returned.isStarted()).isTrue();
            assertThat(warnings()).isEmpty();
        } finally {
            manager.stop();
        }
    }

    @Test
    void subscribing_again_while_the_manager_is_stopped_does_not_WARN_since_its_start_starts_the_held_subscription() {
        var manager = manager();
        var held    = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});

        var returned = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});

        assertThat(returned).isSameAs(held);
        assertThat(warnings()).isEmpty();
        manager.start();
        try {
            assertThat(held.isStarted()).isTrue();
        } finally {
            manager.stop();
        }
    }

    @Test
    void subscribing_again_after_unsubscribe_creates_a_started_subscription_and_does_not_WARN() {
        var manager = startedManager();
        try {
            var first = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});
            first.unsubscribe();

            var second = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});

            assertThat(second).isNotSameAs(first);
            assertThat(second.isStarted()).isTrue();
            assertThat(warnings()).isEmpty();
        } finally {
            manager.stop();
        }
    }

    private List<ILoggingEvent> warnings() {
        return logAppender.list.stream()
                               .filter(event -> event.getLevel() == Level.WARN)
                               .toList();
    }

    private static Logger managerLogger() {
        return (Logger) LoggerFactory.getLogger(DefaultEventStoreSubscriptionManager.class);
    }

    private static DefaultEventStoreSubscriptionManager startedManager() {
        var manager = manager();
        manager.start();
        return manager;
    }

    /**
     * Mocks throughout, so no database is involved: an in-transaction subscription only registers with the event store's
     * local event bus when it starts
     */
    private static DefaultEventStoreSubscriptionManager manager() {
        var eventStore = mock(EventStore.class);
        doReturn(mock(EventBus.class)).when(eventStore).localEventBus();
        doReturn(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver()).when(eventStore).getEventStoreSubscriptionObserver();
        return new DefaultEventStoreSubscriptionManager(eventStore,
                                                        10,
                                                        Duration.ofMillis(100),
                                                        mock(FencedLockManager.class),
                                                        Duration.ofMinutes(1),
                                                        mock(DurableSubscriptionRepository.class),
                                                        true);
    }
}
