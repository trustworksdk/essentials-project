/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import java.sql.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Utility for inspecting and validating PostgreSQL logical replication slots.
 */
public final class PgReplicationSlots {
    private PgReplicationSlots() {
    }

    public static final class SlotInfo {
        public final String  slotName;
        public final String  slotType;
        public final String  plugin;
        public final String  database;
        public final Integer activePid;
        public final boolean temporary;
        public final String  restartLsn;
        public final String  confirmedFlushLsn;
        public final String  walStatus;
        public final Long    safeWalSize;
        public final String  inactiveSince;
        public final String  conflicting;
        public final String  invalidationReason;
        public final Boolean failover;
        public final Boolean synced;

        /**
         * Create a {@link SlotInfoBuilder} that names every argument.
         *
         * @return the builder
         */
        public static SlotInfoBuilder builder() {
            return new SlotInfoBuilder();
        }

        /**
         * @param slotName           the slot name
         * @param slotType           {@code logical} or {@code physical}
         * @param plugin             the output plugin backing a logical slot
         * @param database           the database the slot belongs to
         * @param activePid          the pid holding the slot, or {@code null} when inactive
         * @param temporary          whether the slot is temporary
         * @param restartLsn         the slot's restart LSN
         * @param confirmedFlushLsn  the slot's confirmed flush LSN
         * @param walStatus          {@code reserved}, {@code extended}, {@code unreserved} or {@code lost}
         * @param safeWalSize        bytes of WAL that can still be written before the slot risks invalidation
         * @param inactiveSince      when the slot went inactive, or {@code null}
         * @param conflicting        whether the slot conflicts with recovery
         * @param invalidationReason why the slot was invalidated, or {@code null}
         * @param failover           whether the slot is enabled for failover
         * @param synced             whether the slot was synced from a primary
         * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
         */
        @Deprecated(forRemoval = true, since = "0.40.x")
        public SlotInfo(String slotName,
                        String slotType,
                        String plugin,
                        String database,
                        Integer activePid,
                        boolean temporary,
                        String restartLsn,
                        String confirmedFlushLsn,
                        String walStatus,
                        Long safeWalSize,
                        String inactiveSince,
                        String conflicting,
                        String invalidationReason,
                        Boolean failover,
                        Boolean synced) {
            this.slotName = slotName;
            this.slotType = slotType;
            this.plugin = plugin;
            this.database = database;
            this.activePid = activePid;
            this.temporary = temporary;
            this.restartLsn = restartLsn;
            this.confirmedFlushLsn = confirmedFlushLsn;
            this.walStatus = walStatus;
            this.safeWalSize = safeWalSize;
            this.inactiveSince = inactiveSince;
            this.conflicting = conflicting;
            this.invalidationReason = invalidationReason;
            this.failover = failover;
            this.synced = synced;
        }

        public boolean isLogical() {
            return "logical".equalsIgnoreCase(slotType);
        }

        public boolean isActive() {
            return activePid != null;
        }
    }

    public static SlotInfo findSlot(Connection c, String slotName) throws SQLException {
        requireNonNull(c, "connection cannot be null");
        requireNonNull(slotName, "slotName cannot be null");

        try (var ps = c.prepareStatement("select * from pg_replication_slots where slot_name = ?")) {
            ps.setString(1, slotName);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                var columns = columnNames(rs.getMetaData());
                return new SlotInfo(
                        rs.getString("slot_name"),
                        rs.getString("slot_type"),
                        getString(rs, columns, "plugin"),
                        getString(rs, columns, "database"),
                        getInteger(rs, columns, "active_pid"),
                        Boolean.TRUE.equals(getBoolean(rs, columns, "temporary")),
                        getString(rs, columns, "restart_lsn"),
                        getString(rs, columns, "confirmed_flush_lsn"),
                        getString(rs, columns, "wal_status"),
                        getLong(rs, columns, "safe_wal_size"),
                        getString(rs, columns, "inactive_since"),
                        getString(rs, columns, "conflicting"),
                        getString(rs, columns, "invalidation_reason"),
                        getBoolean(rs, columns, "failover"),
                        getBoolean(rs, columns, "synced")
                );
            }
        }
    }

    public static void createLogicalSlot(Connection c, String slotName, String plugin) throws SQLException {
        try (var ps = c.prepareStatement("select * from pg_create_logical_replication_slot(?, ?)")) {
            ps.setString(1, slotName);
            ps.setString(2, plugin);
            ps.execute();
        }
    }

    public static void dropSlot(Connection c, String slotName) throws SQLException {
        try (var ps = c.prepareStatement("select pg_drop_replication_slot(?)")) {
            ps.setString(1, slotName);
            ps.execute();
        }
    }

    /**
     * Force-recreate the slot by terminating any attached backend first, then dropping and
     * re-creating it. Unlike {@link PgSlotMode#RECREATE} (which refuses active slots), this
     * helper is destructive: it tears down whatever session owned the slot and discards any
     * unacknowledged WAL changes. Intended for dev/test/perf-lab scenarios — never production.
     * Returns {@code true} when a pre-existing slot was dropped, {@code false} when no prior
     * slot existed.
     */
    public static boolean forceRecreateSlot(Connection c, String slotName, String plugin) throws SQLException {
        SlotInfo existing = findSlot(c, slotName);
        boolean dropped = false;
        if (existing != null) {
            if (existing.isActive()) {
                terminateSlotBackend(c, slotName);
                // pg_terminate_backend only *signals* the walsender; the backend may still hold the
                // slot for a short window after the call returns. Wait (bounded) for active_pid to
                // clear so the drop below doesn't fail with "replication slot ... is active for PID",
                // which would abort this auto-heal and delay recovery until the next monitor fire.
                waitUntilSlotInactive(c, slotName);
            }
            dropSlotToleratingStillActive(c, slotName);
            dropped = true;
        }
        createLogicalSlot(c, slotName, plugin);
        return dropped;
    }

    /** Number of times to poll for the slot's backend to release it after pg_terminate_backend. */
    private static final int  SLOT_DEACTIVATION_POLL_ATTEMPTS = 5;
    /** Delay between deactivation polls (and the single drop retry). 5 x 200ms ≈ 1s worst case. */
    private static final long SLOT_DEACTIVATION_POLL_DELAY_MS  = 200L;

    private static void terminateSlotBackend(Connection c, String slotName) throws SQLException {
        try (var term = c.prepareStatement(
                "select pg_terminate_backend(active_pid) " +
                        "from pg_replication_slots where slot_name = ? and active_pid is not null")) {
            term.setString(1, slotName);
            term.execute();
        }
    }

    /**
     * Poll the slot until its {@code active_pid} clears (or it disappears), bounded by
     * {@link #SLOT_DEACTIVATION_POLL_ATTEMPTS}. Best-effort: if it is still active after the budget,
     * we proceed to the drop anyway (which retries once — see {@link #dropSlotToleratingStillActive}).
     */
    private static void waitUntilSlotInactive(Connection c, String slotName) throws SQLException {
        for (int attempt = 0; attempt < SLOT_DEACTIVATION_POLL_ATTEMPTS; attempt++) {
            SlotInfo slot = findSlot(c, slotName);
            if (slot == null || !slot.isActive()) {
                return;
            }
            sleep(SLOT_DEACTIVATION_POLL_DELAY_MS);
        }
    }

    private static void dropSlotToleratingStillActive(Connection c, String slotName) throws SQLException {
        try {
            dropSlot(c, slotName);
        } catch (SQLException e) {
            if (!isSlotStillActive(e)) {
                throw e;
            }
            // The terminated backend hadn't fully released the slot yet — give it one more grace
            // period and retry the drop once before surfacing the failure to the caller.
            sleep(SLOT_DEACTIVATION_POLL_DELAY_MS);
            dropSlot(c, slotName);
        }
    }

    /**
     * Detects the "replication slot is active" failure PostgreSQL raises as {@code object_in_use}
     * (SQLState {@code 55006}) with a message like {@code replication slot "x" is active for PID N}.
     */
    private static boolean isSlotStillActive(SQLException e) {
        if ("55006".equals(e.getSQLState())) {
            return true;
        }
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase(Locale.ROOT).contains("is active for pid");
    }

    private static void sleep(long millis) throws SQLException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for the replication slot's backend to release it", ie);
        }
    }

    public static void ensureSlot(Connection c, String slotName, PgSlotMode mode, String expectedPlugin) throws SQLException {
        requireNonNull(c, "connection cannot be null");
        requireNonNull(slotName, "slotName cannot be null");
        requireNonNull(mode, "mode cannot be null");
        requireNonNull(expectedPlugin, "expectedPlugin cannot be null");

        SlotInfo slot = findSlot(c, slotName);

        switch (mode) {
            case EXTERNAL -> {
                if (slot == null) {
                    throw new SQLException("Replication slot '" + slotName + "' missing (mode=EXTERNAL)");
                }
                validateSlotOrThrow(slotName, slot, expectedPlugin);
                validateSlotHealthOrThrow(slotName, slot);
                if (slot.isActive()) {
                    throw new SQLException("Replication slot '" + slotName + "' is already active (active_pid=" +
                                                   slot.activePid + ") (mode=EXTERNAL)");
                }
            }
            case REQUIRE_EXISTING -> {
                if (slot == null) {
                    throw new SQLException("Replication slot '" + slotName + "' does not exist (mode=REQUIRE_EXISTING)");
                }
                validateSlotOrThrow(slotName, slot, expectedPlugin);
                validateSlotHealthOrThrow(slotName, slot);
                if (slot.isActive()) {
                    throw new SQLException("Replication slot '" + slotName + "' is already active (active_pid=" +
                                                   slot.activePid + ") - owned by another logical consumer");
                }
            }
            case CREATE_IF_MISSING -> {
                if (slot == null) {
                    createLogicalSlot(c, slotName, expectedPlugin);
                    return;
                }
                validateSlotOrThrow(slotName, slot, expectedPlugin);
                validateSlotHealthOrThrow(slotName, slot);
                if (slot.isActive()) {
                    throw new SQLException("Replication slot '" + slotName + "' is already active (active_pid=" +
                                                   slot.activePid + ") - owned by another logical consumer");
                }
            }
            case RECREATE -> {
                if (slot != null) {
                    // RECREATE intentionally skips health validation — we're about to drop the
                    // slot anyway, so a degraded slot here is exactly the case RECREATE is for.
                    // We still run identity validation so we don't accidentally drop a slot
                    // that turned out not to be ours.
                    validateSlotOrThrow(slotName, slot, expectedPlugin);
                    if (slot.isActive()) {
                        throw new SQLException("Replication slot '" + slotName + "' is active (active_pid=" +
                                                       slot.activePid + "); refusing to drop while in use (mode=RECREATE)");
                    }
                    dropSlot(c, slotName);
                }
                createLogicalSlot(c, slotName, expectedPlugin);
            }
            default -> throw new SQLException("Unsupported PgSlotMode: " + mode);
        }
    }

    /**
     * Identity validation: confirms the slot is one we can use (logical, expected plugin,
     * tied to a database, persistent). Throws a descriptive {@link SQLException} otherwise.
     * Does not look at health/retention state — see {@link #validateSlotHealthOrThrow}.
     */
    public static void validateSlotOrThrow(String slotName, SlotInfo slot, String expectedPlugin) throws SQLException {
        if (!slot.isLogical()) {
            throw new SQLException("Replication slot '" + slotName + "' is not logical (slot_type=" + slot.slotType + ")");
        }
        if (slot.plugin == null || !expectedPlugin.equalsIgnoreCase(slot.plugin)) {
            throw new SQLException("Replication slot '" + slotName + "' uses unexpected plugin '" + slot.plugin +
                                           "' (expected '" + expectedPlugin + "')");
        }
        if (slot.database == null || slot.database.isBlank()) {
            throw new SQLException("Replication slot '" + slotName + "' has no database set (unexpected for logical slot)");
        }
        if (slot.temporary) {
            throw new SQLException("Replication slot '" + slotName + "' is temporary; expected a persistent slot");
        }
    }

    /**
     * Health validation: fails fast when the slot exists but cannot be used as-is because
     * PostgreSQL has marked it degraded — most commonly via {@code max_slot_wal_keep_size}
     * pruning the underlying WAL. Detects three independent server-side signals:
     * <ul>
     *   <li>{@code wal_status} not in {@code reserved} (PG ≥ 13): the slot is either
     *       {@code extended} (over {@code max_wal_size}, will keep growing the disk),
     *       {@code unreserved} (about to be invalidated), or {@code lost} (already dead).</li>
     *   <li>{@code conflicting = true} (PG ≥ 16): the slot conflicts with recovery and
     *       cannot stream further changes.</li>
     *   <li>{@code invalidation_reason} non-null (PG ≥ 16): the server has explicitly
     *       invalidated the slot and recorded why.</li>
     * </ul>
     * Older PostgreSQL versions report {@code null} for fields they don't support; those
     * are treated as "unknown, can't verify" and pass. The check therefore has no false
     * positives on supported servers and no spurious failures on older ones.
     * <p>
     * On any failure, the exception message includes ready-to-run remediation SQL —
     * either {@code SELECT pg_drop_replication_slot('…')} or a pointer to switch the
     * configured slot mode — so operators don't need to look up the recovery procedure.
     * Callers that intend to drop the slot themselves (e.g. {@link PgSlotMode#RECREATE})
     * should not invoke this method.
     */
    public static void validateSlotHealthOrThrow(String slotName, SlotInfo slot) throws SQLException {
        if (slot.walStatus != null && !"reserved".equalsIgnoreCase(slot.walStatus)) {
            throw new SQLException("Replication slot '" + slotName + "' has wal_status='" + slot.walStatus +
                                           "' (expected 'reserved'); slot is degraded — PostgreSQL has either " +
                                           "exceeded max_wal_size (extended), is about to invalidate the slot " +
                                           "(unreserved), or has already invalidated it (lost). Subscribers will " +
                                           "fall back to polling automatically. To recover: " +
                                           "SELECT pg_drop_replication_slot('" + slotName + "'); and restart, " +
                                           "or set essentials.eventstore.cdc.slot.mode=RECREATE for one boot.");
        }
        if (slot.conflicting != null && ("t".equalsIgnoreCase(slot.conflicting) || "true".equalsIgnoreCase(slot.conflicting))) {
            String reason = slot.invalidationReason == null || slot.invalidationReason.isBlank()
                            ? "(no reason reported)"
                            : "(invalidation_reason='" + slot.invalidationReason + "')";
            throw new SQLException("Replication slot '" + slotName + "' is in a conflicting state " + reason +
                                           " and can no longer stream changes. Recover with: " +
                                           "SELECT pg_drop_replication_slot('" + slotName + "'); and restart.");
        }
        if (slot.invalidationReason != null && !slot.invalidationReason.isBlank()) {
            throw new SQLException("Replication slot '" + slotName + "' has been invalidated by PostgreSQL " +
                                           "(invalidation_reason='" + slot.invalidationReason + "'). Recover with: " +
                                           "SELECT pg_drop_replication_slot('" + slotName + "'); and restart.");
        }
    }

    /**
     * Checks whether PostgreSQL has a server-side disk safety net configured for replication
     * slots, returning a ready-to-log advisory string when it does not.
     * <p>
     * {@code max_slot_wal_keep_size} (PG 13+) bounds how much WAL the server will retain on
     * behalf of an unconsumed slot before invalidating it. The default is {@code -1}
     * (unbounded), which means a stuck or orphaned slot can fill the disk. When that's the
     * case we recommend setting an explicit value (e.g. {@code 10GB}) so the server has a
     * fallback when the framework's own slot-growth defenses (idle LSN push, advisory locks,
     * effectiveness monitor) all fail.
     * <p>
     * Returns:
     * <ul>
     *   <li>{@link Optional#empty()} when the setting cannot be read (older PG, missing
     *       privileges) or is already bounded — nothing for the operator to do.</li>
     *   <li>An {@link Optional} carrying a human-readable advisory string when the setting is
     *       {@code -1} / unlimited. The caller is expected to log it INFO once per JVM start.
     *       The string includes both the explanation and a concrete configuration suggestion.</li>
     * </ul>
     * Pure read; no side effects on the server.
     */
    public static Optional<String> getKeepSizeAdvisoryIfUnbounded(Connection c) {
        requireNonNull(c, "connection cannot be null");
        try (var ps = c.prepareStatement("select setting from pg_settings where name = 'max_slot_wal_keep_size'");
             var rs = ps.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            String setting = rs.getString("setting");
            if (setting == null || setting.isBlank()) return Optional.empty();
            // pg_settings.setting returns the raw integer value as a string. -1 = unlimited.
            // Anything else (0, positive integer in MB) means a bound is in place.
            if (!"-1".equals(setting.trim())) return Optional.empty();
            return Optional.of(
                    "PostgreSQL max_slot_wal_keep_size is unbounded (-1, the default). " +
                            "A stuck or orphaned replication slot can therefore retain WAL until " +
                            "the disk fills. Consider setting a value (e.g. 10GB) in postgresql.conf " +
                            "so the server invalidates over-budget slots before they impact the database. " +
                            "When invalidation fires, subscribers fall back to polling automatically — " +
                            "no event loss; the slot is just recreated. See cdc.md §5.6.");
        } catch (SQLException e) {
            // Best-effort advisory — never fail startup over this. A privilege issue here just
            // means we don't know whether to advise; silent skip is the right behaviour.
            return Optional.empty();
        }
    }

    private static Set<String> columnNames(ResultSetMetaData metaData) throws SQLException {
        Set<String> columns = new HashSet<>();
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            columns.add(metaData.getColumnLabel(index).toLowerCase(Locale.ROOT));
        }
        return columns;
    }

    private static String getString(ResultSet rs, Set<String> columns, String column) throws SQLException {
        if (!columns.contains(column.toLowerCase(Locale.ROOT))) return null;
        return rs.getString(column);
    }

    private static Integer getInteger(ResultSet rs, Set<String> columns, String column) throws SQLException {
        if (!columns.contains(column.toLowerCase(Locale.ROOT))) return null;
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long getLong(ResultSet rs, Set<String> columns, String column) throws SQLException {
        if (!columns.contains(column.toLowerCase(Locale.ROOT))) return null;
        Object value = rs.getObject(column);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Boolean getBoolean(ResultSet rs, Set<String> columns, String column) throws SQLException {
        if (!columns.contains(column.toLowerCase(Locale.ROOT))) return null;
        Object value = rs.getObject(column);
        return value instanceof Boolean bool ? bool : null;
    }
}
