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

package dk.trustworks.essentials.examples.perflab.harness;

import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A point-in-time reading of the PostgreSQL counters that matter when judging a queue design, so
 * two readings can be subtracted to give the cost of whatever ran between them.
 * <p>
 * The headline metrics this exists to produce are <b>WAL bytes per message</b> and <b>dead tuples
 * per message</b>. Throughput alone hides both: a design can look fast for the length of a
 * benchmark while generating the write amplification and vacuum debt that make it slow an hour
 * later. The soak scenario is what catches that, and these counters are what it reads.
 * <p>
 * Counter columns are read generically through {@link ResultSetMetaData} rather than by name.
 * {@code pg_stat_wal} and {@code pg_stat_database} have both gained and lost columns across recent
 * PostgreSQL majors, and a harness that hard-codes column names fails on the next upgrade for no
 * good reason. Whatever numeric columns exist are captured; whatever does not exist is absent from
 * the delta rather than fatal.
 */
public final class PgSnapshot {
    private static final Logger log = LoggerFactory.getLogger(PgSnapshot.class);

    private final long              walBytes;
    private final Map<String, Long> counters;

    private PgSnapshot(long walBytes, Map<String, Long> counters) {
        this.walBytes = walBytes;
        this.counters = Map.copyOf(counters);
    }

    /**
     * Capture the current counters.
     *
     * @param dataSource the database to read from
     * @param tables     tables whose per-relation statistics and sizes should be captured
     */
    public static PgSnapshot capture(DataSource dataSource, Collection<String> tables) {
        requireNonNull(dataSource, "No dataSource provided");
        requireNonNull(tables, "No tables collection provided");

        var counters = new LinkedHashMap<String, Long>();
        try (var connection = dataSource.getConnection()) {
            // pg_lsn arithmetic yields a numeric byte count and has been stable since 9.4, which
            // makes it a far better WAL measure than pg_stat_wal's shifting column set.
            long walBytes = scalarLong(connection, "SELECT (pg_current_wal_lsn() - '0/0'::pg_lsn)::bigint").orElse(0L);

            collectNumericRow(connection,
                              "SELECT * FROM pg_stat_database WHERE datname = current_database()",
                              "db.",
                              counters);
            collectNumericRow(connection, "SELECT * FROM pg_stat_wal", "wal.", counters);

            for (var table : tables) {
                collectTableStats(connection, table, counters);
            }
            return new PgSnapshot(walBytes, counters);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to capture PostgreSQL statistics snapshot", e);
        }
    }

    /**
     * Read a single captured value as an absolute reading rather than a difference.
     * <p>
     * Some of what {@code pg_stat_*} exposes is a gauge, not a counter — {@code n_dead_tup} is the
     * important one, because autovacuum drives it back down and differencing two readings of it
     * yields a negative "dead tuples created". Gauges must be read here; only counters belong in
     * {@link #deltaFrom}.
     */
    public long gauge(String key) {
        return counters.getOrDefault(requireNonNull(key, "No key provided"), 0L);
    }

    /**
     * Counters that this snapshot has and {@code before} did not, minus what {@code before} had.
     * Only counters present in both are reported — a column that appeared or vanished between
     * readings cannot be differenced meaningfully.
     */
    public Map<String, Long> deltaFrom(PgSnapshot before) {
        requireNonNull(before, "No before snapshot provided");
        var delta = new LinkedHashMap<String, Long>();
        delta.put("wal.bytesWritten", walBytes - before.walBytes);
        counters.forEach((key, value) -> {
            var previous = before.counters.get(key);
            if (previous != null) {
                delta.put(key, value - previous);
            }
        });
        return delta;
    }

    /**
     * Settings that dominate every other measurement and must therefore be identical across the two
     * arms of an A/B comparison. Recorded into the result JSON so a run whose numbers look wrong can
     * be checked against the run it is being compared with, rather than argued about.
     */
    public static Map<String, String> captureEnvironment(DataSource dataSource) {
        requireNonNull(dataSource, "No dataSource provided");
        var settings = new LinkedHashMap<String, String>();
        var names = List.of("server_version",
                            "synchronous_commit",
                            "fsync",
                            "full_page_writes",
                            "wal_level",
                            "wal_compression",
                            "shared_buffers",
                            "max_wal_size",
                            "checkpoint_timeout",
                            "autovacuum",
                            "max_connections",
                            "effective_cache_size",
                            "random_page_cost");
        try (var connection = dataSource.getConnection()) {
            for (var name : names) {
                try (var statement = connection.createStatement();
                     var resultSet = statement.executeQuery("SHOW " + name)) {
                    if (resultSet.next()) {
                        settings.put("pg." + name, resultSet.getString(1));
                    }
                } catch (SQLException e) {
                    log.debug("Setting '{}' not readable on this server: {}", name, e.getMessage());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to capture PostgreSQL environment", e);
        }
        var runtime = Runtime.getRuntime();
        // The cgroup budget, not just what the kernel advertises. These two disagreeing is exactly
        // what made this lab's saturated measurements unreproducible: everything sizes its pools
        // against availableProcessors while the quota throttles it at the period boundary.
        settings.put("cgroup.cpuMax", readFirstLine("/sys/fs/cgroup/cpu.max"));
        settings.put("cgroup.memoryMax", readFirstLine("/sys/fs/cgroup/memory.max"));
        settings.put("cpu.affinity", readCpusAllowed());
        settings.put("jvm.version", System.getProperty("java.version"));
        settings.put("jvm.vendor", System.getProperty("java.vendor"));
        settings.put("os.name", System.getProperty("os.name"));
        settings.put("os.arch", System.getProperty("os.arch"));
        settings.put("cpu.availableProcessors", Integer.toString(runtime.availableProcessors()));
        settings.put("jvm.maxMemoryBytes", Long.toString(runtime.maxMemory()));
        return settings;
    }

    private static String readFirstLine(String path) {
        try {
            return java.nio.file.Files.readAllLines(java.nio.file.Path.of(path)).getFirst().trim();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String readCpusAllowed() {
        try {
            return java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status")).stream()
                                      .filter(line -> line.startsWith("Cpus_allowed_list:"))
                                      .map(line -> line.substring("Cpus_allowed_list:".length()).trim())
                                      .findFirst()
                                      .orElse("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void collectTableStats(Connection connection, String table, Map<String, Long> into) throws SQLException {
        collectNumericRow(connection,
                          "SELECT * FROM pg_stat_user_tables WHERE relname = '" + table + "'",
                          "table." + table + ".",
                          into);
        scalarLong(connection, "SELECT pg_total_relation_size('" + table + "')")
                .ifPresent(size -> into.put("table." + table + ".totalRelationSizeBytes", size));
        scalarLong(connection, "SELECT pg_indexes_size('" + table + "')")
                .ifPresent(size -> into.put("table." + table + ".indexesSizeBytes", size));
    }

    private static void collectNumericRow(Connection connection, String sql, String prefix, Map<String, Long> into) {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return;
            }
            var metaData = resultSet.getMetaData();
            for (var column = 1; column <= metaData.getColumnCount(); column++) {
                var value = resultSet.getObject(column);
                if (value instanceof Number number) {
                    into.put(prefix + metaData.getColumnLabel(column), number.longValue());
                }
            }
        } catch (SQLException e) {
            // A view that does not exist on this major version is expected, not exceptional.
            log.debug("Skipping statistics query [{}]: {}", sql, e.getMessage());
        }
    }

    private static Optional<Long> scalarLong(Connection connection, String sql) {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? Optional.of(resultSet.getLong(1)) : Optional.empty();
        } catch (SQLException e) {
            log.debug("Skipping scalar query [{}]: {}", sql, e.getMessage());
            return Optional.empty();
        }
    }
}
