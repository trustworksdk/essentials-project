# Event store redesign: phase 0 in one page

Status as of 2026-11-15, P0.16 included. Numbers are from the dedicated server (Intel i7-7700HQ laptop, 4 cores / 8 threads,
client on the same host over loopback, `fsync=on`; PostgreSQL 17.5 on 2026-11-13, 17.12 on 2026-11-14, 18.7 on
2026-11-15) unless marked **VM** (the earlier 8-CPU Docker VM, `fsync=off`, client and server sharing the VM).

## Bottom line

Phase 0 confirms the redesign's correctness assumptions and its write-path shape. It does not yet settle the read
side. The single-statement append (§5.1) is kept as written: its xid is always taken before the identity value, on
16, 17 and 18. The combined probe (§4.10) is kept, provided it runs in autocommit or after `pg_stat_clear_snapshot()`.
The engine stays on raw pgjdbc with no second driver: JDBI costs only a few µs, and pgjdbc's `PINNED_PEEK` drain
delivers notifications as fast as Vert.x. Transactions the engine owns can be sent as one explicit `BEGIN … COMMIT`
flush through pgjdbc (P0.14), never as an autocommit batch. Everything the model and the read-side spikes found is
recorded as "proposed revision, not decided": P0.5's F1-F6, the `UNION ALL` hole re-read, the after-commit coalesced
notify, reading `pg_stat_get_activity(NULL)` directly, and the page-size back-off. These have to be accepted, changed
or rejected before phase 2. PostgreSQL 18.7 changes no phase-0 decision. Nothing may be released as supported on a
managed PostgreSQL service until P0.3 passes for that service.

## Per spike

| Spike | Question | Answer / decision | Confidence or caveat |
|---|---|---|---|
| P0.0 | What does today's engine cost? | Baseline recorded: 68.9-69.1 reader statements per appended event at 10 ev/s (6.8 per delivery), 1.04 per delivery at 100 ev/s; idle 1.5 statements/s per subscription; OCC burns 616-625 identity values per 1 000 appends (VM 466). These numbers are the phase 2/3 gates. | Machine-independent counts match the VM. The second burst run's ~480 000-490 000 B of WAL is probably autovacuum. |
| P0.1 | Is the xid taken before the identity value? | **Holds.** 24 of 24 cases with the `xid` CTE, all controls null; VM 72 of 72 on 16.15, 17.5 and 18.4. §5.1 kept, no separate `pg_current_xact_id()`. | Rests on executor behaviour, so it must run in CI against every supported major. |
| P0.2 | Does the combined probe read writers after its snapshot? | **Holds in autocommit.** It is unsafe inside a transaction that read `pg_stat_activity` earlier, because backend status is cached per transaction. Kept, with autocommit or `pg_stat_clear_snapshot()`. | The probe must also return the running set when the tail is empty (not tested). |
| P0.3 | Is `backend_xid` visible on managed services? | **Deferred**: no RDS, Aurora, Azure or Cloud SQL access. Partial: Supabase passes for its `postgres` role (a member of `pg_read_all_stats`). | Not checked: a role without that membership, and the transaction pooler. |
| P0.4 | Is the single-statement append faster? | **Gate partly met; §5.1 kept.** Uncontended: B is −30-35 % at 100 events, −37-44 % for 5 aggregates, −7-9 % at 10 events, a tie at 1 event. Contended, the arms converge (B/A ≈ 0.98) and B burns 12-16 % fewer values, against about half on the VM. An advisory lock as a separate statement gives 0 holes at −3 to −5 %. | B's extra server CPU is 2-3x its execution difference. The 18.7 append rows fell in the slow phase and are not usable; the contention figures are valid. |
| P0.5 | Does the state machine hold? | **I1, I3, I12 and I13 hold; I2 fails in `FRESH` as written** and holds once the tail re-reads the outstanding holes (F1). F1-F6 are proposed revisions. | Deterministic Java model, not TLA+. Fencing (I5), `resetFrom` (I6) and tenants were not modelled. |
| P0.6 | Is JDBI's overhead material? | **No.** Contended, raw pgjdbc is +6-16 % ahead; JDBI costs +20-70 µs of server CPU per attempt, which fits its read-ahead round trip on `setFetchSize(1)`. Raw pgjdbc stays, chosen for its capabilities. | The VM's NORMAL-mode JDBI lead was an idle effect. The fetch-size fix was not re-tested on the server. |
| P0.7 | Table design options | **With durable commits the head table costs no throughput.** COMBO_ALL is +8 % over BASE on 17.12 (+4 % on 18.7) and COMBO_ALL_SCALAR +12 % on 17.12, both with less server CPU. WAL drops from 892-895 B per event (BASE) to 551-563 B (COMBO). | Findings only, no decision. P0.7 has no gate row in §18. |
| P0.9 | Where does the unattributed server CPU go? | Outside recorded execution, A spends 274-295 µs and B 301-326 µs per attempt, the same under NORMAL and SPIN, on 17 and 18. The VM's idle/wake-up explanation does not apply on this host. | **Open**: the split between commit flush, protocol and background processes. |
| P0.10 | Cost of the tier-1 wake-up | `pg_notify` inside the transaction: −75 to −79 % at 8 writers and −81 to −85 % at 20, with a ceiling of about 1 000-1 160/s. After commit, coalesced per JVM: about −10 % at 8 writers, even at 20. Per writer: −32 to −38 % at 8. Proposed: tier 1 after commit, coalesced. | Not decided. The pgjdbc wake latency (p50 2.3-19.8 ms against 0.02-1.09 ms on the wire) is not attributed. |
| P0.11 | Cost of re-reading holes in the tail | `OR = ANY` walks the whole primary key under a generic plan (289-406 ms); `UNION ALL` (D) uses index scans in every mode, 36-46 µs up to 10 holes and 280-318 µs at 1 000. Proposed: a `UNION ALL` branch. | The trap is identical on 18. Open: a range branch that does not trip the JIT, and more than 1 000 holes. |
| P0.12 | Cost of the probe's `pg_stat_activity` read | 1.34-1.41 µs per backend (VM ~0.55). At 400 connections, 40 combined probers cut appends by 80-84 % (tail-only probers: 51-54 %), and the server reaches 6.0 cores. Proposed: read `pg_stat_get_activity(NULL)` directly; probe interval not fixed per JVM. | Not decided. On 18.7 the probe's own execution does not grow, yet throughput is 17 % lower, so the extra cost is elsewhere. Client/server split open. |
| P0.13 | Resume point in the handler transaction | Pages beat the periodic save: `PAGE_100` 7.8x with 4 projections (2026-11-13), set-based `PAGE_500_SET` 45.6-49.8x. The per-event penalty disappears; one failure in 1 000 costs −44 % against `PAGE_100`. Proposed: the fallback backs off the page size. | Single windows. Open: `ViewEventProcessor`'s savepoint batch mode, views with secondary indexes. |
| P0.14 | One flush for engine-owned transactions | **Passes at 8 writers** for 10 events (+31 %), 5 aggregates (+17 %) and the page (+20 %); fails for a single event (−7 %) but gains +49 % at 20. Saves 42-137 µs of server CPU per removed round trip. Use explicit `BEGIN … COMMIT` (`D_EXPLICIT`, on pgjdbc). | An autocommit batch is not atomic past about 64 KB (255 of 399 rows committed). Wire client arm C not built. The five-aggregate rollback still needs a correctness test. |
| P0.15 | Group commit for engine-owned writes | **Parked** 2026-11-14. | Revisit only if round trips still dominate after P0.14. |
| P0.16 | Owned, pre-prepared read connections | **Run 2026-11-15 on 18.7: use `force_generic_plan` and one flush per wake.** An owned connection under a generic plan saves 57-189 µs server CPU per tail read (p50 −24 to −56 %), but owning the connection alone saves nothing: under `auto` PostgreSQL re-plans every call. Reading 10 woken types in one pgjdbc flush costs −58 % server CPU (−66 % at 50), one round trip. The minimal wire client is within −11 to +5 % of pgjdbc. | The pool with a generic plan was not measured. Generic plans are safe only for statements whose plans were checked (not P0.11's `OR = ANY`). |
| P0.17 (a) | Faster notification listener? | **pgjdbc only.** At 3 000/s, `PINNED_PEEK`'s p99 is 0.60-0.72 ms against Vert.x's 0.60-0.66 ms; the stock drain lags up to 1.4 s inside pgjdbc. | `QUERY_POLL` is a borderline fallback (2.10-2.17 ms p99 in the 20-writer burst). |
| P0.17 (b) | Request-path pipelining | **Parked** 2026-11-14: it needs a wire client of our own. | — |

## What changed when we moved off the VM

A real WAL flush (`fsync=on`) changes the contended picture. On the VM, B appended 30 % slower than A, the head-table
options ranked clearly, and B halved the holes. On the server every arm commits once and waits on the flush, so:

- The arms converge, and B saves only 12-16 % of burned values.
- The head table becomes free.
- Burned values per 1 000 rise to 616-625.

Several VM-era readings of cost changed:

- **P0.9:** the idle/wake-up explanation fails; about 280 µs per attempt stays outside recorded execution.
- **P0.6:** the NORMAL-mode JDBI lead disappears.
- **P0.10:** the in-transaction notify penalty grows, and a notify per writer stops being free.
- **P0.12:** the per-backend slope is about 2.5x the VM's, and the collapse deepens to −80-84 %.
- **P0.13:** the per-event penalty disappears, page ratios grow with the cost of a flush, and the fallback penalty
  doubles.

Server execution is 2.5-4x the VM's everywhere; that is the machine's speed, not any one statement. A slow flush phase
(commits about 3-5x slower) hit parts of the 17.5 and 18.7 runs. Rows that fell in it are marked unusable or were
replaced.

## Open items

- **Slow flush phase cause.** It came back under governor `performance`, so the governor did not cause it. Storage is
  suspected (write cache, thermal throttling, SSD housekeeping). Next: `pg_test_fsync`, `iostat -x` during a run, and
  the SSD's temperature. Whether 18's `io_method` or checksums add to it cannot be separated.
- **P0.9's gap split**: A 274-295 µs and B 301-326 µs per attempt outside execution, across commit flush, protocol
  and background processes. This needs per-backend CPU or a separately timed commit.
- **A separate wired client host**, to split client from server in P0.12's collapse and in P0.10's pgjdbc wake
  latency. The listener on a separate host is also unmeasured for P0.17 (a).
- **P0.16 follow-up**: measure a pooled connection under `force_generic_plan`.
- **P0.14 follow-ups**: a correctness test for the five-aggregate rollback on a zero-row conflict. The "B within
  10 % of C" clause was not evaluated because C was not built.
- **P0.3**: the managed services.
- **Proposed revisions** (F1-F6, P0.10-P0.13) still to be accepted, changed or rejected before phase 2.
- **Closed 2026-11-15:** P0.1/P0.2 on PostgreSQL 16 was dropped. Both already held on 16.15 in the VM, and they test
  ordering rules, not timing.

## Where the detail is

- **Spike definitions, gates, results table and design consequences:**
  [event-store-subscription-redesign.md](event-store-subscription-redesign.md) §18 ("Phase 0 — investigation" and the
  results table).
- **Dedicated-server runs (2026-11-12 to 2026-11-15), which supersede the VM where they differ:**
  [event-store-phase0-external-db.md](event-store-phase0-external-db.md) §9.
- **P0.0 baseline:** [event-store-phase0-baseline.md](event-store-phase0-baseline.md).
- **P0.4, encoding, P0.7 table design, combined options, P0.9 CPU attribution, and P0.10-P0.13 (VM):**
  [event-store-phase0-append.md](event-store-phase0-append.md) §§2-13.
- **P0.5 model:** [event-store-phase0-model.md](event-store-phase0-model.md).
- **P0.6 JDBI against raw pgjdbc:** [event-store-phase0-jdbc.md](event-store-phase0-jdbc.md).
- **P0.14-P0.17 background and the parking decision:**
  [event-store-phase0-beyond-jdbc-spikes.md](event-store-phase0-beyond-jdbc-spikes.md).
- **Listener options:** [pgjdbc-notification-drain-options.md](pgjdbc-notification-drain-options.md).
- **Overview:** [README.md](README.md), phase-0 rows.
