/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import java.time.Duration;

public class CdcProperties {

    private boolean enabled                        = true;
    private int     cdcEventStoreBackfillBatchSize = 1000;

    private final Wal2JsonTailerProperties wal2JsonTailer = new Wal2JsonTailerProperties();
    private final CdcDispatcherProperties  cdcDispatcher  = new CdcDispatcherProperties();
    private final CdcSlotProperties        slot           = new CdcSlotProperties();

    /**
     * Checks whether the Change Data Capture (CDC) functionality is enabled.
     *
     * @return {@code true} if CDC is enabled; {@code false} otherwise
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables the Change Data Capture (CDC) functionality.
     *
     * @param enabled a boolean indicating whether CDC should be enabled
     *                ({@code true}) or disabled ({@code false})
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Retrieves the batch size for backfilling events into the CDC event store.
     * <p>
     * This value determines the number of events that are processed in a single
     * batch during a backfill operation.
     *
     * @return the batch size used for CDC event store backfill operations
     */
    public int getCdcEventStoreBackfillBatchSize() {
        return cdcEventStoreBackfillBatchSize;
    }

    /**
     * Sets the batch size for backfilling events into the CDC event store.
     * <p>
     * This value determines the number of events to be processed in a single batch during
     * a backfill operation. Adjusting this size can influence the performance and efficiency
     * of the backfill process.
     *
     * @param cdcEventStoreBackfillBatchSize the batch size to be used for CDC event store
     *                                       backfill operations
     */
    public void setCdcEventStoreBackfillBatchSize(int cdcEventStoreBackfillBatchSize) {
        this.cdcEventStoreBackfillBatchSize = cdcEventStoreBackfillBatchSize;
    }

    /**
     * Retrieves the properties configuration for the Wal2Json tailer.
     *
     * @return an instance of Wal2JsonTailerProperties containing the configuration for the Wal2Json tailer.
     */
    public Wal2JsonTailerProperties getWal2JsonTailer() {
        return wal2JsonTailer;
    }

    /**
     * Retrieves the properties configuration for the Change Data Capture (CDC) event dispatcher.
     * <p>
     * The returned configuration includes settings that determine the dispatcher's behavior,
     * such as polling intervals, batch processing size, and poison message handling policies.
     *
     * @return an instance of CdcDispatcherProperties containing the configuration for the CDC event dispatcher
     */
    public CdcDispatcherProperties getCdcDispatcher() {
        return cdcDispatcher;
    }

    /**
     * Retrieves the configuration properties for a PostgreSQL replication slot.
     *
     * @return an instance of PgSlotProperties containing the configuration for the PostgreSQL replication slot
     */
    public CdcSlotProperties getSlot() {
        return slot;
    }

    /**
     * Configuration properties for the Wal2JsonTailer, enabling control over various
     * operational parameters like polling intervals, backoff strategies, and JSON output format.
     * <p>
     * This class provides settings to adjust how changes from a PostgreSQL Change Data Capture (CDC)
     * are processed and tailored to specific requirements. It supports customization of retry policies,
     * response formatting, and inclusion of metadata.
     * <p>
     * The following key properties are configurable:
     * - Polling interval and backoff timings, defined as durations.
     * - Exponential backoff mechanism with jitter and scaling factor.
     * - JSON output formatting, including options for pretty printing.
     * - Inclusion of additional metadata such as XIDs, timestamps, and log sequence numbers (LSN).
     * - Poison message handling through a defined {@link PoisonPolicy}.
     * <p>
     * This class also provides methods to get and set these properties, as well as a static builder
     * method for creating a new instance with custom defaults.
     */
    public static class Wal2JsonTailerProperties {

        private Duration     pollInterval              = Duration.ofMillis(25);
        private Duration     pollBackoffInterval       = Duration.ofMillis(250);
        private Duration     maxPollBackoffInterval    = Duration.ofSeconds(5);
        private Duration     replicationStatusInterval = Duration.ofSeconds(1);
        private double       jitterRatio               = 0.2;
        private double       backOffFactor             = 2;
        private boolean      prettyPrint               = false;
        private boolean      includeXids               = true;
        private boolean      includeTimestamp          = true;
        private boolean      includeLsn                = true;
        private PoisonPolicy poisonPolicy              = PoisonPolicy.QUARANTINE_AND_CONTINUE;


        public static Wal2JsonTailerProperties defaults(Duration pollInterval,
                                                        Duration pollBackoffInterval,
                                                        Duration maxPollBackoffInterval,
                                                        Duration replicationStatusInterval) {
            var tailer = new Wal2JsonTailerProperties();
            tailer.setPollInterval(pollInterval);
            tailer.setPollBackoffInterval(pollBackoffInterval);
            tailer.setMaxPollBackoffInterval(maxPollBackoffInterval);
            tailer.setReplicationStatusInterval(replicationStatusInterval);
            return tailer;
        }

        /**
         * Retrieves the configured polling interval.
         *
         * @return the duration representing the interval at which polling occurs
         */
        public Duration getPollInterval() {
            return pollInterval;
        }

        /**
         * Sets the polling interval for an operation. This defines the time duration
         * at which the polling is executed.
         *
         * @param pollInterval the duration representing the time interval between
         *                     successive polling operations
         */
        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        /**
         * Retrieves the configured polling backoff interval. This interval determines
         * the duration between successive polling operations when backing off after
         * failed attempts or delays.
         *
         * @return the duration representing the configured polling backoff interval
         */
        public Duration getPollBackoffInterval() {
            return pollBackoffInterval;
        }

        /**
         * Sets the polling backoff interval. This interval defines the duration
         * to wait before retrying a polling operation, typically after a failure
         * or when applying a backoff strategy.
         *
         * @param pollBackoffInterval the duration to set as the interval between
         *                            successive polling attempts during backoff periods
         */
        public void setPollBackoffInterval(Duration pollBackoffInterval) {
            this.pollBackoffInterval = pollBackoffInterval;
        }

        /**
         * Retrieves the maximum polling backoff interval. This interval defines the upper limit
         * for the duration that can be applied when backing off during polling operations.
         *
         * @return the duration representing the maximum allowed polling backoff interval
         */
        public Duration getMaxPollBackoffInterval() {
            return maxPollBackoffInterval;
        }

        /**
         * Sets the maximum polling backoff interval. This interval defines the upper limit
         * for the duration that can be applied when backing off during polling operations.
         *
         * @param maxPollBackoffInterval the duration representing the maximum allowed polling backoff interval
         */
        public void setMaxPollBackoffInterval(Duration maxPollBackoffInterval) {
            this.maxPollBackoffInterval = maxPollBackoffInterval;
        }

        /**
         * Retrieves the replication status interval. This interval defines the time duration
         * between successive replication status checks.
         *
         * @return the duration representing the interval for replication status checks
         */
        public Duration getReplicationStatusInterval() {
            return replicationStatusInterval;
        }

        /**
         * Sets the replication status interval. This interval defines the time duration
         * between successive checks for replication status.
         *
         * @param replicationStatusInterval the duration representing the interval for
         *                                  checking replication status
         */
        public void setReplicationStatusInterval(Duration replicationStatusInterval) {
            this.replicationStatusInterval = replicationStatusInterval;
        }

        /**
         * Retrieves the jitter ratio. The jitter ratio is used to introduce
         * randomness in intervals to avoid synchronized behaviors in distributed systems.
         *
         * @return the configured jitter ratio as a double value
         */
        public double getJitterRatio() {
            return jitterRatio;
        }

        /**
         * Sets the jitter ratio to be used. The jitter ratio is a value that introduces
         * randomness into intervals, typically to avoid synchronized behaviors in
         * distributed systems.
         *
         * @param jitterRatio the jitter ratio as a double value. Valid values typically
         *                    range between 0 and 1, where 0 implies no jitter and higher
         *                    values introduce more randomness.
         */
        public void setJitterRatio(double jitterRatio) {
            this.jitterRatio = jitterRatio;
        }

        /**
         * Retrieves the backoff factor. The backoff factor is used to adjust the
         * delay interval in a backoff strategy, typically for retrying operations.
         *
         * @return the configured backoff factor as a double value
         */
        public double getBackOffFactor() {
            return backOffFactor;
        }

        /**
         * Sets the backoff factor to be used in a backoff strategy. The backoff factor adjusts
         * the delay interval for retrying operations and influences the exponential backoff behavior.
         *
         * @param backOffFactor the backoff factor as a double value. It typically represents
         *                      the multiplier used to calculate subsequent delay intervals in
         *                      an exponential backoff algorithm.
         */
        public void setBackOffFactor(double backOffFactor) {
            this.backOffFactor = backOffFactor;
        }

        /**
         * Checks if the pretty print mode is enabled. Pretty print mode typically
         * formats the output in a more human-readable way.
         *
         * @return true if pretty print mode is enabled, false otherwise
         */
        public boolean isPrettyPrint() {
            return prettyPrint;
        }

        /**
         * Sets whether the pretty print mode is enabled. Pretty print mode
         * typically formats the output in a more human-readable way.
         *
         * @param prettyPrint a boolean indicating whether to enable or disable
         *                    pretty print mode. True enables pretty printing,
         *                    while false disables it.
         */
        public void setPrettyPrint(boolean prettyPrint) {
            this.prettyPrint = prettyPrint;
        }

        /**
         * Checks if transaction IDs (XIDs) should be included.
         *
         * @return true if XIDs are included, false otherwise
         */
        public boolean isIncludeXids() {
            return includeXids;
        }

        /**
         * Sets whether transaction IDs (XIDs) should be included.
         *
         * @param includeXids a boolean indicating whether to include XIDs. True enables
         *                    inclusion of XIDs, while false disables it.
         */
        public void setIncludeXids(boolean includeXids) {
            this.includeXids = includeXids;
        }

        /**
         * Checks if timestamps should be included.
         *
         * @return true if timestamps are included, false otherwise
         */
        public boolean isIncludeTimestamp() {
            return includeTimestamp;
        }

        /**
         * Sets whether timestamps should be included.
         *
         * @param includeTimestamp a boolean indicating whether timestamps should be included.
         *                         True enables the inclusion of timestamps, while false disables it.
         */
        public void setIncludeTimestamp(boolean includeTimestamp) {
            this.includeTimestamp = includeTimestamp;
        }

        /**
         * Checks if Log Sequence Numbers (LSNs) should be included.
         *
         * @return true if LSNs are included, false otherwise
         */
        public boolean isIncludeLsn() {
            return includeLsn;
        }

        /**
         * Sets whether Log Sequence Numbers (LSNs) should be included.
         *
         * @param includeLsn a boolean indicating whether to include LSNs.
         *                   True enables the inclusion of LSNs, while false disables it.
         */
        public void setIncludeLsn(boolean includeLsn) {
            this.includeLsn = includeLsn;
        }

        /**
         * Retrieves the configured poison policy. The poison policy defines the behavior
         * for handling poisoned messages or errors during processing.
         *
         * @return the configured PoisonPolicy object
         */
        public PoisonPolicy getPoisonPolicy() {
            return poisonPolicy;
        }

        /**
         * Sets the poison policy to define the behavior for handling poisoned messages or errors during processing.
         *
         * @param poisonPolicy the PoisonPolicy object to set as the configuration for handling poisoned messages
         */
        public void setPoisonPolicy(PoisonPolicy poisonPolicy) {
            this.poisonPolicy = poisonPolicy;
        }
    }

    /**
     * Represents configuration properties for a Change Data Capture (CDC) event dispatcher.
     * <p>
     * The class is designed to define settings that control the behavior of the dispatcher,
     * such as how frequently to poll for new events, the number of events to process in
     * a single batch, and the policy for managing poison messages.
     * <p>
     * Key properties include:
     * - Polling interval: Determines the frequency at which the dispatcher polls for new events.
     * - Batch size: Specifies the maximum number of events to process in a single operation.
     * - Poison policy: Defines the approach to handle messages that cannot be processed
     * (e.g., stop processing entirely or quarantine and continue).
     */
    public static class CdcDispatcherProperties {
        private Duration     pollInterval = Duration.ofMillis(20);
        private int          batchSize    = 500;
        private PoisonPolicy poisonPolicy = PoisonPolicy.QUARANTINE_AND_CONTINUE;

        public static CdcDispatcherProperties defaults() {
            return new CdcDispatcherProperties();
        }

        /**
         * Retrieves the poll interval, which specifies the frequency at which the dispatcher
         * polls for new change data capture (CDC) events.
         *
         * @return the configured poll interval as a {@link Duration}
         */
        public Duration getPollInterval() {
            return pollInterval;
        }

        /**
         * Sets the polling interval that specifies how often the dispatcher should poll
         * for new change data capture (CDC) events.
         *
         * @param pollInterval the desired polling interval, provided as a {@link Duration}
         */
        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        /**
         * Retrieves the batch size, which specifies the maximum number of events
         * to process in a single operation.
         *
         * @return the configured batch size as an integer
         */
        public int getBatchSize() {
            return batchSize;
        }

        /**
         * Sets the batch size, which specifies the maximum number of events to be processed
         * in a single operation.
         *
         * @param batchSize the desired batch size as an integer; must be a non-negative value
         */
        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        /**
         * Retrieves the configured poison policy for handling messages that cannot
         * be processed (poison messages) in the Change Data Capture (CDC) event dispatcher.
         * <p>
         * The poison policy determines the behavior of the dispatcher when encountering
         * such messages. For example, it could either stop processing entirely or
         * quarantine the message and continue processing other messages.
         *
         * @return the configured {@link PoisonPolicy} for managing poison messages
         */
        public PoisonPolicy getPoisonPolicy() {
            return poisonPolicy;
        }

        /**
         * Configures the poison policy to specify how the dispatcher should handle
         * messages that cannot be processed ("poison messages") in the Change Data Capture (CDC) system.
         * <p>
         * The poison policy determines the behavior in scenarios where messages are malformed
         * or fail processing due to errors. Possible values for the poison policy include:
         * - {@code STOP}: Processing terminates immediately upon encountering a poison message.
         * - {@code QUARANTINE_AND_CONTINUE}: The poison message is set aside, and processing continues
         * for subsequent messages.
         *
         * @param poisonPolicy the {@link PoisonPolicy} defining the behavior for handling poison messages
         */
        public void setPoisonPolicy(PoisonPolicy poisonPolicy) {
            this.poisonPolicy = poisonPolicy;
        }
    }

    public static class CdcSlotProperties {
        /**
         * Logical WAL consumer-group. This is the main knob.
         * Examples: "default", "orders", "billing"
         */
        private String group = "default";

        /**
         * Slot mode / ownership semantics
         */
        private PgSlotMode mode = PgSlotMode.CREATE_IF_MISSING;
        private String     name;

        public String getGroup() {
            return group;
        }

        public void setGroup(String group) {
            this.group = group;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public PgSlotMode getMode() {
            return mode;
        }

        public void setMode(PgSlotMode mode) {
            this.mode = mode;
        }

    }
}
