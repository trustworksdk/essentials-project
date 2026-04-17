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

    public static final String DEFAULT_PLUGIN = Wal2JsonLogicalDecodingPlugin.PLUGIN_NAME;

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
                if (slot.isActive()) {
                    throw new SQLException("Replication slot '" + slotName + "' is already active (active_pid=" +
                                                   slot.activePid + ") - owned by another logical consumer");
                }
            }
            case RECREATE -> {
                if (slot != null) {
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
