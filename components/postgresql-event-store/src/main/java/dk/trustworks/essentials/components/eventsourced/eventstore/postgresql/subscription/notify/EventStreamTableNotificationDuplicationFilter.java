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

import dk.trustworks.essentials.components.foundation.postgresql.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Registers event-stream tables with a {@link MultiTableChangeListener} for notify-polling, and makes the listener publish
 * <b>at most one</b> {@link EventStreamTableChangeNotification} per event-stream table per poll.
 * <p>
 * The notify trigger of an event-stream table sends the same payload for every insert -
 * <code>{"table_name":"orders_events","sql_operation":"INSERT"}</code> - so the notifications one poll reads for a table
 * are identical, and the {@link NotifyEpochSource} only needs to know that the table changed. Without this filter the
 * listener published every one of them to its {@code EventBus}: a burst of N committed appends became N bus events, and
 * once a slow asynchronous subscriber on that bus (in Spring the application's shared {@code EventStoreEventBus}) let the
 * bus's buffer fill up, the listener's poll thread parked on every publish, delaying every later wake-up - and the durable
 * queues' wake-ups, which use the same listener - until the slow subscriber had caught up.
 * <p>
 * This is a {@link NotificationDuplicationFilter}: it returns the table name as the duplication key for the event-stream
 * tables registered through {@link #listenTo(String)}, and nothing for any other notification, so other tables'
 * notifications (durable queues, an application's own tables) are published exactly as before. It is added as the
 * <b>last</b> filter, so a filter registered by another component keeps deciding for its own notifications. Like every
 * duplication filter it only applies while the listener filters duplicates
 * ({@link MultiTableChangeListener#isFilterDuplicateNotifications()}, on by default, and
 * {@code essentials.multi-table-change-listener.filter-duplicate-notifications} in Spring) and while the listener's
 * {@link ChannelDeduplicationPolicy} de-duplicates the event-stream tables' channels (every channel by default).
 * <p>
 * Usage, without Spring:
 * <pre>{@code
 * var eventStreamTables = EventStreamTableNotificationDuplicationFilter.registerWith(multiTableChangeListener);
 * persistenceStrategy.enableNotifyTriggers(eventStreamTables::listenTo);
 * var epochSource = new NotifyEpochSource(multiTableChangeListener.getEventBus());
 * }</pre>
 */
public final class EventStreamTableNotificationDuplicationFilter implements NotificationDuplicationFilter {
    private static final Logger log = LoggerFactory.getLogger(EventStreamTableNotificationDuplicationFilter.class);

    private final MultiTableChangeListener<TableChangeNotification> multiTableChangeListener;
    /**
     * The registered event-stream tables, in lower case - the table name in the payload is PostgreSQL's {@code TG_TABLE_NAME}
     */
    private final Set<String>                                       eventStreamTables = ConcurrentHashMap.newKeySet();

    private EventStreamTableNotificationDuplicationFilter(MultiTableChangeListener<TableChangeNotification> multiTableChangeListener) {
        this.multiTableChangeListener = requireNonNull(multiTableChangeListener, "multiTableChangeListener cannot be null");
    }

    /**
     * Create a filter and add it as the last {@link NotificationDuplicationFilter} of {@code multiTableChangeListener}
     *
     * @param multiTableChangeListener the listener the event-stream tables are registered with
     * @return the filter; register each event-stream table through {@link #listenTo(String)}
     */
    public static EventStreamTableNotificationDuplicationFilter registerWith(MultiTableChangeListener<TableChangeNotification> multiTableChangeListener) {
        var filter = new EventStreamTableNotificationDuplicationFilter(multiTableChangeListener);
        multiTableChangeListener.addDuplicationFilterAsLast(filter);
        if (!multiTableChangeListener.isFilterDuplicateNotifications()) {
            log.warn("The MultiTableChangeListener does not filter duplicate notifications, so it publishes every event-stream table notification "
                             + "to its EventBus instead of one per table per poll; a slow asynchronous subscriber on that bus can then delay "
                             + "notify-polling wake-ups. Enable duplicate filtering (essentials.multi-table-change-listener.filter-duplicate-notifications=true in Spring)");
        }
        return filter;
    }

    /**
     * Collapse the notifications of {@code eventStreamTableName} to one per poll, then LISTEN to them as
     * {@link EventStreamTableChangeNotification}s. Pass it to
     * {@code SeparateTablePerAggregateTypePersistenceStrategy#enableNotifyTriggers(Consumer)}
     *
     * @param eventStreamTableName the event-stream table
     */
    public void listenTo(String eventStreamTableName) {
        requireNonNull(eventStreamTableName, "eventStreamTableName cannot be null");
        eventStreamTables.add(ListenNotify.resolveTableChangeChannelName(eventStreamTableName));
        multiTableChangeListener.listenToNotificationsFor(eventStreamTableName, EventStreamTableChangeNotification.class);
    }

    @Override
    public Optional<String> extractDuplicationKey(JsonNode notificationParameterJson) {
        var tableName = notificationParameterJson.get(ListenNotify.TABLE_NAME);
        if (tableName == null || !tableName.isString()) {
            return Optional.empty();
        }
        var key = tableName.asString().toLowerCase(Locale.ROOT);
        return eventStreamTables.contains(key) ? Optional.of(key) : Optional.empty();
    }

    @Override
    public String toString() {
        return "EventStreamTableNotificationDuplicationFilter" + eventStreamTables;
    }
}
