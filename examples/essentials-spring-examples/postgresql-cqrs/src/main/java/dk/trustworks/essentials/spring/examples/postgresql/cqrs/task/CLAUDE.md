# Bounded context: task

A task with comments. This context exists to demonstrate one specific thing: an
`InTransactionEventProcessor` reacting to an event and issuing a follow-up command **inside the same unit of
work** that appended it.

**Write style: aggregate (`rules/slice-design.md` §R5).** `Task` is an `AggregateRoot` reached through
`Tasks`, a `StatefulAggregateRepository` wrapper. Sanctioned lane — do not convert it to a `Decider`.

## Slices

| Slice | Kind | Role |
|---|---|---|
| `use_cases/create_task` | command | Creates a `Task`, optionally carrying an initial comment |
| `use_cases/add_comment` | command | Adds a comment to an existing `Task` |
| `automations/comment_on_task_created` | automation | Turns a task's initial comment into an explicit `AddComment` |

## The point of this context

`CreateTask` may carry a comment. Rather than the aggregate quietly emitting two events, the automation
observes `TaskCreated` and issues `AddComment`, so the comment lands as its own event with its own command
behind it. Because the processor is an `InTransactionEventProcessor`, both events commit together —
`TaskProcessorIT` asserts exactly that.

Read `create_task` → `comment_on_task_created` → `add_comment` in that order; the flow only makes sense as a
chain.

## `routing/TaskCommand`

`TaskCommand` is the BC-private marker interface every command in this context implements — the law's
`routing/` concept. It is deliberately **not** sealed: that is what lets a new command slice add a file
without editing an existing one. It is BC-private; a foreign context implementing it would route its command
into this aggregate.

Note that `banking` and `shipping` have no equivalent — this context is the only one here that demonstrates
the pattern.

## `TaskEvent` is not sealed

Unlike `AccountEvent`, `IntraBankMoneyTransferEvent` and `ShippingEvent`, `TaskEvent` is a plain interface.
The non-sealed marker is permitted by §R3, but it forfeits exhaustive `switch` checking and it makes this
context the odd one out. Either seal it or record why not — right now it reads as drift rather than as a
decision.

## Boundaries

The importable surface is `events/` and `types/`. `routing/` and `aggregates/` are BC-private. Nothing
outside `task` imports from it today.

## Directories beside the slices

- `events/` — `TaskEvent` and its variants, one per file
- `types/` — `TaskId`, `Comment`
- `routing/` — `TaskCommand`
- `aggregates/` — `Task` and its repository wrapper `Tasks`
