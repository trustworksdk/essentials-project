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

import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Phase 3 §2.4, §4.2: the reader loop holds one connection per {@link EventLog} while any subscription exists and none
 * otherwise; a subscription holds none. Twenty subscriptions on a pool of three deliver everything; stopping them all
 * leaves no active connection and ends the loop; a new subscription starts the reader again, at the head. Resource
 * assertions, no mutant.
 */
class EventLogPoolIT extends AbstractSubscriptionIT {
    private static final int SUBSCRIPTIONS = 20;

    private HikariDataSource pool;

    @BeforeEach
    void poolOfThree() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(3);
        config.setMinimumIdle(0);
        config.setPoolName("eventlog-pool-it");
        pool = new HikariDataSource(config);
        restartEventLog(pool, FAST);
    }

    @AfterEach
    void closePool() {
        eventLog.stop();
        pool.close();
    }

    @Test
    void twenty_subscriptions_on_a_pool_of_three_deliver_everything_and_hold_no_connection() throws Exception {
        commitAppend("before", 10);
        var recorders     = new ArrayList<Recorder>();
        var subscriptions = new ArrayList<FreshSubscription>();
        for (var i = 0; i < SUBSCRIPTIONS; i++) {
            var recorder = new Recorder();
            recorders.add(recorder);
            subscriptions.add(subscribe("sub-" + i, recorder, TestSeams.NONE));
        }
        for (var i = 0; i < 5; i++) {
            commitAppend("during-" + i, 4);
        }
        var committed = committedOrders();
        assertThat(committed).hasSize(30);
        for (var recorder : recorders) {
            await().atMost(WAIT).untilAsserted(() -> assertThat(recorder.deliveredOrders()).containsAll(committed));
            assertThat(perAggregateOrderViolations(recorder.delivered)).isEmpty();
        }
        awaitTrue(() -> subscriptions.stream().allMatch(FreshSubscription::isIdle));
        assertThat(pool.getHikariPoolMXBean().getActiveConnections()).as("only the reader loop holds a connection while idle").isEqualTo(1);

        subscriptions.forEach(FreshSubscription::stop);
        assertThat(eventLog.readerLoop().awaitStopped(WAIT)).isTrue();
        assertThat(eventLog.readerLoop().isRunning()).isFalse();
        await().atMost(WAIT).until(() -> pool.getHikariPoolMXBean().getActiveConnections() == 0);

        var rowsBefore = reads.rowsRead.get();
        var recorder   = new Recorder();
        var restarted  = subscribe("late", recorder, TestSeams.NONE);
        await().atMost(WAIT).untilAsserted(() -> assertThat(recorder.deliveredOrders()).containsAll(committed));
        awaitTrue(restarted::isIdle);
        assertThat(eventLog.reader(TYPE).state().cursor()).isEqualTo(Collections.max(committed));
        assertThat(reads.rowsRead.get()).as("the restarted reader starts at the head and reads no old row").isEqualTo(rowsBefore);
        var tail = commitAppend("tail", 1);
        awaitDelivered(recorder, tail);
        assertThat(reads.rowsRead.get()).isEqualTo(rowsBefore + 1);
    }

    private Set<Long> committedOrders() throws Exception {
        var orders = new TreeSet<Long>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT global_order FROM " + tables.eventTable())) {
            while (resultSet.next()) {
                orders.add(resultSet.getLong(1));
            }
        }
        return orders;
    }
}
