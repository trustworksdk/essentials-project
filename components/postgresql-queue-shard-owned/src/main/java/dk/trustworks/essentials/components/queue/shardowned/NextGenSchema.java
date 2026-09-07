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
import java.sql.SQLException;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Schema for the shard-owned queue engine, as specified in {@code docs/durable-queue-next-gen-design.md} §4.1.
 * <p>
 * This slice creates the unordered lane and the shard lease table. The ordered lane and the dead
 * letter lane come with the phases that need them; putting them in now would be schema without a
 * consumer.
 * <p>
 * Two things here exist to be measured rather than assumed:
 * <ul>
 *     <li><b>{@code payload bytea}, not {@code jsonb}.</b> The database never looks inside a
 *         payload, so bytes skip JSON validation on write and detoast on read.</li>
 *     <li><b>The primary key <em>is</em> the read index.</b> {@code (queue_id, shard, seq)} makes the
 *         fast path a forward range scan with no secondary index to maintain on insert. Whether
 *         PostgreSQL actually chooses that scan over a sequential one on a small, hot queue table is
 *         an open question the design records — and one this schema exists to answer on its own
 *         terms, not by inference from a differently-shaped table.</li>
 * </ul>
 * Interning of {@code queue_id} is done by the caller holding a name-to-id map; a lookup table can
 * follow when there is an admin surface that needs to resolve names.
 */
public final class NextGenSchema {

    public static final String UNORDERED_TABLE = "ng_unordered";
    public static final String ORDERED_TABLE   = "ng_ordered";
    public static final String DLQ_TABLE       = "ng_dlq";
    public static final String LEASE_TABLE     = "ng_shard_lease";
    public static final String INSTANCE_TABLE  = "ng_instance";
    public static final String SESSION_FENCE_SEQUENCE = "ng_session_fence";

    private NextGenSchema() {
    }

    public static void create(DataSource dataSource, int shardCount) throws SQLException {
        requireNonNull(dataSource, "No dataSource provided");
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {

            statement.execute("DROP TABLE IF EXISTS " + UNORDERED_TABLE);
            statement.execute("DROP TABLE IF EXISTS " + ORDERED_TABLE);
            statement.execute("DROP TABLE IF EXISTS " + DLQ_TABLE);
            statement.execute("DROP TABLE IF EXISTS " + LEASE_TABLE);
            statement.execute("DROP TABLE IF EXISTS " + INSTANCE_TABLE);

            statement.execute("""
                              CREATE TABLE %s (
                                  queue_id      smallint    NOT NULL,
                                  shard         smallint    NOT NULL,
                                  seq           bigint      NOT NULL,
                                  payload       bytea       NOT NULL,
                                  payload_type  int         NOT NULL,
                                  meta_data     bytea,
                                  enqueued_at   timestamptz NOT NULL DEFAULT now(),
                                  visible_at    timestamptz NOT NULL DEFAULT now(),
                                  attempts      smallint    NOT NULL DEFAULT 0,
                                  lease         bigint,
                                  -- NULL for a pre-claim, which is consumed immediately and never
                                  -- expires; set for a pull session's row lease, which does. That is
                                  -- the whole distinction: the shard's owner must ignore a live
                                  -- session's rows and must pick up a dead owner's pre-claims, and
                                  -- without an expiry the two are indistinguishable in the row.
                                  lease_until   timestamptz,
                                  PRIMARY KEY (queue_id, shard, seq)
                              ) WITH (fillfactor = 80)
                              """.formatted(UNORDERED_TABLE));

            // Supports the head sweep and delayed messages. The fast path does not use it.
            statement.execute("CREATE INDEX %s_visible ON %s (queue_id, shard, visible_at)"
                                      .formatted(UNORDERED_TABLE, UNORDERED_TABLE));

            // The ordered lane. Its primary key is (queue_id, shard, msg_key, key_order), NOT
            // (queue_id, shard, seq) — which is the whole reason §4.1 splits the lanes into separate
            // tables. Finding the head of a key becomes a primary-key prefix scan, and asking whether
            // a key has anything queued becomes a prefix existence check, neither of which needs a
            // secondary index. In one shared table only one of the two access patterns could own the
            // primary key and the other would pay for an index on every insert, including the
            // unordered inserts that never use it.
            statement.execute("""
                              CREATE TABLE %s (
                                  queue_id      smallint    NOT NULL,
                                  shard         smallint    NOT NULL,
                                  msg_key       text        NOT NULL,
                                  key_order     bigint      NOT NULL,
                                  seq           bigint      NOT NULL,
                                  payload       bytea       NOT NULL,
                                  payload_type  int         NOT NULL,
                                  meta_data     bytea,
                                  enqueued_at   timestamptz NOT NULL DEFAULT now(),
                                  visible_at    timestamptz NOT NULL DEFAULT now(),
                                  attempts      smallint    NOT NULL DEFAULT 0,
                                  lease         bigint,
                                  PRIMARY KEY (queue_id, shard, msg_key, key_order)
                              ) WITH (fillfactor = 80)
                              """.formatted(ORDERED_TABLE));
            // Discovery: the owner still needs to find newly arrived work in commit order. This is
            // the index the ordered lane pays for and the unordered lane does not.
            statement.execute("CREATE INDEX %s_seq ON %s (queue_id, shard, seq)"
                                      .formatted(ORDERED_TABLE, ORDERED_TABLE));
            statement.execute("CREATE INDEX %s_visible ON %s (queue_id, shard, visible_at)"
                                      .formatted(ORDERED_TABLE, ORDERED_TABLE));

            // The cold lane. Dead letters are rare, read by humans, and allowed to be expensive —
            // which is why they live outside the live lanes rather than as a flag on them. Keeping
            // them out is what lets the live tables stay narrow and lets an acked row simply vanish.
            //
            // Deviation from §4.1, which specifies a jsonb payload here: this engine's payloads are
            // opaque bytes end to end, so jsonb would mean inventing a decode the rest of the engine
            // does not have. Revisit when there is an admin surface that needs to query into them.
            statement.execute("""
                              CREATE TABLE %s (
                                  id               bigserial PRIMARY KEY,
                                  queue_id         smallint    NOT NULL,
                                  shard            smallint    NOT NULL,
                                  source_lane      text        NOT NULL,
                                  msg_key          text,
                                  key_order        bigint,
                                  seq              bigint      NOT NULL,
                                  payload          bytea       NOT NULL,
                                  payload_type     int         NOT NULL,
                                  attempts         smallint    NOT NULL,
                                  last_error       text,
                                  dead_lettered_at timestamptz NOT NULL DEFAULT now()
                              )
                              """.formatted(DLQ_TABLE));
            statement.execute("CREATE INDEX %s_queue ON %s (queue_id, shard)".formatted(DLQ_TABLE, DLQ_TABLE));

            statement.execute("""
                              CREATE TABLE %s (
                                  queue_id    smallint    NOT NULL,
                                  lane        text        NOT NULL,
                                  shard       smallint    NOT NULL,
                                  owner       text,
                                  fence       bigint      NOT NULL DEFAULT 0,
                                  lease_until timestamptz NOT NULL DEFAULT now(),
                                  -- Lane is part of the key because the two lanes are separate tables
                                  -- with separate owners. Sharing a lease row made them compete: an
                                  -- unordered message could land in a shard leased by the ordered
                                  -- consumer, which never reads that lane, and simply sit there.
                                  PRIMARY KEY (queue_id, lane, shard)
                              )
                              """.formatted(LEASE_TABLE));

            // Membership. The lease table cannot answer "how many instances are there" on its own:
            // an instance holding no shards — the one that most needs to be counted, because it is
            // waiting for a fair share — leaves no trace in it.
            statement.execute("""
                              CREATE TABLE %s (
                                  queue_id    smallint    NOT NULL,
                                  instance_id text        NOT NULL,
                                  last_seen   timestamptz NOT NULL DEFAULT now(),
                                  PRIMARY KEY (queue_id, instance_id)
                              )
                              """.formatted(INSTANCE_TABLE));

            // Session fences are allocated negative, so they can never collide with an owner fence
            // in the shared `lease` column. The two mean opposite things and are read by the same
            // predicates; keeping their ranges disjoint removes a whole class of confusion.
            statement.execute("DROP SEQUENCE IF EXISTS " + SESSION_FENCE_SEQUENCE);
            statement.execute("CREATE SEQUENCE " + SESSION_FENCE_SEQUENCE + " START WITH 1 INCREMENT BY 1");

            // Sequences are created per queue, by registerQueue, because they must be scoped to
            // (queue, shard) rather than to shard alone. Drop whatever a previous run left behind.
            statement.execute("""
                              DO $$
                              DECLARE r record;
                              BEGIN
                                  FOR r IN SELECT sequencename FROM pg_sequences
                                           WHERE sequencename LIKE 'ng_seq_q%' OR sequencename LIKE 'ng_ordered_seq_q%'
                                  LOOP
                                      EXECUTE 'DROP SEQUENCE IF EXISTS ' || quote_ident(r.sequencename);
                                  END LOOP;
                              END $$;
                              """);
        }
    }

    /**
     * Make a queue usable: one lease row per lane and shard, and one sequence per lane and shard.
     * <p>
     * Leases are seeded rather than upserted on acquisition so that taking a shard is an
     * {@code UPDATE} — the lease row's existence is not contended, only its ownership.
     * <p>
     * <b>The sequences are scoped to (queue, shard), not to shard.</b> They were once named per shard
     * alone, so every queue on a shard drew from one counter. The cursor read walks {@code seq} and
     * treats any value it steps over as a hole to be chased, so from each queue's view the values the
     * other queues took were holes: {@code queues - 1} of them per message, measured at 3.8 with five
     * queues. The chase queries were not the damage — the acknowledgement floor is bounded by the
     * lowest pending hole, so it stayed pinned for the whole {@code holeExpiry} while the hole map
     * grew. A dense sequence per queue is what makes "a gap means an uncommitted transaction" true.
     */
    public static void registerQueue(DataSource dataSource, short queueId, int shardCount) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            for (var shard = 0; shard < shardCount; shard++) {
                // CACHE 1 so an allocated value is one that will be committed.
                statement.execute("CREATE SEQUENCE IF NOT EXISTS " + sequenceName(queueId, shard)
                                  + " START WITH 1 INCREMENT BY 1 CACHE 1");
                statement.execute("CREATE SEQUENCE IF NOT EXISTS " + orderedSequenceName(queueId, shard)
                                  + " START WITH 1 INCREMENT BY 1 CACHE 1");
            }
        }
        seedLeases(dataSource, queueId, shardCount);
    }

    private static void seedLeases(DataSource dataSource, short queueId, int shardCount) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + LEASE_TABLE + " (queue_id, lane, shard, owner, fence, lease_until) "
                     + "VALUES (?, ?, ?, NULL, 0, now()) ON CONFLICT DO NOTHING")) {
            for (var lane : new String[]{"unordered", "ordered"}) {
                for (var shard = 0; shard < shardCount; shard++) {
                    statement.setShort(1, queueId);
                    statement.setString(2, lane);
                    statement.setShort(3, (short) shard);
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    public static String sequenceName(short queueId, int shard) {
        return "ng_seq_q" + queueId + "_s" + shard;
    }

    public static String orderedSequenceName(short queueId, int shard) {
        return "ng_ordered_seq_q" + queueId + "_s" + shard;
    }

    /**
     * Same key, same shard — which is what makes per-key ordering a consequence of ownership rather
     * than something a query has to enforce. {@code String.hashCode} is specified, so the mapping is
     * stable across JVMs and across restarts.
     */
    public static int shardForKey(String key, int shardCount) {
        return Math.floorMod(key.hashCode(), shardCount);
    }
}
