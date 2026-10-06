# Essentials Slice Design

The slice-design law for Trustworks Essentials projects in Java and Kotlin. Every skill and command
in this plugin **references this file by section name rather than restating its rules** — read it
before advising on, generating, or auditing slice structure.

This file is **self-contained**. It depends on no other plugin. Framework API detail lives in
`references/llm/`; code templates live in `references/slice/templates/`; this file is structure only.

These rules are **advisory-by-construction** — this plugin ships no enforcement hooks.
`/essentials:slice-check` is the opt-in audit that reports against them.

A `<!-- slice-law: … -->` line under a heading scopes that section, and every section under it, to
the lanes (§R5), the slice kinds, or the persistence (`store=spring-data`) it names; a section
without one applies everywhere. `scripts/slice-law.py` prints only the sections that apply to a
lane, a kind and a project, which is how the slice skills load this file.

## Slice Zero: detect the language and the bounded context before advising

Never recommend a structure before you know which language the target module is written in and which
bounded context the work belongs to. Kotlin and Java use **different Essentials decider APIs**
(§R5) and different sealed-type mechanics (§R3); advice that ignores this compiles in one and not
the other. A module with both `.kt` and `.java` under the same bounded context is **language-mixed**
— stop and ask rather than guessing.

## The four slice kinds

A **vertical slice** is one complete, independently testable feature path — from its trigger through
decision logic and persistence to its output. It is the unit of work and the unit of navigation.
Every slice has **exactly one** kind; every feature maps to **exactly one** slice.

| Kind | Intent | Trigger | Produces | Directory | External API |
|---|---|---|---|---|---|
| **command** | Accept an intent, enforce invariants, change state | a command | event(s) | `use_cases/<slice>/` | yes — one endpoint |
| **view** | Answer queries from a read-optimised model | a query | a read model | `views/<slice>/` | yes — one API, one or more queries over its own model |
| **automation** | React to what happened, issue a follow-up command | event / schedule | command(s) | `automations/<slice>/` | **no** |
| **translation** | Anti-corruption boundary to an external system | external message / internal event | internal command or external call | `external_systems/<system>/` | **no** |

Mapped onto CQRS: command slices are the **only** way to change state; view slices are pure query
and never produce events; automation slices bridge the read and write sides; translation slices are
the only place an external schema is allowed to appear.

**The four kinds are lane-independent** (§R5). A command slice still produces events on the
service-entity style — they are published on the `EventBus` as integration facts rather than appended
to a stream, which changes where they go, not whether they exist or how §R3 declares them. What that
lane changes is the *view* row: the read model is the entity's own table rather than a separately
projected one, so a view slice there owns a query interface and a read shape instead of a projector
(§ The read side on this lane).

## Directory vocabulary

Fixed, for every Essentials project on the JVM:

```
<bc>/
  use_cases/<slice>/      command slices
  use_cases/_shared/      per-BC shared State + Evolver ONLY
  views/<slice>/          view slices
  automations/<slice>/    automation slices
  external_systems/<sys>/ translation slices
  events/                 the sealed event parent + one file per variant
  types/                  the BC's semantic types (ids, enums, value objects)
  aggregates/             ONLY on the aggregate style (§R5) — one aggregate type per file.
                          Absent from a decider-style BC, which is the default and what this
                          plugin scaffolds
  entities/               ONLY on the service-entity style (§R5) — one state-stored entity per
                          file, plus the write repository that persists it. A BC has `aggregates/`
                          or `entities/` or neither, never two
  routing/                ONLY on the decider style (§R5), and REQUIRED there — one marker
                          interface per aggregate (BC-private). Every command in the BC implements
                          it, so the framework can tell which aggregate a decider serves and which
                          event stream to load. Deliberately NOT sealed: that is what lets a new
                          command slice add a file without editing an existing one. Absent on the
                          other two styles — neither has a decider to filter, and the aggregate
                          lane loads its stream by id through StatefulAggregateRepository while
                          the service-entity lane has no stream at all
  config/                 Spring wiring for this BC — one @Bean per decider
```

The six directories beside the slices are explained in full in
`references/slice/slice-model.md` §4, including why each is BC-scoped rather than slice-scoped.

**Underscores, not hyphens** — a JVM package directory cannot contain a hyphen. There is no
`commands/` directory (command slices live in `use_cases/`), no `queries/` (that is `views/`), and
no `translations/` (that is `external_systems/`).

**No layer directories anywhere inside a BC:** `controllers/`, `services/`, `repositories/`,
`adapters/`, `ports/`, `infrastructure/`, `dto/`, `mappers/`. Those are layers, and this is not a
layered architecture — `adapters/` and `ports/` are the same mistake wearing a hexagonal hat. A
persistence repository is not exempt: on the service-entity style it lives in `entities/`, beside the
entity it persists, because that directory is named for a domain concept rather than for a layer.

**A `_`-prefixed directory is not a slice.** `use_cases/_shared/` is the only one the law names, but
the prefix is a general convention: anything under `<bc>/_<name>/` is excluded from slice enumeration
and from the §R4 boundary check. Each must carry a one-line `CLAUDE.md` saying what it is and why it
is not a slice — a load-test harness or a scratch fixture is honest there and dishonest wedged into
`use_cases/`. Use it sparingly; it is an escape hatch, not a fifth kind.

## R1 — one decision component per command slice

Each command slice owns exactly one decider class, handling exactly one command type. **Never** a
shared component that routes many commands — no `when (cmd)` / `switch (cmd)` over N command types,
no `decideXxx()`-per-command object, no god class. Adding a command means adding a *directory*,
never editing another slice's decision logic.

What this rule targets is **routing**, not object-orientation. A BC on the aggregate style (§R5) puts
the decision in one aggregate that every command slice calls, and that is sanctioned: an aggregate
with named methods carrying their own invariants is not a `switch` over command types. It has its own
bar to stop it becoming one — § The aggregate's own bar. The service-entity style is the same shape
with a state-stored entity in place of the aggregate, under § The entity's own bar. On both lanes what
R1 forbids is the god *class*; on the service-entity lane it also forbids the god *handler* — one
`@CmdHandler` class carrying methods for two or more command types is a router whatever it is called.

Deciders are pure: `handle(command, events) → event?`. Return null for an idempotent no-op, throw to
reject. No I/O, no repository calls, no read-model queries.

## R2 — one API file per slice, owned by that slice

A command or view slice owns **one API file**, and that file serves **only that slice**. Never a
controller that aggregates several slices' handlers, and never a handler that reaches into another
slice's read model or decider.

How many methods that file holds depends on the kind, because the two kinds are identified by
different things:

- **Command slices — one method.** A command slice's identity *is* its command type: one command,
  one decider (R1), therefore one endpoint. A second command is a second slice, never a second
  method here.
- **View slices — one by default, more when they interrogate the same read model.** A view slice's
  identity is the **read model it owns**. Filtering, sorting, pagination, lookup-by-id, and
  field projections over that one model are all the same slice's concern:
  `listOrders()`, `filterByLastName()`, `sortedByFirstName()` belong together in `OrderListAPI`.
  Forcing each into its own slice would produce either several slices sharing one read model —
  which breaks the ownership boundary in R4 — or several read models duplicating one projection
  over the same events. Both are worse than a multi-method API file.

**The test for "is this a new view slice?" is the read model's purpose, not its event set:**

1. Does the query serve a **different purpose**, needing a **different read-model shape** — a
   different entity, a different grain, a different lifecycle? → new slice.
2. Is it just another way to interrogate the same model? → same slice, another method.
3. Does the *same* model need data it does not yet project? → **same slice, extended.** Projecting
   one more event is ordinary evolution, not a new slice — see § Evolving a view slice.

Every exposed method must be declared in the slice's manifest: `serves` names the queries and
`endpoints` names the routes, both arrays. An endpoint absent from the manifest is drift.

Two mappings that **share a route and differ only by a query parameter** — `@GetMapping` beside
`@GetMapping(params = "status")` — are two mappings, so two `endpoints` entries and two `serves`
names, over one read model and inside one slice. That is this rule working as intended, not an
exception to it. The manifest spells the second one `path: "<route>?status="`; see
`references/slice/manifest-guide.md` §3, which also states why tooling must not match that string
against the source verbatim.

Automation and translation slices have no external API at all.

**Cohesion smell (advisory).** A view API that has accumulated many methods with divergent shapes is
usually a read model doing two jobs — apply test 1 before adding the next one.

### The command and the view *are* the contract — no adapter layer
<!-- slice-law: kind=command,view -->

A slice needs no request/response DTOs and no mapper. The **command type is the request body**; the
**read model is the response body**. Return the view entity from the query method; accept the
command's own fields on the way in.

Specifically forbidden:

- A `…Request` or `…Response` type that mirrors the command or the read model **field for field**.
  It doubles the edit surface of every field change and buys nothing.
- Any `…Mapper`, `…Assembler`, `…Converter`, or `toDto()` step between the API and the slice's own
  types. If a class exists only to copy fields across, delete it.

Specifically allowed — this is *assembly*, not translation:

- The handler constructing the command inline from a path variable plus a body, or from a
  server-generated id: `commandBus.send(CancelOrder(OrderId(orderId), body.reason))`. A small body
  type carrying the fields the client actually sends is fine here; a mirror of the whole command is
  not.

  Typing the wire contract directly — the command as the `@RequestBody`, a semantic id as the
  `@PathVariable` — is the *stronger* form of this rule and **the default shape to write**. It rests
  on registrations, and a registration is never implied by a dependency being present, so confirm
  rather than assume. Path variables and request params bind through the typed edge
  (`references/stack/stack-contract.md` S4); request and response bodies through the web mapper
  (S3.3), and on Kotlin through the module both mappers need (S3.4,
  `references/stack/kotlin-spring-boot.md` § Serialization). Every one of them fails at runtime, not
  at compile time.

  Where a registration genuinely is absent, taking plain fields and constructing the typed value in
  the handler is assembly, not an adapter, and remains correct — but it is the fallback, not the
  default.
- Serialisation and validation concerns — `@JsonProperty`, `@field:NotBlank` — which belong **on the
  command or view type itself**, not on a parallel copy of it.

The one real exception is a **deliberate divergence**: the read model holds a field the API must not
expose, or the wire contract is versioned independently of the internal shape. Then the difference is
the point — record why in the slice's `CLAUDE.md` so it reads as a decision rather than as habit. If
most of your views need fields hidden, the read models are projecting more than the slices serve.

## Evolving a view slice
<!-- slice-law: lane=decider,aggregate kind=view -->

**This section applies to the two event-sourced lanes only.** On the service-entity style there is no
projection to rebuild and no stream to replay, so a shape change is an ordinary schema migration and
the `_v2` twin machinery below does not apply — see § The read side on this lane. The opening
principle still holds everywhere: needing one more field is this slice evolving, not a new slice.

A view slice is not frozen once it has data. Needing one more event, one more field, or a changed
shape is **ordinary evolution of this slice** — not a new slice. Slice identity is the read model's
purpose, not the set of events it happened to project on day one.

What makes it non-trivial is that the read model is already populated: changing the projection does
not retroactively change rows the old one wrote. So the question is *how to roll it out*, which is a
deployment concern, not a structural one. Two strategies:

**In-place rebuild — the default.** Add the handler to the existing projection, reset the
subscription, and replay from the start of the streams (`onSubscriptionsReset(aggregateType,
resubscribeFromAndIncluding)` → delete that aggregate type's rows,
then project forward). One slice, one read model, no duplication. Correct whenever a rebuild window
is acceptable, which for most read models it is.

**Versioned twin — when it is not.** When the model is too large to rebuild inside an acceptable
window, or queries must keep being served throughout, run the new projection beside the old:

1. Add `views/<slice>_v2/` with the new projection and its own read model.
2. Let it catch up from the beginning of the streams while `v1` keeps serving.
3. Swap consumers over once it has caught up.
4. **Delete `v1`.** This step is part of the change, not a follow-up ticket.

The twin is **one slice in two versions, not two slices** — and the manifests must say so, or the
structure is indistinguishable from two slices that accidentally share a read model (an R4 violation):

- `v2` declares `supersedes: <bc>.<slice>` and starts at `status: planned`.
- `v1` moves to `status: deprecated` the moment `v2` goes live.

`/essentials:slice-check` reads that pair. With it, the duplicate-read-model advisory is suppressed
and the outstanding retirement is reported as **Should-fix** for as long as both exist. Without it,
two view slices projecting the same events into the same shape are reported as the R4 violation they
look like.

**The twin has an expiry by construction.** A `_v2` outliving its `v1` by more than a release is the
failure mode this section exists to prevent: two permanent slices over one read model, which is
exactly what R2's ownership rule forbids. If retirement keeps slipping, the honest fix is to stop and
do the in-place rebuild.

## R3 — split event hierarchies

A sealed parent type in `events/` with **one variant per file**, each logically owned by the slice
that emits it. Never one god file holding every variant.

The language mechanics differ, and this is the one place they force different work:

- **Kotlin** — sealed subtypes need only share the parent's package and module. Adding a variant is
  a pure file addition; no existing file changes.
- **Java** — a sealed interface needs an explicit `permits` clause when subtypes are in sibling
  files. Adding a variant therefore requires **appending one name to the parent's `permits` list**.
  This is the *single sanctioned cross-slice edit* in this law: it is a declaration-list append, not
  a change to another slice's decision-making, so it does not violate R4. The alternative — a
  non-sealed marker interface — is permitted but is not the default, because it forfeits exhaustive
  `switch` checking in evolvers.

The event store records each event's class name and deserializes into that class, so an event
variant needs no Jackson type information to be read back. A field *inside* an event whose declared
type is sealed (or otherwise abstract) is different: the Essentials mapper enables no default typing,
so that type needs `@JsonTypeInfo` and each variant a name, or deserialisation fails and the processor
reading the event cannot get past it. The templates put `@JsonTypeInfo`/`@JsonTypeName` on the event
parent as well; keep them once events are persisted, because removing them changes the stored JSON.
See `references/llm/LLM-kotlin-eventsourcing.md` § A sealed type used as a *field* inside an event
needs type info.

## R4 — a slice never reaches into another slice's internals

A slice may import only another slice's or bounded context's `events/` and `types/`. Never its
decider, evolver, state, handler, repository, or endpoint. On the aggregate style, a read-side slice may also name the aggregate's `AggregateType` constant (the stream it subscribes to) — never a method of the aggregate or its repository wrapper.

**A bounded context's importable surface is exactly `events/` and `types/`.** `routing/` and
`config/` are BC-private: a foreign BC implementing your routing interface would route its command
into your aggregate, and importing your `config/` couples it to your wiring. Neither Kotlin's
`internal` (module-scoped) nor Java's package-private can express "BC-private" here, so the
directory split plus `/essentials:slice-check` gate 8 is what enforces it.

Never reconstruct another slice's state and call its decider. Collaborate by publishing an event or
by issuing that slice's command through the command bus.

**Issuing a command is the one sanctioned reason to name another slice's command type.** An
automation or translation slice that dispatches `commandBus.send(new CancelOrder(...))` must import
`CancelOrder` from `use_cases/cancel_order/`, and that is the collaboration this rule prescribes — not
a violation of it. The import is legal exactly when the type's only use is constructing a command
handed to the command bus. Reaching past it to that slice's decider, handler, state, or repository is
the violation, as above.

**No BC-scoped domain artifact may import a command type.** Nothing in `events/`, `entities/`, or
`aggregates/` may reference a type from `use_cases/<slice>/`:

```java
// ❌ events/ is the BC's PUBLIC surface — this drags one slice's wire contract
//    into every foreign consumer of the event
public static ShippingOrderRegistered from(RegisterShippingOrder cmd) { … }

// ❌ the consistency boundary tied to one slice's wire contract
public ShippingOrder(RegisterShippingOrder cmd) { … }
```

Those artifacts take the **fields** they need; the emitting slice does the unpacking. The direction of
the dependency is what matters: a slice may name BC-scoped types, never the reverse. The `events/`
case is the worse of the two — `events/` is importable across BCs, so a command reference there makes
a slice-private type part of a foreign BC's compile surface.

## R5 — use a standard Essentials design, per language

Never hand-roll a decision component. Essentials ships **three** sanctioned styles for the write side,
and a bounded context picks **one and stays with it**. A BC holding two is two designs competing over
one consistency boundary — and on the first two lanes that boundary is an aggregate's invariants,
while on the third it is a row. The rule is the same either way: one write style per BC.

| | **Decider style** | **Aggregate style** | **Service-entity style** |
|---|---|---|---|
| Decision lives in | one decider per slice | one aggregate per aggregate type | one state-stored entity per type |
| Decision surface | `handle(command, events) → event?` | a command method per intent | a command method per intent |
| State | folded per slice (or § The `_shared/` promotion bar) | held by the aggregate, rebuilt from the stream | held in a row or document, mutated in place |
| Persistence | event store — state is derived | event store — state is derived | a table or collection — state **is** the record |
| Produces | events, appended to a stream | events, appended to a stream | events, published on the `EventBus` as integration facts |
| Lane marker | neither directory | `<bc>/aggregates/` | `<bc>/entities/` |
| Languages | Kotlin **and** Java | Java family only — see below | Kotlin **and** Java |
| Default for | new code, and everything this plugin templates | code already built this way | a BC that will never reconstruct state from history |

**Decider style is the default.** It is what R1 is written for: adding a command adds a directory and
edits nothing. The other two are deliberate departures, and each needs a reason on the record:

- **Aggregate style** — the code is already there, or a genuinely complex invariant reads better as
  one object's methods than as N independent folds.
- **Service-entity style** — this bounded context has **no need to reconstruct state from history**.
  No audit trail derived from events, no temporal queries, no replay-driven projections. That is a
  forward-looking design decision, not a default you arrive at by not choosing: **state it in the
  BC's `CLAUDE.md`.** A BC that adopts this lane because nobody asked the question is the one that
  discovers a year later it needed the history it never kept.

### Decider style — the API differs per language
<!-- slice-law: lane=decider -->

The two languages use different APIs and templates must never cross-pollinate them:

| Concern | Kotlin | Java |
|---|---|---|
| Decider | `kotlin.eventsourcing.Decider<COMMAND, EVENT>` | `eventsourced.aggregates.eventstream.EventStreamDecider<COMMAND, EVENT>` |
| Evolver | `kotlin.eventsourcing.Evolver<EVENT, STATE>` | `eventstream.EventStreamEvolver<EVENT, STATE>` |
| Wiring | `AggregateTypeConfiguration` + `DeciderSupportsAggregateTypeChecker` + `adapters.DeciderAndAggregateTypeConfigurator` | `EventStreamAggregateTypeConfiguration` |

All under `dk.trustworks.essentials.components.*`. See `references/llm/LLM-kotlin-eventsourcing.md`
and `references/llm/LLM-eventsourced-aggregates.md` for the full signatures — and never invent one.

### Aggregate style — the aggregate is the consistency boundary
<!-- slice-law: lane=aggregate -->

Here the aggregate *is* the decision component, and every command slice in the BC calls it. That is
not an R1 violation: R1 forbids a **router** — one component dispatching over N command types — and
an aggregate is not a router. `order.cancel(reason)` is a named method with its own invariant, not a
`switch` arm. What R1 actually forbids in this lane is the god *class*, and § The aggregate's own bar
below is how that is held off.

**This is a Java-family design.** The stateful and flex families
(`eventsourced.aggregates.stateful.modern.AggregateRoot`, `stateful.modern.WithState`,
`stateful.StatefulAggregateRepository`, `flex.FlexAggregate`) are Java classes; the Kotlin
event-sourcing module (`components.kotlin.eventsourcing`) ships **no** aggregate pattern at all. A
Kotlin BC choosing aggregate style is therefore using the Java family through JVM interop, and gives
up the Kotlin decider API that Slice Zero and §R3 assume — say so out loud rather than letting it
happen by drift.

**The aggregate is BC-scoped, in its own directory:**

```
<bc>/aggregates/<Aggregate>.java     one file per aggregate type
```

Not `use_cases/_shared/`. That directory's rule is *state reconstruction only, a decider there is R1
in disguise* — and an aggregate is precisely a decision component, so putting it there would require
gutting the one rule that keeps `_shared/` honest, and would leave `/essentials:slice-check` unable
to tell a legitimate aggregate from a decider someone hid. A separate directory keeps both rules
crisp and makes the BC's style visible from `ls`.

**The slice still owns everything else.** Aggregate style relaxes exactly one thing — where the
decision lives. `use_cases/<slice>/` still holds this slice's command type, its API file (§R2, one
endpoint), its test, and a thin handler that opens a unit of work, loads the aggregate through
`StatefulAggregateRepository`, calls the **one** method, and lets the unit of work persist. Event
variants still live one-per-file in `events/` (§R3), still owned by the emitting slice. §R2's
no-adapter rule, §R4's boundary, and § Wiring is part of done all apply unchanged.

**No `routing/`.** The marker interface exists to answer two questions the decider configurator asks —
*which deciders serve this aggregate type*, and *which stream does this command load*. Neither is
asked here: there are no deciders to filter, and the handler already holds the aggregate id, which it
hands to `StatefulAggregateRepository` directly. A `routing/` on this lane is a decider-style vestige;
it will compile, be implemented by nothing that matters, and mislead the next reader about the BC's
write style. `routing/` is decider-lane-only, and required there — see § Directory vocabulary.

### The aggregate's own bar
<!-- slice-law: lane=aggregate -->

The `_shared/` promotion bar does not apply — an aggregate is shared *by construction*, and counting
consumers would be meaningless. Its fields are bounded by its invariants, not by its callers, which
is exactly why it does not drift toward the union the way a shared `State` does. But it has its own
failure mode, so it gets its own test:

1. **Every public method enforces an invariant.** A method that only calls `apply(...)` with no rule
   behind it is a decider that lost its way — the slice should emit that event itself.
2. **No query surface.** Getters existing to feed an API, or any method a view slice would want, mean
   the read side is being served from the write model. Project it (§ The four slice kinds).
3. **It does not grow one method per slice.** If the method count tracks the slice count and the
   invariants are all independent, the aggregate is a router after all — R1 applies, and the BC wants
   decider style.

Failing (1) or (3) across most methods is the signal to migrate the BC to decider style, not to keep
trimming.

### Service-entity style — the decision lives on a state-stored entity
<!-- slice-law: lane=service-entity -->

The decision lives on an **entity reached through a repository and mutated in place inside a
transaction**. State is the row or document itself, not a fold over a stream. Domain events are still
declared and still published — on the `EventBus`, as facts for integration — but they are never
appended to a stream and never replayed to reconstruct anything.

**What puts a BC on this lane.** Two criteria are **decisive** — they define the lane, and a BC
meeting both is on it:

1. **State is stored and mutated in place.** A state-stored entity (`@Entity`, `@Document`, or the
   equivalent in whatever persistence the project uses) is loaded by id, changed, and saved. There is
   no fold over a stream.
2. **There is no stream to replay.** No `EventStore`, no `AggregateType`, no `EventOrder` anywhere in
   the BC.

Two further criteria are **strong signals**, and each has a legitimate exception. A BC that meets the
two decisive criteria but not these is still on this lane; report the deviation, do not withhold the
classification:

3. Events are published on the `EventBus`. A BC with no integration consumers may have nothing to
   publish yet.
4. The write path goes through the **command bus** — a `CommandHandler` per slice reached via
   `commandBus.send` / `sendAsync` / `sendAndDontWait` — rather than a bare `@Service` call. This is
   the default and the recommendation; it is what makes §R1's one-handler-per-slice property
   structural instead of conventional. A BC may deviate deliberately, and if it does, **the reason
   belongs in the BC's `CLAUDE.md`** beside the no-history decision the lane already asks for. A
   documented deviation is **Advisory**; an undocumented one is **Should-fix**. Neither makes the
   lane undetermined.

> **Do not deviate on a mistaken premise.** The two reasons teams give for skipping the bus are both
> false, so check them before accepting one. The bus is **not** asynchronous-only: `send(cmd)` blocks
> and returns the handler's result, which is exactly the shape a synchronous request/response
> operation wants. And the bus does **not** impose durability: durability attaches only to
> `sendAndDontWait` on a `DurableLocalCommandBus`, and `LocalCommandBus` is non-durable by
> construction. Choosing `send` costs a synchronous call nothing and keeps the handler discoverable.
>
> Proof: `references/llm/LLM-reactive.md` § LocalCommandBus API — `CommandBus` interface and the
> method-comparison table.

**Spring Data is the common vehicle, not the definition.** Criterion 1 says *state is stored and
mutated in place*; it does not say *through a Spring Data repository*. A BC persisting through
Essentials' own document repository, through JDBI, or through any other store is on this lane if
state is the record and there is no stream. Rules written around Spring Data types — the repository
surface below, `/essentials:slice-check` gate 18 — are scoped to projects that use it and are
**skipped, and reported as skipped**, on ones that do not. The write repository's *purity* rules
(load by id, save, delete, nothing else; lives beside the entity) are framework-agnostic and apply on
every vehicle.

Plain layered Spring/JPA code with none of Essentials is **not on this lane** — it is *nearest* to it,
which is a different claim and belongs to `/essentials:slice-discover`, not to this law. This law
governs Essentials projects; the lane is a destination that code can migrate to, not a label it
already wears.

**The entity is BC-scoped, in its own directory, beside the repository that persists it:**

```
<bc>/entities/<Entity>.java            the entity — invariant-carrying methods
<bc>/entities/<Entity>Repository.java  the write repository — load by id, save, delete. Nothing else
```

The repository lives here, not in a `repositories/` folder, and that is the whole point: the directory
is named for the **domain concept**, not for the layer. It is the same reasoning that puts the
aggregate's `StatefulAggregateRepository` wrapper beside the aggregate it loads. `entities/` also
makes the BC's style visible from `ls`, exactly as `aggregates/` does.

**The write repository is BC-private and write-path-only.** Its sanctioned surface is load-by-id,
save, delete. The moment it grows `findByShipped(boolean)` for a screen, the read side is being served
from the write model — give that query to a view slice instead (§ The read side on this lane).

**Identify the write repository by its mutation surface, never by the interface it extends.** It is
the type through which the BC's entity is saved or deleted — it declares `save`/`delete`/`update`, or
inherits them. On Spring Data that surface becomes a *fact* rather than a convention, because the
interface extends the bare `Repository` marker and declares those three methods itself (§ Spring Data
repository surface). On any other persistence the surface is the same and only the enforcement is
weaker — the purity rules still apply, and a checker that looks for a Spring Data base interface
finds nothing and silently checks nothing.

**Two directories are absent by construction**, and this is a rule rather than an omission:

- **No `routing/`.** Its two jobs — aggregate-type membership and the command-to-aggregate-id
  resolver — exist only to pick an event stream. The command bus routes by command *type* to a handler
  method; there is no stream to select.
- **No `use_cases/_shared/`.** There are no evolvers, so there is no fold to promote and the promotion
  bar has nothing to count. The entity *is* the shared state and it already lives in `entities/`. A
  `_shared/` on this lane is a service class in disguise — report it.

**The slice still owns everything else.** Like aggregate style, this lane relaxes exactly one thing —
where the decision lives and how its state is stored. `use_cases/<slice>/` still holds this slice's
command type, its API file (§R2, one endpoint), its test, and a thin handler that loads the entity,
calls the **one** method, saves, and publishes. Event variants still live one-per-file under a sealed
parent in `events/` (§R3) — they are bus-delivered rather than stored, which changes nothing about how
they are declared. §R2's no-adapter rule, §R4's boundary, and § Wiring is part of done all apply
unchanged.

### The read side on this lane
<!-- slice-law: lane=service-entity -->

On the event-sourced lanes a view slice owns a **separate** read model, so §R4's ownership rule is
easy: nobody else touches that table. Here there is **one** table, shared by the write side and every
view, so the rule has to be restated or it reads as forbidding views entirely.

> **A view slice may read the entity's table, but never through the write repository.** It declares
> its own narrow, read-only query interface inside the slice directory, and returns a **declared read
> shape** rather than the entity.

**The *how* is § Spring Data repository surface**, which governs both interfaces on this lane and is
not restated here: each extends the bare `Repository` marker, the read shape is a closed interface
projection, and no query method reuses a CRUD base method name. Two properties are specific to the
view's interface. It is **slice-private**, living in the slice directory rather than beside the
entity — which is what stops one shared interface accumulating everyone's finders, the failure this
rule exists to prevent. And it is typed over the BC's *entity* while returning the *projection*,
which is legal precisely because it exposes no mutation: it is not the write repository wearing a
different name.

Three consequences, stated so tooling and reviewers do not have to infer them:

- **The read is strongly consistent** — same table, same transaction. That is the one thing this lane
  is *better* at than a projection, and teams must not be told to add eventual consistency they do not
  need.
- **Projection idempotency does not apply.** There is no versioned read model, no `EventOrder`, and no
  redelivery to double-apply. `/essentials:slice-check` gate 10 is **skipped** on this lane, not
  merely tolerated.
- **The `_v2` migration-twin machinery does not apply either.** § Evolving a view slice is written
  around replaying a stream into a second read model while the first keeps serving; with no stream
  there is nothing to catch up from, and a shape change is an ordinary schema migration. Suppress that
  guidance here.

### The entity's own bar
<!-- slice-law: lane=service-entity -->

The `_shared/` promotion bar does not apply, for the same reason it does not apply to an aggregate:
the entity is shared by construction. Its own failure mode is different from the aggregate's, though,
and worse — an ORM *requires* members an aggregate never exposes:

1. **Every public method enforces an invariant.** A method that only assigns fields is a setter with a
   better name; the slice should assign them itself, or the entity should not expose it at all.
2. **No query surface — but distinguish by caller, not by shape.** A getter whose only callers are the
   ORM and `toString()` is persistence machinery and is **not** a finding; flagging it is a false
   positive. A getter called from a `@RestController` or a view slice is the read side being served
   from the write model — project it instead. Make the distinction structural rather than
   conventional: `@Access(AccessType.FIELD)` (JPA) or field mapping (Mongo), plus package-private or
   absent accessors.
3. **It does not grow one method per slice.** Same as the aggregate bar — method count tracking slice
   count with independent invariants is R1's router returned as a class.
4. **The entity imports no command type.** See §R4.

Failing (1) or (3) across most methods is the signal that the BC wants decider style, not that the
entity needs more trimming.

**This lane's distinctive defect, and why it earns its own bar rather than a footnote on the
aggregate's:** `AggregateRoot` has no setters, so on the aggregate lane a caller physically cannot
bypass an invariant method. Here the ORM actively pushes you toward exposing full get/set, which makes
the guard on the one invariant method trivially bypassable — a public `setShipped(true)` defeats the
idempotency check that is the entity's whole reason to exist. That is a class of defect **only** this
lane has.

## Spring Data repository surface
<!-- slice-law: store=spring-data -->

**Scope: every Spring Data repository interface in an Essentials project** — the write repository on
the service-entity lane, a view slice's query interface, and any read model backed by Spring Data JPA
or Spring Data Mongo. This is a persistence-surface rule rather than a slicing rule, so it cuts
across all three write styles of §R5 instead of belonging to one.

**Out of scope: Essentials' own `DocumentDbRepository`.** It is not a Spring Data repository, it has
no interface-projection mechanism, and the read model it stores is a slice-owned `@DocumentEntity`
built for one query — narrow by construction, with nothing of a write model in it. Returning that
read model is correct (§R2). Do not import the rules below into that world: the only way to satisfy
them there would be a hand-written read shape, which §R2 forbids as an adapter.

### Repositories extend the bare `Repository` marker

Every repository interface — write side or read side — extends
`org.springframework.data.repository.Repository<T, ID>` and **nothing else**. Never `CrudRepository`,
`ListCrudRepository`, `PagingAndSortingRepository`, `ListPagingAndSortingRepository`, `JpaRepository`,
`MongoRepository`, or their reactive counterparts (`ReactiveCrudRepository`, `ReactiveMongoRepository`,
`R2dbcRepository`).

The marker exposes nothing, so the interface's surface *is* the set of methods it declares. That
turns two rules that would otherwise be conventions into facts a reviewer can read off the file:

- **On a write repository**, the sanctioned surface — load by id, save, delete (§ Service-entity
  style) — becomes the whole surface. `findAll()`, `deleteAll()`, `count()` and the rest are not on
  offer, so nobody reaches for them and nobody has to notice that they did. Declare `save` and
  `delete` explicitly; three lines is the entire cost, and it is the only place the write surface is
  written down.
- **On a view slice's query interface**, it is what makes "read-only" mean read-only. Extending
  `JpaRepository` there would hand a view `save` and `delete` over the **write** model — §R4's
  boundary crossed by inheritance rather than by an import, and invisible to any check that only
  looks at imports (§ The read side on this lane).

### The read shape is a closed interface projection

Whenever a query returns data out of a Spring Data-mapped persisted type, its return type is a
**closed interface projection** — an interface declaring only the accessors this slice serves — and
never the `@Entity` / `@Document` itself.

This satisfies §R2's no-adapter rule rather than bending it: **a projection interface is a
declaration, not a mapper.** There is no `…Response` mirror to keep in sync, no `toDto()` step, and
no class whose only job is copying fields across. Spring Data reads only the named properties and
returns a proxy, so the projection *is* the response body.

It is also strictly better than returning the mapped type, for two reasons that hold on every lane:

- the entity is a managed, mutable persistence object, so returning it hands the caller something
  row-backed that they can mutate;
- every field of the write model becomes part of the wire contract — including the ones added later
  for an invariant that has nothing to do with this screen.

Declaring an accessor the mapped type does not have fails at startup with a clear message, and that
is the point: the read shape is checked against the model rather than drifting from it. Nested
projections resolve through the same mechanism (`getAddress().getCity()`), and a `@Value` SpEL
expression can compute a derived field — but a projection needing much computation is usually a
different read model, and therefore a different slice (§R2).

### Never name a query method after a CRUD base method

**`findById` is the trap, and it fails at runtime rather than at wiring time.**

The bare marker removes the inherited *methods*; it does not remove the base *implementation*. Spring
Data always composes one in (`SimpleJpaRepository`, `SimpleMongoRepository`) and resolves each
declared method against it by **name and parameter types — the return type is not part of the
match**. So `Optional<OrderStatusView> findById(String)` is never derived as a query at all: it is
routed to the base implementation, which returns the **entity**. The declared projection type is
silently ignored, and the mismatch surfaces as a `ClassCastException` at the call site — not as a
wiring error, not at startup, and not in any test that never calls it.

Give the lookup a name the base does not own and it derives the same `id = ?` query, and does project:

```java
// ❌ captured by the base implementation; returns ShippingOrder; ClassCastException at the caller
Optional<OrderStatusView> findById(String id);

// ✅ derived — same query, and the projection applies
Optional<OrderStatusView> findOrderStatusById(String id);
```

The reserved names are every method on the base implementation, not just this one: `findById`,
`existsById`, `findAll`, `findAllById`, `count`, `save`, `saveAll`, `delete`, `deleteById`,
`deleteAll`, `deleteAllById` — plus, on JPA, `getById`, `getReferenceById`, `getOne`, `flush`,
`saveAndFlush`, `saveAllAndFlush`, `deleteAllInBatch`, `deleteAllByIdInBatch`, and on Mongo,
`insert`. Extending the bare marker does not make any of them callable; it does **not** stop them
capturing a same-named declaration.

**The mirror-image rule holds on the write side.** A query that belongs to a view slice must never be
added to the write repository instead — that is what "the read side served from the write model"
looks like in practice (§ The read side on this lane).

## Sanctioned sharing

Five forms of sharing are allowed, and only five:

1. **A per-BC shared `State` + `Evolver` in `use_cases/_shared/`** — under the promotion bar below.
   `_shared/` holds state reconstruction *only*; a decider in `_shared/` is an R1 violation wearing a
   disguise.
2. **A transaction-time, just-in-time read view** consulted by several deciders for one shared
   invariant.
3. **The aggregate, in `aggregates/`, on the aggregate style only** (§R5). This is one of the two
   places invariant-*deciding* is shared, because there the aggregate is the consistency boundary
   itself. It is exempt from the promotion bar below — counting consumers is meaningless for something
   shared by construction — and governed instead by § The aggregate's own bar.
4. **The entity and its write repository, in `entities/`, on the service-entity style only** (§R5).
   The other place invariant-deciding is shared, for the same reason: the entity is the consistency
   boundary. Also exempt from the promotion bar, and governed instead by § The entity's own bar. The
   write repository is shared *by the write path only* — a view slice reading through it is the
   violation, not the sharing (§ The read side on this lane).
5. **A published read seam** — an interface declared in the BC's `types/`, implemented by the slice
   that owns the data and registered so other slices can ask it a question. See § The published read
   seam below for the bar it has to clear.

Otherwise: share invariant-*reading*, never invariant-*deciding*.

### The published read seam

A read-owning slice sometimes has to answer a neighbour a question that no event carries and no
command asks. The alternatives are all worse: exposing its table (§R4), letting the neighbour import
its internals (§R4), or inventing an event whose only purpose is to be queried. The seam is the
sanctioned third answer, and it is a **read**, which is why it lives here rather than being a new
collaboration mechanism of its own — form 2 above already licenses shared invariant-*reading*; this
form gives it a declared interface and a name.

**The shape, and every part of it is load-bearing:**

```
<bc>/types/OrderCreditStatus.java      the interface — the BC's PUBLIC surface
<bc>/views/credit/CreditView.java      the implementation — slice-PRIVATE, registered as a bean
```

The interface goes in `types/` because `types/` is the BC's importable surface (§R4) and the consumer
must be able to name it. The implementation stays inside the owning slice, so the consumer depends on
the *question*, never on how it is answered — swap the projection behind it and no caller changes.

**The bar. All four, and a seam failing any of them is a finding, not a seam:**

1. **Read-only.** No method mutates, decides, or accepts a command. A seam that changes state is
   invariant-*deciding* shared across slices, which is the one thing this whole section forbids.
2. **It answers a question, it does not expose a model.** `boolean hasOverdueInvoice(CustomerId)` is a
   seam. `OrderRow findOrder(OrderId)` is the slice's read model wearing an interface, and the caller
   now depends on its shape — project what you need instead.
3. **It is declared in the manifest.** The owning slice lists it under `provides:`; the consuming
   slice lists it under `reads[].via`. A seam nobody declared is indistinguishable from a leak, and
   the seam graph is exactly the thing a manifest should make queryable.
4. **Prefer an event where an event will do.** A seam is a synchronous coupling: the consumer cannot
   proceed without the owner. If the consumer can react to something that happened rather than ask
   about it now, that is the cheaper design and § The four slice kinds already covers it.

**Cross-BC, the seam is the `via:` reader and is mandatory** — a slice reading another BC's data
without one is reaching across a boundary. Within a BC the seam is optional, and the bar above is what
keeps it from becoming a service layer reassembled one interface at a time.

### The `_shared/` promotion bar
<!-- slice-law: lane=decider -->

**The default is a per-slice `State` + `Evolver`, living in the slice directory.** A decider is
handed its aggregate's events; folding them itself is the normal case, not a workaround. These folds
are small — usually a dozen lines — and duplicating one is far cheaper than what sharing costs.

Sharing is **coupling under a friendlier name.** A `_shared/State` gives every slice that uses it a
reason to edit it, and every edit reaches all of them. Left alone it drifts toward the *union* of
what all consumers need: each decider then folds fields it never reads, and a field added for slice A
lands in slice B's state. That is the god-aggregate returning through the state layer — the same
gravity R1 exists to resist, one level down.

**Promote only when both hold:**

1. **Three or more deciders** need it. Two is a coincidence; three is a pattern. Carry the duplication
   until the third consumer proves the shape is real.
2. **They need the same state, not a union.** If promoting means widening `State` with a field only
   one consumer reads, the sharing is disproven — leave them separate.

Promotion is a **move**, not a rewrite: the per-slice `State` and `Evolver` keep their names and
change package. That is deliberate, so the cost of getting it wrong is one `git mv` back.

**The one exception below three:** two deciders enforcing *the same named invariant* off
independently-maintained folds. There the duplication is a divergence risk, not just repetition — the
folds can drift until one decider accepts what the other rejects, which is a bug rather than
untidiness. Share at two, and name the same `invariants[].id` in both slices' manifests so the reason
is on the record.

**The standing test after promotion:** if `_shared/State` ever holds a field only one decider reads,
split that field back out. A `_shared/` that only grows is one that was promoted too early.

## Wiring is part of done

Register every decider and processor (`@Bean` in the BC's `config/`) as part of the same change. An
unregistered decider fails no compile and no unit test — it silently breaks every `@SpringBootTest`
that expects the slice to work.

On the **service-entity** style there is usually nothing to write: `ReactiveHandlersBeanPostProcessor`
auto-registers any `CommandHandler` bean with the single `CommandBus` bean, and Spring Data
repositories are registered by scanning. The obligation becomes a **check** rather than an edit —
confirm the handler is a Spring bean in a scanned package, and that
`reactive-bean-post-processor-enabled` (default `true`) has not been switched off. Disabling it
silently unwires every handler in the application, which is the same failure mode as an unregistered
decider and just as invisible.

## Red flags

The checklist a self-check walks. Each entry names the section that holds its reasoning; read that
section rather than arguing the entry from its one line. The structural entries are layout and
boundary findings, the behavioural ones correctness bugs.

### Structural — every lane

- A decider class handling two or more command types, or an object with several `decideXxx()`
  methods (§R1).
- A **command** slice's API file with more than one request mapping (§R2).
- An API file serving more than one slice, or a view API method that reads a read model this slice
  does not own. Several query methods over the slice's *own* read model are fine (§R2).
- A `…Request`/`…Response` type that mirrors the command or the read model field for field, or any
  mapper/assembler/converter class between the API and the slice's own types (§R2).
- A file in `events/` declaring two or more concrete variants (§R3).
- A `controllers/`, `services/`, `repositories/`, `adapters/`, `ports/`, `infrastructure/`, `dto/`, or
  `mappers/` directory inside a bounded context (§ Directory vocabulary).
- An import that reaches into another slice's package beyond its `events/` and `types/` — except a
  command type named solely to dispatch it on the command bus (§R4).
- An entity, an aggregate, or an event importing a command type from `use_cases/<slice>/` (§R4).
- **More than one write style in a BC** — any two of per-slice deciders, `aggregates/`, and
  `entities/` (§R5).

### Structural — event-sourced view slices
<!-- slice-law: lane=decider,aggregate kind=view -->

- Two view slices projecting the same events into the same read-model shape with **no `supersedes`
  link** between them — one slice split by mistake, or a migration twin that never declared itself
  (§ Evolving a view slice).
- A `_v2` view slice whose superseded original is still `status: live`, or still present a release
  after the swap — the retirement never happened (§ Evolving a view slice).

### Structural — decider lane
<!-- slice-law: lane=decider -->

- `use_cases/_shared/` containing a decider, an API handler, or a repository (§ Sanctioned sharing).
- A `use_cases/_shared/` `State` + `Evolver` with **fewer than three** decider consumers, or with a
  field only one decider reads (§ The `_shared/` promotion bar).
- A decider-style BC with **no** `routing/`, or a command in it that does **not** implement its BC's
  routing marker — that command silently routes nowhere (§ Directory vocabulary).
- A sealed routing marker — adding a command then edits an existing file (§R3's contrast table in
  `slice-model.md` §4.1).

### Structural — aggregate lane
<!-- slice-law: lane=aggregate -->

- A `<bc>/routing/` — a decider-style vestige no configurator asks anything (§ Aggregate style).
- An aggregate whose method count tracks the slice count, whose methods mostly `apply(...)` with no
  invariant behind them, or with a getter or query method that exists to feed an API (§ The
  aggregate's own bar).
- A command slice that appends events directly rather than through the aggregate — outside the
  consistency boundary the style exists to hold (§ Aggregate style).

### Structural — service-entity lane
<!-- slice-law: lane=service-entity -->

- A `<bc>/routing/`, or a `use_cases/_shared/` — neither has a job on this lane; a `_shared/` here is
  a service class in disguise (§ Service-entity style).
- A `<bc>/entities/` directory in a BC that also references an `EventStore` or an `AggregateType` —
  not on this lane, or abandoning it by drift (§ Service-entity style).
- A query method on the BC's write repository whose only callers are view slices or API handlers, or
  a view slice injecting the write repository or calling `save`/`delete` anywhere (§ The read side
  on this lane).
- An entity whose method count tracks the slice count, or whose public methods only assign fields.
  **Not** a finding: accessors whose only callers are the ORM and `toString()` (§ The entity's own
  bar).

### Structural — Spring Data repositories
<!-- slice-law: store=spring-data -->

- A `@RestController` returning an `@Entity` / `@Document`, or a Spring Data-backed query returning
  the mapped type where a closed interface projection belongs (§ The read shape is a closed
  interface projection).
- A Spring Data repository extending `JpaRepository`, `MongoRepository`, `CrudRepository`, or another
  CRUD-family interface instead of the bare `Repository` marker (§ Repositories extend the bare
  `Repository` marker).

### Behavioural — every lane

- Loading another aggregate inside a command handler, or modifying several aggregates in one
  transaction.
- Querying a read model inside a decider to check an invariant — racy; use a transaction-time
  uniqueness projection instead.
- Synchronous cross-context calls where an event would do.
- Bypassing the command bus to call a decider directly.

### Behavioural — event-sourced lanes
<!-- slice-law: lane=decider,aggregate -->

- A `@MessageHandler` that writes versioned state — a view projection, or an automation persisting
  process state — and omits `OrderedMessage`: without `message.order` it cannot compare the event's
  `EventOrder` against the row's stored `version`, so redelivery double-applies. The parameter is
  *optional* to the dispatcher; do not flag a handler that carries no versioned state.

### Behavioural — service-entity lane
<!-- slice-law: lane=service-entity -->

- A public setter on an entity that writes a field an invariant method guards — the guard becomes
  bypassable (§ The entity's own bar).
- A mutable value object passed by reference from a command into a persisted entity — the command and
  the long-lived row now share state; defensive-copy it (`references/design/essentials-design.md`
  § State-stored entities).

### Behavioural — Spring Data repositories
<!-- slice-law: store=spring-data -->

- A Spring Data query method that declares a projection return type but is **named after a CRUD base
  method** — it returns the entity and presents as a `ClassCastException` at the call site (§ Never
  name a query method after a CRUD base method).

## Reporting severities

`/essentials:slice-check` and any review that cites this file report at three levels:

- **Blocking** — the code cannot be correct: R1/R2/R3/R4 violated, more than one write style in a BC,
  a decider unwired, a view projection mutating a versioned read model from a `@MessageHandler` that
  omits `OrderedMessage`, a read model queried inside a decider, a projection-returning query method
  named after a CRUD base method.
- **Should-fix** — the structure works but will rot: a slice missing its manifest, a `_shared/`
  holding more than state, a view slice with no test, a view reading through the BC's write
  repository, a repository extending a CRUD-family interface instead of the bare `Repository` marker.
- **Advisory** — worth a look: file-cohesion smells, naming drift, a missing invariant record, an id
  taken at the API edge as a raw scalar where the bounded context has a semantic id type.

## File cohesion (advisory)

**Ownership is the test**: a file mixing two slices' concerns is a god-class regardless of length —
split it. Size is only a secondary symptom; a domain file past roughly 300–400 lines warrants a
look, but a 40-line file serving two slices is the worse problem.

## Anti-Rationalisation

The arguments that sound reasonable mid-change, and the verdict on each. The reasoning lives in the
section each row names.

### Rationalisations — every lane

| Rationalisation | Reality |
|---|---|
| "One decider for all order commands is less duplication." | It makes every future slice edit the same file — the god-decider (§R1). |
| "It is just one more endpoint on the existing controller." | If that controller belongs to another slice, it is now two slices' concern, whatever the endpoint does (§R2). |
| "Every query shape needs its own view slice." | Only a different purpose over a different read-model shape does. Same model → same slice, another method (§R2). |
| "This view now needs one more event, so it is a new slice." | The slice is the read model's *purpose*, not its day-one event set. Extend it (§ Evolving a view slice). |
| "The API needs a Request DTO so the wire shape is decoupled." | The command *is* the wire shape; a mirror doubles the edit surface and decouples nothing (§ The command and the view *are* the contract). |
| "Returning the read model leaks internals." | It is a projection built for this query. A field the API must not expose is a deliberate divergence to record, not a reason for a mapper (§ The command and the view *are* the contract). |
| "I will add this filter by reading the other view's repository." | That reaches into another slice (§R4). Put it in *that* slice's API, or project the data yourself. |
| "All the events in one file is easier to read." | Until two slices change it in the same sprint (§R3). |
| "I only need to read the other slice's state, not change it." | You need *a* state, not *its* state: fold the events yourself (§R4). Another aggregate's state is out of reach inside a decider — use a transaction-time read view, or an automation (§ Sanctioned sharing). |
| "The decider can just query the read model to check uniqueness." | Read models are eventually consistent; the check is racy. Use a transaction-time projection with a unique constraint (§R1). |
| "I will wire the bean up later." | An unwired decider passes every unit test and fails every integration test (§ Wiring is part of done). |
| "Kotlin does not need `permits`, so Java should not either." | Java's sealed types require it; the one-name append is sanctioned, dropping `sealed` is not (§R3). |
| "This external system is basically internal, so no ACL." | The boundary defines it, not the org chart. Another team's service is external (§ The four slice kinds). |

### Rationalisations — event-sourced view slices
<!-- slice-law: lane=decider,aggregate kind=view -->

| Rationalisation | Reality |
|---|---|
| "The `_v2` view works; we will delete `v1` next sprint." | Then there are two permanent slices over one read model. Retirement is part of the change (§ Evolving a view slice). |

### Rationalisations — decider lane
<!-- slice-law: lane=decider -->

| Rationalisation | Reality |
|---|---|
| "Two deciders fold the same events, so let's share the evolver." | Two is a coincidence; promote at three, and only without a union. The exception is one named invariant (§ The `_shared/` promotion bar). |
| "Sharing the state now saves a refactor later." | Promotion is a two-file move that keeps every name; un-sharing a bent `State` is the expensive refactor (§ The `_shared/` promotion bar). |

### Rationalisations — aggregate lane
<!-- slice-law: lane=aggregate -->

| Rationalisation | Reality |
|---|---|
| "We use `AggregateRoot`, so this slice law is not for us." | Only where the decision lives changes; everything else applies unchanged (§ Aggregate style). |
| "The aggregate is exactly the god class R1 forbids." | R1 forbids a *router*. Named methods with their own invariants are not one — until they lose them (§ The aggregate's own bar). |
| "The aggregate is shared, so it belongs in `use_cases/_shared/`." | `_shared/` is state reconstruction only; a decision component there is a violation on sight. It gets `aggregates/` (§ Aggregate style). |
| "We already have the aggregate, so the new command is just another method on it." | The method is the *decision*; the slice — directory, command type, API file, test — is everything else (§R1, §R2). |
| "We are on Kotlin, so we will use `AggregateRoot` too." | Kotlin ships no aggregate pattern; this is Java interop that gives up the Kotlin decider API. A decision to state, not drift into (§ Aggregate style). |

### Rationalisations — service-entity lane
<!-- slice-law: lane=service-entity -->

| Rationalisation | Reality |
|---|---|
| "We are not event-sourced, so this slice law is not for us." | Only where the decision lives and how state is stored changes; everything else applies unchanged (§ Service-entity style). |
| "Adopting the slice law means adopting the event store." | This lane has none. Formalise the entity into `entities/` and split the god handler (§ Service-entity style). |
| "There is no event store, so we do not need a `views/` slice — just query the entity." | The query still has an owner, a shape and a test. The lane skips the *projector*, not the slice (§ The read side on this lane). |
| "The repository is shared, so it needs a `repositories/` folder." | It is shared by the write path, so it lives in `entities/`; a `repositories/` folder is a layer (§ Service-entity style). |
| "It is one more finder on the existing repository — the view needs it." | That serves the read side from the write model. The view gets its own query interface and read shape (§ The read side on this lane). |
| "The ORM makes us expose getters, so the no-query-surface bar cannot apply." | The bar goes by **caller**, not shape (§ The entity's own bar). |

### Rationalisations — Spring Data repositories
<!-- slice-law: store=spring-data -->

| Rationalisation | Reality |
|---|---|
| "Returning the JPA entity is simpler than a projection interface." | It hands out a managed, mutable object and the whole write model as wire contract. The projection is the cheaper option (§ The read shape is a closed interface projection). |
| "A projection interface is a DTO with extra steps." | A DTO is kept in sync by hand; a projection is a declaration checked at startup — not the mapper R2 forbids (§ The read shape is a closed interface projection). |
| "`JpaRepository` gives us `findAll` for free — why type the methods out?" | It also gives every caller `deleteAll`, and a view `save` over the write model (§ Repositories extend the bare `Repository` marker). |
| "`findById` is the obvious name for the projection's lookup." | It is the one name that cannot work (§ Never name a query method after a CRUD base method). |
