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
- **swagger version alignment** — `swagger-models` (non-jakarta, via `swagger-parser`) and `swagger-models-jakarta` (via `swagger-core-jakarta`) share package `io.swagger.v3.oas.models`. One wins at runtime. Equal versions harmless; skew = `NoSuchMethodError` inside openapi-diff, never a resolution error.
  - Rule: `swagger-core.version` = `<swagger-core-version>` of `io.swagger.parser.v3:swagger-parser-project:${swagger-parser.version}`. Look it up; do not take newest swagger-core on Central. `2.1.46 → 2.2.52`.
  - Do **not** derive it from `openapi-diff.version` instead — we pin `swagger-parser.version` *ahead* of the parser openapi-diff declares, so the two only coincided while parser sat at openapi-diff's own 2.1.31. Older wording said "track openapi-diff's parser" and that is how 2.2.53 got picked against a 2.2.52 parser.
  - Bump the pair in one change. Verify after: `mvn dependency:tree -pl components/admin-api-spec | grep swagger-models` must show a single version across jakarta and non-jakarta.
  - Exclusion is not the safety net — `openapi-diff-core` excludes non-jakarta `swagger-models`, but `swagger-parser` still drags it in on its own path, so exclusion alone does not prevent skew.
  - Green tests do not prove alignment: the 2.2.52/2.2.53 skew passed all three OpenAPI tests. Read the tree.
- **Drift gate compares semantically, not textually.** YAML key order is Jackson accessor-discovery order on swagger model classes — not stable across build hosts (same sources emitted `default` before `enum` in the devcontainer, after it on macOS/JDK 25). Test parses both docs and sorts mapping keys; sequence order stays strict. Regenerate writes only on real difference, so no key-order-only churn. Failure reports differing JSON pointers, not the whole 180KB contract.
- Regenerate with `-Dopenapi.regenerate=true`, then **rebaseline only at release** — and regenerate the Java client in the same change; no gate catches a stale client.
- **Regenerate needs `-am`.** Spec reflects off `foundation` / `postgresql-event-store` / `eventsourced-aggregates`. `-pl components/admin-api-spec` alone resolves those from `~/.m2`, so an uninstalled SPI edit regenerates the *old* contract — drift gate then fails again on the next full reactor build. Loops until `-am` (or a prior `install`) is used:
  ```
  mvn -pl components/admin-api-spec -am test -Dtest=OpenApiSpecGenerationTest \
      -Dsurefire.failIfNoSpecifiedTests=false -Dopenapi.regenerate=true
  ```
- Contract YAML is published twice: attached as `:yaml:openapi` via build-helper, and packaged into the jar at `/openapi/essentials-admin-api.yaml` so adapters/tests can read it from the classpath. The Java client instead reads it by **file path**, not as a Maven dependency, so there is no reactor ordering guarantee (harmless — the file is committed).
