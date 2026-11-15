# postgresql-event-log

The new PostgreSQL event store engine ("event log"), built beside `components/postgresql-event-store`, which it must
not change. Nothing depends on this module yet; it is unreleased phase-2 work on a feature branch.

- Plan: `docs/plans/event-store-subscription-redesign.md`. Phase-2 design and decisions: `docs/plans/event-store-engine-phase2.md` (§5 overrides any "Proposed").
- Package `dk.trustworks.essentials.components.eventlog`; tables prefixed `el_`.
- Raw pgjdbc only. No JDBI, Reactor, Jackson or `postgresql-event-store` — `architecture/DependencyRulesTest` enforces it. Payloads are opaque JSON text, bound as `text[]` and cast to `json`, so bytes round-trip exactly.
- DDL text lives only in `EventLogSchemaContributor` (the schema harness rule, `SchemaRulesTest`); `EventLogSchema` runs it under the bootstrap lock when the component owns its schema. All other SQL lives in `EventLogStorage`. Every derived name goes through `AggregateTypeTables` (validated, never truncated).
- Appends run on the caller's `Connection` and never commit. Every append statement takes `pg_current_xact_id()` first (I4, decision 4) — do not remove the `xid` CTE because the head write happens to assign one too. `EventLogAppendXidWindowIT` guards it.
- Event types are interned in their own committed transaction on the engine's `DataSource`, cached only after commit (decision 2).
- Start-up refuses a database where `backend_xid` of another backend is invisible, and an event table whose `global_order` identity cache is not 1.
- Subscriptions (phase 3, `docs/plans/event-store-engine-phase3.md`): `ReaderLoop` = one platform thread + one held connection per `EventLog`, a `TailReader` per active type (starts at head, F4), attach/detach only at the quiescent point (request queue), never waits on an inbox (overflow → close + detach), read failure → every attached inbox closed `READER_RESTART`. `TailReader` keeps phase 2's logic (probe, holes, F1/F2) but no connection; a type's reader uses the seams/relation of the subscription that activated it. `FreshSubscription` = virtual delivery thread, no held connection: catch-up `(P, W]` → WaitingForWatermark → attach → bridge `(P, C]` (attach holes re-read per page) → attached; `HandledSet` (P + runs) dedupes, resume = `P + 1` from received watermarks only. `Inbox` bound counts events, empty inbox accepts any batch. `ProgressStore` (`el_progress`, reset-epoch CAS, no lease). Every reader/bridge statement re-reads holes as a `UNION ALL` branch, never `OR`; `EventLogGenericPlanIT` checks every `TailStatements` generic plan.
- Tests reach the reader via package-private `EventLog.reader(type)`; `AbstractSubscriptionIT.terminateReaderBackend()` forces a reader restart. Never block the loop thread forever in a hook: it is shared, so the next subscription would never start.
- Phase-0 mutants live in `WatermarkMutant`, settable only through package-private `TestSeams`; every invariant IT (I1-I3, I11) has a `must_fail_*` test per mutant. Never add a public way to set them.
- Tests: `*IT` use `EssentialsTestContainers.postgres()` (static container); unit tests `*Test`.
