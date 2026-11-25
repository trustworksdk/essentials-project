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

import dk.trustworks.essentials.components.eventlog.EventLogSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.*;

/**
 * Configuration of the new event store engine ({@code EventLog}), under {@code essentials.event-log}. <b>Preview</b>: the
 * starter and its properties may change in a minor release.
 * <p>
 * {@link EventLogSettings} is a record, a poor fit for relaxed binding, so this mirrors it one property per component and
 * converts once ({@link #toSettings()}). Every default equals {@link EventLogSettings#defaults()}; the defaults are
 * literals so the configuration metadata carries them, and {@code EssentialsEventLogPropertiesTest} keeps the two equal.
 */
@ConfigurationProperties(prefix = "essentials.event-log")
public class EssentialsEventLogProperties {
    /**
     * Turns the new event store engine on. Off by default: the old engine stays the default, and with this set the
     * starter also drops the old starter's {@code EventStoreConfiguration} from the auto-configurations.
     */
    private boolean enabled = false;
    /**
     * Declare the {@code EventLogSchemaContributor} that carries the engine's schema into the schema harness. Required
     * whenever {@code essentials.schema.mode} is not {@code create}.
     */
    private boolean initializeSchema = true;
    /**
     * How long a caught-up tail reader parks before it reads again (the tier-0 wake-up).
     */
    private Duration backstopInterval = Duration.ofSeconds(1);
    /**
     * Most rows one tail read returns above the cursor.
     */
    private int tailPageSize = 100;
    /**
     * Least time between two re-probes while a candidate is outstanding and nothing new was seen.
     */
    private Duration probeBackoffMin = Duration.ofSeconds(1);
    /**
     * Most time between two such re-probes, however expensive the probe was.
     */
    private Duration probeBackoffMax = Duration.ofSeconds(10);
    /**
     * Least time between two periodic resume-point saves of one subscription; zero saves after every delivery.
     */
    private Duration progressSaveInterval = Duration.ofSeconds(1);
    /**
     * A gap of more than this many values (a sequence jump) is kept as one range and re-read as one.
     */
    private int holeRangeThreshold = 1000;
    /**
     * Run the tail reader's statements under plan_cache_mode = force_generic_plan.
     */
    private boolean genericPlans = true;
    /**
     * Most events a subscription's inbox holds before the next batch overflows it; at least tail-page-size.
     */
    private int subscriptionBufferSize = 1000;
    /**
     * Most rows one catch-up or bridge statement returns.
     */
    private int catchUpPageSize = 500;
    /**
     * Most events one page transaction of an in-transaction subscription applies.
     */
    private int pageSize = 100;
    /**
     * How many times an event that fails alone is retried before it is parked.
     */
    private int retryAttempts = 3;
    /**
     * The sleep before the first retry of an event that failed alone, doubled before each further retry.
     */
    private Duration retryBackoffInitial = Duration.ofMillis(10);
    /**
     * The most any such sleep or wait lasts.
     */
    private Duration retryBackoffMax = Duration.ofSeconds(10);
    /**
     * Most catch-up and bridge statements in flight at once per database per JVM.
     */
    private int catchUpPermits = 4;
    /**
     * The first order window a filtered catch-up read scans; doubled after each empty window.
     */
    private long catchUpScanWindow = 100_000;
    /**
     * The largest such window.
     */
    private long catchUpScanWindowMax = 10_000_000;
    /**
     * Aggregate types that get the opt-in (event_type, global_order) index.
     */
    private Set<String> eventTypeIndexed = new LinkedHashSet<>();
    /**
     * Aggregate types that get the opt-in (tenant, global_order) index.
     */
    private Set<String> tenantIndexed = new LinkedHashSet<>();
    /**
     * Least time between two running-set reads of an aggregate type that was woken since its last read.
     */
    private Duration wokenProbeMin = Duration.ofMillis(10);
    /**
     * Tier-1 wake-up: send a coalesced NOTIFY after each commit and LISTEN for other event logs' commits (one held connection while any subscription exists); false leaves only same-process commits and the backstop interval to wake a reader.
     */
    private boolean crossJvmWakeUp = true;
    /**
     * Least time from the start of an aggregate type's last read to the start of a read a wake-up triggers.
     */
    private Duration wokenReadMin = Duration.ZERO;
    /**
     * Least time between the starts of two NOTIFY sends of one event log's notifier.
     */
    private Duration notifySendMin = Duration.ofMillis(2);
    /**
     * How long a claim or renewal of an exclusive subscription's lease lasts, judged by the database clock.
     */
    private Duration leaseDuration = Duration.ofSeconds(15);
    /**
     * How often the lease keeper renews every owned lease and tries to claim every wanted one.
     */
    private Duration leaseRenewalInterval = Duration.ofSeconds(4);
    /**
     * How long after the start of its last confirmed renewal an owner keeps delivering.
     */
    private Duration leaseLocalStop = Duration.ofSeconds(7);
    /**
     * The query timeout of the lease keeper's statements; whole seconds.
     */
    private Duration leaseStatementTimeout = Duration.ofSeconds(3);
    /**
     * Free text (at most 64 characters) prefixed to the lease owner id, to tell nodes apart in the admin views.
     */
    private String nodeName = "";
    /**
     * How often a subscription that holds a parked aggregate reads the retry and discard requests an operator stored on any node; zero means the backstop interval.
     */
    private Duration parkedCheckInterval = Duration.ZERO;
    /**
     * The most rows one admin subscriptions listing returns.
     */
    private int adminListLimit = 1000;
    /**
     * Most time between two running-set reads of an aggregate type while a hole, range or candidate of it is outstanding: caps the probe back-off, so a rolled-back append's hole does not hold the watermark for seconds. The probe cost budget (~1 % of a core) is a soft target while holes wait.
     */
    private Duration holeProbeWaitMax = Duration.ofMillis(100);
    /**
     * How long after it parked the engine retries a parked aggregate whose head did not fail with a ParkEventException (a terminal park waits for an operator); doubled per further failure, up to parked-retry-max. Retried forever, in the aggregate's order.
     */
    private Duration parkedRetryInitial = Duration.ofSeconds(1);
    /**
     * The longest wait between two automatic retries of a parked aggregate; at least parked-retry-initial.
     */
    private Duration parkedRetryMax = Duration.ofMinutes(5);

    /**
     * @return the settings these properties describe
     * @throws IllegalArgumentException if they break one of {@link EventLogSettings}' rules
     */
    public EventLogSettings toSettings() {
        return new EventLogSettings(backstopInterval,
                                    tailPageSize,
                                    probeBackoffMin,
                                    probeBackoffMax,
                                    progressSaveInterval,
                                    holeRangeThreshold,
                                    genericPlans,
                                    subscriptionBufferSize,
                                    catchUpPageSize,
                                    pageSize,
                                    retryAttempts,
                                    retryBackoffInitial,
                                    retryBackoffMax,
                                    catchUpPermits,
                                    catchUpScanWindow,
                                    catchUpScanWindowMax,
                                    eventTypeIndexed,
                                    tenantIndexed,
                                    wokenProbeMin,
                                    crossJvmWakeUp,
                                    wokenReadMin,
                                    notifySendMin,
                                    leaseDuration,
                                    leaseRenewalInterval,
                                    leaseLocalStop,
                                    leaseStatementTimeout,
                                    nodeName,
                                    parkedCheckInterval,
                                    adminListLimit,
                                    holeProbeWaitMax,
                                    parkedRetryInitial,
                                    parkedRetryMax);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    public void setInitializeSchema(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
    }

    public Duration getBackstopInterval() {
        return backstopInterval;
    }

    public void setBackstopInterval(Duration backstopInterval) {
        this.backstopInterval = backstopInterval;
    }

    public int getTailPageSize() {
        return tailPageSize;
    }

    public void setTailPageSize(int tailPageSize) {
        this.tailPageSize = tailPageSize;
    }

    public Duration getProbeBackoffMin() {
        return probeBackoffMin;
    }

    public void setProbeBackoffMin(Duration probeBackoffMin) {
        this.probeBackoffMin = probeBackoffMin;
    }

    public Duration getProbeBackoffMax() {
        return probeBackoffMax;
    }

    public void setProbeBackoffMax(Duration probeBackoffMax) {
        this.probeBackoffMax = probeBackoffMax;
    }

    public Duration getProgressSaveInterval() {
        return progressSaveInterval;
    }

    public void setProgressSaveInterval(Duration progressSaveInterval) {
        this.progressSaveInterval = progressSaveInterval;
    }

    public int getHoleRangeThreshold() {
        return holeRangeThreshold;
    }

    public void setHoleRangeThreshold(int holeRangeThreshold) {
        this.holeRangeThreshold = holeRangeThreshold;
    }

    public boolean isGenericPlans() {
        return genericPlans;
    }

    public void setGenericPlans(boolean genericPlans) {
        this.genericPlans = genericPlans;
    }

    public int getSubscriptionBufferSize() {
        return subscriptionBufferSize;
    }

    public void setSubscriptionBufferSize(int subscriptionBufferSize) {
        this.subscriptionBufferSize = subscriptionBufferSize;
    }

    public int getCatchUpPageSize() {
        return catchUpPageSize;
    }

    public void setCatchUpPageSize(int catchUpPageSize) {
        this.catchUpPageSize = catchUpPageSize;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public void setRetryAttempts(int retryAttempts) {
        this.retryAttempts = retryAttempts;
    }

    public Duration getRetryBackoffInitial() {
        return retryBackoffInitial;
    }

    public void setRetryBackoffInitial(Duration retryBackoffInitial) {
        this.retryBackoffInitial = retryBackoffInitial;
    }

    public Duration getRetryBackoffMax() {
        return retryBackoffMax;
    }

    public void setRetryBackoffMax(Duration retryBackoffMax) {
        this.retryBackoffMax = retryBackoffMax;
    }

    public int getCatchUpPermits() {
        return catchUpPermits;
    }

    public void setCatchUpPermits(int catchUpPermits) {
        this.catchUpPermits = catchUpPermits;
    }

    public long getCatchUpScanWindow() {
        return catchUpScanWindow;
    }

    public void setCatchUpScanWindow(long catchUpScanWindow) {
        this.catchUpScanWindow = catchUpScanWindow;
    }

    public long getCatchUpScanWindowMax() {
        return catchUpScanWindowMax;
    }

    public void setCatchUpScanWindowMax(long catchUpScanWindowMax) {
        this.catchUpScanWindowMax = catchUpScanWindowMax;
    }

    public Set<String> getEventTypeIndexed() {
        return eventTypeIndexed;
    }

    public void setEventTypeIndexed(Set<String> eventTypeIndexed) {
        this.eventTypeIndexed = eventTypeIndexed;
    }

    public Set<String> getTenantIndexed() {
        return tenantIndexed;
    }

    public void setTenantIndexed(Set<String> tenantIndexed) {
        this.tenantIndexed = tenantIndexed;
    }

    public Duration getWokenProbeMin() {
        return wokenProbeMin;
    }

    public void setWokenProbeMin(Duration wokenProbeMin) {
        this.wokenProbeMin = wokenProbeMin;
    }

    public boolean isCrossJvmWakeUp() {
        return crossJvmWakeUp;
    }

    public void setCrossJvmWakeUp(boolean crossJvmWakeUp) {
        this.crossJvmWakeUp = crossJvmWakeUp;
    }

    public Duration getWokenReadMin() {
        return wokenReadMin;
    }

    public void setWokenReadMin(Duration wokenReadMin) {
        this.wokenReadMin = wokenReadMin;
    }

    public Duration getNotifySendMin() {
        return notifySendMin;
    }

    public void setNotifySendMin(Duration notifySendMin) {
        this.notifySendMin = notifySendMin;
    }

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getLeaseRenewalInterval() {
        return leaseRenewalInterval;
    }

    public void setLeaseRenewalInterval(Duration leaseRenewalInterval) {
        this.leaseRenewalInterval = leaseRenewalInterval;
    }

    public Duration getLeaseLocalStop() {
        return leaseLocalStop;
    }

    public void setLeaseLocalStop(Duration leaseLocalStop) {
        this.leaseLocalStop = leaseLocalStop;
    }

    public Duration getLeaseStatementTimeout() {
        return leaseStatementTimeout;
    }

    public void setLeaseStatementTimeout(Duration leaseStatementTimeout) {
        this.leaseStatementTimeout = leaseStatementTimeout;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public Duration getParkedCheckInterval() {
        return parkedCheckInterval;
    }

    public void setParkedCheckInterval(Duration parkedCheckInterval) {
        this.parkedCheckInterval = parkedCheckInterval;
    }

    public int getAdminListLimit() {
        return adminListLimit;
    }

    public void setAdminListLimit(int adminListLimit) {
        this.adminListLimit = adminListLimit;
    }

    public Duration getHoleProbeWaitMax() {
        return holeProbeWaitMax;
    }

    public void setHoleProbeWaitMax(Duration holeProbeWaitMax) {
        this.holeProbeWaitMax = holeProbeWaitMax;
    }

    public Duration getParkedRetryInitial() {
        return parkedRetryInitial;
    }

    public void setParkedRetryInitial(Duration parkedRetryInitial) {
        this.parkedRetryInitial = parkedRetryInitial;
    }

    public Duration getParkedRetryMax() {
        return parkedRetryMax;
    }

    public void setParkedRetryMax(Duration parkedRetryMax) {
        this.parkedRetryMax = parkedRetryMax;
    }
}
