# postgresql-event-log-migration

The one-way move of aggregate types from `PostgresqlEventStore`'s tables to the event log's, in the same database.
**Preview in 0.61.0**: published, and its API may change in a minor release.

- Reference: `docs/design/event-log.md` §10. Consumer doc: `LLM/LLM-postgresql-event-log.md` ("Migrating existing data").
  Design, operator procedure and gate results: `docs/archive/event-store-engine-phase9.md` (archived; "§n" in source
  comments points there).
- Package `dk.trustworks.essentials.components.eventlog.migration`; CLI under `…migration.cli`.
- Depends on the engine and the JDBC driver only, never on JDBI or `postgresql-event-store` (`architecture` tests). The
  classic tables are only read, and `SHARE`-locked during the cut-over.
- The migration state lives in the engine's `el_migration` table, whose DDL the engine owns (`EventLogSchemaContributor`).
  A type marked `COPYING` keeps every event-log node off its tables until `cutover()` marks it `CUT_OVER`.
- Not thread-safe: one migration at a time; the checkpoint's compare-and-set refuses a second concurrent copier.
- Mutants live in `MigrationMutant`, settable only through the package-private `MigrationSeams`; the invariant ITs carry
  must-fail arms that use them. Never add a public way to set them.
- Tests: `*IT` on `AbstractMigrationIT` (static container), e.g. `MigrationCopyIT`, `MigrationCutoverIT`,
  `MigrationInvariantIT` (I8), `MigrationVerifyIT`, `MigrationCliIT`; unit tests `*Test`.
