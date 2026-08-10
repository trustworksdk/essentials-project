# Essentials

Java 17+ building blocks for strongly-typed, event-sourced distributed systems.
Multi-module Maven. GroupId: `dk.trustworks.essentials` / `dk.trustworks.essentials.components`.

- `examples/` — demo projects, not part of the release
- `components/foundation-test/` — internal test utilities, not a consumer API

## LLM Docs

Consumer-facing module docs: `LLM/LLM.md` (entry point), `LLM/LLM-*.md` (per-module).
Read before suggesting APIs — don't guess from class names.
Each module has own `CLAUDE.md` with contributor/dev context.

## Commands

```bash
mvn test                                              # unit only, no Docker (Jackson 3 flavor — the default)
mvn -Pjackson2 test                                   # same, against the Jackson 2 flavor
mvn verify                                            # unit + integration (needs Docker)
mvn test -pl types -am                                # single module, unit
mvn verify -pl components/postgresql-event-store -am  # single module, integration
mvn clean install                                     # full build
mvn clean install -DskipDependencyCheck=true          # skip OWASP check
mvn clean install -P test-release                     # simulated release
```

## Critical Gotchas

- **`provided` scope** — all third-party integrations NOT transitive; consumers declare own deps
- **Intra-service only** — FencedLock/Queues/Inbox/Outbox for same-service multi-instance; not cross-service
- **SQL/NoSQL injection** — table/collection names string-concatenated into queries; validate via `PostgresqlUtil.checkIsValidTableOrColumnName()` / `MongoUtil.checkIsValidCollectionName()`; prefer hardcoded names
- **EventOrder vs GlobalEventOrder** — per-stream vs across all streams of an AggregateType; don't conflate
- **No timestamp ordering** — event ordering via EventOrder/GlobalEventOrder, never timestamps
- **Docker required for integration tests** — `mvn test` runs without Docker; `mvn verify` needs Docker (TestContainers)
- **The flavor profile does not survive transitivity** — a profile only overrides `essentials.types-jackson.artifactId` for modules in the *current reactor*. Installed POMs keep the property unresolved (`flattenMode=resolveCiFriendliesOnly`) and it then resolves from the Essentials parent's **default**, now Jackson 3. So the non-default flavor is the exposed one: `mvn -Pjackson2 -pl <module>` can put **both** flavors on the classpath (same FQCNs) when a sibling comes from the local repo instead of the reactor. Add `-am`, or verify with the full reactor. `EssentialsJacksonModules` fails loudly on the mismatch — believe it rather than the profile
- **Jackson-flavor-neutral test wiring** — tests must build serializers via `EssentialsObjectMappers.createJSONSerializer()` / `EssentialsJSONEventSerializers.createForActiveJacksonFlavor()`. Hardcoding `new JacksonJSONSerializer(...)` makes the test silently exercise Jackson 2, and under `-Pjackson3` it either throws the flavor-mismatch error or persists value types as `{"value":"…"}`
- **Stable central APIs** — breaking changes only in new major; always additive in patch/minor
- **No Node / JavaScript build deps** — the whole build runs on a JVM alone. Any UI work uses Thymeleaf + vanilla JS; no npm, bundler, or JS framework
- **Two Jackson flavors, one wire format** — a build picks Jackson 3 (default, matching Spring Boot 4) or Jackson 2 (`-Pjackson2`) via `essentials.types-jackson.artifactId`; `types-jackson`/`types-jackson3` share FQCNs so only one is ever on the classpath. All persistence mappers must come from `EssentialsObjectMappers` so both majors write byte-identical JSON — existing persisted data must stay readable after an upgrade. CDC included. Touching serialization means running both profiles
- **Jackson 3 needs two per-type pins** — it disabled final-field mutation (Jackson 2's default), which is how immutable payloads get populated, so `EssentialsObjectMappers` re-enables it. That in turn makes a type that *is* a collection or scalar wrapper look like a bean, so those are pinned to delegating creators: `Jackson3CollectionWrapperModule` (foundation, by shape) and `SingleValueTypeCreatorIntrospector` (types-jackson3). Never do it with annotations on the Essentials types themselves
- **Under Jackson 3 a constructor parameter *name* is part of the JSON contract.** J3 reads parameter names from the bytecode and uses any constructor as an implicit properties-based creator — even when a no-arg constructor exists. The J2 mapper registers no parameter-names module, so it never did this and populated fields instead. A parameter whose name does not match the JSON property it ends up in therefore receives `null`, and the class either fails its own `requireNonNull` guard or comes back half-populated. Two shapes bite: a parameter named differently from the field it assigns (`priceValidity` → field `priceValidityPeriod`), and a parameter that is not a property at all because the value is routed elsewhere (classic `Event<ID>` subclasses taking `orderId` and calling `aggregateId(...)`, which persists as `aggregateId`). Fix on the type — rename the parameter, or `@JsonProperty("…")` (that annotation package is shared by both majors). `ConstructorDetector.EXPLICIT_ONLY` does **not** avoid it: with no other way to construct, J3 uses the sole constructor regardless
- **Map keys keyed by a value type need no annotation under Jackson 3** — `types-jackson3` registers `SingleValueTypeKeyDeserializers`. Under Jackson 2 they need `@JsonDeserialize(keyUsing=…)`, and that annotation is in J2's `com.fasterxml.jackson.databind.annotation` package which J3 does not read — so on upgrade it silently stops applying. It surfaced as aggregate snapshots deserializing into `BrokenSnapshot`
- **Admin surface = one contract** — an admin operation lives in 3 synced places: the `*Api` SPI, the `EssentialsAdminApiSpec` mapping table, and a controller in `spring-boot-starter-admin-api`

## Knowledge graph queries

Hand-curated `graphify query` rules. Keep them here — `## graphify` below is overwritten on every devcontainer rebuild (mechanism: post-create.sh).

- Query with **1-3 identifier tokens, never the user's sentence** — overrides the stock `"<question>"` phrasing below. Seed selection guarantees ≥1 BFS start node per matching term, and traversal depth is fixed at 2, so every extra word multiplies the subgraph. `SingleValueTypeConverter` → 2 seeds, 29 nodes; same question as prose → 9 seeds (incl. junk like `PATH`, `types`, `Registration Rules`), 393 nodes, 93% truncated.
- Truncation means **narrow the query**, not raise `--budget`. Budget is a render cap (default 2000 tokens), not a relevance knob — raising it on a bad seed set just dumps the noise.
- `--context call` (also `import`, `field`, `parameter_type`, `return_type`, `attribute`, `generic_arg`) narrows to code structure. Caveat: ~1/3 of edges carry no context — those hold the README and `LLM/*.md` nodes, so any `--context` filter drops all docs from the traversal.
- Class names collide across modules (`SingleValueTypeConverter` matches 5 nodes). `explain` refuses ambiguous names — pass the repo-relative path or full node id it lists.
- The truncation banner's `context_filter=[…]` / `get_node` advice is for graphify's MCP server. CLI equivalents: `--context` and `explain`.
- Graph covers the Java/Kotlin tree plus README/`LLM/*.md` only — not `.devcontainer/` scripts or installed tool sources. Grep those directly; a query about them returns unrelated seeds.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## headroom_read

Use `mcp__headroom__headroom_read` instead of `Read` for files likely to be read more than once (module poms, module `CLAUDE.md`, `LLM/*.md`): the first read costs full price, re-reads of an unchanged file return a ~20-token cache marker. Pass `fresh: true` after a compaction and in subagents. Skip it for one-shot reads — there is no gain. `headroom_compress` is NOT a context saver (content must already be in context to pass it as a parameter); use it only to stash output that is expensive to regenerate, then `headroom_retrieve` by hash.
