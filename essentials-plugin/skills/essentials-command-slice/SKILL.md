---
name: essentials-command-slice
description: >
  Scaffold a Trustworks Essentials command slice (use case) in Java or Kotlin — command type,
  Decider, single-method API handler, the emitted event variant, slice.yaml manifest, per-slice
  CLAUDE.md, and the GivenWhenThen unit test. Invoked by /essentials:add-slice and
  /essentials:add-command-slice.
user-invocable: false
disable-model-invocation: true
allowed-tools: [Read, Write, Edit, Glob, Grep, Bash]
---

# Command slice — Essentials

A command slice accepts an intent, enforces invariants, and emits events. It is the **only** way
state changes.

## Inputs

Supplied by the dispatching command. **Never re-elicit these.**

| Input | Example |
|---|---|
| `language` | `kotlin` \| `java` |
| `lane` | `decider` \| `aggregate` \| `service-entity` — the BC's §R5 write style. Rendered into the manifest as `lane:` |
| `tier` | `cqrs-es` (both event-sourced lanes) \| `service-entity`. **Derived from `lane`, and not the same value** — it is the manifest's `architectureTier` vocabulary, a different axis (`slice-authoring.md` §4). Never render `tier: aggregate`: it is not a tier value, and another tool reading the manifest silently downgrades an unrecognised tier to `custom` and skips the slice |
| `projectRoot`, `sourceRoot`, `testRoot`, `packagePath` | resolved from the project |
| `bc` / `Bc` | `orders` / `Orders` |
| `slice` / `Slice` / `sliceCamel` | `place_order` / `PlaceOrder` / `placeOrder` |
| `aggregate` / `Aggregate` / `AggregateType` | `order` / `Order` / `Orders` |
| `entity` / `Entity` | `order` / `Order` — **service-entity lane only** |
| `Command` / `Event` | `PlaceOrder` / `OrderPlaced` |
| `apiPath`, `owner` | `/api/orders`, `orders-team` |

## Step 1 — Load the law and the shared procedure

```
Read ${CLAUDE_PLUGIN_ROOT}/rules/slice-design.md
Read ${CLAUDE_PLUGIN_ROOT}/references/slice/slice-authoring.md
```

§R1, §R2, §R3, §R4, §R5 and § Wiring is part of done all bind here. Cite them; do not restate them.

The files are written by `scripts/render-slice.py` (`slice-authoring.md` §4b). This skill decides the
shape, runs the script, then fills the TODOs, wires what the script could not, and reports.

## Step 2 — Decide the shape

This is the judgement a template cannot carry.

**One Decider, one command type.** If the requirement mentions two intents ("place *or* amend an
order"), that is two slices. A `when (cmd)` / `switch (cmd)` over several command types is §R1.

**The Decider is pure.** `(command, events) → event?`. No I/O, no repositories, no read-model
queries. Concretely:

- Return `null` / `Optional.empty()` for an idempotent no-op — re-issuing a command whose effect is
  already recorded must not emit a second event.
- Throw to reject. The exception type is the slice's rejection contract; assert it in the test.
- Never load another aggregate, and never query a read model to check an invariant — read models are
  eventually consistent, so the check is racy by construction. Uniqueness across aggregates is
  enforced with an `InTransactionEventProcessor` plus a unique DB constraint.

**Evolver placement — default to per-slice, and stay there.** Emit `State` + `Evolver` into *this
slice's* directory; each Decider folds only the fields it needs. Many deciders need no state at all
(an idempotency check over the raw event list is enough) — emit nothing then.

Promote to `use_cases/_shared/` only when **three or more** deciders need the *same* state, with no
consumer requiring a field the others do not. Two is a coincidence. Sharing is coupling: every
consumer gains a reason to edit `State`, and it drifts toward the union of everyone's needs — the god
aggregate one layer down. Promotion is a move that keeps both names, so waiting costs nothing.
The one exception at two consumers is the same *named* invariant enforced off independent folds,
where drift is a bug; record the shared `invariants[].id` in both manifests. See §
The `_shared/` promotion bar. `_shared/` holds State and Evolver only; a Decider there is §R1 in disguise.

**One event, one file, owned by this slice.** The variant goes in `<bc>/events/`, not in the slice
directory — the BC's `events/` is its public surface. It is still *owned* here; record that in the
slice's CLAUDE.md.

**The command is the contract — no adapter layer** (§R2). Never emit a `…Request` type that mirrors
the command field for field, a `…Response` that mirrors the emitted event, or any
mapper/assembler/`toDto()` between the API and the command.

**Emit the typed wire signature** — `@RequestBody {{Command}}` plus a semantic `@PathVariable`. That
is the purest form of "the command is the contract", and it makes the Decider's idempotency guard
live, since a retry replays the same client-supplied id rather than a fresh server-generated one. It
is what the shipped templates do. Two mechanisms back it, and neither is implied by a dependency
merely being present:

- **Path variables / request params** — a Kotlin `@JvmInline value class` id binds with **nothing
  from Essentials**: Kotlin unboxes it in the JVM signature, so Spring sees a `String` and binds it
  natively, with no `types-spring-web` dependency. Java ids extend `CharSequenceType` and do need
  `SingleValueTypeConverter`, registered by `@Import`ing `EssentialsWebMvcConfigurer` /
  `EssentialsWebFluxConfigurer` (`types-spring-web` — the module auto-configures nothing). A missing registration surfaces as **HTTP 500**, not 400.
- **Request bodies** — `EssentialTypesJacksonModule` (`types-jackson3`) must be on the *web*
  `JsonMapper`. The Essentials starters publish it as a `@Bean`, which Spring Boot adds to its
  auto-configured web mapper; it is silently lost when no Essentials starter is on the classpath
  (expose the bean yourself) or when the application replaces Boot's `JsonMapper` with its own. The
  persistence mapper is built separately and never picks it up.
  Kotlin value types additionally need `KotlinModule` on **both** mappers, or they serialize as
  `{"value":"…"}` instead of `"…"` — silently, with nothing thrown. A `KotlinModule` `@Bean` reaches
  the web mapper only; the persistence mapper needs your own serializer bean built with
  `EssentialsObjectMappers.createJackson3ObjectMapper(KotlinModule.Builder().build())` — see
  `references/stack/kotlin-spring-boot.md` § Serialization — S3.4 in full.

Fall back to plain body fields and constructing the command in the handler **only** where you have
checked and the registration is genuinely absent. Record that in the slice's `CLAUDE.md` so it reads
as a constraint rather than as habit.

If the emitted slice types a **validating** Kotlin value class as a `@PathVariable`, add
`@ExceptionHandler(IllegalArgumentException::class)` returning 400: the `init { require(…) }` guard
does run (Spring re-boxes before invoking the handler), but because it fires during invocation rather
than binding, the default status is 500. See `references/llm/LLM-types-spring-web.md`
§ Validation runs — but watch the status code, and `references/llm/LLM-types-jackson.md`
§ Kotlin semantic types.

**Kotlin: a value class in a handler signature needs an explicit operationId** (trap ESS-113). Kotlin
mangles the JVM name of a handler that takes a value class as a parameter (nullable or `suspend`
included) or returns one (`placeOrder-40lU5Lw`); springdoc uses that name as the `operationId`, and the
generated frontend client inherits it. A value class only inside a generic (`List<OrderId>`) or a DTO
does not mangle. Give exactly the mangling handlers
`@Operation(operationId = "<sliceCamel>")` (`io.swagger.v3.oas.annotations.Operation`), named after the
slice so it stays stable and unique; `@JvmName` is not an option on Spring's open methods. See
`references/llm/LLM-types-spring-web.md` § Kotlin handler methods: set the operationId. The shipped
templates keep value classes inside the request and response types, so no emitted handler mangles;
the first edit that moves one into the signature — a typed `@PathVariable` id, say — does.

**Language split (§R5) — never mix these:**

| | Kotlin | Java |
|---|---|---|
| Decider | `kotlin.eventsourcing.Decider<C, E>` | `eventstream.EventStreamDecider<C, E>` |
| Returns | `E?` | `Optional<E>` |
| `canHandle` | `(cmd: Any): Boolean` | `(Class<?>): boolean` |
| Sealed events | package + module only | explicit `permits` clause |

## Step 3 — Check or scaffold the bounded context

```
Glob <sourceRoot>/<packageDir>/<bc>/
```

If absent, pass `--new-bc`: the script emits the BC scaffold first, choosing the family by `lane`:

| `lane` | Scaffold | Contents |
|---|---|---|
| `decider` | `templates/<language>/bc-scaffold/` | id type, sealed event parent, routing interface, `<Bc>Configuration` (aggregate-type configuration + decider beans only), BC `CLAUDE.md` — plus `templates/<language>/app-wiring/DeciderWiring` into the application package **only if the project has no decider configurator yet**: there is exactly one per application |
| `service-entity` | `templates/<language>/bc-scaffold-service-entity/` | id type, sealed event parent, near-empty `<Bc>Configuration`, BC `CLAUDE.md`, and `entities/CLAUDE.md`. **No `routing/`** — there is no stream to select |
| `aggregate` | `templates/java/bc-scaffold-aggregate/` | id type, sealed event parent, **the aggregate and its repository wrapper** (`aggregates/<Aggregate>.java`, `aggregates/<Aggregates>.java`), near-empty `<Bc>Configuration`, BC `CLAUDE.md`. **No `routing/`** — each handler loads by id, so there is no command-type-to-stream mapping. **Java only** — see below |

**If the BC exists, confirm its lane before emitting anything** (§R5, and `slice-authoring.md` §1b).
The lane is a per-BC property and a BC showing **two** — any two of per-slice deciders, `aggregates/`,
`entities/` — is Blocking. Stop and report it; emitting into it deepens the violation.

**On the `aggregate` lane, emit `command_aggregate` — but only in Java, and one file is still the
user's.** The lane is templated: command type, thin handler (load → call one method → done), API
file, event variant, manifest and both tests. Two caveats belong in the report every time:

- **The aggregate method is an edit to an existing file, so it is the user's call.** The handler the
  template emits calls `<Aggregate>.applyPlaceholder(...)`; the real invariant method has to be
  written on `aggregates/<Aggregate>.java`, which another slice also owns. Never silently add a
  method to an existing aggregate — name the method the handler expects, state the signature, and
  let the user write it. This is the lane's equivalent of the service-entity lane's "the entity is
  yours to write".
- **Java only.** There is no `templates/kotlin/command_aggregate/`, deliberately: the aggregate family
  (`AggregateRoot`, `StatefulAggregateRepository`) is Java-native and `/essentials:slice-check` treats
  an `aggregates/` directory in a Kotlin BC as **Advisory** — reachable through interop, but not a
  shape this plugin generates. If the BC is Kotlin and on this lane, emit nothing and say so: the
  right move is a conversation about whether the BC belongs on the decider lane, which Kotlin has
  first-class.

**On the `service-entity` lane, `entities/` gets its orientation file but no entity.** The entity and
its write repository are the only files on that lane that must name a persistence flavour, and no
template ships for them — the Essentials JPA integration is EXPERIMENTAL upstream and its semantic-id
story is unsettled (`slice-model.md` §3.5). Emit `entities/CLAUDE.md`, and state in the report that
`<Entity>` and `<Entity>Repository` are the user's to write, against the contract that file carries.
Never invent an entity from the placeholders.

Neither scaffold creates `use_cases/_shared/`. On the decider lane a brand-new BC has no deciders, so
a shared `State` there would have zero consumers — premature promotion baked into the scaffold;
`_shared/` is created by hand later, when the third consumer appears. On the service-entity lane it is
never created at all: there are no evolvers to promote, and a `_shared/` there is a service class in
disguise (§R5).

In Java the sealed parent's `permits` clause is generated with **this slice's event** as its first
entry — an empty `permits` does not compile.

## Step 4 — Emit

First the module preconditions. Exit 1 names each module the build file lacks; report it and offer to
add it before going on — the slice does not compile without it:

```bash
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/render-slice.py requires --lang <language> --kind command \
    --lane <lane> [--new-bc] --build <the build file from add-slice Step 0>
```

Then render and wire in one call. Pass the inputs you were given; the script derives the rest
(`slice-authoring.md` §4b). Add `--with-state` only when Step 2 decided this Decider folds state:

```bash
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/render-slice.py render --lang <language> --kind command \
    --lane <lane> [--new-bc] [--with-state] --wire --json \
    --project-root <projectRoot> --main-root <sourceRoot> --test-root <testRoot> \
    --set packagePath=<packagePath> --set bc=<bc> --set slice=<slice> --set Aggregate=<Aggregate> \
    --set AggregateType=<AggregateType> --set Command=<Command> --set Event=<Event> --set owner=<owner> \
    [--set Aggregates=<Aggregates>]   # aggregate lane
    [--set Entity=<Entity>]           # service-entity lane, when entities/ already names one
    [--set apiPath=<apiPath>]         # only when it is not /api/<bc>
```

Exit 2 means nothing was written: relay the message (an existing slice directory, a lane that does
not match the BC's directories, the Kotlin aggregate lane, a template drift) and stop. The family is
`command` on the decider lane, `command_service_entity` on the service-entity lane and
`command_aggregate` on the aggregate lane; the tables below are what it writes.

**Decider lane — `templates/<language>/command/`:**

| Template | Destination |
|---|---|
| `__Slice__.<ext>` | `<bc>/use_cases/<slice>/<Command>.<ext>` |
| `__Slice__Decider.<ext>` | `<bc>/use_cases/<slice>/<Slice>Decider.<ext>` |
| `__Slice__API.<ext>` | `<bc>/use_cases/<slice>/<Slice>API.<ext>` |
| `events/__Event__.<ext>` | `<bc>/events/<Event>.<ext>` |
| `test/__Slice__Test.<ext>` | test tree, mirroring the slice package |
| `slice.yaml`, `CLAUDE.md.template` | `<bc>/use_cases/<slice>/` |

**Service-entity lane — `templates/<language>/command_service_entity/`:**

| Template | Destination |
|---|---|
| `__Slice__.<ext>` | `<bc>/use_cases/<slice>/<Command>.<ext>` |
| `__Slice__Handler.<ext>` | `<bc>/use_cases/<slice>/<Slice>Handler.<ext>` |
| `__Slice__API.<ext>` | `<bc>/use_cases/<slice>/<Slice>API.<ext>` |
| `events/__Event__.<ext>` | `<bc>/events/<Event>.<ext>` |
| `test/__Slice__Test.<ext>` | test tree — the **entity** unit test, pure, no Spring |
| `test/__Slice__IT.<ext>` | test tree — through the `CommandBus`, asserting the event |
| `slice.yaml`, `CLAUDE.md.template` | `<bc>/use_cases/<slice>/` |

**Aggregate lane — `templates/java/command_aggregate/`:**

| Template | Destination |
|---|---|
| `__Slice__.java` | `<bc>/use_cases/<slice>/<Command>.java` |
| `__Slice__Handler.java` | `<bc>/use_cases/<slice>/<Slice>Handler.java` |
| `__Slice__API.java` | `<bc>/use_cases/<slice>/<Slice>API.java` |
| `events/__Event__.java` | `<bc>/events/<Event>.java` |
| `test/__Slice__Test.java` | test tree — the **aggregate** unit test, pure, no Spring |
| `test/__Slice__IT.java` | test tree — through the `CommandBus`, asserting the appended event |
| `slice.yaml`, `CLAUDE.md.template` | `<bc>/use_cases/<slice>/` |

**Not emitted, and reported instead:** the invariant method on `aggregates/<Aggregate>.java`. The
handler names it; the user writes it.

No decider, no evolver, no `GivenWhenThenScenario` on that lane — all three presuppose a stream. The
conditional `State`/`Evolver` block below applies to the **decider lane only**.

**Conditional — `--with-state`, only if this Decider must fold state to decide** (Step 2, Evolver placement):

| Template | Destination |
|---|---|
| `__Aggregate__State.<ext>` | `<bc>/use_cases/<slice>/<Aggregate>State.<ext>` |
| `__Aggregate__StateEvolver.<ext>` | `<bc>/use_cases/<slice>/<Aggregate>StateEvolver.<ext>` |

They land **in the slice**, never in `use_cases/_shared/`. Leave the flag off when an idempotency
check over the raw event list suffices — the shipped `place_order` example needs no state at all. If a
`_shared/<Aggregate>State` already exists **and** this would be its third consumer with no new field
required, leave the flag off and import that instead; say so in the report.

Then fill the TODOs the JSON lists under `todos` — the command's payload, the event's facts, the
invariants — and replace the `placeholder` field the templates carry.

## Step 5 — Wire it

`--wire` makes the edits below at the anchor comments the BC scaffold carries, and the JSON reports each
under `wiring` as `applied`, `present` or `manual`. **`manual` means the anchor comment is gone** — make
that edit by hand at the place named, never skip it.

**Decider lane:**

1. `@Bean fun <sliceCamel>Decider() = <Slice>Decider()` (Kotlin) or the `@Bean` method (Java) in
   `<bc>/config/<Bc>Configuration.<ext>`, plus its import.
2. **Java only:** `<Event>` appended to the `permits` clause of `<bc>/events/<Aggregate>Event.java`.
3. A check, listed first in `wiring`: the application has **exactly one** decider configurator
   (`<packagePath>.DeciderWiring` in a project this plugin scaffolded). `manual` means none — no
   decider reaches the `CommandBus` — or several, each of which registers every decider again so the
   first command fails with `MultipleCommandHandlersFoundException`. Report it; merging configurators
   is the user's edit.

Items 1 and 2 are edits to existing files. The `permits` append is the one cross-slice edit the law
sanctions (§R3); the `@Bean` is this BC's wiring, not another slice's logic.

An unregistered Decider compiles, passes every unit test, and breaks every `@SpringBootTest`.

**Service-entity lane — step 1 becomes a check, not an edit:**

1. There is no `@Bean` to add. `ReactiveHandlersBeanPostProcessor` auto-registers every
   `CommandHandler` bean with the single `CommandBus` bean, so `@Component` in a scanned package is
   the whole of it. **Confirm two things:** the handler's package is component-scanned, and
   `reactive-bean-post-processor-enabled` (default `true`) is not switched off in any profile —
   disabling it silently unwires every handler in the application. Name it in the report either way.
2. **Java only:** the `permits` append is unchanged — a service-entity command slice still supplies
   an event variant, and `--wire` makes it. The aggregate lane has the same single edit.

Do **not** name `EssentialsComponentsConfiguration` or `EssentialsComponentsProperties`: neither
appears in any bundled doc, and the plugin never names an unproven symbol.

**Every lane, Java — the typed edge.** When the slice's API takes a typed id (the aggregate-lane
command and service-entity view templates do), run the S4 registration check in `slice-authoring.md`
§4c. It runs stack-lint, offers each `ESS-S4` fix one at a time, and applies nothing without a yes. Name
the result in the report, including "not checked" when stack-lint could not run.

## Step 6 — Report and self-check

Report every file written and edited, then state what the user must still fill in: the command's
real payload, the event's facts, the invariants, and a test per invariant.

On the **service-entity** lane also state, explicitly:

- `<Entity>` and `<Entity>Repository` are the user's to write if the BC is new — no template ships
  for them; the contract is in `entities/CLAUDE.md`.
- The invariant belongs **on the entity**, not in the handler. A handler holding an `if` about domain
  state is the finding.
- The **write** table needs a migration; no read-model migration exists on this lane.

Re-read `rules/slice-design.md` § Red flags and confirm none of the structural entries applies to
what you just emitted — in particular that no existing Decider or handler gained a branch, no existing
API file gained a mapping, and nothing in `events/` or `entities/` names a command type (§R4).

## Red flags specific to this kind

- The Decider takes a repository, service, or read model in its constructor.
- The Decider calls `.get()` on another aggregate's stream.
- Two command types share a Decider.
- The event variant was added to an existing event file rather than its own.
- The `@Bean` was skipped "because the test passes".

## API provenance

Every Essentials symbol in these templates is listed in
`${CLAUDE_PLUGIN_ROOT}/references/slice/api-provenance.md` with the doc that proves it. Never
introduce one that is not there.
