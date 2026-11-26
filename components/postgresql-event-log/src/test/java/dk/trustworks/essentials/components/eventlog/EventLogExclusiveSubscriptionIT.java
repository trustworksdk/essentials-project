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
package dk.trustworks.essentials.components.eventlog;

import org.junit.jupiter.api.*;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.*;

/**
 * Smoke tests: an exclusive subscription claims
 * its lease, delivers and releases it on stop; a second event log, standing by, takes over after the first stops. The
 * invariant ITs (I5, I6, I16, takeovers under failure; see docs/design/event-log.md §11) are the other lease ITs.
 */
class EventLogExclusiveSubscriptionIT extends AbstractEventLogIT {
    static final String   TYPE = "Orders";
    static final String   SUB  = "projection";
    static final Duration WAIT = Duration.ofSeconds(30);

    /** The test lease timings: 2 s lease, 500 ms renewal, 1 s local deadline, 1 s statement timeout */
    static final EventLogSettings SETTINGS = EventLogSettings.defaults()
                                                             .withBackstopInterval(Duration.ofMillis(25))
                                                             .withProbeBackoff(Duration.ofMillis(25), Duration.ofMillis(100))
                                                             .withProgressSaveInterval(Duration.ZERO)
                                                             .withLease(Duration.ofSeconds(2), Duration.ofMillis(500), Duration.ofSeconds(1))
                                                             .withLeaseStatementTimeout(Duration.ofSeconds(1));

    private final List<EventLog> eventLogs = new ArrayList<>();

    @AfterEach
    void stopEventLogs() {
        eventLogs.forEach(EventLog::stop);
    }

    private EventLog eventLog(String nodeName) throws SQLException {
        var eventLog = EventLog.builder().setDataSource(dataSource("")).setSettings(SETTINGS.withNodeName(nodeName)).build();
        eventLog.start();
        eventLog.registerAggregateType(TYPE);
        eventLogs.add(eventLog);
        return eventLog;
    }

    private void append(EventLog eventLog, String aggregateId, int count) throws SQLException {
        appendCommitted(eventLog, AppendRequest.withoutExpectedOrder(TYPE, aggregateId, events("OrderPlaced", count)));
        eventLog.afterCommit(List.of(TYPE));
    }

    private record Row(Long fence, String owner, Long until, long resumeFrom, boolean exclusive) {
    }

    private Row row() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT lease_fence, lease_owner, (lease_until IS NOT NULL)::int, resume_from, exclusive FROM el_progress " +
                                                                 "WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ''")) {
            statement.setString(1, SUB);
            statement.setString(2, TYPE);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                var fence = resultSet.getLong(1);
                var noFence = resultSet.wasNull();
                return new Row(noFence ? null : fence, resultSet.getString(2), resultSet.getInt(3) == 1 ? 1L : null, resultSet.getLong(4), resultSet.getBoolean(5));
            }
        }
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    @Test
    void an_exclusive_subscription_claims_delivers_and_releases() throws Exception {
        var eventLog  = eventLog("node-a");
        var delivered = new CopyOnWriteArrayList<Long>();
        append(eventLog, "order-1", 10);

        var subscription = Definitions.internal(eventLog.subscribe(Definitions.definition(SUB, TYPE, null, 1, null, null), event -> delivered.add(event.globalOrder())));
        await(() -> delivered.size() == 10, "the first 10 events");
        append(eventLog, "order-2", 5);
        await(() -> delivered.size() == 15, "15 events");
        assertThat(delivered).isSorted().doesNotHaveDuplicates();

        var owned = row();
        assertThat(owned.exclusive()).isTrue();
        assertThat(owned.fence()).isNotNull();
        assertThat(owned.owner()).startsWith("node-a/").isEqualTo(eventLog.leaseOwner());
        assertThat(owned.until()).isNotNull();
        assertThat(subscription.lease().state()).isEqualTo(Lease.State.OWNED);

        subscription.stop();

        var released = row();
        assertThat(released.owner()).as("owner after release").isNull();
        assertThat(released.until()).as("lease_until after release").isNull();
        assertThat(released.fence()).as("the fence is kept").isEqualTo(owned.fence());
        assertThat(released.resumeFrom()).as("the release saved the resume point").isEqualTo(16);
    }

    @Test
    void a_second_event_log_takes_over_after_the_first_stops() throws Exception {
        var first          = eventLog("node-a");
        var second         = eventLog("node-b");
        var deliveredFirst = new CopyOnWriteArrayList<Long>();
        var deliveredSecond = new CopyOnWriteArrayList<Long>();
        append(first, "order-1", 10);

        var a = Definitions.internal(first.subscribe(Definitions.definition(SUB, TYPE, null, 1, null, null), event -> deliveredFirst.add(event.globalOrder())));
        await(() -> deliveredFirst.size() == 10, "the owner's 10 events");
        var b = Definitions.internal(second.subscribe(Definitions.definition(SUB, TYPE, null, 1, null, null), event -> deliveredSecond.add(event.globalOrder())));
        // Several claim passes of the standby go by: it stays standby while the owner renews
        Thread.sleep(SETTINGS.leaseDuration().toMillis() + SETTINGS.leaseRenewalInterval().toMillis());
        assertThat(b.phase()).isEqualTo(Subscription.Phase.STANDBY);
        assertThat(deliveredSecond).isEmpty();
        var firstFence = row().fence();
        assertThat(row().owner()).isEqualTo(first.leaseOwner());

        a.stop();
        await(() -> b.lease().state() == Lease.State.OWNED, "the standby's claim");
        var taken = row();
        assertThat(taken.owner()).isEqualTo(second.leaseOwner());
        assertThat(taken.fence()).as("a takeover draws a new fence").isGreaterThan(firstFence);

        append(second, "order-2", 5);
        await(() -> deliveredSecond.size() == 5, "the new owner's 5 events");
        assertThat(deliveredSecond).as("the new owner resumes from the released resume point: nothing replayed").allMatch(order -> order > 10);
        assertThat(deliveredFirst).hasSize(10);
    }

    @Test
    void a_non_exclusive_subscription_needs_a_non_blank_instance_name() throws Exception {
        var eventLog = eventLog("");
        assertThatThrownBy(() -> eventLog.subscribe(Definitions.definition(SUB, TYPE, "", 1, null, null), event -> {
        })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non-blank instance name");
    }
}
