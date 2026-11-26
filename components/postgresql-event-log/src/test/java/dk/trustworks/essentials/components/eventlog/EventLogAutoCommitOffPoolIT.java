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

import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * An application pool that hands out connections with auto-commit off ({@code spring.datasource.hikari.auto-commit=false},
 * a common Hibernate setting). The engine's writes outside its own transactions assumed auto-commit: a parked event's
 * row was never committed and vanished when the connection was returned, while the event counted as handled.
 */
class EventLogAutoCommitOffPoolIT extends AbstractSubscriptionIT {

    /** Every connection starts with auto-commit off, as such a pool hands it out */
    static final class AutoCommitOffDataSource extends PGSimpleDataSource {
        @Override
        public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            connection.setAutoCommit(false);
            return connection;
        }
    }

    @Test
    void a_periodic_subscription_on_an_auto_commit_off_pool_keeps_its_parked_event_and_its_resume_point() throws Exception {
        var source = new AutoCommitOffDataSource();
        source.setURL(dataSource.getURL());
        source.setUser(dataSource.getUser());
        source.setPassword(dataSource.getPassword());
        restartEventLog(source, FAST);

        var handled = new CountDownLatch(2);
        PeriodicHandler handler = event -> {
            handled.countDown();
            if (event.globalOrder() == 1) {
                throw new ParkEventException(event, "poison", new IllegalStateException("bad payload"));
            }
        };
        var subscription = subscribe("auto-commit-off", handler, TestSeams.NONE, 1);
        commitAppend("a", 1);
        commitAppend("b", 1);
        assertThat(handled.await(30, TimeUnit.SECONDS)).isTrue();

        await().atMost(WAIT).untilAsserted(() -> assertThat(queryLong("SELECT count(*) FROM el_parked_events WHERE subscriber = 'auto-commit-off'"))
                .as("the parked event's row was committed").isOne());
        subscription.stop();
        assertThat(queryLong("SELECT resume_from FROM el_progress WHERE subscriber = 'auto-commit-off'")).as("the final save was committed").isEqualTo(3);
    }
}
