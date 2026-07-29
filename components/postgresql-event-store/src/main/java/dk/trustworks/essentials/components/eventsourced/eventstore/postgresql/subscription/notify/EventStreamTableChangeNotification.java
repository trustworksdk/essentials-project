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

import dk.trustworks.essentials.components.foundation.postgresql.ListenNotify.SqlOperation;
import dk.trustworks.essentials.components.foundation.postgresql.TableChangeNotification;

/**
 * Concrete {@link TableChangeNotification} subtype carrying the per-row change events that
 * arrive from PostgreSQL when an event-stream table receives an INSERT. The framework's
 * {@code MultiTableChangeListener} requires a concrete class to deserialize the NOTIFY
 * payload into; this is that class for event-stream tables.
 * <p>
 * Carries no extra fields beyond the base — the only information the
 * {@link NotifyAwareEventStorePollingOptimizer} needs from a notification is the
 * <i>fact</i> of an insert on a specific table (the table name and operation are inherited
 * from {@link TableChangeNotification}). The optimizer collapses N notifications into a
 * single "epoch advanced" transition, so individual payload contents are intentionally
 * unused.
 */
public class EventStreamTableChangeNotification extends TableChangeNotification {

    /** No-arg constructor required by Jackson deserialisation through MultiTableChangeListener. */
    public EventStreamTableChangeNotification() {
    }

    public EventStreamTableChangeNotification(String tableName, SqlOperation operation) {
        super(tableName, operation);
    }
}
