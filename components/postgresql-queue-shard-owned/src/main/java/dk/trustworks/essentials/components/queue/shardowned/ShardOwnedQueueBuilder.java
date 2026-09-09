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

package dk.trustworks.essentials.components.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.spi.QueueName;

import javax.sql.DataSource;
import java.sql.SQLException;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link ShardOwnedQueue}.
 * <p>
 * The reason to prefer it over the constructor is not ceremony. A queue is identified by a
 * {@code short} next to an {@code int} next to a {@code String} — {@code (queueId, shardCount,
 * instanceId)} — and transposing the first two compiles, runs, and silently addresses the wrong queue
 * with the wrong number of shards. Naming them at the call site is what makes that impossible.
 */
public final class ShardOwnedQueueBuilder {
    private DataSource         dataSource;
    private short              queueId;
    private int                shardCount;
    private String             instanceId;
    private QueueName          queueName;
    private ShardRuntime       runtime;
    private ShardOwnerMetrics  metrics;
    private int                parallelConsumers = 8;
    private boolean            localHandoffEnabled = true;

    public ShardOwnedQueueBuilder setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    public ShardOwnedQueueBuilder setQueueId(short queueId) {
        this.queueId = queueId;
        return this;
    }

    /** Fixed for the life of the queue: changing it re-routes keys and breaks ordering. */
    public ShardOwnedQueueBuilder setShardCount(int shardCount) {
        this.shardCount = shardCount;
        return this;
    }

    public ShardOwnedQueueBuilder setInstanceId(String instanceId) {
        this.instanceId = instanceId;
        return this;
    }

    /**
     * The threads and connections this queue uses. Left unset, it borrows the runtime shared per
     * {@code DataSource}, which is the right default for every process with more than one queue.
     */
    public ShardOwnedQueueBuilder setRuntime(ShardRuntime runtime) {
        this.runtime = runtime;
        return this;
    }

    public ShardOwnedQueueBuilder setMetrics(ShardOwnerMetrics metrics) {
        this.metrics = metrics;
        return this;
    }

    /** Handlers in flight for this consumer. See {@code ConsumerOptions.parallelConsumers}. */
    public ShardOwnedQueueBuilder setParallelConsumers(int parallelConsumers) {
        this.parallelConsumers = parallelConsumers;
        return this;
    }

    /** Turn Tier 2 off to measure the read path rather than the hand-off. */
    public ShardOwnedQueueBuilder setLocalHandoffEnabled(boolean localHandoffEnabled) {
        this.localHandoffEnabled = localHandoffEnabled;
        return this;
    }


    /**
     * Identify the queue by name. Its id and shard count then come from the registry, so a caller has
     * nowhere to supply a shard count that could disagree with the one the queue was created with.
     * <p>
     * The name must already be registered — see
     * {@code ShardOwnedSchema.registerQueue(DataSource, QueueName, int)}. Registering here instead
     * would mean guessing a shard count on a caller's behalf, and the wrong guess is exactly the
     * failure this is meant to prevent.
     */
    public ShardOwnedQueueBuilder setQueueName(QueueName queueName) {
        this.queueName = requireNonNull(queueName, "No queueName provided");
        return this;
    }

    private void resolveQueueName() {
        if (queueName == null) {
            return;
        }
        requireNonNull(dataSource, "No dataSource provided");
        try {
            var registered = ShardOwnedSchema.resolve(dataSource, queueName)
                                             .orElseThrow(() -> new IllegalStateException(
                                                     "Queue '" + queueName + "' is not registered. Call "
                                                     + "ShardOwnedSchema.registerQueue(dataSource, queueName, shardCount) "
                                                     + "first — building a queue cannot invent a shard count for it"));
            this.queueId = registered.queueId();
            this.shardCount = registered.shardCount();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve queue '" + queueName + "'", e);
        }
    }

    public ShardOwnedQueue build() {
        resolveQueueName();
        var queue = metrics != null
                    ? new ShardOwnedQueue(dataSource, queueId, shardCount, instanceId, metrics)
                    : new ShardOwnedQueue(dataSource, queueId, shardCount, instanceId);
        if (runtime != null) {
            queue.useRuntime(runtime);
        }
        queue.setParallelConsumers(parallelConsumers);
        queue.setLocalHandoffEnabled(localHandoffEnabled);
        return queue;
    }
}
