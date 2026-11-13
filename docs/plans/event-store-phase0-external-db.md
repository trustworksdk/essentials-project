# Phase 0 spikes against a dedicated PostgreSQL server

Every phase-0 spike of [event-store-subscription-redesign.md](event-store-subscription-redesign.md) so far ran
against a Testcontainers PostgreSQL with `fsync=off`, on the same 8-CPU arm64 Docker VM as the client. §9 of
[event-store-phase0-append.md](event-store-phase0-append.md) showed that about half of PostgreSQL's CPU per attempt
on that VM is idle and wake-up cost, so the absolute numbers, and possibly the ratios between arms, are specific to
that machine. This document describes how to run the same spikes against a dedicated database server, with the client
on another machine, and how to read the results.

The target machine is an old Clevo laptop: Ubuntu, 30 GB RAM, a 4-core Intel i7, `fsync=on`.

## Running with the script

`scripts/run-phase0-spikes.sh` runs the commands of §6 and keeps what §7 asks for. It checks the prerequisites (JDK
25-27, Maven, Docker or a reachable server), moves an existing `target/spike-results/` aside so runs never mix, builds
the module once, runs each suite sequentially with its own log (a failing suite does not stop the rest), and collects
everything into `phase0-results/<timestamp>-<host>-<mode>/` plus a `.tar.gz` of it: the spike result files, the logs,
the spikes' failsafe reports, `machine.txt` (client), `server.txt` (external mode: version, settings and a 200 ×
`SELECT 1` round-trip measurement when `psql` is installed, host CPU and disks through ssh) and a `SUMMARY.md`.

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

The suites are `xid`, `table-design`, `contention`, `notify`, `tail`, `probe-cost`, `progress`, `append`, `baseline`, `cpu`, `jdbc` and `probe` (the
default runs all of them in §6's priority order); a suite can name one test method, as in `jdbc#protocol_trace`. Since 2026-11-11 `jdbc` also runs
`round_trip_reconciliation` (counting proxy and pgjdbc wire trace on the same units of work; `RT-RECONCILE` lines in
`p0-6-jdbc.txt`), which needs no extra setup on the server. Run
`scripts/run-phase0-spikes.sh --help` for every option. The rest of this document is what the script does by hand,
and what to set up on the server before it can.

## 1. How the spikes reach an external server

The spikes are in `components/postgresql-event-store/src/test/java/.../postgresql/spike/`. They are still tagged
`spike` and run only with `-Dspike.run=true`. Without the properties below they behave exactly as before and start
their own containers.

| Property | Meaning |
|---|---|
| `-Dspike.jdbcUrl=jdbc:postgresql://dbhost:5432/postgres` | The external server. When set, no PostgreSQL container is started. The database in the URL is only used to connect; URL parameters (for example `sslmode`) are kept. |
| `-Dspike.user`, `-Dspike.password` | A superuser on that server (default user `postgres`). |
| `-Dspike.serverCpuCommand="ssh bench@dbhost cat /proc/stat"` | Optional. A shell command, run on the client, that prints either `/proc/stat` of the database host (whole-machine CPU) or a cgroup v2 `cpu.stat` (for example the PostgreSQL service's cgroup, `cat /sys/fs/cgroup/system.slice/postgresql@17-main.service/cpu.stat`). It is run twice per measurement window. |

Every server a spike asks for gets its own database, `spike_<purpose>_<suffix>`, and every role the spikes use
(`observer`, `writer`, `reader`, `reader2`, `explainer`, `spike`) is created as `<role>_<suffix>` with a random
password. All of them are created as superusers, as every role is in the container, so the numbers stay comparable.
When the spike ends, the database is dropped (`WITH (FORCE)`) and the roles are dropped. A JVM shutdown hook does the
same if the run is interrupted. A killed JVM can still leave something behind; find it with:

```sql
SELECT datname FROM pg_database WHERE datname LIKE 'spike\_%';
SELECT rolname FROM pg_roles WHERE rolname ~ '_[0-9a-z]{8,}$' AND rolname !~ '^pg_';
```

That is why `-Dspike.user` must be a superuser: creating superuser roles needs one. The harness checks this, checks
that `pg_stat_statements` is in `shared_preload_libraries` for the spikes that measure through it, and checks that
one connection can see another backend's `pg_stat_activity.backend_xid`. Each check fails the run with a message that
says what to change. Nothing else is changed on the server: `pg_stat_statements.track_planning`, which the container
path turns on with `ALTER SYSTEM`, is set with `ALTER DATABASE` on the run's own database, which is dropped
afterwards.

### What degrades without a container

| Measurement | Container | External server |
|---|---|---|
| `pg_stat_statements`, WAL bytes (`pg_current_wal_lsn`), `pg_stat_activity` samples, table sizes | yes | yes, restricted to the run's roles (and database, for the P0.0 summaries) |
| Server CPU (`PostgreSQL container CPU`, `PG cgroup`) | container cgroup | only with `-Dspike.serverCpuCommand`; otherwise the columns print `NaN` and the header says so |
| Per-backend `/proc/<pid>/schedstat` and context switches (P0.9) | yes | `NaN`: the backends' `/proc` is on another machine |
| Machine busy, context switches, interrupts, IPIs (P0.4 contention, P0.7, P0.9, P0.6) | the shared VM | the **client** machine's, which no longer includes the database |
| auto_explain plans in P0.4 encoding (read from the server log) | yes | skipped with a `PLAN | not available` line; the measurements still run |
| P0.9 `BURNER` arms (a sidecar container on the database's Docker host) | yes | skipped with a line saying so |
| P0.9 `-Dspike.cpu.perf=true` (a `perf` sidecar) and `-Dspike.cpu.pgCpus` (container cpuset) | yes | ignored, recorded in the header; run `perf` on the database host yourself (§6) |
| P0.9's `fsync=off` assertion | enforced | not enforced; the header records the server's `fsync` |
| Several majors in one run (P0.1, P0.2: `-Dspike.pg.images`) | 16, 17, 18 | one server, one major per run; `-Dspike.pg.images` is ignored. Run again against an 18 server to compare |
| `-Dspike.baseline.image` | picks the image | ignored; the header records the server version |

On an external server every results header carries a line starting `EXTERNAL server`, with the server version, the
settings that move the numbers (`fsync`, `synchronous_commit`, `shared_buffers`, `max_wal_size`, `jit`, the
`pg_stat_statements` settings and others) and where server CPU came from. Results still go to
`components/postgresql-event-store/target/spike-results/`, appended to the same files as the container runs, so copy
or rename the directory between machines.

## 2. Installing PostgreSQL on Ubuntu

Use the PostgreSQL project's own apt repository (PGDG), which carries every supported major and the contrib modules
(`pg_stat_statements` and `auto_explain` are in the server package):

```bash
sudo apt install -y postgresql-common
sudo /usr/share/postgresql-common/pgdg/apt.postgresql.org.sh   # adds the PGDG repository and its key
sudo apt install -y postgresql-17
# optionally a second major on another port, for the P0.1/P0.2 cross-version runs:
sudo apt install -y postgresql-18                               # becomes cluster 18/main on port 5433
pg_lsclusters
```

The configuration lives in `/etc/postgresql/17/main/`. Restart with `sudo systemctl restart postgresql@17-main`.

## 3. Server configuration

Put the settings below in `/etc/postgresql/17/main/conf.d/spikes.conf` (Ubuntu's `postgresql.conf` includes
`conf.d`), so the package's own file stays untouched. The values suit 30 GB of RAM, 4 cores and a benchmark: stable
numbers and no surprise checkpoints, not production tuning.

```ini
# reachable from the LAN
listen_addresses = '*'
max_connections = 200            # the contention spikes open 8-20 connections per JVM; the P0.0 shapes several pools

# statistics the spikes read
shared_preload_libraries = 'pg_stat_statements'       # add ',auto_explain' only for ad-hoc plan logging, see below
pg_stat_statements.max = 10000                        # what the container path sets
pg_stat_statements.track = top
pg_stat_statements.track_utility = on                 # P0.0 counts BEGIN/COMMIT
pg_stat_statements.track_planning = off               # the spikes that need it turn it on for their own database
track_io_timing = on
track_activity_query_size = 4096

# memory (30 GB)
shared_buffers = 8GB
effective_cache_size = 22GB
work_mem = 32MB
maintenance_work_mem = 1GB
huge_pages = try

# durability: the point of this machine
fsync = on
synchronous_commit = on
full_page_writes = on
wal_compression = off            # match the container default; try lz4 as a separate experiment

# WAL and checkpoints: keep checkpoints out of a 20 s window
wal_level = replica
wal_buffers = 64MB
max_wal_size = 16GB
min_wal_size = 2GB
checkpoint_timeout = 30min
checkpoint_completion_target = 0.9

# planner and background work
jit = off                        # the spikes' statements are short; JIT only adds noise
random_page_cost = 1.1           # SSD; leave at 4 on an HDD
autovacuum = on                  # leave it on, but note in the record if a window overlapped a vacuum

# logging that helps afterwards
log_checkpoints = on
log_autovacuum_min_duration = 0
```

Notes:

- `jit = off` differs from the container's default (`on`). Record it; it makes no difference for these statements,
  which never reach the JIT cost threshold, but it removes one variable.
- `auto_explain` can be loaded per session by a superuser (`LOAD 'auto_explain'`, which P0.1 does), so it does not
  need to be preloaded. Preload it (`shared_preload_libraries = 'pg_stat_statements,auto_explain'` and
  `auto_explain.log_min_duration = 0`) only for a manual plan-logging session, and remove it afterwards: logging every
  plan costs CPU on every statement and would distort every other measurement.
- Check the running values with `SHOW` after the restart; the spikes record them too.

### Access

`pg_hba.conf` (`/etc/postgresql/17/main/pg_hba.conf`), for a LAN of `192.168.1.0/24`:

```
# TYPE  DATABASE  USER   ADDRESS          METHOD
host    all       all    192.168.1.0/24   scram-sha-256
```

Then create the benchmark superuser:

```bash
sudo -u postgres psql -c "CREATE ROLE bench LOGIN SUPERUSER PASSWORD 'choose-one'"
```

A superuser is needed, not just a role with `pg_read_all_stats`: the harness creates per-run superuser roles (so that
the run matches the container, where every role is a superuser), `LOAD`s `auto_explain`, resets
`pg_stat_statements` and drops what it created. Full `pg_stat_activity` visibility, including `backend_xid` of other
roles' backends, follows from that. Keep the machine on the LAN only.

Firewall:

```bash
sudo ufw allow from 192.168.1.0/24 to any port 5432 proto tcp
sudo ufw allow OpenSSH
sudo ufw enable
```

### Alternative: PostgreSQL in Docker Compose

Added 2026-11-12. Instead of the Ubuntu package (§2 and the sections above), PostgreSQL can run from the official
`postgres` image under Docker Compose on the same machine. The image ships `pg_stat_statements` and `auto_explain`, so
nothing extra is installed. The settings of §3 become `-c` flags on the server command, because the image has no
`conf.d` to drop a file into:

```yaml
# compose.yaml on the database machine
services:
  pg:
    image: postgres:17.12           # the latest 17; for the 18 run use the latest 18 tag
    container_name: spike-pg
    network_mode: host              # no Docker NAT or userland proxy in every round trip; listens on the host's 5432
    shm_size: 1g                    # Docker's default /dev/shm is 64 MB
    environment:
      POSTGRES_USER: bench          # created as a superuser, which the spikes need
      POSTGRES_PASSWORD: choose-one
      POSTGRES_DB: postgres
    volumes:
      - pgdata:/var/lib/postgresql/data
    command:
      - postgres
      - -c
      - listen_addresses=*
      - -c
      - max_connections=500           # P0.12's 400-connection level needs at least 500
      - -c
      - shared_preload_libraries=pg_stat_statements
      - -c
      - pg_stat_statements.max=10000
      - -c
      - pg_stat_statements.track=top
      - -c
      - pg_stat_statements.track_utility=on
      - -c
      - pg_stat_statements.track_planning=off
      - -c
      - track_io_timing=on
      - -c
      - track_activity_query_size=4096
      - -c
      - shared_buffers=8GB
      - -c
      - effective_cache_size=22GB
      - -c
      - work_mem=32MB
      - -c
      - maintenance_work_mem=1GB
      - -c
      - huge_pages=try
      - -c
      - fsync=on
      - -c
      - synchronous_commit=on
      - -c
      - full_page_writes=on
      - -c
      - wal_compression=off
      - -c
      - wal_level=replica
      - -c
      - wal_buffers=64MB
      - -c
      - max_wal_size=16GB
      - -c
      - min_wal_size=2GB
      - -c
      - checkpoint_timeout=30min
      - -c
      - checkpoint_completion_target=0.9
      - -c
      - jit=off
      - -c
      - random_page_cost=1.1
      - -c
      - log_checkpoints=on
      - -c
      - log_autovacuum_min_duration=0
volumes:
  pgdata:
```

```bash
docker compose up -d
docker exec spike-pg psql -U bench -c "SHOW shared_preload_libraries" -c "SHOW fsync" -c "SHOW shared_buffers"
```

Notes:

- **Access.** No `pg_hba.conf` edit is needed: when `POSTGRES_PASSWORD` is set, the image's `pg_hba.conf` accepts
  password logins from any address. Restrict reachability with the host firewall instead (the `ufw` rules above apply
  unchanged with host networking).
- **The role.** `POSTGRES_USER`, `POSTGRES_PASSWORD` and `POSTGRES_DB` are applied only when the data volume is
  initialised. With an existing `pgdata` volume, create the role by hand
  (`docker exec spike-pg psql -U <existing superuser> -c "CREATE ROLE bench LOGIN SUPERUSER PASSWORD '…'"`). The `-c`
  flags apply on every start.
- **Networking.** `network_mode: host` keeps Docker's port forwarding out of the measured round trips. Publishing the
  port (`ports: ["5432:5432"]`) works too, but adds a NAT or proxy hop to every round trip; record which was used.
- **Server CPU.** The process runs in the container's cgroup on the host. Two choices for `--server-cpu-command`:
  - The whole host: `ssh <host-user>@dbhost cat /proc/stat`. Includes `dockerd`, `containerd` and everything else on
    the machine.
  - PostgreSQL alone, which matches what the container path measures (the container's `cpu.stat`). With Docker's
    default systemd cgroup driver on Ubuntu the container's cgroup is
    `/sys/fs/cgroup/system.slice/docker-<full container id>.scope`:
    `ssh <host-user>@dbhost 'cat /sys/fs/cgroup/system.slice/docker-$(docker inspect -f "{{.Id}}" spike-pg).scope/cpu.stat'`.
    The host user must be allowed to run `docker` (member of the `docker` group). Check the command by hand once;
    `SpikeServer` reports "FAILING: check the command" in the run header if its output cannot be parsed.

  `<host-user>` is the Ubuntu login on the database machine, not the database role. Set up key-based ssh first.

## 4. Preparing the laptop

A laptop is a poor benchmark host by default. Before a run:

- **Power plugged in**, battery charged: on battery the firmware caps the CPU.
- **CPU governor `performance`**: `sudo apt install linux-tools-common linux-tools-$(uname -r)`, then
  `sudo cpupower frequency-set -g performance`; check with `cpupower frequency-info`. With `intel_pstate` also check
  `cat /sys/devices/system/cpu/intel_pstate/no_turbo`.
- **No sleep or lid suspend**: in `/etc/systemd/logind.conf` set `HandleLidSwitch=ignore` and
  `HandleLidSwitchExternalPower=ignore`, then `sudo systemctl restart systemd-logind`; and
  `sudo systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target`.
- **Turbo and thermals**: an old laptop throttles under sustained load, and turbo makes the first seconds faster than
  the rest. Either disable turbo (`echo 1 | sudo tee /sys/devices/system/cpu/intel_pstate/no_turbo`) for steadier
  numbers, or leave it on and watch for throttling (`sudo turbostat --quiet --show Busy%,Bzy_MHz,PkgTmp --interval 5`
  in another terminal). Record which. Clean the fans and keep the lid open or the laptop raised.
- **Quiet machine**: no desktop session (`sudo systemctl isolate multi-user.target`), no `unattended-upgrades`
  during the run (`sudo systemctl stop unattended-upgrades`), no backups, no `snapd` refreshes
  (`sudo snap refresh --hold`).
- **Disk type**: with `fsync=on` every commit waits for the WAL flush, so the disk decides much of the append cost.
  Check `lsblk -d -o NAME,ROTA,MODEL,SIZE` (`ROTA=1` is a spinning disk) and run `pg_test_fsync` (in
  `/usr/lib/postgresql/17/bin/`) on the data directory's file system. An HDD gives a few hundred fsyncs a second, a
  SATA SSD thousands, an NVMe more. A drive with a volatile write cache that lies about flushes gives SSD-like numbers
  that are not durable; record the model.

## 5. The network between client and server

Use a wired connection on both ends. Wi-Fi adds round-trip time and, worse, jitter of several milliseconds, which
would dominate every single-thread latency figure. Measure before each session and record it:

```bash
ping -c 100 -i 0.2 dbhost | tail -2                   # rtt min/avg/max/mdev
pgbench -h dbhost -U bench -i -s 1 postgres           # once
pgbench -h dbhost -U bench -c 1 -j 1 -T 20 -S -n postgres   # 1-client select-only: latency ≈ round trip + execution
pgbench -h dbhost -U bench -c 1 -j 1 -T 20 -b simple-update -n postgres   # 1-client commits: adds the WAL flush
```

The P0.0 and P0.4 append shapes report round trips per unit of work (through the counting proxy); multiply them by
the measured round-trip time to see how much of a latency is network. On the VM a round trip cost about 0.05 ms; on
a gigabit LAN expect 0.2-0.5 ms, so single-thread latencies will be several times higher and dominated by round trips,
while server CPU per statement is unaffected.

## 6. The run, in priority order

Common prefix (from the repository root, nothing else building):

```bash
EXT=(-Dspike.jdbcUrl=jdbc:postgresql://dbhost:5432/postgres -Dspike.user=bench -Dspike.password=choose-one
     "-Dspike.serverCpuCommand=ssh bench@dbhost cat /proc/stat")
run() { mvn -o verify -pl components/postgresql-event-store -DskipUTs -Dit.test="$1" -Dfailsafe.failIfNoSpecifiedTests=false \
        -Dspike.run=true -Dfailsafe.forkCount=1 -DskipDependencyCheck=true "${EXT[@]}" "${@:2}"; }
```

Set up key-based ssh to the database host first, so the CPU command does not prompt. `/proc/stat` measures the whole
host, which is right for a dedicated, quiet machine; each reading costs an ssh round trip and a few milliseconds of
CPU, negligible against 20 s windows. For PostgreSQL's CPU alone, point the command at the service's cgroup instead:
`ssh bench@dbhost cat /sys/fs/cgroup/system.slice/postgresql@17-main.service/cpu.stat`.

0. **Prerequisite: `backend_xid` visibility.** Every external server checks it when it is set up, but run the probe
   spike once on its own first; it is short and proves the xid ordering assumption on this server:
   `run AppendXidBeforeIdentitySpikeIT`
1. **Append, table design and combined options (P0.4, P0.7).** The main question: does the head table's cost, and
   the gap between the single-statement append (B) and the engine's (A), survive on real hardware with `fsync=on`?
   On the VM part of the gap was wake-up cost (§9.5 of the append doc).
   - `run TableDesignOptionsSpikeIT`
   - `run ContendedAppendThroughputSpikeIT`
   - `run SingleStatementAppendSpikeIT`
   - `run AppendEncodingAttributionSpikeIT` (no auto_explain plans externally, see §1)
   - `run WakeupNotifyCostSpikeIT` (P0.10, suite `notify`, about 6 minutes against one server): what a
     wake-up `pg_notify` costs the append, inside the transaction against after commit. Of every spike this is the
     one the container cannot answer: a notifying commit holds PostgreSQL's NOTIFY lock through its WAL flush, and
     the flush is what `fsync=on` on a real disk makes long. On an external server it runs one pass with the server's
     own settings (the container runs `fsync=off` and `fsync=on`); results in `p0-10-notify.txt`, append doc §10.
   - `run TailHoleReReadSpikeIT` (P0.11, suite `tail`): what re-reading the outstanding holes costs the tail
     statement, and which plan PostgreSQL picks for each statement shape under custom, generic and `auto` plan
     caching. It bulk-loads two 2-million-row tables (about 4 GB of disk and WAL on the server; dropped afterwards,
     `-Dspike.tail.rows` to shrink) and needs `pg_stat_statements` preloaded. The plan shapes should not depend on the
     machine; the latencies will. Results in `p0-11-tail-holes.txt` and `p0-11-tail-plans.txt`, append doc §11.
   - `run ProbeRunningSetCostSpikeIT` (P0.12, suite `probe-cost`): what the combined probe's `pg_stat_activity`
     read costs at 10, 100 and 400 open connections, idle and with 20 % of them appending, and what probing every
     1 ms (one reader) or every 10 ms (one reader per 10 connections) does to the appends. It opens up to 400
     connections, so the server's `max_connections` must allow that plus what is already connected (levels that do
     not fit are skipped and say so); it cannot vary `max_connections` itself (the container runs 50, 500 and 2 000).
     Needs `pg_stat_statements` preloaded. Results in `p0-12-probe-cost.txt`, append doc §12.
   - `run ProjectionProgressSpikeIT` (P0.13, suite `progress`): a view projection writing one row per event from a
     200 000-event table, with the resume point saved per event, per page (10, 100, 500 events, row by row, as a JDBC
     batch or as one set-based statement), periodically every second (today's model), and with the per-event fallback
     after a failing event, at 1 and 4 concurrent projections. One pass with the server's own `fsync` (the container
     runs `fsync=off` and `fsync=on`); WAL per event comes from `pg_stat_wal`, which is cluster-wide, so other traffic
     on the server inflates it. The commit latency is what changes most with a real disk. Results in
     `p0-13-progress.txt`, append doc §13.
2. **P0.0 baseline of the current engine.** The statement mix and WAL per event are machine-independent and should
   match; the latencies and the burst catch-up will not. `run CurrentEngineBaselineSpikeIT`
3. **Unattributed CPU (P0.9).** Does the idle and wake-up cost exist on real hardware? Compare server CPU per attempt
   (from `/proc/stat` or the cgroup) with `pg_stat_statements`' recorded execution. The `@SPIN` arms still apply,
   since they spin on the client; the `@BURNER` arms are skipped. For the symbol-level view, run on the database host
   during a window: `sudo perf record -a -g -F 997 -- sleep 20` and `perf report --sort comm,dso,sym`.
   `run UnattributedCpuSpikeIT`
4. **JDBI vs raw pgjdbc (P0.6).** Client-side CPU is unchanged by the server; what changes is how much a round trip
   costs relative to the client work. `run JdbiVsRawPgjdbcSpikeIT`
5. **Correctness spikes (P0.1, P0.2)** on this server and, if installed, against PostgreSQL 18 on port 5433:
   `run AppendXidBeforeIdentitySpikeIT`, `run CombinedProbeSnapshotOrderSpikeIT`, then the same two with
   `-Dspike.jdbcUrl=jdbc:postgresql://dbhost:5433/postgres`.

Each class takes its usual tuning properties (`-Dspike.contention.seconds`, `-Dspike.design.runs`,
`-Dspike.cpu.arms`, and so on), as documented in the class javadoc. A short smoke first, for example
`run ContendedAppendThroughputSpikeIT -Dspike.contention.runs=1 -Dspike.contention.seconds=2`, catches setup problems
in a minute.

## 7. What to record

With each results directory, keep a `machine.txt` written before the run:

```bash
{ uname -a; lscpu | grep -E 'Model name|^CPU\(s\)|MHz|Thread'; free -g; lsblk -d -o NAME,ROTA,MODEL,SIZE;
  /usr/lib/postgresql/17/bin/pg_config --version; cpupower frequency-info -p;
  cat /sys/devices/system/cpu/intel_pstate/no_turbo; ping -c 20 -q dbhost | tail -1; } > machine.txt
```

Run the CPU and disk lines on the database host, and the `ping` on the client. Add the `pg_test_fsync` and pgbench
results, whether the client was wired, and the client machine's model and CPU count. The results themselves land in
`components/postgresql-event-store/target/spike-results/p0-*.txt` on the client; move them to a directory named after
the machine and date before the next run, since the files are appended to.

## 8. Interpreting the differences from the VM

Read §9 of [event-store-phase0-append.md](event-store-phase0-append.md) first. In short, on the VM:

- About half of PostgreSQL's CPU per attempt depended on whether the CPUs went idle between requests, so absolute CPU
  per attempt was not a property of the statement.
- Ratios between arms were inflated whenever the arms differed in throughput: the slower arm left the CPUs idle more
  often and paid more per attempt.
- The estimate was that on a host whose CPUs do not halt between requests, B's extra cost should be close to the
  recorded execution difference (+3-5 µs per one-event append) rather than the +25-35 µs of §5.

On the laptop expect:

- **Server CPU per attempt minus recorded execution** is the unattributed part. If it shrinks to a few µs, §9's
  explanation holds and the VM's absolute numbers should not be used. If it stays large on real hardware, it is real
  cost of the statement shape and §9.5's caveat is lifted.
- **`fsync=on` adds a WAL flush per commit.** Throughput under contention is then bounded by the disk's flush rate
  times the group-commit factor, not by CPU. Arms that commit the same number of times will converge; any arm that
  commits more often (more units of work per event) falls behind by more than on the VM. Compare WAL bytes per event,
  which do not depend on the machine.
- **Network round trips** dominate single-thread latency. Compare round trips per unit of work (machine-independent)
  and server CPU, not client latency, when ranking the arms.
- **4 cores instead of 8** for the database means less parallel headroom under 8 writers; contended throughput may
  be CPU-bound on the server where the VM was not, which again changes the ratios. The `pg_stat_activity` wait
  samples show which: `CPU` versus `IO`/`WALSync`/`LWLock`.
- The client's machine figures (busy, context switches, IPIs) now describe the client alone; they are no longer
  comparable to the VM's, which included the database.

When a ratio between arms is the same on both machines, it is a property of the statements. When it differs, the
difference is the machine, and the laptop's figure is the one closer to a production database host.

## 9. Runs against the dedicated server

### 2026-11-12: read-side suites over Wi-Fi (`20261112-171749-Mac-external`)

Suites `notify`, `tail`, `probe-cost` and `progress` at commit `0dd5609aa`, all passed (58 minutes). Client: Apple M3
Max laptop, JDK 25.0.2. Server: PostgreSQL 17.11 in rootless Docker Compose on the Ubuntu machine, `fsync=on`,
`synchronous_commit=on`, `wal_sync_method=fdatasync`, `shared_buffers=8GB`, `max_connections=200`, `jit=off`. No
server CPU command and no host facts were recorded, so every server CPU column reads `NaN`.

**This run is bound by the network, not by the server.** The client was on Wi-Fi: ping 4.8 / 60.9 / 117.0 ms
min/avg/max, 48 ms deviation. A `SELECT 1` cost 6.9 ms at p50 and every single-statement figure sits on that floor
(commit p50 7.6 ms with one writer). Rootless Docker's user-space port proxy adds another hop. So absolute
throughput and client latencies from this run must not be used, and ratios between arms that differ in round trips
are inflated. What still holds is anything measured inside the server (plans, `pg_stat_statements` execution time,
WAL bytes, round trips per event) and the direction of ratios between arms with the same number of round trips.

What the run confirms or changes, compared with the VM:

| Item | This run | VM | Reading |
|---|---|---|---|
| P0.10, `pg_notify` inside the transaction | appends −30 % (1 writer), −34 % (8), −37-40 % (20), −15-17 % (8 on 10 aggregates) | −58-77 % with `fsync=on` | Same direction. Smaller here because each writer spends most of its time waiting on the network, not on the NOTIFY lock. Not a contradiction; the size needs a wired run. |
| P0.10, after commit coalesced per JVM | −1-5 % against no notify | about equal | Confirmed. |
| P0.10, after commit per writer | −29-34 % | −10-26 % | One extra round trip per append, which costs 7 ms here. A network effect. |
| P0.10, wake latency | p50 6.6-9.4 ms commit to listener socket | 0.09-0.18 ms | About one network delay. Not comparable. |
| P0.11, plans | `B` generic and `C` in every mode walk the primary key (~330-380 ms per call on 2 M rows); `D` (`UNION ALL`) uses index scans in every mode, 0.04-0.49 ms execution up to 1 000 holes | same shapes; `B` generic ~300 ms, `D` 9-121 µs | Confirmed on real hardware with `jit=off`. `D`'s execution is 2-4x the VM's. |
| P0.12, server execution of the sub-select | `SUB_AGG` 46 µs at 16 backends, 174 µs at 106: about 1.4 µs per backend (probe 75 → 186 µs: 1.2 µs) | about 0.55 µs per backend | The slope is 2-2.5x the VM's on this server. Server CPU unknown, so not explained. |
| P0.12, appends with probers | 100 connections, 20 writers: no prober 1 727/s; 10 tail-only probers every 10 ms −10-13 %; 10 combined probers −14 % | 73-75.5 against 71-71.5 thousand (−3-5 %) | The probe costs about 2-4 points more than the tail read alone, as on the VM. The 400-connection level, where the VM halved appends, did not run: `max_connections=200` limited the levels to 10 and 100. |
| P0.13, page against periodic | 4 projections: PAGE_100 451 against PERIODIC 230 events/s (2.0x); PAGE_500_SET 20 700 (90x) | 2x; 10-13x | The row-by-row ratio holds. The batch and set-based ratios grow with round-trip time, because they save round trips. |
| P0.13, per event and fallback | PER_EVENT −31 % against PERIODIC; one failure in 1 000 −21 % against PAGE_100; one in 100 below PER_EVENT | −33 %; −23 %; below PER_EVENT | Confirmed. |

Still needed from this machine: a wired run (or the client on the server host, `--external
jdbc:postgresql://127.0.0.1:5432/postgres`, accepting that the client then shares the server's CPU), the server CPU
and disk facts from §7, the 400-connection level of `probe-cost` (needs `max_connections` of at least 500), and the
write-side suites (`xid`, `table-design`, `contention`, `append`, `baseline`, `cpu`, `jdbc`, `probe`).

### 2026-11-13: every suite, client on the server host (`20261113-153049-lac-N85-N87-HJ-HJ1-HK1-external`)

All twelve suites at commit `153b850b3`, all passed (about 2 h 50 min). The client JVM ran on the database host itself
and connected over `127.0.0.1` (ping 0.026 / 0.041 / 0.054 ms), so the network is out of every figure. Machine: Intel
i7-7700HQ, 4 cores / 8 threads, 30 GB, Ubuntu with kernel 7.0, governor `powersave`, Temurin 25.0.5. Server:
PostgreSQL 17.5 in Docker Compose, `fsync=on`, `synchronous_commit=on`, `wal_sync_method=fdatasync`,
`shared_buffers=8GB`, `max_connections=500`, `jit=off`. The run needed `-Ddocker.skip=true` (the module's
docker-maven-plugin builds a CDC test image during `verify`); `scripts/run-phase0-spikes.sh` now adds it in external
mode.

Three things limit how the run reads:

- **Client and server share the 8 CPUs.** Cells that are CPU-bound (P0.0 burst catch-up, P0.6 contended tail read at
  82-85 % busy) are capped by the shared machine. The contended append suites are not: their `pg_stat_activity`
  samples are `LWLock:WALWrite` 8.6-17.8 %, `Lock:transactionid` 4.6-9.3 %, `IO:WalSync` 4.0-4.8 % and `CPU` only
  1.2-3.9 %, with the machine 24-62 % busy.
- **No server CPU.** No server CPU command was set, so every server CPU column is `NaN` and P0.9's question (server
  CPU per attempt minus recorded execution, §8) is still open. On a shared host a whole-machine `/proc/stat` would
  count the client too; the PostgreSQL container's cgroup v2 `cpu.stat` does not, and `SpikeServer` accepts it as
  `--server-cpu-command "cat /sys/fs/cgroup/<the container's scope>/cpu.stat"`.
- **The commit flush ran at two speeds.** In the fast phase a one-writer commit took about 1.1-1.6 ms and the
  contended suites appended about 1 900-2 350 events/s. In a slow phase every arm fell to about 660-780/s, commit
  time per attempt rose to about 4.6-4.9 ms and server execution at low load roughly doubled. It covers most of P0.7's
  contended windows (66 of 80), P0.7 run 2's uncontended rounds, the first two windows of P0.4 contention run 1, most
  of P0.9, and the opening of P0.6's contended run 1. Candidates are a checkpoint, the SSD's flush behaviour or
  thermal and frequency scaling under `powersave`; none is verified, and `pg_test_fsync` and the disk facts of §7 were
  not recorded. Every ratio below compares arms within the same phase.

Server execution is about 2.5-4x the VM's everywhere (P0.6, P0.7, P0.9, P0.11, encoding), today's statements
included, so it is this machine's speed and not a property of any one statement.

**Correctness.** No check failed: P0.1's xid-before-identity held in 24 of 24 cases with all 8 controls null; P0.7's
40 correctness lines and 80 windows were clean (no non-contiguous order, head equals max, 0 burned values in the head
arms); P0.2 found no violation and its controls still detect the broken orders; P0.0's concurrent writers delivered
everything without duplicates; P0.13's checks and P0.11's sanity lines were all `ok`. Only 17.5 was run, so P0.1 and
P0.2 on 16 and 18 still need another server.

**What holds unchanged.** Everything that does not depend on the machine matches the VM: round trips per unit of work
(P0.0 3 / 2 / 3 / 3 / 11; P0.4; P0.6's reconciliation 3 / 2 / 4 / 4 with proxy and trace equal in 50 of 50),
statements per event (P0.0 steady 68.9-69.1 and 10.6), WAL bytes per event (P0.0 948-1 056 B; P0.7 BASE 892-895 down
to COMBO 551-563 B, within a few bytes of the VM in every option), P0.0's idle statement rate and its polling-bound
latency (p50 48.5-52.9 ms), P0.11's plan shapes, and the jsonb-over-json conversion cost (0.73 µs per event).

What changes, compared with the VM:

| Item | This run | VM | Reading |
|---|---|---|---|
| P0.4, contended B against A | Fast phase, run 2: A 1 945, C 2 036, B 1 909, B + `ON CONFLICT DO NOTHING` 1 961 appended/s (B/A 0.98) | B −30 % against A; `ON CONFLICT` about half | **The gap disappears**, as §8 and append §5.5 predicted: with a real flush every arm commits once per attempt and waits on the WAL. `ON CONFLICT` is no longer worse. |
| P0.4, values burned per 1 000 appended | A 590-626, B 524-538, `ON CONFLICT` 549-553; zero-row results about 6 % of attempts | A 458-465, B 241-270 | **B no longer halves the holes**; it saves 12-15 %. With slow commits an overlapping append almost never sees a committed competitor, so most conflicts end in a unique violation either way. The window that matters is the competitor's commit, which extends append §8.3. |
| P0.4, advisory lock as a separate statement | 0 burned; 1 687-1 872/s against A 1 950-1 971 | 0 burned; a third of A's throughput | Zero holes holds; its cost falls to −5-14 %. As first CTE it still does nothing (514-520 burned). |
| P0.4, uncontended B against A | 100 events 4.19-4.25 against 6.00-6.45 ms (−30-35 %); 5 aggregates −37-44 %; 10 events −7-9 % even with expected order; 1 event a tie | −35 %; about half; tie | Holds. Every 1-event latency (1.3-1.7 ms) sits on the commit flush. |
| P0.7, contended throughput per option | All arms within 659-778/s in the slow phase; one fast window each: BASE 2 224, guarded 2 350, `FOR UPDATE` 2 183, COMBO_ALL 2 314 | guarded −8-18 %, `FOR UPDATE` −30 %, COMBO_ALL −15 % against BASE | **The VM's ranking is gone**: under the flush the options converge. One fast window per arm is too little to rank them on a CPU-bound host. |
| P0.7, burned per 1 000 without a head table | 560-589; the 50 µs delay after the lookup changes nothing | 276-305; the delay cut it to 219-225 and gained 35 % | Same mechanism as P0.4: commit time, not the lookup-to-append gap, sets the conflict window. |
| P0.7, uncontended | Server execution for 100 events BASE 1 412-1 424 µs down to COMBO_ALL_BYTEA 893-910; reading 100 events json/bytea 0.27-0.38 against BASE 0.43-0.53 ms | 490-496 down to 325-349; 0.12 against 0.18 ms | Ranking and direction hold; absolute about 2.8x. |
| P0.9, B against A | Fast phase: A 2 260-2 391, B 2 164-2 273/s (−8 % to +2 %). Recorded execution A 62.9-71.3, B 77.8-85.8 µs | NORMAL A 29.6-34.2k, B 26.0-27.4k; execution 16.6-16.9 against 20.5-21.0 µs | **The VM's NORMAL gap does not reproduce**, as append §9's idle explanation predicted. B's execution excess keeps the VM's 1.2x ratio, so that part is the statement's. |
| P0.6, contended raw pgjdbc against JDBI | lookup + insert: raw +13-15 % (NORMAL), +12-14 % (SPIN); lookup + B: +10-14 %, +9-12 % | NORMAL JDBI 3-10 % ahead or a tie; SPIN raw +11-20 % | **The SPIN verdict holds and the NORMAL flip disappears**, confirming it was the VM's idle effect. |
| P0.6, uncontended | Same ranking; JDBI's read-ahead lookup round trip costs about +95-105 µs (VM about +40); client CPU per call JDBI minus raw +15-34 µs (VM +6-8) | | Direction holds; the overheads scale with the slower CPU. JDBI's `LOOKUP_NO_FETCH_SIZE` and `findFirst` arms did not run, so the fetch-size fix is not re-tested. |
| P0.0, OCC contended (8 writers, 10 aggregates) | 38 409-38 426 appended in 20 s; 619-620 values burned per 1 000 appends | 593 740-657 312; 466 | Commit-bound, 15x lower. More burned values for the same reason as P0.4. "Every lost race burns one value" holds. |
| P0.0, concurrent writers | late deliveries 11 / 4 (p50 3.9 / 3.1 ms) | 2 / 1 (1.2-2.5 ms) | Slower commits widen the window in which commits land out of order. Transient gaps exceed holes by exactly the late count. |
| P0.10, `pg_notify` inside the transaction | 8 writers −75 % against `NONE` (247-258 against 1 015-1 039/s), 20 writers −90 %, 8 on 10 aggregates −64 %; writers in `Lock:object` 86-95 % of samples | −57-63 %, −76-77 %, −44-45 % | **Back and larger than the VM.** `IN_TX` is pinned near 250/s at every writer count: notifying commits serialise and lose group commit, which costs more when the flush is real. The Wi-Fi run's −30-40 % was hidden by the network. |
| P0.10, after commit | Coalesced per JVM −4 % to +1 %; one notify per writer −2 % to +2 % | about equal; −10-26 % | Coalesced confirmed free. Per writer is also free here: the extra statement hides behind the flush-bound commit. |
| P0.10, wake latency | Commit returned to listener socket: after commit p50 0.40-0.94 ms, inside the transaction 0.015-0.040 ms; through pgjdbc 1.14-2.18 ms | 0.09-0.18 ms | After commit adds 0.4-0.9 ms, against 0.1-0.2 on the VM; the notifier hand-off competes for the shared CPUs. |
| P0.11, `D` (`UNION ALL`) execution | 36-46 µs up to 10 holes, 53-82 at 100, 280-318 at 1 000; today's tail read `A` 25-39 µs; `B` generic and `C` walk the primary key (289-406 ms) | 9-12, ~120 µs; `B` generic ~300 ms | Plans confirmed a second time. `D`/`A` is 1.1-1.2 up to 10 holes; the absolute 3-4x is the machine. |
| P0.12, per-backend slope | `SUB_AGG` 36.6 / 134.8 / 561.0 µs at 16 / 106 / 406 backends: 1.34 µs per backend | about 0.55 µs | Same as the Wi-Fi run (1.4), so a property of this host, not of the network. |
| P0.12, appends with probers | 100 connections, 20 writers: 10 tail-only probers −8 %, 10 combined probers −27 %. 400 connections, 80 writers (first time on this server): `NONE` 14 963-15 169/s, 40 tail-only probers −51 %, 40 combined probers −80 % (2 925-3 215/s), append p99 96-104 ms | 100: −3-5 % against tail-only; 400: −49 % against tail-only, p99 55-64 ms | **The collapse reproduces and is deeper.** Append p50 rises here (4.8 → 20 ms) where it fell on the VM. No wait sampling ran at that level, so it is not attributed. |
| P0.13, page against periodic | 4 projections `PAGE_100` 17 502 against `PERIODIC` 2 239 events/s (7.8x), `PAGE_500_SET` 105 052 (47x); 1 projection 8.6x | 2x / 10-13x (`fsync=off`); 4.1x / 5.7x (`fsync=on`) | The ratio grows with the price of a flush, because the page saves flushes. |
| P0.13, per event and fallback | `PER_EVENT` against `PERIODIC` −0.1 % (4 projections), −3.8 % (1); one failure in 1 000 −44 % against `PAGE_100`; one in 100 below `PER_EVENT` | −33 % (`fsync=off`), −20-23 % (`fsync=on`); −23 %; below `PER_EVENT` | **The per-event penalty disappears** (both commit once per event and wait on the flush); the fallback penalty doubles. This revises the Wi-Fi run's "−31 %, confirmed". |

Unexplained or inconsistent, to keep in mind:

- P0.10 at one writer: the two `NONE` windows disagree (479 and 206/s, commit p50 1.2 and 5.4 ms), so the
  one-writer `IN_TX` cost lies anywhere between −2 % and −57 %.
- Commit p50 was 5-7 ms in P0.10 but 1.0-1.5 ms in P0.13's per-event arm on the same server.
- P0.0's second burst run wrote 479 760 B of WAL during catch-up against 1 064 B in the first, probably autovacuum
  after the 100 000-row append.
- The headers of `p0-9-cpu.txt` and `p0-4-encoding.txt` say `fsync=off`; that label is hard-coded, and the server
  facts on the same line say `fsync=on`.
- The server reports 17.5 here, while the 2026-11-12 entry above records 17.11 for the same machine. The compose file
  above pinned `postgres:17.5`, the image the container path runs; it now pins the latest 17, `postgres:17.12`, and
  the next run should be on that.

Still needed from this machine: the server CPU through the container's cgroup `cpu.stat` (above), which reopens P0.9
and attributes P0.12's 400-connection collapse; `pg_test_fsync` and the disk facts of §7, with the slow phase
explained or avoided (governor `performance`, a checkpoint before each suite); a rerun on the latest 17 (17.12); and
a full run on 18 (its latest minor), which also covers P0.1 and P0.2 on 18. P0.1 and P0.2 on 16 need a 16 server.
