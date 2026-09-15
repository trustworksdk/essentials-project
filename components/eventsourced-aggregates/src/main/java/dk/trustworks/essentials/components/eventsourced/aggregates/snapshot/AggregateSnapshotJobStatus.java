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

/**
 * Enum representing the possible statuses of an {@link AggregateSnapshotJob}.
 * <ul>
 *   <li>{@code PENDING}: Created but not yet locked by a worker.</li>
 *   <li>{@code PROCESSING}: Currently in progress (a worker has locked the row).</li>
 *   <li>{@code FAILED}: Worker raised an exception. The job is eligible for retry once
 *       {@code next_attempt_ts} elapses, until {@code attempts >= maxRetries}.</li>
 *   <li>{@code PARKED}: Retry budget exhausted (poison pill). The job is no longer
 *       picked up by polling, but the row is kept so operators can inspect the failure.
 *       Re-enqueueing the same {@code (aggregate_impl_type, aggregate_id, last_included_event_order)}
 *       will replace a {@code PARKED} row's payload with the new one and reset its retry state.</li>
 * </ul>
 */
public enum AggregateSnapshotJobStatus {
    PENDING,
    PROCESSING,
    FAILED,
    PARKED
}
