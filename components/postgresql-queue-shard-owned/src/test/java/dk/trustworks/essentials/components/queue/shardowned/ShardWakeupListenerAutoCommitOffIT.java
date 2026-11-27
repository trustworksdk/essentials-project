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

package dk.trustworks.essentials.components.queue.shardowned;

import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tier 1 on a pool that hands out connections with auto-commit off ({@code spring.datasource.hikari.auto-commit=false},
 * a common Hibernate setting): LISTEN used to run in a transaction that never committed, so no notification arrived and
 * the owners fell back to the backstop poll, with nothing logged.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardWakeupListenerAutoCommitOffIT {
    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource autoCommitOff;
    private HikariDataSource autoCommitOn;

    private static HikariDataSource pool(boolean autoCommit) {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(2);
        config.setAutoCommit(autoCommit);
        return new HikariDataSource(config);
    }

    @BeforeEach
    void setUp() {
        autoCommitOff = pool(false);
        autoCommitOn = pool(true);
    }

    @AfterEach
    void tearDown() {
        autoCommitOff.close();
        autoCommitOn.close();
    }

    @Test
    void the_listener_on_an_auto_commit_off_pool_receives_notifications() throws Exception {
        var wakeup   = new ShardWakeup();
        var listener = new ShardWakeupListener(autoCommitOff, Map.of("1:u:0", wakeup));
        listener.start();
        try {
            var woken = false;
            for (int attempt = 0; attempt < 50 && !woken; attempt++) {
                try (var connection = autoCommitOn.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("SELECT pg_notify('" + ShardWakeupListener.CHANNEL + "', '1:u:0')");
                }
                woken = wakeup.await(100);
            }
            assertThat(woken).as("a notification reached the wake-up").isTrue();
            assertThat(listener.notificationsReceived()).isPositive();
        } finally {
            listener.stop();
        }
    }
}
