# Proposal: shard-owned consumer overhead that grows with instances

**Status (2026-11-26, checked against the code on `release/0.61.1`):**

- **Candidate 1 (one horizon probe per pump pass): shipped in 0.61.0.** It was built as `3be11d757` and reached `main`
  in the squash merge of #57 (`687826c69`), so that commit id is not in `main`'s history. Measured in
  `docs/design/durable-queue-measurements.md` §3.12. The built version differs from this proposal in two places; see
  [As built](#as-built-0610).
- **Candidate 2 (cursor-read cost when units are split): not started.** No profile has been taken and none of its
  possible directions is in the code; see [Status of candidate 2](#status-of-candidate-2-2026-11-26).

Evidence from the trading demo's EF-2 measurements (`docs/design/key-partitioned-event-processing.md` §5,
"Investigation of the forwarder slowdown").
**Module:** `components/postgresql-queue-shard-owned`.
**Where it would land:** candidate 1 landed on `main` with #57. Candidate 2 would start on a new branch created from
`origin/main`.

## What was measured

A uniform workload — 202 000 ordered messages over ~1 000 keys, forwarded by one reader and consumed with
`GlobalCompetingConsumers` — was drained by one instance and by two, with PostgreSQL pinned to four cores and each
instance to its own two. `pg_stat_statements` was reset per run.

| PostgreSQL time per drain | 1 instance | 2 instances |
|---|---|---|
| Batched cursor reads (`LATERAL` over the owners' cursors) | ~2 s | ~25 s (28 500 calls, 0.7–0.9 ms) |
| Watermark horizon probes (`SELECT backend_xid … FROM pg_stat_activity`) | 2.3 s, 97 712 calls | 9.4 s, 204 548 calls (~4 800/s) |
| Acknowledge deletes | 1.9 s | 6.8 s |
| The producer's `INSERT` into the ordered lane | 2.7 s | 3.2 s |

The messages and the producer's work are the same in both columns; the consumer side's database work is not. A second
instance added work proportional to instances and units, not to messages, and the single producer — which shares the
database — slowed with it. Two-instance runs on that machine varied by up to 40 %, so the figures show direction and
order of magnitude, not a precise ratio.

## Candidate 1 — one horizon probe per pump pass, not per owner

**Before (as proposed against the code of 2026-11-08; line numbers from that time, since changed).** `ShardPump` ran
one batched read for every attentive owner (`ShardPump.batchRead`) and then called `pumpOnce` on each. Each
`OrderedShardOwner.pumpOnce` that had watermark candidates called `advanceWatermark`, which issued its own probe. A pump
serving 32 attentive units issued
up to 32 identical `pg_stat_activity` scans per pass. Reading `pg_stat_activity` takes the procarray and backend-status
snapshot, so it is not free at thousands per second.

**Proposal.** Take the probe once per pass, after `batchRead`, and hand the same set to every owner's
`advanceWatermark` in that pass. Owners that read on their own (no batch) keep probing themselves.

**Why it stays correct.** The watermark needs two things from a probe (see the `OrderedShardOwner` gotcha in the
module's `CLAUDE.md`, from the out-of-order delivery fix): a candidate is *recorded* with a probe taken after the read
that observed its `maxSeen`, and *retired* against a probe taken before the read that is supposed to have seen the
rows. A probe taken once, right after the pass's batched read, is after every owner's read in that pass, and before
every owner's read in the next pass. Both conditions hold for all owners at once.

**Expected effect.** Probes per pass from O(attentive owners) to 1. In the measurement, ~4 800/s probes would become
a few hundred.

**Risk.** Low. The set is read-only and pass-scoped. Any probe failure must fail the pass for all owners, as one
owner's probe failure does today. (The built version handles failure differently; see below.)

### As built (0.61.0)

Where it lives: `HorizonProbingOwner` (new interface), `ShardPump.probeHorizon`, and
`OrderedShardOwner.applyHorizonProbe`; `OrderedShardOwner.advanceWatermark` uses the pass's probe when it has one.
Probes are counted in the `horizonProbes` owner metric, once per shared probe.

It differs from the proposal in two places:

| | Proposal | Built |
|---|---|---|
| When the probe is taken | Once, after the pass's batched read; owners that read on their own keep probing themselves | After *all* of the pass's reads: the batched read, then a `readAhead` by each owner that reads on its own. Those owners share the probe too, so the probe still follows every owner's read |
| Probe failure | Fails the pass for all owners | On a connection that is still valid, it is logged at ERROR and each owner probes for itself that pass, as before the probe was shared. On a broken connection the exception is rethrown and the pump reconnects. An owner that throws while being asked whether it wants the probe, or while reading ahead, falls back to probing or reading for itself |

A first version took the probe after the whole pass instead; it was dropped before it was measured cleanly
(measurements doc §3.12).

**Measured effect** (`ShardOwnedOrderedLoadedCostIT`, 8 ordered queues on 2 pumps, one process; §3.12): horizon
probes per delivered message fell from 2.35 to 0.52 at 2 000 msg/s and from 2.59 to 0.24 at 3 000 msg/s;
transactions per message fell by about a quarter; p99 fell from 20.9 to 13.7 ms and from 151 to 71 ms. One cost not
foreseen here: pumps that no longer wait on a probe per owner run more passes, so read statements per message rose
at 3 000 msg/s (1.00 to 1.99), and with handlers that burn CPU at saturation p99 got worse (121 to 363 ms). Idle cost
is unchanged.

## Candidate 2 — the batched cursor read's cost when units are split

**Observed.** With two instances the `LATERAL` cursor read ran ~6× more often and ~3× slower per call (0.9 ms vs
0.3 ms). That is consistent with more passes that each find less to do. With units split, each pump is woken more often
for fewer rows, and the head-of-line check refuses rows whose key is in flight, so they are re-read.

**Not yet understood.** Unlike candidate 1, this one needs a profile before a design. What to measure first:

- cursor reads per delivered message, one instance vs two, from the existing `orderedReadStatements` and `delivered`
  metrics;
- rows returned per read versus rows dispatched (re-read waste);
- whether the per-call cost rises from the `unnest` over more cursors, or from index churn from concurrent acknowledge
  deletes.

**Possible directions**, depending on the answer: back off a pump whose attentive owners are all key-blocked; read
fewer rows per owner when its keys are in flight; batch acknowledgements across owners in a pass.

### Status of candidate 2 (2026-11-26)

Nothing has been done. Checked against the code:

| Item | State in the code |
|---|---|
| Profile: one instance against two | Not taken. No two-instance measurement in the measurements doc or the release notes |
| Back off a pump whose owners are all key-blocked | Absent |
| Read fewer rows per owner when its keys are in flight | Absent. The ordered lane's cursor read still uses the fixed `readBatchSize` (`ShardPump` batched read, `OrderedShardOwner` own read). Only the unordered lane's read became adaptive (capped at 64 rows or twice the free handlers, 0.60.1) |
| Batch acknowledgements across owners | Absent. The ordered lane acknowledges with one `DELETE … WHERE queue_id = ? AND shard = ? AND seq = ANY(?)` per shard (`ShardOwnedStorage`) |

Related but not this candidate: 0.61.0 merged each head sweep's two statements into one `UNION ALL` statement, which
lowered read statements per delivered message from 3.82–3.98 to 3.34–3.67. That cuts per-pass cost everywhere; it
does not address why splitting units across instances makes the cursor read more frequent and slower per call.

## How it would be measured

Per the standing rule for queue changes: before/after benchmark suites against `main`, run from worktrees outside
`/workspace`, alternating rounds, `-Dfailsafe.forkCount=1`. Plus two targeted measurements:

1. `ShardOwnedConcurrentProducersOrderIT`'s workload with two consumers, counting horizon probes and cursor reads per
   delivered message, before and after.
2. The demo's `m4-drain` replay (uniform stretch, one and two instances, pinned cores), with `pg_stat_statements`.

Candidate 1 is a small change with a clear expected effect. Candidate 2 starts with a measurement, not a change.

For candidate 1, neither targeted measurement was run as written. It was measured with `ShardOwnedOrderedLoadedCostIT`
instead (one process, 2 pumps; measurements doc §3.12), so the two-instance effect it was motivated by has not been
re-measured. Both measurements above still apply to candidate 2.

## Not proposed

- **Removing the per-enqueue `pg_notify`.** Measured at 0.001 ms per call and absent from the waits.
- **Changing the ordered lane's schema or the producer path.** The producer's statements were unchanged between one and
  two instances.
