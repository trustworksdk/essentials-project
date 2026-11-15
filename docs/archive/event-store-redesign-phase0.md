# Event store redesign: phase 0 record

> **ARCHIVED (2026-11-15).** Phase 0 of the event store redesign is finished; the design itself is still a plan in [`../plans/event-store-subscription-redesign.md`](../plans/event-store-subscription-redesign.md), whose "Phase 0 decisions" section lists what was decided.

This is the condensed record of phase 0 (investigation) of the redesign: what each spike P0.0-P0.17 asked, how it was
run, the numbers it produced and what was decided from them. It replaces seven working documents (a summary, the P0.0
baseline, the append and table-design measurements, the JDBI comparison, the P0.5 model, the dedicated-server runs and
the P0.14-P0.17 background); their run-by-run history is in git. The spike definitions, gates and results table stay in
the redesign's §18.

Numbers are from the dedicated server unless marked **VM**. The machines are described in
[How the spikes were run](#how-the-spikes-were-run).

## Summary and decisions

Phase 0 confirms the redesign's correctness assumptions and its write-path shape:

- The single-statement append (§5.1) is kept as written: its xid is always taken before the identity value, on
  PostgreSQL 16, 17 and 18.
- The combined probe (§4.10) is kept, provided it runs in autocommit or after `pg_stat_clear_snapshot()`.
- The engine stays on raw pgjdbc with no second driver: JDBI costs only a few µs, and pgjdbc's `PINNED_PEEK` drain
  delivers notifications as fast as Vert.x.
- Transactions the engine owns are sent as one explicit `BEGIN … COMMIT` flush through pgjdbc (P0.14), never as an
  autocommit batch.
- PostgreSQL 18.7 changes no phase-0 decision.
- Nothing may be released as supported on a managed PostgreSQL service until P0.3 passes for that service.

Every proposal the model and the read-side spikes made was decided on 2026-11-15 as recommended, and is listed in the
redesign's "Phase 0 decisions" section: P0.5's F1-F6 (with the `UNION ALL` hole re-read of P0.11), the after-commit
coalesced notify (P0.10), reading `pg_stat_get_activity(NULL)` with a cost-based probe rate (P0.12), per-page resume
points with a backing-off fallback and an opt-in page handler (P0.13), and generic plans plus one flush per wake for
the tail reader (P0.16).

| Spike | Question | Answer / decision | Confidence or caveat |
|---|---|---|---|
| [P0.0](#p00-baseline-of-the-current-engine) | What does today's engine cost? | Baseline recorded: 68.9-69.1 reader statements per appended event at 10 ev/s (6.8 per delivery), 1.04 per delivery at 100 ev/s; idle 1.5 statements/s per subscription; OCC burns 616-625 identity values per 1 000 appends (VM 466). These numbers are the phase 2/3 gates. | Machine-independent counts match the VM. The second burst run's ~480 000-490 000 B of WAL is probably autovacuum. |
| [P0.1](#p01-xid-before-identity) | Is the xid taken before the identity value? | **Holds.** 24 of 24 cases with the `xid` CTE, all controls null; VM 72 of 72 on 16.15, 17.5 and 18.4. §5.1 kept, no separate `pg_current_xact_id()`. | Rests on executor behaviour, so it must run in CI against every supported major. |
| [P0.2](#p02-combined-probe-snapshot-order) | Does the combined probe read writers after its snapshot? | **Holds in autocommit.** Unsafe inside a transaction that read `pg_stat_activity` earlier, because backend status is cached per transaction. Kept, with autocommit or `pg_stat_clear_snapshot()`. | The probe must also return the running set when the tail is empty (not tested). |
| [P0.3](#p03-managed-services-deferred) | Is `backend_xid` visible on managed services? | **Deferred**: no RDS, Aurora, Azure or Cloud SQL access. Partial: Supabase passes for its `postgres` role (a member of `pg_read_all_stats`). | Not checked: a role without that membership, and the transaction pooler. |
| [P0.4](#p04-single-statement-append) | Is the single-statement append faster? | **Gate partly met; §5.1 kept.** Uncontended: B −30-35 % at 100 events, −37-44 % for 5 aggregates, −7-9 % at 10 events, a tie at 1 event. Contended, the arms converge (B/A ≈ 0.98) and B burns 12-16 % fewer values (VM: about half). An advisory lock as a separate statement gives 0 holes at −3 to −5 %. | B's extra server CPU is 2-3x its execution difference. The 18.7 append rows fell in the slow phase; the contention figures are valid. |
| [P0.5](#p05-model-of-the-subscription-state-machine) | Does the state machine hold? | **I1, I3, I12 and I13 hold; I2 fails in `FRESH` as written** and holds once the tail re-reads the outstanding holes (F1). F1-F6 decided. | Deterministic Java model, not TLA+. Fencing (I5), `resetFrom` (I6) and tenants were not modelled. |
| [P0.6](#p06-jdbi-against-raw-pgjdbc) | Is JDBI's overhead material? | **No.** Contended, raw pgjdbc is +6-16 % ahead; JDBI costs +20-70 µs of server CPU per attempt, which fits its read-ahead round trip on `setFetchSize(1)`. Raw pgjdbc stays, chosen for its capabilities. | The VM's NORMAL-mode JDBI lead was an idle effect. The fetch-size fix was not re-tested on the server. |
| [P0.7](#p07-table-design-options) | Table design options | **With durable commits the head table costs no throughput.** COMBO_ALL +8 % over BASE on 17.12 (+4 % on 18.7), COMBO_ALL_SCALAR +12 % on 17.12, both with less server CPU. WAL 892-895 B per event (BASE) down to 551-563 B (COMBO). | Findings only. P0.7 has no gate row in §18. |
| [P0.9](#p09-unattributed-server-cpu) | Where does the unattributed server CPU go? | Outside recorded execution, A spends 274-295 µs and B 301-326 µs per attempt, the same under NORMAL and SPIN, on 17 and 18. The VM's idle/wake-up explanation does not apply on this host. | **Open**: the split between commit flush, protocol and background processes. |
| [P0.10](#p010-wake-up-notification-cost) | Cost of the tier-1 wake-up | `pg_notify` inside the transaction: −75 to −79 % at 8 writers, −81 to −85 % at 20, ceiling about 1 000-1 160/s. After commit, coalesced per JVM: about −10 % at 8 writers, even at 20. Per writer: −32 to −38 % at 8. Decided: tier 1 after commit, coalesced. | The pgjdbc wake latency (p50 2.3-19.8 ms against 0.02-1.09 ms on the wire) is not attributed. |
| [P0.11](#p011-re-reading-holes-in-the-tail-statement) | Cost of re-reading holes in the tail | `OR = ANY` walks the whole primary key under a generic plan (289-406 ms); `UNION ALL` (D) uses index scans in every mode, 36-46 µs up to 10 holes, 280-318 µs at 1 000. Decided: a `UNION ALL` branch. | The trap is identical on 18. Open: a range branch that does not trip the JIT, and more than 1 000 holes. |
| [P0.12](#p012-cost-of-the-probes-pg_stat_activity-read) | Cost of the probe's `pg_stat_activity` read | 1.34-1.41 µs per backend (VM ~0.55). At 400 connections, 40 combined probers cut appends by 80-84 % (tail-only: 51-54 %), and the server reaches 6.0 cores. Decided: read `pg_stat_get_activity(NULL)` directly; probe rate cost-based, not fixed per JVM. | On 18.7 the probe's own execution does not grow, yet throughput is 17 % lower, so the extra cost is elsewhere. Client/server split open. |
| [P0.13](#p013-projection-progress-per-event-per-page-and-periodic) | Resume point in the handler transaction | Pages beat the periodic save: `PAGE_100` 7.8x with 4 projections, set-based `PAGE_500_SET` 45.6-49.8x. The per-event penalty disappears; one failure in 1 000 costs −44 % against `PAGE_100`. Decided: the fallback backs off the page size. | Single windows. Open: `ViewEventProcessor`'s savepoint batch mode, views with secondary indexes. |
| [P0.14](#p014-one-flush-for-engine-owned-transactions) | One flush for engine-owned transactions | **Passes at 8 writers** for 10 events (+31 %), 5 aggregates (+17 %) and the page (+20 %); fails for a single event (−7 %) but gains +49 % at 20. Saves 42-137 µs of server CPU per removed round trip. Use explicit `BEGIN … COMMIT` (`D_EXPLICIT`). | An autocommit batch is not atomic past about 64 KB (255 of 399 rows committed). Wire client arm C not built. The five-aggregate rollback needs a correctness test. |
| [P0.15](#p015-group-commit-for-engine-owned-writes-parked) | Group commit for engine-owned writes | **Parked** 2026-11-14. | Revisit only if round trips still dominate after P0.14. |
| [P0.16](#p016-owned-pre-prepared-read-connections) | Owned, pre-prepared read connections | **Use `force_generic_plan` and one flush per wake.** A generic plan saves 57-189 µs server CPU per tail read (p50 −24 to −56 %); owning the connection alone saves nothing. Ten woken types in one pgjdbc flush cost −58 % server CPU (−66 % at 50). The minimal wire client is within −11 to +5 % of pgjdbc. | The pool with a generic plan was not measured. Generic plans only for statements whose plans were checked (not P0.11's `OR = ANY`). |
| [P0.17 (a)](#p017-a-faster-notification-listener) | Faster notification listener? | **pgjdbc only.** At 3 000/s, `PINNED_PEEK`'s p99 is 0.60-0.72 ms against Vert.x's 0.60-0.66 ms; the stock drain lags up to 1.4 s inside pgjdbc. | `QUERY_POLL` is a borderline fallback (2.10-2.17 ms p99 in the 20-writer burst). |
| [P0.17 (b)](#p017-b-request-path-pipelining-parked) | Request-path pipelining | **Parked** 2026-11-14: it needs a wire client of our own. | — |

### What changed when the spikes moved off the VM

A real WAL flush (`fsync=on`) changes the contended picture. On the VM, B appended 30 % slower than A, the head-table
options ranked clearly, and B halved the holes. On the server every arm commits once and waits on the flush, so the
arms converge, B saves only 12-16 % of burned values, the head table becomes free, and burned values per 1 000 rise to
616-625. Several VM-era readings of cost changed:

- **P0.9:** the idle/wake-up explanation fails; about 280 µs per attempt stays outside recorded execution.
- **P0.6:** the NORMAL-mode JDBI lead disappears.
- **P0.10:** the in-transaction notify penalty grows, and a notify per writer stops being free.
- **P0.12:** the per-backend slope is about 2.5x the VM's, and the collapse deepens to −80-84 %.
- **P0.13:** the per-event penalty disappears, page ratios grow with the cost of a flush, and the fallback penalty
  doubles.

Server execution is 2.5-4x the VM's everywhere, today's statements included; that is the machine's speed, not any
one statement. When a ratio between arms is the same on both machines, it is a property of the statements; when it
differs, the server's figure is the one closer to a production database host.

## How the spikes were run

### Machines

- **VM.** An 8-CPU arm64 Docker Desktop linuxkit VM (15.6 GiB for Docker), JDK 25 (Temurin), Testcontainers
  PostgreSQL (`postgres:17.5` unless a spike says otherwise) with Testcontainers' default `fsync=off`, client and
  database sharing the VM's CPUs, a fresh container per run. About half of PostgreSQL's CPU per attempt on this VM is
  the cost of CPUs going idle and being woken up (see [P0.9](#p09-unattributed-server-cpu)), so its absolute CPU
  figures, and ratios between arms that differ in throughput, are specific to it.
- **Dedicated server.** An Intel i7-7700HQ laptop (4 cores / 8 threads, 30 GB, Ubuntu, kernel 7.0), PostgreSQL in
  Docker Compose with host networking, `fsync=on`, `synchronous_commit=on`, `wal_sync_method=fdatasync`,
  `shared_buffers=8GB`, `max_connections=500`, `jit=off`. The client JVM (Temurin 25.0.5) ran on the same host over
  `127.0.0.1` (ping 0.026 / 0.041 / 0.054 ms), so the network is out of every figure but client and server share the
  8 hardware threads. Runs:
  - 2026-11-13: every suite on **17.5**, governor `powersave`, no server CPU.
  - 2026-11-14: every suite on **17.12**, governor `performance`, server CPU from the container's cgroup.
  - 2026-11-15: P0.17 (a) on 17.12; every suite on **18.7** (Debian image, so 18's defaults apply where not set,
    among them `io_method=worker` and data checksums); P0.12 rerun on 18.7; P0.14 and P0.16 on 18.7.

  A first run on 2026-11-12 (read-side suites, client on Wi-Fi, 17.11) was bound by the network (a `SELECT 1` cost
  6.9 ms at p50) and is superseded; only its plan and ratio confirmations carried over.

### The run script

`scripts/run-phase0-spikes.sh` runs the spikes and keeps their output. It checks the prerequisites (JDK 25-27, Maven,
Docker or a reachable server), moves an existing `target/spike-results/` aside so runs never mix, builds the module
once, runs each suite sequentially with its own log (a failing suite does not stop the rest), and collects everything
into `phase0-results/<timestamp>-<host>-<mode>/` plus a `.tar.gz` of it: the spike result files, the logs, the
spikes' failsafe reports, `machine.txt` (client), `server.txt` (external mode: version, settings, a 200 × `SELECT 1`
round-trip measurement when `psql` is installed, host CPU and disks through ssh) and a `SUMMARY.md`. In external mode
it adds `-Ddocker.skip=true` (the module's docker-maven-plugin otherwise builds a CDC test image during `verify`).

```bash
# Testcontainers on this machine (needs Docker)
scripts/run-phase0-spikes.sh --container

# the dedicated server; the password comes from SPIKE_PASSWORD or a prompt, never the command line
export SPIKE_PASSWORD=...
scripts/run-phase0-spikes.sh --external jdbc:postgresql://dbhost:5432/postgres --user bench \
    --server-cpu-command "ssh bench@dbhost cat /proc/stat"

# selected suites, extra spike properties after --, and a look at the Maven commands first
scripts/run-phase0-spikes.sh --external jdbc:postgresql://dbhost:5432/postgres --user bench \
    --suite xid,contention --dry-run -- -Dspike.contention.seconds=20
```

Suites and the spike classes they run (all in
`components/postgresql-event-store/src/test/java/.../postgresql/spike/`, tagged `spike`, run only with
`-Dspike.run=true`; results in `components/postgresql-event-store/target/spike-results/p0-*.txt`):

| Suite | Spike | Class |
|---|---|---|
| `xid` | P0.1 | `AppendXidBeforeIdentitySpikeIT` |
| `probe` | P0.2 | `CombinedProbeSnapshotOrderSpikeIT` |
| `baseline` | P0.0 | `CurrentEngineBaselineSpikeIT` |
| `append` | P0.4, encoding | `SingleStatementAppendSpikeIT`, `AppendEncodingAttributionSpikeIT` |
| `contention` | P0.4 contended | `ContendedAppendThroughputSpikeIT` |
| `jdbc` | P0.6 | `JdbiVsRawPgjdbcSpikeIT` (also `round_trip_reconciliation`) |
| `table-design` | P0.7 | `TableDesignOptionsSpikeIT` |
| `cpu` | P0.9 | `UnattributedCpuSpikeIT` |
| `notify` | P0.10 | `WakeupNotifyCostSpikeIT` |
| `tail` | P0.11 | `TailHoleReReadSpikeIT` |
| `probe-cost` | P0.12 | `ProbeRunningSetCostSpikeIT` |
| `progress` | P0.13 | `ProjectionProgressSpikeIT` |
| `pipeline` | P0.14 | `OneFlushTransactionSpikeIT` |
| `owned` | P0.16 | `OwnedReadConnectionSpikeIT` |
| `listen` | P0.17 (a) | `AlternativeClientSpikeIT` |

A suite can name one test method, as in `jdbc#protocol_trace`; `--help` lists every option. By hand, a spike is
`mvn -o verify -pl components/postgresql-event-store -DskipUTs -Dit.test=<SpikeClass> -Dfailsafe.failIfNoSpecifiedTests=false
-Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true` plus its own tuning properties (documented in its
javadoc). A short smoke first (for example `-Dspike.contention.runs=1 -Dspike.contention.seconds=2`) catches setup
problems in a minute.

### Pointing the spikes at an external server

| Property | Meaning |
|---|---|
| `-Dspike.jdbcUrl=jdbc:postgresql://dbhost:5432/postgres` | The external server. When set, no PostgreSQL container is started. The database in the URL is only used to connect; URL parameters (for example `sslmode`) are kept. |
| `-Dspike.user`, `-Dspike.password` | A superuser on that server (default user `postgres`). |
| `-Dspike.serverCpuCommand="…"` | Optional. A shell command, run on the client, that prints either `/proc/stat` of the database host (whole machine) or a cgroup v2 `cpu.stat`. Run twice per measurement window. |

Every server a spike asks for gets its own database, `spike_<purpose>_<suffix>`, and its roles (`observer`, `writer`,
`reader`, `reader2`, `explainer`, `spike`) are created as superusers `<role>_<suffix>`, as in the container; both are
dropped when the spike ends (a killed JVM can leave `spike\_%` databases behind). The harness checks that the user is a
superuser, that `pg_stat_statements` is preloaded and that `backend_xid` of other backends is visible, and fails with
a message saying what to change. Without a container, server CPU needs `-Dspike.serverCpuCommand` (otherwise `NaN`),
per-backend schedstat reads `NaN`, machine busy and IPI figures describe the client only, P0.9's `BURNER` and `perf`
arms are skipped, and one run covers one server major. Every results header carries an `EXTERNAL server` line with
the version and the settings that move the numbers.

### Server configuration

The dedicated runs used the official image under Docker Compose; the image ships `pg_stat_statements` and
`auto_explain`. The settings suit 30 GB of RAM, 4 cores and a benchmark (stable numbers, no surprise checkpoints), not
production. On an Ubuntu package install (PGDG repository) the same settings go into
`/etc/postgresql/<major>/main/conf.d/spikes.conf`, with a `pg_hba.conf` line allowing `scram-sha-256` from the LAN.

```yaml
# compose.yaml on the database machine
services:
  pg:
    image: postgres:17.12           # or the latest 18 tag
    container_name: spike-pg
    network_mode: host              # no Docker NAT or userland proxy in every round trip
    shm_size: 1g                    # Docker's default /dev/shm is 64 MB
    environment:
      POSTGRES_USER: bench          # created as a superuser, which the spikes need
      POSTGRES_PASSWORD: choose-one
      POSTGRES_DB: postgres
    volumes:
      - pgdata:/var/lib/postgresql/data
    command: [postgres,
      -c, listen_addresses=*,
      -c, max_connections=500,                     # P0.12's 400-connection level needs at least 500
      -c, shared_preload_libraries=pg_stat_statements,
      -c, pg_stat_statements.max=10000,
      -c, pg_stat_statements.track=top,
      -c, pg_stat_statements.track_utility=on,     # P0.0 counts BEGIN/COMMIT
      -c, pg_stat_statements.track_planning=off,   # the spikes that need it turn it on for their own database
      -c, track_io_timing=on,
      -c, track_activity_query_size=4096,
      -c, shared_buffers=8GB,
      -c, effective_cache_size=22GB,
      -c, work_mem=32MB,
      -c, maintenance_work_mem=1GB,
      -c, huge_pages=try,
      -c, fsync=on,
      -c, synchronous_commit=on,
      -c, full_page_writes=on,
      -c, wal_compression=off,
      -c, wal_level=replica,
      -c, wal_buffers=64MB,
      -c, max_wal_size=16GB,                       # keep checkpoints out of a 20 s window
      -c, min_wal_size=2GB,
      -c, checkpoint_timeout=30min,
      -c, checkpoint_completion_target=0.9,
      -c, jit=off,                                 # the spikes' statements are short; JIT only adds noise
      -c, random_page_cost=1.1,
      -c, log_checkpoints=on,
      -c, log_autovacuum_min_duration=0]
volumes:
  pgdata:
```

Notes:

- With `POSTGRES_PASSWORD` set, the image's `pg_hba.conf` accepts password logins from any address; restrict
  reachability with the host firewall (`ufw allow from <lan>/24 to any port 5432 proto tcp`). `POSTGRES_USER` and
  friends apply only when the volume is initialised; with an existing volume create the role by hand
  (`CREATE ROLE bench LOGIN SUPERUSER PASSWORD '…'`).
- A superuser is needed, not just `pg_read_all_stats`: the harness creates per-run superuser roles, `LOAD`s
  `auto_explain`, resets `pg_stat_statements` and drops what it created.
- Prepare the laptop: power plugged in, governor `performance`, sleep disabled, no desktop session or background
  updates during a run. Record the disk and run `pg_test_fsync` on the data directory's file system; with `fsync=on`
  the disk decides much of the append cost. A client on another machine needs a wired connection.

### Measuring server CPU from the container's cgroup

On a host shared by client and server, a whole-machine `/proc/stat` counts the client too. The PostgreSQL container's
cgroup v2 `cpu.stat` counts every PostgreSQL process (backends and background processes) and nothing else, which
matches what the container path measures. With Docker's systemd cgroup driver the container's cgroup is
`/sys/fs/cgroup/system.slice/docker-<full container id>.scope` (under rootless Docker it sits under
`user.slice/.../docker-<id>.scope`):

```bash
scripts/run-phase0-spikes.sh --external jdbc:postgresql://127.0.0.1:5432/postgres --user bench \
    --server-cpu-command "cat /sys/fs/cgroup/system.slice/docker-$(docker inspect -f '{{.Id}}' spike-pg).scope/cpu.stat" \
    -- -Ddocker.skip=true
```

From another machine, wrap it in ssh (key-based, so it does not prompt); the host user must be in the `docker` group.
Check the command by hand once: `SpikeServer` reports "FAILING: check the command" in the run header if its output
cannot be parsed.

### Reading the results

- Compare what does not depend on the machine first: round trips per unit of work, statements per event, WAL bytes
  per event, plan shapes. These matched the VM on every server run.
- **Server CPU per attempt minus recorded execution** (`pg_stat_statements`) is the unattributed part. It includes
  everything `pg_stat_statements` does not record: `COMMIT` and its WAL flush, failed statements, socket I/O,
  background processes.
- With `fsync=on`, contended throughput is bounded by the flush rate times the group-commit factor, not by CPU. Arms
  that commit equally often converge; an arm that commits more often falls behind. The `pg_stat_activity` wait
  samples show which bound applies (`CPU` against `IO:WalSync`, `LWLock:WALWrite`, `Lock`).
- Compare arms only within the same flush phase (see [the slow flush phase](#the-slow-flush-phase-and-other-machine-caveats)):
  every spike runs its arms in palindrome order with paired windows so that a phase change shows as a disagreement
  between the two windows of one arm.
- The client machine's busy, context-switch and IPI figures describe the client alone on an external server and
  are not comparable to the VM's, which included the database.

## P0.0 Baseline of the current engine

**Question.** What does the current engine cost before any new engine code exists, so that later phases are
compared against measured numbers rather than a reading of the code? These numbers are the phase 2 and 3 gates.

**Setup.** `CurrentEngineBaselineSpikeIT` (with `RoundTripCountingProxy`), suite `baseline`, about 32 minutes for two
runs of every shape. Shapes: `idle`, `steady`, `burst`, `concurrentWriters`, `append` (one alone with
`-Dit.test=CurrentEngineBaselineSpikeIT#idle`). Knobs: `-Dspike.baseline.runs` (default 2),
`-Dspike.baseline.timeScale` (default 1.0), `-Dspike.baseline.image` (default `postgres:17.5`). Raw output, including
the top twelve normalised statements of every window, in `p0-0-baseline.txt`.

- **Database.** A fresh database per run of each shape; `pg_stat_statements` (`track=top`, `track_utility=on`) reset
  at the start of each window. The application connects as separate roles — `writer` for appends, `reader` (and
  `reader2` for a second instance) for the consuming event store and subscription manager — so the write path and the
  read path are separated; the observing connection uses a third role and is excluded. WAL is the
  `pg_current_wal_lsn()` difference over the window.
- **Engine wiring**, as `spring-boot-starter-postgresql-event-store` wires it by default: `PostgresqlEventStore` with
  `SeparateTablePerAggregateTypePersistenceStrategy` (`TEXT` ids, `JSONB` payloads, single tenant),
  `PostgresqlEventStreamGapHandler`, `EventStoreSubscriptionManager` with polling batch size 10, polling interval
  100 ms, resume points saved every 1 s, the starter's `JitteredEventStorePollingOptimizer(name, 100, 50, 2000, 0.1)`
  (+50 ms per empty poll up to 2 000 ms, ±10 % jitter), notify-polling off, CDC off, `PostgresqlFencedLockManager`
  (15 s lock timeout, 4 s confirmation interval), `PostgresqlDurableSubscriptionRepository`, schema mode `CREATE`, a
  Hikari pool of 10 per instance, `EssentialsJSONEventSerializers.create()`, the module's `OrderEvent` test types with
  a correlation id and a cause id in the metadata. The unit of work factory is `EventStoreManagedUnitOfWorkFactory`
  and the subscription observer is the no-op one; both are believed immaterial to the counts.
- **Counting caveat.** `pg_stat_statements` recorded a `BEGIN` for every transaction but almost no `COMMIT` or
  `ROLLBACK`, although the proxy shows the commit being sent. Every count that includes utility statements is
  therefore one statement per transaction short of what the client sends; "non-utility" counts exclude
  `BEGIN`/`SET`/`SHOW` and are exact.

The tables below are the **VM** run (PostgreSQL 17.5, `fsync=off`), both runs shown. The dedicated server's figures
follow in [P0.0 on the dedicated server](#p00-on-the-dedicated-server).

### Idle

No writes. 100 events seeded, a 45 s warm-up after every subscription had caught up (the optimizer needs about 41 s of
empty polls to reach its 2 s ceiling), then a 60 s window.

| Subscriptions | Instances | Statements/s, run 1 / run 2 | Non-utility/s | Gap-table rows written | WAL from statements |
|---|---|---|---|---|---|
| 1 non-exclusive | 1 | 1.45 / 1.50 | 0.97 / 1.00 | 0 | 0 B |
| 10 non-exclusive | 1 | 14.90 / 15.00 | 9.93 / 10.00 | 0 | 0 B |
| 50 non-exclusive | 1 | 75.05 / 74.65 | 50.03 / 49.77 | 0 | 0 B |
| 10 exclusive | 1 | 17.70 / 17.65 | 12.47 / 12.43 | 0 | 11 KB (lock confirms) |
| 10 exclusive | 2 | 22.75 / 22.75 | 15.00 / 15.00 | 0 | 11 KB (lock confirms) |

- An idle poll is one transaction of three counted statements (four sent): `BEGIN`,
  `SELECT MIN(global_order) … WHERE global_order >= ?` and the subscriber's transient gaps. Each subscription polls
  about every 2 s, so the cost is **0.5 polls/s, 1.5 counted (2 sent) statements/s per subscription, exactly linear in
  the number of subscriptions**.
- Exclusive subscriptions add the lock manager: the holder confirms each lock every 4 s (`UPDATE fenced_locks …`,
  150/min for 10 locks), and a second instance tries to acquire each lock every 4 s (`BEGIN` +
  `SELECT * FROM fenced_locks WHERE lock_name = ?`, 300 statements/min for 10 locks), about 0.5 statements/s per
  exclusive subscription.
- An idle read path writes nothing. The WAL position still moved about 92 KB per minute in every idle window, from
  server background (checkpointer, autovacuum).

### Steady

10 non-exclusive subscriptions, one writer appending one `OrderAdded` to a new aggregate per unit of work with no
expected order, 10 s warm-up, 60 s window. Latency is from the appender's unit of work returning after commit to the
handler's receipt, same JVM, over all ten subscriptions' deliveries.

| Rate | Latency p50 (ms) | p99 (ms) | max (ms) | Statements per appended event (writer / reader) | Reader statements per delivery | WAL per event |
|---|---|---|---|---|---|---|
| 10 ev/s | 49.8 / 49.1 | 108.2 / 107.9 | 116.3 / 142.4 | 3.0 / 68.3 and 3.0 / 68.1 | 6.83 / 6.81 | 1 248 / 1 255 B |
| 100 ev/s | 50.5 / 50.1 | 106.5 / 106.6 | 118.9 / 142.9 | 3.0 / 10.4 and 3.0 / 10.4 | 1.04 / 1.04 | 1 030 / 1 033 B |

- Latency is the polling interval: p50 is half of 100 ms and p99 just above it, at both rates.
- At 10 ev/s the read path costs **68 statements per appended event** against 3 on the write path. A poll that
  returns events is followed at once by another, which is empty and then sleeps, so each subscription makes about two
  polls per event; every poll is a transaction with `BEGIN`, the transient-gap read and a range read
  (`SELECT * FROM orders_events WHERE global_order BETWEEN ? AND ?`), and every empty one adds the `MIN` probe. Resume
  points add one `UPDATE durable_subscriptions` per subscription per second while they move.
- At 100 ev/s polls fill better and the read path falls to about one statement per delivery.
- Max latency differs between runs; noise.

### Burst

One subscription stopped after its first event; 100 000 events appended (1 000 units of work of one aggregate and
100 events, 4 writer threads); the subscription started again.

| Run | Append | Catch-up | Rate | Reader statements | Per event | Polls (`BEGIN`s) | Heap baseline / peak | WAL during catch-up |
|---|---|---|---|---|---|---|---|---|
| 1 | 0.7 s | 2.3 s | 42 753 ev/s | 37 513 | 0.375 | 12 506 | 25 / 104 MB (+79) | 552 B |
| 2 | 0.7 s | 2.6 s | 38 805 ev/s | 37 513 | 0.375 | 12 506 | 29 / 103 MB (+73) | 552 B |

Catch-up reads 8 events per range read on average (batch size 10), three counted statements per poll: **12 500
transactions for 100 000 events**. It writes nothing but three resume-point updates.

### Concurrent writers

8 writer threads, each at most 50 units of work per second (one `OrderAdded` to a new aggregate), 10 % rolled back
after the insert, for 30 s; one non-exclusive subscription; then 135 s more so the transient gaps reach the 120 s
give-up threshold.

| Run | Committed | Rolled back = holes | All committed delivered | Late deliveries | Transient gap rows inserted / deleted | Permanent gap rows inserted | In-order delivery p50 / p99 |
|---|---|---|---|---|---|---|---|
| 1 | 10 208 | 1 121 | yes, no duplicates | 2 (1.2 / 2.5 ms) | 1 122 / 1 122 | 1 120 | 71.7 / 278.3 ms |
| 2 | 10 172 | 1 146 | yes, no duplicates | 1 (1.8 ms) | 1 147 / 1 147 | 1 146 | 86.0 / 432.7 ms |

- Every committed event was delivered, once.
- **Every hole cost the subscriber one transient-gap insert, one delete and one permanent-gap insert** 120 s later,
  about 440 B of WAL per hole for this one subscriber. With n subscribers the transient rows are paid n times. While
  gaps were open every poll carried them as an `IN (…)` list on the range read.
- Late commits were rare at this load (1-2 per run, delivered 1-3 ms after their commit returned): too few for a
  latency distribution. The p99 of in-order delivery differs a lot between runs; noise.

### Append

Single thread, one unit of work per iteration, new aggregates, 50 warm-up iterations per case, 1 000 iterations (300
for 100 events, 500 for 5 aggregates). Round trips counted in a separate pass of 100 units of work through a TCP proxy
that counts client flights. pgjdbc 42.7.14 with its defaults (`reWriteBatchedInserts=false`, `prepareThreshold=5`).

| Case | p50 (ms) | p99 (ms) | Counted statements per UoW | Non-utility | Round trips per UoW | WAL per event |
|---|---|---|---|---|---|---|
| 1 event, no expected order | 0.19 / 0.20 | 0.42 / 0.29 | 3.01 / 3.01 | 2.00 | 3.04 / 3.01 | 983 / 982 B |
| 1 event, expected order given | 0.14 / 0.14 | 0.21 / 0.19 | 2.00 / 2.00 | 1.00 | 2.00 / 2.00 | 980 / 990 B |
| 10 events, 1 aggregate | 0.29 / 0.32 | 0.36 / 0.40 | 12.00 / 12.00 | 11.00 | 3.00 / 3.00 | 961 / 963 B |
| 100 events, 1 aggregate | 1.34 / 1.58 | 3.17 / 1.90 | 102.00 / 102.00 | 101.00 | 3.00 / 3.00 | 963 / 963 B |
| 5 aggregates × 10 events | 1.39 / 1.39 | 1.58 / 1.62 | 56.00 / 56.00 | 55.00 | 11.00 / 11.00 | 954 / 948 B |

- The JDBI `PreparedBatch` of N single-row `INSERT … RETURNING` statements is **N executions but one network round
  trip**. 1, 10 and 100 events cost the same three round trips: `BEGIN` with the last-event-order lookup, the insert
  batch, `COMMIT`. Giving the expected order removes the lookup (p50 0.19 → 0.14 ms). Several aggregates cost two
  round trips per aggregate plus the commit. WAL is about 950-990 B per event in every case.
- Every case appends to **new** aggregates, so the lookup finds no row. On an aggregate that already has events JDBI
  reads ahead on pgjdbc's one-row portal and takes a second `Execute` round trip: one event without an expected order
  then costs **4** round trips, not 3 ([P0.6](#p06-jdbi-against-raw-pgjdbc) reconciled proxy and wire trace on all
  four shapes).

**OCC contended.** 8 writers on 10 pre-created aggregates for 20 s, each unit of work reading the aggregate's last
event order (`loadLastPersistedEventRelatedTo`) and appending one event after it.

| Run | Attempts | Appended | OCC conflicts | Identity values burned | per 1 000 attempts | per 1 000 successful appends | Counted statements per attempt |
|---|---|---|---|---|---|---|---|
| 1 | 870 537 | 593 740 | 276 797 | 276 797 | 318 | 466 | 2.68 |
| 2 | 963 741 | 657 312 | 306 429 | 306 429 | 318 | 466 | 2.68 |

Every lost race burned exactly one `global_order` value, a hole every subscriber of the aggregate type then waits out
for 120 s and records as rows.

### P0.0 on the dedicated server

Everything machine-independent matched the VM: round trips per unit of work 3 / 2 / 3 / 3 / 11, statements per event
in the steady shape 68.9-69.1 (10 ev/s) and 10.6 (100 ev/s), WAL 948-1 056 B per event, the idle statement rate, and
the polling-bound latency (p50 48.5-52.9 ms). What differs:

| Item | 17.12 (2026-11-14) | 17.5 (2026-11-13) | VM |
|---|---|---|---|
| OCC contended, burned per 1 000 appended | 616-625 | 619-620 (38 409-38 426 appended in 20 s) | 466 (593 740-657 312 appended) |
| Concurrent writers, late deliveries | 5 / 18 | 11 / 4 (p50 3.9 / 3.1 ms) | 2 / 1 (1.2-2.5 ms) |
| Burst run 2, WAL during catch-up | about 490 000 B | 479 760 B (run 1: 1 064 B) | 552 B |

- The OCC shape is commit-bound (15x fewer appends than the VM), and slower commits widen the window in which a
  competitor's append overlaps, so more values burn; "every lost race burns one value" holds.
- Slower commits also widen the window in which commits land out of order; transient gaps exceed holes by exactly the
  late count. Every committed event was delivered without duplicates on 17.5, 17.12 and 18.7.
- The burst run's WAL is systematic, most likely autovacuum after the 100 000-row append.
- On 18.7 P0.0's uncontended appends fell in the slow flush phase (1 event p50 4.85-6.25 ms against 1.39-1.54), and
  its first OCC run reached only about a third of its attempts; those rows are not usable.

### Reading and consequences

- **§13.1's order of gains holds: the read path dominates by more than an order of magnitude at low rates.** At
  10 events/s with 10 subscriptions the read path issues 68 statements per appended event against 3 on the write
  path; idle cost is 2 statements/s sent per subscription per instance, all of it transactions that read nothing. The
  **gates for phases 2 and 3**: fewer than ~7 reader statements per delivery at 10 ev/s and ~1 at 100 ev/s, and an
  idle cost flat in the number of subscriptions instead of 1.5 counted statements/s each.
- **The read path writes only when there are holes**, but then per hole and per subscriber: one transient insert, one
  delete and one permanent insert, ~440 B WAL per hole per subscriber, plus a resume-point update per subscription per
  second while it moves. Removing the gap tables (§4) removes the former; the resume point is §8's subject.
- **Round trips on the write path are fewer than §2.2 feared.** The `PreparedBatch` is already one round trip however
  many events it holds, so §5.1 cannot save round trips for one aggregate; its gain must come from the removed lookup
  (one round trip for a new aggregate, two for an existing one), from statement count and execution time, and for
  several aggregates from one round trip instead of two each. P0.4 is judged on that basis, and on holes.
- **Holes under contention are large** (466 per 1 000 on the VM, 616-625 on the server), which makes "a lost race
  consumes no sequence value" a material gain, not a corner case.
- **Freshness today is the polling interval**: p50 ≈ 50 ms and p99 ≈ 107 ms at the starter's 100 ms; the figure the
  wake-up tiers (§11) and phase 6 are compared against.
- **Catch-up is cheap in statements** (0.375 per event) but costs a transaction per 8-10 events; streaming catch-up
  (§10.1) mainly changes transactions per event and memory.

Not measured: more than two instances; §17's rare-type catch-up, restart storm, long writer, projection throughput,
command latency and trading-demo shapes; §17's 1 event/s rate; `CONTIGUOUS`/`FRESH`, which only exist in the new
engine.

## P0.1 xid before identity

**Question.** In the single-statement append (§5.1), is the transaction's xid always assigned before the identity
value is drawn, so that a reader that sees a `global_order` can tell from the running set whether a lower value may
still commit?

**Setup.** `AppendXidBeforeIdentitySpikeIT`, suite `xid`. Each case records whether the xid is assigned when the first
row's global order has been taken; controls (a plain `INSERT … VALUES` without the `xid` CTE) must find none, so the
check can tell the difference. On the VM it ran against 16.15, 17.5 and 18.4 containers in one run
(`-Dspike.pg.images`).

**Numbers.** VM: 72 of 72 cases on 16.15, 17.5 and 18.4. Dedicated server: 24 of 24 cases with all 8 controls null
on 17.5, 17.12 and 18.7. P0.7's table-design arms confirmed it with a cheaper trigger-based check, including the
head-table arms (the head row's lock or update assigns the xid) and an explicit `nextval`: the property depends on the
statement shape, not on how the value is drawn.

**Decision.** §5.1 is kept as written, with no separate `pg_current_xact_id()` statement. The property rests on
executor behaviour, so the check must run in CI against every supported major. A run on a PostgreSQL 16 server was
dropped on 2026-11-15: P0.1 already held on 16.15 in the VM, and it tests ordering rules, not timing.

## P0.2 Combined probe snapshot order

**Question.** Does the combined probe (§4.10: tail read plus a `pg_stat_activity` running-set sub-select in one
statement) read the running writers after its snapshot, so that no writer can commit between the two unnoticed?

**Setup.** `CombinedProbeSnapshotOrderSpikeIT`, suite `probe`: race hunts against concurrent writers, plus controls
with deliberately broken orders that the check must detect.

**Numbers.** No violation on the VM (16.15, 17.5, 18.4) or on the server (17.5, 17.12, 18.7); the controls still
detect the broken orders on every version.

**Reading and decision.** The probe holds in autocommit. It is unsafe inside a transaction that read
`pg_stat_activity` earlier, because backend status is cached per transaction; `pg_stat_clear_snapshot()` fixes that
trap (confirmed on 18.7). The probe is kept, running in autocommit or after `pg_stat_clear_snapshot()`. Caveat: the
probe must return the running set even when the tail is empty; a statement that returns no row never evaluates the
sub-select ([P0.11](#p011-re-reading-holes-in-the-tail-statement) observed it), and this was not tested.

## P0.3 Managed services (deferred)

**Question.** Is `pg_stat_activity.backend_xid` of other roles' backends visible on managed PostgreSQL services, as
the combined probe needs?

**Status.** Deferred: there is no RDS, Aurora, Azure or Cloud SQL access. Partial result: Supabase passes for its
`postgres` role, which is a member of `pg_read_all_stats`. Not checked: a role without that membership, and the
transaction pooler. Nothing may be released as supported on a managed service until P0.3 passes for that service.

## P0.4 Single-statement append

**Question.** Is the single-statement append of §5.1 better than the current engine's JDBI `PreparedBatch` append?
Judged on P0.0's basis: the batch is already one round trip, so the gain must come from the removed lookup, fewer
statements and less execution, several aggregates in one round trip, and fewer holes under contention. **Gate**
(from §18): lower latency or fewer round trips at 1, 10 and 100 events, and fewer holes under contention, outside the
noise; otherwise keep the batch shape and drop §5.4.

**Setup.** `SingleStatementAppendSpikeIT` (suite `append`; methods `#append`, `#contended`, `#sanity`; knobs
`-Dspike.append.runs`, `.rounds`, `.timeScale`), `ContendedAppendThroughputSpikeIT` (suite `contention`; knobs
`-Dspike.contention.runs`, `.seconds`, `.arms`, `.spinMicros`) and `AppendEncodingAttributionSpikeIT`. All arms write
to tables created by `SeparateTablePerAggregateTypePersistenceStrategy` inside the same unit of work with the same
serializer, and register the resulting `PersistedEvent`s:

- **A**: `PostgresqlEventStore.appendToStream`, today's engine (lookup when no expected order is given, then a JDBI
  `PreparedBatch` of single-row `INSERT … RETURNING global_order`).
- **C**: the same two statements through raw pgjdbc on the unit of work's connection.
- **B**: the §5.1 statement through raw pgjdbc: `WITH xid, head, ok INSERT … SELECT … FROM ok CROSS JOIN unnest(…)
  WITH ORDINALITY RETURNING global_order, event_order`. One aggregate is one `executeQuery`; five aggregates are one
  pgjdbc batch of five statements, each update count checked (zero = optimistic-concurrency conflict).
- Contended extras: B with `pg_advisory_xact_lock(hashtextextended('<table>:<aggregate id>', 0))` as the first CTE
  (§5.3 as written); B with the same lock as a separate statement before the append; B with
  `ON CONFLICT (aggregate_id, event_order) DO NOTHING`.
- Encoding variants of B: `TEXT_ARRAYS` (the base), `BINARY_ARRAYS`, `JSONB_ONE` (one `jsonb` array via
  `jsonb_to_recordset`), `SCALAR_1` (one event, scalar parameters, no `unnest`), `NO_HEAD` (diagnostic), each also on
  `json` columns.

The uncontended shape is single-threaded, new aggregates, with and without an expected order, three measured rounds
with rotated arm order. The contended shape is P0.0's: 8 writers on 10 aggregates for 20 s, lookup then append, arms
in palindrome order. The `sanity` check confirmed that B writes rows identical to the engine's, that `fetchStream`
reads them back, that a stale expected order returns zero rows and leaves the identity sequence untouched, and that a
batch of three aggregates with the middle one stale returns update counts `[1, 0, 2]`.

### Uncontended

Dedicated server (17.12): B against A is −30-35 % at 100 events (4.19-4.25 against 6.00-6.45 ms), −37-44 % for
5 aggregates, −7-9 % at 10 events even with an expected order, and a tie at 1 event; every 1-event latency
(1.3-1.7 ms) sits on the commit flush. The 18.7 append rows fell in the slow flush phase and are not usable.

On the **VM** the same picture held (B about 35 % lower at 100 events, about half of A's latency for 5 aggregates,
with 2 round trips against 6 or 11, and a tie at 1 and 10 events with an expected order). WAL per event is the same in every arm (~955-985 B). B's gain at 100 events and 5 aggregates (~0.4-0.7 ms) is much
larger than the C-A difference, so it is the statement shape, not the library. With scalar parameters (`SCALAR_1`)
the one-event, expected-order case became a tie or slightly better than A (VM 0.121-0.131 against 0.130-0.134 ms),
where text arrays were a tie or slightly worse.

### Contended

| Arm | 17.12 appended/s | 18.7 appended/s | Burned per 1 000 appended (17.12 / 18.7) | VM appended/s (thousands) | VM burned per 1 000 |
|---|---|---|---|---|---|
| A (engine) | 1 787-1 876 | 1 756-1 947 | 616-629 / 614-620 | 34.5-34.7 | 463-465 |
| C (raw, today's SQL) | 1 967-1 984 | 1 923-1 928 | — | 30.6-32.9 | — |
| B | 1 917-1 931 | 1 829-1 910 | 521-531 / 523-530 | 23.6-24.6 | 248-270 |
| B + `ON CONFLICT DO NOTHING` | 1 869-1 989 | 1 880-1 960 | 549-553 (17.5) | 14.0-17.5 | 242-263 |
| B + advisory lock as first CTE | — | — | 514-520 (17.5) | 21.4-22.1 | 259-264 |
| B + advisory lock as separate statement | 0 burned at −3 to −5 % against A | — | 0 | 11.5-11.9 | 0 |

On the server zero-row results are about 6 % of attempts. Server CPU per attempt (cgroup, 17.12, P0.4 contention):
B 534-548 against A 441-463 µs, recorded execution 108-110 against 71-77 µs.

### Reading

- **On the server the throughput gap disappears.** With a real flush every arm commits once per attempt and waits on
  the WAL, so B/A ≈ 0.98 and `ON CONFLICT` is no longer worse. On the VM B appended about 30 % less than A; that gap
  was real on the VM (same work per attempt, conflict rates within 2-3 points, not lock waits, not planning, not the
  client) and came from B's few extra µs of executor work amplified by the VM's idle and wake-up cost
  ([P0.9](#p09-unattributed-server-cpu)). B's extra server CPU on the server is 2-3x its execution difference.
- **B does not remove holes, and on the server it no longer halves them** (−12-16 %). Under READ COMMITTED `head`
  reads the statement's snapshot, so a competitor that has inserted but not committed is invisible: the check passes,
  the insert waits on the competitor's index entry and fails with a unique violation when it commits. Only a
  competitor that committed *before* the statement started yields zero rows. So the split between clean zero-row
  conflicts and burned values depends on the competitor's commit window relative to the statement. On the VM,
  lengthening the client's lookup-to-append gap alone moved BASE's burned values from 289-299 to 219-225 (50 µs
  busy-wait) and 181-184 (park) per 1 000 (P0.7's gap (b) variants); on the server, slow commits dominate the window,
  so most conflicts burn a value either way.
- **The advisory lock as the first CTE does nothing**: the statement's snapshot is taken before it waits for the lock.
  **As a separate statement before the append it gives zero burned values**, at −3 to −5 % on the server (a third of
  A's throughput on the VM), one more round trip, and serialised writers per aggregate until commit.
- **No parameter encoding closed the VM gap.** The best (scalar for one event, one `jsonb` parameter) recovered about a
  quarter of it; binary arrays were no better than text arrays. Without `head` the statement burns as many values as A
  (467-474 per 1 000 on the VM), so the reduction comes from the check, not the encoding. Executor conversion per
  event: jsonb 1.019 / json 0.473 / text 0.213 µs on 17.12, 0.888 / 0.420 / 0.190 on 18.7 (11-13 % cheaper); the
  jsonb-over-json cost is about 0.73 µs per event. `json` against `jsonb` with these small payloads changes neither
  picture outside the noise.

**Decision.** Gate partly met: fewer holes under contention, and lower latency or fewer round trips at 100 events,
for several aggregates, and whenever no expected order is given; not met at 1 and 10 events with an expected order
(tie). B was never worse than A outside the noise. **§5.1 is kept** for the new engine, with corrections:

- `head` must use `coalesce(max(event_order), -1)` (event orders start at 0; `NO_EVENTS_PREVIOUSLY_PERSISTED` is −1).
- `event_order` is assigned on the server, so a `PersistableEventMapper` called without an expected order gets only a
  provisional order; the `PersistedEvent` is built from `RETURNING event_order`.
- "A lost race consumes no sequence value" holds only against an already committed append; holes stay a normal event
  under contention and the read path must handle them as §4 designs.
- §5.3 is corrected: the advisory lock must be a separate statement before the append. It stays opt-in, for
  aggregates known to be hot.

## P0.5 Model of the subscription state machine

**Question.** Does the redesign's state machine hold: the tail reader with its candidates and watermark, both
delivery modes, attach and detach, catch-up and the resume point? The gate is that a model checks invariants I1-I3,
I12 and I13 (§16). No engine code existed; the model was there to find design flaws before any was written.

**Setup.** Plain Java with JUnit and AssertJ, package `…eventstore.postgresql.spike.model` in the test sources of
`components/postgresql-event-store`: `SubscriptionModel` (state, enabled steps, step semantics, invariant checks,
mutants), `ModelExplorer` (breadth-first exhaustive exploration, so every counterexample is a shortest one, and seeded
random walks) and `SubscriptionStateMachineModelTest` (configurations, checks, scripted scenarios). No Docker; there is
no TLA+ specification (no TLA+ tools were available offline).

```bash
mvn -o test -pl components/postgresql-event-store -Dtest=SubscriptionStateMachineModelTest \
    -Dspike.run=true -Dsurefire.failIfNoSpecifiedTests=false
```

Knobs: `-Dspike.model.maxStates` (default 5 000 000), `-Dspike.model.mutantMaxStates` (default 1 000 000),
`-Dspike.model.seeds` (random walks per configuration, default 200 000). The whole class takes about 17 minutes (40
tests).

**What is modelled.** Writers are transactions in atomic steps (take an xid; insert, deriving `event_order` from
committed state and taking the next `global_order`, as §5.1 does; commit, or abort), with several events per writer
(each taking its own `global_order`, so other writers' values fall between them), a second aggregate per writer,
aborts, sequence jumps and long writers; the unique constraint is checked at commit. The tail reader has a cursor,
unresolved holes, candidates `(S, X)` and a watermark; one probe is two atomic steps in the order P0.2 proved (the
snapshot's rows, then the running set, which may have changed since), and a separate `reader.holeRead` step reads the
holes of retired candidates once. Subscriptions are `FRESH` or `CONTIGUOUS`, with a periodic save of §4.7's formula
or progress in the handler's transaction, a bounded buffer and a durable resume point; they are *detached*
(catch-up of `(position, watermark]`), *attaching* (registered with the reader) or *attached* (after the bridge read
of `(position, reader cursor at attach]`), and a publication to a full buffer detaches them. A crash wipes all memory.
Not modelled: several aggregate types, tenants, wake-up timing, the catch-up permit (I15), fencing (I5), `resetFrom`
(I6), the hand-over from the old engine (I8).

**Invariants**, checked after every step: **I2** (no `A#n` first delivered after an `A#m` with `m > n` within one
process lifetime); **I3** (no order below the durable resume point is held by a running writer, and every committed
matching order below it has been handled); **I13** (the same for a detached subscription's in-memory position);
**I12** (a `CONTIGUOUS` subscription never delivers above the watermark, applies in strictly increasing order, and its
durable projection is exactly the committed matching orders below its resume point, each applied once: a crash at any
point is safe); **I9** (committed rows above the cursor mean the reader has an enabled step); **M0** (the watermark
never moves backwards). **I1**: in every state where all writers have finished, a copy driven to quiescence must have
handled every committed matching event in every subscription. I14 (page fallback) is covered through I12's fold.

**Results.** Common bounds: tail `LIMIT 2`, buffer 2, page 2, catch-up page 1, at most one crash, two aggregates, two
event types. With F1's hole re-read every configuration holds every invariant:

- Exhausted: `small` (21 214 states), `jump` (421 690, up to two sequence jumps), `core-no-jump` (2 711 494 states,
  9.2 M transitions), `pages` (3 939 152), `deadLetter` (4 195 941), `multi-two-aggregates` (73 837), `multi-pages`
  (406 176), `multi` (9 067 626 states, 31.8 M transitions).
- Bounded: `core` with a sequence jump (8 000 000 states, every state to depth 20).
- Random walks, seeds 1-200 000: `core`, `pages`, `large` (five writers, buffer 1, two crashes, two jumps; 11.6 M
  steps), `multi-wide`, `multi-large` (11.9 M steps).
- **As written** (no hole re-read): `core` and `multi` **fail I2** (finding F1); nothing else fails.

**Mutants.** Each invariant was seen to fail against at least one deliberately broken variant, on its original base and
again on `multi`:

| Mutant | What is broken | Invariants that fail |
|---|---|---|
| `VALUE_BEFORE_XID` | the writer takes its `global_order` before its xid (§4.3) | I13, I2, I3 |
| `VALUE_BEFORE_EVENT_ORDER` | the value is taken before `event_order` is derived from committed state (§4.5, §5.1) | I2 |
| `X_BEFORE_S` | the running set is read before the tail read (§4.10 reversed) | I13 |
| `CACHED_STAT_ACTIVITY` | the probe reuses a running set cached earlier in the same transaction (P0.2) | I13, I3, I12 |
| `EMPTY_TAIL_NO_RUNNING_SET` | an empty tail read returns no running set, taken as "nobody running" | I13, I3 |
| `WATERMARK_BEFORE_HOLE_READ` | the watermark advances at retirement, before the hole read | I3, I12 |
| `READER_RESTART_AT_HEAD` | a restarted reader puts its watermark at the head, with no initial candidate | I13 |
| `RESUME_AT_HIGHEST_HANDLED` | the resume point is the highest handled order + 1 | I3 |
| `RESUME_AT_CURSOR` | the resume point is the reader's cursor + 1 | I3, I12 |
| `DETACH_AT_WATERMARK` | after overflow, catch-up resumes from the watermark | I13 |
| `ATTACH_AFTER_BRIDGE_READ` | reads up to the head first, registers with the reader after | I3, I12, I1 |
| `CATCHUP_ABOVE_WATERMARK` | filtered catch-up reads up to the head and advances there on a short page | I13 |
| `CONTIGUOUS_ABOVE_WATERMARK` | `CONTIGUOUS` releases buffered events above the watermark | I12 |
| `CONTIGUOUS_PROGRESS_OUTSIDE_TX` | `CONTIGUOUS` applies per event and saves progress periodically in its own transaction | I12 |
| `PAGE_FALLBACK_PARTIAL_COMMIT` | a failing page commits the events before the failure, then replays per event | I12 (the I14 case) |
| `BACKPRESSURE` | a full buffer blocks the reader instead of detaching the subscription | I9, I1 |

Notation in the traces below: `w0.insert` shows as `A#0 @ g1`, event order 0 of aggregate A at `global_order` 1.

### F1. As written, FRESH breaks per-aggregate order (I2)

The design delivered a late commit below the cursor only from the hole read that follows its candidate's retirement.
§4.5's "A#5 is visible no later than A#6" is true, but the tail read never looks below its cursor, so being visible
does not get A#5 delivered. Shortest counterexample (`core`, 11 steps plus a drain):

```
w0.xid, w0.insert            w0 holds A#0 @ g1, uncommitted
w1.xid                       w1 (aggregate A) takes its xid
w2.xid, w2.insert, w2.commit B#0 @ g2 committed
reader.probe.snapshot        the probe's snapshot sees g2 only
w0.commit                    A#0 @ g1 committed (after the snapshot)
w1.insert, w1.commit         A#1 @ g3: its order is derived from A#0, now committed
s0.attach                    FRESH subscription attaches
(drain) probe.runningSet     cursor 2, hole {1}, candidate (S=2, X={}): every writer has finished
        probe                reads g3 = A#1 and publishes it; the candidate retires
        holeRead             finds A#0 @ g1 and publishes it as a late commit
        s0 handles g2, g3, then g1: A#0 first delivered after A#1  -> I2
```

Delivering the hole read's rows ahead of the same probe's batch is not enough: in the long-writer shape (scripted test
`design_as_written_fresh_long_writer_scenario`) an unrelated writer that took its xid before the probe keeps the
candidate from retiring at all, so A#1 is delivered and A#0 waits for the unrelated writer. With several events per
transaction `multi` fails the same way, both of the late writer's events arriving after A#2:

```
w0.xid, w0.insert, w0.insert2   w0 holds A#0 @ g1 and A#1 @ g2, uncommitted
w1.xid, w1.insert, w1.commit    B#0 @ g3 committed
w2.xid                          w2 (aggregate A) takes its xid
reader.probe.snapshot           the probe's snapshot sees g3 only
w0.commit                       A#0, A#1 committed together (after the snapshot)
w2.insert, w2.commit            A#2 @ g4, derived from A#1
s0.attach                       (drain) A#2 is read above the cursor; A#0, A#1 arrive from the hole read  -> I2
```

**Fix**: the tail statement also re-reads the outstanding holes (with ranges for a sequence jump) and publishes what it
finds in `global_order` ahead of the new rows. A#n+1 can only exist once A#n has committed, so any snapshot that sees
A#n+1 also sees A#n; within one transaction the identity values grow with the event order, so the argument carries
over to several events per transaction. The hole read after retirement is still needed, because a writer can commit
between the statement's snapshot and its read of the running set. `CONTIGUOUS` is unaffected. P0.11 measured the
statement form: a `UNION ALL` branch, not an `OR`.

### F2. The watermark needs two clarifications

- §4.2 defined the watermark as "the highest `S` of a retired candidate such that no older candidate is still
  outstanding", which does not say what it is once the cursor has moved past the last candidate with no new hole. The
  model uses `min(cursor, lowest unresolved hole − 1)`, where a hole is unresolved until its candidate has retired
  *and* its hole read has run.
- Read literally, the definition lets the watermark advance at retirement, before the hole read. `WATERMARK_BEFORE_HOLE_READ`
  shows that loses events: a `CONTIGUOUS` subscription commits a page past the hole before the late commit is published
  (I12), and a crash between retirement and the hole read leaves resume points past an unhandled event (I3). The
  watermark must move only after the hole read has returned and its rows have been published.

### F3. §6's attach rule cannot be met behind a long writer

§6 attached a subscription "within its buffer's distance of the tail", and §10.1 caps every catch-up read at the
watermark. While a long writer holds the watermark down, a subscription that is behind catches up to the watermark and
stops; its distance to the reader's cursor stays above the buffer size, so it never attaches. Scripted test
`attach_rule_relative_to_cursor_starves_fresh_behind_a_long_writer`: a long writer at g1, three commits at g2..g4, the
reader at cursor 4 with watermark 0; the subscription has no enabled step at all. The model attaches when the catch-up
position has reached the watermark, and the bridge read covers `(position, reader cursor]`, which the buffer does not
bound. For `CONTIGUOUS` a long writer can then make a subscription attach, overflow and detach repeatedly, one bridge
read each time: a cost, not a loss, and not measured.

### F4. Where a restarted tail reader starts was unspecified

Starting at the head with the watermark at the head (`READER_RESTART_AT_HEAD`) loses events:

```
w0.xid, w0.insert            w0 holds g1, uncommitted
w1.xid, w1.insert, w1.commit g2 committed
CRASH+restart                reader starts at the head: cursor 2, watermark 2
s0.catchUp(<=2)              the subscription's position passes g1, still held by w0  -> I13
```

Rule: start at the head with one initial candidate `(head, running set)`, keep the watermark unknown until it retires,
and let no subscription catch up past the last known watermark or attach before then. With no writer running at the
restart the watermark is the head at once. §10.1's "computes with its own candidate when no tail reader runs" needs the
same rule.

### F5. The position a detaching subscription resumes from was unspecified

Resuming from the watermark after overflow (`DETACH_AT_WATERMARK`) loses events that were buffered but not handled
(I13). The model detaches at §4.7's formula, `min(watermark + 1, lowest buffered order, the order that did not fit) − 1`.
An open page of an in-transaction subscription is left out of that position and, if the page then rolls back, its
events go back into the buffer; a `CONTIGUOUS` subscription must drop anything at or below the last order it applied,
because the catch-up that follows overlaps what the open page committed.

### F6. Back-pressure would deadlock CONTIGUOUS, not just slow it

`BACKPRESSURE` fails I9 and also I1 at quiescence: a `CONTIGUOUS` buffer full of events held back above the watermark
blocks the reader, and the watermark can only advance through the reader. Every writer has finished and nothing moves
again. §6's rule that a slow subscription detaches rather than blocks the reader is load-bearing for `CONTIGUOUS`
correctness, not only for latency.

**Confirmations.** The other mutants confirm the design: xid before value and `event_order` from committed state are
both necessary; the running set must be read after the tail snapshot and not from a cached backend-status snapshot, and
an empty tail read must still return it; the resume point must be §4.7's formula, not the highest handled order nor the
reader's cursor; catch-up stops at the watermark; attach registers before its bridge read; `CONTIGUOUS` must not
deliver above the watermark and must save progress in the handler's transaction; a failing page rolls back as a whole.

**What the model cannot show**: timing and liveness (no clock; the `CONTIGUOUS` freshness claim of §7.3 is untouched);
PostgreSQL's behaviour (the prerequisites are assumptions that P0.1 and P0.2 measured); anything beyond the bounds
(exhaustive results hold for the configurations above; larger ones were only sampled); several aggregate types per JVM;
threads, the catch-up permit, fencing, resets, the hand-over.

**Decision** (2026-11-15). F1-F6 were accepted as recommended, F1 with P0.11's `UNION ALL` hole re-read.

## P0.6 JDBI against raw pgjdbc

**Question.** For the same statements (A's one-event insert batch, the engine's `loadLastPersistedEventRelatedTo`
lookup, B's single statement and the tail read `SELECT … WHERE global_order > ? ORDER BY global_order LIMIT ?`), how
do JDBI and raw pgjdbc compare on client CPU per statement, uncontended latency and contended throughput? And why did
today's SQL run about 10 % slower through raw pgjdbc (P0.4's arm C) than through the engine (arm A) on the VM?

**Setup.** `JdbiVsRawPgjdbcSpikeIT`, suite `jdbc`, with four tests: `protocol_trace` (pgjdbc's wire messages for one
warmed-up unit of work of arm A and arm C), `statements` (each statement through both libraries on one pooled
connection, same SQL text, same parameter types and row mapping, 20 000 calls per pass), `contended` (8 threads on 10
aggregates; lookup + insert `UOW_A`, lookup + B `UOW_B`, a 100-row tail read `TAIL`; each in NORMAL mode and in SPIN
mode, a 50 µs client busy-wait between lookup and append) and `round_trip_reconciliation` (the counting proxy and the
wire trace on the same units of work, `RT-RECONCILE` lines in `p0-6-jdbc.txt`). JDBI variants: `JDBI` (the engine's
usage, lookup with `setFetchSize(1)` and `findOne()`), `JDBI_FIRST` (`findFirst()`), `JDBI_NO_FETCH_SIZE`. pgjdbc
42.7.14, JDBI 3.55.0, HikariCP 7.0.2.

**Findings that hold on both machines.**

- **Arm C's VM deficit is not a JDBC difference.** A and C send the same protocol messages, message for message, to
  the same server-side prepared statements on the same pooled connection, with identical driver settings
  (`prepareThreshold` 5, `preparedStatementCacheQueries` 256, `reWriteBatchedInserts` false). The deficit appeared
  only when the VM's CPUs were allowed to go idle; with a 50 µs spin C was about 10 % *faster* than A at equal server
  CPU. It was the idle and wake-up effect of [P0.9](#p09-unattributed-server-cpu).
- **JDBI's own overhead is a few µs of client CPU per statement** (VM): +6-8 µs for the one-event insert batch,
  +2-5 µs for the lookup without a fetch size, about +6 µs for B, +3-6 µs for a 100-row tail read. Server execution
  is identical. On the server the client CPU difference per call is +15-34 µs, scaling with the slower CPU.
- **One configuration interaction costs a whole round trip.** JDBI's `ResultSetResultIterator.next()` reads one row
  ahead. With `setFetchSize(1)` inside a transaction pgjdbc uses a one-row portal, so the read-ahead is a second
  `Execute` round trip (`PortalSuspended`, then `Execute` returning `SELECT 0`). `findOne()`, `findFirst()` and
  `first()` all do it. It costs about +40 µs per lookup on the VM and about +95-105 µs on the server; without the
  fetch size JDBI is within 2-6 µs of raw. In the current engine `loadLastPersistedEventRelatedTo`, `loadEvent` and
  the `MAX`/`MIN` order reads all combine `setFetchSize(1)` with the read-ahead.
- **Round trips reconciled.** Proxy and trace agreed on every unit of work (50 of 50 per shape):

  | Shape | Round trips |
  |---|---|
  | New aggregate, the engine's lookup finds no row (P0.0 "1 event, no expected order") | 3 |
  | New aggregate, no lookup (P0.0 "expected order given") | 2 |
  | Existing aggregate, explicit `loadLastPersistedEventRelatedTo`, append with that order | 4 |
  | Existing aggregate, no expected order: the engine's lookup finds a row | 4 |

  P0.0's and P0.4's round trips stand for the shapes they ran (new aggregates). Appending to an aggregate that
  already has events, the usual command-handler case, costs one round trip more whenever the lookup runs.

**Contended numbers.**

| | 17.12 | 18.7 | VM |
|---|---|---|---|
| Raw against JDBI, lookup + insert | raw +6-16 % | raw +10-15 % | SPIN raw +11-20 %; NORMAL JDBI 3-10 % ahead or a tie |
| JDBI's extra server CPU per attempt | +39-70 µs for +2-6 µs more execution | +20-60 µs | SPIN 4-6 µs |
| Tail read, 100 rows | 4 811-5 117 reads/s, execution 653-670 µs | 5 181-5 522 reads/s, execution 555-579 µs | server-bound, tie |

On 17.5 (2026-11-13) raw led by +13-15 % (NORMAL) and +12-14 % (SPIN) for lookup + insert, and +10-14 % / +9-12 % for
lookup + B. **The SPIN verdict holds and the NORMAL flip disappears on the server**, confirming that the VM's NORMAL
JDBI lead was its idle effect. JDBI's extra server CPU fits its extra round trip. The tail read is about 15 % cheaper
on 18. The `JDBI_NO_FETCH_SIZE` and `findFirst` arms did not run on the server, so the fetch-size fix is not
re-tested there.

**Decision.** JDBI's overhead is not material. The engine moves to raw pgjdbc for the capability reasons in §13.2,
not for speed. A raw engine must still use fetch sizes deliberately: `setFetchSize` only for the streaming reads of
§10.1, never for single-row reads.

## P0.7 Table design options

**Question.** The redesign may change the event table itself. What do layouts the current strategy cannot express
cost and save: `json`/`bytea` payloads, no `UNIQUE(event_id)`, `uuid` event ids (v4, v7), a stream head table, a
narrower row, an explicit `nextval`, and their combination? Findings only; P0.7 has no gate row in §18.

**Setup.** `TableDesignOptionsSpikeIT`, suite `table-design` (knobs `-Dspike.design.runs`, `.rounds`, `.seconds`,
`.arms`, `.lookupDelayMicros`). Every arm creates its own table and writes through raw pgjdbc with one statement
shape: the §5.1 append with binary arrays expanded by `unnest`, the `xid` CTE first, always with an expected order.
**BASE** has today's columns and indexes (`text` ids, `integer` revision, `jsonb` payload and metadata,
`UNIQUE(aggregate_id, event_order)`, `UNIQUE(event_id)`, identity `global_order`); each other arm changes one thing.
Payload ~120 B, metadata ~35 B, the ~100-character `FQCN:` event type. Per run: correctness checks; uncontended appends
(new aggregate per unit of work, 1/10/100 events) and an existing-stream shape; a read of 100 events; 8 writers on 10
aggregates for 20 s per arm in palindrome order.

- **Head table** (`aggregate_id text PRIMARY KEY, version bigint`, `fillfactor = 50`). *Guarded `UPDATE`* in the
  append statement: `WITH h AS (UPDATE streams SET version = version + n WHERE aggregate_id = $id AND version =
  $expected RETURNING version - n) INSERT … SELECT … FROM h CROSS JOIN unnest(…)`; a loser waits for the winner's
  commit on the head row, re-checks the `WHERE` (EvalPlanQual), updates nothing and takes no global-order value.
  *`SELECT … FOR UPDATE` first*: one more round trip.
- **Narrow row**: fixed-width columns first, metadata, causation, correlation and tenant merged into one `jsonb`, event
  types interned as `smallint` in a `td_event_types` table.
- **COMBO_ALL**: guarded-`UPDATE` head table, no `UNIQUE(event_id)`, `uuid` (v4) event, causation and correlation ids,
  interned `smallint` event type and revision, `json` payload and metadata, columns in alignment order, only
  `UNIQUE(aggregate_id, event_order)`. **COMBO_ALL_BYTEA** with `bytea` payload; **COMBO_ALL_NO_HEAD** without the
  head table; **COMBO_ALL_SCALAR** binding scalar parameters for one-event appends.

**Correctness**, every arm and run on the VM and the server: a stale expected order returned zero rows and took no
global-order value; event orders stayed contiguous from 0; every head row equalled `max(event_order)` (0 stale rows in
all 20 arms on 18.7); 0 burned values in the head-table arms; the xid was assigned before the first global order.

### Contended, dedicated server

Appended per second (8 writers, 10 aggregates):

| Arm | 17.12 | 18.7 |
|---|---|---|
| BASE | 2 164-2 314 | 2 142-2 265 |
| HEAD_GUARDED | 2 209-2 439 | 2 286-2 396 |
| HEAD_FOR_UPDATE | 2 078-2 209 | 2 082-2 189 |
| COMBO_ALL | 2 341-2 485 | 2 263-2 353 |
| COMBO_ALL_SCALAR | 2 370-2 577 | 2 425-2 541 |
| COMBO_ALL_BYTEA | 2 291-2 495 | 2 368-2 451 |

On 17.12 the head-table arms used 309-381 µs of server CPU per attempt against BASE's 410-432. Without a head table
the arms burned 560-589 values per 1 000 (17.5), and the 50 µs delay after the lookup changed nothing: commit time,
not the lookup-to-append gap, sets the conflict window on this machine. Uncontended, server execution for 100 events
went from BASE 1 412-1 424 µs down to COMBO_ALL_BYTEA 893-910 µs, and reading 100 events with `json`/`bytea` took
0.27-0.38 against BASE's 0.43-0.53 ms (17.5): ranking and direction as on the VM, absolute about 2.8x. WAL per event
dropped from 892-895 B (BASE) to 551-563 B (COMBO), within a few bytes of the VM in every option.

### Per option (VM, `fsync=off`, sizes and relative costs)

- **`json` or `bytea` payload**: no contended difference; uncontended `bytea` < `json` < `jsonb` at 10 and 100 events
  (100 events execution 424-430 / 450-470 / 490-496 µs); reads of 100 small events ~35 % faster (0.117-0.119 against
  0.183-0.184 ms); rows ~32 B and WAL ~22-24 B per event smaller. Not measured: `jsonb`'s SQL querying and the admin
  UI's use of it.
- **No `UNIQUE(event_id)`**: the largest single saving. 100-event appends 0.585-0.592 against 0.712-0.725 ms
  (execution −26 %), WAL −110 to −130 B per event, size −73 B per row, about +5 % contended. It gives up the
  database-level guarantee that an event id is unique, and `findEvent(EventId)` needs another path.
- **`uuid` event ids**: ~70 B per row and ~55-60 B WAL per event saved against `text`. v7 against v4 showed no
  difference at these table sizes; the index locality v7 buys on a large `UNIQUE(event_id)` index was not exercised.
- **Head table**: zero burned values in every window; every conflict a zero-row result. On the VM it cost throughput
  (guarded `UPDATE` −8-18 %, `FOR UPDATE` about −30 %); on the server it costs none. Uncontended it costs ~280 B more
  WAL per new one-event stream and ~130 B more per one-event append to an existing stream; `FOR UPDATE` first costs
  one more round trip. Head updates were almost all HOT. It makes the head table the authority for a stream's
  version: every write path, migration and repair tool must keep it in step, and deleting a stream touches both
  tables.
- **Narrow row**: heap −115 B per row (431 against 546), WAL −87 to −95 B per event, latency and throughput within
  BASE's noise. Nearly all of it is the interned event type. Costs: rows are not readable without the type registry,
  causation and correlation ids lose their own columns, and the registry must be cached and kept consistent across
  instances.
- **Explicit `nextval`**: no difference in any shape.
- **Combination** (VM): heap 304 B per row against 546 (−44 %), table plus indexes 430-434 against 745-747 B (−42 %),
  WAL per event at 100 events 554-556 against 893-894 B (−38 %), 100-event appends −18 % (execution −26 %), reads of
  100 events ≈ −38 %. On an existing stream COMBO_ALL is at or below BASE at 1 event and ~6 % faster at 10.

**Reading.** With durable commits the head table costs no throughput, and COMBO_ALL (+8 % on 17.12, +4 % on 18.7) and
COMBO_ALL_SCALAR (+12 % on 17.12) beat BASE in every window while using less server CPU. The VM's ranking (COMBO_ALL
−15 % against BASE) was an artefact of `fsync=off` and the shared CPUs. These are findings for the table design of
phase 1, not decisions.

Not measured: many aggregates with little contention, large tables (v7 locality, page splits, autovacuum), large
payloads (covered for `json`/`jsonb`/`bytea` by the payload serialization measurements), a
`(event_type, global_order)` index, partitioning.

## P0.9 Unattributed server CPU

**Question.** Where does PostgreSQL CPU per attempt go that `pg_stat_statements`' recorded execution does not show?
On the VM, every form of B carried about 15-20 µs more server CPU per attempt outside recorded execution than A, and a
client that busy-waited between lookup and append cut server CPU per attempt from 93-99 to about 55 µs.

**Setup.** `UnattributedCpuSpikeIT`, suite `cpu` (knobs `-Dspike.cpu.runs`, `.seconds`, `.arms`, `.perf`,
`.pgCpus`). Raw pgjdbc, engine-shaped table, 8 writers on 10 aggregates, the same lookup in every arm; only the append
statement and the client mode differ. Statement arms: `A_VALUES` (`INSERT … VALUES`), `A_SELECT`, `B_NO_CTE` (head
check as a scalar sub-select), `B_NO_XID`, `B` (P0.4's `SCALAR_1`), `B_RET1` (one `RETURNING` column), `B_COMMIT0`
(zero-row result committed). Client modes: `NORMAL`; `SPIN` (a 50 µs busy-wait between lookup and append); `BURNER`
(container only: a privileged sidecar keeps every CPU from executing WFI). Each window records the container's cgroup
CPU (user and system), per-backend schedstat (container only), recorded execution, busy fraction and IPIs.

**VM finding.** About half of the PostgreSQL CPU per contended attempt on the VM was idle and wake-up cost. Removing
idle (`SPIN` or `BURNER`) cut the CPU outside execution from 59-74 to 32-37 µs per attempt, system time from 32-40
to 15-19 µs and wake-up IPIs from 1.5-2.4 to 0.01-0.14 per attempt, and B's excess outside execution over A
disappeared. A `perf` profile put B's NORMAL-mode excess in user space, not the kernel: B's few extra µs of executor
work became 1.5-2 times as expensive on a CPU that keeps going to sleep. No single B-only suspect (`INSERT … SELECT`,
CTEs, `pg_current_xact_id()`, two `RETURNING` columns, rolling back zero-row results) carried an excess; only the head
check moved anything (+4 µs execution). Pinning client and database to separate CPU sets made everything worse
(every wake-up remote, 102-112 µs per attempt). Consequence: absolute CPU per attempt on the VM is not a property of
the statement, and ratios between arms are inflated whenever the arms differ in throughput.

**Dedicated server** (container cgroup, so every PostgreSQL process and nothing of the client):

| | 17.12 | 18.7 |
|---|---|---|
| PostgreSQL CPU per attempt | A 338-354 µs (user 219-235, sys 117-120); B 380-390 µs (user 262-270, sys 116-120) | A 343-359 µs, B 387-405 µs |
| Recorded execution | A 63-64, B 79-80 µs | A 63-65, B 79-80 µs |
| Outside recorded execution | A 274-290, B 301-310 µs | A 280-295, B 308-326 µs |
| Throughput (17.5 fast phase) | A 2 260-2 391, B 2 164-2 273/s (−8 % to +2 %) | |

**Reading.** The CPU outside recorded execution does not shrink to a few µs, and spinning changes nothing, so the VM's
idle explanation does not apply on this host; the gap does not depend on the major version. `pg_stat_statements`
records no execution for `COMMIT`, so the gap includes the commit with its WAL flush, the group-commit wake-ups and
the socket I/O, which fits the identical 117-120 µs of system CPU in every arm. P0.12's `SELECT 1` costs 40-52 µs of
server CPU for about 1 µs of execution, so at about three round trips per attempt a per-round-trip floor explains
roughly half the gap. Background processes are in the cgroup too and were not separated. B's extra server CPU (+26-50
µs, all user CPU) is 2-3x its recorded execution difference (+15-17 µs), not about equal as the VM suggested; B's
execution excess keeps the VM's 1.2x ratio, so that part is the statement's.

**Open.** How the roughly 280 µs splits between commit flush, protocol and background processes. It needs per-backend
CPU (which reads `NaN` on an external server) or the commit timed separately.

## P0.10 Wake-up notification cost

**Question.** What does the tier-1 wake-up of §11 cost the append path? §11 as first written has the appending unit
of work issue `pg_notify('essentials_eventstore', '<table>')` inside the transaction. A notifying commit takes
PostgreSQL's cluster-wide NOTIFY lock (`pg_locks` object, classid 1262, objid 0) and holds it through its WAL flush,
so notifying commits run one at a time and lose group commit; the shard-owned queue had already measured this
([`durable-queue-measurements.md`](../design/durable-queue-measurements.md) §3.14).

**Setup.** `WakeupNotifyCostSpikeIT` with `NotifyTapSocketFactory`, suite `notify` (knobs `-Dspike.notify.shapes`,
default `1,8,20,8@10`; `.arms`; `.seconds`, default 8; `.fsync`, ignored on an external server; `.listener`). The
current engine's append, one event per unit of work, to new aggregates or 8 writers on 10 aggregates (lookup then
append), Hikari pool 24. Arms:

- `NONE`: no notification.
- `IN_TX`: `SELECT pg_notify(?, ?)` on the unit of work's connection before commit (§11 as written).
- `AFTER_COMMIT`: after the commit returns, the writer queues the payload on one per-JVM notifier, which sends
  whatever accumulated while its previous send was in flight as one `SELECT pg_notify(?, key) FROM unnest(?::text[])`
  in autocommit on its own connection (a copy of the queue's `ShardWakeupNotifier`). Identical payloads coalesce.
- `AFTER_COMMIT_DIRECT`: after commit, each writer sends its own `pg_notify` in autocommit, without coalescing.

One `LISTEN` connection drains in every arm with pgjdbc's `getNotifications(50)`. Wake latency is measured from a
tagged append's commit returning to the tagged notification's bytes arriving on the listener's socket (the tap), and
separately through pgjdbc's API. Every notification sent was received in every window.

**Numbers.** Dedicated server, against `NONE`:

| Arm | 17.12 | 18.7 |
|---|---|---|
| `IN_TX`, 1 writer | −1 to −9 % | — |
| `IN_TX`, 8 writers | −76 to −79 % | −75 / −76 % |
| `IN_TX`, 20 writers | −81 to −83 % | −84 / −85 % |
| `IN_TX`, 8 on 10 aggregates | −34 to −47 % | — |
| `IN_TX` ceiling at every writer count | about 1 000-1 160/s | 1 002-1 133/s |
| `AFTER_COMMIT` (coalesced per JVM) | 8 writers −10 to −12 %, 20 writers about even | about −10 % |
| `AFTER_COMMIT_DIRECT` (per writer) | 8 writers −32 to −38 %, 20 writers −15 to −23 % | −32 % (8), −13 % (20) |
| Wake latency through pgjdbc, after commit | p50 3.4-15.9 ms; `IN_TX` 19-301 ms at 8 or more writers | p50 2.3-19.8 ms |
| Wake latency on the wire | 0.02-1.06 ms | 0.02-1.09 ms |

The 17.5 run (2026-11-13) fell entirely in the slow flush phase (commit p50 about 7 ms) and is superseded; there
`IN_TX` was pinned near 250/s at every writer count with writers in `Lock:object` for 86-95 % of samples.

**VM** (`fsync=on`, the VM's fast virtual disk): `IN_TX` −57-63 % at 8 writers and −76-77 % at 20, with 80-93 % of
the writers' active time waiting on the NOTIFY lock and `pg_locks` showing 5.4 (8 writers) and 17.4 (20 writers)
waiters on average; `AFTER_COMMIT` matched `NONE` within the window spread and added about 0.1-0.2 ms (p50) to the
wake-up. `AFTER_COMMIT_DIRECT` at 20 writers with `fsync=off` waited 80 % on the lock and was as slow as `IN_TX`:
`pg_notify` assigns an xid, so each writer's own notify transaction still takes the lock and flushes.

**Reading.**

- **Inside the transaction, notifying commits serialise and group commit is lost.** The ceiling is one WAL flush per
  commit, so it is set by the flush speed; the writers' wait profile changes from `LWLock:WALWrite` (group flush) to
  `Lock:object` (the NOTIFY lock). Folding the notify into the append statement would save `IN_TX`'s round trip, not
  the lock, which is taken at commit.
- **Coalescing, not merely moving the notify out of the transaction, removes most of the cost.** With fast commits a
  notify per writer is not free, and coalescing still costs about 10 % at 8 writers; coalesced remains the cheapest
  delivery. The notifier's backend never waits on the lock: it is the only notifying session.
- **A crash between commit and notify loses the hint**, as in the queue; the tier-0 backstop finds the event. Tier 2,
  the same-JVM wake-up, never involves the database.
- **pgjdbc's `getNotifications` holds notifications back under continuous traffic.** pgjdbc 42.7.14's
  `QueryExecutorImpl.processNotifies(timeout)` sets the timeout to −1 after the first notification and keeps reading
  while `hasMessagePending()` is true, so it does not return while notifications keep arriving: on the VM the
  listener got 75 114 notifications in 2 reads over an 8 s window, and wake latency through the API was 0.3-2.0 s p50,
  while the tap saw the bytes within 0.01-0.2 ms. The queue's `ShardWakeupListener` has the same exposure. The
  pgjdbc wake latency on the server (p50 2.3-19.8 ms against 0.02-1.09 ms on the wire) is probably the listener JVM
  starving on the shared CPUs; it is not attributed. [P0.17 (a)](#p017-a-faster-notification-listener) settled the
  listener.

**Decision** (2026-11-15). Tier 1 sends after commit, coalesced per JVM, as the queue does.

Not measured: several tables per commit; several JVMs notifying at once (each notifier contends for the lock once per
batch); other notifying traffic in the cluster.

## P0.11 Re-reading holes in the tail statement

**Question.** P0.5's F1 has the tail statement re-read the outstanding holes, so a late commit is published before
anything above it: `WHERE global_order > :cursor OR global_order = ANY(:holes)`, with ranges after a sequence jump.
Does the planner keep both halves as index scans, and what does the statement cost with many holes, or with a long
writer holding an old hole while the cursor moves on (§19 risk 13)?

**Setup.** `TailHoleReReadSpikeIT`, suite `tail` (knobs `-Dspike.tail.rows`, default 2 000 000; `.designs`;
`.variants`; `.protocols`; `.iterations`; `.limit`, page size 1 000). Two bulk-loaded tables of 2 million rows: BASE
(today's layout) and COMBO (P0.7's combination), head 3 000 000, a one-million-value sequence jump 150 000 rows below
the head, a *near* pool of 1 000 holes from 200 below the head and a *far* pool of 1 000 from 100 000 below. Holes
outstanding 0, 1, 10, 100, 1 000; cursor 0, 1 or 100 rows below the head. Every variant carries §4.10's running-set
sub-select and returns every event column.

- `A`: `WHERE global_order > ? ORDER BY global_order LIMIT ?`, today's tail read (no hole re-read).
- `B`: `WHERE (global_order > ? OR global_order = ANY(?)) ORDER BY global_order LIMIT ?` (F1 as written).
- `C`: `B` plus `OR global_order BETWEEN ? AND ?` for the jump. `C_UNNEST`: holes and jump as an array of ranges in an
  `EXISTS` over `unnest`.
- `D`: `SELECT u.*, <running> FROM ((SELECT … WHERE global_order > ? ORDER BY global_order LIMIT ?) UNION ALL (SELECT
  … WHERE global_order = ANY(?))) u ORDER BY u.global_order`. One statement, one snapshot; the holes do not count
  against the page `LIMIT`.
- `D_RANGES`: `D` plus a `LATERAL` branch over `unnest` of ranges for the jump.
- `E`: two statements (`A`, then the holes); for comparison only, since two snapshots break the model's constraint.

Plans under `force_custom_plan`, `force_generic_plan` and `auto` (after five executions, with and without a hole
history); timings on pgjdbc unnamed (`prepareThreshold=0`), named under `auto`, and named under `force_generic_plan`.

**Plan shapes** (identical on both tables, on the VM, on 17.5/17.12 and on 18.7, where all 728 plans were identical in
kind, shape and buffers):

| Variant | Custom plan | Generic plan | `auto` |
|---|---|---|---|
| A | `Limit > Index Scan (Index Cond > $1)` | same | same |
| B | `Limit > Sort > Bitmap Heap Scan > BitmapOr > 2 × Bitmap Index Scan` | **`Limit > Index Scan` with the whole predicate as `Filter`, walking the primary key** | custom on 2 M rows; **generic at 1 000 holes on a 300 000-row table** (VM smoke run) |
| C | **`Limit > Index Scan` with `Filter`** | same | same |
| C_UNNEST | **`Limit > Index Scan` with `Filter` and a `SubPlan` over `unnest` per row** | same | same |
| D | `Merge Append` of `Limit > Index Scan (> $1)` and `Sort > Index Scan (= ANY)` | same index scans | same |
| D_RANGES | `D` plus `Nested Loop (Function Scan on unnest > Index Scan BETWEEN)`; JIT compiled on BASE | same, JIT with inlining and optimisation | as custom |
| E | `Limit > Index Scan` and `Index Scan (= ANY)` | same | same |

An `OR` cannot be an index condition of one ordered index scan, so the planner chooses between a `BitmapOr` plus a
sort and walking the primary key in order with the predicate as a filter. A generic plan estimates
`global_order > $1` at a third of the table, expects the `LIMIT`'s rows early and picks the walk; the matches are all
at the far end of the index, so the walk reads the whole table. `D` never has the choice: each branch has its own
index condition.

**The generic-plan trap.** pgjdbc's default `prepareThreshold=5` makes the tail statement a named server-side
statement from its fifth execution, and the server then compares the generic plan's estimated cost with the average
of the custom plans so far. `B`'s custom cost grows with the hole array (7 876 at 1 000 holes on 2 M rows, ~13 with
none); the generic estimate does not depend on it but grows with the table (18 717 on 2 M rows). Once the average
custom cost exceeds the generic estimate, the server switches to the generic plan, and from then on every probe reads
the whole table below the cursor, holes or not. With a no-hole history it did not switch, so the trap waits for a burst
of holes (a long writer, a rollback storm) and then stays. Nothing but latency reveals it.

**Numbers** (server execution per probe, BASE, one new row):

| | Dedicated server (17.5) | VM |
|---|---|---|
| `A`, today's tail read | 25-39 µs | 7-11 µs |
| `D`, up to 10 holes | 36-46 µs | 9-14 µs |
| `D`, 100 holes | 53-82 µs | 21-26 µs |
| `D`, 1 000 holes | 280-318 µs | 119-121 µs |
| `B` generic, `C` and `C_UNNEST` in any mode | 289-406 ms | ~280-510 ms |
| `D_RANGES` (BASE, JIT) | — | 5-8 ms custom, 80-95 ms generic |

On 18.7 the hole re-reads were 5-10 % slower, within this host's spread. On the VM each hole cost about 0.11 µs of
execution, near or far alike (an index lookup by value); an unnamed statement paid 168-197 µs of planning per probe at
1 000 holes, generic `D` none; `E` costs one round trip more whenever a hole is outstanding. With no new row and no
hole found the statement returns no row, so the running-set sub-select is never evaluated: the P0.2 caveat.

**Reading.**

- **F1's `OR` form is a generic-plan hazard**: index-only as a custom plan, a full walk as a generic plan, and `auto`
  can switch by itself. Protecting it needs a custom plan on every execution, which costs ~170 µs of planning per
  probe at 1 000 holes.
- **The jump must not be an `OR` range**: even the custom plan walks the index once a `BETWEEN` joins the `OR`.
- **`UNION ALL` (`D`) keeps index scans in every plan mode**, for one statement and one snapshot. Rows come back in
  `global_order` through the outer `ORDER BY`, and the holes do not count against the page `LIMIT`, so a burst of late
  commits cannot crowd out the tail. It is the cheapest correct form at every hole count measured, under a generic
  plan.
- **Ranges need their own branch, kept away from the JIT.** The `LATERAL` range branch has the right plan but a row
  estimate large enough to trigger JIT on every execution; `jit=off` on the reader's connection, or reading ranges
  only on the probe after a jump is first seen, would avoid it (neither measured).
- **1 000 outstanding holes cost about 0.12 ms (VM) to 0.3 ms (server) per probe**, linear in the count. No cap is
  needed for cost; a cap would have to stop the cursor rather than drop holes, or F1's guarantee is lost.

**Decision** (2026-11-15). The hole re-read is a `UNION ALL` branch (`D`), not an `OR`.

Not measured: more than 1 000 holes; where `B`'s `auto` switch happens as a function of table size and hole count; a
range branch that does not trip the JIT; the running set returned once instead of per row; concurrent appends during
the probe; tables larger than 2 million rows.

## P0.12 Cost of the probe's pg_stat_activity read

**Question.** The combined probe reads the running set with `(SELECT array_agg(backend_xid::text) FROM
pg_stat_activity WHERE backend_xid IS NOT NULL)`. Reading `pg_stat_activity` builds a row for every live backend in the
cluster, so its cost depends on how many connections the cluster has, not on how many write. What does it cost at
10, 100 and 400 connections, and what does probing do to appends?

**Setup.** `ProbeRunningSetCostSpikeIT`, suite `probe-cost` (knobs `-Dspike.probecost.levels`, default `10,100,400`;
`.iterations`; `.activeIterations`; `.windowSeconds`; `.writerShare`, 0.2; `.rows`; `.appends`). Needs
`max_connections` of at least 500 for the 400 level. An engine-shaped event table with 100 000 rows and ten holes.
Statements, each tagged with a comment so `pg_stat_statements` can attribute it: `SUB_ROWS`, `SUB_AGG` (the probe's
exact sub-select), `SRF_AGG` (the same over `pg_stat_get_activity(NULL)`, without the view's joins to `pg_database`
and `pg_authid`), `IDSET` (`pg_stat_get_backend_idset()`, the status copy with no rows; a cost reference), `XIP`
(`pg_snapshot_xip(pg_current_snapshot())`, rejected by §4.4; a cost reference), `LOCKS`, `SUB_AGG` under each
`stats_fetch_consistency`, `TXN_CLEAR_SUB_AGG` and `TXN_CACHED_SUB_AGG` (in a transaction, with and without
`pg_stat_clear_snapshot()`), `PROBE_D0`/`PROBE_D10` (P0.11's `D` with the running set, 0 and 10 holes),
`TAIL_D0`/`TAIL_D10` (the same without it) and `SELECT1`. Append windows run 20 % of the connections as writers with
no prober (`NONE`), one prober every 1 ms (`TAIL_1MS`, `PROBE_1MS`), or *N* = connections / 10 probers each every
10 ms (`TAIL_N_10MS`, `PROBE_N_10MS`), as if every JVM had a pool of 10 and one reader.

**Numbers.**

| | 17.12 | 18.7 (rerun with the tag fixed) | VM |
|---|---|---|---|
| `SUB_AGG` execution at 10 (16 backends) / 100 / 400 connections | 35.9 / 139.2 / 585.7 µs | 38.5 / 153.5 / 589.4 µs | 17 / 60 / 238 µs |
| Per-backend slope | 1.41 µs (17.5: 1.34) | — | about 0.55 µs |
| `PROBE_D0` at 10 / 100 / 400 | 50.0 / 154.9 / 616.0 µs | 52.0 / 161.3 / 614.5 µs | 14 / 63 / 249 µs |
| 400 conn, 80 writers: `NONE` | 15 323-15 400/s | 14 650-15 408/s | 74.2-81.9 thousand/s |
| 40 tail-only probers | −52 % | −52 to −54 % | 49.4-52.2 thousand/s |
| 40 combined probers | −80 to −81 % (2 958-3 099/s) | −83 to −84 % (2 421-2 527/s) | 25.1-26.2 thousand/s (half of tail-only) |
| Server CPU at 400 | 4.3-4.4 cores, 6.0 with combined probers | 4.2-4.3 cores, 6.0 | 6.0-6.1 → 7.0 cores |
| Probe execution under 40 combined probers | 19.8-20.6 ms (p50 26.1-27.1 ms) | 18.5-19.7 ms (p50 25.1-26.1 ms) | probe p50 13-16 ms |

On 17.5, at 100 connections with 20 writers, 10 tail-only probers cost −8 % and 10 combined probers −27 %; at 400
append p99 reached 96-104 ms and append p50 rose (4.8 → 20 ms), where on the VM it fell. The first 18.7 run lost the
probe execution figures because 18's `pg_stat_statements` dropped the spike's leading comment tag (fixed in
`4cc73d2cc`); the rerun above restored them.

VM detail: `SRF_AGG` saves 70-110 µs of client latency and about 90 µs of server CPU per call against `SUB_AGG` at
10-100 connections (both about 230-240 µs at 400); `IDSET` costs 36-39 µs at 400, so most of the cost is building the
view's rows; `max_connections` adds about 8 µs per 1 000 slots; `stats_fetch_consistency` makes no difference; `XIP`
costs about 1 µs at any connection count.

**Reading.**

- **Cost scales with live backends and with nothing the engine controls**: about 1.4 µs of execution per backend per
  read on this server (0.55 on the VM), the same idle or with xids in the set, with 0 or 10 holes.
- **Cluster-wide cost is readers × connections, quadratic in the number of JVMs.** With *J* JVMs, each with a pool of
  *p* connections and one tail reader per aggregate type *T*, probing every *i* ms while holes are outstanding, the
  cluster reads *J·T·1000/i* times per second a status table of *J·p* rows. At *J* = 40, *p* = 10, *T* = 1, *i* = 10
  that is 4 000 reads per second of 400 rows. Under write load holes are nearly always outstanding, so §4.10's "plain
  tail read when no candidate is outstanding" does not remove the cost when it matters.
- **At 400 connections the probe saturates the server's CPU**: about 6 of 8 hardware threads, with the client needing
  the rest. On 18.7 the probe's own execution does not grow, yet combined-prober throughput is 17 % lower than on
  17.12, so the extra cost is not in the probe statement. Without a separate client host the client/server split of
  the collapse is not attributed.
- **Calling `pg_stat_get_activity(NULL)` directly is the cheaper form**, at low connection counts. It returns the same
  `backend_xid` from the same per-transaction snapshot, keeps P0.2's conditions and needs the same
  `pg_read_all_stats` visibility. No in-core function returns `backend_xid` without building the whole activity row.

**Decision** (2026-11-15). Read `pg_stat_get_activity(NULL)` directly, and set the probe rate from its measured cost
rather than a fixed interval per JVM (§4.10 and §7.3 of the redesign).

Not measured: wait events in the 400-connection windows, connections that are active but do not write, more than 400
connections, probe intervals other than 1 and 10 ms, one reader per cluster or a shared running set.

## P0.13 Projection progress: per event, per page and periodic

**Question.** §8.1-§8.3 propose saving a `CONTIGUOUS` view projection's resume point in the handler's own transaction,
one transaction per page, with a per-event fallback when an event in the page fails. What does that cost against
today's periodic save, which is the phase-4 gate ("projection throughput above the periodic-save baseline")?

**Setup.** `ProjectionProgressSpikeIT`, suite `progress`. 200 000 events over 20 000 interleaved aggregates, payload
about 200 B; one view table per projection (`aggregate_id text PRIMARY KEY`, every write an
`INSERT … ON CONFLICT … DO UPDATE` that updates); the resume point in today's `durable_subscriptions` shape, written with
§8.2's statement and §9's fence assertion (`UPDATE … WHERE … reposition_epoch = ? AND EXISTS (SELECT 1 FROM leases
WHERE … fence = ?)`; zero rows fails the run). Each projection reads in pages of 500 in autocommit. Arms:

- `PER_EVENT`: a transaction per event with the upsert and the resume-point update.
- `PERIODIC`: today's model; a transaction per event with the upsert only, the resume point saved every 1 000 ms on a
  second connection.
- `PAGE_N` (10, 100, 500): a transaction per page, one upsert per event, then one resume-point update.
  `PAGE_N_BATCH`: the page's upserts as one JDBC batch. `PAGE_N_SET`: one statement over `unnest` arrays,
  pre-aggregated per aggregate id.
- `FALLBACK_K`: `PAGE_100` where every event whose global order is a multiple of K fails permanently: the page rolls
  back, is replayed one event per transaction, the failing event is dead-lettered, and page mode resumes.

1 and 4 concurrent projections; after each run the view and resume row are checked against what was committed.

**Numbers.**

| | Dedicated server | VM `fsync=off` |
|---|---|---|
| `PAGE_100` against `PERIODIC`, 4 projections | 7.8x (17 502 against 2 239 events/s, 17.5); 1 projection 8.6x | 2.0x (57 106 against 27 739) |
| `PAGE_500_SET` against `PERIODIC` | 47x (105 052 events/s, 17.5); 45.6x (17.12); 49.8x (18.7) | 13x (350 252 events/s) |
| `PER_EVENT` against `PERIODIC`, 4 / 1 projections | −0.1 % / −3.8 % (17.5); −1.1 % / −10.5 % (17.12); +5.7 % / −7.7 % (18.7) | −32 % / −33 % |
| One failure in 1 000 (`FALLBACK_1000`) against `PAGE_100` | −44 % | −23 % |
| One failure in 100 (`FALLBACK_100`) | below `PER_EVENT` | 4 589 events/s, below `PER_EVENT` (6 067) |

Server figures are single windows. VM detail (`fsync=off`, one projection): `PER_EVENT` 3 round trips per event,
`PERIODIC` 2, `PAGE_100` 1.02, the batch and set arms 0.032; server CPU per event 84 / 58 / 27 / 7-10 µs; WAL per event
652 / 546 / 514 / 509 B. Every run's view and resume rows matched what had been committed, on every machine.

**Reading.**

- **Progress in the handler's transaction is cheap only per page.** Per page the resume-point update is amortised to
  nothing (0.01 statements per event at 100). With a real flush the per-event penalty against `PERIODIC` disappears
  (both commit once per event and wait on the flush), and the page ratio grows with the price of a flush, because
  the page saves flushes.
- **A handler that hands the page's writes over at once (batch or set-based) gets the large factor**; a per-event
  `@MessageHandler` cannot, so that needs a handler shape that sees the page. Row by row, page size stops mattering
  above about 100 on the VM: one round trip per event is the floor.
- **Bigger pages lose HOT updates.** Within one long transaction a page's dead versions cannot be pruned, so view
  updates were 86 % HOT at 10 events per page, 33-36 % at 100 and 7 % at 500 (VM, default `fillfactor`); WAL per
  event grew 459 → 514 → 557 B. A view with more indexes would feel it.
- **The fallback's cost is the failure rate times the page size.** §8.3 as written pays the whole page again for every
  failure, however often failures come: at one in 100 every page fails and is replayed, and throughput falls below
  per-event mode. On the server the penalty at one in 1 000 doubles to −44 %.
- `PERIODIC`'s replay window after a crash is up to one interval (about 9 000 events at one projection on the VM).

**Decision** (2026-11-15). Per-page resume points in the handler's transaction; the per-event fallback backs off the
page size after a failure instead of replaying whole pages; a page handler (batch or set-based) is opt-in.

Not measured: `ViewEventProcessor`'s existing batched mode (`getDirectHandlingBatchSize()` > 1, a savepoint per event);
a failure placed at random in the page; views with secondary indexes or a lower `fillfactor`; concurrent appends
while projecting; more than 4 projections.

## Background of P0.14-P0.17

P0.14-P0.17 were added on 2026-11-14 after reading `java-raw-stohirov`, a raw-protocol PostgreSQL client (read only,
nothing built or run; it has no licence file, so any wire client of ours is written from the protocol documentation).
Its techniques: several statements pipelined with one Sync, commit by Sync without `BEGIN`/`COMMIT`, group commit
across callers, owned connections with statements prepared at connect and fixed session settings, and an alternative
client.

**The constraint that shapes all of them.** An append runs inside the caller's `UnitOfWork`, on the caller's JDBC
connection, with in-transaction handlers, and the caller gets the `PersistedEvent`s (built from `RETURNING
event_order`) and any `OptimisticAppendToStreamException` synchronously. The engine owns neither the connection nor
the commit and cannot hold the append back until commit. So a technique that needs the engine to own the transaction
or the connection applies only to the paths the engine owns: a subscription page with its fenced resume point (§8.3),
an append the engine commits itself, the periodic resume-point writer, the tail readers, the notifier and the listener.
Group commit cannot apply to appends in a unit of work (different callers' units of work cannot share a transaction),
and an alternative client cannot join a JDBC transaction.

Two limits the spikes respect:

- A zero-row result is not an error, so it does not stop a pipeline. §8.2's fenced resume-point update must abort
  its transaction on zero rows; with the commit in the same flush it can only do so if the statement raises an error
  on zero rows, for example `WITH u AS (UPDATE … RETURNING 1) SELECT 1 / (SELECT count(*) FROM u)` (fails with
  `22012`). After an error the server skips everything up to the Sync, the `COMMIT` included.
- A rolled-back group burns the identity values of every member, so each group rollback leaves as many holes as it
  had appends.

Techniques not made into spikes: array parameters and `unnest … WITH ORDINALITY` (already the design, measured in
P0.4, P0.11 and P0.13), session settings and binary format on their own (arms of P0.16), allocation-light row
parsing (client CPU only), COPY, and a custom driver for the append path (ruled out by the unit-of-work constraint).
Two cautions carried into the spikes: PostgreSQL already lets one backend's WAL flush cover every commit record
inserted before it, so a client-side group saves per-transaction work and round trips rather than flushes; and
`force_generic_plan` is a trap for the event store's own tail read with holes (P0.11), so the plan mode is chosen per
statement and proven with `EXPLAIN`.

## P0.14 One flush for engine-owned transactions

**Question.** When the engine owns the transaction, what do round trips and server CPU drop to if the last statements
and the commit go in one flush, and can pgjdbc do it? It builds on P0.9 (about 280 µs per attempt outside recorded
execution) and P0.12 (`SELECT 1` costs 40-52 µs of server CPU for about 1 µs of execution). **Gate**: phase 2 builds
engine-owned transactions as one flush if the best arm removes at least one round trip per unit of work, saves at
least 35 µs of server CPU per removed round trip and gives at least 10 % more units/s at 8 writers, and a stale epoch
commits nothing in every arm; if B is within 10 % of C, it stays pgjdbc.

**Setup.** `OneFlushTransactionSpikeIT`, suite `pipeline`, PostgreSQL 18.7, 1, 8 and 20 writers, two palindrome
windows per cell (agreeing within a few percent; no slow phase). Shapes: §5.1 with 1 and 10 events committed by the
engine; §5.2 with five aggregates; a page of 100 events as P0.13's set-based view upsert, then §8.2's fenced update
(in its erroring form), then commit. Arms: `A_TX` (today: autocommit off, statements, then `commit()`); `B_BATCH` and
`B_MULTI` (autocommit on, the statements as one `executeBatch` or one multi-statement `PreparedStatement`: one Sync,
so one implicit transaction); `D_EXPLICIT` (explicit `BEGIN … COMMIT` in the same flush). The arms that need a wire
client of our own (C) were not built.

**Numbers** (appended or pages per second, mean of the two windows; PostgreSQL CPU per unit from the cgroup):

| Shape, writers | `A_TX` | `B_BATCH` | `B_MULTI` | `D_EXPLICIT` |
|---|---|---|---|---|
| 1 event, 1 | 703/s, 294 µs | 720/s, 226 µs | 718/s, 235 µs | 713/s, 240 µs |
| 1 event, 8 | 4 728/s, 320 µs | 4 378/s, 269 µs | 4 394/s, 279 µs | 4 404/s, 279 µs |
| 1 event, 20 | 6 769/s, 366 µs | 10 155/s, 272 µs | 10 210/s, 276 µs | 10 062/s, 284 µs |
| 10 events, 8 | 2 824/s, 649 µs | 3 676/s, 535 µs | 3 698/s, 548 µs | 3 636/s, 543 µs |
| 10 events, 20 | 4 641/s, 760 µs | 5 907/s, 633 µs | 5 863/s, 639 µs | 5 875/s, 636 µs |
| 5 aggregates, 8 | 2 671/s, 870 µs | 3 113/s, 752 µs | 2 901/s, 863 µs | 3 052/s, 786 µs |
| 5 aggregates, 20 | 3 937/s, 1 041 µs | 5 068/s, 891 µs | 4 805/s, 962 µs | 4 910/s, 949 µs |
| Page of 100, 8 | 1 494/s, 2 126 µs | not applicable | 1 794/s, 1 852 µs | 1 793/s, 1 871 µs |
| Page of 100, 20 | 1 872/s, 2 651 µs | not applicable | 2 262/s, 2 391 µs | 2 294/s, 2 397 µs |

Round trips per unit: 2 → 1 for appends, 3 → 1 for the page.

**Reading.**

- **The gate passes at 8 writers for 10 events (+31 %), 5 aggregates (+17 %) and the page (+20 %), and fails for
  1 event (−7 %).** Server CPU saved per removed round trip is 42-137 µs everywhere, above the 35 µs threshold; the
  single event fails only on throughput. At 20 writers it gains +49 % and its p50 drops from 3.0 to 1.9 ms, so the
  8-writer dip is a group-commit effect of that writer count, not a cost of the flush.
- **An autocommit batch is not atomic past about 64 KB.** With 400 statements, the last a duplicate key, `B_BATCH` and
  `B_MULTI` left 255 of 399 rows committed: pgjdbc syncs mid-flush at about 64 KB of pending replies, and autocommit
  commits what came before. At 5 statements both stayed atomic. `D_EXPLICIT` is as fast as the autocommit forms in
  every cell and the only one-flush form that stays one transaction at any size; its only extra cost is a `ROLLBACK`
  round trip on the error path.
- **A stale fence or epoch commits nothing in every arm**; the view and resume point stay unchanged, and the
  connection stays usable.

**Decision.** For transactions the engine owns, send the statements and the commit in one flush as an explicit
`BEGIN … COMMIT` (`D_EXPLICIT`) through pgjdbc, never as an autocommit batch. The "B within 10 % of C" clause was not
evaluated, since C was not built. The five-aggregate append's rollback on a zero-row conflict under one flush still
needs its own correctness test.

## P0.15 Group commit for engine-owned writes (parked)

**Question.** Does a batcher that takes every waiting engine-owned write and commits them as one implicit transaction
beat each caller committing alone, when PostgreSQL already shares one WAL flush among concurrent commits, and what
does a group rollback cost? It would apply only to writes outside a caller's unit of work: an opt-in append-and-commit
API without in-transaction handlers (importers, forwarding, the command-side track of §14), periodic resume-point
saves (§8.1) and lease renewals (§9). The planned `EngineGroupCommitSpikeIT` was never built.

**Status.** Parked on 2026-11-14: PostgreSQL already groups concurrent commits, and appends cannot use a batcher.
Revisit only if round trips still dominate after P0.14. Until then engine-owned writes commit per caller and rely on
PostgreSQL's shared WAL flush, and periodic saves stay as today.

## P0.16 Owned, pre-prepared read connections

**Question.** For the engine's own long-lived connections (tail readers, periodic resume-point writer, notifier),
what does an owned connection with every statement prepared at connect and fixed session settings save per call over
pooled pgjdbc defaults; which `plan_cache_mode` keeps each statement on its index (P0.11's trap); and do the tail reads
of several woken aggregate types in one flush beat one round trip per type? **Gate**: own the connection if it saves
at least 20 µs of server CPU or 20 % of p50 per tail read; read several types in one flush if at 10 types it cuts
server CPU per wake by at least 30 %; a wire client counts only if it beats pgjdbc by at least 20 %.

**Setup.** `OwnedReadConnectionSpikeIT` (with `MinimalWireClient`), suite `owned`, PostgreSQL 18.7, P0.11's
2-million-row tables plus 1, 10 and 50 aggregate-type tables; 17 minutes. Arms: `A_POOL` (Hikari, pgjdbc defaults,
prepare per call), `B_AUTO` and `B_GENERIC` (an owned pgjdbc connection with every statement executed once at connect,
`jit=off`, no parallel workers, under `auto` or `force_generic_plan`), `C_AUTO` (the minimal wire client: statements
parsed at connect, binary parameters and results). For several types: one connection and round trip per type against
one owned connection reading every type in one flush (a multi-statement `PreparedStatement` with `getMoreResults`).
No result mismatches; every statement used an index-only plan under `auto`, `force_custom_plan` and
`force_generic_plan`.

**Numbers.**

| Statement | `A_POOL` | `B_AUTO` | `B_GENERIC` | `C_AUTO` |
|---|---|---|---|---|
| `TAIL_1`, server CPU / p50 | 109 µs / 190 µs | 111 µs / 194 µs | 50 µs / 124 µs | 105 µs / 175 µs |
| `PROBE_10`, server CPU / p50 | 303 µs / 412 µs | 284 µs / 393 µs | 116 µs / 198 µs | 281 µs / 370 µs |
| Planning per call | 46-146 µs, 1 plan | 48-137 µs, 1 plan | 0, no plan | 46-136 µs, 1 plan |

Several types per wake: at 10 types one pgjdbc flush costs 625 µs of server CPU per wake against 1 470 µs with one
connection per type (−58 %), at 50 types 2 971 against 8 769 µs (−66 %), and it stays one round trip (no mid-flush
Sync). Latency is slightly higher than the parallel per-type reads (p50 834 against 730 µs at 10 types, 3.3 against
2.8 ms at 50). A Sync per read on the wire client costs about 50 % more server CPU than one Sync at the end.

**Reading.** The owned gate passes on 5 of 5 tail reads (`B_GENERIC` against `A_POOL`: 57-189 µs server CPU saved,
p50 −24 to −56 %), **but the whole saving is the generic plan, not the owned connection**: `B_AUTO` costs the same as
`A_POOL`, because under `auto` PostgreSQL keeps re-planning these statements (1.00 plans per call). The wire client
does not count (−11 to +5 % of pgjdbc in the same mode).

**Decision.** Phase 2's tail reader runs its read statements under `force_generic_plan` and reads all woken types in
one flush, on pgjdbc. Owning the connection is not needed for the saving; no second client. Generic plans are only for
statements whose plans were checked; P0.11's `OR = ANY` form is the known case where a generic plan is wrong. Not
measured: a pooled connection under `force_generic_plan` (it should get the same saving as long as the server-side
prepared statement survives on the pooled connection).

## P0.17 (a) Faster notification listener

**Question.** Does a client that delivers notifications as they arrive beat pgjdbc enough to justify a second driver?
**Gate**: an alternative listener only if pgjdbc's best (`PINNED_PEEK`, or `QUERY_POLL` if reflection is rejected)
misses p99 ≤ 2 ms at 3 000 notifications/s and the alternative meets it. The options are described in
[`pgjdbc-notification-drain-options.md`](../plans/pgjdbc-notification-drain-options.md).

**Setup.** `AlternativeClientSpikeIT`, suite `listen`, `-Dspike.listen.writers=8,20
-Dspike.listen.rates=200,1000,3000,burst2000`. Producers as P0.10 (the coalesced after-commit notifier) at fixed
rates. Listeners: pgjdbc `getNotifications(200)` stock (`STOCK`), `PINNED_PEEK` (reflection that pins pgjdbc's peek
cool-down), `QUERY_POLL` (a query-driven poll), and Vert.x `PgSubscriber`. Wake latency from the commit's return to
the listener callback, and from the wire (`NotifyTapSocketFactory`). Producers, listener and server share the host.
Two windows per cell in palindrome order; mean p50 and the worse window's p99. All 64 windows received exactly what
was sent, without duplicates, on 17.12 and 18.7.

**Numbers** (17.12, ms):

| Writers, rate | `STOCK` p50 / p99 | `PINNED_PEEK` p50 / p99 | `QUERY_POLL` p50 / p99 | Vert.x p50 / p99 |
|---|---|---|---|---|
| 8, 200/s | 1.73 / 1.97 | 0.62 / 0.79 | 1.16 / 1.91 | 0.65 / 0.82 |
| 8, 1 000/s | 4.23 / 22.2 | 0.29 / 0.57 | 0.83 / 1.65 | 0.32 / 0.46 |
| 8, 3 000/s | 144 / 661 | 0.30 / 0.62 | 0.83 / 1.56 | 0.29 / 0.60 |
| 8, burst 2 000 | 45.4 / 389 | 0.36 / 0.73 | 0.54 / 1.92 | 0.36 / 0.70 |
| 20, 200/s | 1.64 / 3.15 | 0.50 / 0.77 | 1.18 / 1.93 | 0.54 / 0.74 |
| 20, 1 000/s | 3.91 / 38.3 | 0.29 / 0.48 | 0.85 / 1.62 | 0.29 / 0.49 |
| 20, 3 000/s | 151 / 1 367 | 0.29 / 0.72 | 0.83 / 1.56 | 0.28 / 0.66 |
| 20, burst 2 000 | 13.4 / 131 | 0.47 / 0.96 | 0.85 / 2.10 | 0.46 / 0.85 |

On 18.7, p99 at 3 000/s: 8 writers `STOCK` 1 255, `PINNED_PEEK` 0.60, `QUERY_POLL` 1.59, Vert.x 0.61 ms; 20 writers
1 481, 0.69, 1.57, 0.61 ms; 20-writer burst `QUERY_POLL` 2.17 ms.

**Reading.**

- `PINNED_PEEK` meets the gate at both writer counts (worst p99 at 3 000/s 0.60-0.72 ms), and Vert.x is within about
  0.1 ms of it everywhere at the same listener client CPU (6-16 µs per notification at 2 000-3 000/s), so a second
  driver buys nothing for wake-ups.
- The stock drain's lag is inside pgjdbc, not on the wire: the notification reaches the socket at p99 0.45-0.87 ms
  in every cell, but the callback waits up to 1.4 s at 3 000/s. At 200/s the stock drain is still acceptable (p99
  2-3 ms).
- `QUERY_POLL` as the fallback is borderline: p99 1.56 ms at 3 000/s but 2.10-2.17 ms in the 20-writer burst, about 4x
  `PINNED_PEEK`'s listener CPU at 200/s (245 against 44-62 µs per notification) and more server CPU (554-581 against
  432-468 µs per notification, producers' commits included).
- PostgreSQL CPU per notification is the same for every listener at 1 000/s and above (149-221 µs, producers' commits
  included); the listener's choice does not load the server except through `QUERY_POLL`'s query.

**Decision.** pgjdbc only; the listener uses `PINNED_PEEK`, with `QUERY_POLL` as the fallback. No second driver in
phases 2 or 6. Not measured: Vert.x's wire timestamp, a minimal LISTEN-only reader (not needed once `PINNED_PEEK` and
Vert.x answered the question), producers going through the engine's own append, a listener on a separate host on a
wire.

## P0.17 (b) Request-path pipelining (parked)

**Question.** Does a client that pipelines many callers' independent statements on one connection (Vert.x with
`pipeliningLimit` 1, 16, 256, or the minimal wire client), for engine-owned requests only, give at least 25 % less
server CPU per statement and 1.3x throughput at 20 callers against pgjdbc's best?

**Status.** Parked on 2026-11-14: it needs a wire client of our own, for a gain P0.14 can mostly show with pgjdbc.
Revisit only if P0.14's one-flush transactions leave round trips dominant. Any alternative client would also bring
provided-scope Vert.x and Netty and a second credential and TLS configuration beside the `DataSource`.

## The slow flush phase and other machine caveats

**The slow flush phase.** On the dedicated laptop the commit flush ran at two speeds. In the fast phase a one-writer
commit took about 1.1-1.6 ms and the contended suites appended about 1 800-2 600 events/s; in the slow phase every arm
fell to about 660-780/s, commit time per attempt rose to about 4.6-4.9 ms (commits about 3-5x slower), and server
execution at low load roughly doubled.

- On 17.5 (2026-11-13, governor `powersave`) it covered most of P0.7's contended windows (66 of 80), P0.7 run 2's
  uncontended rounds, the first two windows of P0.4 contention run 1, most of P0.9, the opening of P0.6's contended
  run 1, and the whole P0.10 suite (commit p50 about 7 ms), whose rows were therefore replaced by the 17.12 run.
- On 17.12 (2026-11-14, governor `performance`) it did not appear: every window of P0.9, P0.7 contended, P0.4
  contention and P0.6 contended ran fast.
- On 18.7 (2026-11-15) it came back with the governor still at `performance`: P0.0's uncontended appends ran about 3x
  slower (1 event p50 4.85-6.25 ms against 1.39-1.54), P0.0's first OCC run reached only about a third of its
  attempts, and P0.4's append suite ran slow from the start of its second run through its contended section (about
  10:17-10:27 UTC; 1 event p50 6.2-6.4 ms against 1.3-1.4, contended 657-716 appended/s against about 1 900). Those
  rows are not usable. Shorter commit stalls showed elsewhere: commit p99 of 7-18 ms in six P0.13 arms (one on 17.12),
  append p99 7.0-8.6 ms in 6 of 10 P0.12 windows at 10 connections (2.1-2.5 ms on 17.12), commit p99 about 5 ms with
  one P0.10 writer.

So the governor did not cause it. The cause is more likely the laptop's storage (write cache, thermal throttling or
SSD housekeeping) than PostgreSQL, since 17.5 showed it too; whether 18's `io_method` or checksums add to the stalls
cannot be separated. Every ratio in this record compares arms within the same phase, and rows that fell in it are
marked unusable or were replaced.

**Other caveats.**

- **Client and server share the host's 8 hardware threads.** Cells that are CPU-bound (P0.0 burst catch-up, the
  contended tail read, P0.12 at 400 connections) are capped by the shared machine. The contended append suites were
  flush-bound, not CPU-bound: their `pg_stat_activity` samples on 17.5 were `LWLock:WALWrite` 8.6-17.8 %,
  `Lock:transactionid` 4.6-9.3 %, `IO:WalSync` 4.0-4.8 % and `CPU` only 1.2-3.9 %, with the machine 24-62 % busy.
- **Server execution is 2.5-4x the VM's everywhere**, today's statements included, and did not drop under governor
  `performance`; it is the machine's speed.
- **Known oddities.** P0.10 at one writer: the two `NONE` windows on 17.5 disagreed (479 and 206/s), so the one-writer
  `IN_TX` cost lies anywhere between −2 % and −57 % there. Commit p50 was 5-7 ms in P0.10 but 1.0-1.5 ms in P0.13's
  per-event arm on the same 17.5 server. The headers of `p0-9-cpu.txt` and `p0-4-encoding.txt` say `fsync=off`; that
  label is hard-coded, and the server facts on the same line say `fsync=on`.
- **VM-only effects** that do not carry over: the idle and wake-up cost behind P0.4's 30 % gap, P0.6's NORMAL-mode
  JDBI lead and P0.7's option ranking ([P0.9](#p09-unattributed-server-cpu)).

## Open items

- **P0.3, managed services.** No RDS, Aurora, Azure or Cloud SQL access. Nothing may be released as supported on a
  managed service until P0.3 passes for it; still unchecked on Supabase: a role without `pg_read_all_stats` and the
  transaction pooler.
- **The slow flush phase's cause.** Next: `pg_test_fsync`, `iostat -x` during a run, and the SSD's temperature.
- **P0.9's gap split.** A 274-295 µs and B 301-326 µs per attempt outside execution, across commit flush, protocol
  and background processes. Needs per-backend CPU or a separately timed commit.
- **A separate wired client host**, to split client from server in P0.12's 400-connection collapse and in P0.10's
  pgjdbc wake latency, and to measure the P0.17 (a) listener on another host.
- **A pooled connection under `force_generic_plan`** (P0.16 measured only the owned connection).
- **`CONTIGUOUS` attach/detach behind a long writer** (P0.5 F3): a subscription can attach, overflow and detach
  repeatedly, one bridge read each time; the cost is not measured.
- **A correctness test for the five-aggregate rollback under one flush** (P0.14): a zero-row conflict on one aggregate
  must roll back the whole explicit `BEGIN … COMMIT`.
- **Closed 2026-11-15**: P0.1/P0.2 on PostgreSQL 16 was dropped (both held on 16.15 in the VM and test ordering rules,
  not timing); every proposed revision (F1-F6, P0.10, P0.12, P0.13, P0.16) was decided as recommended.
