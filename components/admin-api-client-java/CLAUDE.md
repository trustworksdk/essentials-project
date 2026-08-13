## admin-api-client-java

Generated Java client for Essentials Admin API. Maven: `admin-api-client-java`.
Zero hand-written source — `src/` is empty. Everything under `dk.trustworks.essentials.components.adminapi.client`
is emitted at `generate-sources` by openapi-generator from `admin-api-spec/openapi/essentials-admin-api.yaml`.

Generator: `java` + `library=native` (`java.net.http`), Jackson serialization, jakarta EE, `openApiNullable=false`.

## Gotchas

- **`openapi-generator.version` *is* this module's public API.** No source here moves when it changes, yet
  every consumer-facing class does. Treat a bump as an API change, not a build-tool bump.
- **Green build proves nothing.** Nothing in this repo calls the client, so compilation success says only
  that the generator emitted valid Java. Diff the emitted surface instead:
  ```
  # before and after the bump
  cd target/classes && find . -name '*.class' | sed 's|^\./||;s|\.class$||;s|/|.|g' \
    | sort | while read c; do javap -cp . "$c"; done > /tmp/api-<version>.txt
  diff /tmp/api-old.txt /tmp/api-new.txt      # '<' lines = removals = breaks
  ```
  `7.8.0 → 7.24.0` added 168 members but removed/retyped 7 — async response interceptor went
  `HttpResponse<String>` → `HttpResponse<InputStream>`, `updateBaseUri`/`getDefaultBaseUri` became `final`,
  `createDefaultObjectMapper`/`createDefaultHttpClientBuilder` went `protected` → `public static`,
  `Configuration()` public constructor removed. None of that fails a build.
- **Reads contract by file path, not as a Maven dependency** (`${admin-api.spec}` → `../admin-api-spec/...`).
  No reactor ordering guarantee. Harmless because the YAML is committed — but a spec regenerated in the same
  build is not picked up unless `admin-api-spec` ran first.
- **Regenerate the client whenever the contract is rebaselined.** No gate catches a stale client;
  `admin-api-spec`'s drift gate only compares spec-vs-SPI.
- Client swagger/jackson versions are unrelated to `admin-api-spec`'s — see that module's CLAUDE.md for the
  `swagger-core`/`swagger-parser` split-package rule, which does not apply here.
