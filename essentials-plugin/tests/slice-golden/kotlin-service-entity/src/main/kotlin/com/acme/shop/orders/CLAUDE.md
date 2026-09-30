# Bounded context: orders

**Write style:** service-entity (`rules/slice-design.md` §R5) — the decision lives on a state-stored
`Order`, mutated in place. **This bounded context has no event store.**
**Entity:** Order
**Owner:** orders-team
**Purpose:** TODO one sentence — what this context is responsible for.

## Why this lane

TODO — **state the reason, do not delete this heading.** Service-entity style is chosen because this
context has *no need to reconstruct state from history*: no audit trail derived from events, no
temporal queries, no replay-driven projections. That is a forward-looking design decision, and a BC
that ends up here because nobody asked the question is the one that discovers a year later it needed
the history it never kept.

If that stops being true, the migration is a real project — `/essentials:slice-check` reports moving
between write styles and refuses to automate it.

## Layout

```
orders/
  use_cases/<slice>/       command slices — one @CmdHandler, one endpoint each
  views/<slice>/           view slices — a read-only query interface + a read shape. No projectors
  automations/<slice>/     automation slices — no API
  external_systems/<sys>/  translation slices — anti-corruption layers
  entities/                Order + OrderRepository — the consistency boundary. Write by
                           hand; see entities/CLAUDE.md
  events/                  OrderEvent sealed parent + one file per variant, published on
                           the EventBus as integration facts (§R3 applies unchanged)
  types/                   OrderId and this BC's other semantic types
  config/                  OrdersConfiguration — usually near-empty; handlers auto-register
```

**Absent by construction, and that is a rule rather than an omission:**

- **No `routing/`.** Its jobs — aggregate-type membership and the command-to-aggregate-id resolver —
  exist only to pick an event stream. The command bus routes by command *type*.
- **No `use_cases/_shared/`.** There are no evolvers, so there is nothing to promote and the
  promotion bar has nothing to count. The entity *is* the shared state and it lives in `entities/`.
  A `_shared/` here is a service class in disguise.
- **No `aggregates/`.** A BC has `entities/` or `aggregates/` or neither — never two. Two write
  designs over one consistency boundary is Blocking.

## The public surface

`events/` and `types/`. Everything else — `entities/`, `use_cases/`, `views/`, `config/` — is
BC-private. Nothing in `events/` or `entities/` may import a command type from `use_cases/<slice>/`
(§R4): those artefacts take the fields they need, and the emitting slice does the unpacking.

## Wiring

Nothing to write per slice. `ReactiveHandlersBeanPostProcessor` auto-registers `CommandHandler` beans
with the single `CommandBus` bean, and Spring Data repositories are registered by scanning. The
obligation is a check: confirm handlers are beans in a scanned package, and that
`reactive-bean-post-processor-enabled` (default `true`) is not switched off — disabling it silently
unwires every handler in the application.

## Migrations

The **write** table needs a migration on a relational database — that is the write model. There is no
read-model migration, because there is no separate read model: views query this table directly, which
is also why their reads are strongly consistent.
