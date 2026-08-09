# Slice: task.add_comment

**Kind:** command   **Status:** live   **Owner:** task-team
**Purpose:** Add a comment to an existing task.

## Invariants
- **INV-AC-1** — a comment (`taskId` + `content` + `createdAt`) is applied at most once per task
  (enforced by `Task.addComment`, which only calls `apply(new CommentAdded(...))` when the
  equivalent `Comment` is not already in the aggregate's comment set). This dedup is what makes
  the automation path below safe to re-run.

  Note what that key does *not* cover: because `createdAt` is part of it, dedup protects against
  redelivery of the *same* command, but a client retrying with a freshly stamped `createdAt` produces
  a comment the aggregate considers new. Conversely two genuinely distinct comments with identical
  text at the same instant collapse into one. Fine for an example; size it up before copying.

## Boundaries
**Reacts to / reads:** `AddComment` — **two triggers reach this one slice**: `POST /tasks/add-comment`
  (`AddCommentAPI`), and the `task.comment_on_task_created` automation, which issues the same command
  when a task is created carrying an initial comment. Two triggers, one command, one handler — still
  a single slice (§R1/§R2).
**Publishes:** `CommentAdded`.
**Forbidden:**
  - Never import another slice's internals — only `task/events/` and `task/types/`.
  - Never add this slice's logic to a shared decider/controller/event file.

## Data
**Owns (writes):** `Task` aggregate.
**Reads:** `Task`, loaded through `Tasks.findTask` (`orElseThrow` if absent).

## Notes
`createdAt` is **supplied by the caller**, not stamped by the server. That is deliberate rather
than an oversight: the automation must pass the originating `TaskCreated` timestamp through, and
INV-AC-1's dedup check keys on it — a server clock would make every replay a new comment. Worth
knowing before copying this command into a design where the server should own the clock.

## Files
- `AddComment.java` — the command; also the HTTP request body (§R2, no DTO)
- `AddCommentHandler.java` — `@CmdHandler`, loads the aggregate and calls `Task.addComment`
- `AddCommentAPI.java` — `POST /tasks/add-comment`, the slice's single endpoint

## Tests
- `src/test/java/.../cqrs/task/TaskProcessorIT.java#create_task_and_comment_in_same_unit_of_work`
  exercises this slice **through the automation trigger** — it asserts `CommentAdded` is persisted
  and that the reloaded task holds exactly one comment. The HTTP trigger is not covered, and there
  are no unit tests in this module.
