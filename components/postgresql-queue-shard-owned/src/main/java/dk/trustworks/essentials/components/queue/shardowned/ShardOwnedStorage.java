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

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

import static dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema.*;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The storage seam of the shard-owned engine — every statement the design's hot path issues, and
 * nothing else. Policy (leases, cursors, hole chasing, retries) lives above this in
 * {@link ShardOwner}; SQL lives here.
 * <p>
 * The design's cost claim is visible in how few methods this has: an insert and a delete are the
 * only writes on the steady-state path. There is deliberately no claim or dequeue statement,
 * because ownership makes one unnecessary.
 *
 * <h2>{@code shard} is bound with {@code setInt}, against a {@code smallint} column</h2>
 * A shard is an {@code int} everywhere above this class — {@code shardCount}, the loop variables,
 * {@code MessageId.shard} — and every binding used to narrow it with a {@code (short)} cast to match
 * the column. Thirty-six of those read to a static analyser as an unchecked truncation, and they were
 * safe only because of an invariant it cannot see.
 * <p>
 * Binding the {@code int} directly is not a workaround for the warning; it is better on both counts
 * that mattered. PostgreSQL's {@code integer_ops} btree opfamily carries the cross-type
 * {@code int2 = int4} operators, so a comparison against a {@code smallint} column still uses the
 * index — verified on 17: identical {@code Index Only Scan} plans either way. And on the write side an
 * out-of-range value now raises {@code smallint out of range} instead of silently wrapping, which is
 * what {@code (short) 32768} did.
 * <p>
 * {@code queueId} is a real {@code short} and is still bound with {@code setShort}; nothing is cast
 * there.
 */
public final class ShardOwnedStorage {
    private final DataSource dataSource;
    private final short      queueId;

    public ShardOwnedStorage(DataSource dataSource, short queueId) {
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.queueId = queueId;
    }


    /**
     * Run a batch as one transaction, whatever the caller's connection was set to.
     * <p>
     * Enqueue has to be all-or-nothing. Without this it is not: pgjdbc splits a large batch into
     * chunks and syncs each one, so PostgreSQL's implicit transaction covers a chunk rather than the
     * batch — a rejected row part way through leaves everything before it committed. Measured at
     * 2 295 of 5 000 rows persisted from a batch whose caller was told it had failed. A small batch
     * fits in one chunk and is atomic by accident, which is what makes this so easy to miss.
     * <p>
     * Partial enqueue is worst exactly where this design is aimed: a caller writing an outbox sees an
     * exception, reasonably assumes nothing was written, and retries — and half the batch is
     * delivered twice while the caller believes it was delivered once.
     */
    private <T> T inTransaction(Connection connection, SqlCall<T> work) throws SQLException {
        var autoCommit = connection.getAutoCommit();
        if (!autoCommit) {
            // Already inside the caller's transaction — the outbox case. Their commit decides, which
            // is the whole point of enqueueing transactionally.
            return work.call();
        }
        connection.setAutoCommit(false);
        try {
            var result = work.call();
            connection.commit();
            return result;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    /**
     * Give the current transaction its xid now, before it allocates an ordered-lane sequence value.
     * <p>
     * The ordered lane's watermark steps over a sequence value only once no transaction that could still
     * commit it is running, and it finds those transactions by their {@code backend_xid}. That holds only if
     * a writer has an xid by the time it holds a value, and PostgreSQL does not guarantee it: an xid is
     * assigned lazily, at the first heap write, and {@code nextval()} assigns one only on the calls that
     * WAL-log - one in every 32. For the other 31, {@code INSERT ... VALUES (..., nextval(...))} takes the
     * value first and the xid only when the row is written, and in between the writer is invisible. A second
     * producer committing a higher value into the same unit in that window let the watermark pass the first
     * one; its row landed below the cursor, reachable only by the head sweep, and a later {@code key_order}
     * of the same key was delivered first. Seen once in twenty minutes with two instances producing into
     * shared units; {@code ShardOwnedWatermarkXidWindowIT} holds the window open and reproduces it every time.
     * <p>
     * One extra statement per transaction. Harmless inside a caller's transaction, which is about to write
     * anyway.
     */
    private static void assignTransactionIdBeforeAllocating(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_current_xact_id()")) {
            statement.execute();
        }
    }

    @FunctionalInterface
    private interface SqlCall<T> {
        T call() throws SQLException;
    }

    /**
     * The queue every statement on this handle binds.
     */
    public short queueId() {
        return queueId;
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Enqueue a batch as one multi-row insert. Sequence values come from the shard's own sequence
     * inside the same statement, so a message's identity is allocated and committed together.
     */
    public void enqueueBatch(Connection connection, int shard, List<byte[]> payloads, int payloadType) throws SQLException {
        enqueueRows(connection, shard, payloads.stream().map(payload -> new PayloadRow(payload, payloadType)).toList());
    }

    /**
     * As {@link #enqueueBatch}, but each row carries its own payload type.
     *
     * @return the allocated sequence values, ascending
     */
    public List<Long> enqueueRows(Connection connection, int shard, List<PayloadRow> rows) throws SQLException {
        return inTransaction(connection, () -> enqueueBatchInternal(connection, shard, rows));
    }

    private List<Long> enqueueBatchInternal(Connection connection, int shard, List<PayloadRow> rows) throws SQLException {
        // visible_at is now() plus the requested delay, computed by the SERVER. A client timestamp
        // here would make delayed delivery depend on the enqueueing node's clock, which is the one
        // thing the rest of this engine is careful never to do.
        var sql = "INSERT INTO " + UNORDERED_TABLE
                + " (queue_id, shard, seq, payload, payload_type, visible_at)"
                + " VALUES (?, ?, nextval(?::regclass), ?, ?, now() + make_interval(secs => ? / 1000.0))";
        var seqs = new ArrayList<Long>(rows.size());
        try (var statement = connection.prepareStatement(sql, new String[]{"seq"})) {
            for (var row : rows) {
                statement.setShort(1, queueId);
                statement.setInt(2, shard);
                statement.setString(3, ShardOwnedSchema.sequenceName(queueId, shard));
                statement.setBytes(4, row.payload());
                statement.setInt(5, row.payloadType());
                statement.setLong(6, row.delayMillis());
                statement.addBatch();
            }
            statement.executeBatch();
            try (var keys = statement.getGeneratedKeys()) {
                while (keys.next()) {
                    seqs.add(keys.getLong(1));
                }
            }
        }
        Collections.sort(seqs);
        // In the same transaction as the inserts: delivered at commit, discarded on rollback. One
        // hint per batch per shard rather than one per message — coalescing is what keeps the
        // notification queue from becoming the bottleneck it is famous for being.
        ShardWakeupListener.notifyShard(connection, queueId, "unordered", shard);
        return seqs;
    }

    /**
     * Enqueue pre-claimed for local hand-off (§4.5 Tier 2).
     * <p>
     * The row is stamped with the enqueuing owner's fence, and that owner's cursor read excludes its
     * own fence — so the message is durable but invisible to the read path, because the owner already
     * has it in memory and is about to dispatch it directly. No read-back, and no extra write to
     * suppress one: the stamp rides along in the insert that was happening anyway.
     * <p>
     * On crash the lease expires and the fence changes, so the next owner's exclusion no longer
     * matches and the rows become visible again. The stamp is self-expiring.
     *
     * @return the allocated sequence values, ascending — which corresponds to payload order, because
     * {@code nextval} is applied in the ordinality order of the input array
     */
    public List<Long> enqueuePreClaimed(Connection connection, int shard, List<byte[]> payloads, int payloadType, long fence) throws SQLException {
        // A FIXED single-row statement batched with addBatch, not a multi-row VALUES list built per
        // call. The dynamic form worked, but its SQL text changed with the batch size, so pgjdbc's
        // prepared-statement cache never hit and every enqueue paid a fresh parse and plan. That
        // showed up as a p99 of 266 ms on the producer side while delivery itself was 2 ms — the
        // mechanism was fine and the statement was not.
        //
        // Generated keys rather than RETURNING, because they survive batching.
        var sql = "INSERT INTO " + UNORDERED_TABLE
                + " (queue_id, shard, seq, payload, payload_type, lease)"
                + " VALUES (?, ?, nextval(?::regclass), ?, ?, ?)";
        var seqs = new ArrayList<Long>(payloads.size());
        try (var statement = connection.prepareStatement(sql, new String[]{"seq"})) {
            for (var payload : payloads) {
                statement.setShort(1, queueId);
                statement.setInt(2, shard);
                statement.setString(3, ShardOwnedSchema.sequenceName(queueId, shard));
                statement.setBytes(4, payload);
                statement.setInt(5, payloadType);
                statement.setLong(6, fence);
                statement.addBatch();
            }
            statement.executeBatch();
            try (var keys = statement.getGeneratedKeys()) {
                while (keys.next()) {
                    seqs.add(keys.getLong(1));
                }
            }
        }
        // Sequence values are allocated in row order, so sorting re-establishes payload order without
        // depending on the order generated keys come back in.
        Collections.sort(seqs);
        ShardWakeupListener.notifyShard(connection, queueId, "unordered", shard);
        return seqs;
    }

    /**
     * Where a new owner's cursor starts: just below the shard's first row the cursor read can see, or - with none -
     * at the shard sequence's last allocated value.
     * <p>
     * The cursor used to start at 0, so the first read recorded every sequence value below the shard's first
     * remaining row as a hole: every message the shard had ever delivered and deleted, chased for
     * {@code holeExpiry}. A row below the starting point that is not readable yet - delayed, retrying, held by a
     * session, or allocated by a transaction that commits after the takeover - is delivered by the head sweep.
     */
    public long takeoverCursor(Connection connection, int shard) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT coalesce((SELECT min(seq) - 1 FROM " + UNORDERED_TABLE
                        + "                WHERE queue_id = ? AND shard = ? AND visible_at <= now()" + notRowLeasedClause() + "),"
                        + "                (SELECT CASE WHEN is_called THEN last_value ELSE last_value - 1 END FROM "
                        + ShardOwnedSchema.sequenceName(queueId, shard) + "))")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return Math.max(0L, resultSet.getLong(1));
            }
        }
    }

    /**
     * The fast path: a forward range scan on the primary key from the owner's cursor.
     * <p>
     * No lease predicate, no anti-join, no filtering of rows belonging to another consumer — the
     * owner knows what it has read, and nobody else reads this shard. Acked rows are gone, so there
     * are no dead tuples to rescan either.
     */
    public List<Row> readFromCursor(Connection connection, int shard, long cursor, int limit, long ownFence) throws SQLException {
        // Skip what this owner handed to itself. Anything stamped with a DIFFERENT fence belongs to
        // a dead owner and must be read normally, which is what makes the stamp self-expiring.
        var sql = "SELECT seq, payload, payload_type FROM " + UNORDERED_TABLE
                + " WHERE queue_id = ? AND shard = ? AND seq > ? AND visible_at <= now()"
                + " AND (lease IS NULL OR lease <> ?)"
                + notRowLeasedClause()
                + " ORDER BY seq LIMIT " + limit;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setLong(3, cursor);
            statement.setLong(4, ownFence);
            return readRows(statement);
        }
    }

    /**
     * Milliseconds until the shard's earliest not-yet-visible row becomes deliverable, or empty when
     * there is none.
     * <p>
     * Delayed delivery needs this, and the backstop alone is not good enough for it. A delayed row is
     * invisible to the cursor read, so the reader steps over it and books it as a hole; the hole
     * chase cannot resolve it either, because the chase filters on visibility too. It would therefore
     * be abandoned after {@code holeExpiry} and left to the head sweep — which has backed off to
     * {@code maxSweepInterval} on a quiet shard, so a message asked to wait one second could arrive
     * thirty seconds late. Asking the server when the next row is due lets the owner park exactly
     * that long instead. Index-only against {@code (queue_id, shard, visible_at)}, once per sweep.
     */
    public OptionalLong millisUntilNextVisible(Connection connection, int shard) throws SQLException {
        return millisUntilNextVisible(connection, UNORDERED_TABLE, shard);
    }

    public OptionalLong millisUntilNextVisible(Connection connection, String table, int shard) throws SQLException {
        // The SERVER computes the difference, and what comes back is an interval rather than an
        // instant. Returning min(visible_at) and subtracting the client clock from it would be the
        // obvious shape and would reintroduce exactly the skew this engine avoids everywhere else:
        // a node whose clock runs five seconds behind the database would wait five seconds too long
        // for every delayed message. An interval is clock-free by construction.
        try (var statement = connection.prepareStatement(
                "SELECT EXTRACT(EPOCH FROM (min(visible_at) - now())) * 1000 FROM " + table
                        + " WHERE queue_id = ? AND shard = ? AND visible_at > now()")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return OptionalLong.empty();
                }
                // getDouble + wasNull, not getObject(Double.class): EXTRACT returns numeric, and
                // pgjdbc refuses numeric -> Double through getObject. That threw a PSQLException the
                // pump could not tell from a lost connection.
                var millis = resultSet.getDouble(1);
                return resultSet.wasNull() ? OptionalLong.empty() : OptionalLong.of(Math.max(0L, (long) millis));
            }
        }
    }

    /**
     * Targeted lookup for sequence values the cursor stepped over. Only issued when a hole exists,
     * and never allowed to stall the cursor.
     */
    public List<Row> readSpecific(Connection connection, int shard, Collection<Long> seqs) throws SQLException {
        if (seqs.isEmpty()) {
            return List.of();
        }
        var sql = "SELECT seq, payload, payload_type FROM " + UNORDERED_TABLE
                + " WHERE queue_id = ? AND shard = ? AND seq = ANY(?) AND visible_at <= now()"
                + notRowLeasedClause();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setArray(3, connection.createArrayOf("bigint", seqs.toArray(Long[]::new)));
            return readRows(statement);
        }
    }

    /**
     * The backstop. Reads from the head of the shard regardless of cursor, so that anything a
     * crashed owner left behind — or a late commit the in-memory hole set has forgotten — is still
     * delivered. Runs rarely; correctness does not depend on the cursor being perfect, only on this
     * existing.
     */
    public List<Row> sweepFromHead(Connection connection, int shard, int limit, long ownFence,
                                   long handoffGraceMillis) throws SQLException {
        // The sweep must still recover a hand-off that was lost — the process died between the commit
        // and the dispatch — which is why it does not simply skip this owner's pre-claims. But a
        // pre-claim that was committed moments ago is far more likely to be in flight than lost, and
        // sweeping it produces a duplicate: the sweep delivers it, acknowledges it and deletes it,
        // and then the hand-off delivers it again with nothing left to deduplicate against. Measured
        // at one in 2 000 messages, and seven in 20 000.
        //
        // So a pre-claim of this owner's own is left alone until it is older than the grace. Older
        // than that and it really is orphaned, and the backstop does its job.
        var sql = "SELECT seq, payload, payload_type FROM " + UNORDERED_TABLE
                + " WHERE queue_id = ? AND shard = ? AND visible_at <= now()"
                + notRowLeasedClause()
                + " AND (lease IS NULL OR lease <> ? OR enqueued_at <= now() - make_interval(secs => ? / 1000.0))"
                + " ORDER BY seq LIMIT " + limit;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setLong(3, ownFence);
            statement.setLong(4, handoffGraceMillis);
            return readRows(statement);
        }
    }

    /**
     * Acknowledge a contiguous prefix in one range delete, and any out-of-order remainder in a
     * second statement.
     * <p>
     * A range delete over physically contiguous rows is far cheaper than the same number of
     * scattered single-row deletes — for the delete itself and for the vacuum that follows. Acks in
     * a shard are usually contiguous precisely because the owner reads in sequence order, so the
     * common case is the cheap one.
     */
    public int acknowledge(Connection connection, int shard, long contiguousThrough, Collection<Long> stragglers,
                           String owner, long fence) throws SQLException {
        return acknowledge(connection, shard, 0L, contiguousThrough, stragglers, owner, fence);
    }

    /**
     * {@link #acknowledge(Connection, int, long, Collection, String, long)} with the range delete bounded below:
     * {@code rangeAbove < seq <= contiguousThrough}.
     * <p>
     * A new owner starts its cursor just below the shard's first readable row instead of at 0, and a message whose
     * sequence value was allocated before that point but committed after it is delivered by the head sweep. The
     * range delete must not reach down to it before it is delivered, so the owner bounds the range at that point and
     * acknowledges anything at or below it by exact sequence value.
     */
    public int acknowledge(Connection connection, int shard, long rangeAbove, long contiguousThrough, Collection<Long> stragglers,
                           String owner, long fence) throws SQLException {
        var deleted = 0;
        if (contiguousThrough > rangeAbove) {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM " + UNORDERED_TABLE + " u WHERE u.queue_id = ? AND u.shard = ? AND u.seq <= ? AND u.seq > ?"
                            // A row a session holds is not the owner's to delete. Without this the range
                            // delete would remove it once its hole expired — undelivered, and while a
                            // session was still working on it.
                            + " AND (u.lease_until IS NULL OR u.lease_until <= now())"
                            // Nor is a row that is not visible yet: a delayed message, or one waiting out
                            // a backoff that a previous owner scheduled. Either sits below the cursor as a
                            // hole, and once the hole expired nothing in memory protected it, so the next
                            // contiguous ack deleted it undelivered. Nothing this owner handled can be
                            // invisible — a retried message, whose row is, is acknowledged by exact seq.
                            + " AND u.visible_at <= now()"
                            + stillOwnedClause())) {
                statement.setShort(1, queueId);
                statement.setInt(2, shard);
                statement.setLong(3, contiguousThrough);
                statement.setLong(4, rangeAbove);
                statement.setString(5, owner);
                statement.setLong(6, fence);
                deleted += statement.executeUpdate();
            }
        }
        if (!stragglers.isEmpty()) {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM " + UNORDERED_TABLE + " u WHERE u.queue_id = ? AND u.shard = ? AND u.seq = ANY(?)"
                            + stillOwnedClause())) {
                statement.setShort(1, queueId);
                statement.setInt(2, shard);
                statement.setArray(3, connection.createArrayOf("bigint", stragglers.toArray(Long[]::new)));
                statement.setString(4, owner);
                statement.setLong(5, fence);
                deleted += statement.executeUpdate();
            }
        }
        return deleted;
    }

    /**
     * Does this owner still hold the shard under this fence?
     * <p>
     * Needed because a delete affecting zero rows is ambiguous: it means either "the fence clause
     * rejected me" or "those rows were already gone". Reading the first as the second costs a lease
     * the owner still holds — it stops, the shard is re-acquired, and everything the outgoing owner
     * had in flight is redelivered. Asking is one primary-key lookup, and only on the ambiguous path.
     */
    public boolean stillOwns(String lane, int shard, String owner, long fence) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT 1 FROM " + LEASE_TABLE + " WHERE queue_id = ? AND lane = ? AND shard = ?"
                             + " AND owner = ? AND fence = ?")) {
            statement.setShort(1, queueId);
            statement.setString(2, lane);
            statement.setInt(3, shard);
            statement.setString(4, owner);
            statement.setLong(5, fence);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    /**
     * Allocate a fence for a pull session. Negative, so it can never be mistaken for an owner fence in
     * the shared {@code lease} column.
     */
    public long nextSessionFence() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT nextval('" + ShardOwnedSchema.SESSION_FENCE_SEQUENCE + "')");
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return -resultSet.getLong(1);
        }
    }

    /**
     * Claim up to {@code limit} unordered rows for a session, stamping each with the session's fence
     * and an expiry.
     * <p>
     * This is a write per claimed message, and that is the point rather than a regression: message and
     * batch scope buy finer granularity by paying for it, which is exactly the trade
     * {@code SessionScope} documents. The push fast path is untouched and still writes nothing.
     * <p>
     * {@code SKIP LOCKED} in the sub-select so two sessions polling the same shard step past each
     * other instead of queueing — the one place in this engine where that construct earns its keep,
     * because here there genuinely are several writers competing for the same rows.
     */
    public List<SessionRow> claimForSession(Connection connection, int shard, int limit,
                                            long sessionFence, long leaseMillis) throws SQLException {
        // The rows to update are selected by primary key - (queue_id, shard) and an array of seq - never by joining
        // the locking subquery back to the table. When the table was analyzed while drained the planner expects no
        // rows, and the join (WHERE (queue_id, shard, seq) IN (...)) became a nested loop over the shard, quadratic in
        // its size: a 32-row claim took 155 s instead of 7.5 ms at 20 000 rows per shard, and 2.8 s instead of 0.9 ms at
        // 2 000. ARRAY(...) is evaluated once, so the locked set is fixed before the UPDATE runs. Guarded by
        // ShardOwnedSessionClaimPlanIT
        try (var statement = connection.prepareStatement(claimForSessionSql())) {
            statement.setLong(1, sessionFence);
            statement.setLong(2, leaseMillis);
            statement.setShort(3, queueId);
            statement.setInt(4, shard);
            statement.setShort(5, queueId);
            statement.setInt(6, shard);
            statement.setInt(7, limit);
            try (var resultSet = statement.executeQuery()) {
                var rows = new ArrayList<SessionRow>();
                while (resultSet.next()) {
                    rows.add(new SessionRow(shard, resultSet.getLong(1), resultSet.getBytes(2),
                                            resultSet.getInt(3), resultSet.getInt(4)));
                }
                return rows;
            }
        }
    }

    /**
     * The statement {@link #claimForSession} runs. Binds, in order: the session fence, the lease in milliseconds,
     * then queue id and shard twice, then the limit
     */
    static String claimForSessionSql() {
        return "UPDATE " + UNORDERED_TABLE + " SET lease = ?, lease_until = now() + make_interval(secs => ? / 1000.0)"
                + " WHERE queue_id = ? AND shard = ? AND seq = ANY(ARRAY("
                + "   SELECT seq FROM " + UNORDERED_TABLE
                + "   WHERE queue_id = ? AND shard = ? AND visible_at <= now()"
                + "     AND (lease_until IS NULL OR lease_until <= now())"
                + "   ORDER BY seq LIMIT ? FOR UPDATE SKIP LOCKED))"
                + " RETURNING seq, payload, payload_type, attempts";
    }

    /**
     * Delete rows this session still holds. The lease is the fence: a session whose lease has lapsed
     * deletes nothing, because by then the rows are visible to the shard's owner again and may already
     * have been redelivered.
     */
    public int acknowledgeSessionRows(Connection connection, int shard, Collection<Long> seqs,
                                      long sessionFence) throws SQLException {
        if (seqs.isEmpty()) {
            return 0;
        }
        try (var statement = connection.prepareStatement(
                "DELETE FROM " + UNORDERED_TABLE + " WHERE queue_id = ? AND shard = ? AND seq = ANY(?)"
                        + " AND lease = ? AND lease_until > now()")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setArray(3, connection.createArrayOf("bigint", seqs.toArray(Long[]::new)));
            statement.setLong(4, sessionFence);
            return statement.executeUpdate();
        }
    }

    /**
     * Push every row this session holds further out. Returns how many were still its to extend.
     */
    public int extendSessionRows(Connection connection, long sessionFence, long leaseMillis) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE " + UNORDERED_TABLE + " SET lease_until = now() + make_interval(secs => ? / 1000.0)"
                        + " WHERE queue_id = ? AND lease = ? AND lease_until > now()")) {
            statement.setLong(1, leaseMillis);
            statement.setShort(2, queueId);
            statement.setLong(3, sessionFence);
            return statement.executeUpdate();
        }
    }

    /**
     * Hand back everything this session still holds, immediately. Closing without this would leave the
     * rows invisible until their leases lapsed, which is a delay nobody asked for when the session
     * shut down cleanly.
     */
    public int releaseSessionRows(Connection connection, long sessionFence) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE " + UNORDERED_TABLE + " SET lease = NULL, lease_until = NULL"
                        + " WHERE queue_id = ? AND lease = ?")) {
            statement.setShort(1, queueId);
            statement.setLong(2, sessionFence);
            return statement.executeUpdate();
        }
    }

    /**
     * Hand one row back, for {@code fail} — the caller has said it is not working on it any more.
     */
    public int releaseSessionRow(Connection connection, int shard, long seq, long sessionFence) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE " + UNORDERED_TABLE + " SET lease = NULL, lease_until = NULL"
                        + " WHERE queue_id = ? AND shard = ? AND seq = ? AND lease = ?")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setLong(3, seq);
            statement.setLong(4, sessionFence);
            return statement.executeUpdate();
        }
    }

    /**
     * A row lease session's {@code fail}: count the attempt and hand the row back, visible at once, in one statement
     * guarded by the session's row lease. The count is the stored one plus one - the session pulled it with that
     * count - never a constant, which reset the budget of every message a session failed. A row another session has
     * claimed since this one's lease lapsed is left alone.
     *
     * @return whether the row was still this session's
     */
    public boolean failSessionRow(Connection connection, int shard, long seq, long sessionFence) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE " + UNORDERED_TABLE + " SET attempts = attempts + 1, visible_at = now(), lease = NULL, lease_until = NULL"
                        + " WHERE queue_id = ? AND shard = ? AND seq = ? AND lease = ?")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setLong(3, seq);
            statement.setLong(4, sessionFence);
            return statement.executeUpdate() > 0;
        }
    }

    public record SessionRow(int shard, long seq, byte[] payload, int payloadType, int attempts) {
    }

    /**
     * Hides rows a pull session currently holds from the shard's owner, and reveals them again the
     * moment the session's lease lapses. The expiry is what makes the hand-back automatic: a session
     * whose process died leaves rows that come back on their own.
     */
    private static String notRowLeasedClause() {
        return " AND (lease_until IS NULL OR lease_until <= now())";
    }

    /**
     * The fence check of {@link #stillOwnedClause()} for either lane, against a row aliased {@code alias}.
     * Binds three parameters, in order: the lane, the owner and the fence.
     */
    private static String ownedByClause(String alias) {
        return " AND EXISTS (SELECT 1 FROM " + LEASE_TABLE + " l"
                + " WHERE l.queue_id = " + alias + ".queue_id AND l.lane = ? AND l.shard = " + alias + ".shard"
                + " AND l.owner = ? AND l.fence = ?)";
    }

    private static String laneOf(String table) {
        return ORDERED_TABLE.equals(table) ? "ordered" : "unordered";
    }

    /**
     * Fencing: a write only lands if the writer still holds the lease it was issued under.
     * <p>
     * A lease is a time-based guarantee, and a stop-the-world pause or a clock jump can make an owner
     * believe it still holds a shard that has already moved. Without this clause such an owner would
     * delete messages the new owner is about to deliver — silent loss, at exactly the moment the
     * system is already in trouble. One extra index lookup on the lease table's primary key, per
     * batch rather than per message.
     */
    private static String stillOwnedClause() {
        return " AND EXISTS (SELECT 1 FROM " + LEASE_TABLE + " l"
                + " WHERE l.queue_id = u.queue_id AND l.lane = 'unordered' AND l.shard = u.shard"
                + " AND l.owner = ? AND l.fence = ?)";
    }

    /**
     * Announce that this instance is alive and wants work.
     */
    public void heartbeatInstance(String instanceId) throws SQLException {
        heartbeat(instanceId);
    }

    /**
     * {@link #heartbeatInstance}, reporting whether the membership row was last refreshed by another process.
     * <p>
     * Every process stamps the row with its {@link #PROCESS_INCARNATION}. After this process has refreshed the row
     * once, finding another process's stamp means a second live process heartbeats under the same instance id: the
     * rebalance then counts the two as one instance, and they keep taking the same shards from each other. A process
     * restarted after a crash sees its predecessor's stamp once, on its first heartbeat, which the caller ignores.
     *
     * @return whether the row's previous stamp was another process's
     */
    public boolean heartbeat(String instanceId) throws SQLException {
        // The previous row is read by a CTE in the same statement; without FOR UPDATE it sees the row as it was
        // before the upsert
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "WITH previous AS (SELECT incarnation FROM " + INSTANCE_TABLE + " WHERE queue_id = ? AND instance_id = ?)"
                             + " INSERT INTO " + INSTANCE_TABLE + " (queue_id, instance_id, last_seen, incarnation) VALUES (?, ?, now(), ?)"
                             + " ON CONFLICT (queue_id, instance_id) DO UPDATE SET last_seen = now(), incarnation = EXCLUDED.incarnation"
                             + " RETURNING (SELECT incarnation FROM previous)")) {
            statement.setShort(1, queueId);
            statement.setString(2, instanceId);
            statement.setShort(3, queueId);
            statement.setString(4, instanceId);
            statement.setString(5, PROCESS_INCARNATION);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                var previous = resultSet.getString(1);
                return previous != null && !previous.equals(PROCESS_INCARNATION);
            }
        }
    }

    /**
     * A token per JVM, stamped on this process's membership rows - see {@link #heartbeat}. Per process rather than
     * per queue object: one process's unordered and ordered consumers share an instance id and must not look like two
     */
    static final String PROCESS_INCARNATION = UUID.randomUUID().toString();

    /**
     * Remove this instance from the membership table on the way out.
     * <p>
     * Without it a graceful stop is worse than a crash for the survivors. The departing instance
     * hands its leases back at once, so the shards are free — but its membership row goes on counting
     * as live for the whole staleness window, {@code fairShare} stays
     * {@code ceil(shardCount / liveInstances)} for a cluster that no longer exists, and every survivor
     * is already at that quota. The shards sit unowned and nobody is allowed to take them. That is
     * exactly the shape of an autoscaler scaling in.
     */
    public void deregisterInstance(String instanceId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "DELETE FROM " + INSTANCE_TABLE + " WHERE queue_id = ? AND instance_id = ?")) {
            statement.setShort(1, queueId);
            statement.setString(2, instanceId);
            statement.executeUpdate();
        }
    }

    /**
     * Drop membership rows far past any liveness window.
     * <p>
     * Rows were only ever upserted, never removed, so an instance id that changes per boot — which is
     * what an autoscaled deployment produces, and what the Spring starter defaults to — left one row
     * per pod that had ever run, forever, on a table every heartbeat of every queue scans.
     * <p>
     * The cutoff is deliberately far beyond {@code leaseTtl}: this is garbage collection, not liveness,
     * and a row young enough to matter to {@code fairShare} must never be removed by it.
     */
    public int pruneDepartedInstances(long staleAfterMillis) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "DELETE FROM " + INSTANCE_TABLE
                             + " WHERE queue_id = ? AND last_seen < now() - make_interval(secs => ? / 1000.0)")) {
            statement.setShort(1, queueId);
            statement.setLong(2, staleAfterMillis);
            return statement.executeUpdate();
        }
    }

    /**
     * Instances heartbeating for this queue, <b>floored at one</b>.
     * <p>
     * The floor is for {@code fairShare}, which divides by this and must not divide by zero. It makes
     * the value wrong for anything that wants to know whether anybody is there at all — zero
     * instances and one instance are indistinguishable — so a health report must use
     * {@link #countInstances} instead. Keeping both is deliberate: collapsing them would either
     * reintroduce a division by zero or make "nobody is consuming this queue" unreportable.
     *
     * @param staleAfterMillis an instance that has not been seen for this long is presumed gone
     */
    public int countLiveInstances(long staleAfterMillis) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + INSTANCE_TABLE
                             + " WHERE queue_id = ? AND last_seen > now() - make_interval(secs => ? / 1000.0)")) {
            statement.setShort(1, queueId);
            statement.setLong(2, staleAfterMillis);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return Math.max(1, resultSet.getInt(1));
            }
        }
    }

    /**
     * Instances heartbeating for this queue, unfloored — the truth, including zero.
     *
     * @param staleAfterMillis an instance that has not been seen for this long is presumed gone
     */
    public int countInstances(long staleAfterMillis) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + INSTANCE_TABLE
                             + " WHERE queue_id = ? AND last_seen > now() - make_interval(secs => ? / 1000.0)")) {
            statement.setShort(1, queueId);
            statement.setLong(2, staleAfterMillis);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    /**
     * Hand a shard back voluntarily by expiring its lease.
     * <p>
     * The fence is deliberately NOT bumped here — the next acquirer bumps it. Bumping on release
     * would invalidate the releasing owner's own in-flight acknowledgements before it has finished
     * draining them.
     */
    public void releaseLease(String lane, int shard, String owner) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     // owner = NULL, not a back-dated expiry: there is no expiry any more. The
                     // fence is still deliberately NOT bumped — the next acquirer bumps it, because
                     // bumping here would invalidate this owner's own in-flight acknowledgements.
                     "UPDATE " + LEASE_TABLE + " SET owner = NULL"
                             + " WHERE queue_id = ? AND lane = ? AND shard = ? AND owner = ?")) {
            statement.setShort(1, queueId);
            statement.setString(2, lane);
            statement.setInt(3, shard);
            statement.setString(4, owner);
            statement.executeUpdate();
        }
    }

    /**
     * Hand back every shard this owner holds on one lane, over ONE connection.
     * <p>
     * Same statement as {@link #releaseLease(String, int, String)} widened to a set of shards, and
     * the reason it exists is the shutdown path rather than throughput. Releasing one lease per
     * connection meant an instance holding the ordered lane's 64 units acquired 64 connections to
     * perform one logical operation — merely wasteful while the database is there, and the
     * difference between a shutdown and a hang while it is not: every acquisition then waits out the
     * pool's full {@code connectionTimeout} (30s at Hikari's default) before failing, so handing back
     * one lane of one queue took half an hour, and an application with several queues never finished
     * at all. One acquisition discovers an absent database once.
     *
     * @param shards the shards to release; releasing is bounded by {@code owner} as well, so a unit
     *               already taken away from this instance is left alone
     * @return how many leases were actually given up
     */
    public int releaseLeases(String lane, Collection<Integer> shards, String owner) throws SQLException {
        if (shards.isEmpty()) {
            return 0;
        }
        var shardArray = shards.stream().map(Integer::shortValue).toArray(Short[]::new);
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE " + LEASE_TABLE + " SET owner = NULL"
                             + " WHERE queue_id = ? AND lane = ? AND shard = ANY(?) AND owner = ?")) {
            statement.setShort(1, queueId);
            statement.setString(2, lane);
            statement.setArray(3, connection.createArrayOf("int2", shardArray));
            statement.setString(4, owner);
            return statement.executeUpdate();
        }
    }

    /**
     * {@link #releaseLeases(String, Collection, String)}, also bounded by the fence each unit was taken under: a row
     * {@code owner} has taken again since, under a newer fence, is left alone too.
     * <p>
     * The instance id alone cannot tell two incarnations of one instance apart. A queue stopped and started again in the
     * same process keeps its instance id, and {@link #acquireLeaseGrant} hands it a row that already names it, under a new
     * fence; a release by the earlier incarnation would then free the row the new one serves under.
     *
     * @param fenceByShard each shard to release, with the fence its owner holds it under
     * @return how many leases were actually given up
     */
    public int releaseFencedLeases(String lane, Map<Integer, Long> fenceByShard, String owner) throws SQLException {
        if (fenceByShard.isEmpty()) {
            return 0;
        }
        var shards = new Integer[fenceByShard.size()];
        var fences = new Long[fenceByShard.size()];
        var index  = 0;
        for (var entry : fenceByShard.entrySet()) {
            shards[index] = entry.getKey();
            fences[index] = entry.getValue();
            index++;
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE " + LEASE_TABLE + " l SET owner = NULL"
                             + " FROM unnest(?::int[], ?::bigint[]) AS released(shard, fence)"
                             + " WHERE l.queue_id = ? AND l.lane = ? AND l.owner = ?"
                             + "   AND l.shard = released.shard AND l.fence = released.fence")) {
            statement.setArray(1, connection.createArrayOf("int", shards));
            statement.setArray(2, connection.createArrayOf("bigint", fences));
            statement.setShort(3, queueId);
            statement.setString(4, lane);
            statement.setString(5, owner);
            return statement.executeUpdate();
        }
    }

    /**
     * Take or renew the shard's lease, bumping the fence on a change of owner.
     *
     * @return the fence held, or empty if another instance holds an unexpired lease
     */
    /**
     * The fences this instance still holds on one lane, in ONE statement — and a READ, not a write.
     * <p>
     * This replaced a per-unit lease renewal, which is the whole point. The heartbeat's job here is
     * to notice that a unit was taken away and stop the owner immediately, rather than let it find
     * out at acknowledgement time when it may already have dispatched work its successor is also
     * dispatching. Noticing is a question, not an assertion of ownership, so it does not need to
     * write anything: liveness now comes from the instance row, and the fence answers "is this still
     * mine".
     * <p>
     * Measured before the change, five idle queues holding 170 units: 17 lease writes a second
     * against 6.8 reads — 71% of an idle engine's cost was renewing rows to say nothing had changed.
     *
     * @return the fence currently recorded for each shard this instance still owns, by shard
     */
    public Map<Integer, Long> heldFences(String lane, int[] shards, String owner) throws SQLException {
        if (shards.length == 0) {
            return Map.of();
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT shard, fence FROM " + LEASE_TABLE
                             + " WHERE queue_id = ? AND lane = ? AND owner = ? AND shard = ANY(?)")) {
            statement.setShort(1, queueId);
            statement.setString(2, lane);
            statement.setString(3, owner);
            statement.setArray(4, connection.createArrayOf("int", boxed(shards)));
            try (var resultSet = statement.executeQuery()) {
                var fences = new HashMap<Integer, Long>();
                while (resultSet.next()) {
                    fences.put(resultSet.getInt(1), resultSet.getLong(2));
                }
                return fences;
            }
        }
    }

    /**
     * Whether a lease row's owner is gone, for the two kinds of owner this table holds.
     * <p>
     * An <b>instance</b> leaves {@code lease_until} NULL and is judged by its row in
     * {@code shard_queue_instance}, which the heartbeat refreshes once per queue. It used to leave an
     * expiry instead, which meant WRITING one row per unit held on every heartbeat: invisible at a
     * handful of shards per lane, and the engine's dominant idle cost at a fixed sixty-four-unit
     * ordered space — measured at 17 lease writes a second against 6.8 reads, across five idle
     * queues. Liveness is a property of the instance, not of each unit it happens to hold.
     * <p>
     * A <b>pull session</b> is not an instance and heartbeats nothing, so it sets a real expiry and
     * renews it. That path is unchanged.
     * <p>
     * Correctness never rested on the expiry either way: it rests on the FENCE. An owner whose unit
     * is taken finds its fence bumped and its writes refused.
     * <p>
     * Binds one {@code ttlMillis} parameter, in statement order.
     */
    private static String ownerIsGoneClause() {
        return " ((l.lease_until IS NOT NULL AND l.lease_until <= now())"
                + "  OR (l.lease_until IS NULL AND NOT EXISTS (SELECT 1 FROM " + INSTANCE_TABLE + " i"
                + "        WHERE i.queue_id = l.queue_id AND i.instance_id = l.owner"
                + "          AND i.last_seen > now() - make_interval(secs => ? / 1000.0))))";
    }

    /**
     * Take or renew a unit for a pull SESSION, which is not an instance and heartbeats nothing, so it
     * carries a real expiry. See {@link #ownerIsGoneClause()}.
     */
    public Optional<Long> acquireSessionLease(String lane, int shard, String sessionId, long ttlMillis)
            throws SQLException {
        var sql = "UPDATE " + LEASE_TABLE + " l"
                + " SET owner = ?, lease_until = now() + make_interval(secs => ? / 1000.0),"
                + "     fence = CASE WHEN l.owner = ? THEN l.fence ELSE l.fence + 1 END"
                + " WHERE l.queue_id = ? AND l.lane = ? AND l.shard = ?"
                + "   AND (l.owner IS NULL OR l.owner = ? OR" + ownerIsGoneClause() + ")"
                + " RETURNING l.fence";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setLong(2, ttlMillis);
            statement.setString(3, sessionId);
            statement.setShort(4, queueId);
            statement.setString(5, lane);
            statement.setInt(6, shard);
            statement.setString(7, sessionId);
            statement.setLong(8, ttlMillis);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getLong(1)) : Optional.empty();
            }
        }
    }

    /**
     * Take a unit for an instance. <b>Always bumps the fence</b>, including when the row already names this owner.
     * <p>
     * Keeping the fence for a matching owner is right for a renewal, which is what this statement once also was, and
     * what {@link #acquireSessionLease} still is. For an instance it no longer renews anything - liveness is the
     * instance row - and it is only ever called for a unit this queue instance does not hold in memory. So a row
     * already naming this owner belongs to an earlier incarnation: this queue before a {@code stop()} that kept an
     * ordered unit because a key was still in a handler, or an earlier process that ran under the same instance id.
     * Taking it over under the same fence left that incarnation's writes valid - an in-JVM restart could start a key
     * the old handler was still running, and two processes sharing an id both acknowledged. A new fence refuses them.
     */
    public Optional<Long> acquireLease(String lane, int shard, String owner, long ttlMillis) throws SQLException {
        return acquireLeaseGrant(lane, shard, owner, ttlMillis).map(LeaseGrant::fence);
    }

    /**
     * {@link #acquireLease}, also reporting how the previous owner left the unit.
     * <p>
     * A voluntary release ({@link #releaseLease}) clears the owner after flushing acknowledgements, so a unit taken
     * with no owner was handed over cleanly. Anything else - an expired or taken-over owner, or an earlier
     * incarnation under this owner's own id - may have left a handler mid-delivery, and only that kind of takeover
     * charges the rows the previous owner could have had in flight with an attempt. Charging every takeover made
     * each routine deploy or rebalance cost every queued message an attempt.
     */
    public Optional<LeaseGrant> acquireLeaseGrant(String lane, int shard, String owner, long ttlMillis) throws SQLException {
        // RETURNING only sees the new owner, so the old one is read by a CTE joined into the UPDATE - joined, not
        // referenced from RETURNING: a CTE is evaluated where it is first used, and from RETURNING that is after the
        // UPDATE has changed the row, where it read nothing at all and every takeover looked unclean. A concurrent
        // acquirer blocks on the row lock and re-checks the WHERE against the winner's row, which names a live
        // owner, so it takes nothing
        var sql = "WITH previous AS (SELECT owner FROM " + LEASE_TABLE
                + "   WHERE queue_id = ? AND lane = ? AND shard = ?)"
                + " UPDATE " + LEASE_TABLE + " l"
                + " SET owner = ?, lease_until = NULL, fence = l.fence + 1"
                + " FROM previous p"
                + " WHERE l.queue_id = ? AND l.lane = ? AND l.shard = ?"
                + "   AND (l.owner IS NULL OR l.owner = ? OR" + ownerIsGoneClause() + ")"
                + " RETURNING l.fence, p.owner IS NULL";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setShort(1, queueId);
            statement.setString(2, lane);
            statement.setInt(3, shard);
            statement.setString(4, owner);
            statement.setShort(5, queueId);
            statement.setString(6, lane);
            statement.setInt(7, shard);
            statement.setString(8, owner);
            statement.setLong(9, ttlMillis);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next()
                       ? Optional.of(new LeaseGrant(resultSet.getLong(1), resultSet.getBoolean(2)))
                       : Optional.empty();
            }
        }
    }

    /**
     * @param fence                 the fence the unit is now held under
     * @param previousOwnerReleased whether the previous owner released the unit voluntarily, after flushing its
     *                              acknowledgements - so it can have left nothing mid-delivery
     */
    public record LeaseGrant(long fence, boolean previousOwnerReleased) {
    }

    /**
     * The error recorded for a message dead-lettered by {@link #exhaustedAfterTakeover}, and the message of the cause
     * the owners hand to {@code QueueObserver.deadLettered} for it.
     * <p>
     * It must hold for every row that pass can park, and the pass cannot tell them apart. Usually no failure was ever
     * recorded for the message - its handler never returned, or its owner lost the shard before recording one - but
     * a pull session's failure counts on the same row and does not dead-letter, so some of the attempts may have
     * failed. The live lanes keep no error text, so there is none to carry over. The text used to say that no failure
     * was ever recorded, which was false for such a row.
     */
    public static final String EXHAUSTED_BY_TAKEOVERS = "Reached its attempt limit when the shard was taken over: an earlier "
            + "delivery ended with its process stopping (crashed or killed) or losing the shard, and any error a pull "
            + "session recorded before that is not kept on a live row";

    /**
     * Bulk attempt increment on takeover.
     * <p>
     * A handler that crashes the JVM never records its attempt, so without this a poison message
     * loops forever. It is the only reason the steady-state path can get away with never writing an
     * attempt count.
     * <p>
     * Only the rows the previous owner can have had in flight are charged, not the whole backlog: charging every
     * row made each takeover cost every queued message an attempt, so a message that sat through a few of them
     * was dead-lettered by its first real failure. The previous owner dispatched in sequence order, held at most
     * {@code inFlightBound} handlers, and had at most {@code ackBatchSize} handled rows not yet deleted, so a row
     * it could have been handling is
     * <ul>
     *     <li>among the lowest {@code inFlightBound + ackBatchSize} visible rows that have never been charged,</li>
     *     <li>a retry that had come due, which it re-dispatches from memory regardless of sequence, or</li>
     *     <li>a row it pre-claimed for local hand-off, stamped with its fence ({@code fence - 1}).</li>
     * </ul>
     * <p>
     * <b>Fenced, like the acknowledgement, retry and dead-letter writes.</b> The charge lands only while
     * {@code owner} still holds the shard's lease under {@code fence}. An owner can reach its takeover after its lease
     * has moved on - taken by another instance, or released by a {@code stop()} that ended it before its pump took it
     * over - and its charge would then fall on rows the successor may be delivering, each reaching its redelivery
     * limit one failure early.
     *
     * @param inFlightBound how many handlers the previous owner could have had running at once
     * @param ackBatchSize  how many handled rows the previous owner could have held before flushing their deletes
     * @param owner         the instance taking the shard over
     * @param fence         the fence it holds the shard under; the previous owner's is the one before it
     * @return how many rows were charged: 0 also when {@code owner} no longer holds the lease under {@code fence} -
     *         {@link #stillOwns(String, int, String, long)} tells that apart from nothing to charge
     */
    public int bumpAttemptsOnTakeover(Connection connection, int shard, int inFlightBound, int ackBatchSize, String owner, long fence)
            throws SQLException {
        requireNonNull(owner, "No owner provided");
        var window = (int) Math.min(Integer.MAX_VALUE, (long) inFlightBound + ackBatchSize);
        try (var statement = connection.prepareStatement(
                "UPDATE " + UNORDERED_TABLE + " u SET attempts = u.attempts + 1"
                        + " WHERE u.queue_id = ? AND u.shard = ? AND u.seq IN ("
                        + "   SELECT seq FROM " + UNORDERED_TABLE
                        + "   WHERE queue_id = ? AND shard = ? AND visible_at <= now()"
                        + "     AND (attempts > 0 OR lease = ?)"
                        // A row a live pull session holds is that session's delivery, not the previous owner's
                        + notRowLeasedClause()
                        + "   UNION"
                        + "   (SELECT seq FROM " + UNORDERED_TABLE
                        + "    WHERE queue_id = ? AND shard = ? AND visible_at <= now() AND attempts = 0"
                        + notRowLeasedClause()
                        + "    ORDER BY seq LIMIT ?))"
                        + ownedByClause("u"))) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setShort(3, queueId);
            statement.setInt(4, shard);
            statement.setLong(5, fence - 1);
            statement.setShort(6, queueId);
            statement.setInt(7, shard);
            statement.setInt(8, window);
            statement.setString(9, "unordered");
            statement.setString(10, owner);
            statement.setLong(11, fence);
            return statement.executeUpdate();
        }
    }

    /**
     * The visible rows at or past the redelivery policy's limit, read right after a takeover's charge. Not only the
     * rows {@link #bumpAttemptsOnTakeover} charged: any row at the limit that nobody holds, which includes one a pull
     * session failed up to it, since a session's failure counts on the row and never dead-letters. Most are messages
     * whose handler never completed - the delivering process stopped every time - so no failure will ever be
     * recorded for them, and without this they would be redelivered forever. They are parked with
     * {@link #EXHAUSTED_BY_TAKEOVERS}, whose text holds for both kinds.
     * <p>
     * Never a row nobody could have been delivering: one a live pull session holds, or an ordered row whose key is
     * held behind a retry in backoff or a dead letter. Those are not charged either, so one that reads as exhausted
     * here has a count left from before, and is dead-lettered as a failure, or parked behind the dead letter, when
     * its turn comes. A requested redelivery is never counted (see {@link #scheduleRequestedRedeliveryIfOwned}), so
     * a handler asking to wait can never bring a message here.
     *
     * @return the sequence values of the rows to dead-letter
     */
    public List<Long> exhaustedAfterTakeover(Connection connection, String table, int shard, int maxAttempts) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT a.seq FROM " + table + " a WHERE a.queue_id = ? AND a.shard = ? AND a.attempts >= ? AND a.visible_at <= now()"
                        + (ORDERED_TABLE.equals(table) ? orderedKeyNotHeldClause("a") : notRowLeasedClause())
                        + " ORDER BY a.seq")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setInt(3, maxAttempts);
            try (var resultSet = statement.executeQuery()) {
                var seqs = new ArrayList<Long>();
                while (resultSet.next()) {
                    seqs.add(resultSet.getLong(1));
                }
                return seqs;
            }
        }
    }

    /**
     * The attempt count stored on a live row, or 0 when the row is gone.
     * <p>
     * Read on the failure path only, the first time a message fails under an owner: the delivery read leaves the
     * count out on purpose (see {@code MessageHandler}), and an owner that seeded its in-memory count from 1 gave
     * every message a fresh budget on every takeover.
     */
    public int storedAttempts(Connection connection, String table, int shard, long seq) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT attempts FROM " + table + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setLong(3, seq);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    /**
     * The shard count the registry currently holds for this queue, or empty if it has no registry row
     * — a queue registered by raw id rather than by name has nothing to grow.
     */
    public OptionalInt currentShardCount() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT shard_count FROM " + REGISTRY_TABLE + " WHERE queue_id = ?")) {
            statement.setShort(1, queueId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? OptionalInt.of(resultSet.getInt(1)) : OptionalInt.empty();
            }
        }
    }

    /**
     * The ordered routing space THIS QUEUE's data was written under.
     * <p>
     * Read from the registry rather than taken from {@link ShardOwnedSchema#ORDERED_UNITS}, and that
     * distinction is the whole point: the constant is only the default for a queue being created. A
     * key's unit is {@code mix(hash(key)) mod} this number, so a queue that already holds data must
     * keep routing by the number it was created with, whatever the running build would choose today.
     * Otherwise upgrading the default silently re-routes live keys — or, if guarded, refuses to start
     * and needs a human to drain and re-create the queue, which is the manual step this engine is
     * supposed not to have.
     * <p>
     * Falls back to the constant only when the queue is not registered, which is a caller error every
     * other path already reports.
     */
    public int orderedUnits() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT ordered_units FROM " + REGISTRY_TABLE + " WHERE queue_id = ?")) {
            statement.setShort(1, queueId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : ShardOwnedSchema.ORDERED_UNITS;
            }
        }
    }

    /**
     * How many shards of each lane have a live owner, in one query against the lease table.
     * <p>
     * The lease table is small — one row per shard per lane — so this is cheap enough to poll, which
     * is the point: it is the only signal that distinguishes "nobody is consuming this queue" from
     * "this queue is busy", and those look identical in depth.
     */
    public int[] ownedShardsPerLane(long ttlMillis) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT l.lane, count(*) FILTER (WHERE l.owner IS NOT NULL AND NOT"
                             + ownerIsGoneClause() + ")"
                             + " FROM " + LEASE_TABLE + " l WHERE l.queue_id = ? GROUP BY l.lane")) {
            statement.setLong(1, ttlMillis);
            statement.setShort(2, queueId);
            var owned = new int[]{0, 0};
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if ("ordered".equals(resultSet.getString(1))) {
                        owned[1] = resultSet.getInt(2);
                    } else {
                        owned[0] = resultSet.getInt(2);
                    }
                }
            }
            return owned;
        }
    }

    public long countRemaining(int shard) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + UNORDERED_TABLE + " WHERE queue_id = ? AND shard = ?")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    /**
     * How many messages one unordered shard holds, and when the oldest of them that is ready for delivery
     * became ready - in one statement, so {@link PostgresqlMessageQueue#depth()} costs no more round trips
     * than counting alone. A row a live pull session holds is out for delivery, not ready; see
     * {@link dk.trustworks.essentials.components.queue.shardowned.spi.QueueDepth#oldestReadyAt()} for what
     * "ready" cannot exclude.
     */
    public ShardDepth depthOf(int shard) throws SQLException {
        return shardDepth("SELECT count(*), min(visible_at) FILTER (WHERE visible_at <= now()" + notRowLeasedClause() + ")"
                                  + " FROM " + UNORDERED_TABLE + " WHERE queue_id = ? AND shard = ?", shard);
    }

    /**
     * {@link #depthOf(int)} for one unit of the ordered lane. The ordered lane has no pull sessions, so
     * every visible row counts as ready - including one waiting behind its key's head while the head is
     * being handled.
     */
    public ShardDepth orderedDepthOf(int unit) throws SQLException {
        return shardDepth("SELECT count(*), min(visible_at) FILTER (WHERE visible_at <= now())"
                                  + " FROM " + ORDERED_TABLE + " WHERE queue_id = ? AND shard = ?", unit);
    }

    private ShardDepth shardDepth(String sql, int shard) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                var oldestReady = resultSet.getTimestamp(2);
                return new ShardDepth(resultSet.getLong(1), oldestReady != null ? oldestReady.toInstant() : null);
            }
        }
    }

    /**
     * @param count         messages in the shard
     * @param oldestReadyAt when the oldest ready one became ready, or {@code null} when none is ready
     */
    public record ShardDepth(long count, Instant oldestReadyAt) {
    }

    public List<Long> allSeqs(int shard) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT seq FROM " + UNORDERED_TABLE + " WHERE queue_id = ? AND shard = ?")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            try (var resultSet = statement.executeQuery()) {
                var seqs = new ArrayList<Long>();
                while (resultSet.next()) {
                    seqs.add(resultSet.getLong(1));
                }
                return seqs;
            }
        }
    }

    private static List<Row> readRows(PreparedStatement statement) throws SQLException {
        try (var resultSet = statement.executeQuery()) {
            var rows = new ArrayList<Row>();
            while (resultSet.next()) {
                rows.add(new Row(resultSet.getLong(1), resultSet.getBytes(2), resultSet.getInt(3)));
            }
            return rows;
        }
    }


    // ---------------- ordered lane ----------------

    /**
     * Enqueue ordered messages. The caller has already routed each to the shard its key hashes to,
     * so a key's messages can only ever land in one shard — which is what lets the owner enforce
     * order in memory instead of asking the database to find each key's head.
     *
     * @return the allocated sequence values, ascending. An ordered message is addressed by
     * {@code seq} like every other — {@code key_order} is the producer's ordering hint, not
     * an identity, and returning it as one produced ids that no by-id operation could resolve.
     */
    public List<Long> enqueueOrderedBatch(Connection connection, int shard, List<OrderedPayload> messages) throws SQLException {
        return inTransaction(connection, () -> enqueueOrderedBatchInternal(connection, shard, messages));
    }

    private List<Long> enqueueOrderedBatchInternal(Connection connection, int shard, List<OrderedPayload> messages) throws SQLException {
        assignTransactionIdBeforeAllocating(connection);
        var sql = "INSERT INTO " + ORDERED_TABLE
                + " (queue_id, shard, msg_key, key_order, seq, payload, payload_type, visible_at)"
                + " VALUES (?, ?, ?, ?, nextval(?::regclass), ?, ?, now() + make_interval(secs => ? / 1000.0))";
        var seqs = new ArrayList<Long>(messages.size());
        try (var statement = connection.prepareStatement(sql, new String[]{"seq"})) {
            for (var message : messages) {
                statement.setShort(1, queueId);
                statement.setInt(2, shard);
                statement.setString(3, message.key());
                statement.setLong(4, message.keyOrder());
                statement.setString(5, ShardOwnedSchema.orderedSequenceName(queueId));
                statement.setBytes(6, message.payload());
                statement.setInt(7, message.payloadType());
                statement.setLong(8, message.delayMillis());
                statement.addBatch();
            }
            statement.executeBatch();
            try (var keys = statement.getGeneratedKeys()) {
                while (keys.next()) {
                    seqs.add(keys.getLong(1));
                }
            }
        }
        Collections.sort(seqs);
        ShardWakeupListener.notifyShard(connection, queueId, "ordered", shard);
        return seqs;
    }

    /**
     * Discovery scan for the ordered lane, on the {@code seq} index.
     * <p>
     * Note what is absent: no correlated subquery asking whether an earlier {@code key_order} exists
     * for the same key, and no exclusion list of keys another thread happens to be working on. Both
     * are unnecessary because one owner sees all of a key's messages and remembers what it has in
     * flight. That query shape is the single most expensive thing a conventional ordered queue does
     * on every poll, and here it does not exist.
     */
    public List<OrderedRow> readOrderedFromCursor(Connection connection, int shard, long cursor, int limit) throws SQLException {
        var sql = "SELECT seq, msg_key, key_order, payload, payload_type FROM " + ORDERED_TABLE
                + " WHERE queue_id = ? AND shard = ? AND seq > ? AND visible_at <= now()"
                + " ORDER BY seq LIMIT " + limit;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setLong(3, cursor);
            return readOrderedRows(statement);
        }
    }

    /**
     * The identifiers of every write transaction running right now, which is how the ordered lane
     * decides when a sequence value it has stepped over can never arrive.
     * <p>
     * <b>Why identity and not ordering.</b> The obvious formulation is to keep the highest running
     * xid and wait for {@code pg_snapshot_xmin} to pass it, but {@code backend_xid} is a 32-bit
     * {@code xid} that wraps while {@code pg_snapshot_xmin} returns a non-wrapping {@code xid8}, and
     * there is no cast between them — the comparison would be wrong once per wraparound cycle and
     * correct in every test. Comparing sets by equality needs no ordering at all. Two identical xids
     * separated by a full wraparound cannot both appear inside a window measured in milliseconds.
     * <p>
     * <b>Why not the snapshot.</b> A snapshot's {@code xmax} is {@code latestCompletedXid + 1}, so a
     * transaction holding an assigned xid sits at or above it and appears in neither the in-progress
     * list nor below {@code xmax} — a single running writer reads as {@code 55486:55486:}, an
     * apparently empty snapshot. Deciding safety from {@code pg_current_snapshot()} alone therefore
     * steps over live writers. See {@code docs/durable-queue-ordered-routing-design.md} §3.
     * <p>
     * Read <em>after</em> the value it protects, never before: a transaction that allocates a
     * sequence value after this returns is not in the set, and must not be treated as retired.
     */
    public Set<Long> runningWriteTransactionIds(Connection connection) throws SQLException {
        // backend_xid is visible to an ordinary role on PostgreSQL 17.5 without pg_read_all_stats,
        // verified against a role holding only table and sequence privileges. If a deployment ever
        // redacts it this returns a SUBSET, which is unsafe rather than merely degraded, so the
        // engine probes for it at start-up rather than trusting it here.
        try (var statement = connection.prepareStatement(
                "SELECT backend_xid::text::bigint FROM pg_stat_activity WHERE backend_xid IS NOT NULL");
             var resultSet = statement.executeQuery()) {
            var running = new HashSet<Long>();
            while (resultSet.next()) {
                running.add(resultSet.getLong(1));
            }
            return running;
        }
    }

    // readOrderedSpecific is gone with the hole chase it existed for. The ordered lane no longer
    // queries for a value it stepped over, because it no longer steps over one: the cursor waits and
    // the next ordinary read picks the value up. The unordered lane keeps its equivalent
    // (readSpecific), which is still the chase path there.

    /**
     * The cursor read for MANY shards in one statement.
     * <p>
     * <b>Why {@code LATERAL} and not a join.</b> The obvious shape — a list of per-shard cursors joined
     * to the table — is plan-unstable: the planner merge-joins it, pushes the cursor down to a join
     * filter and scans the whole discovery index (68 ms under backlog, 234 ms on a sparse wake-up,
     * against 0.6 ms for the nested loop it picks in the third case). A per-row {@code LIMIT} inside a
     * {@code LATERAL} cannot be satisfied by a merge join, so the nested loop stops being a preference
     * and becomes the only legal plan.
     *
     * <b>The queue id is a PARAMETER, not this handle's.</b> These three are the only statements
     * issued by a {@link ShardPump} rather than by an owner, and a pump is shared by every queue in
     * the process — {@code ShardRuntime} hands it a storage handle bound to queue id 0 purely to open
     * connections. Binding {@code this.queueId} here therefore queried queue 0 and matched nothing,
     * silently: the cursor read returned no rows and every message arrived via the per-shard head
     * sweep instead, and the batched sweep returned no rows while the owner recorded that it had
     * swept. Group by queue and pass the owner's own id.
     *
     * @param perShardLimit rows per shard. NOT a share of one batch: each shard must be able to return
     *                      as much as it would have on its own, or this returns a different result
     *                      from the per-shard reads it replaces.
     * @return rows by shard; a shard with nothing to return is absent rather than empty
     */
    public Map<Integer, List<OrderedRow>> readOrderedFromCursors(Connection connection,
                                                                 short forQueueId,
                                                                 int[] shards,
                                                                 long[] cursors,
                                                                 int perShardLimit) throws SQLException {
        if (shards.length == 0) {
            return Map.of();
        }
        var sql = "SELECT x.shard, x.seq, x.msg_key, x.key_order, x.payload, x.payload_type"
                + " FROM unnest(?::int[], ?::bigint[]) AS cur(shard, cursor),"
                + " LATERAL (SELECT o.shard, o.seq, o.msg_key, o.key_order, o.payload, o.payload_type"
                + "          FROM " + ORDERED_TABLE + " o"
                + "          WHERE o.queue_id = ? AND o.shard = cur.shard::smallint AND o.seq > cur.cursor"
                + "            AND o.visible_at <= now()"
                + "          ORDER BY o.seq LIMIT " + perShardLimit + ") x";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("int", boxed(shards)));
            statement.setArray(2, connection.createArrayOf("bigint", boxed(cursors)));
            statement.setShort(3, forQueueId);
            return readOrderedRowsByShard(statement);
        }
    }

    /**
     * The head sweep for MANY shards in one statement. Same shape and reason as {@link #readOrderedFromCursors}.
     */
    public Map<Integer, List<OrderedRow>> sweepOrderedFromHeads(Connection connection, short forQueueId,
                                                                int[] shards,
                                                                int perShardLimit) throws SQLException {
        if (shards.length == 0) {
            return Map.of();
        }
        var sql = "SELECT x.shard, x.seq, x.msg_key, x.key_order, x.payload, x.payload_type"
                + " FROM unnest(?::int[]) AS s(shard),"
                + " LATERAL (SELECT o.shard, o.seq, o.msg_key, o.key_order, o.payload, o.payload_type"
                + "          FROM " + ORDERED_TABLE + " o"
                + "          WHERE o.queue_id = ? AND o.shard = s.shard::smallint AND o.visible_at <= now()"
                + "          ORDER BY o.seq LIMIT " + perShardLimit + ") x";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setArray(1, connection.createArrayOf("int", boxed(shards)));
            statement.setShort(2, forQueueId);
            return readOrderedRowsByShard(statement);
        }
    }

    /**
     * When each shard's earliest delayed row becomes visible, for MANY shards in one statement. The
     * SERVER computes the interval, as the single-shard form does — subtracting a client clock is what
     * makes node skew matter, and this engine does not have that bug anywhere else.
     *
     * @return milliseconds until the next visible row, by shard; a shard with nothing delayed is absent
     */
    public Map<Integer, Long> millisUntilNextVisibleByShard(Connection connection, short forQueueId,
                                                            String table, int[] shards) throws SQLException {
        if (shards.length == 0) {
            return Map.of();
        }
        try (var statement = connection.prepareStatement(
                "SELECT shard, EXTRACT(EPOCH FROM (min(visible_at) - now())) * 1000 FROM " + table
                        + " WHERE queue_id = ? AND shard = ANY(?) AND visible_at > now() GROUP BY shard")) {
            statement.setShort(1, forQueueId);
            statement.setArray(2, connection.createArrayOf("int", boxed(shards)));
            try (var resultSet = statement.executeQuery()) {
                var byShard = new HashMap<Integer, Long>();
                while (resultSet.next()) {
                    var millis = resultSet.getDouble(2);
                    if (!resultSet.wasNull()) {
                        byShard.put(resultSet.getInt(1), Math.max(0L, (long) millis));
                    }
                }
                return byShard;
            }
        }
    }

    private Map<Integer, List<OrderedRow>> readOrderedRowsByShard(PreparedStatement statement) throws SQLException {
        try (var resultSet = statement.executeQuery()) {
            var byShard = new HashMap<Integer, List<OrderedRow>>();
            while (resultSet.next()) {
                byShard.computeIfAbsent(resultSet.getInt(1), ignored -> new ArrayList<>())
                       .add(new OrderedRow(resultSet.getLong(2), resultSet.getString(3), resultSet.getLong(4),
                                           resultSet.getBytes(5), resultSet.getInt(6)));
            }
            return byShard;
        }
    }

    private static Integer[] boxed(int[] values) {
        var boxed = new Integer[values.length];
        for (var index = 0; index < values.length; index++) {
            boxed[index] = values[index];
        }
        return boxed;
    }

    private static Long[] boxed(long[] values) {
        var boxed = new Long[values.length];
        for (var index = 0; index < values.length; index++) {
            boxed[index] = values[index];
        }
        return boxed;
    }

    public List<OrderedRow> sweepOrderedFromHead(Connection connection, int shard, int limit) throws SQLException {
        var sql = "SELECT seq, msg_key, key_order, payload, payload_type FROM " + ORDERED_TABLE
                + " WHERE queue_id = ? AND shard = ? AND visible_at <= now() ORDER BY seq LIMIT " + limit;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            return readOrderedRows(statement);
        }
    }

    /**
     * Acknowledge by sequence value rather than as a contiguous range.
     * <p>
     * A deliberate deviation from §4.3, which specifies a range delete. The ordered lane's
     * acknowledgements are inherently scattered — keys progress independently, so what has been
     * handled is not a contiguous run of {@code seq} — and inventing a second range-delete floor here
     * would repeat the message-loss bug §4.3 now warns about for a lane where the range would rarely
     * be contiguous anyway. Batched by {@code ANY}, so it is still one round trip.
     */
    public int acknowledgeOrdered(Connection connection, int shard, Collection<Long> seqs,
                                  String owner, long fence) throws SQLException {
        if (seqs.isEmpty()) {
            return 0;
        }
        // Fenced, exactly as the unordered lane is. Its absence here was the gap: the lane whose
        // purpose is ordering was the one lane a superseded owner could still delete from.
        try (var statement = connection.prepareStatement(
                "DELETE FROM " + ORDERED_TABLE + " o WHERE o.queue_id = ? AND o.shard = ? AND o.seq = ANY(?)"
                        + " AND EXISTS (SELECT 1 FROM " + LEASE_TABLE + " l"
                        + " WHERE l.queue_id = o.queue_id AND l.lane = 'ordered' AND l.shard = o.shard"
                        + " AND l.owner = ? AND l.fence = ?)")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setArray(3, connection.createArrayOf("bigint", seqs.toArray(Long[]::new)));
            statement.setString(4, owner);
            statement.setLong(5, fence);
            return statement.executeUpdate();
        }
    }

    /**
     * {@link #bumpAttemptsOnTakeover} for the ordered lane. A key has at most one message in a handler, behind at
     * most {@code ackBatchSize} handled rows of the same key whose deletes had not been flushed, so a row the previous
     * owner could have been handling is among the first {@code ackBatchSize + 1} rows of its key, or a retry that
     * had come due.
     * <p>
     * <b>Only visible rows.</b> A row whose {@code visible_at} is still in the future was not being
     * delivered by anybody — it is either delayed or waiting out a retry backoff — so there is no
     * unrecorded attempt to account for. Leaving it alone is also what keeps {@code attempts > 0} on a
     * not-yet-visible row meaning "a retry pushed this out" (see {@link #orderedRetriesInBackoff}):
     * bumping a delayed row here would make it look like a retry to the next takeover, and block its key.
     * The position is counted among the visible rows only: a delayed row ahead of the one in a handler used to
     * take a place in the window and could push that one out of it, so a message that took the process down
     * every time was never charged.
     * <p>
     * <b>Not a held key.</b> A key whose earlier message is waiting out a retry backoff, or is dead-lettered, was
     * not being delivered at all - the previous owner held it, and the successor holds it too (see
     * {@link #orderedRetriesInBackoff}, {@link #orderedDeadLetterBlocks}). Charging the visible rows queued behind
     * it cost each of them an attempt per takeover, and enough takeovers dead-lettered them, never delivered.
     * <p>
     * <b>Fenced</b>, for the reason {@link #bumpAttemptsOnTakeover} is: the charge lands only while {@code owner} still
     * holds the unit's lease under {@code fence}.
     *
     * @param owner the instance taking the unit over
     * @param fence the fence it holds the unit under
     * @return how many rows were charged: 0 also when {@code owner} no longer holds the lease under {@code fence} -
     *         {@link #stillOwns(String, int, String, long)} tells that apart from nothing to charge
     */
    public int bumpOrderedAttemptsOnTakeover(Connection connection, int shard, int ackBatchSize, String owner, long fence) throws SQLException {
        requireNonNull(owner, "No owner provided");
        var window = (int) Math.min(Integer.MAX_VALUE, (long) ackBatchSize + 1);
        try (var statement = connection.prepareStatement(
                "UPDATE " + ORDERED_TABLE + " o SET attempts = o.attempts + 1"
                        + " WHERE o.queue_id = ? AND o.shard = ? AND o.seq IN ("
                        + "   SELECT seq FROM ("
                        + "     SELECT v.seq, v.attempts,"
                        + "            row_number() OVER (PARTITION BY v.msg_key ORDER BY v.key_order) AS position_in_key"
                        + "     FROM " + ORDERED_TABLE + " v WHERE v.queue_id = ? AND v.shard = ? AND v.visible_at <= now()"
                        + orderedKeyNotHeldClause("v") + ") r"
                        + "   WHERE r.position_in_key <= ? OR r.attempts > 0)"
                        + ownedByClause("o"))) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setShort(3, queueId);
            statement.setInt(4, shard);
            statement.setInt(5, window);
            statement.setString(6, "ordered");
            statement.setString(7, owner);
            statement.setLong(8, fence);
            return statement.executeUpdate();
        }
    }

    /**
     * What a requested redelivery leaves in an ordered row's {@code lease} column, which the ordered lane does not
     * otherwise use. A requested redelivery is not an attempt, so it leaves {@code attempts} alone - and with it the
     * only thing that told a row waiting out a retry from a delayed message. This tells it instead, so a successor
     * holds the key until the redelivery is due (see {@link #orderedRetriesInBackoff}). Only read together with
     * {@code visible_at > now()}; every write that pushes an ordered row's visibility out sets or clears it.
     */
    static final long REDELIVERY_REQUESTED = -1L;

    /**
     * Whether an ordered row that is not yet visible is waiting out a retry or a requested redelivery - rather than
     * delayed by its producer - which holds its key. {@code alias} is the row's table alias, or empty.
     */
    private static String orderedRetryPending(String alias) {
        var prefix = alias.isEmpty() ? "" : alias + ".";
        return "(" + prefix + "attempts > 0 OR " + prefix + "lease = " + REDELIVERY_REQUESTED + ")";
    }

    /**
     * Excludes an ordered row aliased {@code alias} whose key is held at an earlier {@code key_order}: by a row
     * waiting out a retry or a requested redelivery, or by a dead letter. Nobody delivers such a row, so a
     * takeover must neither charge it nor dead-letter it as exhausted.
     */
    private static String orderedKeyNotHeldClause(String alias) {
        return " AND NOT EXISTS (SELECT 1 FROM " + ORDERED_TABLE + " b"
                + "  WHERE b.queue_id = " + alias + ".queue_id AND b.shard = " + alias + ".shard"
                + "    AND b.msg_key = " + alias + ".msg_key AND b.key_order < " + alias + ".key_order"
                + "    AND b.visible_at > now() AND " + orderedRetryPending("b") + ")"
                + " AND NOT EXISTS (SELECT 1 FROM " + DLQ_TABLE + " d"
                + "  WHERE d.queue_id = " + alias + ".queue_id AND d.shard = " + alias + ".shard"
                + "    AND d.source_lane = 'ordered' AND d.msg_key = " + alias + ".msg_key"
                + "    AND d.key_order < " + alias + ".key_order)";
    }

    /**
     * An ordered row a retry has pushed into the future, and how long until it is visible again.
     *
     * @param row          the message, with its payload, ready to be re-dispatched when due
     * @param delayMillis  the remaining backoff, measured by the server as an interval
     */
    public record OrderedRetryInBackoff(OrderedRow row, long delayMillis) {
    }

    /**
     * The ordered rows of this shard that are waiting out a retry backoff, read by a new owner on
     * takeover (N-16).
     * <p>
     * The owner that scheduled a retry keeps the message's key blocked in memory until the retry is
     * due, so a later {@code key_order} cannot overtake the one that failed. A successor has none of
     * that memory, and every other read it makes filters on {@code visible_at <= now()} — so without
     * this it delivered the key's next message while the failed one was still in its backoff, after
     * any rebalance, graceful stop or crash.
     * <p>
     * A retry is told apart from a delayed message by {@code attempts > 0}: {@link #scheduleRetry}
     * always writes a positive count, an enqueue writes 0, and {@link #bumpOrderedAttemptsOnTakeover}
     * no longer touches a row that is not yet visible. A requested redelivery, which is not counted, is
     * told apart by {@link #REDELIVERY_REQUESTED} in the row's otherwise unused {@code lease} column. A delayed ordered message therefore still does
     * not block its key, exactly as before. A row an older version bumped while it was delayed reads
     * as a retry and holds its key until it is visible — the conservative direction.
     * <p>
     * The remaining backoff is the server's interval, never {@code visible_at} compared with a client
     * clock. On the {@code *_visible} index; paid once per takeover.
     */
    public List<OrderedRetryInBackoff> orderedRetriesInBackoff(Connection connection, int shard) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT seq, msg_key, key_order, payload, payload_type,"
                        + " CEIL(EXTRACT(EPOCH FROM (visible_at - now())) * 1000)::bigint"
                        + " FROM " + ORDERED_TABLE
                        + " WHERE queue_id = ? AND shard = ? AND visible_at > now() AND " + orderedRetryPending("")
                        + " ORDER BY msg_key, key_order")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            try (var resultSet = statement.executeQuery()) {
                var retries = new ArrayList<OrderedRetryInBackoff>();
                while (resultSet.next()) {
                    retries.add(new OrderedRetryInBackoff(new OrderedRow(resultSet.getLong(1),
                                                                         resultSet.getString(2),
                                                                         resultSet.getLong(3),
                                                                         resultSet.getBytes(4),
                                                                         resultSet.getInt(5)),
                                                          Math.max(0L, resultSet.getLong(6))));
                }
                return retries;
            }
        }
    }

    /**
     * Whether this key already has anything waiting on the ordered lane.
     * <p>
     * A prefix seek on the ordered table's primary key {@code (queue_id, shard, msg_key, key_order)},
     * so it costs an index lookup rather than a scan. Not on the delivery path: the caller is a
     * producer deciding whether it may handle an event inline or must queue it behind what is already
     * there, which happens once per event on the producing thread.
     */
    public boolean hasOrderedMessagesForKey(int shard, String key) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT 1 FROM " + ORDERED_TABLE
                             + " WHERE queue_id = ? AND shard = ? AND msg_key = ? LIMIT 1")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setString(3, key);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    public long countOrderedRemaining(int shard) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + ORDERED_TABLE + " WHERE queue_id = ? AND shard = ?")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private static List<OrderedRow> readOrderedRows(PreparedStatement statement) throws SQLException {
        try (var resultSet = statement.executeQuery()) {
            var rows = new ArrayList<OrderedRow>();
            while (resultSet.next()) {
                rows.add(new OrderedRow(resultSet.getLong(1),
                                        resultSet.getString(2),
                                        resultSet.getLong(3),
                                        resultSet.getBytes(4),
                                        resultSet.getInt(5)));
            }
            return rows;
        }
    }


    // ---------------- failure path (§4.6) ----------------

    /**
     * Record a retry: bump the attempt count and push the message's visibility out by the backoff.
     * <p>
     * Not a HOT update, though it was once described as one: {@code visible_at} is indexed (the {@code _visible}
     * index the head sweep and delayed messages need), and PostgreSQL only updates a row in place when no indexed
     * column changes. So a retry also writes an index entry. That is acceptable on a path that only failures take.
     * <p>
     * This write is durability backup, not the retry mechanism. The owner keeps the message in an
     * in-memory timer wheel and re-dispatches from there without reading it back; the row exists so
     * that a crash does not lose the backoff.
     */
    public void scheduleRetry(Connection connection, String table, int shard, long seq, int attempts, long delayMillis) throws SQLException {
        scheduleRetry(connection, table, shard, seq, attempts, delayMillis, null, null, 0L);
    }

    /**
     * {@link #scheduleRetry(Connection, String, int, long, int, long)}, fenced the way acknowledgements are: the
     * row is only touched while {@code owner} still holds the shard's lease under {@code fence}. An owner that
     * has lost its lease must not push out the visibility of, or overwrite the attempt count on, a row its
     * successor may be delivering.
     *
     * @return false, with nothing changed, when the owner no longer holds the lease or the row is gone -
     *         {@link #stillOwns(String, int, String, long)} tells the two apart
     */
    public boolean scheduleRetryIfOwned(Connection connection, String table, int shard, long seq, int attempts, long delayMillis,
                                        String owner, long fence) throws SQLException {
        requireNonNull(owner, "No owner provided");
        return scheduleRetry(connection, table, shard, seq, attempts, delayMillis, laneOf(table), owner, fence);
    }

    /**
     * {@link #scheduleRetryIfOwned}, counting the attempt on the stored count ({@code attempts + 1}) rather than
     * writing one the caller tracked. For a pull session, which keeps no count in memory - it may be gone before
     * the retry is due - so the row is the only count there is.
     */
    public boolean countAndScheduleRetryIfOwned(Connection connection, String table, int shard, long seq, long delayMillis,
                                                String owner, long fence) throws SQLException {
        requireNonNull(owner, "No owner provided");
        return scheduleRetry(connection, table, shard, seq, null, delayMillis, laneOf(table), owner, fence);
    }

    /**
     * Schedule a redelivery the handler asked for ({@code RedeliveryRequested}), fenced as
     * {@link #scheduleRetryIfOwned}. Only the visibility moves: a requested redelivery is not an attempt, so the
     * count is left as it is. The redelivery policy's budget is for failures, and a takeover's charge (see
     * {@link #exhaustedAfterTakeover}) is for deliveries that never finished - a handler that asked to wait did
     * finish. An ordered row is marked with {@link #REDELIVERY_REQUESTED}, so a successor still holds its key.
     *
     * @return false, with nothing changed, when the owner no longer holds the lease or the row is gone
     */
    public boolean scheduleRequestedRedeliveryIfOwned(Connection connection, String table, int shard, long seq, long delayMillis,
                                                      String owner, long fence) throws SQLException {
        requireNonNull(owner, "No owner provided");
        try (var statement = connection.prepareStatement(
                "UPDATE " + table + " u SET visible_at = now() + make_interval(secs => ? / 1000.0)"
                        + (ORDERED_TABLE.equals(table) ? ", lease = " + REDELIVERY_REQUESTED : "")
                        + " WHERE u.queue_id = ? AND u.shard = ? AND u.seq = ?"
                        + ownedByClause("u"))) {
            statement.setLong(1, delayMillis);
            statement.setShort(2, queueId);
            statement.setInt(3, shard);
            statement.setLong(4, seq);
            statement.setString(5, laneOf(table));
            statement.setString(6, owner);
            statement.setLong(7, fence);
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * @param attempts the count to write, or null to count this attempt on the stored one
     */
    private boolean scheduleRetry(Connection connection, String table, int shard, long seq, Integer attempts, long delayMillis,
                                  String lane, String owner, long fence) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE " + table + " u SET attempts = " + (attempts != null ? "?" : "u.attempts + 1")
                        + ", visible_at = now() + make_interval(secs => ? / 1000.0)"
                        // A failure, not a requested redelivery: clear the mark (see REDELIVERY_REQUESTED)
                        + (ORDERED_TABLE.equals(table) ? ", lease = NULL" : "")
                        + " WHERE u.queue_id = ? AND u.shard = ? AND u.seq = ?"
                        + (owner != null ? ownedByClause("u") : ""))) {
            var index = 1;
            if (attempts != null) {
                statement.setInt(index++, attempts);
            }
            statement.setLong(index++, delayMillis);
            statement.setShort(index++, queueId);
            statement.setInt(index++, shard);
            statement.setLong(index++, seq);
            if (owner != null) {
                statement.setString(index++, lane);
                statement.setString(index++, owner);
                statement.setLong(index, fence);
            }
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * Move a message to the dead letter lane and remove it from the live one, atomically.
     * <p>
     * Both statements share a transaction because the alternative failure modes are both bad: a
     * message in neither lane is lost, and a message in both is delivered again after being parked.
     *
     * @return false, with nothing changed, when the message was no longer in its lane. The move used to
     *         succeed silently in that case, which let a caller report a dead letter that did not exist
     */
    public boolean moveToDeadLetter(Connection connection, String table, String lane, int shard, long seq, String error) throws SQLException {
        return moveToDeadLetter(connection, table, lane, shard, seq, error, null, 0L);
    }

    /**
     * {@link #moveToDeadLetter(Connection, String, String, int, long, String)}, fenced the way acknowledgements
     * are: nothing moves unless {@code owner} still holds the shard's lease under {@code fence}, so an owner that
     * has lost its lease cannot park a message its successor may be delivering.
     *
     * @return false, with nothing changed, when the owner no longer holds the lease or the row is gone -
     *         {@link #stillOwns(String, int, String, long)} tells the two apart
     */
    public boolean moveToDeadLetterIfOwned(Connection connection, String table, String lane, int shard, long seq, String error,
                                           String owner, long fence) throws SQLException {
        requireNonNull(owner, "No owner provided");
        return moveToDeadLetter(connection, table, lane, shard, seq, error, owner, fence);
    }

    private boolean moveToDeadLetter(Connection connection, String table, String lane, int shard, long seq, String error,
                                     String owner, long fence) throws SQLException {
        var autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            var keyColumns = ORDERED_TABLE.equals(table) ? "msg_key, key_order" : "NULL, NULL";
            try (var insert = connection.prepareStatement(
                    "INSERT INTO " + DLQ_TABLE + " (queue_id, shard, source_lane, msg_key, key_order, seq,"
                            + " payload, payload_type, attempts, last_error)"
                            + " SELECT queue_id, shard, ?, " + keyColumns + ", seq, payload, payload_type, attempts, ?"
                            + " FROM " + table + " m WHERE m.queue_id = ? AND m.shard = ? AND m.seq = ?"
                            + (owner != null ? ownedByClause("m") : ""))) {
                insert.setString(1, lane);
                insert.setString(2, error);
                insert.setShort(3, queueId);
                insert.setInt(4, shard);
                insert.setLong(5, seq);
                if (owner != null) {
                    insert.setString(6, lane);
                    insert.setString(7, owner);
                    insert.setLong(8, fence);
                }
                if (insert.executeUpdate() == 0) {
                    connection.rollback();
                    return false;
                }
            }
            try (var delete = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
                delete.setShort(1, queueId);
                delete.setInt(2, shard);
                delete.setLong(3, seq);
                delete.executeUpdate();
            }
            connection.commit();
            return true;
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    /**
     * The ordered keys this shard has a dead letter for, and the lowest {@code key_order} it was
     * recorded at.
     * <p>
     * This is where "a key never advances past a dead letter" is <em>derived</em> from, rather than
     * remembered. An owner that kept the block only in memory would lose it on every rebalance and
     * every restart, and the key would silently resume delivering past the message nobody handled —
     * the failure this rule exists to prevent, reintroduced by the mechanism meant to enforce it.
     * <p>
     * The dead-letter row already carries what is needed: {@link #moveToDeadLetter} copies
     * {@code msg_key} and {@code key_order} for anything coming from the ordered lane. So the state is
     * one grouped read on the {@code (queue_id, shard)} index, paid at takeover and then only while a
     * block actually exists.
     */
    public Map<String, Long> orderedDeadLetterBlocks(Connection connection, int shard) throws SQLException {
        var blocks = new HashMap<String, Long>();
        try (var statement = connection.prepareStatement(
                "SELECT msg_key, min(key_order) FROM " + DLQ_TABLE
                        + " WHERE queue_id = ? AND shard = ? AND source_lane = 'ordered' AND msg_key IS NOT NULL"
                        + " GROUP BY msg_key")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    blocks.put(resultSet.getString(1), resultSet.getLong(2));
                }
            }
        }
        return blocks;
    }

    /**
     * Move messages that are behind a dead letter on their key into the dead-letter table, unhandled.
     * <p>
     * One statement for the batch, and the reason it is a batch at all is that this runs on a
     * degraded key: while the block stands, everything that arrives for that key comes through here.
     * Doing it per row would be a transaction per message on a path that only ever sees a backlog.
     * <p>
     * The rows land with {@code blocked_by_key_order} set to the {@code key_order} of the dead letter
     * holding their key — a real column, because it is the discriminator between the two kinds of row
     * this table now holds, and an operator who cannot tell a message that failed from one that never
     * ran cannot tell a broken handler from a broken key. The error text says the same thing in
     * words, for whoever is reading the table rather than the record.
     * <p>
     * The blocking order is looked up per row inside the insert rather than passed in, so the value on
     * the row is the table's own answer at the moment of the write. It is the lowest {@code key_order}
     * for that key that is dead-lettered <em>and not itself blocked</em> — the message that actually
     * has to be dealt with, not the one immediately in front — or, once an operator has deleted that
     * one, the lowest dead letter left for the key.
     *
     * @return the sequence values actually moved; a row whose key no longer has a dead letter below it
     *         stays in its lane and is left out
     */
    public Set<Long> poisonOrderedBehindDeadLetter(Connection connection, int shard, Collection<Long> seqs,
                                                   String error) throws SQLException {
        if (seqs.isEmpty()) {
            return Set.of();
        }
        // One statement, and only rows the TABLE says are blocked. The owner decides which rows to move
        // from its in-memory blocks, which it re-reads only on the sweep cadence; a dead letter an
        // operator deleted in the meantime still looks like a block there. Moving on that stale answer
        // dead-lettered the key's next message with no dead letter left to block it - so it became the
        // new block, and deleting a dead letter put the key straight back behind another one. The
        // EXISTS asks the table instead, and the caller hands back whatever was not moved.
        try (var statement = connection.prepareStatement(
                "WITH moved AS ("
                        + " DELETE FROM " + ORDERED_TABLE + " o"
                        + "  WHERE o.queue_id = ? AND o.shard = ? AND o.seq = ANY(?)"
                        // ANY dead letter below it, the same rule the owner blocks by
                        // (orderedDeadLetterBlocks). Counting only one that had itself failed disagreed
                        // with the owner once that one was deleted and rows parked behind it remained:
                        // the owner kept handing the row over and this kept refusing it, every pass.
                        + "    AND EXISTS (SELECT 1 FROM " + DLQ_TABLE + " d"
                        + "                 WHERE d.queue_id = o.queue_id AND d.shard = o.shard"
                        + "                   AND d.source_lane = 'ordered' AND d.msg_key = o.msg_key"
                        + "                   AND d.key_order < o.key_order)"
                        + "  RETURNING o.queue_id, o.shard, o.msg_key, o.key_order, o.seq, o.payload, o.payload_type, o.attempts)"
                        + " INSERT INTO " + DLQ_TABLE + " (queue_id, shard, source_lane, msg_key, key_order, seq,"
                        + " payload, payload_type, attempts, last_error, blocked_by_key_order)"
                        + " SELECT m.queue_id, m.shard, 'ordered', m.msg_key, m.key_order, m.seq, m.payload, m.payload_type,"
                        + "        m.attempts, ?,"
                        // The message that has to be dealt with: the lowest one that failed, or, once
                        // that has been deleted, the lowest dead letter still holding the key.
                        + "        COALESCE((SELECT min(d.key_order) FROM " + DLQ_TABLE + " d"
                        + "                   WHERE d.queue_id = m.queue_id AND d.shard = m.shard"
                        + "                     AND d.source_lane = 'ordered' AND d.msg_key = m.msg_key"
                        + "                     AND d.blocked_by_key_order IS NULL),"
                        + "                 (SELECT min(d.key_order) FROM " + DLQ_TABLE + " d"
                        + "                   WHERE d.queue_id = m.queue_id AND d.shard = m.shard"
                        + "                     AND d.source_lane = 'ordered' AND d.msg_key = m.msg_key))"
                        + "   FROM moved m"
                        + " RETURNING seq")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setArray(3, connection.createArrayOf("bigint", seqs.toArray(Long[]::new)));
            statement.setString(4, error);
            var moved = new HashSet<Long>();
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    moved.add(resultSet.getLong(1));
                }
            }
            return moved;
        }
    }


    /**
     * What the dead-letter table holds for this queue right now, in one read.
     *
     * @param total       every dead letter
     * @param parked      of those, the ones parked unhandled behind another dead letter on their key
     * @param blockedKeys ordered keys with at least one dead letter, which is what stops a key
     */
    public record DeadLetterSummary(long total, long parked, long blockedKeys) {
    }

    public DeadLetterSummary deadLetterSummary() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*),"
                             + "       count(*) FILTER (WHERE blocked_by_key_order IS NOT NULL),"
                             + "       count(DISTINCT msg_key) FILTER (WHERE source_lane = 'ordered')"
                             + "  FROM " + DLQ_TABLE + " WHERE queue_id = ?")) {
            statement.setShort(1, queueId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return new DeadLetterSummary(resultSet.getLong(1), resultSet.getLong(2), resultSet.getLong(3));
            }
        }
    }

    public long countDeadLetters() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + DLQ_TABLE + " WHERE queue_id = ?")) {
            statement.setShort(1, queueId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    public List<DeadLetter> deadLetters() throws SQLException {
        return deadLetters(0, Integer.MAX_VALUE);
    }

    /**
     * @param blockedByKeyOrder the {@code key_order} of the dead letter that stopped this message's
     *                          key, or null when this message was itself tried and failed. It is the
     *                          discriminator between the two kinds of row — {@code attempts} is not,
     *                          since a takeover bumps it on rows that were never delivered.
     */
    public record DeadLetter(String lane, String key, int shard, long seq, byte[] payload, int attempts, String error,
                             int payloadType, Long blockedByKeyOrder, long keyOrder) {
    }

    public List<DeadLetter> deadLetters(int offset, int limit) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT source_lane, msg_key, seq, payload, attempts, last_error, shard, payload_type,"
                             + " blocked_by_key_order, COALESCE(key_order, 0) FROM " + DLQ_TABLE
                             + " WHERE queue_id = ? ORDER BY id OFFSET ? LIMIT ?")) {
            statement.setShort(1, queueId);
            statement.setInt(2, offset);
            statement.setInt(3, limit);
            try (var resultSet = statement.executeQuery()) {
                var rows = new ArrayList<DeadLetter>();
                while (resultSet.next()) {
                    // Read AND tested before anything else touches the result set: wasNull() reports
                    // on the most recent get, so asking it inside the constructor call would answer
                    // for payload_type — which is never null, so every row would come back as
                    // blocked and every dead letter would claim it had never been delivered.
                    var blockedBy         = resultSet.getLong(9);
                    var blockedByKeyOrder = resultSet.wasNull() ? null : blockedBy;
                    rows.add(new DeadLetter(resultSet.getString(1), resultSet.getString(2), resultSet.getInt(7),
                                            resultSet.getLong(3), resultSet.getBytes(4), resultSet.getInt(5),
                                            resultSet.getString(6), resultSet.getInt(8),
                                            blockedByKeyOrder, resultSet.getLong(10)));
                }
                return rows;
            }
        }
    }

    /**
     * Read one message by its id.
     * <p>
     * A primary-key point lookup: {@code MessageId} is {@code (lane, shard, seq)} and the primary key
     * is {@code (queue_id, shard, seq)}. It needs no claim flag, touches no index the fast path uses,
     * and costs delivery nothing — the SPI's own justification for omitting by-id operations
     * conflated addressing a row with knowing whether someone is working on it.
     */
    public Optional<StoredMessage> findMessage(int shard, long seq, boolean ordered) throws SQLException {
        var table = ordered ? ORDERED_TABLE : UNORDERED_TABLE;
        var key      = ordered ? "msg_key" : "NULL::text";
        var keyOrder = ordered ? "key_order" : "0::bigint";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT " + key + ", payload, payload_type, attempts, enqueued_at, visible_at, " + keyOrder
                             + " FROM " + table + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setLong(3, seq);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredMessage(resultSet.getString(1),
                                                     resultSet.getBytes(2),
                                                     resultSet.getInt(3),
                                                     resultSet.getInt(4),
                                                     resultSet.getTimestamp(5).toInstant(),
                                                     resultSet.getTimestamp(6).toInstant(),
                                                     resultSet.getLong(7)));
            }
        }
    }

    /**
     * A page of the live messages in this queue, across <b>both</b> lanes, for an operator browsing
     * it. Not a delivery path — delivery reads per shard from a cursor and never sorts.
     * <p>
     * <b>Ordered by {@code (lane, shard, seq)} — which is the message id — ascending or descending.</b>
     * That is not arrival order and does not claim to be.
     * There is no global arrival order to report: each shard has its own sequence, messages commit
     * independently, and the engine's whole design is that no reader needs a total order. What this
     * ordering is instead is <em>stable</em>, which is the property paging actually requires — the
     * same row is never returned on two pages and never skipped between them. Sorting by
     * {@code enqueued_at} would read as arrival order while being neither stable nor true, since the
     * timestamp is the server's at insert and two rows can share it.
     * <p>
     * <b>Cost is an operator's, not a message's.</b> A deep offset sorts both lane tables for this
     * queue. That is the same trade the by-id operations make and the reason they were allowed: this
     * is paid once per administrator looking at a page, not once per delivered message.
     */
    public List<ListedMessage> listMessages(int offset, int limit, boolean ascending) throws SQLException {
        var sql = "SELECT lane, shard, seq, msg_key, payload, payload_type, attempts, enqueued_at, visible_at, key_order FROM ("
                + "   SELECT 'unordered' AS lane, shard, seq, NULL::text AS msg_key, payload, payload_type,"
                + "          attempts, enqueued_at, visible_at, 0::bigint AS key_order"
                + "     FROM " + UNORDERED_TABLE + " WHERE queue_id = ?"
                + "   UNION ALL"
                + "   SELECT 'ordered', shard, seq, msg_key, payload, payload_type,"
                + "          attempts, enqueued_at, visible_at, key_order"
                + "     FROM " + ORDERED_TABLE + " WHERE queue_id = ?"
                + " ) lanes ORDER BY lane " + (ascending ? "ASC" : "DESC")
                + ", shard " + (ascending ? "ASC" : "DESC")
                + ", seq " + (ascending ? "ASC" : "DESC")
                + " LIMIT ? OFFSET ?";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setShort(1, queueId);
            statement.setShort(2, queueId);
            statement.setInt(3, limit);
            statement.setInt(4, offset);
            try (var resultSet = statement.executeQuery()) {
                var messages = new ArrayList<ListedMessage>();
                while (resultSet.next()) {
                    messages.add(new ListedMessage(resultSet.getString(1),
                                                   resultSet.getInt(2),
                                                   resultSet.getLong(3),
                                                   new StoredMessage(resultSet.getString(4),
                                                                     resultSet.getBytes(5),
                                                                     resultSet.getInt(6),
                                                                     resultSet.getInt(7),
                                                                     resultSet.getTimestamp(8).toInstant(),
                                                                     resultSet.getTimestamp(9).toInstant(),
                                                                     resultSet.getLong(10))));
                }
                return messages;
            }
        }
    }

    /**
     * A row from {@link #listMessages(int, int, boolean)} — its identity, and the message itself.
     */
    public record ListedMessage(String lane, int shard, long seq, StoredMessage message) {
    }

    public record StoredMessage(String key, byte[] payload, int payloadType, int attempts,
                                java.time.Instant enqueuedAt, java.time.Instant visibleAt, long keyOrder) {
    }

    /**
     * Remove one message by id. Returns false if it was already gone.
     */
    public boolean deleteMessage(int shard, long seq, boolean ordered) throws SQLException {
        var table = ordered ? ORDERED_TABLE : UNORDERED_TABLE;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "DELETE FROM " + table + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setLong(3, seq);
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * Remove one dead letter without delivering it.
     * <p>
     * Addressed by lane as well as {@code (shard, seq)}, for the reason {@link #resurrect} is. Removing
     * the dead letter that holds an ordered key releases the key: the owner derives its blocks from
     * this table, and re-reads them on its sweep cadence.
     *
     * @return false if there was no such dead letter
     */
    public boolean deleteDeadLetter(int shard, long seq, boolean ordered) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "DELETE FROM " + DLQ_TABLE + " WHERE queue_id = ? AND shard = ? AND seq = ? AND source_lane = ?")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            statement.setLong(3, seq);
            statement.setString(4, ordered ? "ordered" : "unordered");
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * Put every dead letter of one ordered key back, in one transaction.
     * <p>
     * A key that stops behind a dead letter accumulates them: the message that failed, and then every
     * message that arrived for the key afterwards. Recovering that one row at a time works — the block
     * simply lifts to the next parked value and the key stalls again — but it is O(N) and has to be
     * walked in ascending {@code key_order}, because putting a higher one back first parks it straight
     * away again. This does the whole key at once, which is what an operator actually means.
     * <p>
     * <b>One transaction is what makes the order come out right.</b> The rows become visible together,
     * so the owner's next read holds all of them before it dispatches anything, and its per-key
     * {@code TreeMap} hands them over in {@code key_order} regardless of the sequence values they were
     * given. The insert is ordered by {@code key_order} anyway, so the fresh sequence values ascend
     * with it and a batch larger than {@code readBatchSize} still arrives lowest-first.
     * <p>
     * Attempts are reset, as for a single resurrect: this is a human deciding the messages deserve a
     * fresh policy. If the handler is still broken, the first one fails its way back to the
     * dead-letter table and the block re-forms behind it.
     *
     * @return how many messages were put back
     */
    public int resurrectKey(int shard, String key) throws SQLException {
        requireNonNull(key, "No key provided");
        var sequence = ShardOwnedSchema.orderedSequenceName(queueId);
        try (var connection = dataSource.getConnection()) {
            var autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                assignTransactionIdBeforeAllocating(connection);
                int restored;
                try (var insert = connection.prepareStatement(
                        "INSERT INTO " + ORDERED_TABLE
                                + " (queue_id, shard, msg_key, key_order, seq, payload, payload_type, attempts)"
                                + " SELECT queue_id, shard, msg_key, key_order, nextval('" + sequence + "'),"
                                + "        payload, payload_type, 0"
                                + "   FROM " + DLQ_TABLE
                                + "  WHERE queue_id = ? AND shard = ? AND source_lane = 'ordered' AND msg_key = ?"
                                + "  ORDER BY key_order")) {
                    insert.setShort(1, queueId);
                    insert.setInt(2, shard);
                    insert.setString(3, key);
                    restored = insert.executeUpdate();
                }
                if (restored == 0) {
                    connection.rollback();
                    return 0;
                }
                try (var delete = connection.prepareStatement(
                        "DELETE FROM " + DLQ_TABLE
                                + " WHERE queue_id = ? AND shard = ? AND source_lane = 'ordered' AND msg_key = ?")) {
                    delete.setShort(1, queueId);
                    delete.setInt(2, shard);
                    delete.setString(3, key);
                    delete.executeUpdate();
                }
                // Inside the transaction, so the owner is only woken for something it can actually
                // see. The rows arrive above its cursor, since every one took a fresh sequence value.
                ShardWakeupListener.notifyShard(connection, queueId, "ordered", shard);
                connection.commit();
                return restored;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * Make one message deliverable again after {@code delayMillis}, resetting its attempt count.
     * Server-side {@code now()}, like every other durable moment here.
     */
    public boolean retryMessage(int shard, long seq, boolean ordered, long delayMillis) throws SQLException {
        var table = ordered ? ORDERED_TABLE : UNORDERED_TABLE;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE " + table + " SET attempts = 0, visible_at = now() + make_interval(secs => ? / 1000.0)"
                             + (ordered ? ", lease = NULL" : "")
                             + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
            statement.setLong(1, delayMillis);
            statement.setShort(2, queueId);
            statement.setInt(3, shard);
            statement.setLong(4, seq);
            return statement.executeUpdate() > 0;
        }
    }

    /**
     * Park one message in the dead letter lane by id, whatever its attempt count.
     */
    public boolean deadLetterMessage(int shard, long seq, boolean ordered, String reason) throws SQLException {
        var table = ordered ? ORDERED_TABLE : UNORDERED_TABLE;
        try (var connection = dataSource.getConnection()) {
            if (findMessage(shard, seq, ordered).isEmpty()) {
                return false;
            }
            return moveToDeadLetter(connection, table, ordered ? "ordered" : "unordered", shard, seq, reason);
        }
    }

    /**
     * Put a dead letter back in its lane and remove it from the parking table, atomically.
     * <p>
     * The attempt count is reset, because resurrection is a human deciding the message deserves a
     * fresh policy — carrying the old count forward would send it straight back to the dead letter
     * lane on its first failure, which is not what anybody means by "try this again".
     */
    public boolean resurrect(int shard, long seq, String lane) throws SQLException {
        var ordered = "ordered".equals(lane);
        var table   = ordered ? ORDERED_TABLE : UNORDERED_TABLE;
        var columns = ordered
                      ? "queue_id, shard, msg_key, key_order, seq, payload, payload_type, attempts"
                      : "queue_id, shard, seq, payload, payload_type, attempts";
        // A FRESH sequence value, not the one the message died under.
        //
        // The owner's cursor only ever moves forward, so re-inserting below it makes the row
        // invisible to the fast path: nothing but the head sweep would ever find it, which at the
        // backed-off interval is up to maxSweepInterval — thirty seconds of a resurrected message
        // sitting there for no reason. Taking a new value puts it ahead of the cursor, where the
        // notification issued below actually means something.
        var sequence = ordered ? ShardOwnedSchema.orderedSequenceName(queueId)
                               : ShardOwnedSchema.sequenceName(queueId, shard);
        var selected = ordered
                       ? "queue_id, shard, msg_key, key_order, nextval('" + sequence + "'), payload, payload_type, 0"
                       : "queue_id, shard, nextval('" + sequence + "'), payload, payload_type, 0";
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (ordered) {
                    assignTransactionIdBeforeAllocating(connection);
                }
                int restored;
                try (var insert = connection.prepareStatement(
                        "INSERT INTO " + table + " (" + columns + ") SELECT " + selected
                                + " FROM " + DLQ_TABLE
                                // source_lane is part of the address, not a filter of convenience: the
                                // unordered lane numbers seq per shard and the ordered lane per queue, so
                                // one (shard, seq) can be a dead letter in both. Matching without it
                                // copied the other lane's row into this lane's table.
                                + " WHERE queue_id = ? AND shard = ? AND seq = ? AND source_lane = ?")) {
                    insert.setShort(1, queueId);
                    insert.setInt(2, shard);
                    insert.setLong(3, seq);
                    insert.setString(4, lane);
                    restored = insert.executeUpdate();
                }
                if (restored == 0) {
                    connection.rollback();
                    return false;
                }
                try (var delete = connection.prepareStatement(
                        "DELETE FROM " + DLQ_TABLE + " WHERE queue_id = ? AND shard = ? AND seq = ? AND source_lane = ?")) {
                    delete.setShort(1, queueId);
                    delete.setInt(2, shard);
                    delete.setLong(3, seq);
                    delete.setString(4, lane);
                    delete.executeUpdate();
                }
                ShardWakeupListener.notifyShard(connection, queueId, lane, shard);
                connection.commit();
                return true;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * Remove everything for this queue, across both lanes and the dead letter table.
     */
    public long purge() throws SQLException {
        var removed = 0L;
        try (var connection = dataSource.getConnection()) {
            for (var table : List.of(UNORDERED_TABLE, ORDERED_TABLE, DLQ_TABLE)) {
                try (var statement = connection.prepareStatement("DELETE FROM " + table + " WHERE queue_id = ?")) {
                    statement.setShort(1, queueId);
                    removed += statement.executeUpdate();
                }
            }
        }
        return removed;
    }

    /**
     * One row of an unordered enqueue.
     * <p>
     * Carries its own {@code payloadType} because a batch may legitimately mix types — the caller
     * decides what a batch is, and nothing about the storage requires them to be uniform. The
     * convenience overloads that take one type for a whole list expand into these.
     */
    public record PayloadRow(byte[] payload, int payloadType, Duration delay) {

        public PayloadRow(byte[] payload, int payloadType) {
            this(payload, payloadType, Duration.ZERO);
        }

        /**
         * Server-side, so a delay never depends on the enqueueing node's clock.
         */
        long delayMillis() {
            return delay == null ? 0L : Math.max(0L, delay.toMillis());
        }
    }

    /**
     * One row of an ordered enqueue. Carries its own {@code payloadType} for the same reason
     * {@link PayloadRow} does — deliberately with no defaulting overload, because a silently wrong
     * payload type is exactly the defect this shape exists to prevent.
     */
    public record OrderedPayload(String key, long keyOrder, byte[] payload, int payloadType, Duration delay) {

        public OrderedPayload(String key, long keyOrder, byte[] payload, int payloadType) {
            this(key, keyOrder, payload, payloadType, Duration.ZERO);
        }

        long delayMillis() {
            return delay == null ? 0L : Math.max(0L, delay.toMillis());
        }
    }

    public record OrderedRow(long seq, String key, long keyOrder, byte[] payload, int payloadType) {
    }

    public record Row(long seq, byte[] payload, int payloadType) {
    }
}
