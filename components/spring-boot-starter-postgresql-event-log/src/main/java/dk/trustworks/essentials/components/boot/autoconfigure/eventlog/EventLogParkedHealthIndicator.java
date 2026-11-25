/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dk.trustworks.essentials.components.boot.autoconfigure.eventlog;

import dk.trustworks.essentials.components.eventlog.*;
import org.slf4j.*;
import org.springframework.boot.health.contributor.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Reports the parked heads of every event-log subscription on {@code /actuator/health}, from one
 * {@link EventLogAdmin#parkedSummary()} statement: the cluster's view, whichever node runs each subscription.
 *
 * <h2>Status</h2>
 * <ul>
 *     <li>No parked head, or only <em>retrying</em> ones: {@link Status#UP}. The engine retries those by itself, so they
 *     heal without an operator; the details still list them.</li>
 *     <li>At least one <em>terminal</em> head (one that failed with {@link ParkEventException}, so only an operator can
 *     retry or discard it): the configured status, {@code essentials.event-log.health.terminal-parked-status}, by default
 *     {@link Status#DOWN}.</li>
 *     <li>The summary cannot be read: {@link Status#DOWN} with the exception, as the other Essentials health indicators
 *     report a failing check.</li>
 * </ul>
 *
 * <h2>Keep it out of liveness and readiness</h2>
 * A terminal park is a data problem, not a sick instance: restarting the pod, or taking it out of traffic, does not
 * release the parked event, and every replica reports the same rows. Do not add {@code eventLogParked} to the
 * {@code management.endpoint.health.group.liveness} or {@code .readiness} groups (Spring Boot's probe groups include only
 * the {@code livenessState} / {@code readinessState} indicators unless configured otherwise); point alerting at the
 * composite {@code /actuator/health} or at this indicator instead. If the composite endpoint itself is a probe, set the
 * status to one the probe ignores, or switch the indicator off with {@code management.health.eventLogParked.enabled=false}.
 *
 * <h2>Caching</h2>
 * Probes poll on a timer, from every pod, so the result (a failure included) is reused for
 * {@code essentials.event-log.health.cache-ttl} (default 10 s) and the statement runs at most once per period.
 */
public final class EventLogParkedHealthIndicator implements HealthIndicator {
    /** Terminal heads across every subscription */
    public static final String DETAIL_TOTAL_TERMINAL_HEADS = "totalTerminalHeads";
    /** Retrying heads across every subscription */
    public static final String DETAIL_TOTAL_RETRYING_HEADS = "totalRetryingHeads";
    /** The status reported while a terminal head exists */
    public static final String DETAIL_TERMINAL_PARKED_STATUS = "terminalParkedStatus";
    /** One entry per subscription with a parked head, keyed by the {@code SUBSCRIPTION_*} keys below */
    public static final String DETAIL_SUBSCRIPTIONS = "subscriptions";

    /** The subscriber name */
    public static final String SUBSCRIPTION_SUBSCRIBER = "subscriber";
    /** The aggregate type */
    public static final String SUBSCRIPTION_AGGREGATE_TYPE = "aggregateType";
    /** The instance name, empty for an exclusive subscription */
    public static final String SUBSCRIPTION_INSTANCE_NAME = "instanceName";
    /** Parked heads only an operator can release */
    public static final String SUBSCRIPTION_TERMINAL_HEADS = "terminalHeads";
    /** Parked heads the engine retries by itself */
    public static final String SUBSCRIPTION_RETRYING_HEADS = "retryingHeads";
    /** When the oldest parked head was parked (ISO-8601) */
    public static final String SUBSCRIPTION_OLDEST_PARKED_AT = "oldestParkedAt";
    /** The earliest due automatic retry (ISO-8601), absent without a retrying head */
    public static final String SUBSCRIPTION_NEXT_RETRY_AT = "nextRetryAt";
    /** When a parked head last failed (ISO-8601), absent if none has */
    public static final String SUBSCRIPTION_LAST_FAILED_AT = "lastFailedAt";
    /** The error type of the terminal head that failed last, absent without a terminal head */
    public static final String SUBSCRIPTION_LAST_TERMINAL_ERROR_TYPE = "lastTerminalErrorType";
    /** Its error message, absent without one */
    public static final String SUBSCRIPTION_LAST_TERMINAL_ERROR_MESSAGE = "lastTerminalErrorMessage";

    private static final Logger log = LoggerFactory.getLogger(EventLogParkedHealthIndicator.class);

    private final EventLogAdmin admin;
    private final Status        terminalParkedStatus;
    private final long          cacheTtlNanos;

    private final AtomicReference<CachedHealth> cachedHealth = new AtomicReference<>();

    /**
     * @param admin                the admin of the event log whose parked heads are reported ({@code eventLog.admin()})
     * @param terminalParkedStatus the status reported while any subscription has a terminal parked head
     * @param cacheTtl             how long a result is reused before the summary is read again; {@link Duration#ZERO} reads
     *                             on every call. Must not be negative
     */
    public EventLogParkedHealthIndicator(EventLogAdmin admin, Status terminalParkedStatus, Duration cacheTtl) {
        this.admin = requireNonNull(admin, "No admin provided");
        this.terminalParkedStatus = requireNonNull(terminalParkedStatus, "No terminalParkedStatus provided");
        requireNonNull(cacheTtl, "No cacheTtl provided");
        requireTrue(!cacheTtl.isNegative(), "cacheTtl must not be negative");
        this.cacheTtlNanos = cacheTtl.toNanos();
    }

    @Override
    public Health health() {
        var now    = System.nanoTime();
        var cached = cachedHealth.get();
        if (cached != null && now - cached.computedAtNanos() < cacheTtlNanos) {
            return cached.health();
        }
        var health = computeHealth();
        cachedHealth.set(new CachedHealth(health, now));
        return health;
    }

    private Health computeHealth() {
        List<ParkedSummary> summaries;
        try {
            summaries = admin.parkedSummary();
        } catch (Exception e) {
            log.debug("Could not read the event log's parked summary for the health indicator", e);
            return Health.down(e).build();
        }

        var  subscriptions = new ArrayList<Map<String, Object>>(summaries.size());
        long terminal      = 0;
        long retrying      = 0;
        for (var summary : summaries) {
            terminal += summary.terminalHeads();
            retrying += summary.retryingHeads();
            subscriptions.add(describe(summary));
        }
        return Health.status(terminal > 0 ? terminalParkedStatus : Status.UP)
                     .withDetail(DETAIL_TERMINAL_PARKED_STATUS, terminalParkedStatus.getCode())
                     .withDetail(DETAIL_TOTAL_TERMINAL_HEADS, terminal)
                     .withDetail(DETAIL_TOTAL_RETRYING_HEADS, retrying)
                     .withDetail(DETAIL_SUBSCRIPTIONS, subscriptions)
                     .build();
    }

    private static Map<String, Object> describe(ParkedSummary summary) {
        var details = new LinkedHashMap<String, Object>();
        details.put(SUBSCRIPTION_SUBSCRIBER, summary.key().subscriber());
        details.put(SUBSCRIPTION_AGGREGATE_TYPE, summary.key().aggregateType());
        details.put(SUBSCRIPTION_INSTANCE_NAME, summary.key().instanceName());
        details.put(SUBSCRIPTION_TERMINAL_HEADS, summary.terminalHeads());
        details.put(SUBSCRIPTION_RETRYING_HEADS, summary.retryingHeads());
        details.put(SUBSCRIPTION_OLDEST_PARKED_AT, summary.oldestParkedAt().toString());
        summary.nextRetryAt().ifPresent(at -> details.put(SUBSCRIPTION_NEXT_RETRY_AT, at.toString()));
        summary.lastFailedAt().ifPresent(at -> details.put(SUBSCRIPTION_LAST_FAILED_AT, at.toString()));
        summary.lastTerminalErrorType().ifPresent(type -> details.put(SUBSCRIPTION_LAST_TERMINAL_ERROR_TYPE, type));
        summary.lastTerminalErrorMessage().ifPresent(message -> details.put(SUBSCRIPTION_LAST_TERMINAL_ERROR_MESSAGE, message));
        return details;
    }

    @Override
    public String toString() {
        return "EventLogParkedHealthIndicator{terminalParkedStatus=" + terminalParkedStatus + "}";
    }

    private record CachedHealth(Health health, long computedAtNanos) {
    }
}
