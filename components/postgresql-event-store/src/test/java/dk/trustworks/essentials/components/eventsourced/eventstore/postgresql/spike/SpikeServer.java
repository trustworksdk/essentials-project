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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike;

import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The PostgreSQL server a phase-0 spike runs against: either a Testcontainers container (the default, unchanged
 * behaviour) or an external server given by system properties, see {@code docs/plans/event-store-phase0-external-db.md}.
 * <ul>
 *     <li>{@code -Dspike.jdbcUrl=jdbc:postgresql://host:5432/postgres}: the external server. When set, no container is
 *     started. The database in the URL is only used to connect; every server this class hands out creates its own
 *     database {@code spike_<purpose>_<suffix>} and drops it again on {@link #close()}.</li>
 *     <li>{@code -Dspike.user} / {@code -Dspike.password}: a superuser on that server. The spikes run every role as a
 *     superuser in the container, and an external run does the same so that the numbers stay comparable: each role a
 *     spike asks for ({@link #role(String)}) is created as {@code <name>_<suffix>} with a random password and dropped
 *     on {@link #close()}.</li>
 *     <li>{@code -Dspike.serverCpuCommand="ssh bench@db cat /proc/stat"} (optional): a shell command, run on the client,
 *     whose output is either a {@code /proc/stat} (whole-machine CPU of the database host) or a cgroup v2
 *     {@code cpu.stat} (the PostgreSQL service's cgroup). Without it, server CPU is not available externally and is
 *     reported as {@code NaN}.</li>
 * </ul>
 * Not engine code; spikes only.
 */
final class SpikeServer implements AutoCloseable {
    static final String EXTERNAL_LABEL = "external";

    private static final String  JDBC_URL           = System.getProperty("spike.jdbcUrl", "").trim();
    private static final String  USER               = System.getProperty("spike.user", "postgres");
    private static final String  PASSWORD           = System.getProperty("spike.password", "");
    private static final String  SERVER_CPU_COMMAND = System.getProperty("spike.serverCpuCommand", "").trim();
    private static final Pattern URL                = Pattern.compile("^jdbc:postgresql://([^/?]+)(/[^?]*)?(\\?.*)?$");
    /** USER_HZ: the unit of /proc/stat. 100 on every mainstream Linux build. */
    private static final long    MICROS_PER_JIFFY   = 10_000;

    private static String description;

    /** Null for an external server. */
    final PostgreSQLContainer container;

    private final String              jdbcUrl;
    private final String              database;
    private final String              mainRole;
    private final String              suffix;
    private final String              rolePassword;
    private final List<String>        createdRoles = new ArrayList<>();
    private final Thread              shutdownHook;
    private       boolean             closed;

    private SpikeServer(PostgreSQLContainer container, String jdbcUrl, String database, String mainRole, String suffix, String rolePassword) {
        this.container = container;
        this.jdbcUrl = jdbcUrl;
        this.database = database;
        this.mainRole = mainRole;
        this.suffix = suffix;
        this.rolePassword = rolePassword;
        if (container == null) {
            shutdownHook = new Thread(this::closeQuietly, "spike-external-cleanup");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        } else {
            shutdownHook = null;
        }
    }

    /** True when {@code -Dspike.jdbcUrl} is set: the spikes run against that server and start no container. */
    static boolean external() {
        return !JDBC_URL.isEmpty();
    }

    /** A started container; its user is the main role. */
    static SpikeServer of(PostgreSQLContainer container) {
        return new SpikeServer(container, container.getJdbcUrl(), container.getDatabaseName(), container.getUsername(), "", null);
    }

    /**
     * A fresh database {@code spike_<purpose>_<suffix>} on the external server, with the main role {@code mainRole}
     * created (as a superuser, like the container's user) and checked.
     *
     * @param needsStatements fail fast unless {@code pg_stat_statements} is in {@code shared_preload_libraries}
     */
    static SpikeServer external(String purpose, String mainRole, boolean needsStatements) throws SQLException {
        if (!external()) throw new IllegalStateException("-Dspike.jdbcUrl is not set");
        if (!purpose.matches("[a-z0-9_]{1,20}")) throw new IllegalArgumentException("purpose: " + purpose);
        try (var admin = admin()) {
            if (!SpikePostgres.queryString(admin, "SELECT rolsuper::text FROM pg_roles WHERE rolname = current_user").equals("true")) {
                throw new IllegalStateException("-Dspike.user '" + USER + "' is not a superuser on " + JDBC_URL + ". The spikes create per-run SUPERUSER roles (as every role is a "
                                                        + "superuser in the container), LOAD auto_explain, reset pg_stat_statements and need full pg_stat_activity visibility. "
                                                        + "Create a benchmark superuser, see docs/plans/event-store-phase0-external-db.md.");
            }
            if (needsStatements) {
                var preload = SpikePostgres.queryString(admin, "SELECT setting FROM pg_settings WHERE name = 'shared_preload_libraries'");
                if (!Arrays.stream(preload.split(",")).map(String::trim).toList().contains("pg_stat_statements")) {
                    throw new IllegalStateException("pg_stat_statements is not preloaded on " + JDBC_URL + " (shared_preload_libraries = '" + preload + "'). This spike measures "
                                                            + "through pg_stat_statements: add it to shared_preload_libraries in postgresql.conf and restart the server.");
                }
            }
        }
        var suffix   = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(new Random().nextInt(36 * 36), 36);
        var database = "spike_" + purpose + "_" + suffix;
        var server   = new SpikeServer(null, withDatabase(JDBC_URL, database), database, mainRole, suffix, UUID.randomUUID().toString());
        try {
            try (var admin = admin(); var s = admin.createStatement()) {
                s.execute("CREATE DATABASE " + database);
                server.createRole(admin, mainRole);
            }
            server.verifyBackendXidVisible();
            return server;
        } catch (SQLException | RuntimeException e) {
            server.closeQuietly();
            throw e;
        }
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(JDBC_URL, USER, PASSWORD);
    }

    // ------------------------------------------------------------------ connection details

    String getJdbcUrl() {
        return jdbcUrl;
    }

    String getUsername() {
        return role(mainRole);
    }

    String getPassword() {
        return password(mainRole);
    }

    String database() {
        return database;
    }

    /** The actual name of a role the spike calls {@code logical}: unchanged in a container, suffixed per run on an external server. */
    String role(String logical) {
        return container != null ? logical : logical + "_" + suffix;
    }

    /** The logical name of an actual role name (the inverse of {@link #role(String)}). */
    String logical(String role) {
        return container != null || !role.endsWith("_" + suffix) ? role : role.substring(0, role.length() - suffix.length() - 1);
    }

    String password(String logical) {
        return container != null ? logical : rolePassword;
    }

    String host() {
        return container != null ? container.getHost() : hostAndPort()[0];
    }

    int port() {
        return container != null ? container.getMappedPort(5432) : Integer.parseInt(hostAndPort()[1]);
    }

    /** The run database through a local TCP proxy on {@code proxyPort}. */
    String viaProxy(int proxyPort) {
        if (container != null) return "jdbc:postgresql://127.0.0.1:" + proxyPort + "/" + database;
        var m = URL.matcher(jdbcUrl);
        if (!m.matches()) throw new IllegalStateException(jdbcUrl);
        return "jdbc:postgresql://127.0.0.1:" + proxyPort + "/" + database + (m.group(3) == null ? "" : m.group(3));
    }

    private String[] hostAndPort() {
        var m = URL.matcher(jdbcUrl);
        if (!m.matches() || m.group(1).contains(",")) throw new IllegalStateException("unsupported -Dspike.jdbcUrl (one host, jdbc:postgresql://host[:port]/db): " + JDBC_URL);
        var hp = m.group(1);
        int colon = hp.lastIndexOf(':');
        return colon < 0 || hp.endsWith("]") ? new String[]{hp, "5432"} : new String[]{hp.substring(0, colon), hp.substring(colon + 1)};
    }

    static String withDatabase(String url, String database) {
        var m = URL.matcher(url);
        if (!m.matches()) throw new IllegalArgumentException("unsupported -Dspike.jdbcUrl (expected jdbc:postgresql://host[:port]/db[?params]): " + url);
        return "jdbc:postgresql://" + m.group(1) + "/" + database + (m.group(3) == null ? "" : m.group(3));
    }

    // ------------------------------------------------------------------ setup the spikes ask for

    /**
     * {@code CREATE ROLE <role> LOGIN SUPERUSER} through {@code connection}: exactly the container statement in a
     * container; on an external server a per-run name and password, dropped again on {@link #close()}.
     */
    void createRole(Connection connection, String logical) throws SQLException {
        try (var s = connection.createStatement()) {
            s.execute("CREATE ROLE " + role(logical) + " LOGIN SUPERUSER PASSWORD '" + password(logical) + "'");
        }
        if (container == null) {
            synchronized (createdRoles) {
                createdRoles.add(role(logical));
            }
        }
    }

    /**
     * Turns {@code pg_stat_statements.track_planning} on for the connections opened after this. In a container that is
     * {@code ALTER SYSTEM} as before; on an external server it is scoped to the run database, so nothing outlives the run.
     */
    void enableTrackPlanning(Connection connection) throws SQLException {
        try (var s = connection.createStatement()) {
            if (container != null) {
                s.execute("ALTER SYSTEM SET pg_stat_statements.track_planning = on");
                s.execute("SELECT pg_reload_conf()");
            } else {
                s.execute("ALTER DATABASE " + database + " SET pg_stat_statements.track_planning = on");
                s.execute("SET pg_stat_statements.track_planning = on");
            }
        }
    }

    /**
     * Fails unless one connection of the main role sees another's {@code pg_stat_activity.backend_xid}: the xid and
     * probe spikes, and every pg_stat_activity sampler, depend on it. Always true for a superuser, but it is the one
     * prerequisite whose absence would read as "no xid assigned" instead of an error.
     */
    void verifyBackendXidVisible() throws SQLException {
        try (var holder = DriverManager.getConnection(jdbcUrl, getUsername(), getPassword());
             var observer = DriverManager.getConnection(jdbcUrl, getUsername(), getPassword())) {
            holder.setAutoCommit(false);
            var xid = SpikePostgres.queryString(holder, "SELECT pg_current_xact_id()::text");
            var pid = SpikePostgres.backendPid(holder);
            var seen = SpikePostgres.backendXidOf(observer, pid);
            holder.rollback();
            if (!xid.equals(seen)) {
                throw new IllegalStateException("pg_stat_activity.backend_xid of another backend is not visible to '" + getUsername() + "' on " + jdbcUrl + " (holder xid " + xid
                                                        + ", observed " + seen + "). Grant pg_read_all_stats or use a superuser.");
            }
        }
    }

    // ------------------------------------------------------------------ server CPU

    /** Where {@link #cpuMicros()} comes from. */
    String cpuSource() {
        return container != null ? "container cgroup" : externalCpuSource();
    }

    private static String externalCpuSource() {
        if (SERVER_CPU_COMMAND.isEmpty()) return "not available (external server, no -Dspike.serverCpuCommand): server CPU columns read NaN";
        var stat = cpuStat();
        return "-Dspike.serverCpuCommand '" + SERVER_CPU_COMMAND + "' (" + (stat == null ? "FAILING: check the command" : stat.length == 4 ? "cgroup cpu.stat" : "whole-host /proc/stat") + ")";
    }

    /**
     * CPU the server has used, µs: the container's cgroup (as before; 0 if it cannot be read), else the
     * {@code -Dspike.serverCpuCommand} output, else -1 (not available). Use {@link #cpuDelta(long, long)} on two readings.
     */
    long cpuMicros() {
        if (container != null) {
            try {
                var r = container.execInContainer("sh", "-c", "cat /sys/fs/cgroup/cpu.stat 2>/dev/null || cat /sys/fs/cgroup/cpuacct/cpuacct.usage");
                for (var line : r.getStdout().split("\n")) {
                    if (line.startsWith("usage_usec")) return Long.parseLong(line.split("\\s+")[1]);
                    if (line.matches("\\d+")) return Long.parseLong(line) / 1000;
                }
            } catch (Exception e) {
                // fall through
            }
            return 0;
        }
        var stat = cpuStat();
        return stat == null ? -1 : stat[0];
    }

    /** {usage, user, system} µs of the server, or null when not available. */
    long[] cpuUserSystemMicros() throws Exception {
        if (container != null) {
            long[] r = new long[3];
            for (var line : container.execInContainer("cat", "/sys/fs/cgroup/cpu.stat").getStdout().split("\n")) {
                var f = line.split("\\s+");
                switch (f[0]) {
                    case "usage_usec" -> r[0] = Long.parseLong(f[1]);
                    case "user_usec" -> r[1] = Long.parseLong(f[1]);
                    case "system_usec" -> r[2] = Long.parseLong(f[1]);
                    default -> {
                    }
                }
            }
            return r;
        }
        var stat = cpuStat();
        return stat == null ? null : new long[]{stat[0], stat[1], stat[2]};
    }

    /** {@code after - before} as a double, NaN when either reading is not available. */
    static double cpuDelta(long before, long after) {
        return before < 0 || after < 0 ? Double.NaN : after - before;
    }

    /**
     * {usage, user, system} µs from {@code -Dspike.serverCpuCommand} (a fourth element marks a cgroup source), or null.
     * /proc/stat: usage = every non-idle, non-iowait jiffy (guest time is already inside user).
     */
    private static long[] cpuStat() {
        if (SERVER_CPU_COMMAND.isEmpty()) return null;
        try {
            var process = new ProcessBuilder("/bin/sh", "-c", SERVER_CPU_COMMAND).redirectErrorStream(false).start();
            String out;
            try (var in = process.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
            long usage = -1, user = 0, system = 0;
            for (var line : out.split("\n")) {
                var f = line.trim().split("\\s+");
                switch (f[0]) {
                    case "usage_usec" -> usage = Long.parseLong(f[1]);
                    case "user_usec" -> user = Long.parseLong(f[1]);
                    case "system_usec" -> system = Long.parseLong(f[1]);
                    case "cpu" -> {
                        long u = Long.parseLong(f[1]) + Long.parseLong(f[2]);
                        long sy = Long.parseLong(f[3]) + Long.parseLong(f[6]) + Long.parseLong(f[7]);
                        long steal = f.length > 8 ? Long.parseLong(f[8]) : 0;
                        return new long[]{(u + sy + steal) * MICROS_PER_JIFFY, u * MICROS_PER_JIFFY, sy * MICROS_PER_JIFFY};
                    }
                    default -> {
                    }
                }
            }
            return usage < 0 ? null : new long[]{usage, user, system, 0};
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ------------------------------------------------------------------ description

    /**
     * What a results header says about the server: {@code containerDescription} unchanged in the default container
     * path; on an external server its address, version and the settings that move the numbers.
     */
    static synchronized String target(String containerDescription) {
        if (!external()) return containerDescription;
        if (description == null) {
            try (var admin = admin(); var s = admin.createStatement();
                 var rs = s.executeQuery("SELECT name, current_setting(name), '' FROM pg_settings WHERE name IN ('fsync', 'synchronous_commit', 'wal_sync_method', 'full_page_writes', "
                                                 + "'shared_buffers', 'max_wal_size', 'checkpoint_timeout', 'wal_compression', 'max_connections', 'shared_preload_libraries', "
                                                 + "'pg_stat_statements.track_planning', 'pg_stat_statements.max', 'pg_stat_statements.track_utility', 'track_io_timing', 'jit') ORDER BY name")) {
                var settings = new StringBuilder();
                while (rs.next()) settings.append(settings.isEmpty() ? "" : ", ").append(rs.getString(1)).append('=').append(rs.getString(2)).append(rs.getString(3));
                var version = SpikePostgres.queryString(admin, "SHOW server_version");
                var m = URL.matcher(JDBC_URL);
                description = "EXTERNAL server " + (m.matches() ? m.group(1) : JDBC_URL) + " PostgreSQL " + version + " (" + settings + ") | server CPU " + externalCpuSource()
                        + " | client CPUs " + Runtime.getRuntime().availableProcessors() + ", client-side machine/JVM figures are the client's";
            } catch (SQLException e) {
                throw new IllegalStateException("cannot reach -Dspike.jdbcUrl " + JDBC_URL + ": " + e.getMessage(), e);
            }
        }
        return (containerDescription.startsWith(" | ") ? " | " : "") + description;
    }

    // ------------------------------------------------------------------ teardown

    /** Stops the container, or drops the run database and every role this run created. */
    @Override
    public void close() throws SQLException {
        if (container != null) {
            container.stop();
            return;
        }
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // already shutting down: this is the hook
        }
        try (var admin = admin(); var s = admin.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            synchronized (createdRoles) {
                for (var role : createdRoles) s.execute("DROP ROLE IF EXISTS " + role);
            }
        }
    }

    private void closeQuietly() {
        try {
            close();
        } catch (Exception e) {
            System.err.println("SPIKE could not clean up " + database + " / " + createdRoles + " on " + JDBC_URL + ": " + e.getMessage());
        }
    }
}
