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

/**
 * Callback invoked by the persistence strategy when a new event-stream table is created
 * (and once per table when notify-polling is enabled mid-deployment), giving the S1
 * autoconfig the chance to install a {@code pg_notify} trigger and register the table
 * with the framework's {@code MultiTableChangeListener}.
 * <p>
 * Kept as a single-method functional interface so the persistence strategy stays
 * ignorant of the notify infrastructure — it just invokes a callback per table. The
 * actual implementation (in the Spring autoconfig) calls
 * {@code ListenNotify.addChangeNotificationTriggerToTable} and
 * {@code multiTableChangeListener.listenToNotificationsFor(...)}.
 * <p>
 * Implementations must be idempotent: the persistence strategy may invoke the installer
 * for tables that already have triggers installed (during the start-of-day sweep when
 * notify-polling is enabled for an existing database) and the framework's
 * {@code ListenNotify} APIs already drop-and-recreate triggers safely.
 */
@FunctionalInterface
public interface NotifyTriggerInstaller {
    /**
     * Install (or re-install) the notify trigger for {@code eventStreamTableName} and
     * register the table with the change listener. Called by the persistence strategy
     * inside the same unit-of-work that just created the table.
     */
    void installFor(String eventStreamTableName);
}
