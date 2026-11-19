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

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One subscription's {@code el_parked_events} rows and its <b>held aggregates</b> (design
 * {@code docs/archive/event-store-engine-phase4.md} §4.4, §4.5): aggregate id → the {@code global_order} of its parked
 * head. Every later event of a held aggregate is parked behind the head ({@code blocked_by}) instead of handed on.
 * Loaded at start; changed only by the delivery thread, and only after the row that justifies the change committed.
 */
final class ParkedEvents {
    /**
     * A row to park: {@code blockedBy} {@code null} for a head
     */
    record Row(StoredEvent event, Long blockedBy, int attempts, String errorType, String errorMessage) {
        Row {
            requireNonNull(event, "No event provided");
        }

        long globalOrder() {
            return event.globalOrder();
        }
    }

    private final ProgressStore.Key   key;
    private final DataSource          dataSource;
    private final Map<String, Long>   held = new HashMap<>();

    ParkedEvents(ProgressStore.Key key, DataSource dataSource) {
        this.key = requireNonNull(key, "No key provided");
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
    }

    /** Replace the held set with the parked heads in the table */
    void load() throws SQLException {
        held.clear();
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(EventLogStorage.PARKED_HEADS)) {
            bindKey(statement, 1);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    held.put(resultSet.getString(1), resultSet.getLong(2));
                }
            }
        }
    }

    void clear() {
        held.clear();
    }

    /** @return the parked head {@code aggregateId} is held behind, or {@code null} */
    Long headOf(String aggregateId) {
        return held.get(aggregateId);
    }

    /** {@code aggregateId} is held behind {@code head} from now on */
    void hold(String aggregateId, long head) {
        held.putIfAbsent(aggregateId, head);
    }

    /** The aggregate held behind {@code head}, if any, is no longer held; @return its id or {@code null} */
    String release(long head) {
        for (var it = held.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue() == head) {
                it.remove();
                return entry.getKey();
            }
        }
        return null;
    }

    int heldCount() {
        return held.size();
    }

    /**
     * Insert {@code rows} on {@code connection}, which is not committed here; a row already there stays as it is (a
     * replay after a crash finds the event parked)
     */
    void insert(Connection connection, List<Row> rows) throws SQLException {
        try (var statement = connection.prepareStatement(EventLogStorage.PARK_EVENT)) {
            for (var row : rows) {
                var p = bindKey(statement, 1);
                statement.setLong(p++, row.globalOrder());
                statement.setString(p++, row.event().aggregateId());
                if (row.blockedBy() == null) {
                    statement.setNull(p++, Types.BIGINT);
                } else {
                    statement.setLong(p++, row.blockedBy());
                }
                statement.setInt(p++, row.attempts());
                statement.setString(p++, row.errorType());
                statement.setString(p, row.errorMessage());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** {@link #insert} in its own autocommit transaction ({@code PERIODIC}, §4.4) */
    void insertNow(List<Row> rows) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            insert(connection, rows);
        }
    }

    /**
     * The periodic park insert guarded on the progress row (phase 7 §3.3): lands only while the row's reset epoch is
     * {@code resetEpoch} and, for an exclusive subscription, its lease is {@code fence}'s. A row already there stays as it
     * is.
     *
     * @return {@code false} if the guard matched no row (a zero-row write: the caller decides by phase 7 §2.6), else
     * {@code true}, inserted or found parked
     */
    boolean insertGuarded(Row row, long resetEpoch, ProgressStore.Fence fence) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(EventLogStorage.PARK_EVENT_GUARDED)) {
            var p = bindKey(statement, 1);
            p = ProgressStore.bindGuard(statement, p, resetEpoch, fence);
            p = bindKey(statement, p);
            statement.setLong(p++, row.globalOrder());
            statement.setString(p++, row.event().aggregateId());
            if (row.blockedBy() == null) {
                statement.setNull(p++, Types.BIGINT);
            } else {
                statement.setLong(p++, row.blockedBy());
            }
            statement.setInt(p++, row.attempts());
            statement.setString(p++, row.errorType());
            statement.setString(p, row.errorMessage());
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) > 0;
            }
        }
    }

    /**
     * Lock {@code head}'s row and every row held behind it, on {@code connection}
     *
     * @return their global orders, in order; empty if {@code head} is not parked
     */
    List<Long> lock(Connection connection, long head) throws SQLException {
        var orders = new ArrayList<Long>();
        try (var statement = connection.prepareStatement(EventLogStorage.PARKED_LOCK)) {
            var p = bindKey(statement, 1);
            statement.setLong(p++, head);
            statement.setLong(p, head);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    orders.add(resultSet.getLong(1));
                }
            }
        }
        return orders;
    }

    /** A request stored on a parked head (phase 8 §4.3) */
    record Request(long head, boolean discard) {
    }

    /** The parked check (phase 8 §4.3): the requests stored on this subscription's heads, in order */
    List<Request> requests() throws SQLException {
        var requests = new ArrayList<Request>();
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(EventLogStorage.PARKED_REQUESTS)) {
            bindKey(statement, 1);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    requests.add(new Request(resultSet.getLong(1), "DISCARD".equals(resultSet.getString(2))));
                }
            }
        }
        return requests;
    }

    /**
     * The serve transaction's first statement (phase 8 §4.3): lock {@code key}'s progress row if its epoch is
     * {@code resetEpoch} and, exclusive, its lease is {@code fence}'s
     *
     * @return {@code false} if the guard matched no row: a reset or takeover got there first
     */
    static boolean lockProgress(Connection connection, ProgressStore.Key key, Long resetEpoch, ProgressStore.Fence fence) throws SQLException {
        try (var statement = connection.prepareStatement(EventLogStorage.PARKED_SERVE_GUARD)) {
            var p = ProgressStore.bindKey(statement, 1, key);
            ProgressStore.bindGuard(statement, p, resetEpoch, fence);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    void delete(Connection connection, List<Long> globalOrders) throws SQLException {
        try (var statement = connection.prepareStatement(EventLogStorage.PARKED_DELETE)) {
            var p = bindKey(statement, 1);
            statement.setArray(p, connection.createArrayOf("bigint", globalOrders.toArray(Long[]::new)));
            statement.executeUpdate();
        }
    }

    /** A retry of {@code head}'s aggregate failed: one more attempt on the head's row, autocommit */
    void failedAgain(long head, Throwable error) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(EventLogStorage.PARKED_FAILED_AGAIN)) {
            statement.setString(1, error.getClass().getName());
            statement.setString(2, error.getMessage());
            var p = bindKey(statement, 3);
            statement.setLong(p, head);
            statement.executeUpdate();
        }
    }

    /**
     * {@link #failedAgain(long, Throwable)} guarded as {@link #insertGuarded} (phase 7 §3.1): counts only under the
     * current epoch and lease
     */
    void failedAgainGuarded(long head, Throwable error, long resetEpoch, ProgressStore.Fence fence) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(EventLogStorage.PARKED_FAILED_AGAIN_GUARDED)) {
            statement.setString(1, error.getClass().getName());
            statement.setString(2, error.getMessage());
            var p = bindKey(statement, 3);
            statement.setLong(p++, head);
            p = bindKey(statement, p);
            ProgressStore.bindGuard(statement, p, resetEpoch, fence);
            statement.executeUpdate();
        }
    }

    private int bindKey(PreparedStatement statement, int first) throws SQLException {
        statement.setString(first, key.subscriber());
        statement.setString(first + 1, key.aggregateType());
        statement.setString(first + 2, key.instanceName());
        return first + 3;
    }
}
