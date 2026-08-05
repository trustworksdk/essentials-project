## spring-boot-starter-admin-api

HTTP adapter serving the `admin-api-spec` contract over Spring WebMVC. Maven: `spring-boot-starter-admin-api`.
Delegates to the 11 `*Api` SPI beans wired by `spring-boot-starter-postgresql` / `-postgresql-event-store`
/ `eventsourced-aggregates`.
Replaces `vaadin-ui` + `spring-boot-starter-admin-ui` as the admin surface.

Consumer-facing docs: `docs/openapi/README.md`.

## Package Structure

| Package | Contents |
|---|---|
| `dk.trustworks.essentials.components.adminapi.rest` | 11 controllers, principal resolver, exception handler, Jackson module, paths |
| `.rest.dto` | Contract wrapper shapes — `CountResult`, `ReleaseResult`, `DeleteResult`, `PurgeResult`, `QueueNameResult`, `GlobalEventOrderResult`, `ApiError`, `ResurrectDeadLetterMessageRequest` |
| `dk.trustworks.essentials.components.boot.autoconfigure.admin.api` | `@AutoConfiguration` + `@ConfigurationProperties` |

## Key Classes

| Class | Internal Role |
|---|---|
| `AdminApiPaths` | Base-path constants; `BASE_PATH_PLACEHOLDER` is the `@RequestMapping` value every controller uses |
| `AdminApiPrincipalResolver` | Only security touch point — `EssentialsAuthenticatedUser` → principal, throws `AdminApiUnauthenticatedException` when unauthenticated |
| `AdminApiExceptionHandler` | `@RestControllerAdvice` scoped by `basePackageClasses` → contract statuses + `Error` body |
| `AdminApiJacksonModule` | Jackson **3** serializers: `CharSequenceType` → string, `NumberType` → number |
| `EssentialsAdminApiAutoConfiguration` | Declares every controller as a bean; all `@ConditionalOnMissingBean` **and** `@ConditionalOnBean` of their own SPI, so a subsystem the application does not run costs its endpoints rather than the whole context. The 4 aggregate controllers additionally sit in a nested `@ConditionalOnClass` config. `essentialsAdminApiSurfaceSummary` logs served-vs-skipped areas at startup |
| `<Tag>Controller` ×11 | One per contract tag; each method = one contract operation |

## Test Structure

All plain unit tests — **no Docker, no database**. SPIs are Mockito mocks.

| Test | Gates |
|---|---|
| `AdminApiContractConformanceTest` | {verb, path} served == {verb, path} declared, **both directions**; reads contract from classpath (`/openapi/essentials-admin-api.yaml`, test dep on `admin-api-spec`). Has an explicit 27-operation count assertion so it cannot pass vacuously |
| `AdminApiSerializationTest` | Value types render as primitives; `rest.dto` field names match contract schemas |
| `AdminApiEndpointsTest` | `MockMvcBuilders.standaloneSetup` — routing, param defaults, 200/400/401/403/404/500 |
| `EssentialsAdminApiAutoConfigurationTest` | `WebApplicationContextRunner` — bean wiring, `enabled=false`, base-path property |

## Extension Points

| SPI | Purpose |
|---|---|
| `EssentialsAuthenticatedUser` (`shared`) | Consumer reports who the caller is. Adapter authenticates nobody |
| `EssentialsSecurityProvider` (`shared`) | Consumer authorizes by role. Enforced inside the SPI beans, not here |

Every bean is `@ConditionalOnMissingBean` — a consumer can replace any controller or the resolver.

## Gotchas

- **`@ConditionalOnBean` needs the ordering to be right.** It is evaluated against the beans registered so far, so `@AutoConfiguration(afterName = …)` must name every auto-configuration defining an SPI bean — `EssentialsComponentsConfiguration`, `EventStoreConfiguration`, and the four aggregate ones. Miss one and its controller is silently skipped and answers 404, which is worse than the loud startup failure it replaced. The startup summary exists so that failure mode is visible.
- **A deployment serves a subset, by design** — and can serve nothing at all. The conformance test compares the contract against controller *classes*, so it is unaffected by runtime conditions; the contract keeps promising all 38 operations. Documented for consumers in `docs/openapi/README.md`.
- **Add an operation ⇒ 3 places**: SPI interface, `EssentialsAdminApiSpec` mapping table, controller here. The conformance test fails until all three agree — that is the point.
- **Never reuse `EssentialTypesJacksonModule`** from `types-jackson`/`types-jackson3`: both publish that same FQCN for different Jackson majors and a build picks one via `essentials.types-jackson.artifactId`. Spring Boot 4 web is always Jackson 3, so this module ships its own serializers. Keep `AdminApiJacksonModule` in sync with `EssentialsValueTypeModelConverter` in `admin-api-spec` — the converter decides the schema, this decides the wire.
- **Controllers map the property placeholder, not a literal** — path assertions must resolve `essentials.admin-api.base-path`. In `standaloneSetup`, use `.addPlaceholderValue(...)`.
- **Path variables are bound as `String`** then converted (`QueueName.of(...)`) so a malformed identifier surfaces as `IllegalArgumentException` → 400. No Spring `Converter` registration needed.
- **`IllegalArgumentException` maps to 400** because `FailFast` throws it. A deeper business-logic IAE will also read as 400.
- **`5xx` never echoes the exception message** — logged only. Don't "improve" this by returning `e.getMessage()`.
- Security defaults are no-access, so out of the box every endpoint answers 401/403 and the starter logs a warning. That is intended, not a misconfiguration.
