# Platform upgrade for 0.60 — JDK 25, Kotlin 2.4, Spring Boot 4.1.1, Jackson 3 only

**Target: 0.60.** This is the breaking major, and it already carries the durable-queues refactor
(`docs/durable-queues-breaking-refactor.md`) and the `forRemoval` constructor removals promised by
`docs/MIGRATION-NEXT_MAJOR.md`. This document covers the platform side of that release:

| # | Workstream                      | From                                  | To                                   |
|---|---------------------------------|---------------------------------------|--------------------------------------|
| 1 | JDK baseline (`--release`)      | 21 (build on 21–25)                   | **25** (build on 25+)                |
| 2 | Kotlin compiler                 | 2.2.21                                | **2.4.20** (newest 2.4.x on Central) |
| 3 | Spring Boot BOM                 | 4.0.8                                 | **4.1.1**                            |
| 4 | Jackson flavors                 | Jackson 3 (default) + Jackson 2 (`-Pjackson2`) | **Jackson 3 only**          |

Versions were read from Maven Central on 2026-09-23. Re-check them when work starts.

## 1. Which branch to base on

The facts:

- `queue-breaking-refactor` is 15 commits **ahead** of `main` and 0 behind. Its merge base is `main`'s tip
  (`5b090b1c`), so today it is a clean fast-forward of `main`.
- It already edits the files the Jackson 2 removal must edit: `DefaultDurableQueueConsumer`,
  `CentralizedMessageFetcher` (both import `com.fasterxml.jackson.databind.exc.MismatchedInputException`),
  `MongoDurableQueues`, both `EssentialsComponentsConfiguration` starters, `components/foundation/pom.xml`,
  `components/spring-boot-starter-*/pom.xml`, the root `CLAUDE.md` and several `LLM/*.md` files.
- Its own plan (§ "Both Jackson flavors") requires every serialization-touching commit to pass `mvn -Pjackson2 test`.
  Once Jackson 2 is gone that obligation goes too.
- A JDK 25 baseline is itself a breaking change, so none of this work can go to `main` while `main` still ships
  0.50.x patches.

**Recommendation: do not stack the upgrade on `queue-breaking-refactor` as a feature branch. Create one integration
branch for the whole major and put both efforts on it.**

1. Create `release/0.60` from `queue-breaking-refactor`'s current tip. Today that is the same as fast-forwarding it
   from `main`. From then on, the queue work lands on `release/0.60` through its own PRs.
2. Branch `upgrade/0.60-platform` from `release/0.60`, and do this plan there as the commit series in §3.
3. Keep `main` as the 0.50.x line. Forward-merge `main` into `release/0.60` after each patch release, not by rebasing.
4. When 0.60 is ready, merge `release/0.60` into `main`.

This gives one conflict surface in place of two long-lived branches racing on the same files, and the queue branch
stops paying for dual-flavor testing as soon as step 4.x in §3 lands. If the queue refactor has to ship before the
platform work, or without it, basing on `queue-breaking-refactor` directly is the fallback, at the cost of a rebase
whenever it moves.

Ordering inside the branch: **platform bumps first, Jackson 2 removal last.** Spring Boot 4.1.1 manages the same
Jackson versions as 4.0.8 (Jackson 3 `3.1.5`, Jackson 2 `2.21.5`), so the bumps in steps 1–3 do not touch
serialization and only need the default profile. That keeps each bump bisectable, and the large removal then starts
from a green, current baseline.

## 2. Decisions to confirm before starting

Each has a recommendation. They go into the release notes, so settle them before the relevant step.

| ID | Question | Recommendation | Why |
|----|----------|----------------|-----|
| D1 | Branch base | **Decided:** `release/0.60`, created at `queue-breaking-refactor`'s tip (`36adb5f2`), with the queue work treated as complete; this work on `upgrade/0.60-platform` | One conflict surface; `main` stays patchable |
| D2 | Artifact names once Jackson 2 is gone | **Keep** `types-jackson3` / `immutable-jackson3`; **delete** `types-jackson` / `immutable-jackson` | Reusing `types-jackson` for Jackson 3 content means a consumer's unchanged dependency silently changes Jackson major. A missing artifact fails loudly, and that is the failure we want |
| D3 | J2-named serializer classes (`JacksonJSONSerializer`, `JacksonJSONEventSerializer`) | **Delete** them; keep the `Jackson3*` names | Same reason as D2: the name would compile against a different type. A later minor can add an alias if people ask for one |
| D4 | `kotlinLanguage.version` / `kotlinApi.version` (the consumer floor) | **Decided:** `2.3` / `2.3` | Measured on 2026-09-23 with the embeddable compilers. (1) The JDK 25 baseline already makes Kotlin 2.3 the consumer minimum: Kotlin 2.2 has no `-jvm-target 25`, and no compiler will inline our version-69 bytecode into a lower target. (2) A compiler reads metadata one minor ahead but not two: 2.3.21 reads language-2.4 classes, 2.2.21 rejects them. Language 2.3 matches Boot 4.1.1's managed Kotlin (2.3.21) exactly, so consumers never rely on the one-ahead rule, and a later 2.5 compiler bump stays safe. (3) Boot 4.1.1 manages `kotlin-stdlib` 2.3.21, so an API level of 2.4 could bind to stdlib functions consumers do not have (`NoSuchMethodError`); 2.3 is the ceiling |
| D5 | Upper bound of the enforcer JDK range, and the CI matrix | **Decided:** `[25,28)`; CI runs full `verify` on 25 and unit tests on 26 and 27 | Adoptium ships 26 and 27 GA (27 is the newest feature release; 28 is early access). A bounded range allows exactly what CI tests and fails with a clear enforcer message on an untested JDK, instead of an obscure ByteBuddy or Kotlin-compiler error. Raise the ceiling by one in the same change that adds a new JDK to the CI matrix |
| D6 | Java 25 language adoption (e.g. `ScopedValue` in place of `ThreadLocal` in unit-of-work code) | **Out of scope.** Changing the baseline only; features adopted later, one per change | Keeps this series mechanical and reviewable |

## 3. Steps

Each numbered step is one commit, or a small group of commits, that builds green on its own. "Verify" is the gate
before moving on. Every command runs with the devcontainer's JDK 25 (see the build-JDK note in project memory:
override `JAVA_HOME` per invocation).

### Step 0 — Branch setup

1. Done: `release/0.60` created at `36adb5f2` (the tip of `queue-breaking-refactor`), and `upgrade/0.60-platform`
   branched from it. `queue-breaking-refactor` can be deleted once `release/0.60` is on `origin`.
2. Capture a baseline: `mvn clean verify` on the default profile, then `scripts/test-timings.sh --csv > before.csv`.
   Record any existing flaky tests so they are not blamed on the upgrade later.

### Step 1 — JDK 25 baseline

1. Root `pom.xml`: `java.release.version` 21 → 25. `java.version`, `maven.compiler.release` and every module's
   `kotlin-maven-plugin` `<jvmTarget>${java.version}</jvmTarget>` follow from that property. Confirm no module
   overrides it (none does today).
2. Enforcer `requireJavaVersion`: `[${java.release.version},26)` → `[${java.release.version},28)` (D5).
3. `java.build.version` stays at 25. Check whether anything still needs it as a separate property now that the build
   version equals the release version; drop it if nothing does.
4. `.github/workflows/maven.yml`:
   - `verify` matrix `['21','25']` → `['25']` (D5).
   - Unit-only matrix `['22','23','24']` → `['26','27']` (D5).
   - The `jackson2` job pins JDK 21. Leave it for now; step 4.9 deletes it.
   - Update the header comment, which describes a "Java 21 LTS baseline".
5. `codeql.yml` and `release-to-maven-central.yml` already use 25. No change.
6. Check the tools that parse bytecode or run on the target release: dokka `2.2.0`, `maven-javadoc-plugin`, JaCoCo if
   present, ArchUnit `1.5.0` (reads class files, so it must understand version-69 classes), and byte-buddy/Mockito.
   Bump any that reject class-file version 69.
7. ArchUnit freeze stores: if the rule output changes because of JDK 25 bytecode, regenerate them and review the diff.
   Do not accept a regenerated store blindly.
8. Docs: root `CLAUDE.md` line 3 ("Java 21+ … compiled `--release 21`; build on JDK 21-25"), `README.md`, `LLM/LLM.md`,
   and the Initializr defaults in the `essentials:init` stack contract if they pin a JDK.

**Verify:** `mvn clean verify`. `javap -v` on one Java class and one Kotlin class shows `major version: 69`.

### Step 2 — Kotlin 2.4.20

1. `kotlin.version` 2.2.21 → 2.4.20.
2. **Management gotcha.** The root POM does not manage `kotlin-stdlib` / `kotlin-reflect` itself, so today they come
   from `spring-boot-dependencies`. That only worked because 4.0.8 manages the same 2.2.21. Boot 4.1.1 manages 2.3.21,
   so after this bump the compiler would be 2.4.20 while the stdlib resolved to 2.3.21. Import
   `org.jetbrains.kotlin:kotlin-bom:${kotlin.version}` **above** the `spring-boot-dependencies` import (the first import
   wins), and add a comment explaining why, next to the existing ordering rationale.
3. `kotlinLanguage.version` 2.2 → 2.3 and `kotlinApi.version` 2.1 → 2.3 (D4). Rewrite the comment block at
   `pom.xml:74-90`: its claim that "a Kotlin 2.1 compiler rejects these artifacts" at language 2.2 contradicts the
   measured one-minor-ahead rule, and its reason for keeping the API level one behind no longer applies. State the
   real floor: Kotlin 2.3, set by the JDK 25 target and matched by the language level. Note it in the release notes.
4. `jackson-module-kotlin`: Jackson 3's is `tools.jackson.module:jackson-module-kotlin`. Confirm the 3.1.5 module
   supports Kotlin 2.4 metadata. If it does not, this step must wait for a Jackson release, which is a real blocker to
   check early.
5. Fix new compiler warnings and errors in `types`, `types-spring-web`, `postgresql-event-store`,
   `kotlin-eventsourcing` and `postgresql-document-db`. Read the Kotlin 2.3 and 2.4 "what's new" and compatibility
   guides for deprecations that became errors.
6. Dokka `2.2.0`: confirm it runs with Kotlin 2.4, and bump it if not.

**Verify:** `mvn clean verify`, then `javap -v` on a Kotlin class: the `mv=[…]` tuple must match D4.
`mvn dependency:tree -Dincludes=org.jetbrains.kotlin` shows 2.4.20 everywhere.

### Step 3 — Spring Boot 4.1.1

1. `spring-boot.version` 4.0.8 → 4.1.1.
2. Moves in the managed dependencies (4.0.8 → 4.1.1) that land on us:

   | Library | 4.0.8 | 4.1.1 | Our action |
   |---|---|---|---|
   | Spring Data BOM | 2025.1.7 | 2026.0.1 | Check the `types-springdata-mongo` / `types-springdata-jpa` converters and the Mongo queue/lock against the Spring Data 2026.0 notes |
   | MongoDB driver | 5.6.5 | 5.8.1 | Mongo ITs |
   | Micrometer | 1.16.7 | 1.17.1 | Queue metrics and tracing wiring in the starters |
   | Spring Security | 7.0.7 | 7.1.1 | `spring-boot-starter-admin-api` / `admin-ui` |
   | Kafka | 4.1.2 | 4.2.1 | We pin `kafka-clients` 4.3.1 directly, and a direct entry wins. Keep it, or drop it if Boot has caught up |
   | Mockito | 5.20.0 | 5.23.0 | Now equal to our `mockito-bom.version`. Remove the pin |
   | byte-buddy | 1.17.8 | 1.18.11 | We pin 1.18.12. Keep it while it is still ahead, and re-check against `requireUpperBoundDeps` |
   | snakeyaml | 2.5 | 2.6 | Now equal. Remove the pin |
   | Jackson 3 / Jackson 2 | 3.1.5 / 2.21.5 | 3.1.5 / 2.21.5 | Unchanged |
   | Tomcat, Netty, Postgres JDBC, Testcontainers, JUnit | — | unchanged | Keep the CVE pins for Tomcat/Netty only if still ahead; `log4j` 2.26.1 is still ahead of 2.25.5 |

   Re-derive this table from `mvn dependency:tree` after the bump, as the root `CLAUDE.md` requires. It was produced
   from the BOM POMs, not from a resolved tree.
3. Read the Spring Boot 4.1 release notes for removed 4.0 deprecations and for auto-configuration or property renames.
   Our starters use `@ConditionalOnProperty(prefix = "management.tracing" …)`, so tracing property renames matter
   in particular.
4. Update `examples/*` and the `essentials:init` stack contract (S1–S11) if they pin a Boot version.
5. Check `admin-api-spec`'s swagger-core / swagger-parser / openapi-diff chain. It is independent of Boot, but still
   needs a compile check.

**Verify:** `mvn clean verify`, `mvn -Pjackson2 test` (the last time this is needed, as a confirmation that the bump
changed nothing serialization-related), and the `enforce-dependency-hygiene` execution passes.

### Step 4 — Drop Jackson 2

The largest step, and the only one that changes persisted-data behaviour. Split it into these commits, in this order.

**4.1 Freeze the wire format first.** Before deleting anything:
- `EssentialsObjectMappersWireFormatTest` (postgresql-event-store): make sure its golden document captures what
  Jackson 2 *wrote*, and that it holds representative cases: value types, `Map` keys typed by value types,
  `BigDecimal`, `java.time`, immutable payloads, event metadata and queue `MessageMetaData`. Once the Jackson 2 writer
  is gone, this golden file is the only guarantee that data persisted by 0.50 stays readable. The CI comment at
  `maven.yml:87-89` says the same.
- Do the same for `WalMessageFilterFlavorParityTest` (CDC) and `ActiveJacksonFlavorTest`: turn each dual-flavor
  assertion into a golden-input read under Jackson 3.
- Add golden documents for durable-queue payloads if none exist. The queue refactor persists `MessageMetaData`.

**4.2 Foundation JSON layer** (`components/foundation/.../json/`):
- Delete `JacksonJSONSerializer` (J2), `EssentialsObjectMappers.createJackson2ObjectMapper(…)`, and the flavor dispatch
  (`EssentialsJacksonModules.isJackson3Flavor()` and the 7 main-source call sites).
- `EssentialsJacksonModules`: remove the flavor-mismatch detection, or keep a trimmed version that fails if
  `types-jackson` from 0.50 is on the classpath. The trimmed version is recommended: it gives a clear error to
  consumers who half-upgrade.
- Durable-queue permanent-error classification. On `main`, `DefaultDurableQueueConsumer:592` checks
  `instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException`, so under the default Jackson 3 flavor a
  deserialization failure is **not** treated as permanent and gets retried. That is a live 0.50.x bug, worth its own
  patch on `main`. The queue branch's `MessageDeliveryClassifier` already fixes it by matching both FQCNs by name.
  Here, drop the `com.fasterxml` name from that set.
- `postgresql/MultiTableChangeListener`, `NotificationFilterChain`, `NotificationDuplicationFilter`: port from
  `com.fasterxml.jackson.databind.*` to `tools.jackson.databind.*`.

**4.3 Event store and queues.**
- `postgresql-event-store`: delete `JacksonJSONEventSerializer`, simplify `EssentialsJSONEventSerializers.createForActiveJacksonFlavor()`
  (keep the method, deprecated, delegating to the Jackson 3 factory, so test and consumer code still compiles), and
  port `cdc/filter/DefaultWalMessageFilter`.
- `postgresql-queue`: `DurableQueuesSerialization.createDefaultObjectMapper()` returns a J2 `ObjectMapper`. Change it
  to return `tools.jackson.databind.ObjectMapper`, which is a breaking signature change for the migration guide.
  Port `QueueNameDuplicationFilter`.
- `springdata-mongo-queue`: `MongoDurableQueues` builds a J2 `JsonMapper`. Port it.

**4.4 Spring Boot starters.** `spring-boot-starter-postgresql`, `-postgresql-event-store` and `-mongodb` define J2
`ObjectMapper` / `Module` beans. Replace them with Jackson 3 `JsonMapper` / `JacksonModule` beans, and check the
`@ConditionalOnMissingBean` types so that a consumer's Boot-provided `JsonMapper` still backs off correctly. Update
`@ConfigurationProperties` docs where they mention a flavor.

**4.5 Delete the modules.** Remove `types-jackson` and `immutable-jackson` from the reactor (D2). Delete the
`essentials.jackson.flavor`, `essentials.types-jackson.artifactId` and `essentials.immutable-jackson.artifactId`
properties, and replace every `${essentials.types-jackson.artifactId}` dependency with the literal `types-jackson3`
(`immutable-jackson3` likewise). This also retires the "flavor profile does not survive transitivity" gotcha.
Remove the `jackson2` and `jackson3` profiles. Deleting `jackson3` breaks any script that passes `-Pjackson3`; the
alternative is to keep it as an empty profile with a deprecation comment for one release. Recommended: delete it,
because 0.60 is the breaking release.

**4.6 Tests.** About 70 test files reference `com.fasterxml.jackson.databind`/`core` (eventsourced-aggregates 22,
postgresql-event-store 20, types-spring-web 8, types-jackson 6, and others). Port them to `tools.jackson`, or delete
them where they tested the J2 flavor only. Keep the rule that tests build serializers through
`EssentialsObjectMappers` / `EssentialsJSONEventSerializers`. `com.fasterxml.jackson.annotation.*` stays, because
Jackson 3 reuses that package.

**4.7 Remaining Jackson 2 on the classpath.** Dropping *support* does not remove Jackson 2 from every graph:
- `types-avro`: Avro itself depends on Jackson 2 databind.
- `admin-api-spec`: `EssentialsValueTypeModelConverter` implements swagger-core's `ModelConverter`, whose API is Jackson 2
  (`JavaType`). This module is build/test-only (`test` scope in the admin-api starter), so it is acceptable.
- Testcontainers / docker-java, in test scope.
- `types-spring-web`: declares `jackson-databind`, `jackson-datatype-*` and `jackson-module-kotlin` (J2). Remove them if
  they only served the J2 flavor.
- The examples and `essentials-performance-lab` (9 main-source files).

Run `mvn dependency:tree -Dincludes=com.fasterxml.jackson.core:jackson-databind` and classify each hit. Then add an
enforcer `bannedDependencies` rule that bans `com.fasterxml.jackson.core:jackson-databind` from the compile and runtime
scopes of every published module except those on an explicit allow-list (`types-avro`). Keep the direct J2
`dependencyManagement` entries for the artifacts that remain, for CVE hygiene, and trim the rest. The `jackson-bom`
import is inert anyway (see the root `CLAUDE.md`).

**4.8 Examples and perf lab.** Port `examples/essentials-performance-lab` and the `essentials-spring-examples` modules.
These are not released, but they must build.

**4.9 CI.** Delete the `jackson2` job in `maven.yml` (the comment there already authorizes it), but keep
`EssentialsObjectMappersWireFormatTest` (see 4.1).

**Verify:** `mvn clean verify`, the enforcer ban passes, and
`grep -rn 'com\.fasterxml\.jackson\.\(databind\|core\|datatype\|module\)' --include=*.java --include=*.kt` over released
modules returns only the allow-listed files. Also read a database written by 0.50.x: start the 0.50.0 trading demo,
write events, queue messages and snapshots, then boot the 0.60 build against the same database and replay. This is
the one check the golden files cannot fully replace.

### Step 5 — Documentation and release material

1. Root `CLAUDE.md`: rewrite line 3 (JDK), the command table (drop `mvn -Pjackson2 test`), and delete or collapse the
   gotchas that exist only because of dual flavor: "flavor profile does not survive transitivity",
   "Jackson-flavor-neutral test wiring", "Two Jackson flavors, one wire format" (keep its *wire format* half), and the
   J2 half of "Map keys keyed by a value type". Keep "Jackson 3 needs two per-type pins" and "constructor parameter
   name is part of the JSON contract". Those still apply.
2. Module `CLAUDE.md`s and `README.md`s. The largest are `types-spring-web`, `types-jackson3`,
   `postgresql-event-store`, `foundation`, `immutable-jackson3` and the example CLAUDE.md files. Delete
   `types-jackson/README.md` and `immutable-jackson/README.md` with their modules.
3. `LLM/*.md`: `LLM-types-integrations.md`, `LLM-types-jackson.md`, `LLM-immutable-jackson.md`,
   `LLM-types-spring-web.md`, `LLM-types.md`, `LLM.md` and `LLM-immutable.md`. Rename `LLM-types-jackson.md` only if
   it is renamed in `LLM/LLM.md`'s index at the same time.
4. `docs/durable-queues-breaking-refactor.md`: remove the "Both Jackson flavors" obligation, and add a cross-reference
   here.
5. Migration guide for 0.60 (the "larger document" the queue plan refers to). Merge it with `MIGRATION-NEXT_MAJOR.md`
   and add:
   - JDK 25 is the minimum.
   - Kotlin 2.3 is the minimum for Kotlin consumers (D4).
   - Spring Boot 4.1.x is required (4.0.x is not supported).
   - `types-jackson` / `immutable-jackson` → `types-jackson3` / `immutable-jackson3`. `com.fasterxml.jackson.databind`
     types in our signatures → `tools.jackson.databind`: `DurableQueuesSerialization.createDefaultObjectMapper()`,
     `EssentialsObjectMappers.createJackson2ObjectMapper()` (removed), `JacksonJSONSerializer` (removed),
     `JacksonJSONEventSerializer` (removed), and the starter bean types.
   - Consumers with `@JsonDeserialize(keyUsing=…)` from J2's annotation package: it stops applying, and nothing
     replaces it because `SingleValueTypeKeyDeserializers` already covers it.
   - Consumers with J2-only annotations (`com.fasterxml.jackson.databind.annotation.*`) on persisted types must move
     to the `tools.jackson.databind.annotation` equivalents.
   - The Jackson 3 constructor-parameter-name rule from the root `CLAUDE.md`, restated for consumers, because it
     now applies to everyone.
6. `docs/RELEASE-NOTES-0.60.0.md`.
7. `graphify update .` after the code changes.

## 4. Risks

| Risk | Mitigation |
|---|---|
| Data persisted by Jackson 2 in 0.50 no longer reads under 0.60 | Step 4.1 golden files first, plus the 0.50 → 0.60 database replay check in step 4 |
| Consumer depends on `types-jackson` and an unrelated library pulls Jackson 2 in | Trimmed `EssentialsJacksonModules` check (4.2) fails at startup with a message naming the fix |
| Kotlin stdlib silently resolves to Boot's 2.3.21 | `kotlin-bom` imported above Spring Boot (step 2.2); verified with `dependency:tree` |
| `jackson-module-kotlin` 3.1.5 lags Kotlin 2.4 | Checked early in step 2.4; if blocked, sequence Kotlin after the next Jackson 3 patch |
| A tool rejects class-file version 69 | Step 1.6 audit; the fix is a plugin bump |
| Merge conflicts with the queue branch | The `release/0.60` integration branch (§1); queue PRs merge into it before step 4 starts |
| Other branches (`queue_shard_owned`, `mssql_durable_queues`, `feature/non-transactional-message-handler`, `bigdecimal-numeric-attribute-converters`) target `main` and use Jackson 2 APIs | Decide per branch whether it targets 0.50.x (`main`) or 0.60 (`release/0.60`) before step 4, and rebase the 0.60 ones after it |
| Stale `target/` from the language server gives phantom failures | Known gotcha in the root `CLAUDE.md`: stop other builds, then `mvn clean install -pl <m> -am` |

## 5. Out of scope

- Adopting Java 25 language or library features (D6).
- The `forRemoval` constructor removals from `MIGRATION-NEXT_MAJOR.md`. That is separate 0.60 work, but it touches
  the same constructors in the starters, so schedule it after step 4.4 to avoid conflicts.
- The database schema harness (`docs/database-schema-harness.md`).
