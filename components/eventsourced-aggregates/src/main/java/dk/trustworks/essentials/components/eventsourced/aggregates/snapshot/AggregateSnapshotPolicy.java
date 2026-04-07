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
    boolean enabled() default true;

    SnapshotExecutionMode mode() default SnapshotExecutionMode.SYNC;

    int everyNEvents() default 100;

    SnapshotDeletionMode deletionMode() default SnapshotDeletionMode.DELETE_ALL_HISTORIC;

    int keepLastSnapshots() default 1;

    String aggregateType() default "";
}
