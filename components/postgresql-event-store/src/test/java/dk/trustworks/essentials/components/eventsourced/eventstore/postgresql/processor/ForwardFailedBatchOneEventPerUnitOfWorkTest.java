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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.shared.functional.*;
import org.jdbi.v3.core.*;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AbstractEventProcessor#forwardFailedBatchOneEventPerUnitOfWork(List, Throwable, SubscriptionErrorPolicy, Consumer)}
 * tries every event of a failed batch once more on its own when the policy handed over skips. An event that fails with
 * an I/O or connection failure is tried once more at once (a stale pooled connection must not cost it); when it fails
 * that way again, the database is probed: only a failed probe - the database gone, not specific to the event - stops
 * the replay, instead of letting every later event wait for the same failure. A probe that answers lets the next event
 * be tried.
 * <p>
 * Mocks only: the unit of work factory runs the forward inline, and the probe succeeds or fails as the test says, so no
 * database is involved.
 */
class ForwardFailedBatchOneEventPerUnitOfWorkTest {

    @Test
    void stops_when_an_event_fails_twice_with_a_connection_failure_and_the_database_does_not_answer_the_probe() {
        var firstAttemptFailure = new RuntimeException("Could not forward", new ConnectException("Connection refused"));
        var retryFailure        = new RuntimeException("Could not forward", new ConnectException("Connection refused"));
        var forwarded           = new ArrayList<PersistedEvent>();
        var events              = events(4);
        var probes              = new AtomicInteger();

        assertThatThrownBy(() -> processor(false, probes).forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            throw forwarded.size() == 1 ? firstAttemptFailure : retryFailure;
        })).isSameAs(retryFailure)
           .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());

        assertThat(forwarded).containsExactly(events.get(0), events.get(0));
        assertThat(probes).hasValue(1);
    }

    @Test
    void keeps_an_event_whose_connection_failure_does_not_repeat_on_its_retry() {
        var connectionFailure = new RuntimeException("Could not forward", new ConnectException("Connection refused"));
        var forwarded         = new ArrayList<PersistedEvent>();
        var events            = events(4);
        var probes            = new AtomicInteger();

        var allForwarded = processor(true, probes).forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            if (forwarded.size() == 1) {
                throw connectionFailure;
            }
        });

        assertThat(allForwarded).isTrue();
        assertThat(forwarded).containsExactly(events.get(0), events.get(0), events.get(1), events.get(2), events.get(3));
        assertThat(probes).hasValue(0);
    }

    @Test
    void keeps_consecutive_events_that_each_draw_a_stale_connection_once() {
        var forwarded = new ArrayList<PersistedEvent>();
        var events    = events(4);
        var probes    = new AtomicInteger();

        var allForwarded = processor(true, probes).forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            var firstAttempt = !forwarded.contains(event);
            forwarded.add(event);
            if (firstAttempt && (event.equals(events.get(0)) || event.equals(events.get(1)))) {
                throw new RuntimeException("Could not forward", new ConnectException("Connection reset"));
            }
        });

        assertThat(allForwarded).isTrue();
        assertThat(forwarded).containsExactly(events.get(0), events.get(0), events.get(1), events.get(1), events.get(2), events.get(3));
        assertThat(probes).hasValue(0);
    }

    @Test
    void goes_on_after_an_event_specific_io_failure_when_the_database_answers_the_probe() {
        var event2Failure = new RuntimeException("Could not forward", new ConnectException("Connection refused"));
        var forwarded     = new ArrayList<PersistedEvent>();
        var events        = events(4);
        var probes        = new AtomicInteger();

        assertThatThrownBy(() -> processor(true, probes).forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            if (event.equals(events.get(1))) {
                throw event2Failure;
            }
        })).isSameAs(event2Failure)
           .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());

        assertThat(forwarded).containsExactly(events.get(0), events.get(1), events.get(1), events.get(2), events.get(3));
        assertThat(probes).hasValue(1);
    }

    @Test
    void does_not_probe_when_the_retry_fails_with_a_failure_that_is_not_an_io_failure() {
        var retryFailure = new IllegalStateException("event 1 cannot be forwarded");
        var forwarded    = new ArrayList<PersistedEvent>();
        var events       = events(3);
        var probes       = new AtomicInteger();

        assertThatThrownBy(() -> processor(false, probes).forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            if (forwarded.size() == 1) {
                throw new RuntimeException("Could not forward", new ConnectException("Connection refused"));
            }
            if (forwarded.size() == 2) {
                throw retryFailure;
            }
        })).isSameAs(retryFailure)
           .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());

        assertThat(forwarded).containsExactly(events.get(0), events.get(0), events.get(1), events.get(2));
        assertThat(probes).hasValue(0);
    }

    @Test
    void stops_at_a_jdbi_connection_failure_after_an_event_specific_one_when_the_database_does_not_answer_the_probe() {
        var eventSpecificFailure = new IllegalStateException("event 1 cannot be forwarded");
        var firstAttemptFailure  = new RuntimeException(new ConnectionException(new SQLException("Connection is gone")));
        var retryFailure         = new RuntimeException(new ConnectionException(new SQLException("Connection is gone")));
        var forwarded            = new ArrayList<PersistedEvent>();
        var events               = events(5);
        var probes               = new AtomicInteger();

        assertThatThrownBy(() -> processor(false, probes).forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            var firstAttempt = !forwarded.contains(event);
            forwarded.add(event);
            if (event.equals(events.get(0))) {
                throw eventSpecificFailure;
            }
            if (event.equals(events.get(1))) {
                throw firstAttempt ? firstAttemptFailure : retryFailure;
            }
        })).isSameAs(eventSpecificFailure)
           .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(retryFailure));

        assertThat(forwarded).containsExactly(events.get(0), events.get(1), events.get(1));
        assertThat(probes).hasValue(1);
    }

    @Test
    void tries_every_event_when_the_failures_are_specific_to_their_events() {
        var event2Failure = new IllegalStateException("event 2 cannot be forwarded");
        var event4Failure = new IllegalArgumentException("event 4 cannot be forwarded");
        var forwarded     = new ArrayList<PersistedEvent>();
        var events        = events(4);
        var probes        = new AtomicInteger();

        assertThatThrownBy(() -> processor(true, probes).forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
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
        assertThat(probes).hasValue(0);
    }

    @Test
    void forwards_nothing_when_the_policy_handed_over_stops() {
        var forwarded = new ArrayList<PersistedEvent>();

        var allForwarded = processor(true, new AtomicInteger()).forwardFailedBatchOneEventPerUnitOfWork(events(3), new RuntimeException("batch failed"), SubscriptionErrorPolicy.stop(), forwarded::add);

        assertThat(allForwarded).isFalse();
        assertThat(forwarded).isEmpty();
    }

    @Test
    void forwards_every_event_when_the_policy_handed_over_skips_whatever_the_subscription_manager_is() {
        // Neither the processor's own policy nor a DefaultEventStoreSubscriptionManager's to read: only the policy handed over
        var forwarded = new ArrayList<PersistedEvent>();
        var events    = events(3);

        var allForwarded = processor(Optional.empty(), true, new AtomicInteger()).forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), forwarded::add);

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

    private static AbstractEventProcessor processor(boolean databaseAnswersProbe, AtomicInteger probes) {
        return processor(Optional.of(SubscriptionErrorPolicy.skip()), databaseAnswersProbe, probes);
    }

    /**
     * @param databaseAnswersProbe whether the database probe ({@link AbstractEventProcessor#databaseReachable()}) succeeds
     * @param probes               counts the probes
     */
    @SuppressWarnings({"unchecked", "OptionalUsedAsFieldOrParameterType"})
    private static AbstractEventProcessor processor(Optional<SubscriptionErrorPolicy> ownPolicy, boolean databaseAnswersProbe, AtomicInteger probes) {
        var unitOfWorkFactory = mock(EventStoreUnitOfWorkFactory.class);
        // Runs the forward inline, as its own unit of work would
        doAnswer(invocation -> {
            invocation.<CheckedRunnable>getArgument(0).run();
            return null;
        }).when(unitOfWorkFactory).usingUnitOfWork(any(CheckedRunnable.class));
        // The database probe: the only caller of the UnitOfWork-consuming overload. It runs the probe's own body against
        // a mocked handle, whose SELECT 1 fails when the database does not answer
        var handle = mock(Handle.class);
        when(handle.execute(anyString())).thenAnswer(invocation -> {
            assertThat(invocation.<String>getArgument(0)).isEqualTo("SELECT 1");
            probes.incrementAndGet();
            if (!databaseAnswersProbe) {
                throw new ConnectionException(new SQLException("Connection refused", "08001"));
            }
            return 1;
        });
        var probeUnitOfWork = mock(EventStoreUnitOfWork.class);
        when(probeUnitOfWork.handle()).thenReturn(handle);
        doAnswer(invocation -> {
            invocation.<CheckedConsumer<EventStoreUnitOfWork>>getArgument(0).accept(probeUnitOfWork);
            return null;
        }).when(unitOfWorkFactory).usingUnitOfWork(any(CheckedConsumer.class));
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
