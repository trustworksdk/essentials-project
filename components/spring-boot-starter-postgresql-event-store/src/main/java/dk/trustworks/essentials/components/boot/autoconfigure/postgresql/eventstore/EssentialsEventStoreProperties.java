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

package dk.trustworks.essentials.components.boot.autoconfigure.postgresql.eventstore;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.projection.AnnotationBasedInMemoryProjector;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc.CdcProperties;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Properties for the Postgresql EventStore<br>
 * <br>
 * <br>
 * <u><b>Security:</b></u><br>
 * If you in your own Spring Boot application choose to override the Beans defined by this starter,
 * then you need to check the component document to learn about the Security implications of each configuration.
 * <br>
 * Also see {@link EssentialsComponentsConfiguration} for security information related to common Essentials components.
 *
 * @see PostgresqlDurableSubscriptionRepository
 * @see SeparateTablePerAggregateTypePersistenceStrategy
 * @see SeparateTablePerAggregateTypeEventStreamConfigurationFactory
 * @see SeparateTablePerAggregateEventStreamConfiguration
 * @see EventStreamTableColumnNames
 * @see dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues
 * @see dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager
 * @see dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockStorage
 * @see dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener
 */
@Configuration
@ConfigurationProperties(prefix = "essentials.eventstore")
public class EssentialsEventStoreProperties {
    private IdentifierColumnType                             identifierColumnType                   = IdentifierColumnType.TEXT;
    private JSONColumnType                                   jsonColumnType                         = JSONColumnType.JSONB;
    private boolean                                          useEventStreamGapHandler               = true;
    private boolean                                          verboseTracing                         = false;
    private boolean                                          addAnnotationBasedInMemoryProjector    = true;
    private boolean                                          autoFlushAndPublishAfterAppendToStream = false;
    private EssentialsComponentsProperties.MetricsProperties metrics                                = new EssentialsComponentsProperties.MetricsProperties();

    private final EventStoreSubscriptionManagerProperties subscriptionManager = new EventStoreSubscriptionManagerProperties();

    private final EventStoreSubscriptionMonitorProperties subscriptionMonitor = new EventStoreSubscriptionMonitorProperties();

    private final CdcProperties cdc = new CdcProperties();

    /**
     * Should the Tracing produces only include all operations or only top level operations (default false)
     *
     * @return Should the Tracing produces only include all operations or only top level operations
     */
    public boolean isVerboseTracing() {
        return verboseTracing;
    }

    /**
     * Should the Tracing produces only include all operations or only top level operations (default false)
     *
     * @param verboseTracing Should the Tracing produces only include all operations or only top level operations
     */
    public void setVerboseTracing(boolean verboseTracing) {
        this.verboseTracing = verboseTracing;
    }

    /**
     * The {@link IdentifierColumnType} used for all Aggregate-Ids
     *
     * @return The {@link IdentifierColumnType} used for all Aggregate-Ids
     */
    public IdentifierColumnType getIdentifierColumnType() {
        return identifierColumnType;
    }

    /**
     * The {@link IdentifierColumnType} used for all Aggregate-Ids
     *
     * @param identifierColumnType The {@link IdentifierColumnType} used for all Aggregate-Ids
     */
    public void setIdentifierColumnType(IdentifierColumnType identifierColumnType) {
        this.identifierColumnType = identifierColumnType;
    }

    /**
     * The {@link JSONColumnType} used for all JSON columns
     *
     * @return The {@link JSONColumnType} used for all JSON columns
     */
    public JSONColumnType getJsonColumnType() {
        return jsonColumnType;
    }

    /**
     * The {@link JSONColumnType} used for all JSON columns
     *
     * @param jsonColumnType The {@link JSONColumnType} used for all JSON columns
     */
    public void setJsonColumnType(JSONColumnType jsonColumnType) {
        this.jsonColumnType = jsonColumnType;
    }

    /**
     * Get the {@link EventStoreSubscriptionManager} properties
     *
     * @return the {@link EventStoreSubscriptionManager} properties
     */
    public EventStoreSubscriptionManagerProperties getSubscriptionManager() {
        return subscriptionManager;
    }

    /**
     * Should the {@link PostgresqlEventStore} use {@link PostgresqlEventStreamGapHandler} or the {@link NoEventStreamGapHandler}?
     *
     * @return Should the {@link PostgresqlEventStore} use {@link PostgresqlEventStreamGapHandler} or the {@link NoEventStreamGapHandler}?
     */
    public boolean isUseEventStreamGapHandler() {
        return useEventStreamGapHandler;
    }

    /**
     * Should the {@link PostgresqlEventStore} use {@link PostgresqlEventStreamGapHandler} or the {@link NoEventStreamGapHandler}?
     *
     * @param useEventStreamGapHandler Should the {@link PostgresqlEventStore} use {@link PostgresqlEventStreamGapHandler} or the {@link NoEventStreamGapHandler}?
     */
    public void setUseEventStreamGapHandler(boolean useEventStreamGapHandler) {
        this.useEventStreamGapHandler = useEventStreamGapHandler;
    }

    /**
     * Should the {@link AnnotationBasedInMemoryProjector} be automatically added to the {@link EventStore}? (default true)
     *
     * @return true if the {@link AnnotationBasedInMemoryProjector} should be automatically added to the {@link EventStore}
     */
    public boolean isAddAnnotationBasedInMemoryProjector() {
        return addAnnotationBasedInMemoryProjector;
    }

    /**
     * Should the {@link AnnotationBasedInMemoryProjector} be automatically added to the {@link EventStore}? (default true)
     *
     * @param addAnnotationBasedInMemoryProjector true if the {@link AnnotationBasedInMemoryProjector} should be automatically added to the {@link EventStore}
     */
    public void setAddAnnotationBasedInMemoryProjector(boolean addAnnotationBasedInMemoryProjector) {
        this.addAnnotationBasedInMemoryProjector = addAnnotationBasedInMemoryProjector;
    }

    /**
     * Should the {@link FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream} interceptor be automatically
     * added to the {@link EventStore}? (default false for backwards compatibility)
     * <p>
     * <b>Default behavior (when disabled):</b><br>
     * Events are published to the {@link EventStoreEventBus} at {@link CommitStage#BeforeCommit}
     * and {@link CommitStage#AfterCommit} only.
     * This means in-transaction subscribers receive events just before the transaction commits.
     * <p>
     * <b>When enabled:</b><br>
     * This interceptor <b>additionally</b> publishes {@link PersistedEvents} to the EventBus <b>immediately after</b>
     * each {@code appendToStream()} call completes, using {@link CommitStage#Flush}.
     * This enables in-transaction subscribers to react to events as soon as they are appended, rather than waiting
     * for the transaction to reach the commit phase.
     * <p>
     * <b>Use cases for enabling:</b>
     * <ul>
     *   <li>In-transaction projections that need events immediately after each append (not just at BeforeCommit)</li>
     *   <li>Saga coordination where you need to react to each append individually within the same transaction</li>
     *   <li>{@code subscribeToAggregateEventsInTransaction} receiving events at Flush stage for immediate processing</li>
     * </ul>
     * <p>
     * <b>YAML example:</b>
     * <pre>{@code
     * essentials:
     *   eventstore:
     *     auto-flush-and-publish-after-append-to-stream: true
     * }</pre>
     * <b>Properties example:</b>
     * <pre>{@code
     * essentials.eventstore.auto-flush-and-publish-after-append-to-stream=true
     * }</pre>
     *
     * @return true if the {@link FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream} interceptor should be added
     * @see FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream
     */
    public boolean isAutoFlushAndPublishAfterAppendToStream() {
        return autoFlushAndPublishAfterAppendToStream;
    }

    /**
     * Should the {@link FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream} interceptor be automatically
     * added to the {@link EventStore}? (default false for backwards compatibility)
     * <p>
     * <b>Default behavior (when disabled):</b><br>
     * Events are published to the {@link EventStoreEventBus} at {@link CommitStage#BeforeCommit}
     * and {@link CommitStage#AfterCommit} only.
     * This means in-transaction subscribers receive events just before the transaction commits.
     * <p>
     * <b>When enabled:</b><br>
     * This interceptor <b>additionally</b> publishes {@link PersistedEvents} to the EventBus <b>immediately after</b>
     * each {@code appendToStream()} call completes, using {@link CommitStage#Flush}.
     * This enables in-transaction subscribers to react to events as soon as they are appended, rather than waiting
     * for the transaction to reach the commit phase.
     * <p>
     * <b>Use cases for enabling:</b>
     * <ul>
     *   <li>In-transaction projections that need events immediately after each append (not just at BeforeCommit)</li>
     *   <li>Saga coordination where you need to react to each append individually within the same transaction</li>
     *   <li>{@code subscribeToAggregateEventsInTransaction} receiving events at Flush stage for immediate processing</li>
     * </ul>
     * <p>
     * <b>YAML example:</b>
     * <pre>{@code
     * essentials:
     *   eventstore:
     *     auto-flush-and-publish-after-append-to-stream: true
     * }</pre>
     * <b>Properties example:</b>
     * <pre>{@code
     * essentials.eventstore.auto-flush-and-publish-after-append-to-stream=true
     * }</pre>
     *
     * @param autoFlushAndPublishAfterAppendToStream true to add the interceptor
     * @see FlushAndPublishPersistedEventsToEventBusRightAfterAppendToStream
     */
    public void setAutoFlushAndPublishAfterAppendToStream(boolean autoFlushAndPublishAfterAppendToStream) {
        this.autoFlushAndPublishAfterAppendToStream = autoFlushAndPublishAfterAppendToStream;
    }

    /**
     * Get the {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring.EventStoreSubscriptionMonitorManager} properties
     *
     * @return the {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring.EventStoreSubscriptionMonitorManager} properties
     */
    public EventStoreSubscriptionMonitorProperties getSubscriptionMonitor() {
        return this.subscriptionMonitor;
    }

    /**
     * Retrieves the change data capture (CDC) properties associated with the event store.
     *
     * @return the {@link CdcProperties} that contains the CDC configuration.
     */
    public CdcProperties getCdc() {
        return cdc;
    }

    /**
     * Configuration properties for essentials metrics collection and logging.
     * <p>
     * This configuration is used to enable and fine-tune metrics gathering and logging for the event store.
     * If the <code>enabled</code> property is
     * set to <code>false</code>, then no performance metrics will be collected or logged for that component.
     * <p>
     * <b>YAML example:</b>
     * <pre>{@code
     * essentials:
     *   event-store:
     *     metrics:
     *       enabled: true
     *       thresholds:
     *         debug: 25ms
     *         info: 200ms
     *         warn: 500ms
     *         error: 5000ms
     * }</pre>
     * <b>Properties example:</b>
     * <pre>{@code
     * essentials.event-store.metrics.enabled=true
     * essentials.event-store.metrics.thresholds.debug=25ms
     * essentials.event-store.metrics.thresholds.info=200ms
     * essentials.event-store.metrics.thresholds.warn=500ms
     * essentials.event-store.metrics.thresholds.error=5000ms
     * }</pre>
     * <p>
     * You can further control the log levels by adjusting the minimum log level for the respective loggers:
     * <table border="1">
     *     <tr><th>Metric</th><th>Logger Class</th></tr>
     *     <tr><td>essentials.event-store.metrics</td>
     *        <td>
     *            dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.interceptor.micrometer.RecordExecutionTimeEventStoreInterceptor<br>
     *            dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.micrometer.MeasurementEventStoreSubscriptionObserver
     *        </td>
     *     </tr>
     * </table>
     *
     * @return essentials metrics properties
     */
    public EssentialsComponentsProperties.MetricsProperties getMetrics() {
        return metrics;
    }

    /**
     * {@link EventStoreSubscriptionManager} properties
     */
    public static class EventStoreSubscriptionManagerProperties {
        private int                                              eventStorePollingBatchSize   = 10;
        private Duration                                         eventStorePollingInterval    = Duration.ofMillis(100);
        private Duration                                         maxEventStorePollingInterval = Duration.ofMillis(2000);
        private Duration                                         snapshotResumePointsEvery    = Duration.ofSeconds(10);
        private EssentialsComponentsProperties.MetricsProperties metrics                      = new EssentialsComponentsProperties.MetricsProperties();
        private final NotifyPollingProperties                    notifyPolling                = new NotifyPollingProperties();

        /**
         * How many events should The {@link EventStore} maximum return when polling for events
         *
         * @return how many events should The {@link EventStore} maximum return when polling for events
         */
        public int getEventStorePollingBatchSize() {
            return eventStorePollingBatchSize;
        }

        /**
         * How many events should The {@link EventStore} maximum return when polling for events
         *
         * @param eventStorePollingBatchSize how many events should The {@link EventStore} maximum return when polling for events
         */
        public void setEventStorePollingBatchSize(int eventStorePollingBatchSize) {
            this.eventStorePollingBatchSize = eventStorePollingBatchSize;
        }

        /**
         * How often should the {@link EventStore} be polled for new events
         *
         * @return how often should the {@link EventStore} be polled for new events
         */
        public Duration getEventStorePollingInterval() {
            return eventStorePollingInterval;
        }

        /**
         * How often should the {@link EventStore} be polled for new events
         *
         * @param eventStorePollingInterval how often should the {@link EventStore} be polled for new events
         */
        public void setEventStorePollingInterval(Duration eventStorePollingInterval) {
            this.eventStorePollingInterval = eventStorePollingInterval;
        }

        /**
         * Retrieves the maximum interval at which the EventStore is polled for new events. Default is 2000 ms.<br>
         * Used as input to the {@link JitteredEventStorePollingOptimizer} configured with the default {@link EventStoreSubscriptionManager}
         *
         * @return the maximum polling interval for the EventStore
         */
        public Duration getMaxEventStorePollingInterval() {
            return maxEventStorePollingInterval;
        }

        /**
         * Sets the maximum interval at which the EventStore is polled for new events. Default is 2000 ms.<br>
         * Used as input to the {@link JitteredEventStorePollingOptimizer} configured with the default {@link EventStoreSubscriptionManager}
         *
         * @param maxEventStorePollingInterval the maximum polling interval for the EventStore
         */
        public void setMaxEventStorePollingInterval(Duration maxEventStorePollingInterval) {
            this.maxEventStorePollingInterval = maxEventStorePollingInterval;
        }

        /**
         * How often should active (for exclusive subscribers this means subscribers that have acquired a distributed lock) subscribers have their {@link SubscriptionResumePoint} saved
         *
         * @return how often should active (for exclusive subscribers this means subscribers that have acquired a distributed lock) subscribers have their {@link SubscriptionResumePoint} saved
         */
        public Duration getSnapshotResumePointsEvery() {
            return snapshotResumePointsEvery;
        }

        /**
         * How often should active (for exclusive subscribers this means subscribers that have acquired a distributed lock) subscribers have their {@link SubscriptionResumePoint} saved
         *
         * @param snapshotResumePointsEvery How often should active (for exclusive subscribers this means subscribers that have acquired a distributed lock) subscribers have their {@link SubscriptionResumePoint} saved
         */
        public void setSnapshotResumePointsEvery(Duration snapshotResumePointsEvery) {
            this.snapshotResumePointsEvery = snapshotResumePointsEvery;
        }

        /**
         * Configure essentials event store subscription manager metrics
         * <p>
         * YAML example:
         * <pre>{@code
         * essentials:
         *   event-store:
         *     subscription-manager:
         *       metrics:
         *         enabled: true
         *         thresholds:
         *           debug: 25ms
         *           info: 200ms
         *           warn: 500ms
         *           error: 5000ms
         * }</pre>
         * <p>
         * Properties example:
         * <pre>{@code
         * essentials.event-store.subscription-manager.metrics.enabled=true
         * essentials.event-store.subscription-manager.metrics.thresholds.debug=25ms
         * essentials.event-store.subscription-manager.metrics.thresholds.info=200ms
         * essentials.event-store.subscription-manager.metrics.thresholds.warn=500ms
         * essentials.event-store.subscription-manager.metrics.thresholds.error=5000ms
         * }</pre>
         *
         * @return essentials event store subscription manager metrics
         */
        public EssentialsComponentsProperties.MetricsProperties getMetrics() {
            return metrics;
        }

        /**
         * Configuration for the S1 NOTIFY-driven polling wake-up feature — adds an
         * {@code AFTER INSERT} trigger on every event-stream table that fires
         * {@code pg_notify}, registers the table with the framework's
         * {@code MultiTableChangeListener}, and installs a NOTIFY-aware
         * {@code EventStorePollingOptimizer} so subscribers ramp their poll cadence to
         * the configured cap on quiet systems but wake immediately on writes.
         * <p>
         * See {@code subscription-improvements.md} (S1) for the full design including
         * trade-offs vs CDC INBOX, the operator decision tree, and the phase-2 path to
         * sub-10 ms reactive wake-up.
         */
        public NotifyPollingProperties getNotifyPolling() {
            return notifyPolling;
        }
    }

    /**
     * Properties for the S1 NOTIFY-driven polling wake-up feature. Disabled by default
     * — the master switch makes the whole feature opt-in and the change non-breaking.
     */
    public static class NotifyPollingProperties {
        /**
         * Master switch. {@code false} (default) = framework behaves exactly as before:
         * no triggers installed, no notification listener registered for event-stream
         * tables, no NOTIFY-aware optimizer wired. Set {@code true} to enable.
         */
        private boolean  enabled            = false;

        /**
         * Backoff floor after a no-events poll. Also the value the optimizer resets to
         * on a NOTIFY-driven wake-up. Default 50 ms — short enough to keep follow-up
         * polls responsive when activity continues, long enough to avoid busy-spinning
         * a polling loop on a freshly-emptied inbox.
         */
        private Duration initialDelay       = Duration.ofMillis(50);

        /**
         * Backoff cap — the longest the optimizer will let the per-subscription delay
         * grow to on a sustained quiet period. Equals the worst-case live latency on a
         * fully-idle system (a NOTIFY arriving mid-sleep cannot interrupt the in-flight
         * sleep in Phase 1 — that's Phase 2). Default 1 s — balances DB-load savings
         * (higher cap = fewer queries) against worst-case latency on quiet systems.
         */
        private Duration maxDelay           = Duration.ofSeconds(1);

        /**
         * Exponential ramp factor between consecutive no-events polls. Default 2.0 →
         * 50 ms → 100 → 200 → … → 1 s in ~5 ramps. Must be {@code > 1.0}.
         */
        private double   backoffMultiplier  = 2.0d;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public Duration getMaxDelay() {
            return maxDelay;
        }

        public void setMaxDelay(Duration maxDelay) {
            this.maxDelay = maxDelay;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public void setBackoffMultiplier(double backoffMultiplier) {
            this.backoffMultiplier = backoffMultiplier;
        }
    }

    public static class EventStoreSubscriptionMonitorProperties {
        private boolean  enabled  = true;
        private Duration interval = Duration.ofMinutes(1);

        /**
         * Is monitoring of event store subscribers enabled
         *
         * @return Is monitoring of event store subscribers enabled
         */
        public boolean isEnabled() {
            return this.enabled;
        }

        /**
         * Monitoring of event store subscribers using implementations of
         * {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring.EventStoreSubscriptionMonitor}
         *
         * @param enabled Monitoring of event store subscribers
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Monitoring interval
         *
         * @param interval Monitoring interval
         */
        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        /**
         * The interval to execute the {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.monitoring.EventStoreSubscriptionMonitor}s
         *
         * @return monitoring interval
         */
        public Duration getInterval() {
            return this.interval;
        }
    }
}
