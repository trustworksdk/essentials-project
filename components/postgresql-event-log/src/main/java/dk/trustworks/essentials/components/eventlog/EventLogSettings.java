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
package dk.trustworks.essentials.components.eventlog;

import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Settings of the event log's subscriptions (design {@code docs/plans/event-store-engine-phase2.md} §1.2, §3.3, §3.4).
 *
 * @param backstopInterval     tier-0 wake-up: how long a caught-up tail reader parks before it reads again (plan §11).
 *                             Wake-ups ({@link EventLog#afterCommit}) only shorten it (I7)
 * @param tailPageSize         most rows one tail read returns above the cursor; late commits re-read from holes do
 *                             not count against it
 * @param probeBackoffMin      least time between two re-probes while a candidate is outstanding and nothing new was
 *                             read; never below {@code backstopInterval}'s role as the only wake-up, see
 *                             {@link #probeWait(long, int)}
 * @param probeBackoffMax      most time between two such re-probes, however expensive the probe was
 * @param progressSaveInterval least time between two periodic resume-point saves of one subscription; zero saves
 *                             after every handled event
 * @param holeRangeThreshold   a gap of more than this many values (a sequence jump) is kept as one range and re-read
 *                             by the range branch, instead of as single values in the {@code = ANY} branch
 * @param genericPlans         run the tail reader's statements under {@code plan_cache_mode = force_generic_plan} with
 *                             server-side prepared statements, except those whose generic plan is not index-only
 *                             (P0.16; {@code EventLogGenericPlanIT} checks them). {@code false} plans every read custom
 * @param subscriptionBufferSize most events a subscription's inbox holds before the next batch overflows it and the
 *                             subscription detaches (phase 3 §2.3); at least {@code tailPageSize}. An empty inbox
 *                             accepts any batch
 * @param catchUpPageSize      most rows one catch-up or bridge statement returns (phase 3 §3.3, §3.4)
 * @param pageSize             most events one page transaction of an in-transaction subscription applies (phase 4
 *                             §3.2; P0.13: no gain above about 100 row by row)
 * @param retryAttempts        how many times an event that fails alone is retried before it is parked (phase 4 §4.3);
 *                             zero parks it on its first failure alone
 * @param retryBackoffInitial  the sleep before the first retry of an event that failed alone, doubled before each further
 *                             retry; also the first wait after an I/O failure, which is retried indefinitely (phase 4 §4.1)
 * @param retryBackoffMax      the most any such sleep or wait lasts
 * @param catchUpPermits       most catch-up and bridge statements in flight at once per database per JVM, shared by every
 *                             {@code EventLog} on that database (phase 5 §4); the first event log on a database sizes it
 * @param catchUpScanWindow    the first order window a filtered catch-up read scans (phase 5 §3.2); doubled after each
 *                             short page, quartered after a cancelled statement
 * @param catchUpScanWindowMax the largest such window
 * @param eventTypeIndexed     aggregate types that get the opt-in {@code (event_type, global_order)} index (phase 5 §5)
 * @param tenantIndexed        aggregate types that get the opt-in {@code (tenant, global_order)} index (phase 8 §15
 *                             question 7): built like the event-type index; it serves the skip-ahead probe of a
 *                             tenant-filtered catch-up
 * @param wokenProbeMin        least time between two running-set reads of an aggregate type that was woken since its last
 *                             one (phase 6 §5): the cost rule of {@link #probeWait(long, int)} without the backstop floor
 * @param crossJvmWakeUp       tier 1 (phase 6 §3): {@link EventLog#afterCommit} also sends a coalesced {@code NOTIFY} on a
 *                             borrowed connection, and while any subscription exists one listener thread holds one
 *                             {@code LISTEN} connection that wakes this event log's readers on other event logs' commits.
 *                             {@code false}: neither runs, and only the same event log's commits (tier 2) and the
 *                             backstop wake a reader
 * @param wokenReadMin         least time from the start of an aggregate type's last read to the start of a read a wake
 *                             asks for (phase 6 §10.8, wake coalescing): a wake inside it defers the read to its end, and
 *                             every wake that arrives meanwhile is served by that one read. Never drops a wake, never
 *                             delays a read the backstop, a full page or a probe makes due, and never defers past the
 *                             backstop. Costs at most this much commit-to-handler latency. Zero reads at once on every wake
 * @param notifySendMin        least time between the starts of two {@code NOTIFY} sends of one event log's notifier (tier 1,
 *                             phase 6 §10.8): the aggregate types committed inside it are unioned into the next send. Nothing
 *                             pending is dropped; a stop sends what is pending at once. Costs other event logs at most this
 *                             much latency. Zero sends as soon as the previous send returned
 * @param leaseDuration        how long a claim or renewal of an exclusive subscription's lease lasts, judged by the
 *                             database's clock (phase 7 §2, §6); greater than {@code leaseLocalStop + leaseRenewalInterval}
 * @param leaseRenewalInterval how often the event log's lease keeper renews every owned lease, and tries to claim every
 *                             standby one, in one statement each
 * @param leaseLocalStop       how long after the start of its last confirmed renewal an owner keeps delivering (the local
 *                             deadline, §2.4); at least {@code leaseRenewalInterval}, less than {@code leaseDuration}
 * @param leaseStatementTimeout the query timeout of the keeper's statements, whole seconds (pgjdbc's granularity), at least
 *                             one second and at most {@code leaseLocalStop}
 * @param parkedCheckInterval  phase 8 §4.3: how often a subscription that holds at least one aggregate reads the retry and
 *                             discard requests stored on its parked heads by an operator on any node; {@link Duration#ZERO}
 *                             (the default) means {@code backstopInterval}. A subscription that holds no aggregate reads
 *                             nothing
 * @param adminListLimit       phase 8 §4.1: the most rows one {@link EventLogAdmin#subscriptions} listing returns
 * @param nodeName             free text (at most 64 characters) prefixed to the lease owner id ({@code "<nodeName>/<id>"}),
 *                             for operators; empty for none
 */
public record EventLogSettings(Duration backstopInterval,
                               int tailPageSize,
                               Duration probeBackoffMin,
                               Duration probeBackoffMax,
                               Duration progressSaveInterval,
                               int holeRangeThreshold,
                               boolean genericPlans,
                               int subscriptionBufferSize,
                               int catchUpPageSize,
                               int pageSize,
                               int retryAttempts,
                               Duration retryBackoffInitial,
                               Duration retryBackoffMax,
                               int catchUpPermits,
                               long catchUpScanWindow,
                               long catchUpScanWindowMax,
                               java.util.Set<String> eventTypeIndexed,
                               java.util.Set<String> tenantIndexed,
                               Duration wokenProbeMin,
                               boolean crossJvmWakeUp,
                               Duration wokenReadMin,
                               Duration notifySendMin,
                               Duration leaseDuration,
                               Duration leaseRenewalInterval,
                               Duration leaseLocalStop,
                               Duration leaseStatementTimeout,
                               String nodeName,
                               Duration parkedCheckInterval,
                               int adminListLimit) {
    /**
     * The probes of the whole cluster may take at most about 1 % of one core while candidates are outstanding: one
     * reader's probe costs at most 1 % of its wait ({@code wait >= cost * 100}), divided by the number of readers that
     * probe the same server (P0.12: the running-set read costs about 0.55-1.4 µs per live backend, much more under
     * write load, and every reader of every JVM pays it)
     */
    static final int PROBE_COST_MULTIPLIER = 100;

    /**
     * How many backends the back-off assumes per probing reader when it estimates the cluster's readers from the
     * backend count: P0.12's topology, a JVM with a pool of 10 and one tail reader. Phase-2 unit C measured that
     * {@code cost * 100} alone (no backstop floor) let 40 readers at 400 connections take 29 % of a core, and the 1 s
     * backstop floor alone 6.4 %; scaled by {@code backends / 10} it stays under 1 %.
     */
    static final int BACKENDS_PER_READER = 10;

    /**
     * {@link #wokenReadMin()} of {@link #defaults()}: off. Phase 6 §10.8 measured 2 and 5 ms: at 100 ev/s the commits are
     * 10 ms apart, so neither interval merges a single read, and neither brings §7.1's statements per delivered event
     * near the backstop-only figure; nothing is gained for the latency it costs under bursts
     */
    static final Duration DEFAULT_WOKEN_READ_MIN  = Duration.ZERO;
    /**
     * {@link #notifySendMin()} of {@link #defaults()}: the smallest measured interval (phase 6 §10.8) that cuts the
     * notifier's sends at 8 and 20 writers from about 10 000 to about 490 a second and keeps §7.2's append cost inside 10 %
     */
    static final Duration DEFAULT_NOTIFY_SEND_MIN = Duration.ofMillis(2);

    public EventLogSettings {
        requirePositive(backstopInterval, "backstopInterval");
        requireTrue(tailPageSize > 0, "tailPageSize must be > 0");
        requirePositive(probeBackoffMin, "probeBackoffMin");
        requirePositive(probeBackoffMax, "probeBackoffMax");
        requireTrue(probeBackoffMax.compareTo(probeBackoffMin) >= 0, "probeBackoffMax must be >= probeBackoffMin");
        requireNonNull(progressSaveInterval, "No progressSaveInterval provided");
        requireTrue(!progressSaveInterval.isNegative(), "progressSaveInterval must not be negative");
        requireTrue(holeRangeThreshold > 0, "holeRangeThreshold must be > 0");
        requireTrue(subscriptionBufferSize >= tailPageSize, "subscriptionBufferSize must be >= tailPageSize");
        requireTrue(catchUpPageSize > 0, "catchUpPageSize must be > 0");
        requireTrue(pageSize > 0, "pageSize must be > 0");
        requireTrue(retryAttempts >= 0, "retryAttempts must be >= 0");
        requirePositive(retryBackoffInitial, "retryBackoffInitial");
        requirePositive(retryBackoffMax, "retryBackoffMax");
        requireTrue(retryBackoffMax.compareTo(retryBackoffInitial) >= 0, "retryBackoffMax must be >= retryBackoffInitial");
        requireTrue(catchUpPermits > 0, "catchUpPermits must be > 0");
        requireTrue(catchUpScanWindow >= catchUpPageSize, "catchUpScanWindow must be >= catchUpPageSize");
        requireTrue(catchUpScanWindowMax >= catchUpScanWindow, "catchUpScanWindowMax must be >= catchUpScanWindow");
        eventTypeIndexed = java.util.Set.copyOf(requireNonNull(eventTypeIndexed, "No eventTypeIndexed provided"));
        tenantIndexed = java.util.Set.copyOf(requireNonNull(tenantIndexed, "No tenantIndexed provided"));
        requirePositive(wokenProbeMin, "wokenProbeMin");
        requireNotNegative(wokenReadMin, "wokenReadMin");
        requireNotNegative(notifySendMin, "notifySendMin");
        requirePositive(leaseDuration, "leaseDuration");
        requirePositive(leaseRenewalInterval, "leaseRenewalInterval");
        requirePositive(leaseLocalStop, "leaseLocalStop");
        requirePositive(leaseStatementTimeout, "leaseStatementTimeout");
        requireTrue(leaseLocalStop.compareTo(leaseRenewalInterval) >= 0, "leaseLocalStop must be >= leaseRenewalInterval");
        requireTrue(leaseLocalStop.compareTo(leaseDuration) < 0, "leaseLocalStop must be < leaseDuration");
        requireTrue(leaseDuration.compareTo(leaseLocalStop.plus(leaseRenewalInterval)) > 0, "leaseDuration must be > leaseLocalStop + leaseRenewalInterval");
        requireTrue(leaseStatementTimeout.toNanos() % 1_000_000_000L == 0, "leaseStatementTimeout must be whole seconds");
        requireTrue(leaseStatementTimeout.compareTo(leaseLocalStop) <= 0, "leaseStatementTimeout must be <= leaseLocalStop");
        requireNonNull(nodeName, "No nodeName provided");
        requireTrue(nodeName.length() <= 64, "nodeName must be at most 64 characters");
        requireNotNegative(parkedCheckInterval, "parkedCheckInterval");
        requireTrue(adminListLimit > 0, "adminListLimit must be > 0");
    }

    /**
     * The lease timings (phase 7 §6): lease, renewal interval and local deadline, validated together. A statement timeout
     * above the new local deadline is lowered to the local deadline's whole seconds (at least one)
     */
    public EventLogSettings withLease(Duration leaseDuration, Duration leaseRenewalInterval, Duration leaseLocalStop) {
        var leaseStatementTimeout = this.leaseStatementTimeout;
        if (leaseLocalStop != null && leaseStatementTimeout.compareTo(leaseLocalStop) > 0) {
            leaseStatementTimeout = Duration.ofSeconds(Math.max(1, leaseLocalStop.toSeconds()));
        }
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withLeaseStatementTimeout(Duration leaseStatementTimeout) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    /** Phase 8 §4.3; {@link Duration#ZERO} means {@code backstopInterval} */
    public EventLogSettings withParkedCheckInterval(Duration parkedCheckInterval) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withAdminListLimit(int adminListLimit) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    /** @return the effective parked-check interval (phase 8 §4.3) */
    public Duration effectiveParkedCheckInterval() {
        return parkedCheckInterval.isZero() ? backstopInterval : parkedCheckInterval;
    }

    public EventLogSettings withNodeName(String nodeName) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    private static void requireNotNegative(Duration duration, String name) {
        requireNonNull(duration, "No " + name + " provided");
        requireTrue(!duration.isNegative(), name + " must not be negative");
    }

    private static void requirePositive(Duration duration, String name) {
        requireNonNull(duration, "No " + name + " provided");
        requireTrue(duration.isPositive(), name + " must be positive");
    }

    /**
     * Backstop 1 s, page 100 (P0.13), re-probe between 1 s and 10 s, save every second, ranges above 1 000 values,
     * generic plans on, inbox 1 000 events, catch-up page 500, page transaction 100 events, 3 retries backing off from
     * 100 ms to at most 10 s; 4 catch-up permits, filtered scan window 100 000 orders growing to at most 10 000 000, no
     * event-type index; a woken type probes at most every 10 ms; cross-JVM wake-up on; no woken-read
     * coalescing; notify sends at most every 2 ms; leases of 15 s renewed every 4 s, local deadline 7 s, lease statements
     * time out after 3 s, no node name
     */
    public static EventLogSettings defaults() {
        return new EventLogSettings(Duration.ofSeconds(1), 100, Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(1), 1_000, true, 1_000, 500, 100, 3, Duration.ofMillis(100), Duration.ofSeconds(10), 4, 100_000, 10_000_000,
                                    java.util.Set.of(), java.util.Set.of(), Duration.ofMillis(10), true, DEFAULT_WOKEN_READ_MIN, DEFAULT_NOTIFY_SEND_MIN,
                                    Duration.ofSeconds(15), Duration.ofSeconds(4), Duration.ofSeconds(7), Duration.ofSeconds(3), "", Duration.ZERO, 1_000);
    }

    public EventLogSettings withBackstopInterval(Duration backstopInterval) {
        var min = probeBackoffMin.compareTo(backstopInterval) > 0 ? probeBackoffMin : backstopInterval;
        return new EventLogSettings(backstopInterval, tailPageSize, min, probeBackoffMax.compareTo(min) < 0 ? min : probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withTailPageSize(int tailPageSize) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withProbeBackoff(Duration min, Duration max) {
        return new EventLogSettings(backstopInterval, tailPageSize, min, max, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withProgressSaveInterval(Duration progressSaveInterval) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withHoleRangeThreshold(int holeRangeThreshold) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withGenericPlans(boolean genericPlans) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withSubscriptionBufferSize(int subscriptionBufferSize) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withCatchUpPageSize(int catchUpPageSize) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withPageSize(int pageSize) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withRetryAttempts(int retryAttempts) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withRetryBackoff(Duration initial, Duration max) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, initial, max, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withCatchUpPermits(int catchUpPermits) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withCatchUpScanWindow(long catchUpScanWindow, long catchUpScanWindowMax) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withEventTypeIndexed(java.util.Set<String> eventTypeIndexed) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    /** Phase 8 §15 question 7: the aggregate types that get the opt-in {@code (tenant, global_order)} index */
    public EventLogSettings withTenantIndexed(java.util.Set<String> tenantIndexed) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withCrossJvmWakeUp(boolean crossJvmWakeUp) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withWokenReadMin(Duration wokenReadMin) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withNotifySendMin(Duration notifySendMin) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    public EventLogSettings withWokenProbeMin(Duration wokenProbeMin) {
        return new EventLogSettings(backstopInterval, tailPageSize, probeBackoffMin, probeBackoffMax, progressSaveInterval, holeRangeThreshold, genericPlans, subscriptionBufferSize, catchUpPageSize, pageSize, retryAttempts, retryBackoffInitial, retryBackoffMax, catchUpPermits, catchUpScanWindow, catchUpScanWindowMax, eventTypeIndexed, tenantIndexed, wokenProbeMin, crossJvmWakeUp, wokenReadMin, notifySendMin, leaseDuration, leaseRenewalInterval, leaseLocalStop, leaseStatementTimeout, nodeName, parkedCheckInterval, adminListLimit);
    }

    /**
     * @param retry the zero-based number of the retry (or of the consecutive I/O failure) about to happen
     * @return {@link #retryBackoffInitial()} doubled {@code retry} times, at most {@link #retryBackoffMax()}
     */
    Duration retryBackoff(int retry) {
        var shift = Math.min(Math.max(0, retry), 30);
        var nanos = retryBackoffInitial.toNanos();
        var max   = retryBackoffMax.toNanos();
        return Duration.ofNanos(nanos > (max >> shift) ? max : Math.min(max, nanos << shift));
    }

    /**
     * How long to wait before re-probing while a candidate is outstanding and the last probe read nothing new (P0.12
     * decision: back off with the probe's measured cost; with backstop wake-up only, never sooner than the backstop):
     * {@code cost * 100 * max(1, backends / 10)}, between {@code max(backstopInterval, probeBackoffMin)} and
     * {@code probeBackoffMax}. The second factor estimates how many readers probe the same server, so the cluster's
     * probes, not each reader's, stay near 1 % of one core ({@link #BACKENDS_PER_READER}). Beyond what
     * {@code probeBackoffMax} allows (about 400 backends at a 2.5 ms probe and a 10 s maximum) the budget is exceeded.
     *
     * @param lastProbeNanos what the last probe statement took
     * @param backends       how many backends the last running-set read saw
     */
    Duration probeWait(long lastProbeNanos, int backends) {
        var floor   = probeBackoffMin.compareTo(backstopInterval) > 0 ? probeBackoffMin : backstopInterval;
        var readers = Math.max(1L, backends / BACKENDS_PER_READER);
        var cost    = Duration.ofNanos(Math.multiplyExact(Math.multiplyExact(Math.max(0, lastProbeNanos), (long) PROBE_COST_MULTIPLIER), readers));
        if (cost.compareTo(floor) < 0) {
            return floor;
        }
        return cost.compareTo(probeBackoffMax) > 0 ? probeBackoffMax : cost;
    }

    /**
     * How long to wait between two running-set reads of a type woken since its last one (phase 6 §5, the probe clock):
     * {@code cost * 100 * max(1, backends / 10)} as {@link #probeWait(long, int)}, but between {@link #wokenProbeMin()}
     * and {@link #probeBackoffMax()} - no backstop floor, since wakes now arrive at the commit rate
     */
    Duration wokenProbeWait(long lastProbeNanos, int backends) {
        var readers = Math.max(1L, backends / BACKENDS_PER_READER);
        var cost    = Duration.ofNanos(Math.multiplyExact(Math.multiplyExact(Math.max(0, lastProbeNanos), (long) PROBE_COST_MULTIPLIER), readers));
        var floor   = wokenProbeMin.compareTo(probeBackoffMax) > 0 ? probeBackoffMax : wokenProbeMin;
        if (cost.compareTo(floor) < 0) {
            return floor;
        }
        return cost.compareTo(probeBackoffMax) > 0 ? probeBackoffMax : cost;
    }
}
