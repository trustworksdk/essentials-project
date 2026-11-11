# Concepts and glossary

New to Essentials, to CQRS or to event sourcing? Start here. This page explains the words the rest of the
documentation, the LLM docs and the Claude Code plugin take for granted. It says what each word means and
why it matters, then links to the doc that owns the API. It shows no API in full, so the module docs stay
the single source.

**Prefer to watch it happen?** [`event-sourcing-vs-crud/`](event-sourcing-vs-crud/) is an animated
walkthrough, built from the same argument as the first section below. It runs a CRUD webshop and an
event-sourced one side by side, step by step. Open `index.html` in a browser; `SCRIPT.md` is its text.
Its ten concept cards are the ten entries of [Core vocabulary](#core-vocabulary).

- [The idea in one page](#the-idea-in-one-page)
- [Core vocabulary](#core-vocabulary): event, handler and outbox, eventual consistency, event store,
  event processor, command, decider, projection, aggregate, slice
- [CQRS](#cqrs)
- [The building blocks underneath](#the-building-blocks-underneath): UnitOfWork, DurableQueues,
  Inbox and Outbox, FencedLock, semantic types, optimistic concurrency
- [Designing a system](#designing-a-system): bounded context, aggregate size, from Event Modeling to slices
- [The plugin's vocabulary](#the-plugins-vocabulary): slice law, write styles, `slice.yaml`, stack
  contract, ESS ids
- [Where to go next](#where-to-go-next)

---

## The idea in one page

Event sourcing is **CRUD plus a journal**, taken seriously.

1. **A CRUD system keeps only the latest state.** `UPDATE products SET price = 1225.00` overwrites the
   old price, and the reason it changed is gone.
2. **Side effects get bolted on.** Send an email, update a dashboard, call a payment gateway. Each of them
   is called from the code that saved the row, so that code grows with every new requirement.
3. **Making it event-driven helps.** The saving code publishes "order placed", and handlers react. An
   *outbox* stored in the same transaction makes sure no reaction is lost.
4. **Then ask why the row is kept at all.** If every change is already recorded as an event, the events
   are the truth, and the row can be recomputed from them.
5. **Reading and writing become symmetrical.** Writing appends events. Every table you read from is a
   *projection* built from those events, and can be deleted and rebuilt by replaying them.
6. **Decisions shrink.** A command needs only the past events its decision depends on. One small
   *decider* per command replaces one big class that knows everything.
7. **Events name business facts**, such as `OrderPlaced` or `ProductPriceChanged`, not
   `OrderUpdated`. A row-shaped "something changed" event carries no meaning a new subscriber could use.
8. **History is a bonus you get for free.** You can see what a screen showed last Tuesday, build a report
   nobody asked for when the data was written, or start a new projection from the first event.
9. **It is honest about its costs.** You choose, per screen, how fresh a view must be. Event types are a
   contract with data already stored, so changing them needs care.
10. **A new requirement becomes a new subscriber or a new projection**, not an edit to code that already
    works. The Essentials plugin scaffolds each of those pieces for you.

---

## Core vocabulary

### Event

A record that something happened in the business, named in the **past tense**: `OrderPlaced`,
`ProductPriceChanged`. It is a fact: it cannot be refused or changed afterwards, only followed by more
events. It carries what happened and nothing more. Some events need only the id, such as `OrderPlaced(id)`;
others carry the why, such as `OrderCancelled(id, reason)`. The code that records an event does not know
who listens to it.

Stored as an event type plus a JSON payload. The type is the event class's name, so **renaming or moving an
event class leaves the events already stored unreadable**, until you map the old name
([ESS-138](../LLM/LLM-traps.md#ess-138),
[LLM-postgresql-event-store.md § Renaming or moving an event class](../LLM/LLM-postgresql-event-store.md#renaming-or-moving-an-event-class)).

### Handler and outbox

A **handler** is code that reacts to one kind of event. An **outbox** is a table in the same database:
the handler stores the work (send the email) there *in the same transaction* as the change, and a
background consumer does it afterwards. If the call fails, it is retried, and after the last retry the
message becomes a *dead letter* that someone can inspect and resend. The customer never waits for the
mail server, and an order cannot commit without its email being queued.

Essentials: `EventBus` and `AnnotatedEventHandler` ([LLM-reactive.md](../LLM/LLM-reactive.md)), `Outbox`
([LLM-foundation.md § Inbox/Outbox Patterns](../LLM/LLM-foundation.md#inboxoutbox-patterns)).

### Eventual consistency

Something derived from a change, such as a dashboard counter, is updated **just after** the change commits,
in its own transaction, instead of inside it. For a moment it shows the old value; then it catches up. In
return, the write does not wait for or lock what is derived from it. Nothing is lost: the update is queued
and retried. Whether a given view may lag is a choice you make per view; see
[event processor](#event-processor) for the one that does not lag.

### Event store

An append-only table: events are added, **never updated or deleted**. It is the system's source of
truth, and every other table is derived from it. Three words go with it:

| Word | Meaning |
|---|---|
| **Aggregate** | One thing decisions are made about, with its own id: an order, a product. |
| **Stream** | One aggregate's events, in order: everything that ever happened to order `ORD-1042`. |
| **Aggregate type** | All aggregates of one kind, such as `Orders`. In Essentials an `AggregateType` gets one table of its own (`orders_events`). |

Each stored event carries two counters, and they are easy to confuse:

- **`EventOrder`**: the event's position *within its own stream*, counted from 0. An order's current
  state is its stream read from `EventOrder` 0 and replayed.
- **`GlobalEventOrder`**: its position across *every stream of the same aggregate type*. Subscribers
  remember the last one they handled and resume from there.

Events are ordered by these counters, **never by timestamp**. Two writers cannot both append the same
`EventOrder` to one stream: the second fails (see [optimistic concurrency](#optimistic-concurrency)).
Because every appended event is something to publish, the event store is also the outbox.

Essentials: `EventStore`, `ConfigurableEventStore`, `AggregateType`, `EventOrder`, `GlobalEventOrder`,
`PersistedEvent` ([LLM-postgresql-event-store.md](../LLM/LLM-postgresql-event-store.md)).

### Event processor

A handler that **subscribes to the event store itself** instead of to a bus. It remembers how far it has
read (a *resume point*), so it never misses an event, even across restarts and deployments. A new processor
starts from the first event by default, so it can build from history. Publishing cannot be forgotten,
because the append *is* the publish.

You choose one of three kinds per processor:

| Kind | Use it for |
|---|---|
| `EventProcessor` | Slow or unreliable work: email, a payment gateway, Kafka. Events go through a durable inbox with retries and dead letters. |
| `ViewEventProcessor` | Views that should be quick: handled at once, queued only when handling fails. |
| `InTransactionEventProcessor` | A view that must be fresh at once: it runs inside the append's own transaction. |

Essentials: [LLM-postgresql-event-store.md § EventProcessor Framework](../LLM/LLM-postgresql-event-store.md#eventprocessor-framework).

### Command

A request to change something, named in the **imperative**: `ChangeProductPrice`, `PlaceOrder`. Unlike
an event it is only a wish: the system may refuse it, or find that nothing needs to change. One command
carries one intent (`ChangeProductPrice`, not `UpdateProduct`). A command is handled once and then gone;
the event it produces is kept for good. Commands are sent through a `CommandBus`.

### Decider

A plain function that makes **one decision**. Given a command and the events so far, it returns new
events, none, or a refusal. It never touches the database: Essentials loads the events and appends the
result. Its tests need no mocks and read like the business rule:

```
given  ProductAdded(PRD-7f3a, …, 1056.00)
when   ChangeProductPrice(PRD-7f3a, 1225.00)
then   ProductPriceChanged(PRD-7f3a, 1225.00)
```

Asking twice for the same price returns no event the second time, so repeating the command is harmless. Its partner, the
**evolver**, folds past events into the small state the decision needs.

Essentials has one API per language. Kotlin uses `Decider` and `Evolver`
([LLM-kotlin-eventsourcing.md](../LLM/LLM-kotlin-eventsourcing.md)). Java uses `EventStreamDecider` and
`EventStreamEvolver`, or a separate `Decider` family with typed errors. Pick between them with
[LLM-eventsourced-aggregates.md § Pattern Selection](../LLM/LLM-eventsourced-aggregates.md#pattern-selection).

### Projection

Code that turns events into a table shaped for **one screen or one question**. That table is called a
**view** or **read model**. The projection applies the events in order, one at a time:
`ProductAdded` inserts a row, `ProductPriceChanged` updates its price. Many projections can read the same
events, each building the table its own screen needs. A view is derived: delete it, replay the events, and
it comes back the same. It usually runs after the append, so it can be a moment behind (see
[eventual consistency](#eventual-consistency)).

### Aggregate

One thing decisions are made about, with its own id. It is also a **consistency boundary**: two commands
on the same aggregate cannot both succeed against the same past. In the classic style, an aggregate is a
class rebuilt from all of its events before every command; its methods check the rules and record new
events. That style is familiar, because it looks like an entity loaded from events instead of from a row.
Its cost shows as it grows: every command rebuilds every field, needed or not. Deciders keep the
consistency boundary and drop the big class.

Essentials: `AggregateRoot`, `StatefulAggregateRepository`
([LLM-eventsourced-aggregates.md](../LLM/LLM-eventsourced-aggregates.md)).

### Slice

**One use case, cut from the API down to the events, kept in one directory.** For a command, that is the
command, its decider and its endpoint. Slices never call each other; they only share events. There are four
kinds:

| Kind | Does | Example |
|---|---|---|
| **command** | changes something | `use_cases/place_order/` |
| **view** | shows something | `views/products_for_sale/` |
| **automation** | reacts to an event by issuing a command | `automations/hold_funds_on_order_placed/` |
| **translation** | talks to a system you do not own (an anti-corruption layer) | `external_systems/payment_gateway/` |

Adding a feature adds a directory and edits nothing that already works. The rules for slices are the
plugin's [slice law](#the-plugins-vocabulary).

---

## CQRS

**Command Query Responsibility Segregation** means that the code which changes state and the code which
answers questions are separate, with separate models.

- The **write side** takes commands, enforces the rules and records events: command slices, and the
  automation slices that issue commands.
- The **read side** answers queries from views shaped for them: view slices. They never produce events.

Event sourcing and CQRS fit together because the events are the bridge between the two sides. CQRS does not
require event sourcing, though: in the plugin's service-entity style (below) the write side updates a row,
and the read side queries that same table.

---

## The building blocks underneath

These are the Essentials components the concepts above run on. Each works with PostgreSQL or MongoDB,
using the database you already have.

**All of them coordinate instances of one service over one database**. They are not a messaging system
between services.

### UnitOfWork

A technology-neutral transaction. Everything done inside one unit of work commits or rolls back together:
saving a row, appending events, queueing a message. This is what lets an outbox entry and the change it
belongs to commit together. Essentials: `UnitOfWorkFactory`
([LLM-foundation.md § UnitOfWork](../LLM/LLM-foundation.md#unitofwork-transactions)).

### DurableQueues

Point-to-point queues stored in the database. Delivery is **at least once**, and several instances of
the service can compete for messages. A failed message is redelivered according to a `RedeliveryPolicy`;
when the retries run out it becomes a **dead letter** (DLQ) that can be inspected and resent. Because a
message can arrive twice, handlers must be safe to repeat. Essentials: `DurableQueues`
([LLM-foundation.md § DurableQueues](../LLM/LLM-foundation.md#durablequeues-messaging)).

### Inbox and Outbox

Store-and-forward on top of DurableQueues. The **inbox** takes messages coming in from outside (for
example from Kafka) and processes each one reliably. The **outbox** takes messages going out and sends them
after the transaction that produced them has committed. Essentials: `Inbox`, `Outbox`
([LLM-foundation.md § Inbox/Outbox Patterns](../LLM/LLM-foundation.md#inboxoutbox-patterns)).

### FencedLock

A lock shared by every instance of one service, so that only one of them runs a piece of work at a time,
such as an exclusive subscriber. Each lock carries a **fence token** that only ever increases, so an
instance that lost the lock without noticing cannot overwrite the work of the one that holds it now.
Essentials: `FencedLockManager`
([LLM-foundation.md § FencedLock](../LLM/LLM-foundation.md#fencedlock-distributed-locking)).

### Semantic types

Small wrapper types instead of bare strings and numbers: `OrderId` instead of `String`. The compiler then
refuses `placeOrder(customerId, orderId)`, the code reads like the domain, and validation lives in the
type. Essentials calls them single-value types: `CharSequenceType`, `NumberType` and friends, with
integrations for Jackson, Spring, JPA, JDBI, MongoDB and Avro ([LLM-types.md](../LLM/LLM-types.md)).

### Optimistic concurrency

Essentials does not lock an aggregate while a command runs. Instead, an append states which `EventOrder`
it expects to come next. If another writer got there first, the append fails with
`OptimisticAppendToStreamException`, and the command can be retried against the new state. This scales
better than locks and suits an append-only store.

---

## Designing a system

### Bounded context

A part of the business where every word has **one meaning**. "Product" in the catalogue means a name,
description and images. In pricing it means a price, discounts and margin. In shipping it means weight,
size and fragility. These are three different concepts that happen to share a name. Forcing them into one
`Product` class couples three teams. Each bounded context (BC) gets its own model, its own events and its
own directory. In an Essentials project each BC is one package, `<bc>/`, holding its slices, its
`events/` and its `types/`.

### How big is an aggregate?

The usual worry is "won't there be too many events?". **Aggregate design determines stream size.** A
well-cut aggregate usually collects tens to hundreds of events over its life. If one collects thousands, the
boundary is wrong, and event sourcing is not the problem. The usual cause is modelling a noun ("the
customer") rather than the decisions that must be consistent together. Long-lived streams can also be
closed and carried forward
([LLM-eventsourced-aggregates.md § Closing the Books](../LLM/LLM-eventsourced-aggregates.md#closing-the-books)).

### From Event Modeling to slices

**Event Storming** is a workshop where domain experts and developers put the business's events on a wall,
in time order. **Event Modeling** turns that wall into a timeline with three patterns, and each pattern
becomes one kind of slice:

| On the model | Becomes |
|---|---|
| A screen sends a command, which records an event | a **command slice** |
| Events update a view, which a screen shows | a **view slice** |
| An event triggers a command, with no person involved | an **automation slice** |
| A message crosses to or from a system you do not own | a **translation slice** |

So each vertical stripe of the model becomes one directory in the code. The plugin's
`/essentials:slice-map-html` draws a project back as such a board.

---

## The plugin's vocabulary

The [Claude Code plugin](../README.md#claude-code-plugin) scaffolds, audits and reviews Essentials
projects. Its output uses these terms.

| Term | Meaning | Defined in |
|---|---|---|
| **Slice law** | The rules for slices, R1–R5. **R1**: one decision component per command slice. **R2**: one API file per slice. **R3**: one event variant per file. **R4**: a slice never reaches into another slice's internals. **R5**: use a standard Essentials write style. | [`rules/slice-design.md`](../essentials-plugin/rules/slice-design.md) |
| **Write style (lane)** | How a BC records a change; it picks one and keeps it. **Decider style**, the default: one decider per command slice, state derived from events. **Aggregate style**: one aggregate class per aggregate type, in `<bc>/aggregates/` (Java). **Service-entity style**: a row or document *is* the state, in `<bc>/entities/`, and events are only published on the `EventBus`. Choose it only for a BC that will never need its history, and say so in the BC's `CLAUDE.md`. | `rules/slice-design.md` § R5 |
| **`slice.yaml`** | A small manifest beside each slice: its kind, what it handles, consumes and produces, and its endpoint. The map, the audit and the review read it; nothing in the build does, which is why the plugin can lint it. | [`references/slice/manifest-guide.md`](../essentials-plugin/references/slice/manifest-guide.md) |
| **Stack contract, S1–S11** | What a working Essentials *application* needs around the slices: version pins, which starter, the serialization setup, transactions, the API, security, tests and build. Several of these fail only at startup, which is why `/essentials:init` builds what it generates. | [`references/stack/stack-contract.md`](../essentials-plugin/references/stack/stack-contract.md) |
| **ESS ids** | Stable ids on findings. `ESS-NNN` is a framework trap: something that compiles and then bites at runtime ([`LLM/LLM-traps.md`](../LLM/LLM-traps.md)). `ESS-S<n>` is a stack-contract requirement. `ESS-G<gate>` is a slice-check gate. Ids are never renumbered, so a finding can be looked up later. | the three files above, plus [`slice-check-gates.md`](../essentials-plugin/references/slice/slice-check-gates.md) |

`/essentials:intro` lists every command; `/essentials:init` starts a project and `/essentials:add-slice`
adds a feature to one.

---

## Where to go next

1. **See it:** the animated [ES vs CRUD walkthrough](event-sourcing-vs-crud/).
2. **Try semantic types first:** README [Quick Start](../README.md#quick-start), Path 1. It needs no
   database.
3. **The event-sourced side:** [components/README.md](../components/README.md), then the module docs:
   [LLM-postgresql-event-store.md](../LLM/LLM-postgresql-event-store.md) and
   [LLM-eventsourced-aggregates.md](../LLM/LLM-eventsourced-aggregates.md), or
   [LLM-kotlin-eventsourcing.md](../LLM/LLM-kotlin-eventsourcing.md) for Kotlin.
4. **Before production:** [LLM-traps.md](../LLM/LLM-traps.md), the list of things that compile and then
   fail.
5. **Build a project with Claude Code:** install the plugin ([README](../README.md#claude-code-plugin)),
   then run `/essentials:init`.
6. **Runnable code:** [`examples/`](../examples/), for example
   [essentials-spring-examples](../examples/essentials-spring-examples/README.md).
