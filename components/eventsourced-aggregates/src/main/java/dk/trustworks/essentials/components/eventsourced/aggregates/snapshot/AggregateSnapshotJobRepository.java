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

    List<AggregateSnapshotJob> lockNextBatch(int batchSize, OffsetDateTime now);

    void markCompleted(UUID jobId);

    void markFailed(UUID jobId, String error, OffsetDateTime nextAttemptTs);
}
