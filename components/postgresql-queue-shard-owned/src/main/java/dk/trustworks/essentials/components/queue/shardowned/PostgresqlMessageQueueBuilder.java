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
 * Builder for {@link PostgresqlMessageQueue}, the {@code MessageQueue} implementation.
 * <p>
 * Same reasoning as {@link ShardOwnedQueueBuilder}: the identifying arguments are a {@code short}, an
 * {@code int} and a {@code String} in a row, and getting their order wrong is a mistake the compiler
 * cannot see.
 */
public final class PostgresqlMessageQueueBuilder {
    private DataSource         dataSource;
    private short              queueId;
    private int                shardCount;
    private String             instanceId;
    private QueueName          queueName;
    private ShardOwnerSettings settings = ShardOwnerSettings.defaults();

    public PostgresqlMessageQueueBuilder setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    public PostgresqlMessageQueueBuilder setQueueId(short queueId) {
        this.queueId = queueId;
        return this;
    }

    /**
     * Fixed at schema creation: changing it re-routes keys and breaks ordering for in-flight work.
     */
    public PostgresqlMessageQueueBuilder setShardCount(int shardCount) {
        this.shardCount = shardCount;
        return this;
    }

    public PostgresqlMessageQueueBuilder setInstanceId(String instanceId) {
        this.instanceId = instanceId;
        return this;
    }

    /**
     * Engine tuning shared by this queue's consumers. Defaults to {@link ShardOwnerSettings#defaults()}.
     */
    public PostgresqlMessageQueueBuilder setSettings(ShardOwnerSettings settings) {
        this.settings = settings;
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
    public PostgresqlMessageQueueBuilder setQueueName(QueueName queueName) {
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

    @SuppressWarnings("removal")
    public PostgresqlMessageQueue build() {
        resolveQueueName();
        return new PostgresqlMessageQueue(dataSource, queueId, shardCount, instanceId, settings);
    }
}
