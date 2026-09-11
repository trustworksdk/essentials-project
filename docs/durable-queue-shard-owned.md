# Shard-Owned PostgreSQL Queue — How It Works

**Module:** `components/postgresql-queue-shard-owned`
**Package:** `dk.trustworks.essentials.components.queue.shardowned`
**Status:** Experimental. Builds and tests with the reactor, **not published** (`maven.deploy.skip=true`). 7 unit tests and 69 integration tests, plus 8 in the Spring Boot starter, all green. No production use.

This document describes the engine as it currently stands: its storage, its threading, how a message travels from `enqueue` to a handler, and what it does when something fails. It is a reference for the system as built, not a record of how it came to be built that way.

Related documents:

| Document | Contents |
|---|---|
| [`LLM/LLM-postgresql-queue-shard-owned.md`](../LLM/LLM-postgresql-queue-shard-owned.md) | Consumer-facing configuration reference, sizing formulas, worked examples |
| [`docs/durable-queue-measurements.md`](./durable-queue-measurements.md) | Every measured number, and the environment that produced it |
| [`components/postgresql-queue-shard-owned/CLAUDE.md`](../components/postgresql-queue-shard-owned/CLAUDE.md) | Contributor gotchas and invariants |
| [`docs/archive/durable-queue-design-history.md`](./archive/durable-queue-design-history.md) | The original design proposal and its chronological defect log. Section references of the form `§4.3` in source comments point here |

---

## 1. The one idea

> Every message is assigned to a shard at enqueue. Each shard has exactly one owning consumer at a time, held by a lease. A consumer reads only shards it owns.

Three consequences follow directly, and everything else in the engine is a detail of making them work.

**There is no claim write.** A conventional PostgreSQL queue marks a row `is_being_delivered = true` so that competing consumers do not take it twice. Under shard ownership nobody else is reading the row, so the claim is implied by the lease and never reaches the database. Measured: `n_tup_upd` is zero on the steady-state path, and dead tuples per message fall to exactly 1.00 — the acknowledging delete and nothing else.

**There is no `SKIP LOCKED` and no lock contention.** Two consumers never look at the same row, so no scan work is wasted on rows another consumer has already taken.

**Per-key ordering is a consequence of ownership rather than of a query.** Messages sharing a key hash to the same shard; one instance owns that shard; that instance enforces FIFO with an in-memory set of the keys it currently has in flight. The expensive correlated anti-join a query-based ordered queue runs on every poll does not exist here. Because the guarantee rests on the lease rather than on a JVM's memory alone, it holds across processes.

The cost of these properties is paid in three places, all of which the rest of this document describes: a cursor that can step over uncommitted sequence values and must chase them (§5.3), a lease protocol that must fence out a superseded owner (§8), and a fixed shard count that cannot change once messages exist (§10).

---

## 2. Storage

Six tables and three families of sequence. `ShardOwnedSchema.create(dataSource, shardCount)` builds them once per database; `ShardOwnedSchema.registerQueue(dataSource, queueId, shardCount)` makes one queue usable.

### 2.1 The two live lanes

Ordered and unordered messages have different natural primary keys, and in one table only one of them could have it. They therefore get separate tables.

**`shard_queue_unordered`** — the high-volume lane.

```sql
CREATE TABLE shard_queue_unordered (
    queue_id      smallint    NOT NULL,
    shard         smallint    NOT NULL,
    seq           bigint      NOT NULL,   -- from shard_queue_seq_q<queue>_s<shard>
    payload       bytea       NOT NULL,
    payload_type  int         NOT NULL,
    meta_data     bytea,
    enqueued_at   timestamptz NOT NULL DEFAULT now(),
    visible_at    timestamptz NOT NULL DEFAULT now(),
    attempts      smallint    NOT NULL DEFAULT 0,
    lease         bigint,                 -- owner fence (pre-claim) or session fence (negative)
    lease_until   timestamptz,            -- NULL for a pre-claim, set for a session row lease
    PRIMARY KEY (queue_id, shard, seq)
) WITH (fillfactor = 80);

CREATE INDEX shard_queue_unordered_visible ON shard_queue_unordered (queue_id, shard, visible_at);
```

The primary key *is* the read index: the fast path is a forward range scan on `(queue_id, shard, seq)` with no secondary index to maintain on insert. The `_visible` index serves only the head sweep and delayed rows.

**`shard_queue_ordered`** — the per-key FIFO lane. Same columns plus `msg_key` and `key_order`, with a different primary key and one extra index:

```sql
    PRIMARY KEY (queue_id, shard, msg_key, key_order)   -- head-of-key is a PK prefix scan
    CREATE INDEX shard_queue_ordered_seq     ON shard_queue_ordered (queue_id, shard, seq);        -- discovery
    CREATE INDEX shard_queue_ordered_visible ON shard_queue_ordered (queue_id, shard, visible_at); -- sweep
```

Finding the head of a key, and asking whether a key has anything queued, are both primary-key prefix operations. The `_seq` index exists because the owner still needs to discover newly arrived work in commit order — it is the index the ordered lane pays for and the unordered lane does not.

Shared choices across both lanes:

- **`bytea`, not `jsonb`.** The database never looks inside a payload. Bytes skip JSON validation on write and detoast on read. Serialization is the caller's concern.
- **`smallint` `queue_id`,** interned via the registry (§2.4) rather than repeated as text, so the primary key of every row stays two bytes and the hot index stays dense.
- **`int` `payload_type`** is a discriminator the *application* defines, so a handler can tell what the bytes are without unpacking them. It is opaque to the engine: stored, carried through dead-lettering and resurrection, handed to the handler, and never compared, indexed or interpreted. Nothing interns it and nothing validates it — the mapping to a type name lives in each application, so two services disagreeing about what `1` means is not something this engine can detect. An earlier draft called it an "interned FQCN" and justified it as index density; it is in no index, and until recently the push handler was never given it at all.
- **No `is_being_delivered`, no `delivery_ts`.** Ownership replaces them.
- **`fillfactor = 80`,** so the rare failure-path `UPDATE` is a HOT update touching no index.

### 2.2 The cold lane

**`shard_queue_dead_letter`** holds messages the redelivery policy gave up on. It carries `source_lane`, the original `shard` and `seq`, the key and key order where they apply, `attempts`, `last_error` and `dead_lettered_at`. Keeping dead letters out of the live lanes is what lets an acknowledged row simply vanish rather than becoming a flag the live queries have to filter on.

The payload here is `bytea` like everywhere else. Payloads are opaque bytes end to end in this engine, so a `jsonb` dead-letter column would mean inventing a decode nothing else has.

### 2.3 Schema creation

`ShardOwnedSchema.initialize(dataSource)` creates everything if absent — non-destructive and
idempotent, so it is safe on every application start. `recreate(dataSource)` drops it all first and
is for tests and deliberate resets. Until the starter was written there was only the destructive
one, which is fine for a fixture and a loaded gun in a start-up hook.

**All DDL runs under the framework's bootstrap advisory lock**
(`pg_advisory_xact_lock(0xE55E_4711_B007_DD15)`, the same key `PostgresqlUtil` uses), inside an
explicit transaction. PostgreSQL's `IF NOT EXISTS` is *not* atomic against concurrent sessions: two
instances starting together can both read "absent" from `pg_class` and one fails with a
`pg_type_typname_nsp_index` violation. Sharing the framework's key rather than picking a private one
is what makes this safe alongside an event store bootstrapping at the same moment; a unit test
asserts the two constants have not drifted, since the module cannot import the canonical one.

### 2.4 The registry — names, ids and shard counts

```sql
CREATE SEQUENCE shard_queue_id_seq START WITH 1 INCREMENT BY 1 MAXVALUE 32767;

CREATE TABLE shard_queue_registry (
    queue_id    smallint    NOT NULL PRIMARY KEY,
    queue_name  text        NOT NULL UNIQUE,
    shard_count int         NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);
```

The engine addresses a queue by an interned `smallint`, because a two-byte column in the primary key
of every row is what keeps the hot index dense. That is a storage decision and it does not change.
What changed is where the mapping from a *name* to that id lives: it used to live in each caller,
which is a shared contract stored in several places at once.

`registerQueue(dataSource, QueueName.of("orders"), 8)` interns the name under the table's unique
constraint, so every process at start-up converges on one id instead of racing to pick different
ones. Id gaps are harmless — an id is an interning token, and a value burned by a losing
`ON CONFLICT` means nothing. The ceiling is 32 767 queues.

**`shard_count` is in this table, and that is the point.** A key's shard is
`hash(key) mod shardCount`, so the count is a property of the *queue*, not of the process reading it.
It used to be a constructor argument each process supplied for itself, and a disagreement was
silent: a producer believing in eight shards and a consumer believing in four write to shards
4–7 that nobody leases and nobody delivers. `ShardOwnedQueueRegistryIT` reproduces exactly that, so
the protection is measured against a real failure rather than asserted. Registering a name with a
different count is now refused, and a queue built from a name takes both the id and the count from
the registry — leaving a caller nowhere to express the disagreement.

### 2.5 Ownership and membership

**`shard_queue_lease`** — who owns what.

```sql
CREATE TABLE shard_queue_lease (
    queue_id    smallint    NOT NULL,
    lane        text        NOT NULL,
    shard       smallint    NOT NULL,
    owner       text,
    fence       bigint      NOT NULL DEFAULT 0,
    lease_until timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (queue_id, lane, shard)
);
```

`lane` is part of the key because the lanes are separate tables with separate owners. One lease row per shard shared by both lanes would let an unordered message land in a shard leased by the ordered consumer, which never reads that table.

Lease rows are seeded by `registerQueue`, so acquiring a shard is an `UPDATE` — the row's existence is never contended, only its ownership.

**`shard_queue_instance`** — membership.

```sql
CREATE TABLE shard_queue_instance (
    queue_id    smallint    NOT NULL,
    instance_id text        NOT NULL,
    last_seen   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (queue_id, instance_id)
);
```

Membership needs its own table because the lease table cannot answer "how many instances are there". An instance holding no shards — the one that most needs counting, since it is waiting for a share — leaves no trace in the lease table.

### 2.6 Reading a queue in psql

Payloads are `bytea` because the database never looks inside them, which is what keeps the write path
cheap — and what makes `SELECT payload` return `\x7b226f...` to an operator holding a support ticket.

Three views fix the ergonomics without touching the storage: `shard_queue_unordered_readable`,
`shard_queue_ordered_readable` and `shard_queue_dead_letter_readable`. Each joins the registry so the
queue's *name* is a column rather than an interned id, and renders the payload through
`shard_queue_readable(bytea)`, which returns text for valid UTF-8 — JSON, XML and plain text all
qualify — and falls back to hex otherwise rather than raising. A view that throws on one binary row
would be worse than one that shows it as hex.

The bytes stay bytes; the engine reads none of this.

### 2.7 Growing a queue's shard count

The two lanes are not equally pinned, and treating them as one is what made the count look immutable.

**Unordered messages are assigned round-robin**, so nothing about them depends on which shard they
landed in. Adding shards adds sequences and lease rows; the messages already stored stay where they
are and are still delivered by whoever owns those shards. There is nothing to re-route.

**Ordered messages used to be the constraint, and the mechanism was the modulus.** A key's shard was
`hash(key) mod shardCount`. Change the count and a key's *next* message hashed to a different shard
from its *last* — one key spread across two shards, two owners dispatching it concurrently. That is
reordering, not the duplicate at-least-once permits, and no care at the read path can undo it, because
per-key order here is enforced by one owner holding one unit.

`growShardCount` therefore refused while the ordered lane held anything. That refusal could not be
satisfied: nothing lets an operator quiesce producers, so the count was frozen for the life of the
queue and had to be guessed correctly the first time.

**The ordered lane now hashes into a fixed space of its own** — `ShardOwnedSchema.ORDERED_UNITS` — and
never reads `shardCount`. A key's unit does not move when the shard count changes, so
`ShardOwnedSchema.growShardCount(dataSource, name, n)` has no ordered-lane precondition and can be
called with ordered traffic in flight. Consumers own *units*; adding a consumer moves units, not keys.

**Shrinking is refused outright**, for a different reason: messages already sitting in the shards
being removed would be addressed by nobody. `shardForKey` and the round-robin cursor would both stop
producing those shard numbers, no consumer would lease them, and the rows would simply stay there.
There is no safe general answer to where they should go, so the supported route is to drain those
shards and recreate the queue.

**No restart is needed.** The count used to be fixed at construction, so growing a queue meant
redeploying every instance — the operationally expensive half of resharding, and an artefact of where
the number was stored rather than anything the design required. The heartbeat re-reads `shard_count`
from the registry every tick, and the acquire loop in `rebalance()` then takes the new shards on its
own, because it has always iterated to `shardCount`. Growth is picked up within one heartbeat
interval (`leaseTtl / 3`, ten seconds by default). Only ever upward: a registry reporting *fewer*
shards is ignored and logged, because dropping shards at runtime would strand whatever is in them.

**What remains is a two-moduli window of one heartbeat.** Instances pick the new count up
independently, so briefly some route by the old modulus and some by the new. For the unordered lane
that is harmless — routing is round-robin and every shard has an owner either way, so growth needs no
coordination at all. For the ordered lane it is the same hazard that requires an empty lane in the
first place, so producers must stay paused across the window. **Seconds, not a deployment.**

### When this applies, and when it does not

Worth stating plainly, because the reshard window is easy to mistake for a general operational
hazard. **It is not.** `shardCount` changes only when an operator calls `growShardCount`; every
instance otherwise reads the same registry row, and an instance constructed with a stale count
corrects itself from the registry within a heartbeat — on the producing side as well as the consuming
one.

So a **rolling redeploy**, a **scale up**, a **scale down** and a **crash** all leave the shard count
untouched and need no procedure at all. Shards move between instances constantly in normal operation;
that is rebalancing, and per-key ordering across it is asserted by `ShardOwnedOrderedRebalanceIT` and
`ShardOwnedMultiProcessIT`. A three-generation rolling deploy under ordered traffic is asserted to see
one shard count throughout.

Growing an **unordered** queue also needs nothing: routing is round-robin, every shard has an owner
either way, so `growShardCount` and carry on.

Growing an **ordered** queue is not a thing: the lane routes on a fixed unit space
(`ShardOwnedSchema.ORDERED_UNITS`) that `shardCount` does not reach, so there is nothing to grow and
`growShardCount` no longer has an ordered-lane precondition. It used to be the one case with a
procedure — quiesce producers, drain, grow, resume — which was not a procedure at all, because
nothing let an operator quiesce.

### How the constraint was removed — and what this section got wrong

**Built.** The ordered lane no longer has a shard count, and the option below that this section
dismissed as "a structural rework of the read path" is the one that shipped. Kept because its
objections were specific and worth reading against what actually resolved them:

- It priced a unit at "an owner object, a wake-up, a cursor, **two sequences** and ~0.1 queries/s",
  and rejected 256 units at 512 sequences per queue. Correct arithmetic, wrong premise. The ordered
  lane now draws from **one sequence per queue**, so units cost no sequences at all.
- It said making that affordable "conflicts with per-`(queue, shard)` sequences — and those exist
  because a shared sequence manufactured `queues - 1` holes per message". That was the true blocker,
  and it fell to a different change: the ordered lane stopped detecting holes by density and now asks
  the transaction horizon directly, so a shared sequence manufactures nothing.
- What remains open is the cost it correctly identified: idle queries per unit, still three statements
  per unit per sweep, still unbatched, and still **unmeasured** for the ordered lane at 64 units.

So the structural objection was real and was removed by a change nobody had in mind when this was
written. The three options as they stood:

**Doubling-only split.** Restrict growth to `N -> 2N`. Then `hash(K) mod 2N` is either `S` or `S+N`
and nothing else, so each old shard splits in exactly two — and if one owner holds *both* halves
during the transition it sees every message for every affected key. Per-key order is preserved by the
existing `keysInFlight` set, and ordering within a key still works because `readyByKey` sorts by the
producer-assigned `key_order` rather than by `seq`, so the halves having separate sequences does not
matter. Unpair once shard `S` holds no rows older than the growth: one cheap query per sweep. This
removes the drain entirely. The costs are that growth becomes doubling-only — 8→16→32, never 8→12 —
and that it needs a transition mode, which is the same class of change (ordering across a topology
change) that took three attempts to test honestly for the ordered shed. Worth building if a real
deployment finds the pause blocking; not before.

**Virtual shards / consistent hashing.** The textbook answer: fix the partition space large, map
virtual shards onto physical owners, and growth becomes pure rebalancing — which already exists. It
fits this engine badly. A shard here is not a cheap bucket: it is an owner object, a wake-up, a
cursor, two sequences and ~0.1 queries/s. At 256 virtual shards across two lanes that is roughly
25 queries/s idle *per queue*, plus 512 lease rows and 512 sequences. Making it affordable would mean
one owner serving a *range* of virtual shards under a single cursor, which conflicts with per-`(queue,
shard)` sequences — and those exist because a shared sequence manufactured `queues - 1` holes per
message. It is a structural rework of the read path, not a feature.

**Size it correctly instead.** The option taken at the time: over-provision, since an idle shard costs
~0.1 queries/s and `shardCount` caps how many instances can consume. It was the wrong answer, and
worth saying why — it made the guess cheaper to get right rather than removing it, and a user who
guessed wrong still had no way back.

### 2.8 Sequences

One per `(queue, shard)` for the **unordered** lane (`shard_queue_seq_q<queue>_s<shard>`) and one per queue for the **ordered** lane (`shard_queue_ordered_seq_q<queue>`), both `CACHE 1` so an allocated value is one that will be committed. Plus one global `shard_queue_session_fence`, from which pull sessions draw **negative** fences so they can never collide with an owner fence in the shared `lease` column.

The per-`(queue, shard)` scoping is load-bearing **for the unordered lane**. Its cursor treats any sequence value it steps over as a hole to be chased, so a counter shared between queues would manufacture `queues − 1` holes per message. A dense sequence per queue is what makes "a gap means an uncommitted transaction" a true statement there.

The ordered lane does not need it, which is why it has one sequence per queue rather than 64. It no longer infers anything from density: its cursor is a safe watermark that advances only once the transaction horizon says no running writer could still commit a lower value, so sparse sequence values within a unit mean nothing to it. `CACHE 1` matters more there, not less — the watermark's safety argument rests on values being handed out in wall-clock order.

---

## 3. Process topology

Threads and connections are properties of the **process**, not of the shard count or the queue count. One `ShardRuntime` holds everything long-lived and is shared by every queue in the process.

```mermaid
flowchart TB
    subgraph JVM["One JVM process"]
        subgraph RT["ShardRuntime — shared by every queue"]
            P0["ShardPump 0<br/>platform thread<br/>1 held connection"]
            P1["ShardPump 1<br/>platform thread<br/>1 held connection"]
            LSN["ShardWakeupListener<br/>1 thread, 1 dedicated connection<br/>LISTEN shard_queue_wakeup"]
            HB["heartbeat<br/>1 scheduler thread"]
            HX["handler executor<br/>virtual thread per message<br/>bounded per consumer"]
        end

        subgraph OWN["Shard owners — plain objects, no threads of their own"]
            O1["ShardOwner<br/>q1 / unordered / shard 0<br/>cursor, holes, inFlight, pendingAcks"]
            O2["ShardOwner<br/>q1 / unordered / shard 1"]
            O3["OrderedShardOwner<br/>q1 / ordered / shard 0<br/>readyByKey, keysInFlight"]
        end

        Q1["ShardOwnedQueue q1"]
        Q2["ShardOwnedQueue q2"]
    end

    DB[("PostgreSQL<br/>shard_queue_unordered · shard_queue_ordered · shard_queue_dead_letter<br/>shard_queue_lease · shard_queue_instance")]

    Q1 -.->|configure + start| O1
    Q1 -.-> O2
    Q1 -.-> O3
    Q2 -.->|its own owners, the same runtime| RT

    P0 -->|pumpOnce| O1
    P0 --> O3
    P1 -->|pumpOnce| O2

    O1 -->|dispatch| HX
    O2 --> HX
    O3 --> HX

    P0 <--> DB
    P1 <--> DB
    LSN <-->|NOTIFY queueId:lane:shard| DB
    HB -->|renew leases, rebalance| DB
    HX -.->|handler's own work| DB

    LSN -->|signal| P0
    LSN --> P1
```

The two axes are deliberately separate mechanisms:

**Database contact** runs on `ShardPump` — a **platform** thread holding one connection and serving many shards. The count comes from `ShardOwnerSettings.pumpThreads` (default 2), never from `shardCount`. Virtual threads are the wrong tool here: a virtual thread blocked in JDBC still holds a connection, so putting database work on them would raise the connection count rather than lower it.

**Handler concurrency** runs on one `newVirtualThreadPerTaskExecutor` per process, bounded per consumer by `ConsumerOptions.parallelConsumers`. Handlers are user code that mostly waits, which is what virtual threads are for. A message in flight costs a virtual thread and a permit, not a platform thread.

The resulting cost, regardless of how many queues or shards exist:

```
held connections = pumpThreads + 1        (the pumps, plus the listener)
threads, idle    = pumpThreads + 2        (the pumps, the listener, the heartbeat)
threads, loaded  = the above + handlers currently in flight (virtual)
```

Measured: 5 connections and 21 threads at 300 queues of 8 shards. A `ShardOwnedQueue` constructed without an explicit runtime borrows the one shared per `DataSource`, reference counted, closed by its last borrower.

### 3.1 How a pump decides what to do

Each shard has its own `ShardWakeup`, which cascades to the wake-up of the pump serving it. The pump parks on its own wake-up and then asks each of its shards `needsAttention()`, so a notification for one shard does not make the pump read all of them.

```java
while (running) {
    for (var owner : owners) {
        if (firstTimeSeen(owner))  owner.onTakeover(connection);  // bulk attempt bump
        if (!owner.leaseHeld())    continue;
        if (!owner.needsAttention()) continue;
        delivered += owner.pumpOnce(connection);   // RuntimeException caught per owner
    }
    if (delivered == 0) park();
}
```

`needsAttention()` is true when a wake-up has been signalled, when a local hand-off is waiting, when the sweep is due, when a retry has fallen due, when a hole is ready to chase, or when acknowledgements are due to flush. The park duration is the shortest deadline any of the pump's shards has, capped by `max(pollBackstop, maxSweepInterval)`.

Two rules the pump enforces that are easy to get wrong:

- **`onTakeover` runs before an owner's first `pumpOnce`, not before the pump's.** Pumps start before any shard is leased, and rebalancing adds shards later. The takeover attempt bump is the only thing standing between a handler that kills the JVM and an infinite redelivery loop, so it must not run after the owner has already delivered.
- **The pump catches `RuntimeException` per owner.** One thread serves several shards; an unchecked exception escaping one owner used to end the thread and silently stall every shard beside it, while the heartbeat went on renewing their leases.

On `SQLException` the pump reconnects rather than exiting, paced at 200 ms. The retrying is bounded by the lease: if the database is genuinely gone the heartbeat cannot renew either, every owner is marked lost, and the loop exits. A pump that dies is worse than a crash, because a crash gets noticed and restarted while a queue that quietly stops delivering does not.

---

## 4. Enqueue

Enqueue is a single multi-row `INSERT` into one shard's lane. Unordered messages are assigned round-robin from a per-queue cursor; ordered messages go to `Math.floorMod(key.hashCode(), shardCount)`. `String.hashCode` is specified by the JDK, so the key-to-shard mapping is stable across JVMs and restarts.

**Every enqueue path runs its batch in an explicit transaction.** This cannot be left to PostgreSQL's implicit transaction: pgjdbc sends a small batch as a single sync but splits a large one into synced chunks, so a ten-row batch is atomic by accident and a five-thousand-row batch is not. A caller who has already begun a transaction is left alone — their commit decides, which is the point of enqueueing transactionally.

Inside the same transaction, `pg_notify` is issued once per batch per shard with the payload `queueId:lane:shard`. Because it is inside the transaction, the hint is delivered only if the enqueue commits and is discarded if it does not.

If the enqueuing JVM also owns the target shard, the insert additionally stamps `lease` with the owner's fence — a **pre-claim** — and the row is handed to the owner in memory after the transaction commits. See §6.

---

## 5. Delivery

### 5.1 The four paths a message can arrive by

```mermaid
flowchart TD
    E["enqueue commits"] --> N{"does this JVM<br/>own the shard?"}
    N -->|yes| H["local hand-off queue<br/>(Tier 2)"]
    N -->|no| NOT["pg_notify<br/>queueId:lane:shard"]

    NOT --> LSN["listener signals<br/>the shard's wakeup"]
    LSN --> PUMP
    H --> PUMP["pump wakes,<br/>owner.pumpOnce()"]
    BACK["backstop park expires"] --> PUMP

    PUMP --> P1["1 · drain local hand-offs<br/>no read at all"]
    P1 -->|nothing handed off| P2["2 · cursor read<br/>seq &gt; cursor AND visible_at &lt;= now()<br/>ORDER BY seq LIMIT readBatchSize"]
    P2 --> P3["3 · chase holes<br/>seq = ANY(missing)<br/>after chaseDelay"]
    P3 --> P4["4 · head sweep<br/>from the head, ignoring the cursor<br/>every sweepInterval, backing off to maxSweepInterval"]

    P1 --> D
    P2 --> D
    P3 --> D
    P4 --> D

    D{"deliver"} -->|"seq already in<br/>inFlight or pendingAcks"| DUP["drop, release permit"]
    D -->|new| ACQ["take dispatch permit,<br/>add to inFlight,<br/>cancel any retry entry for this seq"]
    ACQ --> VT["run handler on a virtual thread"]
    VT -->|returned, not interrupted| OK["move seq to pendingAcks"]
    VT -->|threw| FAIL["onFailure: retry or dead-letter"]
    VT -->|interrupted| ABANDON["abandon — NOT acknowledged"]

    OK --> FLUSH["flushAcks:<br/>range delete + stragglers,<br/>fenced"]
```

Paths 1 and 2 are the fast path. Path 3 costs one cheap point lookup and only exists while a hole is outstanding. Path 4 is pure insurance and is what makes correctness independent of the in-memory state being perfect.

An important property of this arrangement: **losing every notification costs latency and nothing else.** The backstop park and the head sweep still deliver. That is what makes it safe to coalesce notifications aggressively.

### 5.2 The cursor read

```sql
SELECT seq, payload FROM shard_queue_unordered
 WHERE queue_id = ? AND shard = ? AND seq > ? AND visible_at <= now()
   AND (lease IS NULL OR lease <> ?)              -- skip this owner's own pre-claims
   AND (lease_until IS NULL OR lease_until <= now())  -- skip rows a pull session holds
 ORDER BY seq
 LIMIT ?;
```

An index range scan in physical order. No lease join, no anti-join, no filtering of rows belonging to another consumer, and no dead tuples to rescan because acknowledged rows are gone rather than flagged.

The two `lease` predicates carry the whole of the row-level state:

| `lease` | `lease_until` | Meaning | Owner's read |
|---|---|---|---|
| NULL | NULL | ordinary row | delivers it |
| this owner's fence | NULL | pre-claim, already handed off in memory | skips it |
| *another* fence | NULL | pre-claim of a **dead** owner | delivers it — the stamp is self-expiring |
| negative fence | future | a pull session holds it | skips it until the lease lapses |

**Take the dispatch permit before the cursor advances past a row, never after.** A row that cannot be dispatched must still be there for the next read; advancing past it would leave it to the head sweep and register the gap as a hole that no transaction ever owned. Every delivering path — cursor, hand-off, chase, sweep, retry — acquires first and releases on a duplicate.

### 5.3 Holes: why the cursor is safe

A cursor over `seq` has a well-known hazard. Producer A takes `seq = 100`, producer B takes `seq = 101`, B commits first. A consumer reading `seq > cursor` sees 101, advances, and then A commits — 100 is now permanently behind the cursor.

Because a shard has exactly one reader, hole tracking is cheap and entirely in memory. The owner:

- **does not stall the cursor.** It records every value it stepped over in `pendingHoles` and keeps delivering everything it can see.
- **chases** with a targeted `WHERE seq = ANY(?)` after `chaseDelay` (default 2 ms), which resolves as soon as the late transaction commits. Measured hole resolution latency tracks `chaseDelay` almost exactly — the cost of a hole is set by how often the owner bothers to look, not by the database.
- **abandons** a value that never appears after `holeExpiry` (default 10 s), on the assumption its transaction aborted. This must exceed the longest expected enqueue transaction, or live messages get written off.

A hole costs a few milliseconds of extra latency for the delayed message only, and one cheap point-lookup query. Nothing else is affected, and the common path stays write-free.

### 5.4 Acknowledgement, and the floor that makes it safe

Handled sequence values accumulate in `pendingAcks` and flush when `ackBatchSize` (200) is reached or `ackFlushInterval` (1 ms) elapses. Because the owner reads in sequence order, what it has handled is usually a contiguous prefix, so the flush collapses to a range delete over physically adjacent rows plus a small remainder:

```sql
-- contiguous prefix
DELETE FROM shard_queue_unordered u WHERE u.queue_id = ? AND u.shard = ? AND u.seq <= ?
  AND (u.lease_until IS NULL OR u.lease_until <= now())
  AND EXISTS (SELECT 1 FROM shard_queue_lease l
               WHERE l.queue_id = u.queue_id AND l.lane = 'unordered' AND l.shard = u.shard
                 AND l.owner = ? AND l.fence = ? AND l.lease_until > now());

-- stragglers
DELETE FROM shard_queue_unordered u WHERE u.queue_id = ? AND u.shard = ? AND u.seq = ANY(?)
  AND EXISTS ( ...the same fence assertion... );
```

**The range delete is bounded below by a floor, and getting this wrong loses messages.**

```
safeFloor         = min(lowest in-flight seq, lowest pending hole seq)
contiguousThrough = highest contiguous acked seq strictly below safeFloor
```

Nothing at or above `safeFloor` may be range-deleted, however contiguous the prefix looks. A hole at sequence value *N* is a row that has not committed yet; once it commits it sits **below** the acknowledged prefix, and an unbounded range delete would remove it without it ever having been delivered.

**The straggler delete is deliberately not bounded by the floor.** The floor exists to stop a *range* from sweeping up rows that were never delivered. A targeted delete addresses exactly the sequence values this owner handled, so it can remove nothing it did not deliver, and applying the floor to it merely makes everything finished behind a slow handler wait for that handler.

Both statements carry the fence they were issued under and assert, in the same statement, that the lease still matches and has not expired — one extra index lookup on the lease table's primary key, per batch rather than per message.

**Zero rows deleted is ambiguous.** It means either that the fence clause rejected this owner or that the rows had already gone. Only the first means the shard has moved, so the owner asks `stillOwns` directly — one primary-key lookup, only on the ambiguous path — rather than inferring. Reading the second case as the first costs a lease the owner still holds: it stops, the shard is re-acquired, and everything in flight is redelivered.

### 5.5 Delivery is inline; handlers are not

`deliver()` runs on the pump thread and does three things under `stateLock`: deduplicate against `inFlight` and `pendingAcks`, add to `inFlight`, and cancel any retry-schedule entry for the same sequence value. It then hands the message to the virtual-thread executor.

Everything the pump thread and the handler threads both touch — `cursor`, `pendingHoles`, `inFlight`, `pendingAcks`, `retrySchedule` — is guarded by `stateLock`.

The cost of inline dispatch decisions is that a slow handler occupies its pump and therefore the other shards that pump serves. That is bounded and tunable through `pumpThreads`. It is the deliberate choice: making the *unordered* delivery decision asynchronous makes the cursor, the hole map, the in-flight set and the acknowledgement floor concurrent, and those four are what correctness rests on.

**An interrupted handler is not a success.** A handler that catches `InterruptedException` and returns normally — which is what well-behaved code does on shutdown — is otherwise indistinguishable from one that finished, and its message would be acknowledged without having been processed. The owner checks `Thread.currentThread().isInterrupted()` after the handler returns and counts an abandonment instead of an acknowledgement.

---

## 6. Wake-up

Three tiers. Each is faster than the one below it, each is optional, and **correctness never depends on any of them.**

**Tier 0 — the backstop park.** A pump parks until its shortest owner deadline, capped by `max(pollBackstop, maxSweepInterval)`. Combined with the head sweep this is the correctness floor. The sweep interval backs off only when a sweep actually *ran* — a pump iterates whenever any shard it serves has work, so backing off per iteration drove an idle shard to the thirty-second ceiling within milliseconds and quietly made both delayed delivery and lost-notification recovery thirty-second operations.

**Tier 1 — `LISTEN`/`NOTIFY`.** One global channel, `shard_queue_wakeup`, with the payload `queueId:lane:shard`. A single `ShardWakeupListener` on a dedicated connection demultiplexes to the right shard of the right lane of the right queue and signals its wake-up, which cascades to the pump's.

Three rules matter, and all three cost something real when broken:

- **The notify is issued by the application inside the enqueue transaction, coalesced to at most one per shard per batch.** A per-row trigger would turn every message into a notify, and PostgreSQL serializes notify-queue access cluster-wide.
- **The listener's connection never does work.** A busy listener delays every notification queued behind it.
- **The listener reconnects *and* re-runs `LISTEN`.** The subscription is per connection. Its failure is silent by construction — owners fall back to the backstop, so delivery continues and only latency degrades — which means no delivery-based test can detect it. Assert on notification counts.

**Tier 2 — local hand-off.** When the enqueuing JVM also owns the target shard, the message never needs to be read back. The insert stamps `lease` with the owner's current fence, and a post-commit hook hands the already-in-memory row straight to the owner's `localHandoffs` queue. The owner's cursor read excludes rows carrying its own fence, so the message is not read twice; a new owner takes the shard under a *new* fence, so rows pre-claimed by a dead owner become visible again automatically.

The hand-off drain runs first in `pumpOnce` and, if it delivered anything, returns without issuing a cursor read at all — which is the entire point of the tier.

**The head sweep deliberately does not skip pre-claims outright.** If a hand-off is lost — the process dies between commit and dispatch — the sweep is what still delivers the message. But a pre-claim committed moments ago is far more likely to be in flight than lost, so the sweep skips this owner's own pre-claims until they are older than a grace period.

**Tier 3 — WAL streaming.** Not built. Its gate was that it must clearly beat Tier 2 by enough to justify a replication slot's operational weight, and Tier 2 measures 0.44 ms at the median.

---

## 7. Ordering

`OrderedShardOwner` owns one shard of `shard_queue_ordered`. Its read is the same plain forward scan on `seq` (via `shard_queue_ordered_seq`); ordering is enforced entirely by two in-memory facts — a key's messages can only be in one shard, and one owner has all of that shard.

State it keeps per shard:

| Field | Purpose |
|---|---|
| `readyByKey` | `Map<key, TreeMap<key_order, row>>` — everything known but not yet delivered, ordered within each key |
| `keysInFlight` | keys with a message currently in a handler. **At most one per key — this is the FIFO guarantee** |
| `keysAwaitingRetry` | keys blocked by a failing message, so a later message cannot overtake it |
| `highestDeliveredOrder` | per key, used to count ordering violations |
| `activeKeys` | bounded by `keyConcurrency` (default 8), per shard |

Concurrency is per key, not per shard: many keys progress at once, and a key with a slow or stuck handler blocks only itself. That is the correct scope — head-of-line blocking should apply to the thing that actually demands ordering.

**On strictness.** A key advances through the `key_order` values that are present, one at a time. It does *not* wait for a missing `key_order` to arrive, because those values are producer-assigned and a gap may never be filled — waiting would stall the key forever on a producer's bookkeeping error. The consequence is counted rather than claimed away: a message that commits after a higher `key_order` for its key has already been delivered is an ordering violation, and `ShardOwnerMetrics.orderViolations` records it. Measured zero across every run so far.

The ordered lane acknowledges by sequence value rather than by range, batched with `ANY`. Keys progress independently, so what has been handled is rarely a contiguous run of `seq`, and inventing a second range-delete floor for it would reproduce §5.4's hazard for no gain.

---

## 8. Ownership, leases and fencing

### 8.1 Acquiring

```sql
UPDATE shard_queue_lease
   SET owner = ?,
       fence = CASE WHEN owner = ? THEN fence ELSE fence + 1 END,
       lease_until = now() + make_interval(secs => ? / 1000.0)
 WHERE queue_id = ? AND lane = ? AND shard = ?
   AND (lease_until < now() OR owner = ? OR owner IS NULL)
RETURNING fence;
```

The fence increments on a **change** of owner and not on a renewal. An instance takes at most `ceil(shardCount / liveInstances)` shards, which makes the assignment self-balancing with no coordinator: every instance derives the same fair share from the same `shard_queue_instance` table, so the split converges with nobody deciding it.

### 8.2 Lease lifecycle

```mermaid
stateDiagram-v2
    [*] --> Unowned: registerQueue seeds the row
    Unowned --> Owned: acquireLease, fence + 1

    Owned --> Owned: heartbeat renews at leaseTtl / 3, fence unchanged

    Owned --> Lost: renewal refused, or granted under a NEW fence
    Owned --> Lost: ack deleted 0 rows AND stillOwns says no
    Lost --> [*]: owner stops dispatching

    Owned --> Shedding: over fair share, ordered lane only
    Shedding --> Shedding: in-flight keys still draining
    Shedding --> Released: drained, acks flushed under the still-valid fence
    Shedding --> Owned: shedGrace expired, shed abandoned and counted
    Released --> Unowned: releaseLease expires it, fence deliberately NOT bumped

    Owned --> Expired: process died, lease_until passes
    Expired --> Owned: another instance acquires, fence + 1, bulk attempts bump
```

The lease TTL is `ShardOwnerSettings.leaseTtl`, 30 seconds by default. It is its own setting rather than being derived from `holeExpiry`: the two are bounded by unrelated things — `holeExpiry` by the longest enqueue transaction, the lease TTL by the longest tolerable stop-the-world pause and by how long a failover may take — so deriving one from the other meant raising `holeExpiry` for a long outbox transaction silently tripled failover time. The heartbeat renews at a third of it, so a single missed renewal costs nothing. A renewal that is refused, or granted under a *new* fence (meaning the shard was taken and handed back), stops the owner at once rather than letting it discover the problem at acknowledgement time, by which point it may already have dispatched work its successor is also dispatching.

`ShardRuntime` is a restartable `Lifecycle` like everything above it — its executors are rebuilt by `start()`, because none of them can be revived once shut down. `stop()` releases every lease this instance holds, after the pumps have flushed their outstanding acknowledgements, so a rolling restart hands the shards over immediately instead of leaving them unserved for the remainder of the TTL.

**Releasing a lease deliberately does not bump the fence.** The next acquirer does. Bumping on release would invalidate the releasing owner's own in-flight acknowledgements before it has finished draining them.

### 8.3 Fencing

Every acknowledgement carries the fence it was issued under and asserts it in the same statement (§5.4). A superseded owner's delete affects zero rows, and the messages stay for whoever now owns the shard. This is the dangerous write precisely because it deletes: a stale owner acknowledging its in-flight work would remove exactly the messages the new owner is about to deliver.

The residual risk is inherent to every lease-based system, Kafka included: a stale owner may already have dispatched a message to a handler before it notices it lost the lease. It is bounded by the lease TTL. **Handlers must be idempotent.**

**All durable time is `now()`, evaluated by the server.** Message visibility, retry backoff, lease expiry and instance liveness are written and compared server-side; no client timestamp is ever persisted. That is what makes clock skew between nodes a non-issue rather than a bounded risk. Client clocks are used only for local scheduling — the retry schedule, hole chasing, wake-up timeouts — none of which crosses a node boundary. `ServerSideTimeTest` guards this structurally.

### 8.4 Rebalancing

The heartbeat recomputes the fair share every tick. The two lanes shed differently, and they have to.

**The unordered lane sheds by dropping the lease.** Whatever was in a handler gets redelivered by the new owner, and at-least-once permits that.

**The ordered lane sheds by draining.** Dropping the lease mid-key would hand the successor a key the outgoing owner is still running — two messages of one key in flight at once, which is *reordering*, not the duplicate the contract allows. So `beginShedding()` stops the owner dispatching new keys, lets the keys already in handlers finish, flushes their acknowledgements under the fence it still holds, and only then releases.

Two consequences, both deliberate: ordered rebalancing converges more slowly, and a shed that cannot finish inside `shedGrace` (5 s) is **abandoned** rather than forced. The shard stays where it is and `shedsAbandoned` counts it. Unbalanced beats reordered. The shed is retried on the following tick.

### 8.5 Takeover

A handler that crashes the JVM never gets to increment `attempts`, so a poison message could loop forever. On its first `pumpOnce` under a new connection, every owner issues one bulk statement over its shard:

```sql
UPDATE shard_queue_unordered SET attempts = attempts + 1 WHERE queue_id = ? AND shard = ?;
```

One statement over the small unacked set. Poison messages therefore reach the dead-letter lane even under repeated crashes, and it is the only reason the steady-state path can get away with never writing an attempt count.

---

## 9. Failure, retry and dead letters

Failure is rare, so it is allowed to be expensive.

```mermaid
sequenceDiagram
    participant H as handler (virtual thread)
    participant O as ShardOwner
    participant DB as PostgreSQL

    H->>O: throws RuntimeException
    Note over O: still inside the in-flight slot —<br/>releasing it first lets the pump<br/>re-read and burn a second attempt
    O->>O: attempts = attemptsBySeq.merge(seq, 1, sum)
    O->>O: observer.deliveryFailed(key, attempts, cause)

    alt policy exhausted
        O->>DB: INSERT INTO shard_queue_dead_letter ... ; DELETE FROM lane   (one transaction)
        O->>O: forget seq — nothing may ack it again
        O->>O: observer.deadLettered(id, attempts, cause)
    else retry
        O->>DB: UPDATE lane SET attempts = ?, visible_at = now() + backoff<br/>(HOT update, no index touched)
        O->>O: retrySchedule.add(nanoTime + backoff, row)
        O->>O: observer.retryScheduled(key, attempts, delayMillis)
        Note over O,DB: re-dispatched from memory —<br/>the row is never read back
    end
```

The database row is durability backup, not the retry mechanism: if the owner crashes, the new owner finds the row through the head sweep and honours `visible_at`. Retries therefore cost the read path nothing.

**A retry lives in two places timed by two different clocks** — the table's `visible_at` by the database's `now()`, and the in-memory schedule by `System.nanoTime()`. Either can fire first. `deliver()` drops any pending schedule entry for a sequence value it accepts, so whichever path gets there first invalidates the other. Without that, the sweep delivers and deletes the row and the stale schedule entry then delivers it a second time.

Retry and dead-letter events are emitted **from the owners**, never from the SPI's delivery wrapper, because the owner is the only place that knows which attempt this was.

---

## 10. Pull sessions

For a caller that wants to pull rather than be called: a caller controlling its own transaction boundary, a caller driving its own loop, or a handler that outlives any queue-wide timeout. `MessageQueue.openSession(scope, leaseDuration)` returns a `QueueSession` with `poll`, `acknowledge`, `fail`, `extendLease` and `close`.

| Scope | Mechanism | Per-message write | Lane |
|---|---|---|---|
| `SHARD` | takes shard leases, exactly as the engine's own consumers do | **none** | both |
| `MESSAGE` | one row lease at a time | one write per message | unordered only |
| `BATCH` | *n* row leases in one statement | one statement per batch | unordered only |
| `KEY` | — | — | **refused** |

`SHARD` is the recommended path and the only one costing no per-message write: ownership is already recorded in the lease table, so individual messages need no claim. Polling is the same cursor read the engine's own consumers use. Holding a shard excludes consumers from it for the session's life; closing hands the shards straight back rather than making the next consumer wait out a lease.

`MESSAGE` and `BATCH` claim rows with `SKIP LOCKED` — the one place in this engine where that construct earns its keep, because here there genuinely are competing writers. Several sessions can pull from one shard at once and a push consumer keeps running beside them. The claim writes a **negative** session fence into `lease` and a real expiry into `lease_until`; both the owner's reads and the range-delete acknowledgement skip rows carrying a live `lease_until`.

Two limits, both stated rather than hidden:

- **A row lease cannot retract a row the owner already read into memory.** The owner's read is a snapshot, and re-checking at dispatch would put a query on the fast path. So a message can reach both a push consumer and a session. That is a duplicate, which the unordered lane's at-least-once contract permits — and it is exactly why these scopes are not offered on the ordered lane, where the same overlap would be reordering.
- **`KEY` scope cannot exist in this engine.** Per-key exclusivity lives in the owner's in-memory in-flight set; a session in another process cannot enter it. For it to take one key safely the owner would have to re-check the database before dispatching each key — a query per message on the ordered fast path, which is precisely the cost ordering-by-ownership exists to avoid. On the ordered lane the unit of exclusivity **is** the shard, so `SHARD` is the answer rather than a fallback. `openSession` refuses `KEY` with that reason.

---

## 11. Interceptors

`MessageQueueInterceptor` sits *in the call path* and may change what an operation does, which is the
whole difference from a `QueueObserver`:

| | `QueueObserver` | `MessageQueueInterceptor` |
|---|---|---|
| Position | told what happened | in the call path |
| Can change the outcome | no | yes — modify, or skip by not proceeding |
| If it throws | its own bug; the message is unaffected | the operation fails |
| Cost when none registered | an empty loop | nothing — the chain is not built |

Metrics, tracing and logging are observers. Enrichment, filtering, multi-tenancy and kill switches are
interceptors.

**Two operations, not fifteen.** `DurableQueues`' interceptor has a method per interface member,
which follows from an interface where any caller may act on any message. Here only two operations
carry a message and can therefore be meaningfully changed: `EnqueueMessages` (before anything is
written; the batch is replaceable) and `HandleMessage` (before the handler runs). Intercepting
`depth`, `purge`, `deadLetters` or `resurrect` would be surface without capability — an observer
already sees them and nothing useful can be substituted for their results. Omitted deliberately, and
addable if a use appears.

**Zero cost when empty.** `enqueue` and `invoke` both check `interceptors.isEmpty()` and call the
default behaviour directly — no operation object, no chain, no lambda. That matters because the
engine's entire argument is about what a message costs, and delivery interception runs once per
message. Ordering is by `@InterceptorOrder`, sorted once at registration rather than per call, since
the sort reads annotations reflectively.

**Not proceeding on `HandleMessage` skips the handler and the message is acknowledged.** That is a
capability (a poison filter, a kill switch) and a footgun — afterwards a dropped message and a
processed one are indistinguishable — so it is stated rather than left to be discovered.

## 12. Observability

`QueueObserver` is the consumer-facing SPI: `enqueued`, `aroundDelivery`, `delivered`, `deliveryFailed`, `retryScheduled`, `deadLettered`, `shardOwnershipChanged`. `ShardOwnerMetrics` carries both the engine's own counters and the consumer's observer, so one object threads both audiences through the owners' constructors.

`MicrometerQueueObserver` binds `QueueObserver` to a `MeterRegistry`. `micrometer-core` is `provided`, per the project rule that third-party integrations are not transitive.

Three deliberate shapes in that binding:

- **Every meter is registered eagerly**, including the failure counters. A counter that materialises on first use does not exist as a series until the incident has already started, which is exactly when an alert needed it to exist.
- **No tag carries the ordering key.** Keys are unbounded — one per customer, per order, per aggregate — and a tag value per key is the standard way to take a metrics backend down. The key reaches `aroundDelivery`, where it belongs.
- **Depth is opt-in and cached** (`bindQueueDepth`). Every other meter is fed by an event the engine already emits, so it costs an increment. Depth is a query, and Micrometer polls a gauge on every scrape: an unguarded depth gauge would put `2 × shardCount + 1` queries on the database per scrape per process.

**`MessageQueue.health()`** answers the question depth cannot: *is anybody serving this queue?* It
reports the shard count, how many shards of each lane hold a live lease, and how many instances are
heartbeating — two cheap queries against the lease and membership tables. `unownedShards()` is the
number to alert on: zero in steady state, briefly non-zero while shards move, persistently non-zero
when messages are sitting in shards nobody reads.

That distinction is not academic. Every ownership failure this engine has had — a fair share computed
against instances that had departed, two processes disagreeing about the shard count, a consumer
shedding shards to nobody — was invisible in every metric that existed at the time, and surfaced only
as a backlog with no attributable cause. `bindQueueHealth` publishes it as
`essentials.queue.shards.unowned`, `…shards.owned` and `…instances`.

Two things about gauges here that were learned the hard way:

- **Instance counting has two meanings and needs two methods.** `countLiveInstances` is floored at one
  so `fairShare`'s division cannot divide by zero; using it for a health report makes "nobody is
  consuming" indistinguishable from "one instance", which is precisely the state being reported on.
  `countInstances` is the unfloored truth.
- **Every gauge uses Micrometer's `Supplier` form.** The state-object form holds a *weak* reference, so
  a cache created inside a bind method is collected once nothing else refers to it and the gauge
  reports `NaN` from then on — silently, at an arbitrary later moment. The depth gauge shipped with
  that bug and looked correct, because a test asserting straight after binding runs before any
  collection. There is now a test that forces one first.

The engine's own counters — `cursorReads`, `holesObserved`/`Resolved`/`Abandoned`, `sweepRecoveries`, `backstopPolls`, `wakeupsHonoured`, `localHandoffs`, `fencedOutAcks`, `orderViolations`, `shedsAbandoned`, `takeoverAttemptBumps` — are for debugging the engine, and several of them are what the cost integration tests gate on.

---

## 13. Administrative API

The engine's `MessageQueue` is what an application calls: bound to one queue, trusting its caller,
throwing `SQLException` because the caller is usually inside a transaction that has to decide what a
failure means. `ShardOwnedQueuesApi` is the operator's surface over the same operations —
registry-scoped, authorised per principal, unchecked, and with payloads withheld by default.

```mermaid
flowchart LR
    HTTP["HTTP client"] --> C["ShardOwnedQueuesController<br/>(admin API base path)"]
    C -->|"principal"| A["ShardOwnedQueuesApi<br/>authorise · resolve · convert"]
    A -->|"queueNames / findQueue"| R["MessageQueues<br/>(ShardOwnedQueueFactory)"]
    R -->|"registry lookup"| DB[("shard_queue_registry")]
    A -->|"delegate"| Q["MessageQueue<br/>getMessage · delete · retry · …"]
    Q --> DB2[("lanes · dead letters · leases")]
```

### 13.1 Every operation takes a queue name

`DurableQueuesApi` addresses a message by `QueueEntryId` alone, because that id is a UUID and globally
unique. A `MessageId` is `(lane, shard, sequence)` and sequences are per `(queue, shard)` — so `u-0-1`
exists in every queue that has ever enqueued an unordered message. Dropping the queue name would not
make the API more convenient; it would make `deleteMessage` delete an arbitrary queue's message. There
is deliberately no `getQueueNameFor(messageId)` counterpart, because the question has no answer.

### 13.2 The id has a text form

`MessageId.toString()` renders `<lane>-<shard>-<sequence>` — `u-3-1042`, `o-0-7` — and
`MessageId.parse` reads it back. The lane is one character so the id fits a URL path segment without
escaping, and it is the same triple the tables use: the lane picks the table, the other two are the
rest of the primary key, so an id from an HTTP response can be typed straight into `psql`.

By-id lookup itself is not new — `MessageQueue.getMessage(MessageId)` predates this. What was missing
was any way to *name* a message outside Java.

### 13.3 Roles

| Operation | Role (or `ESSENTIALS_ADMIN`) |
|---|---|
| `getQueueNames`, `getQueueStatus`, `getMessage`, `getDeadLetterMessages` | `QUEUE_READER` |
| message payloads within those responses | `QUEUE_PAYLOAD_READER`, **additionally** |
| `deleteMessage`, `retryMessage`, `markAsDeadLetterMessage`, `resurrectDeadLetterMessage`, `purgeQueue` | `QUEUE_WRITER` |

A reader without the payload role still gets the message — id, key, attempts, timestamps, last error —
with `payload` null. Null, not empty: an empty payload is a legal message and has to stay
distinguishable from one being withheld. That is the shape most administration needs, and it keeps
message contents behind a role grantable separately from the ability to see that a queue is stuck.

A payload that is not valid UTF-8 comes back as hex, the same rendering the `shard_queue_*_readable`
views apply (§2.6). Lossy decoding would turn a protobuf payload into U+FFFD noise that still looks
like text.

### 13.4 Status is depth *and* ownership, in one response

`getQueueStatus` returns both. They answer half a question each: a depth of 40 000 is normal under load
and an outage when `unownedShards` is non-zero; `unownedShards` of 4 is a rebalance in progress when
depth is falling and a stall when it is not. Two endpoints would let a dashboard show one without the
other, which is the failure mode §12 exists to close.

### 13.5 A write races an in-flight delivery, and that cannot be fixed

Whether a message is being delivered right now lives in the owning consumer's memory, not in a column.
`deleteMessage` on a message a handler is presently running succeeds, and the handler still finishes.
The engine already tolerates the acknowledgement that follows — it resolves to zero rows, and
`stillOwns` disambiguates it — so nothing is corrupted. What is not guaranteed is that the handler did
not run. These are interventions on a running system, not transactional edits.

### 13.6 Where the HTTP layer lives, and why it is not in the admin API starter

The project convention is that an admin operation lives in three synced places: the `*Api` SPI, the
`EssentialsAdminApiSpec` mapping table, and a controller in `spring-boot-starter-admin-api`. Two of the
three are built:

| Place | Status |
|---|---|
| `ShardOwnedQueuesApi` + `DefaultShardOwnedQueuesApi` | in `postgresql-queue-shard-owned` |
| `ShardOwnedQueuesController` | in `spring-boot-starter-postgresql-queue-shard-owned` |
| `EssentialsAdminApiSpec` entries | **not done — blocked on publication** |

Both `admin-api-spec` and `spring-boot-starter-admin-api` are published; this engine is not
(`maven.deploy.skip=true`). A controller in the published starter would give a published artifact a
dependency on an artifact in no repository, and a spec entry would put a moving surface inside a
contract that is compatibility-checked at version `1.0.0`. So the controller ships with the engine's
own starter and borrows the admin API's conventions — base path, principal resolution, exception
handling — without extending its contract.

The consequence, stated plainly: **these endpoints do not appear in the generated OpenAPI document,
nor in the admin API's start-up summary of served contract areas.** Adding the spec entries and moving
the controller across is one step, and it belongs with publishing the engine.

The dependency on `spring-boot-starter-admin-api` is `provided` — it brings the event-store starter
with it, and an application that wants a queue and nothing else must not acquire an event store by
depending on this starter. The endpoints are wired only when the admin API's classes and an
`EssentialsSecurityProvider` are already present, which is exactly the case where the application has
chosen the admin API for itself.

### 13.7 Endpoints

All under the admin API base path (`essentials.admin-api.base-path`, default
`/api/essentials/admin/v1`).

| Method | Path |
|---|---|
| `GET` | `/shard-owned-queues` |
| `GET` | `/shard-owned-queues/{queueName}/status` |
| `GET` | `/shard-owned-queues/{queueName}/messages/{messageId}` |
| `GET` | `/shard-owned-queues/{queueName}/dead-letter-messages?offset=&limit=` |
| `DELETE` | `/shard-owned-queues/{queueName}/messages/{messageId}` |
| `DELETE` | `/shard-owned-queues/{queueName}/messages` (purge) |
| `POST` | `/shard-owned-queues/{queueName}/messages/{messageId}/retry` |
| `POST` | `/shard-owned-queues/{queueName}/messages/{messageId}/mark-as-dead-letter` |
| `POST` | `/shard-owned-queues/{queueName}/messages/{messageId}/resurrect` |

A malformed `messageId` is a 400, not a 500 — `MessageId.parse` throws `IllegalArgumentException`,
which the admin API's exception handler already maps. A database failure surfaces as
`MessageQueueException`, which maps to 500.

---

## 14. Configuration

Full reference with sizing formulas and worked examples: [`LLM/LLM-postgresql-queue-shard-owned.md`](../LLM/LLM-postgresql-queue-shard-owned.md). Summarised here only to show which knob governs which mechanism.

The **Lane** column matters: the two lanes resolve "has everything before this arrived yet" by
different mechanisms, so several settings govern one lane and are inert for the other.

| `ShardOwnerSettings` — per process | Default | Lane | Governs |
|---|---|---|---|
| `pumpThreads` | 2 | both | §3 — held connections are `pumpThreads + 1` |
| `readBatchSize` | 500 | both | §5.2 — rows per cursor read |
| `ackBatchSize` / `ackFlushInterval` | 200 / 1 ms | both | §5.4 |
| `sweepInterval` / `maxSweepInterval` | 500 ms / 30 s | both | §5.1 — backstop cadence and the idle back-off |
| `pollBackstop` | 500 ms | both | §3.1 — the park ceiling, not a floor |
| `leaseTtl` | 30 s | both | §8.2 — lease lifetime; the heartbeat renews at a third of it |
| `shedGrace` | 5 s | both | §8.4 |
| `chaseDelay` | 2 ms | unordered | §5.3 — sets hole resolution latency directly. On the ordered lane it only throttles the watermark probe, so it cannot spin |
| `holeExpiry` | 10 s | unordered | §5.3 — must exceed the longest enqueue transaction |
| `maxHolesPerChase` | 1 000 | unordered | §5.3 — ceiling on holes resolved in one chase query |
| `watermarkCap` | 60 s | ordered | The ordered lane's counterpart to `holeExpiry`, and deliberately **not** the same setting. A gap is never chased or written off on a timer — it is resolved exactly (§7). The cap only bounds how long one pathological transaction may pin the lane before the cursor is forced past it. It can be generous because it costs nothing to wait; `holeExpiry` is held low by the per-hole map entry and chase query |
| `keyConcurrency` | 8 | ordered | §7 — concurrent keys per ordered shard |

`leaseTtl` is its own setting and no longer derived. It was once `holeExpiry × 3`, which coupled two
unrelated questions: an ordered lane configured with a short `holeExpiry` leased its shards for less
time than the heartbeat took to renew them.

| `ConsumerOptions` — per consumer | Default | Governs |
|---|---|---|
| `parallelConsumers` | 8 | handlers in flight for this consumer, and the **only** bound on handler concurrency. There is no process-wide ceiling — one was removed as unmeasured and too high to bind (see `HandlerDispatch`), so the sum across consumers is what your pool must absorb |
| `maxShards` | unbounded | cap on shards this consumer holds |
| `maxAttempts` / `retryDelay` / `retryMultiplier` / `maxRetryDelay` | 3 / 100 ms / 2.0 / 30 s | §9 |

`shardCount` is per queue and set at registration. Shards are the unit of parallelism **and** of
ordering. Measured on the ordered lane (500 keys, 2 ms handler, `keyConcurrency` 8, interleaved arms):
**the knee is at 4 shards — 89% of peak — and 8 buys 95% of what 16 does**, while return per shard
falls from 1 155 to 151 msg/s across that range. Idle cost is ~0.1 queries/s per owned shard per
lane, so shards past the knee buy overhead and nothing else. Full table in
[`durable-queue-measurements.md`](./durable-queue-measurements.md) §3.7.

**Size an ordered queue for its future peak, not today's load.** The two lanes differ in how
expensive a wrong answer is, though neither costs a deploy:

- **Unordered** — call `growShardCount` and carry on. Routing is round-robin, so nothing depends on
  which shard a message landed in, and running consumers pick the new count up on their next
  heartbeat (§2.7).
- **Ordered** — nothing to do. The lane hashes keys into a fixed unit space of its own and never reads
  `shardCount`, so growing the count moves no key and can happen with ordered traffic in flight. This
  bullet used to describe a pause-and-drain; there was no pause to perform.

**No restart, and no redeploy, in either case.** `ShardOwnedQueue.refreshShardCount` re-reads the
registry on the heartbeat and `rebalance` acquires up to the new count;
`a_running_consumer_picks_up_a_grown_shard_count_without_a_restart` asserts it. Earlier versions of
this section said instances had to be restarted together — that was stale, and it survived into two
other documents.

Over-provisioning an ordered queue costs ~0.1 queries/s per extra shard, so the asymmetry still says
size it generously up front: the correction is cheap, but not free, and it is easier not to need it.

---

## 15. Guarantees, and what they cost

**What the engine guarantees**

- At-least-once delivery. A shard moving mid-flight legitimately redelivers; handlers must be idempotent. Tests that assert exactly-once are wrong.
- Strict per-key FIFO on the ordered lane, across processes, for the `key_order` values that are present, while the key's shard has one owner. Violations are counted rather than assumed away.
- Enqueue is atomic per call. A batch either lands entirely or not at all, and a caller already in a transaction keeps control of the outcome.
- Nothing is lost to a lost notification, a lost connection, a crashed owner, a frozen owner, or a database write outage. Every one of those has a dedicated integration test that was verified to fail against the unfixed code.
- All durable time is server-side, so node clock skew does not affect ownership or visibility.

**Delayed delivery.** `Message.delayed(...)` and `Message.delayedOrdered(...)` set `visible_at` to the *server's* `now()` plus the delay, so a delay never depends on the enqueueing node's clock. A delayed row is invisible to the cursor read, so the owner learns when it is next due by asking the server for the interval — not for a timestamp it would then subtract a local clock from — and parks until then. Without that it would wait for the head sweep, which on a quiet shard has backed off to `maxSweepInterval`.

**Transactional enqueue.** `enqueue(Connection, List<Message>)` writes the rows and the wake-up notification on the caller's connection and commits nothing: the caller's commit publishes both, and a rollback takes both with it. A connection in autocommit is refused rather than quietly given the non-transactional path. Attempt counting and dead-lettering happen later, in the owner, outside any caller's rollback scope — which is the half the current implementation's `FullyTransactional` mode gives up.

**What it does not**

- Exactly-once delivery, cross-service messaging, or ordering across shards.
- Ordering across a `shardCount` change **while the ordered lane holds messages**. A key's shard is `hash(key) mod shardCount`, so growing the count would send a key's next message to a different shard from its last. `ShardOwnedSchema.growShardCount` refuses in that state. Growing is supported once the ordered lane is empty, and at any time for an unordered-only queue — see §2.7. Shrinking is not supported at all: the messages in the removed shards would be addressed by nobody.
- `SessionScope.KEY` (§10).

**Known gaps** — features described in the contract or in the module's own documentation that are not implemented:

| Gap | Detail |
|---|---|
| No admin **UI** | The engine implements its own `MessageQueue` SPI, not `DurableQueues`, so none of the surrounding Essentials machinery consumes it. A Spring Boot starter exists (`components/spring-boot-starter-postgresql-queue-shard-owned`) and wires the engine's own contract, including interceptor and observer beans |
| No semantic type for `instanceId` | Deliberate. `QueueName` is a local record for the same reason: the `types` module carries kotlin-reflect and kotlin-stdlib at compile scope, which is a poor trade for a wrapper in a module that otherwise depends on `shared` alone. The transposition hazard — a `short`, an `int` and a `String` in a row — is closed instead by the builders, which name every argument |

**Not measured** — absence of a result, not a passing one. Network partitions between live nodes, genuinely separate hosts with independent clocks, soaks longer than a few minutes, payload size distributions beyond a uniform 200 bytes, and throughput on hardware that can hold a throughput number still. See [`durable-queue-measurements.md`](./durable-queue-measurements.md) §4 for what this lab can and cannot resolve.

---

## 16. Map of the code

| Class | Responsibility |
|---|---|
| `spi/MessageQueue` | The contract. A new interface rather than an implementation of `DurableQueues`; each omission is justified in its javadoc |
| `spi/` — `Message`, `MessageId`, `QueueName`, `MessageHandler`, `ConsumerOptions`, `Subscription`, `QueueSession`, `SessionScope`, `QueueDepth`, `DeadLetter`, `QueueObserver`, `MessageQueueInterceptor` | The contract's types |
| `spi/operations/` — `EnqueueMessages`, `HandleMessage` | What an interceptor intercepts |
| `spi/MessageQueues` | The queues a process can reach, by name. The smallest thing that makes a registry-scoped caller possible over a single-queue `MessageQueue` |
| `spi/MessageQueueException` | An engine failure, unchecked, for callers with no transaction to roll back |
| `api/ShardOwnedQueuesApi` | The administrative contract: registry-scoped, per-principal, payloads withheld by default |
| `api/DefaultShardOwnedQueuesApi` | Authorise, resolve, delegate, convert — and nothing else |
| `api/ApiShardOwnedMessage` / `ApiShardOwnedQueueStatus` | Its DTOs. Redactable payload, text-form id, depth and ownership together |
| `ShardOwnedSchema` | DDL for all six tables, the sequences, the name registry, and key-to-shard hashing |
| `ShardOwnedStorage` | Every SQL statement in the engine — both lanes, the DLQ, leases, membership, sessions |
| `ShardOwner` | Unordered lane: cursor, holes, in-flight and pending-ack sets, ack floor, head sweep, retries |
| `OrderedShardOwner` | Ordered lane: per-key FIFO, cross-key parallelism, per-key retry blocking, draining shed |
| `LeasedOwner` | What both owners have in common, and what the pump and the heartbeat talk to |
| `ShardRuntime` | Process-wide pumps, listener, heartbeat scheduler, handler executor. Shared per `DataSource`, reference counted |
| `ShardPump` | One platform thread, one connection, many shards |
| `ShardWakeup` | A flag under a monitor, cascading shard → pump. Not a semaphore |
| `ShardWakeupListener` | The `LISTEN shard_queue_wakeup` connection and its reconnect loop |
| `HandlerDispatch` | Per-consumer permits over the virtual-thread executor. The process-wide second level was removed |
| `ShardOwnedQueue` | One queue's leasing, heartbeat, rebalancing, and both lanes' owners |
| `PostgresqlMessageQueue` | `MessageQueue` implemented over `ShardOwnedQueue` |
| `ShardQueueSession` / `RowLeaseQueueSession` | `SHARD` scope, and `MESSAGE`/`BATCH` scope |
| `ShardOwnerSettings` / `ConsumerOptions` / `RedeliveryPolicy` | Configuration |
| `ShardOwnerMetrics` | Engine counters plus the consumer's `QueueObserver` |
| `observability/micrometer/MicrometerQueueObserver` | Micrometer binding, `micrometer-core` `provided` |

Naming follows the rest of Essentials: the implementation that a consumer names is `PostgresqlMessageQueue`, matching `PostgresqlDurableQueues` and `PostgresqlFencedLockManager`, and the engine internals take a `ShardOwned` prefix where the type would otherwise be ambiguous.

---

## 17. Operational requirements

Everything a deployment needs from the database and its surroundings, and what happens if it is
missing. The engine checks the one requirement it cannot survive losing, and fails loudly at
start-up rather than silently later.

### 17.1 PostgreSQL version

**PostgreSQL 13 or later** if the ordered lane is used; **9.5** for the unordered lane alone. The
floor comes from the start-up probe, not from the delivery path:

| Feature | Introduced | Used by | Used for |
|---|---|---|---|
| `pg_current_xact_id()` | 13 | probe only | Forces a real xid, so the probe tests the actual condition (§17.2) |
| `pg_stat_activity.backend_xid` | 9.4 | ordered lane | The set of write transactions that may still commit below the cursor (§7) |
| `ON CONFLICT`, `FOR UPDATE SKIP LOCKED` | 9.5 | both | Registration, leasing, pull sessions |
| `LATERAL` | 9.3 | both | The per-unit batched read (§5.2) |
| `pg_notify`, `pg_advisory_xact_lock` | 9.0 / 9.1 | both | Wake-up, and serialising schema creation |

There is no version gate in the code and nothing in the *runtime* ordered path needs 13 — the
watermark reads `backend_xid`, which is 9.4-era. It is the probe that uses `pg_current_xact_id()`,
and the probe is not optional, so 13 is the effective floor for that lane. Measured on 17.5 and
17.10.

Note that the watermark does **not** use `pg_sequence_last_value` or `pg_snapshot_xmin`, despite both
appearing in earlier descriptions of the design. It compares *sets* of running xids, because
`backend_xid` wraps at 32 bits and `pg_snapshot_xmin` does not — a numeric comparison between them is
correct in every test and wrong once per wraparound cycle. See the design document's §3.

### 17.2 The one hard requirement: `backend_xid` must be readable

The ordered lane's cursor decides that a sequence value can never arrive by comparing the allocation
bound against the set of running write transactions (§7, and the design document's §3). That set
comes from `pg_stat_activity.backend_xid`.

**A partial answer here is a wrong answer, not a degraded one.** If the query returns a subset of the
running writers, the watermark advances over a live one and its messages are skipped — silently, with
no error and no dead letter. That is the worst failure the engine has, so it is the one thing checked
before the lane starts.

`ShardOwnedSchema.verifyWatermarkPrerequisites` runs as the first statement of `startOrdered`, once
per `DataSource`. It does not inspect the column, because `backend_xid` is legitimately null for a
backend that has not written and an inspection would pass everywhere. It **constructs the
condition**: a second connection is forced to take a real xid with `pg_current_xact_id()`, and the
first must see it. Failing that, `startConsumingOrdered` throws with the remedy named.

**In practice this is satisfied out of the box.** Measured on PostgreSQL 17.10, an ordinary `LOGIN`
role with no grants reads `backend_xid` both for its own backends and for another role's:

| Observer | Writer | `backend_xid` visible |
|---|---|---|
| ordinary role | same ordinary role | yes |
| ordinary role | a *different* ordinary role | yes |
| ordinary role + `pg_read_all_stats` | a different ordinary role | yes (no change) |

So `pg_read_all_stats` is a **fallback the error message names, not the mechanism** — granting it
changed nothing in any case tested. It is worth naming anyway: a managed service is free to restrict
this view more than stock PostgreSQL does, and the probe is what turns that from silent message loss
into a start-up failure. If the probe ever fires on a managed platform, the alternatives are that
grant or the unordered lane.

Note that the demo application's role is a superuser and therefore proves nothing about this; the
table above comes from roles created for the purpose.

### 17.3 Connections

| Consumer | Count | Lifetime |
|---|---|---|
| Pump threads | `pumpThreads` (default 2) | **Held permanently** while the engine runs |
| Wake-up listener | 1 | **Held permanently** — a dedicated `LISTEN` connection |
| Heartbeat, leasing, rebalancing | 1 at a time, from the pool | Per operation |
| Handlers | 1 per in-flight handler that opens a unit of work | Per handler invocation |

So the floor is `pumpThreads + 1` connections that the pool can never reclaim, regardless of load.
Size the pool as that floor plus the handler concurrency you can actually reach — which is the
**sum of every consumer's `parallelConsumers`**. There is no process-wide ceiling behind it: one
existed, defaulted to 512, and was removed because nothing measured or derived that number and it
could not bind before a default pool of ten was long exhausted. Eight consumers at the default of 8
is 64 handlers, each potentially wanting a connection.

A pool smaller than the floor does not fail cleanly: the engine starts, takes what it can, and the
remaining pumps block acquiring a connection that will never be free.

### 17.4 What the engine requires of the schema

- **Six tables, two sequence families and one registry row per queue**, all created by
  `ShardOwnedSchema`. A queue must be registered before it is consumed — the engine will not invent
  a shard count, because the count caps how many instances can consume the unordered lane and can be
  raised but never lowered (§2.7).
- **One ordered sequence per queue** (`orderedSequenceName(queueId)`). Sequences are not transactional,
  which is what makes the watermark necessary and is the reason none of this is a gap-free counter.
- **`LISTEN`/`NOTIFY` on one global channel** (`shard_queue_wakeup`). If notifications are blocked —
  some poolers in transaction mode will do this — the engine still delivers, on the sweep cadence
  instead of on the notification, so latency degrades to `sweepInterval` and there is no correctness
  loss. `maxSweepInterval` then sets the worst case for an idle shard.

### 17.5 What it does not require

- **No superuser.** Nothing in the engine needs one; the ordinary-role result in §17.2 is the point.
- **No replication slot, no `wal_level = logical`, no extensions.** WAL streaming was considered for
  wake-up (Tier 3, §6) and not built, precisely so that a queue would not carry a replication slot's
  operational weight.
- **No advisory locks, no `SELECT … FOR UPDATE` on the delivery path.** Ownership is a lease plus a
  fence, so there is no claim write and no lock to leak on a crash.
- **No cross-service coordination.** Like the rest of Essentials' queues, locks and inbox/outbox, this
  is for multiple instances of **one** service against **one** database.

### 17.6 Failure modes an operator should recognise

| Symptom | Cause | Resolution |
|---|---|---|
| `startConsumingOrdered` throws naming `pg_read_all_stats` | §17.2's probe failed | Grant it, or use the unordered lane. Do not suppress the probe |
| A queue's depth is non-zero and steady, no errors logged | Nobody owns its shards | Check `unownedShards`, not depth — depth cannot distinguish "unserved" from "busy" (§12) |
| Ordered lane stops advancing, `watermarkCap` in the log | A write transaction outlived the cap | Find the long transaction; the cap protects the lane, it does not fix the writer |
| Pumps never start, no error | Pool smaller than `pumpThreads + 1` | §17.3 |
| Delivery latency jumps to seconds when idle | Notifications not arriving | §17.4 — check the pooler's mode |
