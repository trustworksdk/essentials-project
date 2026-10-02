# Changelog

Each entry is a release, headed by its `plugin.json` version: the Essentials release the plugin
targets, with a `-N` suffix for plugin-only releases.

## 0.60.0-1 — targets Essentials 0.60.0

- **Bundled docs** — a subscription stopped by its `SubscriptionErrorPolicy` is alerted on through the
  level-triggered gauge `essentials.eventstore.subscription.stopped`, not the
  `stopped_by_error_policy` counter (`LLM-postgresql-event-store.md`, `LLM-spring-boot-starter-modules.md`).
- **Bundled docs** — a failed `ViewEventProcessor` handler that appended events or changed an aggregate is
  queued in a `UnitOfWork` of its own after the rollback, through the new
  `PersistedEventHandler#handOffFailedEvent` hook, instead of being skipped under the default policy.
  The trap for the lost event is retired (`LLM-traps.md`, `LLM-postgresql-event-store.md`, `LLM-foundation.md`).
- **Bundled docs** — a switch between polling and the CDC bus no longer counts as a stop for a retry in
  progress (`LLM-postgresql-event-store.md`).
- **Bundled docs** — a CDC subscription that falls behind catches up from the database and rejoins the bus
  instead of staying on polling, and a late-committing event is delivered under CDC after events with a
  higher `GlobalEventOrder` (`LLM-postgresql-event-store.md`).
- **Bundled docs** — the default gap handler re-asks for up to 50 open gaps per poll instead of 2, and
  tenant-filtered polling loads every tenant's events so other tenants' orders are never gaps
  (`LLM-postgresql-event-store.md`).
- **Bundled docs** — a gap is resolved only once its event was handed to the subscriber, so a stop or crash
  redelivers a gap fill instead of losing it (`LLM-postgresql-event-store.md`).
- **Bundled docs** — subscribers acknowledge handled gap fills through the new `SubscriberAcknowledgement`, so
  a fill's gap is resolved inside the handler's unit of work (`LLM-postgresql-event-store.md`).
- **Bundled docs** — custom gap strategies can compose `defaultSelection()`, a gap is promoted only when a
  poll asked for it, CDC gives up a gap at the gap handler's threshold, and tenant-filtered polls never read
  other tenants' payloads (`LLM-postgresql-event-store.md`).
- **Bundled docs** — the Event Store Starter reference gains Gap Handling and CDC sections; CDC is disabled by
  default (`LLM-spring-boot-starter-modules.md`, `LLM-spring-postgresql-event-store.md`).
- **New trap `ESS-116`** — a handler that skips everything at or below the highest `GlobalEventOrder` it
  has seen drops late-committed events (`LLM-traps.md`).

## 0.60.0 — first release, targets Essentials 0.60.0

The first official release of the `essentials` Claude Code plugin, published from the Essentials
repository itself.

- **`essentials-docs` skill** — framework knowledge over the bundled docs in `references/llm/`,
  generated from the repository's `LLM/` directory, plus the design guide in
  `references/design/essentials-design.md`. The traps index (`LLM-traps.md`) gives every trap a
  stable `ESS-NNN` id.
- **`essentials-change` skill** — routes a change request described in prose to the slice that owns it.
- **Slice-design law** (`rules/slice-design.md`) — four slice kinds and the R1–R5 anti-god-class rules,
  with `/essentials:add-slice` and its four per-kind commands to scaffold slices in Java or Kotlin,
  `/essentials:slice-check` to audit, `/essentials:slice-discover` to analyse a codebase not yet on
  the law, and `/essentials:slice-map` to render the structure of one that is.
- **Application stack contract** (`references/stack/`, S1–S11) — what an Essentials application must
  provide, with Kotlin and Java bindings, the React/TypeScript frontend modes, and the version pins
  (Essentials 0.60.0, Java 25, Spring Boot 4.1.1, Kotlin 2.4.10).
- **`/essentials:init`** — scaffolds a Spring Boot project (Kotlin or Java, WebFlux or WebMvc, three
  DB profiles, an optional embedded or standalone React frontend, optional Docker Compose, an
  optional slice-manifest lint gate) with its wiring tests, then lints and builds it before handing
  it over.
- **`/essentials:upgrade`** — brings an existing project up to what the installed plugin ships.
- **`/essentials:review`** — reviews a change against the traps index, the stack contract and the
  slice law, with `ESS-*` finding ids linking to the section that owns each; `--fix` applies the
  mechanical fixes one at a time.
- **`/essentials:intro`** — read-only orientation.
- **Deterministic scripts** behind the commands, each with committed goldens or tests:
  `init-render.py` and `render-slice.py` (project and slice rendering), `slice-lint.py`,
  `slice-source.py` and `slice-index.py` (manifests, source facts, the map), `stack-lint.py`
  (S1–S11) and `review-scan.py` (trap signatures).
- **Eval suite** (`evals/`) — `claude plugin eval` cases for the steps that need the model's
  judgement.
