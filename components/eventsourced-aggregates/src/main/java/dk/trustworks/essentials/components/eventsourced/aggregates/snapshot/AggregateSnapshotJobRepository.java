package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Repository interface for managing {@link AggregateSnapshotJob} entities.
 * <p>
 * This interface defines the operations to enqueue, process, and manage the lifecycle of
 * aggregate snapshot jobs. Aggregate snapshot jobs are responsible for handling
 * tasks related to aggregate snapshots, such as creation, deletion, and updates.
 */
public interface AggregateSnapshotJobRepository {
    void enqueue(AggregateSnapshotJob job);

    /**
     * Locks the next batch of jobs eligible for processing.
     * <p>
     * A job is eligible if:
     * <ul>
     *     <li>Its status is {@code PENDING} or {@code FAILED} and {@code next_attempt_ts <= now}, or</li>
     *     <li>Its status is {@code PROCESSING} and the worker that locked it is presumed dead because
     *         the lock was acquired before {@code reclaimStaleStartedBefore} (or has no recorded start time).</li>
     * </ul>
     *
     * @param batchSize                  maximum number of jobs to lock
     * @param now                        the current time used to evaluate {@code next_attempt_ts}
     * @param reclaimStaleStartedBefore  jobs in {@code PROCESSING} with {@code processing_started_ts}
     *                                   strictly older than this timestamp are reclaimed; pass
     *                                   {@link OffsetDateTime#MIN} to disable reclaim
     */
    List<AggregateSnapshotJob> lockNextBatch(int batchSize, OffsetDateTime now, OffsetDateTime reclaimStaleStartedBefore);

    /**
     * Convenience overload that disables stale-{@code PROCESSING} reclaim. Equivalent to calling
     * {@link #lockNextBatch(int, OffsetDateTime, OffsetDateTime)} with {@link OffsetDateTime#MIN}.
     */
    default List<AggregateSnapshotJob> lockNextBatch(int batchSize, OffsetDateTime now) {
        return lockNextBatch(batchSize, now, OffsetDateTime.MIN);
    }

    void markCompleted(UUID jobId);

    void markFailed(UUID jobId, String error, OffsetDateTime nextAttemptTs);

    /**
     * Permanently move the job to {@link AggregateSnapshotJobStatus#PARKED} after the retry budget
     * has been exhausted. Parked rows are NOT picked up by polling, but remain in the table so
     * operators can inspect the failure. A subsequent {@link #enqueue(AggregateSnapshotJob)} for
     * the same {@code (aggregate_impl_type, aggregate_id, last_included_event_order)} replaces
     * the parked row with the new payload and resets retry state.
     */
    void markParked(UUID jobId, String error, OffsetDateTime parkedAt);
}
