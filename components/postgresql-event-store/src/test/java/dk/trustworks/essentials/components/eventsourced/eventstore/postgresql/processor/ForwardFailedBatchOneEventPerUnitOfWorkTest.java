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
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.AbstractEventProcessor.FailedBatchReplayTimeBudgetSpentException;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.shared.functional.*;
import org.jdbi.v3.core.ConnectionException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link AbstractEventProcessor#forwardFailedBatchOneEventPerUnitOfWork(List, Throwable, SubscriptionErrorPolicy, Consumer)}
 * forwards every event of a failed batch again on its own when the policy handed over skips. An event that fails with
 * an I/O or connection failure is forwarded a second time at once (a stale pooled connection must not cost it). What
 * stops the replay is its time budget ({@link AbstractEventProcessor#getFailedBatchReplayTimeBudget()}), never the kind
 * or the number of failures: once the budget is spent, the events not tried yet are not forwarded.
 * <p>
 * Mocks only: the unit of work factory runs the forward inline, and the replay's clock is a fake one that a test
 * advances inside the forward, so no database is involved and no test waits for the budget to run out.
 */
class ForwardFailedBatchOneEventPerUnitOfWorkTest {
    private static final long BUDGET_NANOS = Duration.ofSeconds(AbstractEventProcessor.DEFAULT_FAILED_BATCH_REPLAY_TIME_BUDGET_SECONDS).toNanos();

    private final AtomicLong clock = new AtomicLong(1_000);

    @Test
    void stops_at_the_first_event_after_the_time_budget_is_spent_and_leaves_the_rest_untried() {
        var connectionFailure = new RuntimeException("Could not forward", new ConnectionException(new SQLException("Connection is gone", "08006")));
        var forwarded         = new ArrayList<PersistedEvent>();
        var events            = events(5);

        assertThatThrownBy(() -> processor().forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            // Each forward waits out a third of the budget for its connection: the second event's two forwards spend it
            clock.addAndGet(BUDGET_NANOS / 3);
            if (!event.equals(events.get(0))) {
                throw connectionFailure;
            }
        })).isSameAs(connectionFailure)
           .satisfies(thrown -> assertThat(thrown.getSuppressed())
                   .singleElement()
                   .isInstanceOf(FailedBatchReplayTimeBudgetSpentException.class)
                   .satisfies(budgetSpent -> assertThat(budgetSpent.getMessage()).contains("global-event-order 3", "3 remaining event(s)")));

        // The retry of the event that was running when the budget ran out still happened; nothing after it was tried
        assertThat(forwarded).containsExactly(events.get(0), events.get(1), events.get(1));
    }

    @Test
    void reports_a_spent_budget_as_a_failure_when_no_event_failed_so_the_skip_does_not_pass_for_a_whole_forward() {
        var forwarded = new ArrayList<PersistedEvent>();
        var events    = events(4);

        assertThatThrownBy(() -> processor().forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            clock.addAndGet(BUDGET_NANOS / 2);
        })).isInstanceOf(FailedBatchReplayTimeBudgetSpentException.class)
           .hasMessageContaining("global-event-order 3")
           .hasMessageContaining("2 remaining event(s)");

        assertThat(forwarded).containsExactly(events.get(0), events.get(1));
    }

    @Test
    void forwards_only_the_first_event_when_an_override_sets_a_zero_budget() {
        var forwarded = new ArrayList<PersistedEvent>();
        var events    = events(3);

        assertThatThrownBy(() -> processor(Duration.ZERO).forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), forwarded::add))
                .isInstanceOf(FailedBatchReplayTimeBudgetSpentException.class)
                .hasMessageContaining("global-event-order 2")
                .hasMessageContaining("2 remaining event(s)");

        assertThat(forwarded).containsExactly(events.get(0));
    }

    @Test
    void forwards_only_the_first_event_when_an_override_sets_a_negative_budget() {
        var forwarded = new ArrayList<PersistedEvent>();
        var events    = events(3);

        assertThatThrownBy(() -> processor(Duration.ofSeconds(Long.MIN_VALUE)).forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            clock.addAndGet(1);
        })).isInstanceOf(FailedBatchReplayTimeBudgetSpentException.class);

        assertThat(forwarded).containsExactly(events.get(0));
    }

    @Test
    void tries_every_event_when_an_override_sets_a_budget_too_large_to_count_in_nanoseconds() {
        var forwarded = new ArrayList<PersistedEvent>();
        var events    = events(4);

        var allForwarded = processor(Duration.ofSeconds(Long.MAX_VALUE)).forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            // Far past the default budget on every forward: an unbounded budget never runs out
            clock.addAndGet(BUDGET_NANOS * 1_000);
        });

        assertThat(allForwarded).isTrue();
        assertThat(forwarded).containsExactlyElementsOf(events);
    }

    @Test
    void tries_every_event_when_each_fails_with_an_io_failure_inside_the_budget() {
        var forwarded = new ArrayList<PersistedEvent>();
        var events    = events(4);
        var failures  = new ArrayList<RuntimeException>();

        assertThatThrownBy(() -> processor().forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            clock.addAndGet(BUDGET_NANOS / 100);
            var failure = new RuntimeException("Could not forward", new ConnectException("Connection refused"));
            failures.add(failure);
            throw failure;
        })).isSameAs(failures.get(1))
           .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(failures.get(0), failures.get(3), failures.get(5), failures.get(7)));

        assertThat(forwarded).containsExactly(events.get(0), events.get(0), events.get(1), events.get(1), events.get(2), events.get(2), events.get(3), events.get(3));
    }

    @Test
    void keeps_an_event_whose_connection_failure_does_not_repeat_on_its_retry() {
        var connectionFailure = new RuntimeException("Could not forward", new ConnectException("Connection refused"));
        var forwarded         = new ArrayList<PersistedEvent>();
        var events            = events(4);

        var allForwarded = processor().forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            if (forwarded.size() == 1) {
                throw connectionFailure;
            }
        });

        assertThat(allForwarded).isTrue();
        assertThat(forwarded).containsExactly(events.get(0), events.get(0), events.get(1), events.get(2), events.get(3));
    }

    @Test
    void keeps_consecutive_events_that_each_draw_a_stale_connection_once() {
        var forwarded = new ArrayList<PersistedEvent>();
        var events    = events(4);

        var allForwarded = processor().forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            var firstAttempt = !forwarded.contains(event);
            forwarded.add(event);
            if (firstAttempt) {
                // A database restart left every pooled connection stale: each event draws one on its first forward
                throw new RuntimeException("Could not forward", new ConnectionException(new SQLException("Connection is gone", "08006")));
            }
        });

        assertThat(allForwarded).isTrue();
        assertThat(forwarded).containsExactly(events.get(0), events.get(0), events.get(1), events.get(1), events.get(2), events.get(2), events.get(3), events.get(3));
    }

    @Test
    void attaches_the_first_attempts_io_failure_to_a_retry_that_fails_differently() {
        var firstAttemptFailure = new RuntimeException("Could not forward", new ConnectException("Connection reset"));
        var retryFailure        = new IllegalStateException("event 1 cannot be forwarded");
        var forwarded           = new ArrayList<PersistedEvent>();
        var events              = events(3);

        assertThatThrownBy(() -> processor().forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            forwarded.add(event);
            if (forwarded.size() == 1) {
                throw firstAttemptFailure;
            }
            if (forwarded.size() == 2) {
                throw retryFailure;
            }
        })).isSameAs(retryFailure)
           .satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(firstAttemptFailure));

        assertThat(forwarded).containsExactly(events.get(0), events.get(0), events.get(1), events.get(2));
    }

    @Test
    void does_not_attach_a_failure_to_itself_when_both_attempts_throw_the_same_instance() {
        var connectionFailure = new RuntimeException("Could not forward", new ConnectException("Connection refused"));
        var events            = events(2);

        assertThatThrownBy(() -> processor().forwardFailedBatchOneEventPerUnitOfWork(events, new RuntimeException("batch failed"), SubscriptionErrorPolicy.skip(), event -> {
            if (event.equals(events.get(0))) {
                throw connectionFailure;
            }
        })).isSameAs(connectionFailure)
           .satisfies(thrown -> assertThat(thrown.getSuppressed()).isEmpty());
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

    private AbstractEventProcessor processor() {
        return processor(Optional.of(SubscriptionErrorPolicy.skip()));
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private AbstractEventProcessor processor(Optional<SubscriptionErrorPolicy> ownPolicy) {
        return processor(ownPolicy, null);
    }

    private AbstractEventProcessor processor(Duration budgetOverride) {
        return processor(Optional.of(SubscriptionErrorPolicy.skip()), budgetOverride);
    }

    /**
     * @param budgetOverride what the processor's {@link AbstractEventProcessor#getFailedBatchReplayTimeBudget()} returns,
     *                       or {@code null} for the default
     */
    @SuppressWarnings({"unchecked", "OptionalUsedAsFieldOrParameterType"})
    private AbstractEventProcessor processor(Optional<SubscriptionErrorPolicy> ownPolicy, Duration budgetOverride) {
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

        var processor = budgetOverride == null
                        ? new ProcessorSubscriptionErrorPolicyTest.PolicyEventProcessor(subscriptionManager, inboxes, ownPolicy)
                        : new ProcessorSubscriptionErrorPolicyTest.PolicyEventProcessor(subscriptionManager, inboxes, ownPolicy) {
                            @Override
                            protected Duration getFailedBatchReplayTimeBudget() {
                                return budgetOverride;
                            }
                        };
        processor.nanoTime = clock::get;
        return processor;
    }
}
