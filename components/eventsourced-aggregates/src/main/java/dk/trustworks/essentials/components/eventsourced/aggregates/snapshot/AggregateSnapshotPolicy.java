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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import java.lang.annotation.*;

/**
 * Annotation to define a snapshot policy for aggregates in an event-sourced system.
 * This policy specifies the conditions under which snapshots are created, how they are executed,
 * and how old snapshots are managed within the system.
 *
 * Attributes:
 * - `enabled`: Indicates whether snapshotting is enabled for the aggregate.
 * - `mode`: Specifies the execution mode for snapshot creation, such as synchronous or asynchronous.
 * - `everyNEvents`: Defines the interval of events after which a snapshot is triggered.
 * - `deletionMode`: Determines the strategy for handling old snapshots, such as deleting all or keeping a limited number.
 * - `keepLastSnapshots`: Specifies the number of snapshots to retain if a "keep" strategy is selected.
 * - `aggregateType`: Identifies the type of aggregate for which this policy is applied.
 *
 * This annotation should be used at the class level to configure snapshotting behavior
 * for aggregates in scenarios where consistent state reconstruction and data retention
 * are critical.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AggregateSnapshotPolicy {
    /**
     * Indicates whether snapshotting is enabled for the aggregate.
     *
     * @return {@code true} if snapshotting is enabled; {@code false} otherwise
     */
    boolean enabled() default true;

    /**
     * Specifies the execution mode for creating aggregate snapshots.
     * The execution mode determines how and when snapshots are persisted.
     *
     * @return The snapshot execution mode, which can be one of the following:
     *         {@code SYNC} for synchronous persistence,
     *         {@code ASYNC_IN_MEMORY} for asynchronous in-memory persistence,
     *         or {@code ASYNC_DURABLE} for asynchronous durable persistence.
     */
    SnapshotExecutionMode mode() default SnapshotExecutionMode.SYNC;

    /**
     * Defines the interval of events after which a snapshot is triggered.
     * This determines how frequently snapshots are created in an event-sourced system.
     *
     * @return The number of events that must occur before a snapshot is created.
     *         Defaults to 100.
     */
    int everyNEvents() default 100;

    /**
     * Determines the strategy for handling old snapshots in an event-sourced system.
     * The deletion mode specifies how historical snapshots are managed when new snapshots are created.
     *
     * @return The snapshot deletion mode, which can be one of the following:
     *         {@code DELETE_ALL_HISTORIC} to remove all previously created snapshots,
     *         or {@code KEEP_LAST_N} to retain a specified number of the most recent snapshots.
     */
    SnapshotDeletionMode deletionMode() default SnapshotDeletionMode.DELETE_ALL_HISTORIC;

    /**
     * Specifies the number of most recent snapshots to retain when the snapshot
     * deletion mode is set to keep a limited number of snapshots.
     *
     * @return The number of snapshots to retain. Defaults to 1, meaning only the
     *         most recent snapshot is kept if a "keep" strategy is used.
     */
    int keepLastSnapshots() default 1;

    /**
     * Specifies the type of the aggregate this snapshot policy applies to.
     * The aggregate type is typically used to identify and differentiate
     * between various event-sourced aggregates.
     *
     * @return The name of the aggregate type as a {@code String}. Defaults to an empty string if not specified.
     */
    String aggregateType() default "";
}
