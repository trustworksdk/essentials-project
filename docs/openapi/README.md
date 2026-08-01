# Essentials Admin API

This directory documents the **Essentials Admin API** — an HTTP contract over the in-process
administration/monitoring SPIs of the Essentials components (fenced locks, durable queues,
scheduler, event store, CDC, and PostgreSQL statistics).

It replaced the bundled Vaadin admin UI: the contract is defined once, served by a starter, and any UI
is built on top of it. See the roadmap at the end.

> **Status: served.** `spring-boot-starter-admin-api` implements the contract over Spring WebMVC. A
> conformance test in that module fails the build if the endpoints it serves and the operations the
> contract declares ever diverge.

## Where things live

| Artifact | Location |
|----------|----------|
| Canonical contract | [`components/admin-api-spec/openapi/essentials-admin-api.yaml`](../../components/admin-api-spec/openapi/essentials-admin-api.yaml) |
| Published contract artifact | `dk.trustworks.essentials.components:admin-api-spec:<version>:yaml:openapi` (also on the jar's classpath at `/openapi/essentials-admin-api.yaml`) |
| Last-released baseline (compat gate) | `components/admin-api-spec/openapi/baseline/essentials-admin-api-v1.yaml` |
| Contract generator + drift/compat/validation tests | `components/admin-api-spec` |
| HTTP adapter (Spring Boot starter) | `components/spring-boot-starter-admin-api` |
| Optional default UI (Spring Boot starter) | `components/spring-boot-starter-admin-ui` |
| Generated Java client (Maven module) | `components/admin-api-client-java` |
| Changelog | [`CHANGELOG.md`](CHANGELOG.md) |

> **No Node, no JavaScript build dependencies.** Everything here — generating the contract,
> validating it, generating the client, serving the API — runs on a JVM alone.

## How the contract is produced (code-first)

The contract is **generated from the SPI interfaces**, not hand-written:

```
7 *Api SPIs (Java)
  ──reflection (swagger-core)──▶ JSON schemas (DTO records → schemas)
  ──declarative mapping table──▶ REST shape (verb/path/params/roles)
  ═══════════════════════════════▶ essentials-admin-api.yaml (committed)
        ├──openapi-generator (java/native)──▶ Java client
        └──conformance test────────────────▶ spring-boot-starter-admin-api
```

- DTO schemas are reflected automatically, so adding/removing/renaming a DTO field flows through.
- The REST shape (HTTP method, path, parameters, required roles) is declared in a small type-safe
  mapping table: `EssentialsAdminApiSpec`.
- Every public method of every covered interface **must** be mapped; an unmapped or stale mapping
  fails the build.

The seven covered interfaces are `DBFencedLockApi`, `SchedulerApi`, `PostgresqlQueryStatisticsApi`,
`DurableQueuesApi`, `EventStoreApi`, `CdcApi`, and `PostgresqlEventStoreStatisticsApi`.

## Contract conventions

**Paths are server-relative.** The `/api/essentials/admin/v1` prefix lives in `servers[0].url`; path
keys are relative to it (`/fenced-locks`, not `/api/essentials/admin/v1/fenced-locks`). Repeating the
prefix in both places would make every generated client prepend it twice, since a client's default
base URI *is* that server URL.

**Every operation declares its error statuses.** `401`, `403` and `500` on all operations, `400`
wherever there is a parameter or request body to reject, and `404` on the operations whose SPI method
returns an `Optional`. All error bodies are the `Error` schema (`status` and `error` are required).

**`required` is claimed conservatively.** A property is marked required only when it cannot be absent:
primitive-typed record components (guaranteed by the Java type system) plus a small set of
reference-typed properties verified against their producers and declared in
`EssentialsAdminApiSpec.ALWAYS_PRESENT_PROPERTIES`. Everything else stays optional, so a generated
client is never told a field is guaranteed when the server can legitimately send `null`. Properties
that are null *by design* — a role-gated payload, or a component not running in the queried instance —
are marked `nullable` with the reason as their description.

## Security model

The API is **agnostic about authentication**. The contract declares no security scheme, and the
adapter depends on no security framework — not even Spring Security. Two SPIs, both implemented by
the consuming application, carry the whole model:

| SPI | Question it answers | Where it is used |
|-----|--------------------|------------------|
| `dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser` | Who is calling? | The adapter asks it for the caller's principal |
| `dk.trustworks.essentials.shared.security.EssentialsSecurityProvider` | May this principal do this? | The SPI beans ask it, per operation, per role |

How a request is authenticated in the first place — session cookie, bearer token, mTLS, a gateway
header — is entirely the host application's business. It authenticates the request however it likes
and reports the result through `EssentialsAuthenticatedUser`.

Each operation lists the roles that satisfy it under the `x-required-roles` vendor extension; the
`essentials_admin` role satisfies every operation.

**Secure by default.** Both SPIs default to their no-access implementations, so an application that
has implemented neither exposes nothing: `401` while no authenticated user is reported, `403` once a
user exists but holds no roles. The starter logs a prominent warning at startup while those defaults
are in place, because the API includes destructive operations (purge a queue, delete a message,
release a lock).

## Serving the API

Add the starter; there is nothing else to wire:

```xml
<dependency>
    <groupId>dk.trustworks.essentials.components</groupId>
    <artifactId>spring-boot-starter-admin-api</artifactId>
</dependency>
```

Then implement the two SPIs as Spring beans. Configuration:

| Property | Default | Purpose |
|----------|---------|---------|
| `essentials.admin-api.enabled` | `true` | Set `false` to expose nothing at all |
| `essentials.admin-api.base-path` | `/api/essentials/admin/v1` | Relocate the mount point, e.g. behind a gateway prefix |

Error responses use the contract's `Error` schema. `5xx` bodies deliberately carry no exception
detail — the cause is logged instead, so an internal failure cannot leak schema names, SQL, or
hostnames to an HTTP caller.

## Consuming the Java client

The contract's server URL is relative, so the `native` client — which cannot send a relative URI —
must be told where the API is mounted:

```java
var apiClient = new ApiClient();
apiClient.updateBaseUri("https://host/api/essentials/admin/v1");
```

The `native` library generates no authentication helpers by design, matching the contract: attach
whatever credentials your deployment uses via `ApiClient.setRequestInterceptor(...)`.

## Versioning & backwards compatibility

- **Path-major versioning.** Every operation is served under `/api/essentials/admin/v1`. Additive
  changes (new endpoints, new optional fields) ship within `v1`. A genuinely breaking change is
  introduced under a new `/api/essentials/admin/v2` served side-by-side, so existing `v1` consumers
  and their generated clients keep working.
- **Drift gate.** `OpenApiSpecGenerationTest` fails if the committed YAML no longer matches the
  SPIs (e.g. a new interface method, or a changed DTO field).
- **Validation gate.** `OpenApiSpecValidationTest` parses the contract with swagger-parser and fails
  on any structural problem or dangling `$ref`.
- **Conformance gate.** `AdminApiContractConformanceTest` in the adapter compares the endpoints it
  serves against the contract's operations, in both directions — an unimplemented operation and an
  endpoint outside the contract both fail the build.
- **Compatibility gate.** `OpenApiContractCompatibilityTest` diffs the regenerated contract against
  the checked-in baseline (`baseline/essentials-admin-api-v1.yaml`) with
  [openapi-diff](https://github.com/OpenAPITools/openapi-diff). A breaking change within the same
  major fails the build.
- **Deprecation.** Mark an operation/field `deprecated: true` for at least one minor release before
  removing it; removal happens only at the next major.

## Common tasks

Regenerate the contract after intentionally changing an SPI or DTO:

```bash
mvn -pl components/admin-api-spec test \
    -Dtest=OpenApiSpecGenerationTest -Dopenapi.regenerate=true
```

Run the drift + compatibility gates:

```bash
mvn -pl components/admin-api-spec -am test -DskipDependencyCheck=true
```

Promote the current contract to the baseline at release time (additive minor):

```bash
cp components/admin-api-spec/openapi/essentials-admin-api.yaml \
   components/admin-api-spec/openapi/baseline/essentials-admin-api-v1.yaml
```

Generate the Java client (sources are produced at build time):

```bash
mvn -pl components/admin-api-client-java -am install -DskipTests
```

Check that the adapter still serves exactly what the contract declares:

```bash
mvn -pl components/spring-boot-starter-admin-api test
```

> The new modules add the `io.swagger.core.v3`, `org.openapitools.openapidiff`, and
> `openapi-generator` build-time dependencies. If the OWASP dependency-check gate flags a transitive
> build-time dependency, build with `-DskipDependencyCheck=true`.

> **Keep swagger versions aligned.** `swagger-models` (pulled in by openapi-diff through
> swagger-parser) and `swagger-models-jakarta` (used by the generator) share the
> `io.swagger.v3.oas.models` package. A version skew between them surfaces as a `NoSuchMethodError`
> inside openapi-diff rather than as a dependency error, so `swagger-core.version` in
> `components/admin-api-spec/pom.xml` must track whatever swagger-core the pinned
> `openapi-diff.version` parses with.

After regenerating the contract, regenerate the clients in the same change — they are generated from
the committed YAML, and nothing in the build fails if they lag behind it.

## Roadmap

- ~~**Phase 1:** contract + generated Java client.~~ Done.
- ~~**Phase 2:** HTTP adapter — a Spring Boot starter exposing the seven SPIs over HTTP, conformant
  to this contract and delegating to the existing SPI beans + `EssentialsSecurityProvider`.~~ Done:
  `spring-boot-starter-admin-api`.
- ~~**Phase 4:** remove `vaadin-ui` + `spring-boot-starter-admin-ui`.~~ Done — both modules are gone,
  along with the Vaadin BOM, the Karibu test dependencies and the Vaadin doc tree. The admin surface is
  now the contract plus `spring-boot-starter-admin-api`; consumers build their own UI on it.
- ~~**Phase 3:** an optional default UI in Thymeleaf and vanilla JavaScript.~~ Done:
  `spring-boot-starter-admin-ui`, served at `/essentials/admin`. No Node, no npm, no bundler and no
  JavaScript framework. The browser calls these endpoints directly, so the default UI is a consumer of
  the published contract like any other — `AdminUiContractParityTest` fails the build if the UI and the
  contract drift apart in either direction.

> **Migrating off the Vaadin admin UI.** The views mapped onto the API as follows: Locks →
> `/fenced-locks`, Queues → `/durable-queues/**`, Subscriptions → `/event-store/subscriptions`,
> Scheduler → `/scheduler/**`, PostgreSQL statistics → `/postgresql/query-statistics/top-ten-slowest`
> and `/event-store/statistics/**`. The EventProcessors view was a placeholder stub with no backing
> SPI and has no equivalent. `EssentialsAuthenticatedUser` is still the SPI that identifies the
> caller, so an existing implementation carries over unchanged.
