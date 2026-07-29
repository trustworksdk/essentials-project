## SpringData MongoDB Distributed Fenced Lock

MongoDB-backed distributed fenced lock using Spring Data MongoDB. Maven: `springdata-mongo-distributed-fenced-lock`.

## Package Structure

Single package: `dk.trustworks.essentials.components.distributed.fencedlock.springdata.mongo`
- All 3 main classes live here; no sub-packages
- All logic delegates up to `foundation` module base classes

## Key Classes

| Class | Role |
|---|---|
| `MongoFencedLockStorage` | Implements `FencedLockStorage` — all MongoDB CRUD ops (insert/update/confirm/release/lookup/delete). Owns collection init + index setup. Contains private `MongoFencedLock` document model. |
| `MongoFencedLockManager` | `final`, extends `DBFencedLockManager`. Thin wrapper: constructs `MongoFencedLockStorage` and passes to super. No own logic. |
| `MongoFencedLockManagerBuilder` | Fluent builder. `build()` or `buildAndStart()` (calls `start()` immediately). |

## Test Structure

- `MongoFencedLockManagerIT` — extends `DBFencedLockManagerIT` (shared test suite in `foundation`). Uses `@Testcontainers` + `MongoDBContainer("mongo:latest")` replica set URL. Overrides `disruptDatabaseConnection()` / `restoreDatabaseConnection()` via Docker pause/unpause. Adds `verify_indexes` test.
- `MongoFencedLockManager_MultiNode_ReleaseLockIT` — extends `DBFencedLockManager_MultiNode_ReleaseLockIT`. Same Testcontainers setup; two named node instances share same `MongoTemplate`.
- `MongoFencedLockStorageTest` — pure unit test (mocked `MongoTemplate`). Validates collection name rejection only.
- `ApplicationTests` — minimal Spring context smoke test.

All ITs require Docker (Testcontainers spins MongoDB replica set — replica set required for multi-doc transactions).

## Extension Points

`FencedLockStorage<ClientSessionAwareUnitOfWork, DBFencedLock>` — implemented by `MongoFencedLockStorage`. Implement this interface to swap out storage (e.g. different DB). All lock lifecycle methods must be idempotent and handle write conflicts.

`EventBus` (optional) — injected into manager; publishes `FencedLockEvents` on acquire/release.

## Gotchas

- `initializeLockStorage()` is a no-op intentionally — MongoDB does not support `listCollections` inside a multi-document transaction; collection + index creation happens in constructor instead.
- Two compound indexes created at startup: `find_lock` (`name` + `lastIssuedFencedToken`) and `confirm_lock` (`name` + `lastIssuedFencedToken` + `lockedByLockManagerInstanceId`). Missing these → silent full-collection scans under contention.
- `updateLockInDB` and `confirmLockInDB` use optimistic CAS: query matches on `lastIssuedFencedToken` (and `lockedByLockManagerInstanceId` for confirm). `modifiedCount == 0` means another node won the race → `false` returned, caller retries.
- `releaseLockInDB` unsets `lockedByLockManagerInstanceId` (field removal, not null set) — lock remains in collection with incremented token, unowned.
- Token starts at `1` (`FIRST_TOKEN`); uninitialized state uses `-1` (`UNINITIALIZED_LOCK_TOKEN`). Never resets — monotonically increasing across all acquires.
- `lockManagerInstanceId` defaults to machine hostname if `Optional.empty()` — in containerized envs, ensure unique pod identity is set explicitly.
- `releaseAcquiredLocksInCaseOfIOExceptionsDuringLockConfirmation` defaults to `false` in builder — locks survive transient IO failures; set `true` only if split-brain safety is preferred over availability.
- `fencedLocksCollectionName` passed directly to MongoDB — validated via `MongoUtil.checkIsValidCollectionName` but that is not exhaustive; never accept from untrusted input.
- `MongoFencedLock` (private static inner class) stores timestamps as `Instant`; `DBFencedLock` (foundation type) uses `OffsetDateTime` — conversion always assumes UTC on read-back.
