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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamPersistenceStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateTypePersistenceStrategy;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.notify.EventStreamTableChangeNotification;
import dk.trustworks.essentials.components.foundation.postgresql.ListenNotify;
import dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener;
import dk.trustworks.essentials.components.foundation.postgresql.TableChangeNotification;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * S1 (NOTIFY-driven polling wake-up) bootstrap. Created by the event-store Spring
 * autoconfig when {@code essentials.eventstore.subscription-manager.notify-polling.enabled=true}.
 * <p>
 * On construction, this enables notify triggers on the persistence strategy, so that every
 * event-stream table (already-registered and future) gets:
 * <ol>
 *   <li>An {@code AFTER INSERT} {@code pg_notify} trigger, described as part of the table's schema
 *       (see {@link ListenNotify#changeNotificationTriggerStatements}) - so it is created by
 *       whichever applier owns the event store's schema, under the framework's bootstrap lock,
 *       exactly like the table.</li>
 *   <li>Registration with the shared {@link MultiTableChangeListener} for
 *       {@link EventStreamTableChangeNotification}s.</li>
 * </ol>
 * Skipped (with a WARN) when the configured persistence strategy is not a
 * {@link SeparateTablePerAggregateTypePersistenceStrategy} — S1 is specific to the
 * standard table-per-aggregate-type strategy.
 */
public final class EventStoreNotifyPollingBootstrap {
    private static final Logger log = LoggerFactory.getLogger(EventStoreNotifyPollingBootstrap.class);

    /**
     * @deprecated the trigger is now part of the event store's schema, so {@code jdbi} is not used. Use
     * {@link #EventStoreNotifyPollingBootstrap(AggregateEventStreamPersistenceStrategy, MultiTableChangeListener)}
     */
    @Deprecated
    public EventStoreNotifyPollingBootstrap(Jdbi jdbi,
                                            AggregateEventStreamPersistenceStrategy<SeparateTablePerAggregateEventStreamConfiguration> persistenceStrategy,
                                            MultiTableChangeListener<TableChangeNotification> multiTableChangeListener) {
        this(persistenceStrategy, multiTableChangeListener);
    }

    public EventStoreNotifyPollingBootstrap(AggregateEventStreamPersistenceStrategy<SeparateTablePerAggregateEventStreamConfiguration> persistenceStrategy,
                                            MultiTableChangeListener<TableChangeNotification> multiTableChangeListener) {
        requireNonNull(persistenceStrategy, "persistenceStrategy cannot be null");
        requireNonNull(multiTableChangeListener, "multiTableChangeListener cannot be null");

        if (!(persistenceStrategy instanceof SeparateTablePerAggregateTypePersistenceStrategy strategy)) {
            log.warn("Notify-polling is enabled but the persistence strategy is {} (not {}). "
                             + "S1 trigger installation skipped — feature only supported for the "
                             + "standard table-per-aggregate-type strategy.",
                     persistenceStrategy.getClass().getName(),
                     SeparateTablePerAggregateTypePersistenceStrategy.class.getSimpleName());
            return;
        }

        strategy.enableNotifyTriggers(tableName -> {
            multiTableChangeListener.listenToNotificationsFor(tableName, EventStreamTableChangeNotification.class);
            log.info("Notify-polling: pg_notify trigger described and listener registered for table='{}'", tableName);
        });

        log.info("Notify-polling bootstrap enabled notify triggers on the persistence strategy");
    }
}
