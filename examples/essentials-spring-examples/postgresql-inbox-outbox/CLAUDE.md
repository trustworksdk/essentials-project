# postgresql-inbox-outbox

Demo Spring Boot app for the PostgreSQL/JPA flavour of Essentials `Inbox`/`Outbox` store-and-forward
over Kafka. Not part of the release. One bounded context, `shipping`, on the **service-entity** write
style (§R5) — no event store anywhere in this module.

Its sibling `mongodb-inbox-outbox` is the same application in a MongoDB flavour and follows the same
slice structure; `postgresql-cqrs` models the same domain the *other* way (CQRS + event sourcing,
aggregate style), which is the comparison the two are there to make.

```bash
mvn verify -pl :postgresql-inbox-outbox              # unit + ITs (needs Docker; the load IT inserts 15k rows)
mvn -Pjackson2 verify -pl :postgresql-inbox-outbox -am  # the other Jackson flavour; -am is required
mvn spring-boot:run -pl :postgresql-inbox-outbox        # after `docker compose up -d` in the parent dir
```

The `-am` is not optional on the non-default flavour: without it a sibling resolves from the local repo
with the property unresolved and **both** Jackson flavours land on the classpath. See the root
`CLAUDE.md`.

### Why the Kafka DTOs must not carry `OrderId` (it broke `-Pjackson2`)

Both profiles are green. They were not: `ShippingFlowIT` — anything crossing Kafka — failed under
`-Pjackson2` in this module and in `mongodb-inbox-outbox`. Zero records reached the `shipping-events`
topic, and a `ShipOrder` arriving off the command queue could not find its row (`getOrder` threw
`NoSuchElementException`). The failure predated the slice refactor.

The cause was the anti-corruption boundary not actually translating. `config/KafkaConfiguration` binds
Spring Boot 4's Jackson **3** `JsonMapper`, while `-Pjackson2` puts the Jackson **2** flavour of the
Essentials types module on the classpath — so the single-value-type (de)serializer lands on a mapper
Kafka never uses. That only mattered because the DTOs were typed with the domain's `OrderId`. They now
carry a plain `String`, as `postgresql-cqrs` always has, and the two adapters convert at the boundary.
Nothing internal crosses the wire, so the mapper flavour is no longer load-bearing.

Re-typing those DTOs with `OrderId` reintroduces both the coupling and the `-Pjackson2` failure. See
the translation slice's `CLAUDE.md`.

## Layout

```
messaging/
  Application.java
  config/          app-level wiring — Kafka factories, JPA config, OrderId attribute converter, WebConfiguration
  shipping/        the bounded context — see its CLAUDE.md first
```

`config/` is module-level infrastructure and sits outside the BC. It must not reach into slice
internals: `KafkaConfiguration` derives its Kafka trusted-packages prefix from `Application`'s package
rather than from a type inside a slice.

## Commands are the durably-persisted artefact here

On this write style there is no event store, so the JSON that has to stay readable across an upgrade is
**command** payloads sitting in `Inbox`/`Outbox`/`DurableQueues` tables, plus the Kafka DTOs. Under
Jackson 3 a record's canonical constructor parameter names *are* its JSON property names, so renaming a
component of `ShipOrder`, `RegisterShippingOrder`, `ShippingOrderRegistered` or `ExternalOrderShipped`
is a wire-format change. `ShippingDestinationAddress` is not a record but is bound the same way, which
is why its copy helper is a static factory and not a second constructor. Touching any of them means
running both Jackson profiles.

## `use-centralized-message-fetcher` stays `true`

Starter default, and what `postgresql-cqrs` uses. Was `false` here for a while — an experiment, not a
finding; reverted. Don't flip it back and don't document it as deliberate.

`polling-delay-interval-increment-factor` and `max-polling-interval` are still set in
`application.properties` but are **dead** in this mode — they configure the legacy per-consumer polling
path that `false` selects. Kept as worked examples of the properties, not as tuning.

## Tests

| Test | Scope |
|---|---|
| `shipping/entities/ShippingOrderTest` | unit — the BC's only invariant, no Spring, no container |
| `shipping/ShippingFlowIT` | the whole flow: register → Kafka `OrderAccepted` → `Inbox` → ship → `Outbox` → Kafka |
| `shipping/views/order_status/OrderStatusIT` | the view slice, asserting strong consistency with no `Awaitility` |
| `shipping/LoadOrderShippingProcessorIT` | 15 000 rows through the `Inbox` — ~45 s (was ~2 min on the legacy polling path) |
| `DurableQueuesLoadIT` | `DurableQueues` throughput, independent of the shipping BC |

`AbstractIntegrationTest` holds the shared PostgreSQL + Kafka containers. The load harness the load IT
drives lives in `shipping/load/` in **test** scope — see the BC's `CLAUDE.md` for why it is not in
`src/main`.

## Auditing

Run `/essentials:slice-check` **with this module as the root**, not the repo root. Each example under
`examples/essentials-spring-examples/` is a separate application that happens to model the same
`shipping` domain, so their `slice:` ids coincide by design — auditing them together reports those as
gate-3 duplicates, which they are not.

<!-- essentials-slices-rules: v2 — generated by the essentials plugin; edit the plugin, not this file -->

# Essentials slice rules

Package by **vertical slice**, never by technical layer. This file is a pointer, not the law: the
full law is the essentials plugin's `rules/slice-design.md`, reachable via `/essentials:add-slice`,
`/essentials:slice-check`, or by asking about Essentials slices.

## Directory vocabulary and the four kinds

```
<bc>/ use_cases/<slice>/  command — enforces invariants, emits events. One endpoint.
      views/<slice>/      view — answers queries over the model it owns.
      automations/<slice>/       automation — reacts to events, issues commands. No API.
      external_systems/<sys>/    translation — anti-corruption boundary. No API.
      use_cases/_shared/  shared State+Evolver only — 3+ deciders, else keep it per-slice
      aggregates/ | entities/    this BC's write style (R5) — exactly one, never two
      events/ types/      the BC's public surface     routing/ config/  routing + wiring
```

Underscores, not hyphens — hyphens are illegal in JVM package names. A `_`-prefixed directory is not
a slice. No layer directories: `controllers/ services/ repositories/ adapters/ ports/ dto/ mappers/`.

## The boundary rule

A slice may import only another slice's or BC's `events/` and `types/` — never its decider, evolver,
state, handler, repository, or endpoint. The one exception is naming a command type to dispatch it on
the command bus. Nothing in `events/`, `entities/`, or `aggregates/` may name a command type at all.

One decision component per command slice (a decider, or the BC's aggregate or entity — R5), one endpoint.
One API file per slice — a view may expose several queries over its OWN read model, and needing
one more event extends that slice rather than forking it. One event variant per file. Wiring is done.

Run `/essentials:slice-check` to audit.
