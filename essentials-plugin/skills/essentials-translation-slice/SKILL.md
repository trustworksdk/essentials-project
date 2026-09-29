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
| `lane` | `decider` \| `aggregate` \| `service-entity` — the owning BC's §R5 write style. This kind's templates are **lane-independent**, so the lane changes nothing about what is emitted; it is passed so the manifest can record it |
| `tier` | `cqrs-es` (both event-sourced lanes) \| `service-entity`. **Derived from `lane`, and not the same value** — the manifest's `architectureTier` vocabulary, a different axis (`slice-authoring.md` §4). Never render `tier: aggregate`: it is not a tier value, and another tool reading the manifest silently downgrades an unrecognised tier to `custom` and skips the slice |
| `projectRoot`, `sourceRoot`, `testRoot`, `packagePath` | resolved from the project |
| `bc` / `Bc`, `AggregateType` | `orders` / `Orders`, `Orders` |
| `externalSystem` / `ExternalSystem` | `billing` / `Billing` |
| `ExternalEvent` | `InvoiceIssued` |
| `direction` | `inbound` \| `outbound` \| `both` |
| `Event` | the internal event published outbound (outbound/both only) |
| `owner` | `orders-team` |

## Step 1 — Load the law and the shared procedure

```
Read ${CLAUDE_PLUGIN_ROOT}/rules/slice-design.md
Read ${CLAUDE_PLUGIN_ROOT}/references/slice/slice-authoring.md
```

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

`Glob <sourceRoot>/<packageDir>/<bc>/`. If absent, emit `templates/<language>/bc-scaffold/` first,
**omitting `events/`** unless this slice is outbound and the BC already has an event to publish — an
empty Java `permits` clause does not compile.

## Step 4 — Emit

From `${CLAUDE_PLUGIN_ROOT}/references/slice/templates/<language>/translation/`, into
`<bc>/external_systems/<externalSystem>/`:

| Template | Skip when |
|---|---|
| `__ExternalSystem__Translator.<ext>` | never |
| `__ExternalSystem__Client.<ext>` | `direction: inbound` |
| `On__ExternalEvent__.<ext>` | `direction: outbound` |
| `__ExternalSystem__Publisher.<ext>` | `direction: inbound` |
| `test/__ExternalSystem__TranslatorTest.<ext>` | never |
| `slice.yaml`, `CLAUDE.md.template` | never |

Note the directory is `external_systems/` with an underscore — hyphens are illegal in JVM package
names. Abort if the slice directory exists, or if a rendered file still contains `{{` or `__`.

## Step 5 — Wire it

The publisher is a `@Service` and the ingress a `@RestController`; confirm the BC's package is
scanned. The client interface needs a real implementation — say so explicitly in the report, because
the slice will not work until the user supplies one.

Record every mapping in `slice.yaml` `maps` as `{ from, to }` pairs.

**One exception, and it is a real shape rather than a loophole: a port-style ACL.** Where the system
only calls *out* and nothing calls in, there is no inbound message and therefore no mapping table.
Set `direction: outbound` and **delete** `consumes` and `maps` rather than leaving them empty — the
schema stops requiring them on that direction, and an empty list records nothing. It is still
unambiguously a translation slice: it is the one place an external schema may appear.

On `inbound` or `both`, an empty `maps` still means this is not a translation slice.

## Step 6 — Report and self-check

Report the files written. State plainly what is still missing: the client implementation, the real
field mappings in both directions, and the contract test.

Re-read `rules/slice-design.md` § Red flags, then check the ACL invariant directly: grep the rest of
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
