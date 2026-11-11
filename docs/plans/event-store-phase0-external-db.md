# Phase 0 spikes against a dedicated PostgreSQL server

Every phase-0 spike of [event-store-subscription-redesign.md](event-store-subscription-redesign.md) so far ran
against a Testcontainers PostgreSQL with `fsync=off`, on the same 8-CPU arm64 Docker VM as the client. §9 of
[event-store-phase0-append.md](event-store-phase0-append.md) showed that about half of PostgreSQL's CPU per attempt
on that VM is idle and wake-up cost, so the absolute numbers, and possibly the ratios between arms, are specific to
that machine. This document describes how to run the same spikes against a dedicated database server, with the client
on another machine, and how to read the results.

The target machine is an old Clevo laptop: Ubuntu, 30 GB RAM, a 4-core Intel i7, `fsync=on`.

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
