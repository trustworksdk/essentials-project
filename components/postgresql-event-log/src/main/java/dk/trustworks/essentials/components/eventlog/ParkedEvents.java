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
import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * One subscription's {@code el_parked_events} rows and its <b>held aggregates</b>: aggregate id → the
 * {@code global_order} of its parked
 * head. Every later event of a held aggregate is parked behind the head ({@code blocked_by}) instead of handed on.
 * Loaded at start; changed only by the delivery thread, and only after the row that justifies the change committed.
 * <p>
 * The <b>retry schedule</b>: every non-terminal head has a due time ({@link System#nanoTime()}) for its next automatic
 * retry, kept in due order with the earliest one at hand, so the delivery thread's due check costs no statement. The
 * stored {@code next_retry_at} is the truth; {@link #load()} turns its remaining wait, measured by the database's clock,
 * into a due time, so a new owner continues the schedule and node clocks do not matter. A terminal head (it failed with a
 * {@link ParkEventException}) has no due time: only an operator retries it.
 */
final class ParkedEvents {
    /**
     * A row to park: {@code blockedBy} {@code null} for a head
     */
    record Row(StoredEvent event, Long blockedBy, int attempts, String errorType, String errorMessage, boolean terminal) {
        Row {
            requireNonNull(event, "No event provided");
        }

        /** A non-terminal row */
        Row(StoredEvent event, Long blockedBy, int attempts, String errorType, String errorMessage) {
            this(event, blockedBy, attempts, errorType, errorMessage, false);
        }

        long globalOrder() {
            return event.globalOrder();
        }
    }

    /** A parked head's place in the retry schedule; {@code dueNanos} only counts while it is in {@link #due} */
    private static final class Head {
        final String aggregateId;
        final long   order;
        boolean      terminal;
        int          retries;
        long         dueNanos;
        /** When it was parked ({@link System#nanoTime()}): the database's {@code parked_at}, measured by its clock */
        final long   parkedAtNanos;

        Head(String aggregateId, long order, boolean terminal, int retries, long parkedAtNanos) {
            this.aggregateId = aggregateId;
            this.order = order;
            this.terminal = terminal;
            this.retries = retries;
            this.parkedAtNanos = parkedAtNanos;
        }
    }

    /** What a failed retry stored: the head's retries and the wait until its next retry ({@code null}: terminal) */
    record Failed(int retries, Long waitMillis) {
    }

    private final ProgressStore.Key   key;
    private final DataSource          dataSource;
    private final ErrorSettings       errors;
    private final TestSeams           seams;
    private final Map<String, Long>   held  = new HashMap<>();
    private final Map<Long, Head>     heads = new HashMap<>();
    /** The heads with a due time, earliest first */
    private final TreeSet<Head>       due   = new TreeSet<>((a, b) -> a.dueNanos != b.dueNanos ? Long.compare(a.dueNanos - b.dueNanos, 0) : Long.compare(a.order, b.order));

    ParkedEvents(ProgressStore.Key key, DataSource dataSource, ErrorSettings errors, TestSeams seams) {
        this.key = requireNonNull(key, "No key provided");
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.errors = requireNonNull(errors, "No errors provided");
        this.seams = requireNonNull(seams, "No seams provided");
    }

    /**
     * Replace the held set and the schedule with the parked heads in the table. A non-terminal head with no stored due
     * time (parked before the schedule existed) is due at once
     */
    void load() throws SQLException {
        clear();
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(EventLogStorage.PARKED_HEADS)) {
            bindKey(statement, 1);
            try (var resultSet = statement.executeQuery()) {
                var now = System.nanoTime();
                while (resultSet.next()) {
                    var aggregateId = resultSet.getString(1);
                    var order       = resultSet.getLong(2);
                    var terminal    = resultSet.getBoolean(3);
                    var retries     = resultSet.getInt(4);
                    var waitMillis  = resultSet.getLong(5);
                    if (resultSet.wasNull() || seams.has(WatermarkMutant.PARKED_RETRY_SCHEDULE_NOT_LOADED)) {
                        waitMillis = 0;
                    }
                    var ageMillis = resultSet.getLong(6);
                    held.put(aggregateId, order);
                    var head = new Head(aggregateId, order, terminal, retries, now - Duration.ofMillis(ageMillis).toNanos());
                    heads.put(order, head);
                    schedule(head, now + Duration.ofMillis(waitMillis).toNanos());
                }
            }
        }
    }

    void clear() {
        held.clear();
        heads.clear();
        due.clear();
    }

    /** (Re)place {@code head} in the schedule at {@code dueNanos}; a terminal head gets no due time */
    private void schedule(Head head, long dueNanos) {
        due.remove(head);
        head.dueNanos = dueNanos;
        if (!head.terminal || seams.has(WatermarkMutant.PARKED_RETRY_TERMINAL_RETRIED)) {
            due.add(head);
        }
    }

    /** @return the earliest due time of a non-terminal head, or {@code null} if none has one */
    Long earliestDueNanos() {
        return due.isEmpty() ? null : due.first().dueNanos;
    }

    /** @return at most {@code max} heads due at {@code now}, earliest first */
    List<Long> dueHeads(long now, int max) {
        var result = new ArrayList<Long>(Math.min(max, due.size()));
        for (var head : due) {
            if (result.size() == max || head.dueNanos - now > 0) {
                break;
            }
            result.add(head.order);
        }
        return result;
    }

    /** @return the failed retries of {@code head}, {@code 0} if it is not held */
    int retries(long head) {
        var entry = heads.get(head);
        return entry == null ? 0 : entry.retries;
    }

    /** @return the wait from {@code now} until {@code head}'s next automatic retry, or {@code null} if none is scheduled */
    Duration nextRetryIn(long head, long now) {
        var entry = heads.get(head);
        if (entry == null || !due.contains(entry)) {
            return null;
        }
        return Duration.ofNanos(Math.max(0, entry.dueNanos - now));
    }

    /** @return the heads with an automatic retry scheduled */
    int retryingCount() {
        var count = 0;
        for (var head : heads.values()) {
            if (!head.terminal) {
                count++;
            }
        }
        return count;
    }

    /** @return the terminal heads: only an operator retries them */
    int terminalCount() {
        return heads.size() - retryingCount();
    }

    /** @return when the oldest held head was parked ({@link System#nanoTime()}), or {@code null} if none is held */
    Long oldestParkedAtNanos() {
        Long oldest = null;
        for (var head : heads.values()) {
            if (oldest == null || head.parkedAtNanos - oldest < 0) {
                oldest = head.parkedAtNanos;
            }
        }
        return oldest;
    }

    /** @return whether {@code head} is a terminal head (only an operator retries it) */
    boolean terminal(long head) {
        var entry = heads.get(head);
        return entry != null && entry.terminal;
    }

    /**
     * A serve of {@code head} did not succeed and stored nothing (a conflict, a changed set, a lost lease): if it is due by
     * {@code now}, it is next due one backoff of its retries later, so a failing serve never runs in a tight loop
     */
    void postpone(long head, long now) {
        var entry = heads.get(head);
        if (entry != null && entry.dueNanos - now <= 0) {
            schedule(entry, now + errors.parkedRetryBackoff(Math.max(1, entry.retries)).toNanos());
        }
    }

    /**
     * A retry of {@code head} failed with {@code error}: the latest failure decides whether it is terminal, and its
     * next retry is due after the wait the database stored, or, if the update matched no row, one backoff of its retries
     * plus one later by this node's clock
     */
    void failed(long head, Throwable error, Failed stored) {
        var entry = heads.get(head);
        if (entry == null) {
            return;
        }
        var now = System.nanoTime();
        entry.terminal = error instanceof ParkEventException;
        if (stored != null) {
            entry.retries = stored.retries();
            schedule(entry, now + Duration.ofMillis(stored.waitMillis() == null ? 0 : stored.waitMillis()).toNanos());
        } else {
            entry.retries++;
            schedule(entry, now + errors.parkedRetryBackoff(entry.retries).toNanos());
        }
    }

    /** @return the first wait of a new head, or {@code null} for a terminal one; the database's {@code next_retry_at} */
    Long firstWaitMillis(boolean terminal) {
        return terminal ? null : errors.parkedRetryInitial().toMillis();
    }

    /** @return the parked head {@code aggregateId} is held behind, or {@code null} */
    Long headOf(String aggregateId) {
        return held.get(aggregateId);
    }

    /**
     * {@code aggregateId} is held behind {@code head} from now on, its first automatic retry due after
     * {@code waitMillis} ({@code terminal}: never)
     */
    void hold(String aggregateId, long head, boolean terminal, long waitMillis) {
        hold(aggregateId, head, terminal, waitMillis, 0);
    }

    /** {@link #hold(String, long, boolean, long)} for a head parked {@code ageMillis} ago (a held row made the head) */
    void hold(String aggregateId, long head, boolean terminal, long waitMillis, long ageMillis) {
        if (held.putIfAbsent(aggregateId, head) == null) {
            var now   = System.nanoTime();
            var entry = new Head(aggregateId, head, terminal, 0, now - Duration.ofMillis(ageMillis).toNanos());
            heads.put(head, entry);
            schedule(entry, now + Duration.ofMillis(waitMillis).toNanos());
        }
    }

    /** The aggregate held behind {@code head}, if any, is no longer held; @return its id or {@code null} */
    String release(long head) {
        for (var it = held.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (entry.getValue() == head) {
                it.remove();
                var removed = heads.remove(head);
                if (removed != null) {
                    due.remove(removed);
                }
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
                statement.setString(p++, row.errorMessage());
                bindSchedule(statement, p, row);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** {@link #insert} in its own autocommit transaction ({@code PERIODIC}) */
    void insertNow(List<Row> rows) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            insert(connection, rows);
        }
    }

    /**
     * The periodic park insert guarded on the progress row: lands only while the row's reset epoch is
     * {@code resetEpoch} and, for an exclusive subscription, its lease is {@code fence}'s. A row already there stays as it
     * is.
     *
     * @return {@code false} if the guard matched no row (a zero-row write: the caller decides by
     * {@code Subscription.ConflictDecision}), else
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
            statement.setString(p++, row.errorMessage());
            bindSchedule(statement, p, row);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1) > 0;
            }
        }
    }

    /** A park row's terminal flag, the wait before its first automatic retry ({@code null}: none) and whether it failed */
    private void bindSchedule(PreparedStatement statement, int p, Row row) throws SQLException {
        var head = row.blockedBy() == null;
        statement.setBoolean(p++, head && row.terminal());
        var wait = head ? firstWaitMillis(row.terminal()) : null;
        if (wait == null) {
            statement.setNull(p++, Types.BIGINT);
        } else {
            statement.setLong(p++, wait);
        }
        statement.setBoolean(p, head);
    }

    /**
     * Read {@code head}'s row and every row held behind it, on {@code connection}, without locking them: a serve reads
     * them before its handler runs and locks them ({@link #lock}) only after it, behind the progress row
     *
     * @return their global orders, in order; empty if {@code head} is not parked
     */
    List<Long> read(Connection connection, long head) throws SQLException {
        return orders(connection, EventLogStorage.PARKED_ROWS, head);
    }

    /**
     * Lock {@code head}'s row and every row held behind it, on {@code connection}
     *
     * @return their global orders, in order; empty if {@code head} is not parked
     */
    List<Long> lock(Connection connection, long head) throws SQLException {
        return orders(connection, EventLogStorage.PARKED_LOCK, head);
    }

    private List<Long> orders(Connection connection, String sql, long head) throws SQLException {
        var orders = new ArrayList<Long>();
        try (var statement = connection.prepareStatement(sql)) {
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

    /** A request stored on a parked head */
    record Request(long head, boolean discard) {
    }

    /** The parked check: the requests stored on this subscription's heads, in order */
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
     * The serve transaction's first statement: lock {@code key}'s progress row if its epoch is
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

    /**
     * In the serve transaction: the rows of {@code handled} are gone, and {@code newHead}, a row held behind
     * {@code oldHead}, heads the aggregate from now on, with every row still held behind {@code oldHead} held behind it.
     * The in-memory hold follows ({@link #release}, {@link #hold}) only once the caller commits.
     *
     * @param failure      the error {@code newHead} failed with (one attempt counted), or {@code null} if it has not run
     * @param requestRetry {@code true} to leave a retry request on the new head, so the next parked check serves it
     * @return how long ago the new head's row was parked, in milliseconds by the database's clock ({@code 0} if no row
     * matched)
     */
    long rehead(Connection connection, long oldHead, List<Long> handled, long newHead, Throwable failure, boolean requestRetry) throws SQLException {
        var terminal = failure instanceof ParkEventException;
        long ageMillis;
        if (!handled.isEmpty()) {
            delete(connection, handled);
        }
        try (var statement = connection.prepareStatement(EventLogStorage.PARKED_NEW_HEAD)) {
            statement.setInt(1, failure == null ? 0 : 1);
            statement.setString(2, failure == null ? null : failure.getClass().getName());
            statement.setString(3, failure == null ? null : failure.getMessage());
            statement.setString(4, requestRetry ? "RETRY" : null);
            statement.setString(5, requestRetry ? "RETRY" : null);
            statement.setBoolean(6, terminal);
            var wait = reheadWaitMillis(failure);
            if (wait == null) {
                statement.setNull(7, Types.BIGINT);
            } else {
                statement.setLong(7, wait);
            }
            statement.setBoolean(8, failure != null);
            var p = bindKey(statement, 9);
            statement.setLong(p, newHead);
            try (var resultSet = statement.executeQuery()) {
                ageMillis = resultSet.next() ? resultSet.getLong(1) : 0;
            }
        }
        try (var statement = connection.prepareStatement(EventLogStorage.PARKED_REPOINT)) {
            statement.setLong(1, newHead);
            var p = bindKey(statement, 2);
            statement.setLong(p, oldHead);
            statement.executeUpdate();
        }
        return ageMillis;
    }

    /**
     * The wait before a new head's first automatic retry: none if it failed terminally, its first backoff if it failed,
     * at once if it has not run (the rest of a served aggregate, past one page)
     */
    Long reheadWaitMillis(Throwable failure) {
        return failure == null ? Long.valueOf(0) : firstWaitMillis(failure instanceof ParkEventException);
    }

    /** A retry of {@code head}'s aggregate failed: one more attempt on the head's row, autocommit; @return what it stored, or {@code null} */
    Failed failedAgain(long head, Throwable error) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(failedAgainSql(EventLogStorage.PARKED_FAILED_AGAIN))) {
            var p = bindFailure(statement, error);
            p = bindKey(statement, p);
            statement.setLong(p, head);
            return stored(statement);
        }
    }

    private String failedAgainSql(String sql) {
        return seams.has(WatermarkMutant.PARKED_RETRY_BACKOFF_NOT_ADVANCED) ? sql.replace(EventLogStorage.FAILED_AGAIN_EXPONENT, "0") : sql;
    }

    private int bindFailure(PreparedStatement statement, Throwable error) throws SQLException {
        var terminal = error instanceof ParkEventException;
        statement.setString(1, error.getClass().getName());
        statement.setString(2, error.getMessage());
        statement.setBoolean(3, terminal);
        statement.setBoolean(4, terminal);
        statement.setDouble(5, errors.parkedRetryMax().toMillis());
        statement.setDouble(6, errors.parkedRetryInitial().toMillis());
        return 7;
    }

    private static Failed stored(PreparedStatement statement) throws SQLException {
        try (var resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return null;
            }
            var retries = resultSet.getInt(1);
            var wait    = resultSet.getLong(2);
            return new Failed(retries, resultSet.wasNull() ? null : wait);
        }
    }

    /**
     * {@link #failedAgain(long, Throwable)} guarded as {@link #insertGuarded}: counts only under the
     * current epoch and lease
     */
    Failed failedAgainGuarded(long head, Throwable error, long resetEpoch, ProgressStore.Fence fence) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(failedAgainSql(EventLogStorage.PARKED_FAILED_AGAIN_GUARDED))) {
            var p = bindFailure(statement, error);
            p = bindKey(statement, p);
            statement.setLong(p++, head);
            p = bindKey(statement, p);
            ProgressStore.bindGuard(statement, p, resetEpoch, fence);
            return stored(statement);
        }
    }

    private int bindKey(PreparedStatement statement, int first) throws SQLException {
        statement.setString(first, key.subscriber());
        statement.setString(first + 1, key.aggregateType());
        statement.setString(first + 2, key.instanceName());
        return first + 3;
    }
}
