# Slice: task.comment_on_task_created

**Kind:** automation   **Status:** live   **Owner:** task-team
**Purpose:** Turn the initial comment carried by a newly created task into an explicit `AddComment`
command, so it lands as its own event.

## Invariants
- **INV-AC-1** — a comment (`taskId` + `content` + `createdAt`) is applied at most once per task
  (enforced by `Task.addComment`). This slice does not enforce it and must not: it relies on it,
  which is why re-delivery of `TaskCreated` cannot duplicate the comment. The same id is recorded
  in `task.add_comment`'s manifest so the shared dependency is on the record.

## Boundaries
**Reacts to / reads:** `TaskCreated`, for `Tasks.AGGREGATE_TYPE` only. The handler is a no-op when
`event.comment()` is `null`.
**Publishes:** nothing. It **dispatches** `AddComment` on the command bus — an automation never
appends events itself and has **no external API** (§ The four slice kinds).
**Forbidden:**
  - Never import another slice's internals — only `task/events/` and `task/types/`.
  - Never add this slice's logic to a shared decider/controller/event file.

## Data
**Owns (writes):** nothing. **Reads:** nothing — `TaskCreated` carries everything the command needs.

## Why `InTransactionEventProcessor`
This is the whole point of the slice. Extending `InTransactionEventProcessor` rather than the plain
`EventProcessor` means the `AddComment` command is handled inside the **same unit of work** that
appended `TaskCreated` — synchronous and strongly consistent, not eventually consistent via the
durable queue. `TaskProcessorIT` asserts exactly that: both event batches are collected at
`CommitStage.BeforeCommit` of one transaction. Choose this shape only when the follow-up genuinely
must commit atomically with its trigger; it puts the reaction on the writer's latency path.

## Files
- `CommentOnTaskCreatedProcessor.java` — `@MessageHandler void handle(TaskCreated)`; `@Service`, so
  Spring component scanning is the wiring (this BC has no `config/` directory)

## Tests
- `src/test/java/.../cqrs/task/TaskProcessorIT.java#create_task_and_comment_in_same_unit_of_work`
  waits for `isActive()`, sends `CreateTask`, and asserts the derived `CommentAdded`. No unit tests
  exist in this module.
