# Orders bounded context — aggregate lane

Write style: **one aggregate per bounded context** (`rules/slice-design.md` §R5, aggregate style).
The full law lives in the plugin and is read live — this file records only what is true of *this* BC.

## Why this lane

TODO — **state the reason, do not delete this heading.** Decider style is the default; this lane is a
deliberate departure and §R5 asks for its reason on the record. There are two good ones: the code is
already built this way, or a genuinely complex invariant reads better as one object's methods than as
N independent folds. "It is what we know" is not one of them.

Note what this lane costs, so the reason can be weighed against it: every command slice in the BC
shares one class, so § The aggregate's own bar applies — every public method enforces an invariant,
no query surface, and the method count must not simply track the slice count. Failing the first or
third across most methods is the signal that this BC wants decider style after all.

**In Java only.** `AggregateRoot` / `StatefulAggregateRepository` are a Java-native family; a Kotlin
BC on this lane is using them through interop and gives up the Kotlin decider API.

## Layout

```
orders/
  aggregates/Order.java     the consistency boundary. Invariants live HERE
  aggregates/Orders.java    the repository wrapper + the AggregateType constant
  events/                           the BC's public event surface (sealed parent + one file per variant)
  types/                            BC-internal value objects (OrderId, …)
  use_cases/<slice>/                one command slice each: command, handler, endpoint
  views/<slice>/                    read models over this BC's events
  config/                           BC-scoped wiring (usually near-empty on this lane)
```

No `routing/` — there is no command-type-to-stream mapping to declare, because each handler loads
the aggregate by id itself. No `use_cases/_shared/` — there are no evolvers to promote.

## The rule that makes this lane work

**Decisions belong on `Order`, not in a slice handler.** A handler is four lines: load, call
one method, save, done. The moment a handler contains an `if` about domain state, the aggregate has
been reduced to a data holder and the consistency boundary has leaked — that is the god-service this
lane is meant to prevent.

**State is written only by `@EventHandler` methods.** A command method validates and calls
`apply(event)`. Assigning a field directly survives in memory and vanishes on the next reload.

**`Order` must never name a command type** — `aggregates/` is checked for command-type
leakage exactly as `events/` is (§R4).

## Adding a slice

Run `/essentials:add-slice`. It emits the command, handler, endpoint, manifest and test for this
lane, and appends the new event variant to the sealed parent's `permits` clause — the one sanctioned
cross-slice edit in the law.

## Audit

`/essentials:slice-check` — gate 14 detects this lane from `aggregates/` and applies the
aggregate-lane gates. A BC holding **any two** of per-slice deciders, `aggregates/` and `entities/`
is Blocking: two write designs over one consistency boundary.
