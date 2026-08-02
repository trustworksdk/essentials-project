# Essentials Admin API — Changelog

All notable changes to the Essentials Admin API contract
(`components/admin-api-spec/openapi/essentials-admin-api.yaml`) are documented here.

The contract follows semantic versioning, with the major aligned to the path prefix
(`/api/essentials/admin/v{major}`). Additive changes are released as minor versions within the same
major; breaking changes are introduced under a new major path served side-by-side.

## v1.0.0 — unreleased

Initial contract, generated code-first from the seven Essentials admin SPI interfaces:

- `DBFencedLockApi` → `fenced-locks`
- `SchedulerApi` → `scheduler`
- `PostgresqlQueryStatisticsApi` → `postgresql-query-statistics`
- `DurableQueuesApi` → `durable-queues`
- `EventStoreApi` → `event-store`
- `CdcApi` → `cdc`
- `PostgresqlEventStoreStatisticsApi` → `event-store-statistics`

27 operations across 25 paths, served under `/api/essentials/admin/v1`. Authorization is role-based
and surfaced per operation via the `x-required-roles` vendor extension; the contract is
transport-agnostic.

Contract conventions settled before release:

- **Server-relative paths.** The `/api/essentials/admin/v1` prefix lives only in `servers[0].url`.
  It was previously repeated in every path key, which made both generated clients prepend it twice.
- **Complete error statuses.** `401`, `403` and `500` on every operation, `400` wherever there is a
  parameter or request body to reject, `404` on the `Optional`-returning operations. `Error.status`
  and `Error.error` are required.
- **No security scheme at all.** The contract states which roles satisfy an operation
  (`x-required-roles`) and nothing about how a caller is authenticated. A `bearerAuth` scheme was
  briefly declared as a client convenience and then removed: the contract should not imply a
  mechanism the adapter does not implement. Authentication is reported by the application's
  `EssentialsAuthenticatedUser`, authorization decided by its `EssentialsSecurityProvider`.
- **Conservative `required`.** Primitive-typed properties plus verified always-present reference
  properties are required; the rest stay optional. Properties that are null by design carry
  `nullable: true` and the reason as their description.
