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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.functional.tuple.Pair;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;
import org.junit.jupiter.api.*;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DefaultEventStoreApiTest {
    private static final SubscriberId  ORDER_PROCESSOR = SubscriberId.of("OrderProcessor");
    private static final SubscriberId  IN_TX_PROJECTOR = SubscriberId.of("InTransactionProjector");
    private static final AggregateType ORDERS          = AggregateType.of("Orders");
    private static final Instant       NOW             = Instant.parse("2026-08-08T10:15:30Z");
    private static final OffsetDateTime RESUME_POINT_UPDATED = OffsetDateTime.parse("2026-08-08T10:15:00Z");

    private final EventStore                    eventStore                    = mock(EventStore.class);
    private final DurableSubscriptionRepository durableSubscriptionRepository = mock(DurableSubscriptionRepository.class);
    private final EventStoreSubscriptionManager subscriptionManager           = mock(EventStoreSubscriptionManager.class);
    private final SubscriptionStatisticsRegistry statisticsRegistry           = new SubscriptionStatisticsRegistry(10, Clock.fixed(NOW, ZoneOffset.UTC));

    private EventStoreApi api;

    @BeforeEach
    void setUp() {
        api = new DefaultEventStoreApi(new EssentialsSecurityProvider.AllAccessSecurityProvider(),
                                       eventStore,
                                       durableSubscriptionRepository,
                                       Optional.of(subscriptionManager),
                                       Optional.of(statisticsRegistry));
    }

    @Test
    void a_durable_resume_point_is_enriched_with_the_live_state_of_the_subscription_running_here() {
        var orderProcessor = subscription(ORDER_PROCESSOR, true, true, false, 105L);
        when(durableSubscriptionRepository.findAllResumePoints()).thenReturn(List.of(resumePoint(ORDER_PROCESSOR, 100)));
        when(subscriptionManager.getSubscriptions()).thenReturn(Set.of(Pair.of(ORDER_PROCESSOR, ORDERS)));
        when(subscriptionManager.getSubscription(ORDER_PROCESSOR, ORDERS)).thenReturn(Optional.of(orderProcessor));

        var subscriptions = api.findAllSubscriptions("principal");

        assertThat(subscriptions).hasSize(1);
        var subscription = subscriptions.get(0);
        assertThat((CharSequence) subscription.subscriberId()).isEqualTo(ORDER_PROCESSOR);
        assertThat((CharSequence) subscription.aggregateType()).isEqualTo(ORDERS);
        assertThat(subscription.currentGlobalOrder()).isEqualTo(100);
        assertThat(subscription.lastUpdated()).isEqualTo(RESUME_POINT_UPDATED);
        assertThat(subscription.durableResumePointPresent()).isTrue();
        assertThat(subscription.runningInThisInstance()).isTrue();
        assertThat(subscription.active()).isTrue();
        assertThat(subscription.exclusive()).isTrue();
        assertThat(subscription.inTransaction()).isFalse();
        assertThat(subscription.tenant()).isNull();
        assertThat(subscription.inMemoryGlobalOrder()).isEqualTo(105L);
    }

    /**
     * An in-transaction subscription keeps no durable resume point, and an asynchronous one has none until it is first
     * snapshotted - reporting only the database's resume points hides both.
     */
    @Test
    void a_subscription_without_a_durable_resume_point_is_still_reported() {
        var inTransactionProjector = subscription(IN_TX_PROJECTOR, true, false, true, null);
        when(durableSubscriptionRepository.findAllResumePoints()).thenReturn(List.of());
        when(subscriptionManager.getSubscriptions()).thenReturn(Set.of(Pair.of(IN_TX_PROJECTOR, ORDERS)));
        when(subscriptionManager.getSubscription(IN_TX_PROJECTOR, ORDERS)).thenReturn(Optional.of(inTransactionProjector));

        var subscriptions = api.findAllSubscriptions("principal");

        assertThat(subscriptions).hasSize(1);
        var subscription = subscriptions.get(0);
        assertThat((CharSequence) subscription.subscriberId()).isEqualTo(IN_TX_PROJECTOR);
        assertThat(subscription.durableResumePointPresent()).isFalse();
        assertThat(subscription.currentGlobalOrder()).isZero();
        assertThat(subscription.lastUpdated()).isNull();
        assertThat(subscription.runningInThisInstance()).isTrue();
        assertThat(subscription.inTransaction()).isTrue();
        assertThat(subscription.inMemoryGlobalOrder()).isNull();
    }

    @Test
    void a_subscription_is_reported_once_even_when_both_sources_know_it() {
        var orderProcessor = subscription(ORDER_PROCESSOR, true, true, false, 105L);
        when(durableSubscriptionRepository.findAllResumePoints()).thenReturn(List.of(resumePoint(ORDER_PROCESSOR, 100),
                                                                                    resumePoint(IN_TX_PROJECTOR, 7)));
        when(subscriptionManager.getSubscriptions()).thenReturn(Set.of(Pair.of(ORDER_PROCESSOR, ORDERS)));
        when(subscriptionManager.getSubscription(ORDER_PROCESSOR, ORDERS)).thenReturn(Optional.of(orderProcessor));

        assertThat(api.findAllSubscriptions("principal"))
                .extracting(ApiSubscription::subscriberId)
                .containsExactlyInAnyOrder(ORDER_PROCESSOR, IN_TX_PROJECTOR);
    }

    /** A subscription owned by another instance must not be reported as running - or as stalled - here. */
    @Test
    void a_subscription_running_on_another_instance_carries_no_live_state() {
        when(durableSubscriptionRepository.findAllResumePoints()).thenReturn(List.of(resumePoint(ORDER_PROCESSOR, 100)));
        when(subscriptionManager.getSubscriptions()).thenReturn(Set.of());
        when(subscriptionManager.getSubscription(ORDER_PROCESSOR, ORDERS)).thenReturn(Optional.empty());

        var subscription = api.findAllSubscriptions("principal").get(0);

        assertThat(subscription.runningInThisInstance()).isFalse();
        assertThat(subscription.active()).isNull();
        assertThat(subscription.exclusive()).isNull();
        assertThat(subscription.inTransaction()).isNull();
        assertThat(subscription.inMemoryGlobalOrder()).isNull();
        assertThat(subscription.durableResumePointPresent()).isTrue();
    }

    @Test
    void statistics_are_reported_per_subscription_and_map_every_group() {
        recordSomeActivity();

        var statistics = api.findSubscriptionStatistics("principal", ORDER_PROCESSOR, ORDERS).orElseThrow();

        assertThat((CharSequence) statistics.subscriberId()).isEqualTo(ORDER_PROCESSOR);
        assertThat((CharSequence) statistics.aggregateType()).isEqualTo(ORDERS);
        assertThat(statistics.statisticsSince()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(statistics.lifecycle().starts()).isEqualTo(1);
        assertThat(statistics.lifecycle().lastStartedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(statistics.eventHandling().eventsHandled()).isEqualTo(1);
        assertThat(statistics.eventHandling().lastEventHandledGlobalOrder()).isEqualTo(101L);
        assertThat(statistics.eventHandling().averageHandlingTimeMillis()).isEqualTo(25L);
        assertThat(statistics.eventHandling().maxHandlingTimeMillis()).isEqualTo(25L);
        assertThat(statistics.polling().polls()).isEqualTo(1);
        assertThat(statistics.polling().lastPollDurationMillis()).isEqualTo(4L);
        assertThat(statistics.lock().currentlyHeld()).isTrue();
        assertThat(statistics.lock().acquisitions()).isEqualTo(1);
        assertThat(statistics.reset().resets()).isZero();
        assertThat(statistics.reset().lastResetAt()).isNull();
        assertThat(api.findAllSubscriptionStatistics("principal")).containsExactly(statistics);
    }

    @Test
    void statistics_are_empty_for_a_subscription_this_instance_does_not_run() {
        assertThat(api.findSubscriptionStatistics("principal", ORDER_PROCESSOR, ORDERS)).isEmpty();
        assertThat(api.findAllSubscriptionStatistics("principal")).isEmpty();
    }

    /** The legacy constructor keeps answering exactly what it did before the live state and statistics existed. */
    @Test
    void without_a_subscription_manager_or_registry_only_the_durable_resume_points_are_reported() {
        when(durableSubscriptionRepository.findAllResumePoints()).thenReturn(List.of(resumePoint(ORDER_PROCESSOR, 100)));
        var durableOnlyApi = new DefaultEventStoreApi(new EssentialsSecurityProvider.AllAccessSecurityProvider(),
                                                      eventStore,
                                                      durableSubscriptionRepository);

        var subscriptions = durableOnlyApi.findAllSubscriptions("principal");

        assertThat(subscriptions).hasSize(1);
        assertThat(subscriptions.get(0).runningInThisInstance()).isFalse();
        assertThat(subscriptions.get(0).currentGlobalOrder()).isEqualTo(100);
        assertThat(durableOnlyApi.findAllSubscriptionStatistics("principal")).isEmpty();
        assertThat(durableOnlyApi.findSubscriptionStatistics("principal", ORDER_PROCESSOR, ORDERS)).isEmpty();
        verifyNoInteractions(subscriptionManager);
    }

    private void recordSomeActivity() {
        var observer     = new StatisticsCollectingEventStoreSubscriptionObserver(new EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver(),
                                                                                 statisticsRegistry);
        var subscription = subscription(ORDER_PROCESSOR, true, true, false, 105L);
        var event        = mock(PersistedEvent.class);
        when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(101));

        observer.startedSubscriber(subscription, Duration.ofMillis(2));
        observer.lockAcquired(mock(dk.trustworks.essentials.components.foundation.fencedlock.FencedLock.class), subscription);
        observer.handleEvent(event, mock(PersistedEventHandler.class), subscription, Duration.ofMillis(25));
        observer.eventStorePolled(ORDER_PROCESSOR, ORDERS, dk.trustworks.essentials.types.LongRange.from(100, 110),
                                  List.of(), Optional.empty(), List.of(event), Duration.ofMillis(4));
    }

    private static SubscriptionResumePoint resumePoint(SubscriberId subscriberId, long resumeFromAndIncluding) {
        return new SubscriptionResumePoint(subscriberId,
                                           ORDERS,
                                           GlobalEventOrder.of(resumeFromAndIncluding),
                                           RESUME_POINT_UPDATED);
    }

    private static EventStoreSubscription subscription(SubscriberId subscriberId,
                                                       boolean active,
                                                       boolean exclusive,
                                                       boolean inTransaction,
                                                       Long inMemoryGlobalOrder) {
        var subscription = mock(EventStoreSubscription.class);
        when(subscription.subscriberId()).thenReturn(subscriberId);
        when(subscription.aggregateType()).thenReturn(ORDERS);
        lenient().when(subscription.isActive()).thenReturn(active);
        lenient().when(subscription.isExclusive()).thenReturn(exclusive);
        lenient().when(subscription.isInTransaction()).thenReturn(inTransaction);
        lenient().when(subscription.onlyIncludeEventsForTenant()).thenReturn(Optional.empty());
        lenient().when(subscription.currentResumePoint())
                 .thenReturn(inMemoryGlobalOrder != null
                             ? Optional.of(new SubscriptionResumePoint(subscriberId, ORDERS,
                                                                      GlobalEventOrder.of(inMemoryGlobalOrder),
                                                                      RESUME_POINT_UPDATED))
                             : Optional.empty());
        return subscription;
    }
}
