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
- **Reusable abstract base ITs** — cross-DB test suites live in `foundation-test`, parameterized by impl type (e.g. `DurableQueuesIT<Q,UOW,UOWF>`); each DB module extends the base (e.g. `PostgresqlDurableQueuesIT extends DurableQueuesIT<…>`). Add shared test logic to the base, not per-module copies.
- **Fixtures** — test domain types (events, IDs) go in a `test_data/` subpackage of the module's test sources.
- **Test method names** — Java: descriptive snake_case (`test_a_known_country_code_can_be_converted`). Kotlin: backtick sentences (`` fun `should add amounts correctly`() ``).
