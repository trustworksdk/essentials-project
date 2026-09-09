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
import java.time.Duration;
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

    @FunctionalInterface
    private interface SqlCall<T> {
        T call() throws SQLException;
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
                statement.setShort(2, (short) shard);
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
     *         {@code nextval} is applied in the ordinality order of the input array
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
                statement.setShort(2, (short) shard);
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
            statement.setShort(2, (short) shard);
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
            statement.setShort(2, (short) shard);
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
            statement.setShort(2, (short) shard);
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
            statement.setShort(2, (short) shard);
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
        var deleted = 0;
        if (contiguousThrough > 0) {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM " + UNORDERED_TABLE + " u WHERE u.queue_id = ? AND u.shard = ? AND u.seq <= ?"
                    // A row a session holds is not the owner's to delete. Without this the range
                    // delete would remove it once its hole expired — undelivered, and while a
                    // session was still working on it.
                    + " AND (u.lease_until IS NULL OR u.lease_until <= now())"
                    + stillOwnedClause())) {
                statement.setShort(1, queueId);
                statement.setShort(2, (short) shard);
                statement.setLong(3, contiguousThrough);
                statement.setString(4, owner);
                statement.setLong(5, fence);
                deleted += statement.executeUpdate();
            }
        }
        if (!stragglers.isEmpty()) {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM " + UNORDERED_TABLE + " u WHERE u.queue_id = ? AND u.shard = ? AND u.seq = ANY(?)"
                    + stillOwnedClause())) {
                statement.setShort(1, queueId);
                statement.setShort(2, (short) shard);
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
                     + " AND owner = ? AND fence = ? AND lease_until > now()")) {
            statement.setShort(1, queueId);
            statement.setString(2, lane);
            statement.setShort(3, (short) shard);
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
        var sql = "UPDATE " + UNORDERED_TABLE + " SET lease = ?, lease_until = now() + make_interval(secs => ? / 1000.0)"
                  + " WHERE (queue_id, shard, seq) IN ("
                  + "   SELECT queue_id, shard, seq FROM " + UNORDERED_TABLE
                  + "   WHERE queue_id = ? AND shard = ? AND visible_at <= now()"
                  + "     AND (lease_until IS NULL OR lease_until <= now())"
                  + "   ORDER BY seq LIMIT ? FOR UPDATE SKIP LOCKED)"
                  + " RETURNING seq, payload, payload_type, attempts";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sessionFence);
            statement.setLong(2, leaseMillis);
            statement.setShort(3, queueId);
            statement.setShort(4, (short) shard);
            statement.setInt(5, limit);
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
            statement.setShort(2, (short) shard);
            statement.setArray(3, connection.createArrayOf("bigint", seqs.toArray(Long[]::new)));
            statement.setLong(4, sessionFence);
            return statement.executeUpdate();
        }
    }

    /** Push every row this session holds further out. Returns how many were still its to extend. */
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

    /** Hand one row back, for {@code fail} — the caller has said it is not working on it any more. */
    public int releaseSessionRow(Connection connection, int shard, long seq, long sessionFence) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE " + UNORDERED_TABLE + " SET lease = NULL, lease_until = NULL"
                + " WHERE queue_id = ? AND shard = ? AND seq = ? AND lease = ?")) {
            statement.setShort(1, queueId);
            statement.setShort(2, (short) shard);
            statement.setLong(3, seq);
            statement.setLong(4, sessionFence);
            return statement.executeUpdate();
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
               + " AND l.owner = ? AND l.fence = ? AND l.lease_until > now())";
    }

    /**
     * Announce that this instance is alive and wants work.
     */
    public void heartbeatInstance(String instanceId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + INSTANCE_TABLE + " (queue_id, instance_id, last_seen) VALUES (?, ?, now())"
                     + " ON CONFLICT (queue_id, instance_id) DO UPDATE SET last_seen = now()")) {
            statement.setShort(1, queueId);
            statement.setString(2, instanceId);
            statement.executeUpdate();
        }
    }

    /**
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
     * Hand a shard back voluntarily by expiring its lease.
     * <p>
     * The fence is deliberately NOT bumped here — the next acquirer bumps it. Bumping on release
     * would invalidate the releasing owner's own in-flight acknowledgements before it has finished
     * draining them.
     */
    public void releaseLease(String lane, int shard, String owner) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE " + LEASE_TABLE + " SET lease_until = now() - interval '1 millisecond'"
                     + " WHERE queue_id = ? AND lane = ? AND shard = ? AND owner = ?")) {
            statement.setShort(1, queueId);
            statement.setString(2, lane);
            statement.setShort(3, (short) shard);
            statement.setString(4, owner);
            statement.executeUpdate();
        }
    }

    /**
     * Take or renew the shard's lease, bumping the fence on a change of owner.
     *
     * @return the fence held, or empty if another instance holds an unexpired lease
     */
    public Optional<Long> acquireLease(String lane, int shard, String owner, long ttlMillis) throws SQLException {
        var sql = "UPDATE " + LEASE_TABLE
                  + " SET owner = ?, fence = CASE WHEN owner = ? THEN fence ELSE fence + 1 END,"
                  + "     lease_until = now() + make_interval(secs => ? / 1000.0)"
                  + " WHERE queue_id = ? AND lane = ? AND shard = ? AND (lease_until < now() OR owner = ? OR owner IS NULL)"
                  + " RETURNING fence";
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, owner);
            statement.setString(2, owner);
            statement.setLong(3, ttlMillis);
            statement.setShort(4, queueId);
            statement.setString(5, lane);
            statement.setShort(6, (short) shard);
            statement.setString(7, owner);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(resultSet.getLong(1)) : Optional.empty();
            }
        }
    }

    /**
     * Bulk attempt increment on takeover.
     * <p>
     * A handler that crashes the JVM never records its attempt, so without this a poison message
     * loops forever. One statement over whatever the previous owner left unacked is enough, and it
     * is the only reason the steady-state path can get away with never writing an attempt count.
     */
    public int bumpAttemptsOnTakeover(Connection connection, int shard) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE " + UNORDERED_TABLE + " SET attempts = attempts + 1 WHERE queue_id = ? AND shard = ?")) {
            statement.setShort(1, queueId);
            statement.setShort(2, (short) shard);
            return statement.executeUpdate();
        }
    }

    public long countRemaining(int shard) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + UNORDERED_TABLE + " WHERE queue_id = ? AND shard = ?")) {
            statement.setShort(1, queueId);
            statement.setShort(2, (short) shard);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    public List<Long> allSeqs(int shard) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT seq FROM " + UNORDERED_TABLE + " WHERE queue_id = ? AND shard = ?")) {
            statement.setShort(1, queueId);
            statement.setShort(2, (short) shard);
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
     *         {@code seq} like every other — {@code key_order} is the producer's ordering hint, not
     *         an identity, and returning it as one produced ids that no by-id operation could resolve.
     */
    public List<Long> enqueueOrderedBatch(Connection connection, int shard, List<OrderedPayload> messages) throws SQLException {
        return inTransaction(connection, () -> enqueueOrderedBatchInternal(connection, shard, messages));
    }

    private List<Long> enqueueOrderedBatchInternal(Connection connection, int shard, List<OrderedPayload> messages) throws SQLException {
        var sql = "INSERT INTO " + ORDERED_TABLE
                  + " (queue_id, shard, msg_key, key_order, seq, payload, payload_type, visible_at)"
                  + " VALUES (?, ?, ?, ?, nextval(?::regclass), ?, ?, now() + make_interval(secs => ? / 1000.0))";
        var seqs = new ArrayList<Long>(messages.size());
        try (var statement = connection.prepareStatement(sql, new String[]{"seq"})) {
            for (var message : messages) {
                statement.setShort(1, queueId);
                statement.setShort(2, (short) shard);
                statement.setString(3, message.key());
                statement.setLong(4, message.keyOrder());
                statement.setString(5, ShardOwnedSchema.orderedSequenceName(queueId, shard));
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
            statement.setShort(2, (short) shard);
            statement.setLong(3, cursor);
            return readOrderedRows(statement);
        }
    }

    public List<OrderedRow> readOrderedSpecific(Connection connection, int shard, Collection<Long> seqs) throws SQLException {
        if (seqs.isEmpty()) {
            return List.of();
        }
        var sql = "SELECT seq, msg_key, key_order, payload, payload_type FROM " + ORDERED_TABLE
                  + " WHERE queue_id = ? AND shard = ? AND seq = ANY(?) AND visible_at <= now()";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setShort(1, queueId);
            statement.setShort(2, (short) shard);
            statement.setArray(3, connection.createArrayOf("bigint", seqs.toArray(Long[]::new)));
            return readOrderedRows(statement);
        }
    }

    public List<OrderedRow> sweepOrderedFromHead(Connection connection, int shard, int limit) throws SQLException {
        var sql = "SELECT seq, msg_key, key_order, payload, payload_type FROM " + ORDERED_TABLE
                  + " WHERE queue_id = ? AND shard = ? AND visible_at <= now() ORDER BY seq LIMIT " + limit;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setShort(1, queueId);
            statement.setShort(2, (short) shard);
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
                + " AND l.owner = ? AND l.fence = ? AND l.lease_until > now())")) {
            statement.setShort(1, queueId);
            statement.setShort(2, (short) shard);
            statement.setArray(3, connection.createArrayOf("bigint", seqs.toArray(Long[]::new)));
            statement.setString(4, owner);
            statement.setLong(5, fence);
            return statement.executeUpdate();
        }
    }

    public int bumpOrderedAttemptsOnTakeover(Connection connection, int shard) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE " + ORDERED_TABLE + " SET attempts = attempts + 1 WHERE queue_id = ? AND shard = ?")) {
            statement.setShort(1, queueId);
            statement.setShort(2, (short) shard);
            return statement.executeUpdate();
        }
    }

    public long countOrderedRemaining(int shard) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + ORDERED_TABLE + " WHERE queue_id = ? AND shard = ?")) {
            statement.setShort(1, queueId);
            statement.setShort(2, (short) shard);
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
     * A HOT update — {@code attempts} and {@code visible_at} are not in the primary key, and the
     * table is created with {@code fillfactor = 80} so the new row version fits on the same page.
     * That keeps a retry from touching any index.
     * <p>
     * This write is durability backup, not the retry mechanism. The owner keeps the message in an
     * in-memory timer wheel and re-dispatches from there without reading it back; the row exists so
     * that a crash does not lose the backoff.
     */
    public void scheduleRetry(Connection connection, String table, int shard, long seq, int attempts, long delayMillis) throws SQLException {
        try (var statement = connection.prepareStatement(
                "UPDATE " + table + " SET attempts = ?, visible_at = now() + make_interval(secs => ? / 1000.0)"
                + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
            statement.setShort(1, (short) attempts);
            statement.setLong(2, delayMillis);
            statement.setShort(3, queueId);
            statement.setShort(4, (short) shard);
            statement.setLong(5, seq);
            statement.executeUpdate();
        }
    }

    /**
     * Move a message to the dead letter lane and remove it from the live one, atomically.
     * <p>
     * Both statements share a transaction because the alternative failure modes are both bad: a
     * message in neither lane is lost, and a message in both is delivered again after being parked.
     */
    public void moveToDeadLetter(Connection connection, String table, String lane, int shard, long seq, String error) throws SQLException {
        var autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            var keyColumns = ORDERED_TABLE.equals(table) ? "msg_key, key_order" : "NULL, NULL";
            try (var insert = connection.prepareStatement(
                    "INSERT INTO " + DLQ_TABLE + " (queue_id, shard, source_lane, msg_key, key_order, seq,"
                    + " payload, payload_type, attempts, last_error)"
                    + " SELECT queue_id, shard, ?, " + keyColumns + ", seq, payload, payload_type, attempts, ?"
                    + " FROM " + table + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
                insert.setString(1, lane);
                insert.setString(2, error);
                insert.setShort(3, queueId);
                insert.setShort(4, (short) shard);
                insert.setLong(5, seq);
                insert.executeUpdate();
            }
            try (var delete = connection.prepareStatement(
                    "DELETE FROM " + table + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
                delete.setShort(1, queueId);
                delete.setShort(2, (short) shard);
                delete.setLong(3, seq);
                delete.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
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

    public record DeadLetter(String lane, String key, int shard, long seq, byte[] payload, int attempts, String error,
                             int payloadType) {
    }

    public List<DeadLetter> deadLetters(int offset, int limit) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT source_lane, msg_key, seq, payload, attempts, last_error, shard, payload_type FROM " + DLQ_TABLE
                     + " WHERE queue_id = ? ORDER BY id OFFSET ? LIMIT ?")) {
            statement.setShort(1, queueId);
            statement.setInt(2, offset);
            statement.setInt(3, limit);
            try (var resultSet = statement.executeQuery()) {
                var rows = new ArrayList<DeadLetter>();
                while (resultSet.next()) {
                    rows.add(new DeadLetter(resultSet.getString(1), resultSet.getString(2), resultSet.getInt(7),
                                            resultSet.getLong(3), resultSet.getBytes(4), resultSet.getInt(5),
                                            resultSet.getString(6), resultSet.getInt(8)));
                }
                return rows;
            }
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
        var table = ordered ? ORDERED_TABLE : UNORDERED_TABLE;
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
        var sequence = ordered ? ShardOwnedSchema.orderedSequenceName(queueId, shard)
                               : ShardOwnedSchema.sequenceName(queueId, shard);
        var selected = ordered
                       ? "queue_id, shard, msg_key, key_order, nextval('" + sequence + "'), payload, payload_type, 0"
                       : "queue_id, shard, nextval('" + sequence + "'), payload, payload_type, 0";
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int restored;
                try (var insert = connection.prepareStatement(
                        "INSERT INTO " + table + " (" + columns + ") SELECT " + selected
                        + " FROM " + DLQ_TABLE + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
                    insert.setShort(1, queueId);
                    insert.setShort(2, (short) shard);
                    insert.setLong(3, seq);
                    restored = insert.executeUpdate();
                }
                if (restored == 0) {
                    connection.rollback();
                    return false;
                }
                try (var delete = connection.prepareStatement(
                        "DELETE FROM " + DLQ_TABLE + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
                    delete.setShort(1, queueId);
                    delete.setShort(2, (short) shard);
                    delete.setLong(3, seq);
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

        /** Server-side, so a delay never depends on the enqueueing node's clock. */
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
