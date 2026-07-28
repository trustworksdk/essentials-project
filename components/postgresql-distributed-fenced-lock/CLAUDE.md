# PostgreSQL Distributed Fenced Lock

PostgreSQL-backed distributed fencing locks for intra-service node coordination. Maven: `postgresql-distributed-fenced-lock`.

## Package Structure

- `...distributed.fencedlock.postgresql` — main classes: manager, storage, builder
- `...distributed.fencedlock.postgresql.jdbi` — JDBI type adapters for `LockName`

## Key Classes

| Class | Internal Role |
|-------|---------------|
| `PostgresqlFencedLockManager` | Thin final subclass of `DBFencedLockManager`; wires `PostgresqlFencedLockStorage` + UoW factory; all logic lives in parent |
| `PostgresqlFencedLockStorage` | Implements `FencedLockStorage`; owns all SQL; creates/manages `fenced_locks` table on init |
| `PostgresqlFencedLockManagerBuilder` | Fluent builder; `build()` or `buildAndStart()` |
| `LockNameArgumentFactory` | JDBI argument factory for `LockName` (extends `CharSequenceTypeArgumentFactory`) |
| `LockNameColumnMapper` | JDBI column mapper for `LockName` |

`DBFencedLock` and `DBFencedLockManager` live in `foundation` module — that's where heartbeat loop, timeout logic, and lock state machine reside.

## Test Structure

Three test files, all require Docker (Testcontainers `postgres:latest`):

- `PostgresqlFencedLockManagerIT` — extends abstract `DBFencedLockManagerIT` from foundation-test; covers single/multi-node acquire, timeout, re-acquire; disrupts DB via Docker pause/unpause
- `PostgresqlFencedLockManager_MultiNode_ReleaseLockIT` — extends `DBFencedLockManager_MultiNode_ReleaseLockIT`; focuses on explicit release handoff between nodes
- `PostgresqlFencedLockStorageTest` — unit test, no Docker; validates table name sanitization rejects SQL keywords and injection strings

Shared IT logic is in `components/foundation/src/test/.../fencedlock/DBFencedLockManagerIT` — don't duplicate there.

## Extension Points

- `FencedLockStorage<HandleAwareUnitOfWork, DBFencedLock>` — implement to swap persistence backend
- `DBFencedLockManager` (foundation) — subclass to provide alternate storage wiring; `PostgresqlFencedLockManager` is a minimal subclass example

## Gotchas

- `lockConfirmationInterval` MUST be less than `lockTimeOut` — no runtime guard; silent misbehavior if violated
- `fencedLocksTableName` is string-concatenated into SQL — `PostgresqlUtil.checkIsValidTableOrColumnName()` is first-line defense only; never derive from user input
- `PostgresqlUtil.acquireBootstrapLock()` serializes table creation across nodes — required, do not remove
- `INSERT ... ON CONFLICT DO NOTHING` used for initial lock row — means first writer wins; `updateLockInDB` uses optimistic CAS on `(last_issued_fence_token, lock_last_confirmed_ts)` to prevent split-brain
- Release sets `locked_by_lockmanager_instance_id = NULL` (not delete) — row persists; token monotonically increases across owner changes; FIRST_TOKEN = 1, UNINITIALIZED = -1
- `releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation = false` (default) — on DB IO failure, locks stay locally held; set `true` only if fail-fast is preferred over retain-until-timeout
- `lockManagerInstanceId` defaults to machine hostname — collisions possible in containers; always set explicitly in k8s/container deployments
- `PostgresPlugin` must be installed on the `Jdbi` instance before use (see IT setup)
- Scope is intra-service only — all nodes must share same PostgreSQL database
