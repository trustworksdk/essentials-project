# Changelog

Each entry is a release, headed by its `plugin.json` version: the Essentials release the plugin
targets, with a `-N` suffix for plugin-only releases.

## 0.60.0-7 — targets Essentials 0.60.0
- **`/essentials:slice-map-html` — the Slice Atlas.** A new command writes one self-contained page
  (no server, no CDN, opens from `file://`) that shows the whole slice architecture from the manifests,
  computed entirely by scripts — no model tokens go into the drawing. Five lenses:
  - **Atlas** — semantic zoom from bounded contexts, to journeys linked by events, to a classic Event
    Modeling board per journey (trigger, command and view, and one event lane per stream), with each
    slice's given / when / then scenarios at the deepest zoom, or its invariants and test kinds when it
    declares none.
  - **Diff** — what changed against a baseline: on a feature branch the merge base with the default
    branch, or `--diff <ref>`. Added, removed and changed slices, edges and messages, and the issues the
    change introduced or fixed.
  - **Lineage** — pick a field and see every command, event and read model that carries it; a
    read-model column no inbound event supplies is flagged.
  - **Coupling matrix** — slices against the aggregates and read models they write, own and read,
    with cross-context coupling off the diagonal, and a context × context event matrix.
  - **Metro map** — each journey a coloured line, shared slices as interchanges.

  Every lens shares an issues dock that flies to the issue and marks it, search, a detail card (manifest,
  payloads, read-model table, scenarios, the slice's `CLAUDE.md`), and the whole view in the URL hash, so
  a link in a PR comment reopens it. Light and dark follow the system with a toggle, in the Trustworks
  palette. A map with unparsed manifests is still written — each one a red error card under an
  "incomplete" banner — and exits 1. The page is built by the new `slice-index.py atlas`, which CI can run
  directly (`--before-map` takes a cached baseline map).
- **`/essentials:slice-map --html` is retired**: given the flag, slice-map says the page moved and runs
  `/essentials:slice-map-html` with the same path. `--md` stays, and `slice-index.py html` keeps its
  page and its refusal to write an incomplete map.
- **Manifest schema 1.4: optional `flow:` and `scenarios:`.** `flow:` names the journey (or journeys) a
  slice serves; a slice without one is grouped by cause and effect. `scenarios:` is the slice's
  specification by example — given / when / then over the slice's own messages, ending in emitted
  messages, an error (`{ error, message }`), `{ nothing: true }` or a view's read-model rows — with an
  optional `test:` naming the test that runs it. New slice-lint gate `1(d) scenario` checks that a
  scenario names only messages its own manifest declares. Both fields are human- or generator-owned:
  `/essentials:slice-check --fix-manifests` never writes them. A project's schema copy older than 1.4
  rejects them, so a generator checks the copy first and otherwise sends the user to
  `/essentials:upgrade` (`manifest-guide.md` §3).
- **Schema 1.4: `x-` extension keys on every object, and a version gate for unknown keys.** A key
  starting with `x-` is valid on the root and on every mapping a manifest holds — endpoint, projection,
  schedule, seam, scenario, step, `then` — at any `schemaVersion`; before, nested `x-` keys failed. The
  schema declares the version it implements (`x-manifestVersion`). Any other unknown key fails gate 1(b)
  on a manifest stamped at or below it, as before, and is tolerated on one stamped with a newer `1.x`:
  slice-lint prints it as a note (`notes` in `--json`) and exits 0. The projection near-synonyms
  `subscribesTo`, `handles`, `table` and `kind` are the exception and fail at every stamp, so a stamp
  written ahead of the project's schema copy cannot switch that guard off. The gate is in the schema
  itself, so any JSON Schema 2020-12 validator agrees. Unknown-key findings name the key (`endpoints/0:
  unknown key 'rateLimit'`), and the unknown-root-key hint, which never fired, now does. The Slice Atlas
  no longer annotates `x-` keys in scenarios as unknown.
- **Schema 1.4: optional `requirements`** — the requirement ids a slice implements, as the project's
  requirement source names them (`requirements: [REQ-ORDERS-12]`). The schema checks the shape only (a
  non-empty list of distinct, non-empty strings); a toolchain that keeps a requirement trace checks the ids.
  Human- or generator-owned like `flow`: no slice skill writes it, `/essentials:slice-enrich` never proposes
  it, and a manifest carrying it is stamped `"1.4"`. A generator now checks the project's schema copy for an
  `x-manifestVersion` of `"1.4"` or later, rather than for a `scenarios` property, before writing `flow`,
  `scenarios` or `requirements`. A slice-lint note on a copy without `x-manifestVersion` names "the
  project's schema copy" rather than reading "schema this copy's".
- `/essentials:slice-map`'s closed list of checks gains the completeness rule (a read-model column no
  inbound event carries) and the scenario and adoption checks (a `given` event nothing publishes, an
  unknown field, a `test:` that resolves to no test, a malformed scenario, no `flow:`, no scenarios). All
  are `info`/`warn` annotations: still no R1–R5 gates and no severities. The map JSON gains
  `slices[].flows`, `scenarios`, `schedule` and `doc`, and `processes`, `issues` and `lineage`
  (`commands/slice-map.md` §6).
- `slice-source.py --json` gains a top-level `tests` key — the test classes under the project's test
  source roots with their JUnit 5 test methods and `@DisplayName`s — which resolves a scenario's `test:`.
  An unreadable test file is listed under `unparsed`, so the facts mode can now exit 3 on test source
  alone.
- `render-check.py` also renders the Atlas pages: each lens mounts with no console error, an issue
  focuses its target, a deep link restores the view, a broken manifest is an error card.
- **Atlas boards and previews.** Board cards use the classic Event Modeling colours, blue for commands,
  orange for events and green for views, tuned to the Trustworks palette in both themes. A card names its
  fields, columns or endpoints, ending in "+N fields" when they do not fit. A command dispatched from
  another journey is drawn once, where it is handled, not as a second card. Hovering a card, context,
  process or scenario shows a compact form of the detail pane. Double-clicking a Metro station (or
  Shift+Enter) opens it in the Atlas lens. A Matrix context band too small for its name turns rather
  than cutting it: row bands lie across, and a narrow column band stands up. The Matrix headers grow
  to fit a long slice or target name, up to a cap; past it the tooltip has the whole name.
- **Copy fix prompt.** The issues dock copies a prompt for all, error, warning or info issues, grouped
  by the command that fixes them: `/essentials:slice-check <bc> --fix-manifests` for manifest repairs,
  `/essentials:slice-check <bc>` for what needs an audit, and `/essentials:slice-enrich --bc <bc>` for
  the absent `flow` and scenarios. Each issue in the map JSON gains `fix: {route, bc}`. Where the
  clipboard is blocked, the prompt opens in a dialog to copy by hand.
- **One Atlas page per application.** On a folder holding several applications, each with its own build
  file, `slice-index.py atlas` stops (exit 2) and names them, because slice ids are only unique within
  one application. `--per-app DIR` writes one page per application plus an `index.html`; `map --per-app`
  and `--before-map-dir` do the same for CI baselines. `slice-index.py apps` lists the applications.
- **`/essentials:slice-enrich`.** Catches older `slice.yaml` manifests up on `flow`, `scenarios`,
  `summary`, `invariants`, `tier`, projection `consistency`, `featureFlag` and translation `maps`. The new
  `scripts/slice-enrich.py propose` finds the evidence: journeys from cause and effect, scenarios from
  `GivenWhenThenScenario` tests, invariants from guard messages, summaries from Javadoc and KDoc, and
  `@ConditionalOnProperty`. The model drafts only from that evidence and the user accepts per journey and
  per slice. `slice-enrich.py apply` then writes only absent fields, keeping every other byte, and stamps
  schema 1.4 when it writes `flow` or `scenarios`. It refuses a field that became present, and a result
  that adds a schema error. `--dry-run` writes nothing.
- **The manifest reconciliation law** (`references/slice/manifest-reconciliation.md` §1): an absent
  human-owned field may be drafted from evidence and written once a person accepts it, never over a
  value. `--fix-manifests` still writes none of them. `commands/slice-check.md` Step 4 now states how a
  bounded context's `tier` is resolved, which `--adopt-tier` and slice-enrich share. `manifest-guide.md`
  §3 documents the `featureFlag` shape `{ property, havingValue, matchIfMissing }`.
- **Fixed:** `LLM-eventsourced-aggregates.md` and the Java command test template showed
  `.thenThrows(...)`, which `GivenWhenThenScenario` does not have. They now use
  `thenFailsWithExceptionType(...)`.
- **Fixed:** `slice-source.py` missed a command sent through a getter's bus, `getCommandBus().send(...)`,
  the form an `EventProcessor` uses, so such an automation showed no `dispatches`.
- **Fixed:** `slice-source.py` missed a command forwarded through an `Inbox`:
  `addMessageReceived(cmd)` now counts as a dispatch when the same file creates that inbox with
  `getOrCreateInbox(config, commandBus)`, the overload that sends what the inbox receives to the bus; an
  inbox created elsewhere is listed as unresolved. Gate 11(b) also holds an inbound translation's
  `@KafkaListener`, `@RabbitListener` and `@JmsListener` payload types, or their concrete subtypes, to
  its `consumes`.
- **Docs:** `LLM-immutable-jackson.md`, `LLM-types-jackson.md` and `ESS-023` in `LLM-traps.md` now say
  the Essentials mappers hide `is`-getters as well as getters and setters, and read a missing primitive
  as its default, matching the framework fix in the next Essentials release.
- **Fixed: generated projects lacked objenesis.** `immutable-jackson3` declares it `provided`, so the
  application must, and S2.1 did not list it: a project from `/essentials:init` started and wrote,
  then failed its first read of an event, queue message or document with `NoClassDefFoundError`. S2.1
  now lists `org.objenesis:objenesis`, the generated backend POM declares it at `objenesis.version`, and
  `stack-lint` reports a project without it as `s2.1-objenesis` (Blocking).
- **Docs for the next Essentials release (0.60.1).** The LLM docs describe the 0.60.1 fixes and additions,
  each labelled "since 0.60.1": `awaitProcessed` and `AppendedEvents` for integration tests, `@IgnoresEventTypes`
  and the unhandled-event-type WARN on views, the startup report, the wire-format check and removed-property
  errors, the `MultiTableChangeListener` builder, the enqueue-outside-a-unit-of-work guard, redelivery-policy
  warnings, shard-owned ordering across a handover, `hostname:pid` lock-manager and shard-owned ids, the
  document DB fixes (unchecked `OptimisticLockingException`, private `@Id`, `getById`, reserved words), and the
  empty `LongRange`. `LLM-traps.md` gains `ESS-124` (per-instance fenced-lock ownership) and `ESS-125`
  (a view skipping an event type).
- **More docs for Essentials 0.60.1.** The LLM docs and the view-slice skill cover where a processor
  subscription starts, stable processor names across instances, what a failing processor handler must do,
  testing asynchronous projections with `awaitProcessed`, snapshot support per aggregate pattern, the three decider
  families, what a throwing command handler does to the UnitOfWork (including the 0.60.1 `sendAndDontWait`
  rollback) and why its exception arrives wrapped, the UnitOfWork usage rule, hand-opened Jdbi handles, the two
  lock-ownership predicates, the document-DB constructor rule and version contract, blocking starters under
  WebFlux, omitted primitives in request bodies, and hand-built `WebTestClient`s. Wrong examples are fixed: the
  protected `releaseLock(lock)` and a `SendCommandException` constructor that does not exist. `LLM-traps.md`
  gains `ESS-126` (catching a command handler's exception inside `@Transactional` commits its writes) and
  `ESS-127` (a processor handler that catches and logs loses the event).
- **Docs follow three framework changes for Essentials 0.60.1.** `AppendedEvents` now lives in
  `postgresql-event-store` (`dk.trustworks.essentials.components.eventsourced.eventstore.postgresql`) and records
  every append, not only an `EventStreamDecider`'s; the view-slice skill's testing guidance names the new import.
  `CommandBus` lists its handlers and warns about a command type handled twice (`LLM-reactive.md`). The startup report
  is now the run report, `EssentialsRunReport`: runtime findings, a periodic block of new ones
  (`essentials.run-report.new-findings-interval`) and the complete report on shutdown.
- **Fixed: `/essentials:slice-enrich` suggested a hand edit for a value `apply` refused.** When `apply` refuses a
  `consistency` on a projection written as a flow mapping, the report now gives the refusal as worded and no
  workaround; before, it could tell the user to add the value by hand or rewrite the projection in block style.
- **Eval graders name the trap's line.** A must-not-find grader generated by `evals/build.py` now names the line and
  anchor of the trap, not only its file, so a correct finding drawn from another line of the same file no longer fails
  it (`slice-enrich-library` went from split votes to 1.00 over three runs). The enrich, multi-lane and review
  service-entity trap descriptions are tightened to match. The review service-entity case now expects the two
  checks stack-lint itself reports as not run on that fixture (`s1-boot-line`, the frontend checks) under "Not run",
  and tolerates `setShipped` named inside the `setCancelled` row, where the new guard makes it a second bypass.
- **Docs follow the durable-queue changes for Essentials 0.60.1** (`LLM-postgresql-queue.md`,
  `LLM-postgresql-queue-shard-owned.md`). A handled message is acknowledged inside the handler's transaction, and a
  failing acknowledgement is classified like a handler exception; a builder-made `PostgresqlDurableQueues` is wired
  like the starter, with its own change listener, and is `AutoCloseable`; the polling-optimizer defaults and due-time
  polling; stale statistics on a draining queue table; the ordered-claim statement switch, its threshold and the
  timeout a probe of the other statement runs under; handlers that hold a connection. Shard-owned: attempt counts
  survive a change of owner, a requested redelivery is not an attempt, and a WARN names
  `essentials.shard-owned-queue.instance-id` when two live processes share an id; `Subscription.unsubscribe()` ends a
  subscription, and so does `close()` now, which calls it, where `stop()` keeps it registered for the queue's
  `start()`, so consuming anew needs an unsubscribe or a close first; the queue's `isStarted()` reads whether the
  queue is marked started, not whether a subscription runs, so a manager that stops only what reports itself started
  (`DefaultLifecycleManager`) skips a partly started queue; a stop that finds an ordered key still in its handler past
  `shedGrace` keeps that unit, and the subscription's instance id, until that handler has returned and the unit has
  been handed back (its acknowledgement flushed, its lease released, so its next owner charges no attempt and
  delivers nothing again), or for another instance until the lease TTL runs out; an operator can release such a
  unit sooner on the instance that kept it, with `MessageQueue.releaseKeptOrderedUnit` or the admin API's
  `POST /shard-owned-queues/{queueName}/ordered-units/{unit}/release-kept`, accepting that the messages still in a
  handler are delivered again, possibly beside those handlers; and a new Gotcha
  lists what a `QueueObserver` calling the queue back from `shardOwnershipChanged` gets - a `consume()` from a release
  a stop tells, or from one a failed start tells while it stops the lanes it had started, returns a subscription
  registered but not started, now with a WARN; a `Subscription.start()` there starts neither lane and WARNs, even with
  both lanes running, and a queue `start()` starts nothing and WARNs, bar one case: finding the queue marked started
  while a failed `consume()` or `Subscription.start()` stops its lanes, it returns at once, as that call leaves the
  queue started. `ShardOwnerSettings` is built with `ShardOwnerSettings.builder()`, which starts from `defaults()`, or
  `settings.toBuilder()`, which starts from existing settings, so only the settings that differ are named.
  MongoDB (`LLM-springdata-mongo-queue.md`): due-time polling, and the backoff defaults stated correctly (50% of the
  polling interval per empty poll, capped at 20× the polling interval).
- **Docs: the shard-owned queue's `stuckHandlerTimeout` for Essentials 0.60.1** (`LLM-postgresql-queue-shard-owned.md`).
  A restarted queue, or the next `consume()` wanting the instance id, no longer takes back at once an ordered unit a
  stop kept for a key still in its handler: it waits for that handler up to `stuckHandlerTimeout` (default 15
  minutes, per queue, `essentials.shard-owned-queue.stuck-handler-timeout` and `stuck-handler-timeouts.<queue>`),
  then takes the unit over under a new fence, and that key may run in two handlers at once. A WARN once the handler
  outlasts `leaseTtl`, at most once a minute per unit, and `ShardOwnerMetrics.keptUnitsInHandler()`.
- **Docs: supported PostgreSQL is 15+.** `LLM.md` states the floor (the majors the PostgreSQL community still patches)
  and the fenced-lock and shard-owned docs point at it instead of the old 9.5+ and 13+.
- **Docs: `shared` helpers for Essentials 0.60.1** (`LLM-shared.md`). § Exceptions lists `ROOT_CAUSE_PREFIX`,
  `prependRootCauseSummary`, `forEachThenThrowFirst` and `forEachCollectingFailures`, with examples for stopping
  every part and undoing a failed start; § Locks documents `Locks.lockWarningOnce`, a lock wait, like `lock()`, that
  warns once `warnAfter` has passed without the lock instead of blocking silently, then waits in `lock()` itself,
  keeping a fair lock's queue order, and `Locks.tryLockDeferringPendingInterrupt`, a bounded wait that an interrupt
  pending on entry does not cut short.

## 0.60.0-6 — targets Essentials 0.60.0
- **`/essentials:upgrade` now moves a project to the plugin's Essentials release, code included.** A
  project on an older release (`essentials.version` below the pin) gets a new Group D: the move is
  planned by the new `scripts/migration-plan.py` from the release's migration notes
  (`references/stack/migrations/0.50-to-0.60.json`), shown as a decision note — the pins it moves,
  marked Essentials or platform, the findings it closes, the code migrations and their risks, and the
  checklist of behaviour changes — and applied only after you approve it. The note is printed, or
  written to a path you choose (it offers `docs/adr/` when the project has one), and is the ADR. It
  moves `essentials.version` and only the platform pins the release requires, after one question
  (Move all / Stop / Show impact), on an `essentials-<version>` branch it creates, with a commit per
  step if you want one. Then it applies the stack-contract findings that hold only on the new release,
  the API migrations `review-scan.py --tree` finds, and the compile errors that remain — call site by
  call site, each confirmed, in slice source too but never structurally — verifying each step and
  gating the move on the project's full build (Docker required). Behaviour changes no compiler sees
  come back as a checklist with their `ESS` ids, never applied; workarounds the release makes
  removable are offered one at a time. A failing step is named, mapped to its symptom and offered for
  revert; the branch you started from is untouched. `--check` prints the whole plan and needs neither
  Docker nor a clean tree. Upgrade still never moves a pin the release does not require.
- **Upgrade detection for 0.50 → 0.60** — `review-scan.py --tree` scans a whole project (the
  empty-tree diff), with new signatures for the 0.60 compile breaks and removed keys, and new traps
  `ESS-119` to `ESS-123` in `LLM-traps.md` § Upgrading: `QueuedMessageCounts`' new components, the
  CDC `live-drain-stall-threshold` key, the Jackson 2 API renames, `useOrderedUnorderedQuery`, and the
  0.50 `forRemoval` constructors.
- **Standalone Next.js and Create React App frontends are judged correctly** — S8's standalone checks
  assumed Vite: they matched only `import.meta.env.VITE_API_BASE_URL` in a file named `custom-fetch.ts`
  and a hard-coded Orval output path, so a Next.js frontend doing exactly what S8 asks got findings
  whose fixes would break it. `stack-lint.py` now accepts any `import.meta.env.*` or `process.env.*`
  read that prefixes the request URL, finds the mutator and the generated paths from `orval.config.*`
  (reported as not run when that config is computed), reports the tooling it found
  (`facts.frontendTooling`) and names that tooling's variable in its fix text. S8 is worded for any
  tooling, with Vite as the example.
- **CORS origins bound with `@Value`** — `s8-standalone-cors-value` claimed "startup fails" for every
  such key; that holds only for a list. It stays Blocking for a YAML sequence or an indexed key (with a
  `:default`, it says the default is bound silently instead); a scalar, or a key no `application*` file
  sets, is now the Should-fix `s8-standalone-cors-value-scalar`.
- **`slice-lint.py` no longer passes when it validated nothing** — a run that could not import
  `jsonschema` and found nothing exits 2, as `/essentials:slice-check` already documented, instead of
  0. `--allow-skip-schema` accepts a parse-only pass explicitly; `--require-schema` is still accepted,
  as a no-op, so existing hooks and CI keep parsing.
- **Refreshing the lint gate no longer turns CI red unannounced** — `slice-lint.py --baseline <json>`
  reports only the findings a previous `--json` run did not. `/essentials:slice-check` gate 12 and
  `/essentials:upgrade` measure what a refreshed copy would add before offering it, say "the refresh
  adds N findings in <files>", and offer the manifest fixes first; a committed baseline also works as
  a CI ratchet. The gate-14 hint no longer suggests a `tier` value it cannot know: `cqrs-es` when the
  bounded context is event-sourced, otherwise remove `tier`.
- **The pre-commit hook works in a project below the repository root** — it assumed git root =
  project root, so in a nested project the hook never ran, and a missing script let commits through.
  It now finds its project from its own path, lints only manifests staged inside it, and **blocks**
  on findings, on a missing `scripts/slice-lint.py`, and when the linter exits 2. Contributors need
  `uv`, or `python3` with `pyyaml` and `jsonschema`; only a machine with neither `uv` nor `python3`
  still commits unchecked. `/essentials:init` activates it with a repository-relative `core.hooksPath`,
  or offers a forwarder line when another hook setup exists, and the project README gives every other
  clone the command. `/essentials:upgrade` reports a hook git never runs as installed but inactive and
  offers the same activation.
- **Leftovers of the forked-app OpenAPI pipeline are reported** — `s7-spec-generation` went silent once
  a test wrote the spec, so `springdoc-openapi-maven-plugin`, a start/stop execution or
  `application-openapi.*` left behind was never reported again. New Advisory checks
  `s7-forked-pipeline` and `s7-key-order` (`springdoc.writer-with-order-by-keys: true` missing), and
  `/essentials:upgrade` removes the residue as a checklist, warning to commit the spec's one-time
  reformat on its own.
- **The version stamp's format, named** — projects scaffolded before the plugin's first release carry
  `<!-- essentials-init: v<semver> -->`; `/essentials:upgrade` replaces it with
  `<!-- essentials-init: essentials <version> -->`, the Essentials release rather than a plugin
  version. Tooling that parses the stamp needs the new form; upgrade now names any file outside
  `CLAUDE.md` that mentions `essentials-init:` before it replaces the stamp.
- **Framework-knowledge block refresh** — `/essentials:upgrade` now compares the project
  `CLAUDE.md`'s framework-knowledge section with the plugin's and offers to replace that section alone
  when it differs, keeping the project's stamp; before, it only checked that the heading existed.

## 0.60.0-5 — targets Essentials 0.60.0
- **Framework docs: 0.50.1 was never released** — the event-store reference now says the polling
  transaction leak (a connection left `idle in transaction` when a subscription was disposed after an
  idle poll) is fixed in 0.60.0, not 0.50.1. No skill, rule or script changed.

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
