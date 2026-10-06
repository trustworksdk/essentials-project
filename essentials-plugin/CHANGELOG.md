# Changelog

Each entry is a release, headed by its `plugin.json` version: the Essentials release the plugin
targets, with a `-N` suffix for plugin-only releases.

## 0.60.0-3 — targets Essentials 0.60.0
- **Licence** — the plugin now states its licence: Apache-2.0, the same as the Essentials repository.
  `LICENSE` ships in the plugin directory and `plugin.json` carries `"license": "Apache-2.0"`, so an
  installed copy, which does not include the repository's root `LICENSE.txt`, says what terms it
  comes under. The scaffold templates stay without a copyright header on purpose: what they render
  becomes the user's code.
- **Framework docs, brought up to the 0.60 release branch** — `references/llm/` now carries what
  landed on `release/0.60` alongside the plugin: event causation (`CausationContext`, how a cause is
  recorded, carried across durable queues and command buses, and looked up, plus the admin API's
  causation operations), subscription resume points saved every second by default with an opt-in
  early save after a number of events, the shard-owned queue's permanently held connections, and
  the `SCHEDULER_WRITER` role for running a scheduler job on demand. No skill, rule or script changed.
- **`/essentials:doctor`** — checks the machine for the tools the commands run (python3 3.11+, uv or
  pyyaml and jsonschema, the JDK `stack-pins.md` pins, Maven, Docker, npm, git, ripgrep) and says what each
  missing one costs: a command that stops, a gate not run, a compile-only smoke build, a slower
  fallback. `scripts/doctor.sh` does the probing, per profile (`init`, `review`, `slice`, `docs`, `all`)
  with `--json`; it is bash, because what it detects first is a missing or too-old Python. `/essentials:init`,
  `/essentials:review`, `/essentials:slice-check` and `/essentials:slice-map` now run it as their
  preflight instead of their own inline checks, with the same stops and degradations as before.
- **Framework docs: retry-then-stop and resume** — `SubscriptionErrorPolicy.retryThenStop(...)`
  (`error-policy.mode=retry-n-then-stop`), resuming a subscription its error policy stopped without a
  restart (`EventStoreSubscription#resumeIfStoppedByErrorPolicy()`, the manager, and the admin API's
  `POST .../resume`), and the new trap `ESS-117`: `stop()` halts a projection on its first transient
  non-I/O failure. A handler can override the manager's policy for its own subscription
  (`subscriptionErrorPolicy()`, a processor's `getSubscriptionErrorPolicy()`).
- **Framework docs: a failing event no longer skips by default** — the subscription manager's default
  `SubscriptionErrorPolicy` is now `defaultPolicy()` (`retryThenStop(3)`; Spring Boot
  `error-policy.mode=retry-n-then-stop`) with automatic resume: a stopped subscription resumes itself at the failed
  event after 10 s, doubling to 5 min (`SubscriptionErrorPolicy.AutoResume`, `error-policy.auto-resume.*`). Opt-in
  `AutoResume.skippingAfter(...)` skips an event after that many resumes, reported by the new observer callback
  `subscriptionSkippedEventAfterAutoResumes` and counter `essentials.eventstore.subscription.skipped_after_auto_resumes`.
  The section is now `LLM-postgresql-event-store.md` § Direct async subscribers retry, stop and resume at a failing
  event; `ESS-058` and `ESS-117` are reworded for the new default, and the new trap `ESS-118` covers a subscription
  that keeps stopping at a poison event (and the 0.50 upgrade that relied on skipping: `error-policy.mode=skip`).
  The `essentials.eventstore.subscription.stopped` gauge stays at `1` through the automatic resumes until the failed
  event is handled (new `EventStoreSubscription#isRecoveringFromErrorPolicyStop()`), so an alert's `for:` duration is
  not reset by each resume. `skippingAfter(...)`'s count is documented as in memory per instance (a restart,
  redeploy or lock hand-over starts it over), and a resume that throws no longer counts toward it. The middle of a wide
  gap, awaited in memory only, now survives such a resume - any re-subscribe on the same event store instance, polling
  and CDC - with its original timeout, so a late commit into it is still delivered once; a restart, `resetFrom`,
  unsubscribe or fenced-lock release loses it (new `EventStore#forgetGapMiddlesAwaitedInMemory`).
- **Framework docs: bounded polling gaps** — a poll now records a new gap only
  `SubscriptionGapHandler.MAX_AWAITED_ORDERS_PER_GAP_END` (5,000) orders deep from each end and awaits
  the middle of a wider one (sequence `setval`, restore) in memory only, as CDC does, instead of writing a
  transient-gap row per order; a poll after an empty one steps straight over such a gap instead of
  widening its range for hours; and the new persistence-strategy lookup behind both,
  `findLowestGlobalEventOrderPersisted(uow, aggregateType, LongRange)`, a custom strategy should override.
- **Bundled docs** — on MongoDB, a FencedLock hand-over after an outage can take up to the server's
  `transactionLifetimeLimitSeconds` (60 s by default) instead of `lockTimeOut`, when the lock confirmation's commit failed
  during the outage (`LLM-springdata-mongo-distributed-fenced-lock.md`).

## 0.60.0-2 — targets Essentials 0.60.0
- **Slice law, loaded by lane** — `rules/slice-design.md` stays one file, but each lane-, kind- or
  Spring Data-specific section now carries a scope line, and the slice skills and the change router
  load it through the new `scripts/slice-law.py`, which prints only the sections that apply to the
  bounded context's lane, the slice kind and whether the project uses Spring Data repositories, and
  names what it left out. A decider-lane command slice loads about 36 KB of the law instead of 68 KB,
  and a slice reached through a change request loads it once instead of twice; the self-checks walk
  § Red flags from what was already printed instead of re-reading the file. `/essentials:slice-check`
  and `/essentials:slice-discover` still read it whole.
- **Slice law, shorter** — the R2 paragraph on binding typed ids and bodies now cites stack-contract
  S3.3, S3.4 and S4 instead of restating them; § Red flags and § Anti-Rationalisation are grouped
  into subsections per lane and per Spring Data, each entry reduced to its verdict and the section
  that carries the reasoning. No rule changed, and no section heading a citation uses was renamed.
- **Stack pins** — `kotlin.version` 2.4.20, `jdbi3-bom.version` 3.55.0 and `mongodb.version` 5.13.0, the versions
  Essentials itself now builds and tests against (`references/stack/stack-pins.md`; the nine init goldens move with
  them).
- **Bundled docs** — Avro 1.12.2+ refuses a `SpecificDatumReader` for a generated record outside its trusted
  packages; the fix is `org.apache.avro.SERIALIZABLE_PACKAGES` (`LLM-types-avro.md`).
- **Bundled docs** — how fast a Mongo node notices a lost FencedLock is bounded by the `MongoClient` socket timeouts,
  and the best-effort database release after a lost lock can take several connect timeouts on MongoDB driver 5.12+
  (`LLM-springdata-mongo-distributed-fenced-lock.md`).
- **Wording** — the tests and evals no longer call their expectations an "oracle", a term many
  readers do not know and one easily mistaken for the Oracle database: they now say expected results,
  and each eval case's `oracle.yaml` is now `grading.yaml`.

## 0.60.0-1 — targets Essentials 0.60.0
- **Slice check** — a new Advisory clause of gate 6, `6 raw id` (`ESS-G6`), reports a `@PathVariable` or
  `@RequestParam` id typed as a plain `String`, `Long`, `UUID` or other scalar in a command or view slice,
  where the law's default is the bounded context's semantic id type. Translation webhooks are exempt,
  because they carry the external system's ids (`scripts/slice-source.py`, `commands/slice-check.md`).
- **Slice templates** — the service-entity view's lookup endpoint takes the bounded context's semantic id
  instead of a `String`, and unwraps it only for the `String`-keyed query, so a generated view slice no
  longer starts off the law.
- **Stack contract** — S4 now says to import exactly one `types-spring-web` configurer for the web stack
  (`EssentialsWebMvcConfigurer` or `EssentialsWebFluxConfigurer`) whenever a Java `SingleValueType` id is
  at the API edge. `stack-lint` reports `s4-typed-edge-unregistered` (Blocking) for an id that answers
  HTTP 500 without it, and `s4-typed-edge-convention` (Should-fix) for one that Spring currently binds
  through a `String` constructor or `valueOf`/`of`/`from(String)`. The slice skills check the registration
  when they emit a typed id (`references/slice/slice-authoring.md` §4c).
- **Slice discovery** — `/essentials:slice-discover` reports primitive ids at the domain edge as an
  unranked finding, once per bounded context, and puts the semantic id types in `types/` at rung 2 of the
  migration ladder, with no framework adoption required (`discovery-heuristics.md` §8).
- **Bundled docs** — the `types-spring-web` gotcha and `ESS-031` no longer say every typed path variable
  needs the configurer. It is required for an id with no `String` route; Spring binds one with a `String`
  constructor or `valueOf`/`of`/`from(String)` on its own, and the rule is still to import it
  (`LLM-types-spring-web.md`, `LLM-traps.md`).
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
- **Bundled docs** — a CDC gap give-up is durable through `SubscriptionGapHandler#giveUpTransientGaps`, tenant
  filtering compares serialized tenants (a custom `TenantSerializer` must round-trip), one
  `SubscriberAcknowledgement` serves one subscription, and an optimizer with a deliberate zero delay overrides
  `mayRepollImmediatelyAfterAnEmptyPoll()` (`LLM-postgresql-event-store.md`).
- **Bundled docs** — a CDC give-up is a permanent gap of the whole aggregate type, recorded only for gaps
  waited the full threshold; re-subscribing an acknowledged polling flux replaces its registration; an
  interrupted polling worker ends the flux with an `InterruptedException`; each `defaultSelection()` instance
  keeps its own rotation, on the gap handler's thread only (`LLM-postgresql-event-store.md`,
  `LLM-spring-boot-starter-modules.md`).
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
