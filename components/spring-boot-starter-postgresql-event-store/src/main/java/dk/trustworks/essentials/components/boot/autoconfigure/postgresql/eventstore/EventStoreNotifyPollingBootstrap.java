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
import dk.trustworks.essentials.components.foundation.postgresql.ListenNotify.SqlOperation;
import dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener;
import dk.trustworks.essentials.components.foundation.postgresql.PostgresqlUtil;
import dk.trustworks.essentials.components.foundation.postgresql.TableChangeNotification;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * S1 (NOTIFY-driven polling wake-up) bootstrap. Created by the event-store Spring
 * autoconfig when {@code essentials.eventstore.subscription-manager.notify-polling.enabled=true}.
 * <p>
 * On construction, this wires a {@code NotifyTriggerInstaller} into the persistence
 * strategy so that every event-stream table (already-registered and future) gets:
 * <ol>
 *   <li>An idempotent {@code AFTER INSERT} {@code pg_notify} trigger via
 *       {@link ListenNotify#addChangeNotificationTriggerToTable}.</li>
 *   <li>Registration with the shared {@link MultiTableChangeListener} for
 *       {@link EventStreamTableChangeNotification}s.</li>
 * </ol>
 * Each install runs inside a {@link Jdbi#useTransaction} block that first calls
 * {@link PostgresqlUtil#acquireBootstrapLock} — concurrent JVMs starting against the
 * same database serialise DDL through the same advisory lock the table-creation path
 * (P6) already uses, eliminating CREATE-OR-REPLACE races.
 * <p>
 * Skipped (with a WARN) when the configured persistence strategy is not a
 * {@link SeparateTablePerAggregateTypePersistenceStrategy} — S1 is specific to the
 * standard table-per-aggregate-type strategy.
 */
public final class EventStoreNotifyPollingBootstrap {
    private static final Logger log = LoggerFactory.getLogger(EventStoreNotifyPollingBootstrap.class);

    public EventStoreNotifyPollingBootstrap(Jdbi jdbi,
                                            AggregateEventStreamPersistenceStrategy<SeparateTablePerAggregateEventStreamConfiguration> persistenceStrategy,
                                            MultiTableChangeListener<TableChangeNotification> multiTableChangeListener) {
        requireNonNull(jdbi, "jdbi cannot be null");
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

        strategy.enableNotifyTriggerInstallation(tableName ->
                jdbi.useTransaction(handle -> {
                    PostgresqlUtil.acquireBootstrapLock(handle);
                    ListenNotify.addChangeNotificationTriggerToTable(handle,
                                                                     tableName,
                                                                     List.of(SqlOperation.INSERT));
                    multiTableChangeListener.listenToNotificationsFor(
                            tableName,
                            EventStreamTableChangeNotification.class);
                    log.info("Notify-polling: installed pg_notify trigger and registered listener for table='{}'",
                             tableName);
                }));

        log.info("Notify-polling bootstrap registered NotifyTriggerInstaller on persistence strategy");
    }
}
