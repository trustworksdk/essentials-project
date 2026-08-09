# Slice: task.create_task

**Kind:** command   **Status:** live   **Owner:** task-team
**Purpose:** Create a task, optionally carrying an initial comment.

## Invariants
- None enforced here. `new Task(taskId, cmd)` applies `TaskCreated` unconditionally — there is no
  rule to record, so `slice.yaml` carries no `invariants[]` rather than an invented one. The
  comment carried by `CreateTask` is optional; `null` simply means no follow-up command.

## Boundaries
**Reacts to / reads:** `CreateTask`, posted to this slice's endpoint.
**Publishes:** `TaskCreated` — consumed by the `task.comment_on_task_created` automation.
**Forbidden:**
  - Never import another slice's internals — only `task/events/` and `task/types/`.
  - Never add this slice's logic to a shared decider/controller/event file.

## Data
**Owns (writes):** `Task` aggregate (created here), via `Tasks` / `StatefulAggregateRepository`.
**Reads:** nothing — the aggregate does not exist yet.

## Notes
This BC is on the **aggregate style** (`rules/slice-design.md` §R5): the decision lives on
`task/aggregates/Task.java`, and this slice owns the command, the API file, and the thin handler.
`CreateTaskAPI` uses `commandBus.send` (not `sendAndDontWait`) so the caller learns synchronously
that the task was accepted — there is no view slice in this BC to poll.

## Files
- `CreateTask.java` — the command; also the HTTP request body (§R2, no DTO)
- `CreateTaskHandler.java` — `@CmdHandler`, loads/creates via `Tasks`, runs in the bus transaction
- `CreateTaskAPI.java` — `POST /tasks/create`, the slice's single endpoint

## Tests
- `src/test/java/.../cqrs/task/TaskProcessorIT.java#create_task_and_comment_in_same_unit_of_work`
  sends `CreateTask` on the command bus and asserts `TaskCreated` is persisted for the task id.
  No unit tests exist in this module.
