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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify;

import dk.trustworks.essentials.components.foundation.postgresql.TableChangeNotification;
import dk.trustworks.essentials.reactive.EventBus;
import dk.trustworks.essentials.reactive.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Bridges the framework's {@code MultiTableChangeListener} output ({@link EventBus}
 * publishing {@link TableChangeNotification}s) into per-table epoch counters that
 * {@link NotifyAwareEventStorePollingOptimizer} reads.
 * <p>
 * Constructed with the framework's shared {@link EventBus}. On construction it subscribes
 * an async event handler that:
 * <ul>
 *   <li>filters for {@link TableChangeNotification} events (the bus may carry other event
 *       types — durable-queue notifications, custom app events, etc.);</li>
 *   <li>bumps the {@link AtomicLong} epoch counter for the notification's table name in a
 *       {@link ConcurrentHashMap};</li>
 *   <li>creates the counter lazily on first observation so we don't need to know the full
 *       set of event-stream tables up front.</li>
 * </ul>
 * Lookups via {@link #currentEpoch(String)} return {@code 0L} for tables we've never seen
 * a notification for — that's the correct "no notify since the optimizer started"
 * baseline because the optimizer's {@code lastSeenEpoch} also starts at {@code 0L}.
 * <p>
 * Thread-safe by construction: the map and per-key counters are concurrent; the handler
 * runs on the bus's async scheduler; optimizer reads happen from per-subscription
 * scheduler threads. No external synchronization is required.
 * <p>
 * The collapse from "N notifications" to "epoch incremented N times" is fine — the
 * optimizer only cares whether the epoch <em>changed</em> since its last poll, not the
 * absolute count. {@link AtomicLong#incrementAndGet()} provides the strict-monotonicity
 * guarantee the optimizer's compare-and-update relies on.
 */
public final class NotifyEpochSource {
    private static final Logger log = LoggerFactory.getLogger(NotifyEpochSource.class);

    private final EventBus                          eventBus;
    private final EventHandler                      handler;
    private final ConcurrentMap<String, AtomicLong> epochByTable = new ConcurrentHashMap<>();

    public NotifyEpochSource(EventBus eventBus) {
        this.eventBus = requireNonNull(eventBus, "eventBus cannot be null");
        // Inline handler so we hold a reference for later unsubscribe. Anonymous-class
        // handlers can't be used with EventBus#removeAsyncSubscriber (instance equality).
        this.handler = event -> {
            if (event instanceof TableChangeNotification notification) {
                String table = notification.getTableName();
                if (table != null) {
                    epochByTable.computeIfAbsent(table, ignored -> new AtomicLong())
                                .incrementAndGet();
                    if (log.isTraceEnabled()) {
                        log.trace("Notify epoch advanced: table='{}' op='{}' newEpoch='{}'",
                                  table, notification.getOperation(),
                                  epochByTable.get(table).get());
                    }
                }
            }
        };
        eventBus.addAsyncSubscriber(this.handler);
        log.info("NotifyEpochSource subscribed to EventBus for TableChangeNotification events");
    }

    /**
     * Current epoch for {@code tableName}. Returns {@code 0L} when no notification has yet
     * been observed for that table — semantically "no notify since the optimizer's
     * baseline".
     */
    public long currentEpoch(String tableName) {
        AtomicLong counter = epochByTable.get(tableName);
        return counter == null ? 0L : counter.get();
    }

    /**
     * Unsubscribe from the bus — used at shutdown to release the handler. Calling this
     * leaves the epoch map intact (subsequent reads are static), so any in-flight
     * optimizer calls that come after shutdown still return a sane value.
     */
    public void close() {
        try {
            eventBus.removeAsyncSubscriber(handler);
            log.info("NotifyEpochSource unsubscribed from EventBus");
        } catch (Exception e) {
            log.debug("Failed to unsubscribe NotifyEpochSource handler (likely already removed) '{}'",
                      e.toString());
        }
    }
}
