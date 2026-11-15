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

package dk.trustworks.essentials.components.eventlog.spike;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The PostgreSQL server a phase-2 spike runs against, as the phase-0 spikes' {@code SpikeServer} (module
 * {@code postgresql-event-store}, test code) does it: a fresh Testcontainers container with {@code pg_stat_statements}
 * preloaded (the repo's pinned image unless {@code -Dspike.eventlog.image}), or an external server given by
 * {@code -Dspike.jdbcUrl}, {@code -Dspike.user}, {@code -Dspike.password}, on which every instance creates its own
 * database and per-run superuser roles and drops them again. {@code -Dspike.serverCpuCommand} (external only) is a
 * shell command printing a cgroup v2 {@code cpu.stat} or a {@code /proc/stat} of the database host.
 * <p>
 * The application roles ({@code writer}, {@code reader}) are separate from the observing role, so
 * {@code pg_stat_statements} separates the write path from the read path and the measurement itself is excluded.
 * Not engine code; spikes only.
 */
final class SpikeDb implements AutoCloseable {
    static final String IMAGE = System.getProperty("spike.eventlog.image", EssentialsTestContainers.POSTGRES_IMAGE.asCanonicalNameString());

    private static final String  JDBC_URL           = System.getProperty("spike.jdbcUrl", "").trim();
    private static final String  USER               = System.getProperty("spike.user", "postgres");
    private static final String  PASSWORD           = System.getProperty("spike.password", "");
    private static final String  SERVER_CPU_COMMAND = System.getProperty("spike.serverCpuCommand", "").trim();
    private static final Pattern URL                = Pattern.compile("^jdbc:postgresql://([^/?]+)(/[^?]*)?(\\?.*)?$");
    private static final String  OBSERVER           = "observer";

    private final PostgreSQLContainer container;
    private final String              jdbcUrl;
    private final String              database;
    private final String              suffix;
    private final String              rolePassword;
    private final List<String>        roles = new ArrayList<>();
    final         Connection          observer;
    private       String              lsnAtReset;

    static boolean external() {
        return !JDBC_URL.isEmpty();
    }

    /**
     * @param maxConnections {@code max_connections} of a container; an external server keeps its own and must allow
     *                       what the spike opens
     */
    static SpikeDb start(String purpose, int maxConnections) throws SQLException {
        if (external()) {
            try (var admin = DriverManager.getConnection(JDBC_URL, USER, PASSWORD); var s = admin.createStatement()) {
                var preload = queryString(admin, "SELECT setting FROM pg_settings WHERE name = 'shared_preload_libraries'");
                if (!preload.contains("pg_stat_statements")) {
                    throw new IllegalStateException("pg_stat_statements is not in shared_preload_libraries on " + JDBC_URL);
                }
                var suffix   = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(new Random().nextInt(1296), 36);
                var database = "spike_" + purpose + "_" + suffix;
                s.execute("CREATE DATABASE " + database);
                var m = URL.matcher(JDBC_URL);
                if (!m.matches()) throw new IllegalArgumentException("unsupported -Dspike.jdbcUrl: " + JDBC_URL);
                var url = "jdbc:postgresql://" + m.group(1) + "/" + database + (m.group(3) == null ? "" : m.group(3));
                var db  = new SpikeDb(null, url, database, suffix, UUID.randomUUID().toString(), DriverManager.getConnection(url, USER, PASSWORD));
                db.createRoles();
                return db;
            }
        }
        var container = new PostgreSQLContainer(IMAGE).withDatabaseName(purpose).withUsername(OBSERVER).withPassword(OBSERVER)
                                                      .withCommand("postgres", "-c", "fsync=off", "-c", "shared_preload_libraries=pg_stat_statements",
                                                                   "-c", "pg_stat_statements.max=10000", "-c", "pg_stat_statements.track_utility=on",
                                                                   "-c", "max_connections=" + maxConnections);
        container.start();
        var db = new SpikeDb(container, container.getJdbcUrl(), purpose, "", null,
                             DriverManager.getConnection(container.getJdbcUrl(), OBSERVER, OBSERVER));
        db.createRoles();
        return db;
    }

    private SpikeDb(PostgreSQLContainer container, String jdbcUrl, String database, String suffix, String rolePassword, Connection observer) {
        this.container = container;
        this.jdbcUrl = jdbcUrl;
        this.database = database;
        this.suffix = suffix;
        this.rolePassword = rolePassword;
        this.observer = observer;
    }

    private void createRoles() throws SQLException {
        try (var s = observer.createStatement()) {
            s.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
            for (var logical : List.of("writer", "reader")) {
                s.execute("CREATE ROLE " + role(logical) + " LOGIN SUPERUSER PASSWORD '" + password(logical) + "'");
                roles.add(role(logical));
            }
        }
    }

    String role(String logical) {
        return container != null ? logical : logical + "_" + suffix;
    }

    String logical(String role) {
        return container != null || !role.endsWith("_" + suffix) ? role : role.substring(0, role.length() - suffix.length() - 1);
    }

    String password(String logical) {
        return container != null ? logical : rolePassword;
    }

    String describe() {
        try {
            return (container != null ? "container " + IMAGE + " (fsync=off)" : "EXTERNAL server " + JDBC_URL) + ", " + queryString(observer, "SELECT version()").split(",")[0]
                    + ", max_connections " + queryString(observer, "SHOW max_connections") + ", server CPU from " + cpuSource();
        } catch (SQLException e) {
            return "?";
        }
    }

    HikariDataSource pool(String logical, int size) {
        var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(role(logical));
        config.setPassword(password(logical));
        config.setMaximumPoolSize(size);
        config.setPoolName(logical + "-" + UUID.randomUUID().toString().substring(0, 4));
        return new HikariDataSource(config);
    }

    Connection connect(String logical) throws SQLException {
        return DriverManager.getConnection(jdbcUrl, role(logical), password(logical));
    }

    /** Client backends connected to this server, every database */
    long clientBackends() throws SQLException {
        return queryLong(observer, "SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend'");
    }

    void resetStatistics() throws SQLException {
        try (var s = observer.createStatement()) {
            s.execute("SELECT pg_stat_statements_reset()");
        }
        lsnAtReset = queryString(observer, "SELECT pg_current_wal_lsn()::text");
    }

    long walSinceReset() throws SQLException {
        return queryLong(observer, "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), '" + lsnAtReset + "'::pg_lsn)::bigint");
    }

    void checkpoint() throws SQLException {
        try (var s = observer.createStatement()) {
            s.execute("CHECKPOINT");
        }
    }

    record Statement(String role, String query, long calls, long rows, double totalExecMs, long walBytes) {
        boolean utility() {
            var q = query.trim().toLowerCase(Locale.ROOT);
            return q.startsWith("begin") || q.startsWith("commit") || q.startsWith("rollback") || q.startsWith("set ") || q.startsWith("show ")
                    || q.startsWith("reset") || q.startsWith("start transaction") || q.startsWith("savepoint") || q.startsWith("release");
        }
    }

    /** Every statement since {@link #resetStatistics()} of the application roles, most calls first */
    List<Statement> statements() throws SQLException {
        var rows = new ArrayList<Statement>();
        var sql = "SELECT r.rolname, s.query, s.calls, s.rows, s.total_exec_time, s.wal_bytes::bigint FROM pg_stat_statements s JOIN pg_roles r ON r.oid = s.userid "
                + "WHERE r.rolname IN ('" + role("writer") + "', '" + role("reader") + "') AND s.dbid = (SELECT oid FROM pg_database WHERE datname = current_database()) "
                + "ORDER BY s.calls DESC";
        try (var s = observer.createStatement(); var rs = s.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new Statement(logical(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getDouble(5), rs.getLong(6)));
            }
        }
        return rows;
    }

    // ------------------------------------------------------------------ server CPU

    String cpuSource() {
        if (container != null) return "container cgroup";
        return SERVER_CPU_COMMAND.isEmpty() ? "nowhere (external, no -Dspike.serverCpuCommand: NaN)" : "-Dspike.serverCpuCommand '" + SERVER_CPU_COMMAND + "'";
    }

    /** Server CPU used so far, µs; -1 when not available */
    long cpuMicros() {
        try {
            String out;
            if (container != null) {
                out = container.execInContainer("cat", "/sys/fs/cgroup/cpu.stat").getStdout();
            } else if (!SERVER_CPU_COMMAND.isEmpty()) {
                var process = new ProcessBuilder("/bin/sh", "-c", SERVER_CPU_COMMAND).start();
                try (var in = process.getInputStream()) {
                    out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return -1;
            } else {
                return -1;
            }
            for (var line : out.split("\n")) {
                var f = line.trim().split("\\s+");
                if (f[0].equals("usage_usec")) return Long.parseLong(f[1]);
                if (f[0].equals("cpu")) { // /proc/stat: every non-idle, non-iowait jiffy, 10 ms each
                    long busy = 0;
                    for (int i = 1; i < f.length && i <= 8; i++) {
                        if (i != 4 && i != 5) busy += Long.parseLong(f[i]);
                    }
                    return busy * 10_000;
                }
            }
        } catch (Exception e) {
            // not available
        }
        return -1;
    }

    // ------------------------------------------------------------------ helpers

    static long queryLong(Connection connection, String sql) throws SQLException {
        try (var s = connection.createStatement(); var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static String queryString(Connection connection, String sql) throws SQLException {
        try (var s = connection.createStatement(); var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Appends a line to {@code target/spike-results/<file>} and prints it */
    static synchronized void record(String file, String line) {
        System.out.println("SPIKE " + file + " | " + line);
        try {
            var directory = Path.of(System.getProperty("user.dir"), "target", "spike-results");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(file), line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws Exception {
        if (container != null) {
            observer.close();
            container.stop();
            return;
        }
        try (var admin = DriverManager.getConnection(JDBC_URL, USER, PASSWORD); var s = admin.createStatement()) {
            observer.close();
            s.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            for (var role : roles) s.execute("DROP ROLE IF EXISTS " + role);
        }
    }
}
