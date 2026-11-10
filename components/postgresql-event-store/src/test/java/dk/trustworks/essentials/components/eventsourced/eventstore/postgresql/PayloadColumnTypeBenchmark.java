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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures what the PostgreSQL payload column type costs on the database side, independent of the client-side
 * serializer: the same JSON bytes are written to and read from a table shaped like an event-stream table whose
 * payload and metadata columns are {@code jsonb}, {@code json} or {@code bytea}.
 * <p>
 * Plain JDBC, no Essentials event store, no client-side (de)serialization in the measured path. The server-side
 * execution time per statement is read from {@code pg_stat_statements}, and WAL and table size per row from the
 * database, and all three are printed when each trial ends (lines prefixed {@code [server]}).
 * <p>
 * Run (from components/postgresql-event-store, after a test-compile):
 * <pre>{@code
 * java -cp target/test-classes:target/classes:<test classpath> org.openjdk.jmh.Main PayloadColumnTypeBenchmark
 * }</pre>
 */
@BenchmarkMode(Mode.Throughput)
@Fork(1)
@Warmup(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(10)
public class PayloadColumnTypeBenchmark {
    private static final int EVENTS_PER_AGGREGATE    = 20;
    private static final int PREPOPULATED_AGGREGATES = 2_000;

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }

    @State(Scope.Benchmark)
    public static class DbState {
        @Param({"jsonb", "json", "bytea"})
        public String columnType;

        /** small ~ today's benchmark events, medium ~ an order with 10 lines, large ~ 100 lines (TOASTed) */
        @Param({"small", "medium", "large"})
        public String payloadSize;

        private PostgreSQLContainer container;
        private HikariDataSource    ds;
        private String              insertSql;
        private String              selectSql;
        byte[]                      payloadBytes;
        String                      payloadString;
        byte[]                      metadataBytes;
        String                      metadataString;
        final AtomicLong            aggregateSequence = new AtomicLong();
        final AtomicLong            insertedRows      = new AtomicLong();
        private String              walStartLsn;
        private long                rowsAtStart;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            container = new PostgreSQLContainer("postgres:18.4")
                    .withDatabaseName("payload-bench")
                    .withUsername("test-user")
                    .withPassword("secret-password")
                    .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements", "-c", "pg_stat_statements.track=all");
            container.start();
            var config = new HikariConfig();
            config.setJdbcUrl(container.getJdbcUrl());
            config.setUsername(container.getUsername());
            config.setPassword(container.getPassword());
            config.setMaximumPoolSize(10);
            ds = new HikariDataSource(config);

            var mapper = EssentialsObjectMappers.createJackson3ObjectMapper();
            payloadString = mapper.writeValueAsString(payload(payloadSize));
            payloadBytes = payloadString.getBytes(StandardCharsets.UTF_8);
            metadataString = mapper.writeValueAsString(Map.of("Key1", "Value1", "Key2", "Value2"));
            metadataBytes = metadataString.getBytes(StandardCharsets.UTF_8);

            // Same shape as the SeparateTablePerAggregateTypePersistenceStrategy table, minus columns irrelevant here
            try (var c = ds.getConnection(); var s = c.createStatement()) {
                s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
                s.execute("CREATE TABLE bench_events (\n" +
                                  "  global_order   BIGSERIAL PRIMARY KEY,\n" +
                                  "  aggregate_id   TEXT NOT NULL,\n" +
                                  "  event_order    BIGINT NOT NULL,\n" +
                                  "  event_type     TEXT NOT NULL,\n" +
                                  "  event_id       TEXT NOT NULL,\n" +
                                  "  event_payload  " + columnType + " NOT NULL,\n" +
                                  "  event_metadata " + columnType + " NOT NULL,\n" +
                                  "  timestamp      TIMESTAMPTZ NOT NULL,\n" +
                                  "  UNIQUE (aggregate_id, event_order))");
            }
            var cast = columnType.equals("bytea") ? "" : "::" + columnType;
            insertSql = "INSERT INTO bench_events (aggregate_id, event_order, event_type, event_id, event_payload, event_metadata, timestamp) " +
                    "VALUES (?, ?, 'OrderEvent', ?, ?" + cast + ", ?" + cast + ", now())";
            selectSql = "SELECT * FROM bench_events WHERE aggregate_id = ? ORDER BY event_order";

            for (var i = 0; i < PREPOPULATED_AGGREGATES; i++) {
                insertAggregate();
            }
            try (var c = ds.getConnection(); var s = c.createStatement()) {
                s.execute("VACUUM ANALYZE bench_events");
                s.execute("SELECT pg_stat_statements_reset()");
                try (var rs = s.executeQuery("SELECT pg_current_wal_lsn()::text")) {
                    rs.next();
                    walStartLsn = rs.getString(1);
                }
            }
            rowsAtStart = insertedRows.get();
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            try (var c = ds.getConnection(); var s = c.createStatement()) {
                var rows = insertedRows.get() - rowsAtStart;
                long walBytes;
                try (var rs = s.executeQuery("SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '" + walStartLsn + "'::pg_lsn)::bigint")) {
                    rs.next();
                    walBytes = rs.getLong(1);
                }
                long tableBytes;
                long totalRows;
                try (var rs = s.executeQuery("SELECT pg_total_relation_size('bench_events'), (SELECT count(*) FROM bench_events)")) {
                    rs.next();
                    tableBytes = rs.getLong(1);
                    totalRows = rs.getLong(2);
                }
                System.out.printf("%n[server] columnType=%s payloadSize=%s payloadBytes=%d%n", columnType, payloadSize, payloadBytes.length);
                System.out.printf("[server] walBytesPerInsertedRow=%.0f (rows inserted while measuring=%d)%n", rows == 0 ? 0.0 : (double) walBytes / rows, rows);
                System.out.printf("[server] tableBytesPerRow=%.0f (rows=%d)%n", (double) tableBytes / totalRows, totalRows);
                try (var rs = s.executeQuery("SELECT left(regexp_replace(query, '\\s+', ' ', 'g'), 40), calls, mean_exec_time, total_exec_time " +
                                                     "FROM pg_stat_statements WHERE query LIKE 'INSERT INTO bench_events%' OR query LIKE 'SELECT * FROM bench_events%' " +
                                                     "ORDER BY total_exec_time DESC")) {
                    while (rs.next()) {
                        System.out.printf("[server] stmt=%-40s calls=%d meanExecMs=%.4f totalExecMs=%.0f%n",
                                          rs.getString(1), rs.getLong(2), rs.getDouble(3), rs.getDouble(4));
                    }
                }
            }
            ds.close();
            container.stop();
        }

        void insertAggregate() throws SQLException {
            var aggregateId = "agg-" + aggregateSequence.incrementAndGet();
            try (var c = ds.getConnection()) {
                c.setAutoCommit(false);
                try (var ps = c.prepareStatement(insertSql)) {
                    for (var order = 0; order < EVENTS_PER_AGGREGATE; order++) {
                        ps.setString(1, aggregateId);
                        ps.setLong(2, order);
                        ps.setString(3, UUID.randomUUID().toString());
                        if (columnType.equals("bytea")) {
                            ps.setBytes(4, payloadBytes);
                            ps.setBytes(5, metadataBytes);
                        } else {
                            ps.setString(4, payloadString);
                            ps.setString(5, metadataString);
                        }
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
            }
            insertedRows.addAndGet(EVENTS_PER_AGGREGATE);
        }

        void readAggregate(Blackhole bh) throws SQLException {
            var aggregateId = "agg-" + ThreadLocalRandom.current().nextLong(1, PREPOPULATED_AGGREGATES + 1);
            try (var c = ds.getConnection(); var ps = c.prepareStatement(selectSql)) {
                ps.setString(1, aggregateId);
                try (var rs = ps.executeQuery()) {
                    var rows = 0;
                    while (rs.next()) {
                        if (columnType.equals("bytea")) {
                            bh.consume(rs.getBytes("event_payload"));
                            bh.consume(rs.getBytes("event_metadata"));
                        } else {
                            bh.consume(rs.getString("event_payload"));
                            bh.consume(rs.getString("event_metadata"));
                        }
                        rows++;
                    }
                    if (rows != EVENTS_PER_AGGREGATE) {
                        throw new IllegalStateException("Read " + rows + " rows for " + aggregateId);
                    }
                }
            }
        }

        private static Map<String, Object> payload(String size) {
            var lines = switch (size) {
                case "small" -> 0;
                case "medium" -> 10;
                case "large" -> 100;
                default -> throw new IllegalArgumentException(size);
            };
            var payload = new LinkedHashMap<String, Object>();
            payload.put("orderId", UUID.randomUUID().toString());
            payload.put("customerId", UUID.randomUUID().toString());
            payload.put("orderNumber", 123456);
            var orderLines = new ArrayList<Map<String, Object>>();
            var random     = new Random(42);
            for (var i = 0; i < lines; i++) {
                var line = new LinkedHashMap<String, Object>();
                line.put("productId", UUID.randomUUID().toString());
                line.put("description", "Product description " + random.nextInt(1_000_000) + " with some realistic free text");
                line.put("quantity", random.nextInt(10) + 1);
                line.put("unitPrice", Map.of("amount", String.valueOf(random.nextInt(100_000) / 100.0), "currency", "DKK"));
                orderLines.add(line);
            }
            if (lines > 0) {
                payload.put("lines", orderLines);
            }
            return payload;
        }
    }

    /** One transaction appending {@value EVENTS_PER_AGGREGATE} events to a new aggregate - the append path */
    @Benchmark
    public void insert(DbState state) throws SQLException {
        state.insertAggregate();
    }

    /** Load the {@value EVENTS_PER_AGGREGATE} events of one existing aggregate - the fetchStream path */
    @Benchmark
    public void read(DbState state, Blackhole bh) throws SQLException {
        state.readAggregate(bh);
    }
}
