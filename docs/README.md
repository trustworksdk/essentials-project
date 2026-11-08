# Essentials documentation

Consumer-facing API docs are in [`../LLM/`](../LLM/LLM.md). This folder holds the longer design and reference
documents, release notes and migration guides.

- **`docs/`** — documents that describe how a built framework feature works, and why. Where one of them disagrees
  with the code, the code is right; fix the document.
- **[`plans/`](plans/README.md)** — plans and improvement proposals that are not built yet, with an index of every
  open item.
- **[`archive/`](archive/)** — plans that have been carried out, and historical records. Kept for the reasoning;
  not a description of the current code.

## Reference

| Document | Covers |
|---|---|
| [cdc.md](cdc.md) | CDC EventStore: design and operations |
| [event-causation.md](event-causation.md) | Event causation (`causedByEventId`) end to end |
| [event-processor-specializations.md](event-processor-specializations.md) | `PartitionedEventProcessor` / `PartitionedViewEventProcessor` and the start-up refusal |
| [key-partitioned-event-processing.md](key-partitioned-event-processing.md) | Key-partitioned event processing on the shard-owned queue engine |
| [closing-books-business-day-boundary.md](closing-books-business-day-boundary.md) | Business-day closing-books boundary |
| [durable-queue-shard-owned.md](durable-queue-shard-owned.md) | The shard-owned PostgreSQL queue engine |
| [durable-queue-ordered-routing-design.md](durable-queue-ordered-routing-design.md) | The shard-owned engine's ordered lane |
| [durable-queue-measurements.md](durable-queue-measurements.md) | Current measurements of the queue engines |
| [database-schema-harness.md](database-schema-harness.md) | The schema harness (`SchemaChange`, appliers, `essentials.schema.mode`) |
| [constructor-ergonomics-and-optional-policy.md](constructor-ergonomics-and-optional-policy.md) | Construction ergonomics and the `Optional` policy |
| [slice-atlas-design.md](slice-atlas-design.md), [slice-enrich-design.md](slice-enrich-design.md) | Design rationale for the plugin's Slice Atlas and `/essentials:slice-enrich` |
| [openapi/](openapi/README.md) | The admin API contract; `admin-ui-mockup/` is the admin UI's visual language |
| [event-sourcing-vs-crud/](event-sourcing-vs-crud/index.html) | Explainer: event sourcing for CRUD developers |
| [ai-tooling.md](ai-tooling.md) | The devcontainer's optional AI-assist stack |

## Releases

| Document | Covers |
|---|---|
| [NEXT_VERSION_RELEASE.md](NEXT_VERSION_RELEASE.md) | Working draft of the next release's notes |
| `RELEASE-NOTES-<version>.md` | Notes for each released version |
| [MIGRATION-0.60.md](MIGRATION-0.60.md) | Upgrading to 0.60 |
| [MIGRATION-NEXT_MAJOR.md](MIGRATION-NEXT_MAJOR.md) | Replacements for the constructors 0.50 deprecated and 0.60 removed |
