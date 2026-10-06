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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import org.junit.jupiter.api.*;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link AbstractEventStoreSubscription#isStoppedOrRecoveringFromErrorPolicyStop()} - the value of the stopped gauge - must
 * stay true while a resumed subscriber stops at the failed event again, also when the stop lands between the reads that
 * make up the answer. {@code isStoppedByErrorPolicy() || isRecoveringFromErrorPolicyStop()} read the stopped flag twice and
 * answered false when it turned true in between, which dipped the gauge to {@code 0} on a stuck subscription
 */
class AbstractEventStoreSubscriptionStoppedOrRecoveringTest {
    private static final GlobalEventOrder FAILED_AT = GlobalEventOrder.of(2);

    private AbstractEventStoreSubscription subscription;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setup() {
        var context = new EventStoreSubscriptionContext(mock(EventStore.class),
                                                        AggregateType.of("Orders"),
                                                        SubscriberId.of("Subscriber"),
                                                        null,
                                                        mock(EventStoreSubscriptionObserver.class),
                                                        mock(Consumer.class),
                                                        eventStreamLogName -> mock(EventStorePollingOptimizer.class));
        subscription = mock(AbstractEventStoreSubscription.class,
                            withSettings().useConstructor(context).defaultAnswer(CALLS_REAL_METHODS));
        // Stopped once at the event and resumed: the resumer awaits recovery at it
        subscription.autoResumer.stoppedAt(FAILED_AT, SubscriptionErrorPolicy.stop().withoutAutoResume());
    }

    @Test
    void stays_true_when_the_resumed_subscriber_stops_again_between_the_reads() {
        // The first read sees the resumed subscriber still retrying, every later one sees it stopped again
        doReturn(false, true).when(subscription).isStoppedByErrorPolicy();

        assertThat(subscription.isStoppedOrRecoveringFromErrorPolicyStop()).isTrue();
    }

    @Test
    void is_true_while_stopped_and_while_recovering_and_false_once_past_the_failed_event() {
        doReturn(true).when(subscription).isStoppedByErrorPolicy();
        assertThat(subscription.isStoppedOrRecoveringFromErrorPolicyStop()).isTrue();

        doReturn(false).when(subscription).isStoppedByErrorPolicy();
        assertThat(subscription.isRecoveringFromErrorPolicyStop()).isTrue();
        assertThat(subscription.isStoppedOrRecoveringFromErrorPolicyStop()).isTrue();

        subscription.autoResumer.movedPast(FAILED_AT);
        assertThat(subscription.isStoppedOrRecoveringFromErrorPolicyStop()).isFalse();
    }
}
