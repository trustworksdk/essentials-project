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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.fencedlock.FencedLockManager;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.reactive.EventBus;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
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

    @Test
    @SuppressWarnings("PMD.CloseResource") // The executor is shut down with shutdownNow() in the finally
    void subscribing_again_while_another_thread_adds_and_starts_the_held_subscription_does_not_WARN_and_a_direct_stop_still_does() throws Exception {
        var observer = new BlockingStartingSubscriberObserver();
        var manager  = manager(observer, mock(DurableSubscriptionRepository.class));
        manager.start();
        var adding = Executors.newSingleThreadExecutor();
        try {
            // The adding thread puts the subscription in the manager and blocks in the observer's startingSubscriber,
            // which runs before the subscription's own start()
            var added = adding.submit(() -> manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {}));
            var held  = observer.awaitStarting();
            assertThat(held.isStarted()).isFalse();

            var returned = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});

            assertThat(returned).isSameAs(held);
            assertThat(warnings()).isEmpty();
            assertThat(logAppender.list).filteredOn(event -> event.getLevel() == Level.INFO)
                                        .extracting(ILoggingEvent::getFormattedMessage)
                                        .anySatisfy(message -> assertThat(message).contains("this manager has not finished starting it"));

            observer.release();
            assertThat(added.get(5, TimeUnit.SECONDS)).isSameAs(held);
            assertThat(held.isStarted()).isTrue();

            // Once the manager's start of it returned, a stopped held subscription is still WARNed about
            held.stop();
            manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});
            assertThat(warnings()).singleElement()
                                  .satisfies(warning -> assertThat(warning.getFormattedMessage()).contains("not started"));
        } finally {
            observer.release();
            adding.shutdownNow();
            manager.stop();
        }
    }

    @Test
    @SuppressWarnings("PMD.CloseResource") // The executor is shut down with shutdownNow() in the finally
    void subscribing_again_while_the_managers_start_has_not_yet_started_the_held_subscriptions_does_not_WARN() throws Exception {
        var observer = new BlockingStartingSubscriberObserver();
        var manager  = manager(observer, mock(DurableSubscriptionRepository.class));
        var other    = AggregateType.of("OtherThings");
        var first    = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});
        var second   = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, other, Optional.empty(), (event, unitOfWork) -> {});
        var starting = Executors.newSingleThreadExecutor();
        try {
            // The manager's start() blocks in the observer on whichever subscription it starts first, the other one not reached yet
            var started = starting.submit(manager::start);
            observer.awaitStarting();
            assertThat(manager.isStarted()).isTrue();
            assertThat(first.isStarted()).isFalse();
            assertThat(second.isStarted()).isFalse();

            assertThat(manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {})).isSameAs(first);
            assertThat(manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, other, Optional.empty(), (event, unitOfWork) -> {})).isSameAs(second);

            assertThat(warnings()).isEmpty();
            observer.release();
            started.get(5, TimeUnit.SECONDS);
            assertThat(first.isStarted()).isTrue();
            assertThat(second.isStarted()).isTrue();
        } finally {
            observer.release();
            starting.shutdownNow();
            manager.stop();
        }
    }

    @Test
    void subscribing_again_to_a_held_subscription_whose_start_failed_WARNs() {
        var repository = mock(DurableSubscriptionRepository.class, invocation -> {
            throw new IllegalStateException("Resume point lookup failed");
        });
        var manager = manager(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver(), repository);
        manager.start();
        try {
            assertThatThrownBy(() -> manager.subscribeToAggregateEventsAsynchronously(SUBSCRIBER, THINGS, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, Optional.empty(), event -> {}))
                    .hasMessage("Resume point lookup failed");
            var held = manager.getSubscription(SUBSCRIBER, THINGS).orElseThrow();
            assertThat(held.isStarted()).isFalse();

            var returned = manager.subscribeToAggregateEventsAsynchronously(SUBSCRIBER, THINGS, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, Optional.empty(), event -> {});

            assertThat(returned).isSameAs(held);
            assertThat(warnings()).singleElement()
                                  .satisfies(warning -> assertThat(warning.getFormattedMessage()).contains("not started"));
        } finally {
            manager.stop();
        }
    }

    @Test
    void subscribing_again_to_held_subscriptions_the_managers_failed_start_did_not_start_WARNs_for_each() {
        var repository = mock(DurableSubscriptionRepository.class, invocation -> {
            throw new IllegalStateException("Resume point lookup failed");
        });
        var manager = manager(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver(), repository);
        var other   = AggregateType.of("OtherThings");
        var first   = manager.subscribeToAggregateEventsAsynchronously(SUBSCRIBER, THINGS, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, Optional.empty(), event -> {});
        var second  = manager.subscribeToAggregateEventsAsynchronously(SUBSCRIBER, other, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, Optional.empty(), event -> {});
        try {
            // The manager's start() throws on whichever subscription it starts first, and does not reach the other one
            assertThatThrownBy(manager::start).hasMessage("Resume point lookup failed");
            assertThat(manager.isStarted()).isTrue();
            assertThat(first.isStarted()).isFalse();
            assertThat(second.isStarted()).isFalse();

            assertThat(manager.subscribeToAggregateEventsAsynchronously(SUBSCRIBER, THINGS, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, Optional.empty(), event -> {})).isSameAs(first);
            assertThat(manager.subscribeToAggregateEventsAsynchronously(SUBSCRIBER, other, GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER, Optional.empty(), event -> {})).isSameAs(second);

            assertThat(warnings()).hasSize(2)
                                  .allSatisfy(warning -> assertThat(warning.getFormattedMessage()).contains("not started"));
        } finally {
            manager.stop();
        }
    }

    @Test
    void subscribing_again_to_a_held_subscription_the_managers_start_did_not_reach_after_an_Error_WARNs() {
        var eventBus = mock(EventBus.class);
        doThrow(new AssertionError("Local event bus refused the subscriber")).when(eventBus).addSyncSubscriber(any());
        var manager = manager(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver(), mock(DurableSubscriptionRepository.class), eventBus);
        var other   = AggregateType.of("OtherThings");
        var first   = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});
        var second  = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, other, Optional.empty(), (event, unitOfWork) -> {});
        try {
            // The manager's start() ends on an Error from whichever subscription it starts first, and does not reach the other one.
            // The one it reached reports itself started - its own handling of an Error, not the manager's
            assertThatThrownBy(manager::start).isInstanceOf(AssertionError.class).hasMessage("Local event bus refused the subscriber");
            assertThat(manager.isStarted()).isTrue();
            assertThat(List.of(first, second)).filteredOn(subscription -> !subscription.isStarted()).hasSize(1);
            var notReached = first.isStarted() ? second : first;

            assertThat(manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {})).isSameAs(first);
            assertThat(manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, other, Optional.empty(), (event, unitOfWork) -> {})).isSameAs(second);

            assertThat(warnings()).singleElement()
                                  .satisfies(warning -> assertThat(warning.getFormattedMessage()).contains("not started")
                                                                                                 .contains(notReached.aggregateType().toString()));
        } finally {
            manager.stop();
        }
    }

    @Test
    void unsubscribing_a_subscription_the_manager_no_longer_holds_leaves_the_held_one_subscribed_and_started() {
        var manager = startedManager();
        try {
            var stale = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});
            stale.unsubscribe();
            var held = manager.subscribeToAggregateEventsInTransaction(SUBSCRIBER, THINGS, Optional.empty(), (event, unitOfWork) -> {});
            assertThat(held).isNotSameAs(stale);

            // Same subscriber id and aggregate type as the held one, but not the instance the manager holds
            stale.unsubscribe();

            assertThat(manager.getSubscription(SUBSCRIBER, THINGS)).containsSame(held);
            assertThat(held.isStarted()).isTrue();
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
        return manager(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver(), mock(DurableSubscriptionRepository.class));
    }

    private static DefaultEventStoreSubscriptionManager manager(EventStoreSubscriptionObserver observer, DurableSubscriptionRepository durableSubscriptionRepository) {
        return manager(observer, durableSubscriptionRepository, mock(EventBus.class));
    }

    private static DefaultEventStoreSubscriptionManager manager(EventStoreSubscriptionObserver observer, DurableSubscriptionRepository durableSubscriptionRepository, EventBus localEventBus) {
        var eventStore = mock(EventStore.class);
        doReturn(localEventBus).when(eventStore).localEventBus();
        doReturn(observer).when(eventStore).getEventStoreSubscriptionObserver();
        return new DefaultEventStoreSubscriptionManager(eventStore,
                                                        10,
                                                        Duration.ofMillis(100),
                                                        mock(FencedLockManager.class),
                                                        Duration.ofMinutes(1),
                                                        durableSubscriptionRepository,
                                                        true);
    }

    /**
     * Blocks the first {@link #startingSubscriber} call - the manager calls it right before a subscription's own
     * {@code start()} - until {@link #release()}
     */
    private static class BlockingStartingSubscriberObserver extends EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver {
        private final CompletableFuture<EventStoreSubscription> firstStarting = new CompletableFuture<>();
        private final CountDownLatch                            released      = new CountDownLatch(1);

        @Override
        public void startingSubscriber(EventStoreSubscription eventStoreSubscription) {
            if (firstStarting.complete(eventStoreSubscription)) {
                try {
                    if (!released.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }

        EventStoreSubscription awaitStarting() throws Exception {
            return firstStarting.get(5, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }
    }
}
