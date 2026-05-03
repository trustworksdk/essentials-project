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
