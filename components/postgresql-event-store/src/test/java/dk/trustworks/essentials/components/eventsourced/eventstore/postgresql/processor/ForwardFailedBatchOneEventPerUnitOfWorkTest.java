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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.EventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.shared.functional.CheckedRunnable;
import org.jdbi.v3.core.ConnectionException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AbstractEventProcessor#forwardFailedBatchOneEventPerUnitOfWork(List, Throwable, SubscriptionErrorPolicy, Consumer)}
 * tries every event of a failed batch once more on its own when the policy handed over skips, but stops at the first failure that is not specific to the event - an I/O or
 * connection failure - instead of letting every later event wait for the same failure.
 * <p>
 * Mocks only: the unit of work factory runs the forward inline, so no database is involved.
 */
class ForwardFailedBatchOneEventPerUnitOfWorkTest {

    @Test
    void stops_at_a_connection_failure_and_rethrows_it_without_trying_the_later_events() {
        var connectionFailure = new RuntimeException("Could not forward", new ConnectException("Connection refused"));
        var forwarded         = new ArrayList<PersistedEvent>();
        var events            = events(4);

        assertThatThrownBy(() -> processor().forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            throw connectionFailure;
        })).isSameAs(connectionFailure);

        assertThat(forwarded).containsExactly(events.getFirst());
    }

    @Test
    void stops_at_a_jdbi_connection_failure_after_an_event_specific_one() {
        var eventSpecificFailure = new IllegalStateException("event 1 cannot be forwarded");
        var connectionFailure    = new RuntimeException(new ConnectionException(new SQLException("Connection is gone")));
        var forwarded            = new ArrayList<PersistedEvent>();
        var events               = events(4);

        assertThatThrownBy(() -> processor().forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            if (event.equals(events.get(0))) {
                throw eventSpecificFailure;
            }
            if (event.equals(events.get(1))) {
                throw connectionFailure;
            }
        })).isSameAs(eventSpecificFailure)
           .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(connectionFailure));

        assertThat(forwarded).containsExactly(events.get(0), events.get(1));
    }

    @Test
    void tries_every_event_when_the_failures_are_specific_to_their_events() {
        var event2Failure = new IllegalStateException("event 2 cannot be forwarded");
        var event4Failure = new IllegalArgumentException("event 4 cannot be forwarded");
        var forwarded     = new ArrayList<PersistedEvent>();
        var events        = events(4);

        assertThatThrownBy(() -> processor().forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            if (event.equals(events.get(1))) {
                throw event2Failure;
            }
            if (event.equals(events.get(3))) {
                throw event4Failure;
            }
        })).isSameAs(event2Failure)
           .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(event4Failure));

        assertThat(forwarded).containsExactlyElementsOf(events);
    }

    @Test
    void forwards_nothing_when_the_policy_handed_over_stops() {
        var forwarded = new ArrayList<PersistedEvent>();

        var allForwarded = processor().forwardFailedBatchOneEventPerUnitOfWork(events(3), new RuntimeException("batch failed"), SubscriptionErrorPolicy.stop(), forwarded::add);

        assertThat(allForwarded).isFalse();
        assertThat(forwarded).isEmpty();
    }

    @Test
    void forwards_every_event_when_the_policy_handed_over_skips_whatever_the_subscription_manager_is() {
        // Neither the processor's own policy nor a DefaultEventStoreSubscriptionManager's to read: only the policy handed over
        var forwarded = new ArrayList<PersistedEvent>();
        var events    = events(3);

        var allForwarded = processor(Optional.empty()).forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), forwarded::add);

        assertThat(allForwarded).isTrue();
        assertThat(forwarded).containsExactlyElementsOf(events);
    }

    // -------------------------------------------------------------------------------------------------------------------

    private static List<PersistedEvent> events(int count) {
        var events = new ArrayList<PersistedEvent>();
        for (var order = 1; order <= count; order++) {
            var event = mock(PersistedEvent.class);
            when(event.globalEventOrder()).thenReturn(GlobalEventOrder.of(order));
            events.add(event);
        }
        return events;
    }

    private static AbstractEventProcessor processor() {
        return processor(Optional.of(SubscriptionErrorPolicy.skip()));
    }

    @SuppressWarnings({"unchecked", "OptionalUsedAsFieldOrParameterType"})
    private static AbstractEventProcessor processor(Optional<SubscriptionErrorPolicy> ownPolicy) {
        var unitOfWorkFactory = mock(EventStoreUnitOfWorkFactory.class);
        // Runs the forward inline, as its own unit of work would
        doAnswer(invocation -> {
            invocation.<CheckedRunnable>getArgument(0).run();
            return null;
        }).when(unitOfWorkFactory).usingUnitOfWork(any(CheckedRunnable.class));
        var eventStore = mock(EventStore.class);
        doReturn(unitOfWorkFactory).when(eventStore).getUnitOfWorkFactory();
        var subscriptionManager = mock(EventStoreSubscriptionManager.class);
        when(subscriptionManager.getEventStore()).thenReturn(eventStore);

        var inbox = mock(Inbox.class);
        when(inbox.name()).thenReturn(InboxName.of("PolicyEventProcessor"));
        var inboxes = mock(Inboxes.class);
        when(inboxes.getOrCreateInbox(any(InboxConfig.class), any(Consumer.class))).thenReturn(inbox);

        return new ProcessorSubscriptionErrorPolicyTest.PolicyEventProcessor(subscriptionManager, inboxes, ownPolicy);
    }
}
