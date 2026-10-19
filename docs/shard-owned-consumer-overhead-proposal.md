# Proposal: shard-owned consumer overhead that grows with instances

**Status:** proposal, not implemented. Evidence from the trading demo's EF-2 measurements
(`docs/key-partitioned-event-processing.md` §5, "Investigation of the forwarder slowdown").
**Module:** `components/postgresql-queue-shard-owned`.
**Where it would land:** a new branch off `main` once 0.60.1 is released — not `queue_improvements`, which is being
assembled into that release.

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

**Today.** `ShardPump` runs one batched read for every attentive owner (`ShardPump.batchRead`, line 140) and then calls
`pumpOnce` on each (line 144). Each `OrderedShardOwner.pumpOnce` that has watermark candidates calls
`advanceWatermark`, which issues its own probe (`OrderedShardOwner.java:934`). A pump serving 32 attentive units issues
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
owner's probe failure does today.

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

## How it would be measured

Per the standing rule for queue changes: before/after benchmark suites against `main`, run from worktrees outside
`/workspace`, alternating rounds, `-Dfailsafe.forkCount=1`. Plus two targeted measurements:

1. `ShardOwnedConcurrentProducersOrderIT`'s workload with two consumers, counting horizon probes and cursor reads per
   delivered message, before and after.
2. The demo's `m4-drain` replay (uniform stretch, one and two instances, pinned cores), with `pg_stat_statements`.

Candidate 1 is a small change with a clear expected effect. Candidate 2 starts with a measurement, not a change.

## Not proposed

- **Removing the per-enqueue `pg_notify`.** Measured at 0.001 ms per call and absent from the waits.
- **Changing the ordered lane's schema or the producer path.** The producer's statements were unchanged between one and
  two instances.
