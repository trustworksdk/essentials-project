---
paths:
  - "**/*Test.java"
  - "**/*IT.java"
  - "**/*Test.kt"
  - "**/*IT.kt"
---

# Test Conventions

- **Suffix = scope**: `*Test.java` / `*Test.kt` = unit (no Docker, surefire/`mvn test`). `*IT.java` / `*IT.kt` = integration (Docker/Testcontainers, failsafe/`mvn verify`). Suffix drives which Maven plugin runs it — name correctly.
- **Stack** — JUnit 5 (`org.junit.jupiter`), AssertJ (`assertThat`), Awaitility for async polling. Mockito used sparingly — prefer real objects.
- **Integration tests** — annotate `@Testcontainers`; container field `@Container`. PostgreSQL → `PostgreSQLContainer`; Mongo → replica-set container.
- **`@Container` fields are `static`** — an *instance* `@Container` field makes JUnit start and stop a fresh container for every test **method**, not per class. That was ~300 container starts across the suite where ~97 were needed. A static container means state now survives between methods, so the class needs a `@BeforeEach` that resets what it touches (`DROP TABLE IF EXISTS …`, `resetQueueStorage`) — and anything the test opens per method (Hikari pools especially) must be **closed in `@AfterEach`**, or the shared container runs out of `max_connections` part-way through the class.
- **Never use a floating image tag.** `postgres:latest` silently changes the database major version underneath the suite. Modules that depend on `foundation-test` build containers via `EssentialsTestContainers.postgres(...)` / `EssentialsTestContainers.MONGO_IMAGE`; modules that cannot depend on it (`components/foundation` would form a reactor cycle, and the core `types-*` modules sit above it) pin the same explicit tag inline. Bump versions in `EssentialsTestContainers` first, then the inline pins and the CI pre-pull step in `.github/workflows/maven.yml`.
- **`ImageFromDockerfile` needs a stable name and `deleteOnExit=false`** — an anonymous one is rebuilt and orphaned on every use. The CDC ITs left ~1900 dangling ~640 MB images behind before this was fixed; see `AbstractLogicalReplicationPostgresIT.WAL2JSON_IMAGE`. Bump its tag whenever the Dockerfile changes, or the stale image is reused.
- **Cap async waits** — Awaitility `atMost` should be seconds, not `Duration.ofSeconds(2000)`; an unbounded wait turns a hang into a 30-minute CI timeout instead of a fast failure. Prefer waiting on the condition over a fixed `Thread.sleep`.
- **Benchmarks are opt-in** — throughput suites gate on `@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")` (and/or `@Disabled`). Use that existing idiom rather than inventing a second mechanism.
- **Reusable abstract base ITs** — cross-DB test suites live in `foundation-test`, parameterized by impl type (e.g. `DurableQueuesIT<Q,UOW,UOWF>`); each DB module extends the base (e.g. `PostgresqlDurableQueuesIT extends DurableQueuesIT<…>`). Add shared test logic to the base, not per-module copies.
- **Fixtures** — test domain types (events, IDs) go in a `test_data/` subpackage of the module's test sources.
- **Recording collections must be concurrent** — anything a subscriber, poller or event-bus handler writes into while the test thread reads it (`asynchronousOrderEventsReceived`, `RecordingLocalEventBusConsumer`, recording message handlers) is a `CopyOnWriteArrayList`, never `ArrayList`. Two reasons: a plain `ArrayList` is unsafely published across threads, and `ArrayList.equals` checks `modCount` on **both** sides, so `assertThat(a).isEqualTo(b)` throws `ConcurrentModificationException` if *either* list is still being appended to. Both sides of such a comparison have to be concurrent — converting only the async one is not enough.
- **Test method names** — Java: descriptive snake_case (`test_a_known_country_code_can_be_converted`). Kotlin: backtick sentences (`` fun `should add amounts correctly`() ``).
