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
import dk.trustworks.essentials.components.queue.shardowned.spi.Message;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one {@link PostgresqlMessageQueue#depth()} read costs, in statements and in wall time, at an empty queue and
 * under backlog. A depth read is what admin endpoints, health checks, gauges and inbox catch-up loops poll, so it is
 * paid once per poll per queue.
 * <p>
 * Statements are counted from {@code pg_stat_statements} when the extension is available, otherwise only timed.
 * Opt-in ({@code -Dbenchmark.run=true}): it prints, it does not gate - beyond the counts being right.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class ShardOwnedDepthCostIT {

    private static final Logger log = LoggerFactory.getLogger(ShardOwnedDepthCostIT.class);

    private static final short QUEUE_ID     = 1;
    private static final int   SHARD_COUNT  = 4;
    private static final int   PAYLOAD_TYPE = 1;
    private static final int   READS        = 200;

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create("shared_preload_libraries=pg_stat_statements");

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void what_one_depth_read_costs() throws Exception {
        log.warn("=== depth(): cost per read ({} reads per backlog) ===", READS);
        log.warn(String.format("%-10s %12s %10s %10s %10s", "backlog", "stmts/read", "p50 ms", "p99 ms", "mean ms"));
        for (var backlog : new int[]{0, 10_000, 100_000}) {
            measure(backlog);
        }
    }

    private void measure(int backlogPerLane) throws Exception {
        // A fresh table per shape, so one backlog's dead tuples and statistics do not price the next.
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "depth-cost")) {
            var batch = new ArrayList<Message>();
            for (var index = 0; index < backlogPerLane; index++) {
                var payload = Integer.toString(index).getBytes(StandardCharsets.UTF_8);
                batch.add(Message.of(payload, PAYLOAD_TYPE));
                batch.add(Message.ordered(payload, PAYLOAD_TYPE, "key-" + (index % 1_000), index / 1_000));
                if (batch.size() >= 2_000) {
                    queue.enqueue(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                queue.enqueue(batch);
            }
            analyze();

            var expected = queue.depth();
            assertThat(expected.unordered()).isEqualTo(backlogPerLane);
            assertThat(expected.ordered()).isEqualTo(backlogPerLane);

            var statementsBefore = statementCount();
            var nanos            = new long[READS];
            for (var read = 0; read < READS; read++) {
                var started = System.nanoTime();
                var depth   = queue.depth();
                nanos[read] = System.nanoTime() - started;
                assertThat(depth.unordered() + depth.ordered()).isEqualTo(2L * backlogPerLane);
            }
            var statementsPerRead = statementsBefore < 0 ? Double.NaN : (statementCount() - statementsBefore) / (double) READS;
            Arrays.sort(nanos);
            log.warn(String.format("%-10d %12.1f %10.3f %10.3f %10.3f", backlogPerLane, statementsPerRead,
                                   nanos[READS / 2] / 1e6, nanos[(int) (READS * 0.99)] / 1e6,
                                   Arrays.stream(nanos).average().orElse(0) / 1e6));
        }
    }

    private void analyze() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ANALYZE");
        }
    }

    /** Calls of every statement touching the queue tables, or -1 without pg_stat_statements. */
    private long statementCount() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
            try (var resultSet = statement.executeQuery(
                    "SELECT coalesce(sum(calls), 0) FROM pg_stat_statements WHERE query ILIKE '%shard_queue_%' AND query ILIKE '%count(%'")) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (java.sql.SQLException e) {
            return -1;
        }
    }
}
