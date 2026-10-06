# init-overlay: CI-only wiring checks for the rendered init cells

Never rendered into a user project. The scaffold harness copies `<language>/src/**` verbatim into a rendered
cell's `backend/src/` before `mvn verify` (it refuses to overwrite a file the cell already has). The files use
the golden cells' package, `com.example.golden`, so every cell and host is rendered with `groupId=com.example`,
`artifactId=golden`, `packagePath=com.example.golden`.

What they prove on every cell, on top of the generated `ApplicationContextIT` (and `CorsPreflightIT` on standalone):

| Check | Requirement | Silent failure it catches |
|---|---|---|
| A semantic id binds as a `@PathVariable` (200, not 500) | S4 | no `Essentials*WebConfigurer` `@Import` |
| The web mapper writes it as a JSON string | S3.3 (Java), S3.4 (Kotlin) | `EssentialTypesJacksonModule` / `KotlinModule` missing from the web mapper |
| The persistence serializer writes it as a JSON string | S3.2, S3.4 | Kotlin: `{"value":"…"}` persisted because `KotlinModule` never reached the persistence mapper |
| The command bus is the starter's `DurableLocalCommandBus` | S2 | a second or missing command bus |
| `contracts/openapi.json` contains `/api/wiring/{id}` after `verify` | S7 | `OpenApiContractIT` did not run or could not start the context |
| `ProbeDocument.id` and `related[]` document as `{"type":"string"}` | S4/S7 | `SingleValueTypeModelConverter` not registered (Java: `$ref` to an object schema; Kotlin: a mangled `id-…` key) |

The controller also gives Orval a real endpoint, so the frontend job compiles a generated client rather than an
empty one.
