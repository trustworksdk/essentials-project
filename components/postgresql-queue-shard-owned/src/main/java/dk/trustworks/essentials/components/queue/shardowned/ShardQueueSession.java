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
 * A pull session that owns whole shards (§8.3's recommended scope).
 * <p>
 * The scope is the parameter that matters, and shard scope is the one that costs nothing: ownership
 * is already recorded in the lease table, so individual messages need no per-row claim. Polling is
 * the same cursor read the engine's own consumers use — a caller pulling gets the fast path rather
 * than a compatibility lane.
 * <p>
 * It also serves all three requirements the old pull method bundled behind one signature. A caller
 * needing the message inside a transaction it controls acknowledges within its own unit of work; a
 * caller driving its own loop calls {@link #poll}; a caller with a long-running handler extends the
 * lease rather than fighting a queue-wide timeout that has to suit everyone.
 * <p>
 * The price, stated plainly: holding a shard blocks other consumers from it for the session's life.
 * That is the same trade SQS FIFO makes with message groups, and it is why a session should be held
 * for as long as the work takes and no longer.
 */
public final class ShardQueueSession implements QueueSession {
    private static final Logger log = LoggerFactory.getLogger(ShardQueueSession.class);

    private final ShardOwnedStorage storage;
    private final DataSource        dataSource;
    private final String            sessionId;
    private final long              leaseMillis;
    private final List<int[]>       held = new ArrayList<>();

    /**
     * Per shard, the highest sequence value handed to the caller.
     */
    private final Map<Integer, Long> cursors = new HashMap<>();
    private       boolean            closed;

    ShardQueueSession(ShardOwnedStorage storage,
                      DataSource dataSource,
                      String sessionId,
                      int shardCount,
                      int maxShards,
                      Duration leaseDuration) throws SQLException {
        this.storage = requireNonNull(storage, "No storage provided");
        this.dataSource = requireNonNull(dataSource, "No dataSource provided");
        this.sessionId = requireNonNull(sessionId, "No sessionId provided");
        this.leaseMillis = Math.max(1_000L, requireNonNull(leaseDuration, "No leaseDuration provided").toMillis());

        for (var shard = 0; shard < shardCount && held.size() < maxShards; shard++) {
            var fence = storage.acquireSessionLease("unordered", shard, sessionId, leaseMillis);
            if (fence.isPresent()) {
                held.add(new int[]{shard, fence.get().intValue()});
            }
        }
        if (held.isEmpty()) {
            log.info("Session {} acquired no shards — every one is held by a live consumer", sessionId);
        }
    }

    @Override
    public List<PulledMessage> poll(int max) throws SQLException {
        requireOpen();
        var pulled = new ArrayList<PulledMessage>(Math.max(0, max));
        try (var connection = dataSource.getConnection()) {
            for (var entry : held) {
                if (pulled.size() >= max) {
                    break;
                }
                var shard  = entry[0];
                var cursor = cursors.getOrDefault(shard, 0L);
                // The same forward cursor scan the engine's own owners use. The session's fence is
                // passed so anything it pre-claimed for itself is skipped, exactly as for an owner.
                var rows = storage.readFromCursor(connection, shard, cursor, max - pulled.size(), entry[1]);
                for (var row : rows) {
                    cursors.merge(shard, row.seq(), Math::max);
                    pulled.add(new PulledMessage(new MessageId(MessageId.Lane.UNORDERED, shard, row.seq()),
                                                 null, row.payload(), 0, 0));
                }
            }
        }
        return pulled;
    }

    @Override
    public boolean acknowledge(Collection<MessageId> ids) throws SQLException {
        requireOpen();
        requireNonNull(ids, "No ids provided");
        if (ids.isEmpty()) {
            return true;
        }
        var accepted = true;
        try (var connection = dataSource.getConnection()) {
            for (var entry : held) {
                var shard = entry[0];
                var seqs  = ids.stream().filter(id -> id.shard() == shard).map(MessageId::sequence).toList();
                if (seqs.isEmpty()) {
                    continue;
                }
                // Fenced, like every other acknowledgement in the engine: if this session has been
                // superseded the delete affects nothing and the caller is told so, rather than
                // silently removing work the new owner is about to deliver.
                var deleted = storage.acknowledge(connection, shard, 0L, seqs, sessionId, entry[1]);
                if (deleted == 0) {
                    accepted = false;
                }
            }
        }
        if (!accepted) {
            log.warn("Session {} was superseded; its acknowledgement was refused", sessionId);
        }
        return accepted;
    }

    @Override
    public void fail(MessageId id, Throwable cause) throws SQLException {
        requireOpen();
        requireNonNull(id, "No id provided");
        try (var connection = dataSource.getConnection()) {
            // A pulled message that failed goes back with a short backoff. The session does not track
            // attempts in memory — it may be gone before the retry is due — so the durable count on
            // the row is what the redelivery policy sees, which is what it is there for.
            storage.scheduleRetry(connection, ShardOwnedSchema.UNORDERED_TABLE, id.shard(), id.sequence(), 1, 1_000L);
        }
        cursors.merge(id.shard(), id.sequence() - 1, Math::min);
    }

    @Override
    public boolean extendLease() throws SQLException {
        requireOpen();
        var stillHeld = true;
        for (var entry : held) {
            var renewed = storage.acquireSessionLease("unordered", entry[0], sessionId, leaseMillis);
            if (renewed.isEmpty() || renewed.get() != entry[1]) {
                stillHeld = false;
            }
        }
        return stillHeld;
    }

    public int shardsHeld() {
        return held.size();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Hand the shards back rather than letting them time out, so a consumer can pick them up
        // immediately instead of waiting out the lease.
        for (var entry : held) {
            try {
                storage.releaseLease("unordered", entry[0], sessionId);
            } catch (SQLException e) {
                log.warn("Session {} could not release shard {}; it will expire instead", sessionId, entry[0], e);
            }
        }
        held.clear();
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Session " + sessionId + " is closed");
        }
    }
}
