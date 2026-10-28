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

package dk.trustworks.essentials.components.foundation.postgresql;

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.reactive.EventBus;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.event.Level;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Builder for a {@link MultiTableChangeListener} - see {@link MultiTableChangeListener#builder()}.<br>
 * Required: {@link #setJdbi(Jdbi)}, {@link #setJsonSerializer(JSONSerializer)} and {@link #setEventBus(EventBus)}.
 * Every other setting has a default, which matches the Spring Boot starter's.
 * <pre>{@code
 * MultiTableChangeListener<TableChangeNotification> listener = MultiTableChangeListener.builder()
 *     .setJdbi(jdbi)
 *     .setJsonSerializer(jsonSerializer)
 *     .setEventBus(eventBus)
 *     .build();
 * }</pre>
 */
public final class MultiTableChangeListenerBuilder {
    /**
     * Default {@link #setPollingInterval(Duration)}
     */
    public static final Duration DEFAULT_POLLING_INTERVAL = Duration.ofMillis(50);

    private Jdbi                       jdbi;
    private JSONSerializer             jsonSerializer;
    private EventBus                   eventBus;
    private Duration                   pollingInterval              = DEFAULT_POLLING_INTERVAL;
    private boolean                    filterDuplicateNotifications = true;
    private ChannelDeduplicationPolicy channelDeduplicationPolicy   = ChannelDeduplicationPolicy.allChannels();
    private Duration                   livenessCheckInterval        = MultiTableChangeListener.DEFAULT_LIVENESS_CHECK_INTERVAL;
    private Level                      connectionLossLogLevel       = Level.WARN;
    private Duration                   connectionLossLogInterval    = MultiTableChangeListener.DEFAULT_CONNECTION_LOSS_LOG_INTERVAL;
    private String                     pollThreadName               = MultiTableChangeListener.DEFAULT_POLL_THREAD_NAME;
    private Jdbi                       listenerJdbi;
    private DataSource                 listenerDataSource;

    /**
     * Use {@link MultiTableChangeListener#builder()}
     */
    MultiTableChangeListenerBuilder() {
    }

    /**
     * <b>Required.</b> The {@link Jdbi} the LISTEN connection is opened from - normally borrowing it from the
     * application's connection pool for as long as the listener polls - unless {@link #setListenerJdbi(Jdbi)} or
     * {@link #setListenerDataSource(DataSource)} gives the listener a connection source of its own
     *
     * @param jdbi the {@link Jdbi} instance
     * @return this builder instance
     */
    public MultiTableChangeListenerBuilder setJdbi(Jdbi jdbi) {
        this.jdbi = requireNonNull(jdbi, "No jdbi provided");
        return this;
    }

    /**
     * <b>Required.</b> The serializer that maps each notification payload to the {@link TableChangeNotification} type
     * registered for its table
     *
     * @param jsonSerializer the {@link JSONSerializer}
     * @return this builder instance
     */
    public MultiTableChangeListenerBuilder setJsonSerializer(JSONSerializer jsonSerializer) {
        this.jsonSerializer = requireNonNull(jsonSerializer, "No jsonSerializer provided");
        return this;
    }

    /**
     * <b>Required.</b> The {@link EventBus} every notification is published on
     *
     * @param eventBus the {@link EventBus}
     * @return this builder instance
     */
    public MultiTableChangeListenerBuilder setEventBus(EventBus eventBus) {
        this.eventBus = requireNonNull(eventBus, "No eventBus provided");
        return this;
    }

    /**
     * How often the LISTEN connection is polled for notifications. Default {@link #DEFAULT_POLLING_INTERVAL} (50 ms)
     *
     * @param pollingInterval the polling interval, at least 1 millisecond
     * @return this builder instance
     */
    public MultiTableChangeListenerBuilder setPollingInterval(Duration pollingInterval) {
        requireNonNull(pollingInterval, "No pollingInterval provided");
        requireTrue(pollingInterval.toMillis() > 0, "pollingInterval must be at least 1 millisecond");
        this.pollingInterval = pollingInterval;
        return this;
    }

    /**
     * Whether duplicate notifications within one poll are filtered, using the {@link NotificationDuplicationFilter}'s
     * registered with {@link MultiTableChangeListener#addDuplicationFilterAsFirst(NotificationDuplicationFilter)} /
     * {@link MultiTableChangeListener#addDuplicationFilterAsLast(NotificationDuplicationFilter)}. Duplicates are detected
     * per channel. Default {@code true}
     *
     * @param filterDuplicateNotifications whether duplicate notifications are filtered
     * @return this builder instance
     */
    public MultiTableChangeListenerBuilder setFilterDuplicateNotifications(boolean filterDuplicateNotifications) {
        this.filterDuplicateNotifications = filterDuplicateNotifications;
        return this;
    }

    /**
     * Which channels are de-duplicated when {@link #setFilterDuplicateNotifications(boolean)} is {@code true}.
     * Default {@link ChannelDeduplicationPolicy#allChannels()}
     *
     * @param channelDeduplicationPolicy the per-channel de-duplication policy
     * @return this builder instance
     */
    public MultiTableChangeListenerBuilder setChannelDeduplicationPolicy(ChannelDeduplicationPolicy channelDeduplicationPolicy) {
        this.channelDeduplicationPolicy = requireNonNull(channelDeduplicationPolicy, "No channelDeduplicationPolicy provided");
        return this;
    }

    /**
     * How often the LISTEN connection is probed with {@link Connection#isValid(int)}. A non-blocking read for
     * notifications never fails on a half-open connection, so the probe is what detects one. Each probe waits at most
     * this interval, and never more than 5 seconds, for the database to answer. {@link Duration#ZERO} disables the
     * probe. Default {@link MultiTableChangeListener#DEFAULT_LIVENESS_CHECK_INTERVAL} (10 seconds)
     *
     * @param livenessCheckInterval the interval between two liveness probes, or {@link Duration#ZERO}
     * @return this builder instance
     */
    public MultiTableChangeListenerBuilder setLivenessCheckInterval(Duration livenessCheckInterval) {
        requireNonNull(livenessCheckInterval, "No livenessCheckInterval provided");
        requireFalse(livenessCheckInterval.isNegative(), "livenessCheckInterval must not be negative");
        this.livenessCheckInterval = livenessCheckInterval;
        return this;
    }

    /**
     * The level a lost LISTEN connection is reported at. Default {@link Level#WARN}
     *
     * @param connectionLossLogLevel the log level
     * @return this builder instance
     * @see #setConnectionLossLogInterval(Duration)
     */
    public MultiTableChangeListenerBuilder setConnectionLossLogLevel(Level connectionLossLogLevel) {
        this.connectionLossLogLevel = requireNonNull(connectionLossLogLevel, "No connectionLossLogLevel provided");
        return this;
    }

    /**
     * The minimum interval between two connection-loss reports at the {@link #setConnectionLossLogLevel(Level)}. A
     * database that stays unreachable fails every poll; the failures in between two reports are counted, logged at
     * DEBUG, and the count is included in the next report. {@link Duration#ZERO} reports every failure.
     * Default {@link MultiTableChangeListener#DEFAULT_CONNECTION_LOSS_LOG_INTERVAL} (1 minute)
     *
     * @param connectionLossLogInterval the minimum interval between two reports
     * @return this builder instance
     */
    public MultiTableChangeListenerBuilder setConnectionLossLogInterval(Duration connectionLossLogInterval) {
        requireNonNull(connectionLossLogInterval, "No connectionLossLogInterval provided");
        requireFalse(connectionLossLogInterval.isNegative(), "connectionLossLogInterval must not be negative");
        this.connectionLossLogInterval = connectionLossLogInterval;
        return this;
    }

    /**
     * The name of the poll thread. Default {@link MultiTableChangeListener#DEFAULT_POLL_THREAD_NAME}
     *
     * @param pollThreadName the thread name
     * @return this builder instance
     */
    public MultiTableChangeListenerBuilder setPollThreadName(String pollThreadName) {
        this.pollThreadName = requireNonBlank(pollThreadName, "No pollThreadName provided");
        return this;
    }

    /**
     * Optional: a {@link Jdbi} that the LISTEN connection is opened from instead of {@link #setJdbi(Jdbi)}'s - typically
     * one over a non-pooled {@link DataSource}, so the listener does not hold one of the application pool's connections
     * for as long as it polls. Mutually exclusive with {@link #setListenerDataSource(DataSource)}
     *
     * @param listenerJdbi the {@link Jdbi} the LISTEN connection is opened from
     * @return this builder instance
     */
    public MultiTableChangeListenerBuilder setListenerJdbi(Jdbi listenerJdbi) {
        this.listenerJdbi = requireNonNull(listenerJdbi, "No listenerJdbi provided");
        return this;
    }

    /**
     * Optional: a {@link DataSource} that the LISTEN connection is opened from instead of {@link #setJdbi(Jdbi)}'s -
     * typically a non-pooled one, so the listener does not hold one of the application pool's connections for as long
     * as it polls. Mutually exclusive with {@link #setListenerJdbi(Jdbi)}
     *
     * @param listenerDataSource the {@link DataSource} the LISTEN connection is opened from
     * @return this builder instance
     */
    public MultiTableChangeListenerBuilder setListenerDataSource(DataSource listenerDataSource) {
        this.listenerDataSource = requireNonNull(listenerDataSource, "No listenerDataSource provided");
        return this;
    }

    /**
     * Build the {@link MultiTableChangeListener}. It does not poll until it is {@link MultiTableChangeListener#start()}ed
     * (or, if it is never started through {@code Lifecycle}, until the first table is registered)
     *
     * @param <T> the {@link TableChangeNotification} base type of the notifications the listener publishes
     * @return the new {@link MultiTableChangeListener}
     */
    public <T extends TableChangeNotification> MultiTableChangeListener<T> build() {
        requireNonNull(jdbi, "No jdbi provided - setJdbi(...) is required");
        requireNonNull(jsonSerializer, "No jsonSerializer provided - setJsonSerializer(...) is required");
        requireNonNull(eventBus, "No eventBus provided - setEventBus(...) is required");
        requireFalse(listenerJdbi != null && listenerDataSource != null, "Set either setListenerJdbi(...) or setListenerDataSource(...), not both");
        return new MultiTableChangeListener<>(this);
    }

    Jdbi listenConnectionJdbi() {
        if (listenerJdbi != null) {
            return listenerJdbi;
        }
        if (listenerDataSource != null) {
            return Jdbi.create(listenerDataSource);
        }
        return jdbi;
    }

    JSONSerializer jsonSerializer() {
        return jsonSerializer;
    }

    EventBus eventBus() {
        return eventBus;
    }

    Duration pollingInterval() {
        return pollingInterval;
    }

    boolean filterDuplicateNotifications() {
        return filterDuplicateNotifications;
    }

    ChannelDeduplicationPolicy channelDeduplicationPolicy() {
        return channelDeduplicationPolicy;
    }

    Duration livenessCheckInterval() {
        return livenessCheckInterval;
    }

    Level connectionLossLogLevel() {
        return connectionLossLogLevel;
    }

    Duration connectionLossLogInterval() {
        return connectionLossLogInterval;
    }

    String pollThreadName() {
        return pollThreadName;
    }
}
