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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.junit.jupiter.api.Test;
import reactor.util.retry.Retry;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link BatchedPersistedEventSubscriber} hands a failed batch to
 * {@link BatchedPersistedEventHandler#handOffFailedBatch(List, Throwable, SubscriptionErrorPolicy)} together with the
 * policy it was built with, so a handler never has to work that policy out for itself.
 * <p>
 * Mocks only: no event store, no database.
 */
class BatchedPersistedEventSubscriberHandOffTest {
    private static final SubscriberId  SUBSCRIBER_ID  = SubscriberId.of("HandOffSubscriber");
    private static final AggregateType AGGREGATE_TYPE = AggregateType.of("Orders");

    @Test
    void the_hand_off_is_given_the_policy_the_subscriber_was_built_with() {
        var policy         = SubscriptionErrorPolicy.retryThenSkip(2);
        var policyHandedIn = new AtomicReference<SubscriptionErrorPolicy>();
        var handler = new BatchedPersistedEventHandler() {
            @Override
            public int handleBatch(List<PersistedEvent> events) {
                return 1;
            }

            @Override
            public boolean handOffFailedBatch(List<PersistedEvent> events, Throwable failure, SubscriptionErrorPolicy subscriptionErrorPolicy) {
                policyHandedIn.set(subscriptionErrorPolicy);
                return true;
            }
        };
        var subscriber = subscriber(handler, policy);
        try {
            assertThat(subscriber.handedOffToEventHandler(List.of(event()), new RuntimeException("batch failed"))).isTrue();
            assertThat(policyHandedIn.get()).isSameAs(policy);
        } finally {
            subscriber.dispose();
        }
    }

    @Test
    void a_handler_overriding_only_the_two_argument_hand_off_is_still_called() {
        var called = new AtomicBoolean();
        var handler = new BatchedPersistedEventHandler() {
            @Override
            public int handleBatch(List<PersistedEvent> events) {
                return 1;
            }

            @Override
            public boolean handOffFailedBatch(List<PersistedEvent> events, Throwable failure) {
                called.set(true);
                return true;
            }
        };
        var subscriber = subscriber(handler, SubscriptionErrorPolicy.skip());
        try {
            assertThat(subscriber.handedOffToEventHandler(List.of(event()), new RuntimeException("batch failed"))).isTrue();
            assertThat(called.get()).isTrue();
        } finally {
            subscriber.dispose();
        }
    }

    // -------------------------------------------------------------------------------------------------------------------

    private static BatchedPersistedEventSubscriber subscriber(BatchedPersistedEventHandler handler, SubscriptionErrorPolicy policy) {
        var subscription = mock(EventStoreSubscription.class);
        when(subscription.subscriberId()).thenReturn(SUBSCRIBER_ID);
        when(subscription.aggregateType()).thenReturn(AGGREGATE_TYPE);
        when(subscription.currentResumePoint()).thenReturn(Optional.of(new SubscriptionResumePoint(SUBSCRIBER_ID,
                                                                                                    AGGREGATE_TYPE,
                                                                                                    GlobalEventOrder.FIRST_GLOBAL_EVENT_ORDER,
                                                                                                    OffsetDateTime.now())));
        return new BatchedPersistedEventSubscriber(handler,
                                                   subscription,
                                                   (event, failure) -> { },
                                                   Retry.backoff(0, Duration.ofMillis(1)),
                                                   10,
                                                   mock(EventStore.class),
                                                   10,
                                                   Duration.ofHours(1),
                                                   policy);
    }

    private static PersistedEvent event() {
        var event = mock(PersistedEvent.class);
        when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(1));
        return event;
    }
}
