## admin-api-spec

Code-first OpenAPI contract for the eleven Essentials admin `*Api` SPIs. Maven: `admin-api-spec`.
Committed `openapi/essentials-admin-api.yaml` = single source of truth for `admin-api-client-java`.
Served by `spring-boot-starter-admin-api`, whose conformance test gates contract-vs-implementation drift.

Consumer-facing docs: `docs/openapi/README.md`.

## Key Classes

| Class | Internal Role |
|---|---|
| `EssentialsAdminApiSpec` | Declarative mapping table — verb/path/params/roles per SPI method, plus `API_INTERFACES`, `DTO_CLASSES`, `TAGS`, `ALWAYS_PRESENT_PROPERTIES`, `NULLABLE_PROPERTIES`. Low-churn half of the contract |
| `OpenApiSpecGenerator` | Builds + serializes the `OpenAPI` model; owns `SpecBuilder` (paths/schemas/parity) and `OperationSpec` (one operation) |
| `EssentialsValueTypeModelConverter` | swagger `ModelConverter` collapsing `SingleValueType` wrappers to JSON primitives |
| `OpenApiSpecGenerationTest` | Drift gate + contract-convention assertions |
| `OpenApiContractCompatibilityTest` | openapi-diff gate vs `openapi/baseline/essentials-admin-api-v1.yaml` |
| `OpenApiSpecValidationTest` | swagger-parser validation + dangling-`$ref` check. JVM-only replacement for the old redocly lint — project carries no Node dependencies |

## Build Invariants

- Every declared interface method maps to exactly one operation — unmapped, stale, duplicate verb+path, or unknown interface fails `buildOpenApi()`.
- Overloaded SPI methods are rejected: parity is keyed on method name alone, so an overload would let one variant pass as covered.
- `ALWAYS_PRESENT_PROPERTIES` / `NULLABLE_PROPERTIES` entries are validated against real record components — a renamed field fails the build.
- Schemas reflect automatically from `DTO_CLASSES`; nested record types are discovered transitively.

## Gotchas

- **Paths are server-relative.** `register()` uses `spec.path` alone; `BASE_PATH` belongs to `servers[0].url` only. Putting the prefix in both makes generated clients emit `/v1/v1/...`.
- **`nullable` on a `$ref` property is silently dropped** (OpenAPI 3.0 ignores `$ref` siblings). `markNullable()` wraps the ref in `type: object` + single-element `allOf`; the explicit `type` is what keeps `nullable` meaningful.
- **No `securitySchemes`** — deliberate. Contract says which roles satisfy an operation (`x-required-roles`), nothing about authentication. Don't add one back; the adapter authenticates nobody.
- **`required` is claimed conservatively** — primitives + verified reference properties only. Over-claiming breaks client type guards at runtime.
- **swagger version alignment**: `swagger-models` (via openapi-diff → swagger-parser) and `swagger-models-jakarta` (generator) share package `io.swagger.v3.oas.models`. Skew shows up as `NoSuchMethodError` inside openapi-diff, not as a resolution error. `swagger-core.version` must track the pinned `openapi-diff.version`'s parser. Non-jakarta `swagger-models` is excluded from the test scope.
- Regenerate with `-Dopenapi.regenerate=true`, then **rebaseline only at release** — and regenerate the Java client in the same change; no gate catches a stale client.
- Contract YAML is published twice: attached as `:yaml:openapi` via build-helper, and packaged into the jar at `/openapi/essentials-admin-api.yaml` so adapters/tests can read it from the classpath. The Java client instead reads it by **file path**, not as a Maven dependency, so there is no reactor ordering guarantee (harmless — the file is committed).
