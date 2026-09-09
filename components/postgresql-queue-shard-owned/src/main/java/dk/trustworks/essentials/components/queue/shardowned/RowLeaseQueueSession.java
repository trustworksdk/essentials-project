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

import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.slf4j.*;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A pull session at {@link SessionScope#MESSAGE} or {@link SessionScope#BATCH} granularity, on the
 * unordered lane.
 * <p>
 * Where {@link ShardQueueSession} takes whole shards and so excludes consumers from them, this one
 * takes individual rows and leaves the rest of the shard alone. Several sessions can pull from one
 * shard at the same time, and a push consumer can keep running beside them.
 * <p>
 * <b>It costs a write per claimed message, deliberately.</b> That is the trade {@link SessionScope}
 * documents: finer granularity is bought, not free. The push fast path is untouched — it still writes
 * nothing when it consumes — and the cost falls only on callers who pull.
 * <p>
 * <b>What the row lease does and does not exclude.</b> A claimed row is hidden from the shard's owner
 * and from other sessions until the lease lapses, and it comes back on its own if this session's
 * process dies. What it cannot do is retract a row the owner had already read into memory before the
 * claim landed — the owner's read is a snapshot, and re-checking at dispatch would put a query on the
 * fast path, which is the cost this whole design exists to avoid. So a message can be delivered both
 * to a push consumer and to a session. That is a duplicate, which the unordered lane's at-least-once
 * contract permits; it is also why this scope is not offered on the ordered lane, where the same
 * overlap would be reordering.
 */
public final class RowLeaseQueueSession implements QueueSession {
    private static final Logger log = LoggerFactory.getLogger(RowLeaseQueueSession.class);

    private final ShardOwnedStorage storage;
    private final DataSource     dataSource;
    private final int            shardCount;
    private final long           fence;
    private final long           leaseMillis;
    private final int            maxPerPoll;

    /** Which shard each pulled message came from, so acknowledgement can address the right one. */
    private final Map<Long, Integer> shardBySeq = new HashMap<>();
    private       int                nextShard;
    private       boolean            closed;

    RowLeaseQueueSession(ShardOwnedStorage storage, DataSource dataSource, int shardCount,
                         SessionScope scope, Duration leaseDuration) throws SQLException {
        this.storage = requireNonNull(storage, "No storage provided");
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.shardCount = shardCount;
        this.leaseMillis = requireNonNull(leaseDuration, "No leaseDuration provided").toMillis();
        // The one behavioural difference between the two scopes in this engine. A batch claim stamps
        // n rows in a single statement, so "one write per batch" and "one write per message" are the
        // same statement — what MESSAGE scope actually buys is that a caller never holds more than
        // the one message it is working on.
        this.maxPerPoll = scope == SessionScope.MESSAGE ? 1 : Integer.MAX_VALUE;
        this.fence = storage.nextSessionFence();
    }

    @Override
    public List<PulledMessage> poll(int max) throws SQLException {
        if (closed || max <= 0) {
            return List.of();
        }
        var limit = Math.min(max, maxPerPoll);
        var pulled = new ArrayList<PulledMessage>();
        try (var connection = dataSource.getConnection()) {
            // Round-robin the starting shard so a session does not drain shard 0 while later shards
            // sit untouched, and so several sessions do not all contend on the same one first.
            for (var offset = 0; offset < shardCount && pulled.size() < limit; offset++) {
                var shard = (nextShard + offset) % shardCount;
                var rows = storage.claimForSession(connection, shard, limit - pulled.size(), fence, leaseMillis);
                for (var row : rows) {
                    shardBySeq.put(row.seq(), row.shard());
                    pulled.add(new PulledMessage(new MessageId(MessageId.Lane.UNORDERED, row.shard(), row.seq()),
                                                 null, row.payload(), row.payloadType(), row.attempts()));
                }
            }
        }
        nextShard = (nextShard + 1) % shardCount;
        return pulled;
    }

    @Override
    public boolean acknowledge(Collection<MessageId> ids) throws SQLException {
        requireNonNull(ids, "No ids provided");
        if (ids.isEmpty()) {
            return true;
        }
        var byShard = new HashMap<Integer, List<Long>>();
        for (var id : ids) {
            byShard.computeIfAbsent(id.shard(), shard -> new ArrayList<>()).add(id.sequence());
        }
        var deleted = 0;
        var expected = ids.size();
        try (var connection = dataSource.getConnection()) {
            for (var entry : byShard.entrySet()) {
                deleted += storage.acknowledgeSessionRows(connection, entry.getKey(), entry.getValue(), fence);
            }
        }
        if (deleted < expected) {
            // Some row was no longer this session's — its lease had lapsed and the shard's owner may
            // already have redelivered it. Reporting success here would tell the caller work was
            // retired that is still in the queue.
            log.warn("Session {}: acknowledged {} of {} messages — the rest had lapsed", fence, deleted, expected);
            return false;
        }
        ids.forEach(id -> shardBySeq.remove(id.sequence()));
        return true;
    }

    @Override
    public void fail(MessageId id, Throwable cause) throws SQLException {
        requireNonNull(id, "No id provided");
        try (var connection = dataSource.getConnection()) {
            // Hand it straight back rather than waiting out the lease: the caller has told us it is
            // not working on it any more, so holding it hidden would be a delay with no purpose.
            storage.scheduleRetry(connection, ShardOwnedSchema.UNORDERED_TABLE, id.shard(), id.sequence(),
                                  1, 0L);
            storage.releaseSessionRow(connection, id.shard(), id.sequence(), fence);
        }
        shardBySeq.remove(id.sequence());
    }

    @Override
    public boolean extendLease() throws SQLException {
        if (closed) {
            return false;
        }
        try (var connection = dataSource.getConnection()) {
            var extended = storage.extendSessionRows(connection, fence, leaseMillis);
            // Nothing held is not a failure — a session that has acknowledged everything it pulled is
            // perfectly alive. It is only a loss if rows were expected to still be here.
            return extended > 0 || shardBySeq.isEmpty();
        }
    }

    /** Rows still held, for tests and for a caller that wants to know what it is on the hook for. */
    public int messagesHeld() {
        return shardBySeq.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try (var connection = dataSource.getConnection()) {
            var released = storage.releaseSessionRows(connection, fence);
            if (released > 0) {
                log.info("Session {} released {} unacknowledged message(s) on close", fence, released);
            }
        } catch (SQLException e) {
            // The leases expire on their own, so this costs a delay rather than correctness.
            log.warn("Session {}: could not release its rows on close; they return when the lease lapses", fence, e);
        }
    }
}
