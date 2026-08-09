# postgresql-cqrs

Demo Spring Boot app for the PostgreSQL EventStore: CQRS + event sourcing across three bounded
contexts. Not part of the release. Sibling examples (`postgresql-inbox-outbox`,
`mongodb-inbox-outbox`) are **not** structured this way — the slice law below applies to this module
only.

```bash
mvn verify -pl :postgresql-cqrs                 # unit + ITs (needs Docker)
mvn -Pjackson2 verify -pl :postgresql-cqrs -am  # the other Jackson flavour; -am is required
mvn spring-boot:run -pl :postgresql-cqrs        # after `docker compose up -d`
```

The `-am` is not optional on the non-default flavour: without it a sibling resolves from the local
repo with the property unresolved and **both** Jackson flavours land on the classpath. See the root
`CLAUDE.md`.

## Bounded contexts

| BC | Slices | Aggregates |
|---|---|---|
| `banking` | `open_account`, `request_intra_bank_money_transfer`, `transfer_money` (automation), `account_balance` (view) | `Account`, `IntraBankMoneyTransfer` |
| `shipping` | `register_shipping_order`, `ship_order`, `order_management` (translation), `order_status` (view) | `ShippingOrder` |
| `task` | `create_task`, `add_comment`, `comment_on_task_created` (automation) | `Task` |

Start from a context's own `CLAUDE.md`; each slice directory has one too, plus a `slice.yaml`.

**All three use the aggregate write style** (`AggregateRoot` + `StatefulAggregateRepository`), which
is a sanctioned lane in the law — §R5. Do **not** "modernise" them into `Decider`s.

## Three things not to undo

Each of these was wrong once and is easy to reintroduce because the wrong version looks tidier.

- **Repositories do not construct aggregates.** `Accounts.openNewAccount(Account)` persists an
  aggregate the `open_account` slice built. An `openNewAccount(accountId, accountNumber)` overload
  would put the decision back on the repository, out of reach of any command or endpoint.
- **The Kafka DTOs carry a plain `String` id, not `OrderId`.** Converting happens in the two adapters
  of `external_systems/order_management`. Typing the DTOs with `OrderId` means the ACL stops
  translating and an upstream format change reaches the domain.
- **All four event hierarchies are `sealed`.** Adding a variant means appending to `permits` — the one
  sanctioned cross-slice edit in the law. Dropping `sealed` to avoid that forfeits exhaustive
  `switch` checking.

## Commands do not cross into `events/` or `aggregates/`

Deliberate, and easy to undo by accident. Neither an event nor an aggregate references a command
type: aggregate constructors and methods take the fields they need, and the emitting slice does the
unpacking. So `new ShippingOrder(cmd.orderId(), cmd.destinationAddress())`, never
`new ShippingOrder(cmd)`.

The reason is §R4. `events/` and `types/` are the only packages a foreign bounded context may import,
so a command reference inside `events/` drags a slice's internals into every consumer of that event.
`aggregates/` is BC-private and so a lesser problem, but it is the same coupling: a convenience
constructor taking `RegisterShippingOrder` ties the consistency boundary to one slice's wire
contract.

Passing the command *into* a slice's own handler is fine — that is the slice's own type. Issuing
another slice's command over the command bus is also fine, and is the law's sanctioned way for an
automation or translation to collaborate.

<!-- essentials-slices-rules: v1 — from the essentials plugin; edit the plugin, not this section -->

## Essentials slice rules

Package by **vertical slice**, never by technical layer. This is a pointer, not the law: the full law
is the essentials plugin's `rules/slice-design.md`, reachable via `/essentials:add-slice`,
`/essentials:slice-check`, or by asking about Essentials slices.

```
<bc>/ use_cases/<slice>/  command — enforces invariants, emits events. One endpoint.
      views/<slice>/      view — projects events, answers queries over its own model.
      automations/<slice>/       automation — reacts to events, issues commands. No API.
      external_systems/<sys>/    translation — anti-corruption boundary. No API.
      use_cases/_shared/  shared State+Evolver only — 3+ deciders, else keep it per-slice
      aggregates/         ONLY if this BC decides via an aggregate (R5) — never both styles
      events/ types/      the BC's public surface     routing/ config/  routing + wiring
```

Underscores, not hyphens — hyphens are illegal in JVM package names. No `controllers/`, `services/`,
or `repositories/` directories.

**The boundary rule.** A slice may import only another slice's or BC's `events/` and `types/` — never
its decider, evolver, state, handler, or endpoint. Never reconstruct another slice's state and call
its decider: collaborate by publishing an event or issuing that slice's command.

One decision component per command slice (a decider, or the BC's aggregate — R5), one endpoint. One
API file per slice — a view may expose several queries over its OWN read model, and needing one more
event extends that slice rather than forking it. One event variant per file. Wiring is done.

Run `/essentials:slice-check` to audit.
