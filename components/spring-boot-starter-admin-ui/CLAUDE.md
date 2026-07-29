# spring-boot-starter-admin-ui

Spring Boot auto-configuration glue that wires the `vaadin-ui` module into a Spring Boot app. Maven: `spring-boot-starter-admin-ui`.

## Package Structure

- `dk.trustworks.essentials.components.boot.autoconfigure.admin.ui` — single auto-config class; this module's only production Java package
- `dk.trustworks.essentials.ui.admin` *(in `vaadin-ui` module)* — all Vaadin view classes (admin, locks, queues, subscriptions, eventprocessors, scheduler, postgresql stats)
- `dk.trustworks.essentials.ui.view` *(in `vaadin-ui` module)* — shared layout/chrome: `AdminMainLayout`, `AdminLoginView`, `AccessDeniedView`
- `dk.trustworks.essentials.ui.util` *(in `vaadin-ui` module)* — `SecurityUtils` (role checks)
- `dk.trustworks.essentials.ui.component` *(in `vaadin-ui` module)* — reusable `ViewToolbar`

## Key Classes

| Class | Role |
|---|---|
| `EssentialsAdminUIAutoConfiguration` | `@AutoConfiguration`; applies `@EnableVaadin` + `@ComponentScan` over `dk.trustworks.essentials.ui`; no beans declared directly |
| `AdminMainLayout` | `AppLayout` shell with side-nav drawer and user-avatar menu; nav items gated by `SecurityUtils` role checks |
| `SecurityUtils` | Wraps `EssentialsAuthenticatedUser`; translates role predicates (`hasAdminRole`, `hasLockReaderRole`, …) to UI access decisions |
| `AdminView` | Root route (`""`); welcome landing page |
| `SubscriptionsView` | Route `subscriptions`; grids EventStore subscriptions via `EventStoreApi` |
| `LocksView` | Route `locks`; manages fenced locks via `DBFencedLockApi`; supports release action |
| `QueuesView` | Route `queues`; queue admin |
| `EventProcessorsView` | Route `eventprocessors`; stub, "coming soon" |
| `SchedulerView` | Route `scheduler`; scheduled-job admin |
| `PostgresqlStatisticsView` | Route `postgresql`; DB stats display |
| `AccessDeniedView` | Redirect target when `BeforeEnterObserver` denies access |

## Test Structure

Single IT: `StarterAutoConfigurationIT`.
- Uses `ApplicationContextRunner` (no full Spring Boot app startup, no Vaadin servlet)
- Testcontainers spins up `postgres:latest`; datasource wired via `@DynamicPropertySource` **and** `withInitializer` (both needed — see test comment)
- Asserts all view beans (`AdminView`, `EventProcessorsView`, `LocksView`, `PostgresqlStatisticsView`, `QueuesView`, `SchedulerView`, `SubscriptionsView`) are present in context
- Requires Docker

## Extension Points

- `EssentialsAuthenticatedUser` — primary SPI; implement to integrate custom identity/auth providers; all view-level access control delegates here
- `SecurityUtils` — not an SPI but trivially replaceable if role semantics change; wraps `EssentialsAuthenticatedUser` checks
- New Vaadin views auto-discovered via `@ComponentScan("dk.trustworks.essentials.ui")` — add `@SpringComponent @UIScope @Route` view in that package and it wires automatically

## Gotchas

- This module contains **no Vaadin views** itself; all views live in `vaadin-ui`. Adding views here won't help — they must be in `dk.trustworks.essentials.ui.*`.
- `vaadin-spring-boot-starter` is `provided` scope — consumer app must supply it; Tomcat embeds are similarly `provided` to avoid version conflicts.
- `EventProcessorsView` is a placeholder stub; nav item exists but view body says "Coming soon".
- `BeforeEnterObserver.beforeEnter` guards each view independently — access is checked at navigation time, not at startup.
- `@DynamicPropertySource` alone is insufficient in `ApplicationContextRunner` tests; `withInitializer` applying the same properties is also required (see IT comment).
- `eventsourced-aggregates` is optional dep — conditional features absent if not on classpath.
