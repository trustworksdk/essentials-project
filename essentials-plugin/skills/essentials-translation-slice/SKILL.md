---
name: essentials-translation-slice
description: >-
  Scaffold a Trustworks Essentials translation slice (anti-corruption layer) in Java or Kotlin under
  external_systems/{system}/ — a typed client port, a pure translator, an inbound handler
  dispatching internal commands via the Inbox, an optional outbound EventProcessor publisher
  (Outbox), slice.yaml with externalSystem/direction/maps, per-slice CLAUDE.md, and the translator
  round-trip test. Invoked by /essentials:add-slice and /essentials:add-translation-slice.
user-invocable: false
disable-model-invocation: true
allowed-tools: [Read, Write, Edit, Glob, Grep, Bash]
---

# Translation slice — Essentials

A translation slice is an anti-corruption layer: the only place an external system's schema is
allowed to appear. It has **no external API of its own**.

## Inputs

Supplied by the dispatching command. **Never re-elicit these.**

| Input | Example |
|---|---|
| `language` | `kotlin` \| `java` |
| `lane` | `decider` \| `aggregate` — the owning BC's §R5 write style. The two event-sourced lanes get the same files; the lane is passed so the manifest can record it. **`service-entity` is refused** (Step 0) |
| `tier` | `cqrs-es` (both event-sourced lanes) \| `service-entity`. **Derived from `lane`, and not the same value** — the manifest's `architectureTier` vocabulary, a different axis (`slice-authoring.md` §4). Never render `tier: aggregate`: it is not a tier value, and another tool reading the manifest silently downgrades an unrecognised tier to `custom` and skips the slice |
| `projectRoot`, `sourceRoot`, `testRoot`, `packagePath` | resolved from the project |
| `bc` / `Bc`, `AggregateType` | `orders` / `Orders`, `Orders` |
| `externalSystem` / `ExternalSystem` | `billing` / `Billing` |
| `ExternalEvent` | `InvoiceIssued` |
| `direction` | `inbound` \| `outbound` \| `both` |
| `Event` | the internal event published outbound — always passed to the renderer (Step 4) |
| `owner` | `orders-team` |

## Step 0 — Refuse on the service-entity lane

**If `lane` is `service-entity`, emit nothing and stop.** No translation template exists for that lane,
and the event-sourced one does not fit it: it is an `EventProcessor` subscribed to an event-store
`AggregateType`, whereas a service-entity bounded context has no event store — its events are
published in-process on the `EventBus` (`slice-authoring.md` §3). Rendered there it either does not
compile (a Mongo or pg-crud project ships no event store) or compiles and never receives an event.
Say that, and tell the user the slice is theirs to write by hand as an `EventBus` subscriber
(`references/llm/LLM-reactive.md`). `render-slice.py` refuses the combination too.

## Step 1 — Load the law and the shared procedure

```
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/slice-law.py --lane <lane> --kind translation --project <projectRoot>
Read ${CLAUDE_PLUGIN_ROOT}/references/slice/slice-authoring.md
```

`slice-law.py` prints the sections of `rules/slice-design.md` that apply to this lane, this kind and
the project's persistence, and names the ones it left out — that output is the law for this skill,
cited by the file's own section names. Entered from `essentials-change`, the law is already in
context: when its header line names this `lane`, do not print it again.

The files are written by `scripts/render-slice.py` (`slice-authoring.md` §4b). This skill decides the
shape, runs the script, then prunes, fills the TODOs and reports.

## Step 2 — Decide the shape

**What counts as "external" is a boundary question, not a distance question.** Another team's
service is external even in the same datacentre. Your own browser client is not — that is a command
or view slice. If you own both sides of the schema and can change them together, it is not an ACL.

**Direction.**

| Direction | Emits | Mechanism |
|---|---|---|
| `inbound` | external message → internal command | ingress handler → `sendAndDontWait` (Inbox: durable, retried) |
| `outbound` | internal event → external call | `EventProcessor` + `getInboxRedeliveryPolicy()` (Outbox) |
| `both` | both of the above | both files |

The template ships `both`; delete the half you do not need and set `direction` in `slice.yaml` to
match. Do not ship a stub that throws.

**The translator is pure — and that is the whole design.** No Spring, no Essentials, no I/O imports.
That is what lets the mapping be tested without either side running, and it is why the mapping is
its own class rather than inline in the handler. All external→internal type conversion happens here:
external ids, strings, and dates become the BC's semantic types at this boundary and nowhere else.

**Nothing external escapes.** If an external wire type reaches a Decider, an event, a view, or
another slice, the ACL has failed and the slice is decorative. That is the one invariant worth
asserting in review.

**The client is an interface.** A typed port, so the transport (REST client, SDK, message producer)
is swappable and the publisher is testable without the external system.

**Inbound is Inbox-backed, so the receiver must be idempotent.** `sendAndDontWait` retries. The
command's target slice will see it more than once.

**Test floor is a contract test — and this plugin bundles no contract-testing tooling.** The shipped
test is a plain translator round-trip. Tell the user to add Pact or Specmatic themselves and flip
`tests.contract.present`. **Do not invent a Pact dependency or import.**

## Step 3 — Check or scaffold the bounded context

`Glob <sourceRoot>/<packageDir>/<bc>/`. **If absent, stop:** a bounded context starts with its first
command slice (`slice-authoring.md` §3). The publisher imports `<bc>/events/<Event>`, which only a
command slice supplies, so the script refuses `--new-bc` for this kind. Tell the user, and offer
`/essentials:add-command-slice` for the BC's first command.

## Step 4 — Emit

First the module preconditions; exit 1 names each module the build file lacks — report it and offer
to add it before going on:

```bash
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/render-slice.py requires --lang <language> --kind translation \
    --lane <lane> --build <the build file from add-slice Step 0>
```

Then render. Pass the inputs you were given; the script derives the rest (`slice-authoring.md` §4b).
`Event` is required for every direction because the template ships `both`; for an `inbound` slice pass
any event of this BC — the file that uses it is deleted below:

```bash
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/render-slice.py render --lang <language> --kind translation \
    --lane <lane> --json \
    --project-root <projectRoot> --main-root <sourceRoot> --test-root <testRoot> \
    --set packagePath=<packagePath> --set bc=<bc> --set externalSystem=<externalSystem> \
    --set ExternalEvent=<ExternalEvent> --set Event=<Event> --set AggregateType=<AggregateType> \
    --set owner=<owner>
```

Exit 2 means nothing was written: relay the message and stop. The script writes the `both` shape into
`<bc>/external_systems/<externalSystem>/`. **Then prune for `direction`**: delete the files the table
marks for it, set `direction` in `slice.yaml`, and remove the manifest lines and translator method of
the half you deleted (the external message and its `maps` entry for outbound, the internal event and
its `maps` entry for inbound — Step 5). Then fill the TODOs the JSON lists under `todos`.

| Template | Skip when |
|---|---|
| `__ExternalSystem__Translator.<ext>` | never |
| `__ExternalSystem__Client.<ext>` | `direction: inbound` |
| `__ExternalSystem__ClientAdapter.<ext>` | `direction: inbound` |
| `On__ExternalEvent__.<ext>` | `direction: outbound` |
| `__ExternalSystem__Publisher.<ext>` | `direction: inbound` |
| `test/__ExternalSystem__TranslatorTest.<ext>` | never |
| `slice.yaml`, `CLAUDE.md.template` | never |

Note the directory is `external_systems/` with an underscore — hyphens are illegal in JVM package
names. The script refuses an existing slice directory and any file it would overwrite.

## Step 5 — Wire it

The publisher is a `@Service`, the ingress a `@RestController` and the client adapter a `@Component`;
confirm the BC's package is scanned. Both construct the translator themselves — it is pure, so it is
never a bean. The adapter's `send` throws until the user writes the real transport, so the context
starts and every outbound event is redelivered and then dead-lettered rather than dropped — say so
explicitly in the report, because the slice does not deliver anything until the user supplies it.

Record every mapping in `slice.yaml` `maps` as `{ from, to }` pairs.

Record in `consumes` every message that triggers the slice: the external message the ingress
receives, **and** every internal event the publisher's `@MessageHandler` methods handle —
`/essentials:slice-check` gate 11(b) reads those parameter types against `consumes`, so an outbound
publisher's events belong there on every direction.

**One exception, and it is a real shape rather than a loophole: a port-style ACL with no publisher.**
Where the system only calls *out*, other code calls the client, and nothing in the slice handles an
event, there is no inbound message, nothing to consume and no mapping table. Set `direction:
outbound` and **delete** `consumes` and `maps` rather than leaving them empty — the schema stops
requiring them on that direction, and an empty list records nothing. It is still unambiguously a
translation slice: it is the one place an external schema may appear. An outbound slice that keeps
its publisher keeps `consumes` with the publisher's events.

On `inbound` or `both`, an empty `maps` still means this is not a translation slice.

## Step 6 — Report and self-check

Report the files written. State plainly what is still missing: the client implementation, the real
field mappings in both directions, and the contract test.

Walk § Red flags in the law printed in Step 1 — do not load it again — then check the ACL invariant
directly: grep the rest of
the bounded context for the external type names and confirm zero hits outside this directory.

## Red flags specific to this kind

- An external wire type referenced outside `external_systems/<system>/`.
- The translator importing Spring, Essentials, or an HTTP client.
- Mapping logic inlined in the ingress handler or the publisher.
- A `ViewEventProcessor` used for outbound publishing.
- A "translation" slice for a system you own both sides of.
- An invented Pact/Specmatic dependency — the plugin bundles none.

## API provenance

Every Essentials symbol in these templates is listed in
`${CLAUDE_PLUGIN_ROOT}/references/slice/api-provenance.md`. Never introduce one that is not there.
