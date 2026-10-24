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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import dk.trustworks.essentials.components.foundation.schema.*;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema;
import dk.trustworks.essentials.components.queue.shardowned.spi.QueueName;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

/**
 * The shard-owned engine's schema as an {@link EssentialsSchemaHarness} contribution.
 *
 * @deprecated moved to the engine, which now depends on {@code foundation}: use
 * {@link dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchemaContributor}, which this delegates to
 */
@Deprecated(forRemoval = true, since = "0.60.1")
public final class ShardOwnedSchemaContributor implements DynamicSchemaContributor {
    /**
     * @deprecated use {@link dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchemaContributor#MODULE_ID}
     */
    @Deprecated(forRemoval = true, since = "0.60.1")
    public static final String MODULE_ID = dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchemaContributor.MODULE_ID;

    private final dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchemaContributor delegate;

    /**
     * @param dataSource the engine's database
     * @deprecated use {@link dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchemaContributor#ShardOwnedSchemaContributor(DataSource)}
     */
    @Deprecated(forRemoval = true, since = "0.60.1")
    public ShardOwnedSchemaContributor(DataSource dataSource) {
        this.delegate = new dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchemaContributor(dataSource);
    }

    @Override
    public String moduleId() {
        return delegate.moduleId();
    }

    @Override
    public int order() {
        return delegate.order();
    }

    @Override
    public List<SchemaChange> contribute(SchemaContext context) {
        return delegate.contribute(context);
    }

    @Override
    public void attach(SchemaChangeSink sink) {
        delegate.attach(sink);
    }

    public ShardOwnedSchema.QueueDdlExecutor queueDdl() {
        return delegate.queueDdl();
    }

    public ShardOwnedSchema.RegisteredQueue registerQueue(QueueName name, int shardCount, int orderedUnits) throws SQLException {
        return delegate.registerQueue(name, shardCount, orderedUnits);
    }

    public ShardOwnedSchema.RegisteredQueue growShardCount(QueueName name, int newShardCount) throws SQLException {
        return delegate.growShardCount(name, newShardCount);
    }
}
