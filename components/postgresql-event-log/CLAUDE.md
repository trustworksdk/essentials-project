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
- Subscriptions (phase 2): `TailReader` (one connection, one thread; cursor, holes, candidates, watermark F2, restart F4), `FreshSubscription` (FRESH, periodic progress, non-exclusive, backstop only), `ProgressStore` (`el_progress`, reset-epoch CAS, no lease). The tail statement re-reads holes as a `UNION ALL` branch, never `OR` (generic plans walk the primary key); `EventLogGenericPlanIT` checks every reader statement's generic plan.
- Phase-0 mutants live in `WatermarkMutant`, settable only through package-private `TestSeams`; every invariant IT (I1-I3, I11) has a `must_fail_*` test per mutant. Never add a public way to set them.
- Tests: `*IT` use `EssentialsTestContainers.postgres()` (static container); unit tests `*Test`.
