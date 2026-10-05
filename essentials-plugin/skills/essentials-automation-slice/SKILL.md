---
name: essentials-automation-slice
description: >
  Scaffold a Trustworks Essentials automation slice (policy / process manager) in Java or Kotlin —
  optional TodoList process state and its repository, an EventProcessor reacting across aggregate
  types with idempotent handlers, a bounded redelivery policy, slice.yaml manifest, per-slice
  CLAUDE.md, and the integration test. Invoked by /essentials:add-slice and
  /essentials:add-automation-slice.
user-invocable: false
disable-model-invocation: true
allowed-tools: [Read, Write, Edit, Glob, Grep, Bash]
---

# Automation slice — Essentials

An automation slice reacts to what happened and issues the next command. `Event(s) → [TodoList] →
Command`. It has **no external API**.

## Inputs

Supplied by the dispatching command. **Never re-elicit these.**

| Input | Example |
|---|---|
| `language` | `kotlin` \| `java` |
| `lane` | `decider` \| `aggregate` — the owning BC's §R5 write style. The two event-sourced lanes get the same files; the lane is passed so the manifest can record it. **`service-entity` is refused** (Step 0) |
| `tier` | `cqrs-es` (both event-sourced lanes) \| `service-entity`. **Derived from `lane`, and not the same value** — the manifest's `architectureTier` vocabulary, a different axis (`slice-authoring.md` §4). Never render `tier: aggregate`: it is not a tier value, and another tool reading the manifest silently downgrades an unrecognised tier to `custom` and skips the slice |
| `projectRoot`, `sourceRoot`, `testRoot`, `packagePath` | resolved from the project |
| `bc` / `Bc`, `AggregateType` | `orders` / `Orders`, `Orders` |
| `slice` / `Slice` / `sliceCamel` | `fulfillment` / `Fulfillment` / `fulfillment` |
| `Event` | the first event that drives the process |
| `owner` | `orders-team` |

## Step 0 — Refuse on the service-entity lane

**If `lane` is `service-entity`, emit nothing and stop.** No automation template exists for that lane,
and the event-sourced one does not fit it: it is an `EventProcessor` subscribed to an event-store
`AggregateType`, whereas a service-entity bounded context has no event store — its events are
published in-process on the `EventBus` (`slice-authoring.md` §3). Rendered there it either does not
compile (a Mongo or pg-crud project ships no event store) or compiles and never receives an event.
Say that, and tell the user the slice is theirs to write by hand as an `EventBus` subscriber
(`references/llm/LLM-reactive.md`). `render-slice.py` refuses the combination too.

## Step 1 — Load the law and the shared procedure

```
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/slice-law.py --lane <lane> --kind automation --project <projectRoot>
Read ${CLAUDE_PLUGIN_ROOT}/references/slice/slice-authoring.md
```

`slice-law.py` prints the sections of `rules/slice-design.md` that apply to this lane, this kind and
the project's persistence, and names the ones it left out — that output is the law for this skill,
cited by the file's own section names. Entered from `essentials-change`, the law is already in
context: when its header line names this `lane`, do not print it again.

The files are written by `scripts/render-slice.py` (`slice-authoring.md` §4b). This skill decides the
shape, runs the script, then prunes, fills the TODOs and reports.

## Step 2 — Decide the shape

**Stateless or stateful?** The minimum automation is one event in, one command out — no TodoList, no
repository. Reach for the TodoList only when progress spans several events and the process must
remember what has already happened. Ask: *would a second event arriving out of order change what
this should do?* If no, stay stateless and delete the TodoList and repository from the emission.

**Idempotency is not optional.** The Inbox redelivers; the same event *will* arrive twice. Every
handler starts by checking whether its step already happened and returning early. This is the single
most common automation defect and the single most important test.

**Make the guards explicit.** `canProceed()` / `canInitiatePayment()` on the TodoList, rather than
boolean conditions scattered across handlers. The guard is the process rule — it is what is worth
naming, and what the test asserts.

**Bound the retries and compensate.** Cap attempts. On terminal failure issue the compensating
command rather than letting the process stall silently. A stuck process manager is invisible until
someone asks why an order never shipped.

**Delayed commands.** `sendAndDontWait(command, Duration.ofMinutes(15))` is queued durably on the
command bus's `DurableQueues` queue and delivered after the delay; it survives restarts. Handle it with `@CmdHandler`. Use this
rather than a scheduler for process timeouts.

**Base class:** `EventProcessor` (Inbox-backed, with a redelivery policy), taking
`EventProcessorDependencies`. Not `ViewEventProcessor` — that is for read models and has no
redelivery semantics for outbound work.

**Cross-aggregate reach.** Automations commonly react to events from several aggregate types; list
each in `reactsToEventsRelatedToAggregateTypes()`. This is legitimate — an automation reads other
BCs' *events*, which is exactly the collaboration §R4 permits.

**No API.** If the requirement wants an endpoint, that is a command or view slice, not an
automation.

## Step 3 — Check or scaffold the bounded context

`Glob <sourceRoot>/<packageDir>/<bc>/`. **If absent, stop:** a bounded context starts with its first
command slice (`slice-authoring.md` §3). The processor imports `<bc>/events/<Event>`, which only a
command slice supplies, so the script refuses `--new-bc` for this kind. Tell the user, and offer
`/essentials:add-command-slice` for the BC's first command.

## Step 4 — Emit

First the module preconditions; exit 1 names each module the build file lacks — report it and offer
to add it before going on:

```bash
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/render-slice.py requires --lang <language> --kind automation \
    --lane <lane> --build <the build file from add-slice Step 0>
```

Then render. Pass the inputs you were given; the script derives the rest (`slice-authoring.md` §4b):

```bash
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/render-slice.py render --lang <language> --kind automation \
    --lane <lane> --json \
    --project-root <projectRoot> --main-root <sourceRoot> --test-root <testRoot> \
    --set packagePath=<packagePath> --set bc=<bc> --set slice=<slice> \
    --set AggregateType=<AggregateType> --set Event=<Event> --set owner=<owner>
```

Exit 2 means nothing was written: relay the message and stop. The script always writes the stateful
shape from `templates/<language>/automation/`; **for a stateless automation (Step 2), delete the two
files marked below and reduce the processor's handler to the one command it dispatches.** Then fill the
TODOs the JSON lists under `todos`.

| Template | Destination | Skip when |
|---|---|---|
| `__Slice__TodoList.<ext>` | `<bc>/automations/<slice>/` | stateless |
| `__Slice__Repository.<ext>` | `<bc>/automations/<slice>/` | stateless |
| `__Slice__Processor.<ext>` | `<bc>/automations/<slice>/` | — |
| `test/__Slice__IT.<ext>` | test tree, mirroring the slice package | — |
| `slice.yaml`, `CLAUDE.md.template` | `<bc>/automations/<slice>/` | — |

The script refuses an existing slice directory and any file it would overwrite.

## Step 5 — Wire it

The processor is a `@Service`; the repository is a `@Bean`. Confirm the BC's package is scanned.
Record every consumed event in `slice.yaml` `consumes` and every dispatched command in `dispatches`
— an automation with an empty `dispatches` is either stateless plumbing or a mislabelled view.

**A schedule-triggered automation declares `schedule`, not an empty `consumes`.** The law lists this
kind's trigger as "event / **schedule**", and the schema accepts either. Write
`schedule: { cron: "…", note: "…" }` (or `fixedDelay`), delete the `consumes` line, and give the
`note` — a cadence with no stated reason is the one nobody dares change. Both fields together are
legal where a process is driven by events *and* a sweep. `consumes: []` records nothing.

## Step 6 — Report and self-check

Report the files written. State what the user must fill in: the real guard in `canProceed()`, a
handler per driving event including the failure paths, the commands to dispatch, and the
compensation path.

Walk § Red flags in the law printed in Step 1 — do not load it again — and confirm: every handler that persists process state
takes `OrderedMessage` and passes its order to the `save`/`update` (the parameter is optional to the
dispatcher — it is here for the version, not for dispatch); every handler is idempotent; the slice
exposes no endpoint.

## Red flags specific to this kind

- A handler with no early-return idempotency check.
- Unbounded retries, or no compensation on terminal failure.
- A `@RestController` anywhere in the slice.
- The processor writing to the event store directly instead of issuing a command.
- `ViewEventProcessor` as the base class.
- Guard logic inlined in handlers rather than named on the TodoList.

## API provenance

Every Essentials symbol in these templates is listed in
`${CLAUDE_PLUGIN_ROOT}/references/slice/api-provenance.md`. Never introduce one that is not there.
