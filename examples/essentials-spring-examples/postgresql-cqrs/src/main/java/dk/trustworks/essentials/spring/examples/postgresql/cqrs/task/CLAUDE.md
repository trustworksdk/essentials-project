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

## The repository does not construct aggregates

`Tasks.createNewTask(Task)` takes an already-constructed aggregate and persists it. Constructing a `Task` is
what emits `TaskCreated`, and that decision belongs to `use_cases/create_task`, not to a repository wrapper.

This was wrong until recently — `createTask(taskId, comment)` built the aggregate here, putting the decision
somewhere no command, handler or endpoint could reach. All four repository wrappers in this module now have
the same shape; see `banking/CLAUDE.md` for the same note.

## No `routing/`

The context has no `routing/` directory, and that is the law, not an omission: `routing/` is decider-lane-only
(`rules/slice-design.md` § Aggregate style). On this lane each command handler already holds the `TaskId` and hands
it to `Tasks`, so there is no decider to filter and no command-to-stream resolver to feed. A `TaskCommand` marker
interface lived here once and answered neither question; `slice-check` gate 17 reports one as a decider-style
vestige. `CreateTask` and `AddComment` are routed by type alone, like the commands in `banking` and `shipping`.

## `TaskEvent` is sealed

`permits TaskCreated, CommentAdded`, like the other three hierarchies in this module. §R3 permits a
non-sealed marker, but sealing buys exhaustive `switch` checking and makes adding a variant a compile
error rather than a silent omission. Adding one means appending a name to the `permits` clause — the
single sanctioned cross-slice edit in the law.

## Boundaries

The importable surface is `events/` and `types/`. `aggregates/` is BC-private. Nothing
outside `task` imports from it today.

## Directories beside the slices

- `events/` — `TaskEvent` and its variants, one per file
- `types/` — `TaskId`, `Comment`
- `aggregates/` — `Task` and its repository wrapper `Tasks`
