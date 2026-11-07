---
name: run-trading-demo
description: "Launch and drive the essentials-trading-demo Spring Boot app to see a change working end-to-end against a real PostgreSQL — snapshots, closing books, generation lifecycle, admin API and admin console. Use when asked to run, start, smoke-test or verify the demo app, or to confirm event-store / eventsourced-aggregates / starter changes behave at runtime rather than only in tests."
---

# Running the essentials-trading-demo

The demo is the repo's runtime smoke test for `postgresql-event-store`, `eventsourced-aggregates` and the
Spring Boot starters. It seeds three trading accounts, each left in a different closing-books state, and exposes both a
JSON admin API and a server-rendered admin console.

Verified working 2026-08-13 in this devcontainer.

## 1. Install any component module you changed

The demo is a reactor module (`examples/essentials-trading-demo` in the root `pom.xml`), but the launch command below
uses `-pl` **without** `-am`, so its dependencies resolve from `~/.m2`, not from your working tree. A change in
`components/…` that has not been installed is silently invisible to the running app.

```bash
JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64 mvn -q -o \
  -pl components/eventsourced-aggregates -am \
  -DskipTests -DskipDependencyCheck=true install
```

Substitute the module you touched. Skip this step only when your change is inside the demo itself.

## 2. Start PostgreSQL

The `compose` profile activates Spring Boot's Docker Compose support, which starts
`examples/essentials-trading-demo/src/main/resources/compose.yml` automatically. Check first — the container is often
already up from an earlier session, and Spring Boot then reuses it:

```bash
docker ps --format '{{.Names}}\t{{.Status}}' | grep essentials-trading-demo-postgresql
```

It publishes 5432, database `essentials-trading-demo`, user/password `essentials`/`password`.

**An existing database is an asset, not a problem.** The bootstrap runner detects its own seed data and skips
re-seeding, so rows written by *older* builds stay in `aggregate_generations` and the event tables. That makes an
already-populated database the best available test of persisted-format compatibility: if the new code reads those rows
and writes new ones that the same queries still find, the format survived.

## 3. Launch

From the repo root. `JAVA_HOME` must be overridden — the inherited one is broken in this container — and `-o` keeps
Maven off the network:

```bash
JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64 nohup mvn -o \
  -pl examples/essentials-trading-demo \
  -DskipDependencyCheck=true \
  -Dspring-boot.run.profiles=compose spring-boot:run > /tmp/demo.log 2>&1 &
```

Startup is fast — roughly 2-3 seconds to `Started TradingDemoApplication` once the JVM is up, plus Maven's own
overhead. Give it about 60 seconds total, then confirm:

```bash
grep -E "Started TradingDemoApplication|Tomcat started" /tmp/demo.log
grep -cE " ERROR |Exception|Caused by" /tmp/demo.log   # expect 0
```

A clean run logs zero errors. Treat any non-zero count as a failure to investigate, not noise.

## 4. Drive it

Two API surfaces, and they are not interchangeable — check whichever one your change sits behind.

**Framework admin API** (`components/spring-boot-starter-admin-api`), base path `/api/essentials/admin/v1`. Not
documented in the demo README; there is no `/v3/api-docs`. Paths come from
`components/admin-api-spec/.../EssentialsAdminApiSpec.java` and the controllers in
`components/spring-boot-starter-admin-api/.../rest/`.

```bash
B=localhost:8080/api/essentials/admin/v1/aggregate-lifecycle

# Policy registration — reports nothing if policies never reached the registries
curl -s $B/closing-books-policies

# Backed by TypedAggregateClosingBooksGenerationAccess.loadGenerations(String)
curl -s "$B/aggregate-types/TradingAccounts/logical-aggregates/ACC-DEMO-001/closing-books-generations"

# Backed by TypedAggregateClosingBooksGenerationAccess.resolveCurrentGeneration(String)
curl -s "$B/aggregate-types/TradingAccounts/logical-aggregates/ACC-DEMO-001/closing-books-generations/current"

# Rollover counters, timings and outcome
curl -s localhost:8080/api/essentials/admin/v1/aggregate-lifecycle-statistics/closing-books
```

**Demo's own API**, base path `/api/admin` — listed in the demo README. Useful for aggregate state and for forcing
activity:

```bash
curl -s localhost:8080/api/admin/trading-accounts/ACC-DEMO-001      # balances, current generation, all generations
curl -s localhost:8080/api/admin/trading-accounts/closing-books     # effective policy configuration
curl -s -X POST "localhost:8080/api/admin/load-generator/comparisons/trading-account?count=90&readPasses=25&eventThreshold=20"
```

The three seeded accounts differ by design: `ACC-DEMO-001` is rolled by the **policy** with no application
involvement and is the only one with snapshots, `ACC-DEMO-002` by an **explicit command**, `ACC-DEMO-003` not at all.
Pick the one that exercises your change.

**The write path exercises itself.** `TradingLoadGeneratorManager` starts automatically at boot
(`tradeInterval=PT2S`, `priceUpdateInterval=PT1S`) and writes continuously. Since `TradingAccounts` uses
`triggerMode = ON_ACCESS` with an event-count threshold of 100, that background traffic crosses the threshold on its
own and the policy rolls the generation without anything being asked of it — observed roughly 80 seconds after startup:

```
TradingAccount 'ACC-DEMO-001' triggered automatic closing-books rollover using policy
'event-count threshold 100 or time-boundary end-of-month in zone Europe/Copenhagen'. Current generation=8
```

So to verify the write path, wait a couple of minutes and re-read the generations list: a newly opened generation
stamped with today's date, sitting next to generations written by earlier builds and found by the same lookup, is the
compatibility check passing. The `load-generator` endpoints above only turn the volume up; they are not needed to make
a rollover happen.

## 5. The console

`GET /admin` serves the Thymeleaf + vanilla JS console (roughly 37KB), which fetches everything from the endpoints
above.

**There is no Chrome in this container** (`/opt/google/chrome/chrome` is absent), so the Playwright MCP tools cannot
drive it and installing a browser for one screenshot is rarely worth it. Verify the console by confirming the page
renders and then asserting on the data its panels consume:

```bash
curl -s -o /tmp/admin.html -w "%{http_code}\n" localhost:8080/admin
grep -oiE "closing[- ]books[^<\"]{0,40}" /tmp/admin.html | sort -u
```

If a change is genuinely visual, say that a browser was unavailable rather than implying the UI was inspected.

## 6. Shut down

```bash
pkill -f "spring-boot:run"; sleep 3; pkill -f "TradingDemoApplication"
```

Leave the PostgreSQL container running if you found it running — its accumulated data is what makes the next
compatibility check meaningful.
