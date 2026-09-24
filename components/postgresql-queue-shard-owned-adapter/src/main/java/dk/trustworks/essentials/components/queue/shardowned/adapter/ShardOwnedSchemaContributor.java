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
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicReference;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The shard-owned engine's schema as an {@link EssentialsSchemaHarness} contribution. The engine itself depends on
 * nothing but {@code shared}, so it exposes its DDL as plain statements - {@link ShardOwnedSchema#schemaStatements()}
 * and {@link ShardOwnedSchema#queueSequenceStatements(short, int)} - and this class carries them into the harness.
 * <p>
 * The fixed schema is one change. Each queue's sequences depend on the id the registry allocates when the queue is
 * registered, so they cannot be described up front: register queues through {@link #registerQueue} (or pass
 * {@link #queueDdl()} to {@link ShardOwnedSchema}'s registration methods yourself), and each queue's sequences become a
 * change of this contributor, handed to the harness' applier as the queue registers. Registration writes to the
 * registry table, which is part of the fixed schema, so queues are registered once the harness has run.
 * <p>
 * Without a harness, keep calling {@link ShardOwnedSchema#initialize(DataSource)} and
 * {@link ShardOwnedSchema#registerQueue(DataSource, QueueName, int, int)}; they create everything themselves, as
 * before.
 */
public final class ShardOwnedSchemaContributor implements DynamicSchemaContributor {
    /**
     * The {@link #moduleId()} the engine's schema is recorded under
     */
    public static final String MODULE_ID = "postgresql-queue-shard-owned";

    private final ConcurrentSkipListMap<Short, List<String>> queueSequences = new ConcurrentSkipListMap<>();
    private final AtomicReference<SchemaChangeSink>          sink           = new AtomicReference<>();

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public int order() {
        return SchemaOrder.ORDER_QUEUES;
    }

    /**
     * The engine's fixed schema, and the sequences of every queue registered through {@link #queueDdl()} so far.
     */
    @Override
    public List<SchemaChange> contribute(SchemaContext context) {
        var changes = new ArrayList<SchemaChange>(queueSequences.size() + 1);
        changes.add(SchemaChange.repeatable("engine-schema",
                                            ShardOwnedSchema.REGISTRY_TABLE,
                                            ShardOwnedSchema.schemaStatements().toArray(String[]::new)));
        queueSequences.forEach((queueId, statements) -> changes.add(queueSequencesChange(queueId, statements)));
        return changes;
    }

    @Override
    public void attach(SchemaChangeSink sink) {
        this.sink.set(requireNonNull(sink, "No sink provided"));
    }

    /**
     * @return the executor to hand {@link ShardOwnedSchema}'s registration methods, so a queue's sequences become a
     * change of this contributor instead of being created directly
     */
    public ShardOwnedSchema.QueueDdlExecutor queueDdl() {
        return (queueId, statements) -> {
            // Replaced, not merged: after growShardCount the statements cover every shard, old and new, and that
            // latest set is the one the ledger should record
            queueSequences.put(queueId, List.copyOf(statements));
            var current = sink.get();
            if (current != null) {
                current.apply(List.of(queueSequencesChange(queueId, statements)));
            }
        };
    }

    /**
     * {@link ShardOwnedSchema#registerQueue(DataSource, QueueName, int, int, ShardOwnedSchema.QueueDdlExecutor)} with
     * {@link #queueDdl()}.
     */
    public ShardOwnedSchema.RegisteredQueue registerQueue(DataSource dataSource, QueueName name, int shardCount, int orderedUnits) throws SQLException {
        return ShardOwnedSchema.registerQueue(dataSource, name, shardCount, orderedUnits, queueDdl());
    }

    /**
     * {@link ShardOwnedSchema#growShardCount(DataSource, QueueName, int, ShardOwnedSchema.QueueDdlExecutor)} with
     * {@link #queueDdl()}.
     */
    public ShardOwnedSchema.RegisteredQueue growShardCount(DataSource dataSource, QueueName name, int newShardCount) throws SQLException {
        return ShardOwnedSchema.growShardCount(dataSource, name, newShardCount, queueDdl());
    }

    private static SchemaChange queueSequencesChange(short queueId, List<String> statements) {
        return SchemaChange.repeatable("queue-sequences", "shard_queue_q" + queueId, statements.toArray(String[]::new));
    }
}
