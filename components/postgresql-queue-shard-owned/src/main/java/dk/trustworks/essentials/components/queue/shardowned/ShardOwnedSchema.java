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

import dk.trustworks.essentials.components.queue.shardowned.spi.QueueName;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Schema for the shard-owned queue engine. See {@code docs/durable-queue-shard-owned.md} §2.
 * <p>
 * Five tables — the two live lanes, the dead letter lane, the shard lease table and the membership
 * table — plus one sequence per {@code (queue, shard)} per lane and one global session-fence
 * sequence.
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
public final class ShardOwnedSchema {

    /**
     * The ordered lane's routing space. Fixed, not configurable, and frozen for the life of the
     * schema.
     * <p>
     * This is the defect this constant exists to remove. A key's owner was {@code hash(key) mod
     * shardCount}, so {@code shardCount} decided both where a key lives and how many consumers can
     * share the work — and changing it moved every key, which is why the ordered lane could never
     * change it in a running system and a user had to guess correctly once, with no way back. Fixing
     * the routing space separates the two: growing the number of consumers now moves UNITS between
     * owners, and a unit's contents never move.
     * <p>
     * Sixty-four because it must exceed the largest useful instance count and nothing more:
     * {@code ShardOwnedShardCountSweepIT} put the throughput knee at four, with eight buying 95% of
     * what sixteen does, so sixty-four is already far past the point of return. Larger is not free —
     * idle poll cost is linear in units held and does not amortise — and smaller would cap horizontal
     * scale at a number someone could actually reach. See
     * {@code docs/durable-queue-ordered-routing-design.md} §1.
     * <p>
     * The UNORDERED lane keeps a configurable shard count. It has no routing problem — placement is
     * round-robin, so nothing depends on the count — and it can therefore still grow with a single
     * call.
     */
    public static final int ORDERED_UNITS = 64;

    public static final String UNORDERED_TABLE = "shard_queue_unordered";
    public static final String ORDERED_TABLE   = "shard_queue_ordered";
    public static final String DLQ_TABLE       = "shard_queue_dead_letter";
    public static final String LEASE_TABLE     = "shard_queue_lease";
    public static final String INSTANCE_TABLE  = "shard_queue_instance";
    public static final String SESSION_FENCE_SEQUENCE = "shard_queue_session_fence";
    public static final String REGISTRY_TABLE = "shard_queue_registry";
    public static final String QUEUE_ID_SEQUENCE = "shard_queue_id_seq";
    /** Views that render {@code bytea} payloads as text, for reading a queue in {@code psql}. */
    public static final String UNORDERED_VIEW   = "shard_queue_unordered_readable";
    public static final String ORDERED_VIEW     = "shard_queue_ordered_readable";
    public static final String DLQ_VIEW         = "shard_queue_dead_letter_readable";

    /**
     * The framework's bootstrap advisory-lock key, so this engine's DDL serialises behind the same
     * lock as every other Essentials component's.
     * <p>
     * <b>Deliberately duplicated rather than imported.</b> The canonical definition is
     * {@code PostgresqlUtil.ESSENTIALS_BOOTSTRAP_LOCK_KEY} in {@code components/foundation}, whose
     * helper takes a JDBI {@code Handle}; this module depends on {@code shared} alone and speaks
     * plain JDBC. What matters is that the <em>value</em> matches — a component locking on a
     * different key is not protected from the components that use this one, and an application
     * bootstrapping the event store and this queue at the same time is exactly when that bites. If
     * the constant there ever changes, it has to change here.
     */
    public static final long ESSENTIALS_BOOTSTRAP_LOCK_KEY = 0xE55E_4711_B007_DD15L;

    private ShardOwnedSchema() {
    }

    /**
     * Run DDL under the framework's bootstrap advisory lock, in one transaction.
     * <p>
     * PostgreSQL's {@code IF NOT EXISTS} is <b>not atomic against concurrent sessions</b>: two
     * instances starting together can both read "absent" from {@code pg_class}, both write the
     * catalog entry, and one fails with {@code duplicate key value violates unique constraint
     * "pg_type_typname_nsp_index"}. Natural pod-start spread usually hides it; anything that
     * releases instances simultaneously does not.
     * <p>
     * {@code pg_advisory_xact_lock} rather than {@code pg_advisory_lock}, and therefore inside an
     * explicit transaction: in autocommit the lock would be released between statements and the
     * protection would be gone while still looking present.
     */
    private static void withBootstrapLock(DataSource dataSource, SqlWork work) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            var autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                    lock.setLong(1, ESSENTIALS_BOOTSTRAP_LOCK_KEY);
                    lock.execute();
                }
                try (var statement = connection.createStatement()) {
                    work.run(statement);
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(java.sql.Statement statement) throws SQLException;
    }

    /**
     * Create everything the engine needs, if it is not already there. <b>Non-destructive and
     * idempotent</b>, so it is safe to run on every application start.
     * <p>
     * This is the method a container or a starter calls. It is separate from {@link #recreate} on
     * purpose: the only schema entry point used to be the destructive one, which is fine for a test
     * fixture and is a loaded gun anywhere else — wired into a start-up hook it would drop every
     * queue table on every boot, and the first time anyone noticed would be in production.
     */
    public static void initialize(DataSource dataSource) throws SQLException {
        requireNonNull(dataSource, "No dataSource provided");
        withBootstrapLock(dataSource, statement -> {

            statement.execute("""
                              CREATE TABLE IF NOT EXISTS %s (
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
            statement.execute("CREATE INDEX IF NOT EXISTS %s_visible ON %s (queue_id, shard, visible_at)"
                                      .formatted(UNORDERED_TABLE, UNORDERED_TABLE));

            // The ordered lane. Its primary key is (queue_id, shard, msg_key, key_order), NOT
            // (queue_id, shard, seq) — which is the whole reason §4.1 splits the lanes into separate
            // tables. Finding the head of a key becomes a primary-key prefix scan, and asking whether
            // a key has anything queued becomes a prefix existence check, neither of which needs a
            // secondary index. In one shared table only one of the two access patterns could own the
            // primary key and the other would pay for an index on every insert, including the
            // unordered inserts that never use it.
            statement.execute("""
                              CREATE TABLE IF NOT EXISTS %s (
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
            statement.execute("CREATE INDEX IF NOT EXISTS %s_seq ON %s (queue_id, shard, seq)"
                                      .formatted(ORDERED_TABLE, ORDERED_TABLE));
            statement.execute("CREATE INDEX IF NOT EXISTS %s_visible ON %s (queue_id, shard, visible_at)"
                                      .formatted(ORDERED_TABLE, ORDERED_TABLE));

            // The cold lane. Dead letters are rare, read by humans, and allowed to be expensive —
            // which is why they live outside the live lanes rather than as a flag on them. Keeping
            // them out is what lets the live tables stay narrow and lets an acked row simply vanish.
            //
            // Deviation from §4.1, which specifies a jsonb payload here: this engine's payloads are
            // opaque bytes end to end, so jsonb would mean inventing a decode the rest of the engine
            // does not have. Revisit when there is an admin surface that needs to query into them.
            statement.execute("""
                              CREATE TABLE IF NOT EXISTS %s (
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
            statement.execute("CREATE INDEX IF NOT EXISTS %s_queue ON %s (queue_id, shard)".formatted(DLQ_TABLE, DLQ_TABLE));

            statement.execute("""
                              CREATE TABLE IF NOT EXISTS %s (
                                  queue_id    smallint    NOT NULL,
                                  lane        text        NOT NULL,
                                  shard       smallint    NOT NULL,
                                  owner       text,
                                  fence       bigint      NOT NULL DEFAULT 0,
                                  -- NULL for an instance-owned unit, set for a pull session's.
                                  --
                                  -- An instance's units carry no expiry: their liveness is the
                                  -- owner's row in shard_queue_instance, which the heartbeat
                                  -- refreshes once per queue. Storing an expiry per unit meant
                                  -- WRITING one row per unit held on every heartbeat, which at a
                                  -- sixty-four-unit ordered space became the engine's dominant idle
                                  -- cost — 17 lease writes a second against 6.8 reads, idle.
                                  -- Correctness never rested on the expiry; it rests on the fence.
                                  --
                                  -- A SHARD-scope pull session is not an instance and heartbeats
                                  -- nothing, so it keeps a real expiry and renews it. Both kinds
                                  -- live in this column, and which one applies is decided by whether
                                  -- it is NULL.
                                  lease_until timestamptz,
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
                              CREATE TABLE IF NOT EXISTS %s (
                                  queue_id    smallint    NOT NULL,
                                  instance_id text        NOT NULL,
                                  last_seen   timestamptz NOT NULL DEFAULT now(),
                                  PRIMARY KEY (queue_id, instance_id)
                              )
                              """.formatted(INSTANCE_TABLE));

            // The name-to-id registry. The engine addresses a queue by an interned smallint because
            // a two-byte column in every row's primary key is what keeps the hot index dense; the
            // mapping from a name to that id has to live SOMEWHERE, and every process holding its
            // own copy is a shared contract stored in several places at once.
            //
            // shard_count lives here rather than in each caller's constructor because it is a
            // property of the queue, not of the process reading it. A key's shard is
            // hash(key) mod shard_count, so two processes that disagree route the same key to
            // different shards and leave whole shards unowned. Recording it once makes the
            // disagreement impossible to express.
            // smallint, so the ceiling is 32 767 queues. Gaps are harmless: an id is an interning
            // token, and a burned value from a losing INSERT ... ON CONFLICT means nothing.
            statement.execute("CREATE SEQUENCE IF NOT EXISTS " + QUEUE_ID_SEQUENCE
                              + " START WITH 1 INCREMENT BY 1 MAXVALUE 32767");
            statement.execute("""
                              CREATE TABLE IF NOT EXISTS %s (
                                  queue_id    smallint    NOT NULL PRIMARY KEY,
                                  queue_name  text        NOT NULL UNIQUE,
                                  shard_count int         NOT NULL,
                                  -- The ordered lane's routing space AS IT WAS WHEN THIS QUEUE WAS
                                  -- CREATED. Recorded, not assumed: it is baked into where every
                                  -- ordered key lives, so a build whose ORDERED_UNITS differs must be
                                  -- refused rather than allowed to re-route a live queue silently.
                                  ordered_units int        NOT NULL DEFAULT 64,
                                  created_at  timestamptz NOT NULL DEFAULT now()
                              )
                              """.formatted(REGISTRY_TABLE));

            // Session fences are allocated negative, so they can never collide with an owner fence
            // in the shared `lease` column. The two mean opposite things and are read by the same
            // predicates; keeping their ranges disjoint removes a whole class of confusion.
            statement.execute("CREATE SEQUENCE IF NOT EXISTS " + SESSION_FENCE_SEQUENCE
                              + " START WITH 1 INCREMENT BY 1");

            // Payloads are bytea because the database never looks inside them, and that is what
            // makes the write path cheap. The cost is that `SELECT payload FROM ...` in psql returns
            // \x7b226f... and an operator holding a support ticket cannot read their own message.
            //
            // A view fixes the ergonomics without touching the storage: the bytes stay bytes, the
            // hot path is unchanged, and nothing here is queried by the engine. Payloads that are
            // valid UTF-8 — which JSON, XML and text all are — render as text; anything else falls
            // back to hex rather than raising, because a view that throws on one binary row is worse
            // than one that shows it as hex.
            statement.execute("CREATE OR REPLACE FUNCTION shard_queue_readable(payload bytea)"
                              + " RETURNS text AS $$"
                              + " BEGIN RETURN convert_from(payload, 'UTF8');"
                              + " EXCEPTION WHEN others THEN RETURN encode(payload, 'hex');"
                              + " END $$ LANGUAGE plpgsql IMMUTABLE");
            statement.execute("CREATE OR REPLACE VIEW " + UNORDERED_VIEW + " AS"
                              + " SELECT r.queue_name, u.queue_id, u.shard, u.seq, u.payload_type,"
                              + "        shard_queue_readable(u.payload) AS payload,"
                              + "        u.attempts, u.enqueued_at, u.visible_at,"
                              + "        u.visible_at <= now() AS deliverable"
                              + "   FROM " + UNORDERED_TABLE + " u"
                              + "   LEFT JOIN " + REGISTRY_TABLE + " r ON r.queue_id = u.queue_id");
            statement.execute("CREATE OR REPLACE VIEW " + ORDERED_VIEW + " AS"
                              + " SELECT r.queue_name, o.queue_id, o.shard, o.msg_key, o.key_order, o.seq,"
                              + "        o.payload_type, shard_queue_readable(o.payload) AS payload,"
                              + "        o.attempts, o.enqueued_at, o.visible_at,"
                              + "        o.visible_at <= now() AS deliverable"
                              + "   FROM " + ORDERED_TABLE + " o"
                              + "   LEFT JOIN " + REGISTRY_TABLE + " r ON r.queue_id = o.queue_id");
            statement.execute("CREATE OR REPLACE VIEW " + DLQ_VIEW + " AS"
                              + " SELECT r.queue_name, d.queue_id, d.shard, d.source_lane, d.msg_key,"
                              + "        d.key_order, d.seq, d.payload_type,"
                              + "        shard_queue_readable(d.payload) AS payload,"
                              + "        d.attempts, d.last_error, d.dead_lettered_at"
                              + "   FROM " + DLQ_TABLE + " d"
                              + "   LEFT JOIN " + REGISTRY_TABLE + " r ON r.queue_id = d.queue_id");
        });
    }

    /**
     * Drop everything this engine owns and build it again from empty. <b>Destructive.</b>
     * <p>
     * For tests and for a deliberate reset. Named so that nobody reaches for it by accident: the
     * per-queue sequences are found by pattern and dropped, which is what makes a fresh run
     * reproducible and what makes running this against real data unrecoverable.
     */
    public static void recreate(DataSource dataSource) throws SQLException {
        requireNonNull(dataSource, "No dataSource provided");
        withBootstrapLock(dataSource, statement -> {
            for (var view : List.of(UNORDERED_VIEW, ORDERED_VIEW, DLQ_VIEW)) {
                statement.execute("DROP VIEW IF EXISTS " + view);
            }
            for (var table : List.of(UNORDERED_TABLE, ORDERED_TABLE, DLQ_TABLE,
                                     LEASE_TABLE, INSTANCE_TABLE, REGISTRY_TABLE)) {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
            statement.execute("DROP SEQUENCE IF EXISTS " + QUEUE_ID_SEQUENCE);
            statement.execute("DROP SEQUENCE IF EXISTS " + SESSION_FENCE_SEQUENCE);
            // Per-queue sequences are created by registerQueue and named by pattern, so this is the
            // only way to find what a previous run left behind.
            statement.execute("""
                              DO $$
                              DECLARE r record;
                              BEGIN
                                  FOR r IN SELECT sequencename FROM pg_sequences
                                           WHERE sequencename LIKE 'shard_queue_seq_q%' OR sequencename LIKE 'shard_queue_ordered_seq_q%'
                                  LOOP
                                      EXECUTE 'DROP SEQUENCE IF EXISTS ' || quote_ident(r.sequencename);
                                  END LOOP;
                              END $$;
                              """);
        });
        initialize(dataSource);
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
    /**
     * A queue as the registry knows it: its interned id and the shard count fixed with it.
     */
    /**
     * @param orderedUnits the ordered routing space this queue's data was written under. Normally
     *                     {@link #ORDERED_UNITS}; different only on a queue created by another build,
     *                     which is the case {@link #registerQueue(DataSource, QueueName, int)} refuses.
     */
    public record RegisteredQueue(QueueName name, short queueId, int shardCount, int orderedUnits) {
    }

    /**
     * Register a queue by name, or return what it was already registered as.
     * <p>
     * Idempotent, and safe to call from every process at start-up: the id is interned under a unique
     * constraint on the name, so concurrent callers converge on one id rather than racing to pick
     * different ones.
     *
     * @throws IllegalStateException if the name is already registered with a different shard count.
     *                               Shards are the unit of ordering, so silently accepting the second
     *                               count would re-route every key and strand whole shards
     */
    public static RegisteredQueue registerQueue(DataSource dataSource, QueueName name, int shardCount) throws SQLException {
        return registerQueue(dataSource, name, shardCount, ORDERED_UNITS);
    }

    /**
     * Register a queue with an explicit ordered routing space.
     * <p>
     * <b>Only for a queue that will be consumed by more instances than {@link #ORDERED_UNITS}.</b> The
     * space caps how many instances can hold ordered units for this queue, and exceeding it degrades
     * rather than breaks — the surplus instances idle on that lane and recover if the count drops — so
     * the default is the right answer unless you already know it is not.
     * <p>
     * It is frozen for the life of the queue's data, because a key's unit is
     * {@code mix(hash(key)) mod} this number. That is safe to fix per queue in a way it was never safe
     * to fix globally: the value is recorded here and every process routes by what it finds, so
     * upgrading the framework's default changes nothing for a queue that already exists.
     * <p>
     * Measured cost of a larger space, one queue, idle: 64 / 256 / 1024 units all sit at 1.2 to 1.3
     * queries a second, because the reads are batched per queue and an instance-owned unit carries no
     * lease to renew. What does grow is per-unit state — a lease row and an owner object each — so a
     * process running hundreds of queues should not raise this without reason.
     */
    public static RegisteredQueue registerQueue(DataSource dataSource, QueueName name, int shardCount,
                                                int orderedUnits) throws SQLException {
        requireNonNull(dataSource, "No dataSource provided");
        requireNonNull(name, "No queue name provided");
        requireTrue(shardCount > 0, "shardCount must be positive");
        requireTrue(orderedUnits > 0, "orderedUnits must be positive");

        var existing = resolve(dataSource, name);
        if (existing.isEmpty()) {
            try (var connection = dataSource.getConnection();
                 var statement = connection.prepareStatement(
                         "INSERT INTO " + REGISTRY_TABLE
                         + " (queue_id, queue_name, shard_count, ordered_units)"
                         + " VALUES (nextval('" + QUEUE_ID_SEQUENCE + "'), ?, ?, ?)"
                         + " ON CONFLICT (queue_name) DO NOTHING")) {
                statement.setString(1, name.value());
                statement.setInt(2, shardCount);
                statement.setInt(3, orderedUnits);
                statement.executeUpdate();
            }
            // Read back rather than trusting the insert: on conflict it wrote nothing, because
            // another process registered the same name first. Its id is the one that counts.
            existing = resolve(dataSource, name);
        }

        var registered = existing.orElseThrow(
                () -> new IllegalStateException("Queue '" + name + "' could not be registered"));
        if (registered.shardCount() != shardCount) {
            throw new IllegalStateException(
                    "Queue '" + name + "' is already registered with " + registered.shardCount()
                    + " shards and cannot be re-registered with " + shardCount
                    + ". Shards are the unit of ordering: a key's shard is hash(key) mod shardCount, "
                    + "so changing the count re-routes every key and leaves shards nobody owns");
        }
        // NO guard on a differing ORDERED_UNITS, deliberately. An earlier revision refused here, which
        // turned a version upgrade into "drain this queue and re-create it" — the manual, outage-shaped
        // step this engine exists to avoid, and the same non-procedure §1 of the design rejects for
        // shardCount. Routing reads the queue's own recorded space instead, so a build with a
        // different default simply uses it for queues it creates and leaves existing ones alone.
        // Seeded against the space this queue actually has, which may not be this build's default.
        registerQueue(dataSource, registered.queueId(), shardCount, registered.orderedUnits());
        return registered;
    }

    /**
     * Grow a queue's shard count.
     *
     * <h2>Why this is possible at all, when the count is described as fixed</h2>
     * The two lanes are not equally pinned, and treating them as one is what made the count look
     * immutable.
     * <p>
     * <b>Unordered messages are assigned round-robin.</b> Nothing about them depends on which shard
     * they landed in — no key hashes to it, no ordering rests on it. Adding shards adds sequences and
     * lease rows; the messages already stored stay where they are and are still delivered by whoever
     * owns those shards. There is nothing to re-route.
     * <p>
     * <b>Ordered messages are the constraint.</b> A key's shard is {@code hash(key) mod shardCount},
     * so changing the count sends a key's future messages to a different shard from its past ones —
     * two owners for one key, which is reordering rather than the duplicate at-least-once permits.
     * This therefore refuses while the ordered lane holds anything for the queue.
     *
     * <h2>What the caller still has to do</h2>
     * Shard count is baked into every running {@code ShardOwnedQueue}, so growing it in the database
     * changes nothing until instances are restarted — and until they all are, two moduli are in
     * flight at once. For an unordered-only queue that is harmless: every shard has an owner either
     * way, so a rolling restart is fine. With ordered traffic it is not, which is the other half of
     * why the ordered lane must be empty.
     * <p>
     * <b>Shrinking is not supported.</b> Messages in the shards being removed would be addressed by
     * nobody, and there is no safe general answer to where they should go — drain those shards and
     * recreate the queue instead.
     *
     * @return the queue as it now stands
     * @throws IllegalStateException if the count is not an increase
     */
    public static RegisteredQueue growShardCount(DataSource dataSource, QueueName name, int newShardCount) throws SQLException {
        requireNonNull(dataSource, "No dataSource provided");
        requireNonNull(name, "No queue name provided");
        var current = resolve(dataSource, name)
                .orElseThrow(() -> new IllegalStateException("Queue '" + name + "' is not registered"));
        if (newShardCount <= current.shardCount()) {
            throw new IllegalStateException(
                    "Queue '" + name + "' has " + current.shardCount() + " shards and can only grow: "
                    + newShardCount + " is not an increase. Shrinking would leave the messages in the "
                    + "removed shards addressed by nobody");
        }
        // No ordered-lane guard, and its removal is the point of the fixed routing space.
        //
        // This used to refuse while the ordered lane held anything, because a key's shard was
        // hash(key) mod shardCount and growing the count sent a key's next message to a different
        // shard from its last — two owners for one key. There was no way for an operator to satisfy
        // that except stopping the producers, which is an outage rather than a procedure, so the count
        // was effectively frozen for the life of the queue. The ordered lane now routes on a fixed
        // space of its own (ORDERED_UNITS) and does not consult this number at all, so growing it
        // moves nothing and can happen with ordered traffic in flight.
        registerQueue(dataSource, current.queueId(), newShardCount);
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "UPDATE " + REGISTRY_TABLE + " SET shard_count = ? WHERE queue_id = ?")) {
            statement.setInt(1, newShardCount);
            statement.setShort(2, current.queueId());
            statement.executeUpdate();
        }
        return new RegisteredQueue(name, current.queueId(), newShardCount, current.orderedUnits());
    }

    /**
     * Datasources already proven able to see another backend's assigned transaction id. Identity, not
     * equality: a {@code DataSource} is a resource, and framework proxies define equality as they
     * like — the same reasoning as {@code ShardRuntime.SHARED}.
     */
    private static final Set<DataSource> WATERMARK_VERIFIED =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Refuse to run the ordered lane where its safety mechanism cannot work.
     * <p>
     * The ordered lane's cursor is a safe watermark: it passes a sequence value only once
     * {@code max(backend_xid)} over {@code pg_stat_activity} proves no running write transaction could
     * still commit a lower one. If that column is redacted — a managed provider, a hardened role, a
     * later major changing the rules — the query does not fail. It returns a SUBSET of the running
     * transactions, or none, and a subset is not a degraded answer but a wrong one: the watermark
     * advances over a live writer and its message is stepped over.
     * <p>
     * A comment cannot defend against that, and neither can reading the column and finding it
     * non-null, because it is legitimately null for backends that have not written. So this
     * <b>constructs the condition</b>: it opens a second connection, forces it to take a real xid, and
     * asserts the first can see it. Anything else — invisible, or the view unreadable — throws here,
     * at start-up, rather than losing a message later.
     * <p>
     * Verified once per {@code DataSource}: it costs two connections and one aborted transaction.
     */
    public static void verifyWatermarkPrerequisites(DataSource dataSource) throws SQLException {
        requireNonNull(dataSource, "No dataSource provided");
        synchronized (WATERMARK_VERIFIED) {
            if (WATERMARK_VERIFIED.contains(dataSource)) {
                return;
            }
        }
        try (var writer = dataSource.getConnection()) {
            writer.setAutoCommit(false);
            try {
                int writerPid;
                try (var statement = writer.prepareStatement(
                        // pg_current_xact_id assigns a REAL xid, which is the thing being looked for.
                        // A read-only transaction never gets one, so probing with one would prove
                        // nothing and pass everywhere.
                        "SELECT pg_backend_pid(), pg_current_xact_id()");
                     var resultSet = statement.executeQuery()) {
                    resultSet.next();
                    writerPid = resultSet.getInt(1);
                }
                try (var observer = dataSource.getConnection();
                     var statement = observer.prepareStatement(
                             "SELECT backend_xid IS NOT NULL FROM pg_stat_activity WHERE pid = ?")) {
                    statement.setInt(1, writerPid);
                    try (var resultSet = statement.executeQuery()) {
                        var visible = resultSet.next() && resultSet.getBoolean(1);
                        if (!visible) {
                            throw new IllegalStateException(
                                    "This database does not show one connection the transaction id of "
                                    + "another (pg_stat_activity.backend_xid was not visible for a "
                                    + "backend that certainly held one). The ordered lane's cursor "
                                    + "depends on that to decide when a sequence value can never "
                                    + "arrive; without it the cursor would step over messages whose "
                                    + "transaction is still running, losing them. Grant the queue's "
                                    + "role pg_read_all_stats, or use the unordered lane.");
                        }
                    }
                }
            } finally {
                // Nothing was written, and the xid is burned either way.
                writer.rollback();
            }
        } catch (SQLException e) {
            throw new SQLException(
                    "Could not verify that pg_stat_activity.backend_xid is readable, which the ordered "
                    + "lane's cursor depends on for correctness. Grant the queue's role "
                    + "pg_read_all_stats, or use the unordered lane.", e);
        }
        synchronized (WATERMARK_VERIFIED) {
            WATERMARK_VERIFIED.add(dataSource);
        }
    }

    private static void seedLane(java.sql.PreparedStatement statement, short queueId, String lane, int units)
            throws SQLException {
        for (var shard = 0; shard < units; shard++) {
            statement.setShort(1, queueId);
            statement.setString(2, lane);
            statement.setInt(3, shard);
            statement.addBatch();
        }
    }

    private static long countOrdered(DataSource dataSource, short queueId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + ORDERED_TABLE + " WHERE queue_id = ?")) {
            statement.setShort(1, queueId);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    /** What a name is interned to, or empty if it has never been registered. */
    public static Optional<RegisteredQueue> resolve(DataSource dataSource, QueueName name) throws SQLException {
        requireNonNull(dataSource, "No dataSource provided");
        requireNonNull(name, "No queue name provided");
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT queue_id, shard_count, ordered_units FROM " + REGISTRY_TABLE
                     + " WHERE queue_name = ?")) {
            statement.setString(1, name.value());
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next()
                       ? Optional.of(new RegisteredQueue(name, resultSet.getShort(1), resultSet.getInt(2),
                                                         resultSet.getInt(3)))
                       : Optional.empty();
            }
        }
    }

    /** Every registered queue, for an admin surface or a start-up log line. */
    public static List<QueueName> queueNames(DataSource dataSource) throws SQLException {
        requireNonNull(dataSource, "No dataSource provided");
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT queue_name FROM " + REGISTRY_TABLE + " ORDER BY queue_name");
             var resultSet = statement.executeQuery()) {
            var names = new ArrayList<QueueName>();
            while (resultSet.next()) {
                names.add(QueueName.of(resultSet.getString(1)));
            }
            return names;
        }
    }

    /**
     * The low-level form: create a queue's sequences and lease rows against an id the caller has
     * already interned. {@link #registerQueue(DataSource, QueueName, int)} is the one to reach for —
     * it is the only one that can tell whether the shard count agrees with what the queue was
     * created as.
     */
    public static void registerQueue(DataSource dataSource, short queueId, int shardCount) throws SQLException {
        registerQueue(dataSource, queueId, shardCount, ORDERED_UNITS);
    }

    public static void registerQueue(DataSource dataSource, short queueId, int shardCount,
                                     int orderedUnits) throws SQLException {
        requireNonNull(dataSource, "No dataSource provided");
        // Under the bootstrap lock like every other DDL path here: every instance calls this at
        // start-up, so CREATE SEQUENCE IF NOT EXISTS races exactly the way CREATE TABLE does.
        withBootstrapLock(dataSource, statement -> {
            // ONE sequence for the whole ordered lane, not one per shard.
            //
            // Per-shard sequences existed to keep values dense within a shard, because that density
            // was how the ordered lane told an uncommitted value from someone else's. It no longer
            // decides that by density — see OrderedShardOwner.advanceWatermark — so the only thing
            // per-shard ordered sequences still cost is relations: one per shard per queue, which is
            // what makes a large routing space unaffordable across many queues. Collapsing them also
            // makes an ordered `seq` unique within the queue, which retires the trap that seq 1 exists
            // in every shard.
            //
            // The unordered lane is NOT collapsed. It still detects holes by density, and its
            // range-delete acknowledgement is bounded by a floor that depends on it.
            //
            // CACHE 1 so an allocated value is one that will be committed, and because the watermark's
            // safety argument rests on values being handed out in wall-clock order. Never raise it.
            statement.execute("CREATE SEQUENCE IF NOT EXISTS " + orderedSequenceName(queueId)
                              + " START WITH 1 INCREMENT BY 1 CACHE 1");
            for (var shard = 0; shard < shardCount; shard++) {
                statement.execute("CREATE SEQUENCE IF NOT EXISTS " + sequenceName(queueId, shard)
                                  + " START WITH 1 INCREMENT BY 1 CACHE 1");
            }
        });
        seedLeases(dataSource, queueId, shardCount, orderedUnits);
    }

    private static void seedLeases(DataSource dataSource, short queueId, int shardCount, int orderedUnits)
            throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "INSERT INTO " + LEASE_TABLE + " (queue_id, lane, shard, owner, fence) "
                     + "VALUES (?, ?, ?, NULL, 0) ON CONFLICT DO NOTHING")) {
            // Per lane, because the two no longer have the same number of units: the ordered lane's
            // space is fixed at ORDERED_UNITS and the unordered lane's is the caller's shard count.
            seedLane(statement, queueId, "unordered", shardCount);
            seedLane(statement, queueId, "ordered", orderedUnits);
            statement.executeBatch();
        }
    }

    public static String sequenceName(short queueId, int shard) {
        return "shard_queue_seq_q" + queueId + "_s" + shard;
    }

    /** One per queue, not one per shard — see the reasoning in {@link #registerQueue}. */
    public static String orderedSequenceName(short queueId) {
        return "shard_queue_ordered_seq_q" + queueId;
    }

    /**
     * Same key, same shard — which is what makes per-key ordering a consequence of ownership rather
     * than something a query has to enforce. {@code String.hashCode} is specified, so the mapping is
     * stable across JVMs and across restarts.
     * <p>
     * <b>The hash is mixed before the modulus.</b> {@code String.hashCode} is a {@code 31}-polynomial
     * and {@code floorMod} against a power of two keeps only its low bits, which for structured keys
     * are not well spread. Measured over 1 024 keys into 64 units, mean 16 per unit:
     * <pre>
     *   key shape            unmixed                 mixed
     *   ORDER-&lt;n&gt;            54/64 units, max 39     64/64 units, max 24
     *   acct-&lt;n&gt;-EU          52/64 units, max 42     64/64 units, max 25
     *   ORDER-%08d step 64   63/64 units, max 33     64/64 units, max 24
     * </pre>
     * So the unmixed hash leaves up to a fifth of the space unused and overloads its worst unit by
     * about 2.6x the mean, against 1.6x mixed. That is a skew in how evenly consumers share the work,
     * not a correctness problem — but the routing space is chosen once and then frozen, and the cost
     * of avoiding it is one multiply and two shifts on the enqueue path.
     */
    /**
     * The ordered lane's routing function.
     * <p>
     * Takes the space rather than reading {@link #ORDERED_UNITS}, because the constant is the default
     * for a queue being CREATED, not the truth for one that already holds data. A queue keeps the
     * space it was created with — see {@code ShardOwnedStorage.orderedUnits()} — so upgrading the
     * default cannot re-route anyone's live keys, and no version change asks an operator to drain
     * anything.
     */
    public static int unitForKey(String key, int orderedUnits) {
        requireTrue(orderedUnits > 0, "orderedUnits must be positive");
        return shardForKey(key, orderedUnits);
    }

    public static int shardForKey(String key, int shardCount) {
        return Math.floorMod(mix(key.hashCode()), shardCount);
    }

    /**
     * MurmurHash3's 32-bit finalizer. Chosen because it is fixed, published and short: this value is
     * baked into where every key lives, so it must never be "improved" later.
     */
    private static int mix(int hash) {
        var h = hash;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }
}
