package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

/**
 * Enum representing the possible statuses of an {@link AggregateSnapshotJob}.
 * <ul>
 *   <li>{@code PENDING}: Indicates that the snapshot job has been created but has not started processing yet.</li>
 *   <li>{@code PROCESSING}: Indicates that the snapshot job is currently in progress.</li>
 *   <li>{@code FAILED}: Indicates that the snapshot job has encountered an error and failed to complete.</li>
 * </ul>
 *
 * This enum is primarily used to track and manage the lifecycle state of aggregate snapshot jobs.
 */
enum AggregateSnapshotJobStatus {
    PENDING,
    PROCESSING,
    FAILED
}
