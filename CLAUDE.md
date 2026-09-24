# Essentials

Java 25+ building blocks for strongly-typed, event-sourced distributed systems (compiled `--release 25`; build on JDK 25-27).
Multi-module Maven. GroupId: `dk.trustworks.essentials` / `dk.trustworks.essentials.components`.

- `examples/` — demo projects, not part of the release
- `components/foundation-test/` — internal test utilities, not a consumer API

## LLM Docs

Consumer-facing module docs: `LLM/LLM.md` (entry point), `LLM/LLM-*.md` (per-module).
Read before suggesting APIs — don't guess from class names.
Each module has own `CLAUDE.md` with contributor/dev context.

## Commands

```bash
mvn test                                              # unit only, no Docker
mvn verify                                            # unit + integration (needs Docker)
mvn test -pl types -am                                # single module, unit
mvn verify -pl components/postgresql-event-store -am  # single module, integration
mvn clean install                                     # full build
mvn clean install -DskipDependencyCheck=true          # skip OWASP check
mvn clean install -P test-release                     # simulated release
```

Integration-test speed knobs (ITs are ~99% of the build's wall clock):

```bash
mvn -T 1C verify                                      # parallel reactor; multiplies container concurrency with forkCount
mvn verify -Dfailsafe.forkCount=1                     # serialize ITs on a constrained machine / low Docker memory
mvn verify -Dfailsafe.forkCount=0.5C                  # more forks on a big machine — measure, don't assume
mvn verify -Dbenchmark.run=true                       # also run the opt-in latency/throughput suites (off by default)
mvn verify -Dloadtest.skip=true                       # skip the bulk-load suites (on by default) for a faster loop
scripts/test-timings.sh                               # rank test classes by elapsed time from the last run
scripts/test-timings.sh --csv > before.csv            # capture a baseline to diff against
```

`failsafe.forkCount` defaults to 2. Over-forking Docker makes things slower, not faster — use `scripts/test-timings.sh` to tune it rather than guessing.

## Critical Gotchas

- **`provided` scope** — all third-party integrations NOT transitive; consumers declare own deps
- **Intra-service only** — FencedLock/Queues/Inbox/Outbox for same-service multi-instance; not cross-service
- **SQL/NoSQL injection** — table/collection names string-concatenated into queries; validate via `PostgresqlUtil.checkIsValidTableOrColumnName()` / `MongoUtil.checkIsValidCollectionName()`; prefer hardcoded names
- **EventOrder vs GlobalEventOrder** — per-stream vs across all streams of an AggregateType; don't conflate
- **No timestamp ordering** — event ordering via EventOrder/GlobalEventOrder, never timestamps
- **`FailFast` inside a `@MessageHandler` dead-letters the message on first delivery** — the queue consumer applies a built-in permanent-error list *after* consulting the `RedeliveryPolicy`'s `MessageDeliveryErrorHandler`, and `IllegalArgumentException` is on it, as thrown by `requireNonNull`/`requireTrue` and Kotlin `require(...)`. Opt out per type with `alwaysRetryOn(...)`, which overrides the list for `IllegalArgumentException` and `ClassCastException` but not for the three that can never succeed on a retry. A match anywhere in the cause chain counts. Throw a retryable exception when the condition may become true later; details in `LLM/LLM-foundation.md`
- **Docker required for integration tests** — `mvn test` runs without Docker; `mvn verify` needs Docker (TestContainers)
- **`target/` has more than one writer — suspect that before debugging the source.** The VS Code Java language server compiles into the very same `target/classes` Maven uses (the generated `.classpath` sets `output="target/classes"`), and a second concurrent `mvn` run — another terminal, or an agent session — `clean`s and repopulates those directories under the first one. Two symptoms, one cause, neither meaning what it says:
  - **`java.lang.Error: Unresolved compilation problem: …`** in a *test* failure. That text is Eclipse JDT output, never javac. Where ECJ's inference is weaker than javac's, it writes a class whose method bodies just throw; Maven's incremental compiler then sees the `.class` as newer than the `.java` and skips recompiling, so the broken bytecode runs. Known case: `shared`'s `ComparableTuple.toList()` — `List.of(_1, _2)` under `T extends Comparable<? super T>` bounds, which ECJ rejects and javac accepts.
  - **`package dk.trustworks.essentials.… does not exist`** compiling a *downstream* module (e.g. `immutable` against `shared`), when that module's POM plainly declares the dependency. A whole package vanishing means the upstream jar got built from a half-populated `target/classes`, not that anything is wrong with the code.

  Fix: make sure nothing else is building, then `mvn clean install -pl <module> -am`. Tells that it is this and not a real break: `git status` shows the module's sources untouched; and it does not reproduce on a quiet rerun. A green run does not mean it is gone — the language server rebuilds in the background
- **Test serializer wiring** — tests build serializers via `EssentialsObjectMappers.createJSONSerializer()` / `EssentialsJSONEventSerializers.create()`, never a hand-built mapper; a hand-built one drifts from persisted format (e.g. value types written as `{"value":"…"}`)
- **`-proc:full` is load-bearing and must stay.** JDK 23 stopped running annotation processors found on the *classpath*; without the flag `spring-boot-configuration-processor` silently does not run and every starter ships with no `META-INF/spring-configuration-metadata.json` — no IDE completion, no documented defaults for any `essentials.*` property. Nothing fails, the file is just absent, and an already-installed jar keeps whatever an older toolchain generated, so a clean build is the only way to notice. It was absent from every starter before this was added. Valid on every JDK the build supports (25+), and safe because the Spring processor is the only one in the reactor.
- **Stable central APIs** — breaking changes only in new major; always additive in patch/minor
- **No Node / JavaScript build deps** — the whole build runs on a JVM alone. Any UI work uses Thymeleaf + vanilla JS; no npm, bundler, or JS framework
- **Jackson 3 only; wire format frozen** — since 0.60 Jackson 3 (`tools.jackson`) is sole supported major; `types-jackson`/`immutable-jackson` (J2) gone, no flavor profiles. All persistence mappers come from `EssentialsObjectMappers`. Persisted JSON must stay byte-identical to 0.50's Jackson 2 output so existing data stays readable — guarded by golden docs written by old J2 mapper: `EssentialsObjectMappersWireFormatTest` (postgresql-event-store, `wire-format/*.json`) and `WireFormatCompatibilityTest` (types-jackson3, `serialization-test-subject.json`). Never regenerate existing golden docs — J2 writer is gone, regenerating just re-baselines on J3 and hides drift. CDC included. Root-POM enforcer `ban-jackson-2` bans `com.fasterxml.jackson.core:jackson-databind` in compile/provided/runtime except `types-avro` (Avro), `admin-api-spec` (swagger-core), `admin-api-client-java` (generated OpenAPI client). `com.fasterxml.jackson.annotation.*` still fine — shared by J3
- **Jackson 3 needs two per-type pins** — J3 disabled final-field mutation (on by default in Jackson 2), which is how immutable payloads get populated, so `EssentialsObjectMappers` re-enables it. That in turn makes a type that *is* a collection or scalar wrapper look like a bean, so those are pinned to delegating creators: `Jackson3CollectionWrapperModule` (foundation, by shape) and `SingleValueTypeCreatorIntrospector` (types-jackson3). Never do it with annotations on the Essentials types themselves
- **Under Jackson 3 a constructor parameter *name* is part of the JSON contract.** J3 reads parameter names from the bytecode and uses any constructor as an implicit properties-based creator — even when a no-arg constructor exists. 0.50's J2 mapper registered no parameter-names module, so it never did this and populated fields instead. A parameter whose name does not match the JSON property it ends up in therefore receives `null`, and the class either fails its own `requireNonNull` guard or comes back half-populated. Two shapes bite: a parameter named differently from the field it assigns (`priceValidity` → field `priceValidityPeriod`), and a parameter that is not a property at all because the value is routed elsewhere (classic `Event<ID>` subclasses taking `orderId` and calling `aggregateId(...)`, which persists as `aggregateId`). Fix on the type — rename the parameter, or `@JsonProperty("…")` (that annotation package is shared by both majors). `ConstructorDetector.EXPLICIT_ONLY` does **not** avoid it: with no other way to construct, J3 uses the sole constructor regardless
- **Map keys keyed by a value type need no annotation under Jackson 3** — `types-jackson3` registers `SingleValueTypeKeyDeserializers`. Upgrade trap: 0.50-era code using J2 `@JsonDeserialize(keyUsing=…)` (package `com.fasterxml.jackson.databind.annotation`) — J3 does not read it, so it silently stops applying. Surfaced as aggregate snapshots deserializing into `BrokenSnapshot`
- **Admin surface = one contract** — an admin operation lives in 3 synced places: the `*Api` SPI, the `EssentialsAdminApiSpec` mapping table, and a controller in `spring-boot-starter-admin-api`
- **A version property is not proof of a version** — an imported BOM below `spring-boot-dependencies` in the root `dependencyManagement` is silently inert (first import wins); a *direct* entry outranks every import wherever it sits. `jackson-bom`, `mockito-bom` and a dead `junit-bom.version` all read as if they applied while Spring Boot's versions resolved. Order rationale is commented at that block in `pom.xml`. The `enforce-dependency-hygiene` enforcer execution now fails the build on one half of this — management resolving *below* what something asked for — but it cannot see a pin that is merely inert, so still confirm every version claim with `mvn dependency:tree`, never by reading a POM

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
