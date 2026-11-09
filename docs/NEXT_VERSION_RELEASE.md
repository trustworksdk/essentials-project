# Essentials — Next Release (unreleased)

_Working draft. Collects every change since the last release tag, **`0.60.1`**, that a user of the framework,
the `essentials` plugin or the examples would notice. The version number is decided at release time; this file
then becomes `docs/RELEASE-NOTES-<version>.md` (same structure as
[`RELEASE-NOTES-0.60.1.md`](RELEASE-NOTES-0.60.1.md)) and is reset to an empty skeleton._

_Add an entry in the same commit as the change. Cite the commit or PR. Write it for the person upgrading: what
changes for them, and what they must do, if anything. Purely internal refactors, test-only changes and CI
plumbing that no user sees go in §7 at most, as one line._

_A bug-fix entry also says how to observe the fix: the call, and what it returns, throws or logs before the fix
and after it. A project that worked around the bug can then check whether its workaround is still needed and
remove it, instead of keeping it because nobody can tell._

**So far:** the Spring Boot starters register the tracing and queue metrics interceptors when the Micrometer beans
are present, not only with `management.tracing.enabled=true`, which Spring Boot 4 no longer reads (§1.1.1); plugin
`0.60.1-1` and `0.60.1-10` (§4).

---

## Table of contents

1. [Breaking — what you MUST apply](#1-breaking--what-you-must-apply)
   - [1.1 ⚠️ Silent behaviour changes](#11-️-silent-behaviour-changes)
2. [New features](#2-new-features)
3. [Bug fixes](#3-bug-fixes)
4. [The `essentials` Claude Code plugin](#4-the-essentials-claude-code-plugin)
5. [Examples](#5-examples)
6. [Documentation](#6-documentation)
7. [Build and tooling](#7-build-and-tooling)

---

## 1. Breaking — what you MUST apply

None so far.

### 1.1 ⚠️ Silent behaviour changes

#### 1.1.1 Tracing and queue metrics interceptors follow the Micrometer beans, not `management.tracing.enabled` (Spring Boot starters)

The PostgreSQL and MongoDB starters registered `DurableQueuesMicrometerTracingInterceptor` and
`DurableQueuesMicrometerInterceptor`, and the event store starter `MicrometerTracingEventStoreInterceptor`, only with
`management.tracing.enabled=true`. Spring Boot 4 replaced that property with `management.tracing.export.enabled` and no
longer reads it, so an application that moved to the new property lost all three. A durable queue consumer then ran
without the enqueuer's trace context (no trace or span id in its logs), and the per-queue counters and gauges
(`DurableQueues_QueuedMessages_Processed`, `…_Handled`, `…_Retries`, `DurableQueues_DeadLetterMessages_Processed` and the
queued/dead-letter gauges) were missing. This was also the case in 0.50.0.

They are now registered by what is present:

- the two tracing interceptors when a Micrometer `Tracer`, `Propagator` and `ObservationRegistry` bean are all present;
- `DurableQueuesMicrometerInterceptor` whenever a `MeterRegistry` bean is present, independent of tracing.

Each has its own switch, default `true`: `essentials.durable-queues.tracing-enabled`,
`essentials.durable-queues.queue-metrics-enabled` and `essentials.eventstore.tracing-enabled`. The starters are also
ordered after Spring Boot's Micrometer auto-configurations, so these checks, and the existing one for
`MicrometerDurableQueueMessageObserver`, see the beans Spring Boot creates.

`management.tracing.export.enabled` is not read: whether spans are exported is Spring Boot's concern. Note that with the
OpenTelemetry bridge and export disabled, Spring Boot itself installs a no-op propagator, so the trace context is then not
carried across a queue, just as for HTTP and Kafka.

**How to observe it:** with `management.tracing.export.enabled=true` and no `management.tracing.enabled`, a message
enqueued inside a span used to be handled with no trace or span id in the consumer's MDC, and
`/actuator/metrics/DurableQueues_QueuedMessages_Processed` answered 404. Now the consumer logs the enqueuer's trace id
and the metric is present.

**What to do:** remove a `management.tracing.enabled: true` kept only for Essentials. An application with tracing or a
`MeterRegistry` but without that property gets the interceptors now; if you do not want them, set the matching switch
above to `false`.

---

## 2. New features

None so far.

---

## 3. Bug fixes

| Area | Fix | Commit |
|---|---|---|
| Tracing and queue metrics (Spring Boot starters) | `DurableQueuesMicrometerTracingInterceptor`, `DurableQueuesMicrometerInterceptor` and `MicrometerTracingEventStoreInterceptor` were registered only with `management.tracing.enabled=true`, which Spring Boot 4 no longer reads - see §1.1.1. They now follow the Micrometer beans, with an opt-out switch each | - |

---

## 4. The `essentials` Claude Code plugin

0.60.1 shipped plugin `0.60.1`. Plugin releases that follow it are listed here, newest first;
the full entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

- **0.60.1-10** — the LLM docs describe when the starters register the tracing and queue metrics interceptors, and
  their opt-out switches (§1.1.1).
- **0.60.1-1** — `/essentials:slice-check` grades findings by consequence and names their impact. Gate 8 and new
  write-site facts run in `slice-source.py`. A project-owned `slice-check-accepted.yaml` records settled deviations.
  `--fail-on` lets CI gate on a severity. The law gains sections on cross-context writes, dual writes and
  `DocumentDbRepository` read sides. `/essentials:upgrade` checks that the pre-commit hook is executable and runs,
  prefers the forwarder when other tools own hooks, and lists the views that will log the 0.60.1
  unhandled-event-types warning.

---

## 5. Examples

Not published; listed because they are the reference for how to use the framework.

---

## 6. Documentation

None so far.

---

## 7. Build and tooling

None so far.
