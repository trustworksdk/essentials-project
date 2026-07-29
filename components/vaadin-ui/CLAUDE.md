## vaadin-ui

Vaadin Flow admin UI for Essentials infrastructure — fenced locks, durable queues, event-store subscriptions, PostgreSQL stats, scheduler jobs. Maven: `vaadin-ui`.

## Package Structure

| Package | Contents |
|---|---|
| `dk.trustworks.essentials.ui.admin` | All admin views (one class per route) |
| `.ui.view` | Layout, login, access-denied scaffold views |
| `.ui.component` | `ViewToolbar` reusable header component |
| `.ui.util` | `SecurityUtils` — thin role-check wrapper |

## Key Classes

| Class | Internal Role |
|---|---|
| `AdminMainLayout` | `AppLayout` shell; builds side-nav conditionally based on roles; all `@Route` views use it as `layout=` |
| `AdminLoginView` | `@AnonymousAllowed` login form; POSTs to Spring Security `/login` |
| `AccessDeniedView` | Redirect target from `BeforeEnterObserver`; sets HTTP 403 via `VaadinService.getCurrentResponse()` |
| `SecurityUtils` | Wraps `EssentialsAuthenticatedUser` role checks into domain-level methods (`canAccessLocks()`, `canWriteQueues()`, etc.) |
| `LocksView` | Grid over `DBFencedLockApi`; write role exposes Release button per row |
| `QueuesView` | Queue selector → lazy-loaded queued + dead-letter grids via `ConfigurableFilterDataProvider`; filter = `QueueEntryId` only |
| `SubscriptionsView` | Grid over `EventStoreApi.findAllSubscriptions`; on-demand "Load Highest" button per row (intentionally expensive — warns in UI) |
| `EventProcessorsView` | Stub — placeholder "Coming soon", access-controlled |
| `PostgresqlStatisticsView` | Four grids: table sizes, activity, cache hit ratio, slow queries; pulled from two API beans |
| `SchedulerView` | PgCron jobs + ScheduledExecutor jobs; run-details filtered by job ID |
| `ViewToolbar` | `Composite<Header>` — drawer toggle + title + optional action group; use `ViewToolbar.group(...)` for action grouping |

## Test Structure

- Uses **Karibu Testing** (`MockVaadin`) — no browser, no Playwright, no Docker
- Tests DO require **Testcontainers PostgreSQL** (real DB needed for Spring Boot context with Essentials auto-config)
- `KaribuTestBase` — abstract base: spins up Testcontainers Postgres, configures `MockVaadin`, provides `loginProgrammatically(username, roles...)` helper
- `VaadinAdminUiTest` — single smoke test: logs in as admin, navigates to every view, asserts view renders
- Test infra: `UiTestApplication` (`@SpringBootApplication`), `UiTestSecurityConfig`, `TestAuthenticatedUser` (implements `EssentialsAuthenticatedUser` via `AuthenticationContext`), `TestSecurityProvider` (implements `EssentialsSecurityProvider`)
- `task/` sub-package = full example domain (Task aggregate, commands, events, views) wired for integration test context

## Extension Points

| SPI | Purpose |
|---|---|
| `EssentialsAuthenticatedUser` (`shared`) | Provide authenticated principal + role checks to all views; inject as Spring bean |
| `EssentialsSecurityProvider` (`shared`) | Back the `isAllowed()` gate used by underlying API beans |

## Gotchas

- All views use `@PermitAll` + `BeforeEnterObserver` for access control — NOT Vaadin's built-in `@RolesAllowed`. Role enforcement is imperative inside `beforeEnter()` via `SecurityUtils`, forwarding to `AccessDeniedView`.
- `SecurityUtils` is constructed inline (`new SecurityUtils(authenticatedUser)`) inside each view, not a Spring bean — keep it stateless.
- `QueuesView` queued-message search only matches on exact `QueueEntryId`; free-text search is NOT implemented (comment says "Only QueueEntryId supported for now").
- `ConfigurableFilterDataProvider` instances are recreated on every queue selection in `QueuesView` — old listeners from `addValueChangeListener` accumulate. If adding new filter fields, clear old listeners or recreate the field.
- `AdminMainLayout` nav items are role-gated at render time only — a user with a bookmarked URL can still navigate; `BeforeEnterObserver` is the actual guard.
- `EventProcessorsView` is a stub — stub out or remove before shipping if not implementing.
- Testcontainers image is `postgres:latest` — pin to a specific version if flaky test behaviour appears across environments.
- `SubscriptionsView` warning: "calling Load highest global order frequently can effect EventStore performance" — leave that Span warning in place.
